(ns rdm-spec.kernel.plugins
  "Plugins (dhf/spec/kernel/plugins.feature; ADR 0003): the project's own
  judges, declared in its configuration's :plugins in the order they run.
  A subscriber is given each event it subscribes to that the task published,
  after the task's own stages; an answerer is asked: a :Gate before fence and
  verify, a :Check as one of verify's checks.

  One interface for every plugin: the event or the request as EDN on its
  standard input; its working directory the project's root (the checkout's,
  in verify); RDM_SPEC set as the oracle's is; its own time limit (:seconds,
  60 by default); exit 0 holds, 1 findings, 2 cannot run; its error stream's
  lines relayed unchanged, its standard output kept as notes.

  plugin add copies a template rdm-spec carries: from its sources, plugins/
  beside src/; as a package, the classpath resources under rdm-spec/plugins/."
  (:refer-clojure :exclude [run!])
  (:require [babashka.classpath :as classpath]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def default-seconds 60)

(def requests #{:Gate :Check})

(def entry-keys #{:name :command :subscribes :answers :seconds})

(defn seconds-of [plugin] (or (:seconds plugin) default-seconds))

;; ---------------------------------------------------------------------------
;; Declarations

(defn- under? [path entry]
  (let [e (str/replace (str entry) #"/+$" "")
        p (str/replace (str path) #"^(\./)+" "")]
    (and (seq e) (or (= p e) (str/starts-with? p (str e "/"))))))

(defn- entry-problem
  "Why one entry cannot be trusted, or nil."
  [config e]
  (let [n (when (map? e) (:name e))
        who (str "the plugin " (if (and (string? n) (seq n)) n (pr-str e)))
        events (sort (map name (keys bus/catalogue)))]
    (cond
      (not (map? e)) (str who " is not a map: a plugin is declared as {:name :command :subscribes or :answers :seconds}")
      (not (and (string? n) (not (str/blank? n)))) (str who " has no :name: a plugin is named by a string")
      (seq (remove entry-keys (keys e)))
      (str who " declares " (str/join ", " (map str (sort (remove entry-keys (keys e)))))
           ": a plugin declares exactly :name, :command, :subscribes or :answers, and :seconds")
      (and (not (contains? e :subscribes)) (not (contains? e :answers)))
      (str who ": a plugin subscribes to events or answers a request")
      (and (contains? e :subscribes) (contains? e :answers))
      (str who ": a plugin subscribes to events or answers a request, not both")
      (and (contains? e :subscribes) (not (and (vector? (:subscribes e)) (seq (:subscribes e)) (every? keyword? (:subscribes e)))))
      (str who ": :subscribes is a vector of event names, such as [:SpecParsed]")
      (and (contains? e :subscribes) (some #(not (contains? bus/catalogue %)) (:subscribes e)))
      (let [bad (first (remove #(contains? bus/catalogue %) (:subscribes e)))]
        (str who ": rdm-spec publishes no event " (name bad) "; the events it publishes are " (str/join ", " events)))
      (and (contains? e :answers) (not (requests (:answers e))))
      (str who " answers " (pr-str (:answers e)) ": the requests are :Gate and :Check")
      (not (and (vector? (:command e)) (seq (:command e)) (every? string? (:command e))))
      (str who " has no :command: a plugin's command is a vector of strings")
      (and (contains? e :seconds) (not (and (number? (:seconds e)) (pos? (:seconds e)))))
      (str who " has :seconds " (pr-str (:seconds e)) ": its time limit is a positive number of seconds")
      (some (fn [w] (some #(under? w %) (:implementation config))) (:command e))
      (str who " runs " (pr-str (:command e)) ", under the implementation's paths ("
           (str/join ", " (:implementation config)) "): a plugin is a judge the builder never writes"))))

(defn problem
  "Why the configuration's :plugins cannot be trusted, or nil."
  [config]
  (let [ps (:plugins config)]
    (cond
      (nil? ps) nil
      (not (vector? ps)) "the configuration's :plugins is not a vector of plugins"
      :else (or (first (keep #(entry-problem config %) ps))
                (when-let [n (first (for [[n c] (frequencies (map :name ps)) :when (> c 1)] n))]
                  (str "the plugin " n " is declared twice: a plugin's name is declared once"))))))

(defn validate!
  "Refuses the task, before anything runs, when a declaration cannot be trusted."
  [config]
  (when-let [why (problem config)] (cmd/cannot-run! why)))

;; ---------------------------------------------------------------------------
;; Running one plugin

(defn- edn-str [x] (binding [*print-namespace-maps* false *print-length* nil *print-level* nil] (pr-str x)))

(defn run!
  "Runs plugin with input as EDN on its standard input, in :dir: {:exit :out
  :err :seconds}, with :timed-out (stopped, with everything it started, past
  its limit) or :not-found."
  [plugin input {:keys [dir]}]
  (let [t0 (System/currentTimeMillis)
        took #(/ (- (System/currentTimeMillis) t0) 1000.0)]
    (try
      (let [pr (p/process (:command plugin)
                          {:dir (str dir) :in (edn-str input) :out :string :err :string
                           :extra-env {"RDM_SPEC" (cmd/this-command-line)}})
            r (deref pr (long (* 1000 (seconds-of plugin))) ::timeout)]
        (if (= r ::timeout)
          (do (p/destroy-tree pr)
              (let [d (deref pr 5000 nil)]
                {:timed-out true :exit nil :out (str (:out d)) :err (str (:err d)) :seconds (took)}))
          {:exit (:exit r) :out (str (:out r)) :err (str (:err r)) :seconds (took)}))
      (catch java.io.IOException e
        {:not-found true :exit nil :out "" :err (ex-message e) :seconds (took)}))))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s*\r?\n\s*" " ")))

(defn- warning-line? [l] (or (str/starts-with? l "warning: ") (str/includes? l ": warning: ")))

(defn relay
  "What a plugin's run says, as the task's own report: {:findings :warnings
  :notes}, or a refusal when it cannot run."
  [plugin {:keys [exit out err timed-out not-found]}]
  (let [n (:name plugin)
        ls (remove str/blank? (str/split-lines (str err)))
        printed (one-line (str out " " err))
        relayed (fn [l] {:line-text l})
        warnings (mapv relayed (filter warning-line? ls))
        others (remove warning-line? ls)]
    (cond
      not-found (cmd/cannot-run! (str "the plugin " n " cannot be started: its command " (pr-str (:command plugin))
                                      " was not found"))
      timed-out (cmd/cannot-run! (str "the plugin " n " timed out after " (seconds-of plugin) " seconds and was stopped"
                                      (when (seq printed) (str "; it printed: " printed))))
      (= 2 exit) (cmd/cannot-run! (str "the plugin " n " cannot run"
                                      (if (str/blank? err) " (it exited 2)" (str ": " (one-line err)))))
      (= 1 exit) {:findings (if (seq others)
                              (mapv relayed others)
                              [(cmd/finding nil nil "plugin-failed" (str "the plugin " n " exited 1 and wrote no finding"))])
                  :warnings warnings :notes out}
      (= 0 exit) {:findings []
                  :warnings (into warnings (for [l others] (cmd/finding nil nil "plugin-output" (str n ": " l))))
                  :notes out}
      :else (cmd/cannot-run! (str "the plugin " n " exited " exit ": a plugin exits 0, 1 or 2"
                                  (when (seq printed) (str "; it printed: " printed)))))))

(defn ask
  "Asks plugin with input in dir and relays its answer: {:findings :warnings
  :notes :exit :seconds}; a refusal when it cannot run."
  [plugin input dir]
  (let [r (run! plugin input {:dir dir})]
    (merge (relay plugin r) (select-keys r [:exit :seconds]))))

;; ---------------------------------------------------------------------------
;; Subscribers and answerers

(defn subscribers-stage
  "Each subscriber, in the order declared, given every event it subscribes
  to that the task published (:published, [[event payload] ...]), in order."
  [{:keys [root config published]}]
  (let [runs (for [pl (:plugins config)
                   :when (:subscribes pl)
                   [e payload] published
                   :when (some #{e} (:subscribes pl))]
               (let [input (merge {:event e} (select-keys payload (get-in bus/catalogue [e :carries])))
                     a (ask pl input root)]
                 (assoc a :report {:plugin (:name pl) :event e :exit (:exit a) :seconds (seconds-of pl)
                                   :notes (str (:notes a))})))
        runs (vec runs)]
    {:findings (vec (mapcat :findings runs))
     :warnings (vec (mapcat :warnings runs))
     :result {:plugins (mapv :report runs)}}))

(defn gate-input
  "The Gate request for the task the context runs."
  [{:keys [task args cut cycle]}]
  (case task
    "fence" {:request :Gate :task :fence :cut (second args)}
    "verify" {:request :Gate :task :verify :cut cut :cycle cycle}
    {:request :Gate :task (keyword task)}))

(defn gate-stage
  "The plugins answering :Gate, in the order declared, before the task; the
  first that fails stops it with its findings."
  [{:keys [root config] :as ctx}]
  (loop [ps (filter #(= :Gate (:answers %)) (:plugins config)) warnings []]
    (if-let [pl (first ps)]
      (let [a (ask pl (gate-input ctx) root)]
        (if (seq (:findings a))
          {:findings (:findings a) :warnings (into warnings (:warnings a))}
          (recur (rest ps) (into warnings (:warnings a)))))
      {:warnings warnings})))

;; ---------------------------------------------------------------------------
;; Templates

(defn- read-template-edn [bytes]
  (try (let [t (edn/read-string (String. ^bytes bytes "UTF-8"))] (when (map? t) t)) (catch Exception _ nil)))

(defn dir-templates
  "{name {:template :version :files {path bytes}}} of the templates in dir."
  [dir]
  (into (sorted-map)
        (for [t (when (fs/directory? dir) (fs/list-dir dir))
              :when (fs/directory? t)
              :let [te (fs/file t "template.edn")]
              :when (fs/regular-file? te)
              :let [tpl (read-template-edn (fs/read-all-bytes te))]
              :when tpl]
          [(str (fs/file-name t))
           {:template tpl :version (:version tpl)
            :files (into (sorted-map) (for [f (fs/glob t "**" {:hidden true}) :when (fs/regular-file? f)]
                                        [(str (fs/relativize t f)) (fs/read-all-bytes f)]))}])))

(def resource-prefix "rdm-spec/plugins/")

(defn- entry-bytes [^java.util.jar.JarFile jf entry]
  (with-open [in (.getInputStream jf entry)]
    (let [out (java.io.ByteArrayOutputStream.)] (io/copy in out) (.toByteArray out))))

(defn jar-templates
  "The templates a package carries as classpath resources under rdm-spec/plugins/."
  [jar]
  (with-open [jf (java.util.jar.JarFile. (str jar))]
    (let [files (for [e (enumeration-seq (.entries jf))
                      :let [n (.getName ^java.util.jar.JarEntry e)]
                      :when (and (str/starts-with? n resource-prefix) (not (str/ends-with? n "/")))
                      :let [[t rel] (str/split (subs n (count resource-prefix)) #"/" 2)]
                      :when (seq rel)]
                  [t rel (entry-bytes jf e)])
          by-t (group-by first files)]
      (into (sorted-map)
            (for [[t fs'] by-t
                  :let [m (into (sorted-map) (map (fn [[_ rel b]] [rel b]) fs'))
                        tpl (some-> (get m "template.edn") read-template-edn)]
                  :when tpl]
              [t {:template tpl :version (:version tpl) :files m}])))))

(defn carried-templates
  "The templates this rdm-spec carries, however it was installed: the
  classpath's rdm-spec/plugins/ (a package, or a resources directory), else
  plugins/ beside the src/ it runs from."
  []
  (let [entries (map #(fs/absolutize %) (remove str/blank? (str/split (str (classpath/get-classpath)) #":")))]
    (or (first (for [e entries
                     :let [ts (cond
                                (and (fs/regular-file? e) (str/ends-with? (str e) ".jar")) (jar-templates e)
                                (fs/directory? (fs/path e resource-prefix)) (dir-templates (fs/path e resource-prefix))
                                :else nil)]
                     :when (seq ts)]
                 ts))
        (first (for [e entries
                     :when (and (fs/directory? e) (= "src" (str (fs/file-name e))))
                     :let [ts (dir-templates (fs/path (fs/parent e) "plugins"))]
                     :when (seq ts)]
                 ts))
        {})))

(defn- listing [ts]
  (str/join ", " (for [[n t] ts] (str n " (version " (:version t) ")"))))

(defn add-stage
  "rdm-spec plugin add NAME: copies the template into dhf/harness/plugins/NAME,
  unchanged, with origin.edn; never overwrites, never guesses."
  [{:keys [root args]}]
  (let [[verb n] args]
    (when-not (= "add" verb)
      (cmd/cannot-run! (str "rdm-spec plugin has no command " verb ": usage: rdm-spec plugin add NAME")))
    (let [ts (carried-templates)
          t (get ts n)
          rel (str "dhf/harness/plugins/" n)
          dest (fs/path root rel)]
      (cond
        (empty? ts) (cmd/cannot-run! "this rdm-spec carries no plugin template")
        (nil? t) (cmd/cannot-run! (str "rdm-spec carries no template " n "; the templates it carries are " (listing ts)))
        (fs/exists? dest)
        {:findings [(cmd/finding rel nil "plugin-exists"
                                 (str rel " already exists: plugin add never overwrites it, and copied nothing"))]}
        :else
        (do (doseq [[p b] (:files t)]
              (let [f (fs/path dest p)]
                (fs/create-dirs (fs/parent f))
                (fs/write-bytes f b)))
            (spit (fs/file dest "origin.edn")
                  (str (edn-str {:template n :version (:version t) :copied (str (java.time.LocalDate/now))}) "\n"))
            {:result {:plugin {:added n :version (:version t) :directory rel
                               :declare (:plugin (:template t))
                               :note "add :declare to the configuration's :plugins, in the order it runs"}}})))))
