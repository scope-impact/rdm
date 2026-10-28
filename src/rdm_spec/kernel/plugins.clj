(ns rdm-spec.kernel.plugins
  "Kernel component Plugins: runs the project's plugins, the judges it keeps
  for itself, declared in the configuration's :plugins in the order they run.
  A subscriber is given each event of rdm-spec's published language it
  subscribes to; an answerer is asked a request (:Gate before fence and
  verify, :Check as one of verify's checks). One interface for all: the
  event or request as EDN on standard input, the project's root as working
  directory, RDM_SPEC and the plugin's :env in its environment, its own time
  limit, and findings on its error stream. Templates rdm-spec carries are
  copied into a project by plugin add, and are then the project's own."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]))

(def default-seconds 60)
(def entry-keys #{:name :command :subscribes :answers :seconds :env})
(def requests #{:Gate :Check})

(defn declared
  "The configuration's plugins, in order, each with its :seconds and :env."
  [cfg]
  (vec (for [p (:plugins cfg)]
         (merge {:seconds default-seconds :env {}} (select-keys p entry-keys)))))

(defn- under-implementation? [cfg word]
  (let [w (str/replace (str word) #"^\./" "")]
    (some (fn [i] (let [i (str/replace (str i) #"/+$" "")]
                    (or (= w i) (str/starts-with? w (str i "/")))))
          (:implementation cfg))))

(defn validate!
  "Refuses (exit 2), before anything runs, a :plugins rdm-spec cannot trust."
  [cfg]
  (let [ps (:plugins cfg)
        refuse (fn [p why] (project/cannot-run! (str "the plugin " (pr-str (:name p)) " is refused: " why)))]
    (when (some? ps)
      (when-not (and (sequential? ps) (every? map? ps))
        (project/cannot-run! ":plugins is a vector of plugin maps, each {:name :command :subscribes|:answers}")))
    (doseq [p ps]
      (when-not (string? (:name p)) (refuse p "a plugin's :name is a string"))
      (when-let [extra (seq (remove entry-keys (keys p)))]
        (refuse p (str "a plugin is declared with :name, :command, :subscribes or :answers, :seconds and :env, not " (str/join ", " (map pr-str extra)))))
      (when-not (and (sequential? (:command p)) (seq (:command p)) (every? string? (:command p)))
        (refuse p "its :command is a vector of strings"))
      (let [s? (contains? p :subscribes) a? (contains? p :answers)]
        (cond
          (and s? a?) (refuse p "a plugin subscribes to events or answers a request, not both")
          (not (or s? a?)) (refuse p "a plugin subscribes to events or answers a request")))
      (when (contains? p :subscribes)
        (when-not (and (sequential? (:subscribes p)) (every? keyword? (:subscribes p)) (seq (:subscribes p)))
          (refuse p ":subscribes is a vector of event names"))
        (doseq [e (:subscribes p) :when (not (contains? bus/catalogue e))]
          (refuse p (str "rdm-spec publishes no event " (name e) "; the events it publishes are "
                         (str/join ", " (map name (bus/event-names)))))))
      (when (contains? p :answers)
        (when-not (requests (:answers p))
          (refuse p (str "it answers " (pr-str (:answers p)) ", and the requests are :Gate and :Check"))))
      (when (and (contains? p :seconds) (not (and (number? (:seconds p)) (pos? (:seconds p)))))
        (refuse p ":seconds is a positive number of seconds"))
      (when (and (contains? p :env) (not (and (map? (:env p)) (every? string? (keys (:env p))) (every? string? (vals (:env p))))))
        (refuse p ":env is a map of strings to strings"))
      (when (some #(under-implementation? cfg %) (:command p))
        (refuse p (str "its command " (pr-str (:command p)) " is under the implementation's paths, and a plugin is a judge the builder never writes"))))
    (doseq [[n c] (frequencies (map :name ps)) :when (> c 1)]
      (project/cannot-run! (str "the plugin " (pr-str n) " is declared " c " times: a plugin's name is declared once")))))

;; ------------------------------------------------------------------ which rdm-spec

(defn rdm-spec-command-line
  "A command line, words separated by spaces, starting this same rdm-spec
  however it was installed: from its sources or as a package."
  []
  (let [u (str (io/resource "rdm_spec/kernel/command.clj"))
        decode #(java.net.URLDecoder/decode ^String % "UTF-8")]
    (cond
      (str/starts-with? u "jar:file:") (str "bb " (decode (first (str/split (subs u 9) #"!"))))
      (str/starts-with? u "file:") (str "bb --classpath " (str (fs/parent (fs/parent (fs/parent (decode (subs u 5))))))
                                        " -m rdm-spec.kernel.command")
      :else "rdm-spec")))

;; ------------------------------------------------------------------ running one

(defn run-plugin
  "Runs PLUGIN with INPUT (EDN text) on its standard input in DIR; stops it at
  its time limit. {:exit :out :err :timed-out? :error}."
  [plugin input dir]
  (let [env (merge (project/env) (:env plugin) {"RDM_SPEC" (rdm-spec-command-line)})]
    (try
      (let [proc (p/process (vec (:command plugin)) {:dir (str dir) :env env :out :string :err :string})
            _ (future (try (with-open [w (io/writer (:in proc))] (.write w ^String input))
                           (catch java.io.IOException _ nil)))
            r (deref proc (long (* 1000 (:seconds plugin default-seconds))) ::timeout)]
        (if (= r ::timeout)
          (do (p/destroy-tree proc)
              (let [r (deref proc 5000 nil)]
                {:exit nil :out (str (:out r)) :err (str (:err r)) :timed-out? true}))
          {:exit (:exit r) :out (:out r) :err (:err r)}))
      (catch java.io.IOException e
        {:error (project/one-line (ex-message e))}))))

(defn- lines [s] (vec (remove str/blank? (str/split-lines (str s)))))

(defn- warning-line? [l] (boolean (re-find #"(^|: )warning: " l)))

(defn answer-report
  "What a plugin said, as the task's own report: its finding lines relayed
  unchanged, its warnings, its notes; a plugin that cannot run, or ran past
  its limit, stops the task."
  [plugin res]
  (let [who (str "the plugin " (pr-str (:name plugin)))
        said (str/join " " (concat (lines (:out res)) (lines (:err res))))]
    (cond
      (:error res) (project/cannot-run! (str who " cannot be started: its command " (pr-str (:command plugin)) " is not found ("
                                             (:error res) ")"))
      (:timed-out? res) (project/cannot-run! (str who " timed out after " (:seconds plugin) " seconds and was stopped"
                                                  (when-not (str/blank? said) (str "; it printed: " said))))
      (= 0 (:exit res)) {:findings []
                         :warnings (mapv project/raw (filter warning-line? (lines (:err res))))
                         :notes (lines (:out res))}
      (= 1 (:exit res)) (let [ls (lines (:err res))
                              fs (remove warning-line? ls)]
                          {:findings (if (seq fs) (mapv project/raw fs)
                                         [(project/finding (first (:command plugin)) "plugin-failed" (str who " exited 1 and named no finding"))])
                           :warnings (mapv project/raw (filter warning-line? ls))
                           :notes (lines (:out res))})
      (= 2 (:exit res)) (project/cannot-run! (str who " cannot run" (when-not (str/blank? said) (str ": " said))))
      :else (project/cannot-run! (str who " exited " (:exit res) (when-not (str/blank? said) (str ": " said)))))))

(defn run-subscribers!
  "Runs every plugin subscribing to an event among PUBLISHED, in the order
  declared, each event in the order published; their report."
  [cfg published]
  (let [rs (for [p (declared cfg) :when (:subscribes p)
                 {:keys [event data]} published :when (some #{event} (:subscribes p))]
             (answer-report p (run-plugin p (pr-str (merge {:event event} data)) (project/root))))]
    {:result nil
     :findings (vec (mapcat :findings rs))
     :warnings (vec (mapcat :warnings rs))}))

(defn answerers [cfg request] (filterv #(= request (:answers %)) (declared cfg)))

(defn gate!
  "Asks every plugin answering :Gate, in order, with REQUEST; stops at the
  first that fails. {:findings :warnings :passed?}."
  [cfg request]
  (loop [ps (answerers cfg :Gate) warnings []]
    (if (empty? ps)
      {:passed? true :findings [] :warnings warnings}
      (let [p (first ps)
            r (answer-report p (run-plugin p (pr-str request) (project/root)))]
        (if (seq (:findings r))
          {:passed? false :findings (:findings r) :warnings (into warnings (:warnings r))}
          (recur (rest ps) (into warnings (:warnings r))))))))

;; ------------------------------------------------------------------ templates

(defn- jar-templates [jar-path]
  (with-open [jf (java.util.jar.JarFile. ^String jar-path)]
    (reduce (fn [acc ^java.util.jar.JarEntry e]
              (let [n (.getName e)]
                (if-let [[_ t rel] (re-matches #"rdm-spec/plugins/([^/]+)/(.+)" n)]
                  (if (.isDirectory e)
                    acc
                    (assoc-in acc [t rel] (with-open [in (.getInputStream jf e)]
                                            (let [o (java.io.ByteArrayOutputStream.)] (io/copy in o) (.toByteArray o)))))
                  acc)))
            {}
            (enumeration-seq (.entries jf)))))

(defn- dir-templates [dir]
  (into {} (for [t (fs/list-dir dir) :when (fs/directory? t)]
             [(str (fs/file-name t))
              (into {} (for [f (fs/glob t "**") :when (fs/regular-file? f)]
                         [(str/replace (str (fs/relativize t f)) "\\" "/") (fs/read-all-bytes f)]))])))

(defn template-files
  "Every template rdm-spec carries: {name {path bytes}}. A package carries
  them as resources under rdm-spec/plugins/; run from sources, they are
  plugins/ beside src/."
  []
  (let [u (str (io/resource "rdm_spec/kernel/command.clj"))
        decode #(java.net.URLDecoder/decode ^String % "UTF-8")
        res (some-> (io/resource "rdm-spec/plugins") str)]
    (cond
      (str/starts-with? u "jar:file:") (jar-templates (decode (first (str/split (subs u 9) #"!"))))
      (and res (str/starts-with? res "file:")) (dir-templates (decode (subs res 5)))
      (str/starts-with? u "file:")
      (let [d (fs/path (fs/parent (fs/parent (fs/parent (fs/parent (decode (subs u 5)))))) "plugins")]
        (if (fs/directory? d) (dir-templates d) {}))
      :else {})))

(defn templates []
  (vec (sort-by :name
                (for [[n files] (template-files)
                      :let [t (some-> (get files "template.edn") (String. "UTF-8") edn/read-string)]
                      :when t]
                  (assoc t :name n :files files)))))

(defn- listing [ts] (str/join ", " (map #(str (:name %) " (version " (:version %) ")") ts)))

(defn plugin-task
  "plugin add NAME: copies a template into dhf/harness/plugins/NAME and records its origin."
  [{:keys [args]}]
  (let [[verb name] args
        ts (templates)
        t (first (filter #(= name (:name %)) ts))
        dest (str "dhf/harness/plugins/" name)]
    (cond
      (not= "add" verb) (project/cannot-run! (str "unknown plugin task " verb ": rdm-spec plugin add NAME"))
      (nil? t) (project/cannot-run! (str "rdm-spec carries no template " name "; the templates it carries are " (listing ts)))
      (fs/exists? (project/abs dest))
      (project/report {} [(project/finding dest "plugin-exists" (str dest " already exists; plugin add never overwrites, and copied nothing"))])
      :else
      (do (doseq [[rel bs] (:files t)]
            (let [f (project/abs (str dest "/" rel))]
              (fs/create-dirs (fs/parent f))
              (with-open [o (java.io.FileOutputStream. f)] (.write o ^bytes bs))))
          (spit (project/abs (str dest "/origin.edn"))
                (str (pr-str {:template name :version (:version t) :copied (str (java.time.LocalDate/now))}) "\n"))
          (project/report {:plugin {:added name :version (:version t) :directory dest
                                    :add-to-plugins (:plugin t)}})))))
