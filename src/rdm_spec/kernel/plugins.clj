(ns rdm-spec.kernel.plugins
  "The plugins (dhf/spec/kernel/plugins.feature; ADR 0003): judges a project
  keeps for itself, each a command declared in the configuration's :plugins
  in the order it runs. A subscriber is given each event it subscribes to as
  EDN on its standard input, after the task's own stages; an answerer is
  asked a request: :Gate before fence and verify, :Check as one of verify's
  checks. A plugin reports as a task does: findings on its error stream, exit
  0 holds, 1 findings, 2 cannot run; past its :seconds (60 by default) it is
  stopped, every process it started with it. It runs with RDM_SPEC and its
  :env added to the caller's environment.

  Templates rdm-spec carries (plugins/ beside the sources, or rdm-spec/plugins/
  in a package) are copied by \"plugin add\" and are then the project's own."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def requests #{:Gate :Check})
(def entry-keys #{:name :command :subscribes :answers :seconds :env})

(defn declared
  "The configuration's plugins, each with its :seconds and :env."
  [config]
  (vec (for [p (:plugins config)] (merge {:seconds 60 :env {}} p))))

(defn- impl-path? [config s]
  (let [s (str/replace (str s) #"^\./" "")]
    (some (fn [p] (let [p (str/replace (str p) #"/+$" "")] (or (= s p) (str/starts-with? s (str p "/")))))
          (:implementation config))))

(defn validate!
  "Refuses, before anything runs, a declaration rdm-spec cannot trust."
  [_root config]
  (let [ps (:plugins config)
        events (sort (map name (keys bus/catalogue)))]
    (when (and ps (not (and (sequential? ps) (every? map? ps))))
      (cmd/cannot-run! ":plugins is a vector of maps, one per plugin"))
    (doseq [[n c] (frequencies (map :name ps)) :when (> c 1)]
      (cmd/cannot-run! (str "the plugin " n " is declared " c " times: a plugin's name is declared once")))
    (doseq [{:keys [name command subscribes answers seconds env] :as p} ps
            :let [no (fn [why] (cmd/cannot-run! (str "the plugin " (or name (pr-str p)) " cannot be trusted: " why)))]]
      (when-not (and (string? name) (seq name)) (no "a plugin has a :name"))
      (when-let [extra (seq (remove entry-keys (keys p)))]
        (no (str "it declares " (str/join ", " extra) ", which a plugin's entry does not hold: an entry is :name, :command, :subscribes or :answers, :seconds and :env")))
      (when-not (and (vector? command) (seq command) (every? string? command))
        (no "its :command is a vector of strings"))
      (cond
        (and (nil? subscribes) (nil? answers)) (no "a plugin subscribes to events or answers a request")
        (and subscribes answers) (no "a plugin subscribes to events or answers a request, not both"))
      (when subscribes
        (when-not (and (vector? subscribes) (every? keyword? subscribes))
          (no ":subscribes is a vector of event names"))
        (doseq [e subscribes :when (not (contains? bus/catalogue e))]
          (no (str "rdm-spec publishes no event " (clojure.core/name e) "; it publishes " (str/join ", " events)))))
      (when (and answers (not (requests answers)))
        (no (str "it answers " (pr-str answers) ", and the requests are :Gate and :Check")))
      (when (and (some? seconds) (not (and (number? seconds) (pos? seconds))))
        (no ":seconds is its time limit, a number of seconds"))
      (when (and (some? env) (not (and (map? env) (every? string? (keys env)) (every? string? (vals env)))))
        (no ":env is a map of strings to strings"))
      (when (some #(impl-path? config %) command)
        (no (str "its command " (pr-str command) " is under the implementation's paths: a plugin is a judge the builder never writes"))))))

;; ---------------------------------------------------------------------------
;; Running one

(defn- edn-str [x] (binding [*print-namespace-maps* false] (pr-str x)))

(defn run-plugin
  "Runs a plugin in dir, given input as EDN."
  [plugin dir input]
  (cmd/run-process (:command plugin)
                   {:dir dir :in (edn-str input) :seconds (:seconds plugin 60)
                    :env (merge (:env plugin) {"RDM_SPEC" (cmd/this-command-line)})}))

(defn- lines [s] (vec (remove str/blank? (str/split-lines (str s)))))

(defn report-of
  "What a plugin said: {:outcome :passed|:failed|:refused :findings :warnings :notes :why}."
  [plugin r]
  (let [n (:name plugin)
        said (str/trim (str (:out r) " " (:err r)))]
    (cond
      (:not-found r)
      {:outcome :refused :findings [] :warnings []
       :why (str "the plugin " n " cannot be started: its command " (pr-str (:command plugin)) " was not found")}
      (:timed-out r)
      {:outcome :refused :findings [] :warnings []
       :why (str "the plugin " n " timed out after " (:seconds plugin 60) " seconds and was stopped"
                 (when (seq said) (str "; it printed: " said)))}
      (#{0 1} (:exit r))
      (let [ls (lines (:err r))
            warning? #(str/includes? % "warning:")]
        {:outcome (if (= 0 (:exit r)) :passed :failed)
         :findings (if (= 1 (:exit r)) (mapv (fn [l] {:raw l}) (remove warning? ls)) [])
         :warnings (mapv (fn [l] {:raw l}) (if (= 1 (:exit r)) (filter warning? ls) ls))
         :notes (lines (:out r))})
      :else
      {:outcome :refused :findings [] :warnings []
       :why (str "the plugin " n " cannot run (it exited " (:exit r) ")" (when (seq said) (str ": " said)))})))

(defn ask
  "Runs a plugin on input in dir: its report."
  [plugin dir input]
  (report-of plugin (run-plugin plugin dir input)))

;; ---------------------------------------------------------------------------
;; Answerers

(defn answering [config request]
  (filterv #(= request (:answers %)) (declared config)))

(defn gates
  "Asks every :Gate plugin, in order, stopping at the first that fails: a
  stage's result. A gate that cannot run stops the task."
  [{:keys [root config]} request]
  (loop [ps (answering config :Gate) acc {:findings [] :warnings []}]
    (if-let [p (first ps)]
      (let [r (ask p root (merge {:request :Gate} request))]
        (when (= :refused (:outcome r)) (cmd/cannot-run! (:why r)))
        (let [acc (-> acc (update :findings into (:findings r)) (update :warnings into (:warnings r)))]
          (if (= :failed (:outcome r))
            (cond-> acc (empty? (:findings acc)) (update :findings conj
                                                         (cmd/finding nil nil "gate-failed" (str "the plugin " (:name p) " answering :Gate failed"))))
            (recur (rest ps) acc))))
      acc)))

(defn fence-gate-stage
  "The :Gate plugins, before the fence builds anything."
  [{:keys [args] :as ctx}]
  (gates ctx {:task :fence :cut (second args)}))

;; ---------------------------------------------------------------------------
;; Subscribers

(defn subscribe!
  "Subscribes each subscriber to its events on the bus, for the end of the
  task: the deliveries, kept in the order published."
  [{:keys [config]}]
  (let [subs (filterv :subscribes (declared config))]
    (when (seq subs)
      (let [got (atom [])]
        (doseq [e (distinct (mapcat :subscribes subs))]
          (bus/subscribe! e :kernel/plugins (fn [payload] (swap! got conj [e payload]))))
        {:plugins subs :got got}))))

(defn run-subscribers!
  "Runs each subscriber, in the order declared, on every event it subscribes
  to that the task published, in the project's root."
  [{:keys [root]} {:keys [plugins got]}]
  (reduce (fn [acc p]
            (reduce (fn [acc [e payload]]
                      (if (some #{e} (:subscribes p))
                        (let [r (ask p root (merge {:event e} payload))]
                          (when (= :refused (:outcome r)) (cmd/cannot-run! (:why r)))
                          (-> acc
                              (update :findings into (:findings r))
                              (update :warnings into (:warnings r))
                              (cond-> (seq (:notes r)) (update-in [:result :plugins (:name p)] (fnil into []) (:notes r)))))
                        acc))
                    acc @got))
          {:findings [] :warnings [] :result {}}
          plugins))

;; ---------------------------------------------------------------------------
;; Templates

(defn- classpath [] (str/split (str (System/getProperty "java.class.path")) #":"))

(defn templates
  "{name {:version :plugin :files {path bytes}}} of the templates rdm-spec carries."
  []
  (let [found (atom {})
        add! (fn [rel bytes]
               (let [[n & more] (str/split rel #"/")]
                 (when (and (seq n) (seq more))
                   (swap! found assoc-in [n :files (str/join "/" more)] bytes))))]
    (doseq [e (classpath) :when (seq e)]
      (let [f (fs/path e)]
        (cond
          (and (fs/directory? f) (fs/exists? (fs/path f "rdm_spec/kernel/command.clj"))
               (fs/directory? (fs/path (fs/parent (fs/absolutize f)) "plugins")))
          (let [d (fs/path (fs/parent (fs/absolutize f)) "plugins")]
            (doseq [x (fs/glob d "**") :when (fs/regular-file? x)]
              (add! (str (fs/relativize d x)) (fs/read-all-bytes x))))
          (and (fs/directory? f) (fs/directory? (fs/path f "rdm-spec/plugins")))
          (let [d (fs/path f "rdm-spec/plugins")]
            (doseq [x (fs/glob d "**") :when (fs/regular-file? x)]
              (add! (str (fs/relativize d x)) (fs/read-all-bytes x))))
          (and (fs/regular-file? f) (str/ends-with? (str f) ".jar"))
          (with-open [z (java.util.zip.ZipFile. (str f))]
            (doseq [entry (enumeration-seq (.entries z))
                    :let [n (.getName entry)]
                    :when (and (str/starts-with? n "rdm-spec/plugins/") (not (.isDirectory entry)))]
              (add! (subs n (count "rdm-spec/plugins/"))
                    (with-open [in (.getInputStream z entry)] (.readAllBytes in))))))))
    (into (sorted-map)
          (for [[n t] @found
                :let [te (some-> (get-in t [:files "template.edn"]) (String. "UTF-8") edn/read-string)]
                :when te]
            [n (assoc t :version (:version te) :plugin (:plugin te))]))))

(defn add-stage
  "plugin add NAME: copies a template into dhf/harness/plugins/NAME, never
  over anything, and says how to declare it."
  [{:keys [root args]}]
  (let [[verb n] args]
    (when-not (= "add" verb) (cmd/cannot-run! "usage: rdm-spec plugin add NAME"))
    (let [ts (templates)
          t (or (get ts n)
                (cmd/cannot-run! (str "rdm-spec carries no template " n ": it carries "
                                      (if (seq ts) (str/join ", " (for [[k v] ts] (str k " (version " (:version v) ")"))) "none"))))
          rel (str "dhf/harness/plugins/" n)
          dir (fs/path root rel)]
      (if (fs/exists? dir)
        {:findings [(cmd/finding rel nil "plugin-exists"
                                 (str "the directory " rel " already exists: plugin add never overwrites, so nothing was copied"))]}
        (do
          (doseq [[p bytes] (:files t)]
            (let [f (fs/path dir p)]
              (fs/create-dirs (fs/parent f))
              (fs/write-bytes f bytes)
              (when (str/ends-with? p ".sh") (fs/set-posix-file-permissions f "rwxr-xr-x"))))
          (spit (fs/file dir "origin.edn")
                (edn-str {:template n :version (:version t) :copied (str (java.time.LocalDate/now))}))
          {:result {:plugin {:added n :version (:version t) :path rel
                             :entry (:plugin t)
                             :next (str "add the entry to :plugins in dhf/harness/config.edn: " (edn-str (:plugin t)))}}})))))
