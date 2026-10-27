(ns rdm-spec.kernel.plugins
  "Plugins (dhf/spec/kernel/plugins.feature; ADR 0003): the judges a project
  keeps for itself, declared in its configuration's :plugins in the order
  they run. A plugin subscribes to events rdm-spec publishes (its published
  language) and is given each as EDN on its standard input, after the core's
  own stages; or answers a request: :Gate before fence and verify, :Check as
  one of verify's checks. One interface: run in the project's root (the
  checkout's, for a Check) with RDM_SPEC set; findings one per line on its
  error stream, relayed unchanged; exit 0 holds, 1 findings, 2 cannot run;
  past its :seconds it is stopped and named as timed out, its output kept.
  `plugin add NAME` copies a template rdm-spec carries into the project."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def default-seconds 60)

(def requests #{:Gate :Check})

;; ---------------------------------------------------------------------------
;; Declarations

(defn declarations
  "The configuration's plugins, in order, each with its :seconds."
  [config]
  (mapv #(merge {:seconds default-seconds} %) (:plugins config)))

(defn- under? [path entry]
  (let [e (str/replace entry #"/$" "")]
    (or (= path e) (str/starts-with? path (str e "/")))))

(defn- problem
  "Why a declaration cannot be trusted, or nil."
  [config names {:keys [name command subscribes answers seconds] :as p}]
  (let [who (str "the plugin " (pr-str name))
        events (sort (map clojure.core/name (keys bus/catalogue)))]
    (cond
      (not (map? p)) (str "a plugin is declared as a map, not " (pr-str p))
      (not (string? name)) (str "a plugin declared as " (pr-str p) " has no :name")
      (> (get names name) 1) (str who " is declared " (get names name) " times: a plugin's name is declared once")
      (seq (remove #{:name :command :subscribes :answers :seconds} (keys p)))
      (str who " carries " (str/join ", " (remove #{:name :command :subscribes :answers :seconds} (keys p)))
           ": a plugin is declared with exactly :name, :command, :subscribes or :answers, and an optional :seconds")
      (not (and (vector? command) (seq command) (every? string? command)))
      (str who " names no command: :command is a vector of words")
      (and (nil? subscribes) (nil? answers)) (str who " neither subscribes nor answers: a plugin subscribes to events or answers a request")
      (and subscribes answers) (str who " both subscribes and answers: a plugin subscribes to events or answers a request, not both")
      (and subscribes (not (and (vector? subscribes) (seq subscribes) (every? keyword? subscribes))))
      (str who " subscribes to " (pr-str subscribes) ": :subscribes is a vector of event names")
      (some #(not (contains? bus/catalogue %)) subscribes)
      (let [e (first (remove #(contains? bus/catalogue %) subscribes))]
        (str who " subscribes to " e ", but rdm-spec publishes no event " (clojure.core/name e)
             "; the events it publishes are " (str/join ", " events)))
      (and answers (not (requests answers))) (str who " answers " (pr-str answers) ": the requests are :Gate and :Check")
      (and (some? seconds) (not (and (number? seconds) (pos? seconds))))
      (str who " has :seconds " (pr-str seconds) ": its time limit is a number of seconds")
      (some (fn [w] (some #(under? w %) (:implementation config))) command)
      (str who " runs " (first (filter (fn [w] (some #(under? w %) (:implementation config))) command))
           ", under the implementation's paths: a plugin is a judge the builder never writes"))))

(defn validate!
  "Refuses, before anything runs, a declaration rdm-spec cannot trust."
  [config]
  (let [ps (:plugins config)]
    (when-not (or (nil? ps) (vector? ps) (seq? ps))
      (cmd/cannot-run! ":plugins is a vector of plugins, in the order they run"))
    (let [names (frequencies (map #(when (map? %) (:name %)) ps))]
      (doseq [p ps]
        (when-let [why (problem config names p)] (cmd/cannot-run! why))))
    config))

;; ---------------------------------------------------------------------------
;; Running one plugin

(defn run
  "Runs a plugin in dir with input as EDN on its standard input:
  {:exit :out :err}, :timed-out past its :seconds (everything it started
  stopped), :not-found when its command cannot be started."
  [{:keys [command seconds]} input dir]
  (try
    (let [pr (p/process command {:dir (str dir) :in (pr-str input) :out :string :err :string
                                 :extra-env {"RDM_SPEC" (cmd/this-command-line)}})
          r (deref pr (long (* 1000 (or seconds default-seconds))) ::timeout)]
      (if (= ::timeout r)
        (do (p/destroy-tree pr)
            (let [d (deref pr 5000 nil)]
              {:timed-out true :out (str (:out d)) :err (str (:err d))}))
        {:exit (:exit r) :out (:out r) :err (:err r)}))
    (catch java.io.IOException e
      {:not-found true :exit 127 :out "" :err (ex-message e)})))

(defn- lines [s] (remove str/blank? (str/split-lines (str s))))

(defn- warning? [l] (boolean (re-find #"(^|: )warning: " l)))

(defn report
  "What a plugin's run becomes in the task's report: {:findings :warnings}
  of its lines, unchanged, or {:cannot-run why}."
  [{:keys [name command seconds]} {:keys [exit out err timed-out not-found]}]
  (let [said (concat (lines out) (lines err))]
    (cond
      not-found {:cannot-run (str "the plugin " name " cannot be started: its command " (pr-str command) " was not found")}
      timed-out {:cannot-run (str "the plugin " name " timed out after " (or seconds default-seconds)
                                  " seconds and was stopped"
                                  (when (seq said) (str "; it printed: " (str/join " / " said))))}
      (= 2 exit) {:cannot-run (str "the plugin " name " cannot run (exit 2)"
                                   (when (seq said) (str ": " (str/join " / " said))))}
      (not (#{0 1} exit)) {:cannot-run (str "the plugin " name " exited " exit ", which is neither 0, 1 nor 2"
                                            (when (seq said) (str ": " (str/join " / " said))))}
      :else
      (let [ls (lines err)
            findings (mapv (fn [l] {:line-text l}) (remove warning? ls))
            warnings (vec (concat (map (fn [l] {:line-text l}) (filter warning? ls))
                                  (map (fn [l] (cmd/finding nil nil "plugin-output" (str name ": " l))) (lines out))))]
        (if (= 1 exit)
          {:findings (if (seq findings) findings
                         [(cmd/finding "dhf/harness/config.edn" nil "plugin-findings"
                                       (str "the plugin " name " exited 1, naming no finding"))])
           :warnings warnings}
          {:findings [] :warnings (into (vec (map (fn [{:keys [line-text]}] {:line-text line-text}) findings)) warnings)})))))

(defn ask
  "Runs a plugin in dir with input, and reports it: {:findings :warnings} or
  {:cannot-run why}."
  [plugin input dir]
  (report plugin (run plugin input dir)))

;; ---------------------------------------------------------------------------
;; The command's stages

(defn- relay [{:keys [cannot-run] :as r}]
  (when cannot-run (cmd/cannot-run! cannot-run))
  r)

(defn subscribers-stage
  "After the core's own stages: each subscriber, in the order declared, is
  given each event it subscribes to that the task published."
  [{:keys [root config published]}]
  (let [ps (declarations config)
        rs (vec (for [pl ps :when (:subscribes pl)
                      [event payload] published :when (some #{event} (:subscribes pl))]
                  (relay (ask pl (merge (select-keys payload (get-in bus/catalogue [event :carries])) {:event event}) root))))]
    {:findings (vec (mapcat :findings rs))
     :warnings (vec (mapcat :warnings rs))
     :result {:plugins ps}}))

(defn gate-stage
  "Before fence and verify: every plugin answering :Gate, in the order
  declared, given the task it gates."
  [{:keys [root config task args cut cycle]}]
  (let [request (cond-> {:request :Gate :task (keyword task) :cut (or cut (second args))}
                  cycle (assoc :cycle cycle))
        rs (vec (for [pl (declarations config) :when (= :Gate (:answers pl))]
                  (relay (ask pl request root))))]
    {:findings (vec (mapcat :findings rs))
     :warnings (vec (mapcat :warnings rs))}))

(defn answering
  "The plugin named name answering request, or nil."
  [config name request]
  (first (filter #(and (= name (:name %)) (= request (:answers %))) (declarations config))))

;; ---------------------------------------------------------------------------
;; Templates

(defn- source-root
  "The directory rdm-spec runs from, when it runs from its sources."
  []
  (when-let [u (io/resource "rdm_spec/kernel/plugins.clj")]
    (when (= "file" (.getProtocol u))
      (-> (fs/path (.getPath u)) fs/parent fs/parent fs/parent fs/parent))))

(defn templates-dir
  "Where the templates rdm-spec carries are: plugins/ beside its sources."
  []
  (let [r (source-root)
        d (when r (fs/path r "plugins"))]
    (when (and d (fs/directory? d)) d)))

(defn template-names []
  (if-let [d (templates-dir)]
    (vec (sort (for [x (fs/list-dir d) :when (fs/exists? (fs/path x "template.edn"))] (str (fs/file-name x)))))
    []))

(defn add-stage
  "plugin add NAME: copies the template NAME into dhf/harness/plugins/NAME,
  unchanged, records its origin, and prints the :plugins entry to add."
  [{:keys [root args]}]
  (let [[verb name] args
        names (template-names)]
    (when-not (= "add" verb) (cmd/cannot-run! (str "plugin takes add, not " verb ": usage: rdm-spec plugin add NAME")))
    (when-not (some #{name} names)
      (cmd/cannot-run! (str "rdm-spec carries no template " name "; the templates it carries are "
                            (if (seq names) (str/join ", " names) "none"))))
    (let [rel (str "dhf/harness/plugins/" name)
          dest (fs/path root rel)
          src (fs/path (templates-dir) name)
          t (edn/read-string (slurp (fs/file src "template.edn")))]
      (if (fs/exists? dest)
        {:findings [(cmd/finding rel nil "plugin-exists"
                                 (str rel " already exists: plugin add never overwrites, and copied nothing"))]}
        (let [origin {:template name :version (:version t) :copied (str (java.time.LocalDate/now))}]
          (fs/create-dirs (fs/parent dest))
          (fs/copy-tree src dest)
          (spit (fs/file dest "origin.edn") (str (pr-str origin) "\n"))
          {:result {:plugin {:added name :dir rel :origin origin
                             :entry (:plugin t)
                             :next (str "add the entry to :plugins in dhf/harness/config.edn, in the order it runs")}}})))))
