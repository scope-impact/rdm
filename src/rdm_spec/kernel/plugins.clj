(ns rdm-spec.kernel.plugins
  "Runs the project's plugins: subscribers to the core's events, answerers of
  its :Gate and :Check requests, through one interface; and adds a plugin
  from the templates rdm-spec carries (dhf/spec/08-plugins/plugins.feature; ADR 0003).

  The interface: the event or the request as EDN on standard input; the
  working directory the project's root (the checkout's, for :Check); RDM_SPEC
  set, and the plugin's :env; its own time limit (:seconds, 60 by default);
  exit 0 holds, 1 findings, 2 cannot run; findings FILE:LINE: RULE: what on
  its error stream, warnings carrying `warning: `."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]))

(def entry-keys #{:name :command :subscribes :answers :seconds :env})

(defn seconds [p] (or (:seconds p) 60))

(defn problem
  "Why the configuration's :plugins cannot be trusted, or nil."
  [cfg plugins]
  (let [impl (:implementation cfg)
        under-impl? (fn [arg] (some #(or (= arg %) (str/starts-with? (str arg) (str (str/replace % #"/$" "") "/"))) impl))
        names (map :name plugins)]
    (or (when-not (sequential? plugins) ":plugins is a vector of plugin declarations")
        (first
         (for [p plugins
               :let [n (or (:name p) "(unnamed)")
               why (cond
                     (not (map? p)) "a plugin is declared as a map"
                     (not (string? (:name p))) "a plugin names itself with :name, a string"
                     (seq (remove entry-keys (keys p)))
                     (str "a plugin is declared with :name, :command, :subscribes or :answers, :seconds and :env only, not " (str/join ", " (remove entry-keys (keys p))))
                     (not (and (vector? (:command p)) (seq (:command p)) (every? string? (:command p))))
                     "a plugin's :command is a vector of strings"
                     (and (nil? (:subscribes p)) (nil? (:answers p))) "a plugin subscribes to events or answers a request"
                     (and (:subscribes p) (:answers p)) "a plugin subscribes to events or answers a request, not both"
                     (and (:subscribes p) (not (and (sequential? (:subscribes p)) (seq (:subscribes p)))))
                     "a plugin's :subscribes is a vector of event names"
                     (some #(not (bus/catalogue %)) (:subscribes p))
                     (str "rdm-spec publishes no event " (name (first (remove bus/catalogue (:subscribes p))))
                          ": the events it publishes are " (str/join ", " (map name (sort (keys bus/catalogue)))))
                     (and (:answers p) (not (#{:Gate :Check} (:answers p))))
                     (str "a plugin answers " (pr-str (:answers p)) ", and the requests are :Gate and :Check")
                     (> (count (filter #{(:name p)} names)) 1) (str "a plugin's name is declared once, and " n " is declared twice")
                     (some under-impl? (:command p)) "its command is under the implementation's paths: a plugin is a judge the builder never writes"
                     (and (:seconds p) (not (and (number? (:seconds p)) (pos? (:seconds p))))) "a plugin's :seconds is a positive number"
                     (and (contains? p :env) (not (and (map? (:env p)) (every? string? (keys (:env p))) (every? string? (vals (:env p))))))
                     "a plugin's :env is a map of strings to strings")]
               :when why]
           (str "the plugin " n " is refused: " why))))))

(defn validate!
  "Refuses (exit 2) a :plugins declaration rdm-spec cannot trust, before anything runs."
  [run]
  (when-let [p (problem (:cfg run) (get-in run [:cfg :plugins] []))]
    (project/cannot-run! p))
  nil)

(defn deliveries
  "[[plugin input]] for every published event each subscriber subscribes to, plugins in declared order."
  [plugins published]
  (vec (for [p plugins :when (:subscribes p)
             e published :when (some #{(:event e)} (:subscribes p))]
         [p (merge {:event (:event e)} (:payload e))])))

(defn answerers [plugins kw] (filterv #(= kw (:answers %)) plugins))

(defn split-lines*
  "A plugin's error stream as finding lines and warning lines."
  [err]
  (let [ls (remove str/blank? (str/split-lines (str err)))
        w? #(re-find #"(^|: )warning: " %)]
    {:findings (vec (remove w? ls)) :warnings (vec (filter w? ls))}))

;; ------------------------------------------------------------------ running a plugin

(defn rdm-spec-command
  "A command line starting this same rdm-spec, however it was installed."
  []
  (let [cp (System/getProperty "java.class.path")
        cp (if (str/blank? cp)
             (let [u (str (io/resource "rdm_spec/kernel/command.clj"))]
               (cond (str/starts-with? u "jar:file:") (second (re-find #"^jar:file:(.*?)!/" u))
                     (str/starts-with? u "file:") (str (fs/parent (fs/parent (fs/parent (fs/path (subs u 5))))))
                     :else "src"))
             cp)]
    (str "bb -cp " cp " -m rdm-spec.kernel.command")))

(defn run-plugin
  "Runs a plugin's command with input in dir. Returns {:exit :out :err :timed-out? :not-found?}."
  [p input dir & [extra-args]]
  (project/run-cmd (into (vec (:command p)) extra-args)
                   {:dir dir :in (project/edn-str input) :seconds (seconds p)
                    :env (merge (:env p) {"RDM_SPEC" (rdm-spec-command)})}))

(defn answer
  "What a plugin's run makes of the task's report: {:findings :warnings :cannot-run}."
  [p r]
  (let [{:keys [findings warnings]} (split-lines* (:err r))
        raw (fn [l] {:raw l})]
    (cond
      (:not-found? r) {:cannot-run (str "the plugin " (:name p) "'s command " (pr-str (:command p)) " cannot be started")}
      (:timed-out? r) {:cannot-run (str "the plugin " (:name p) " timed out after " (seconds p) " seconds and was stopped"
                                        (let [o (project/one-line (str (:out r) " " (:err r)))] (when-not (str/blank? o) (str "; it printed: " o))))}
      (= 2 (:exit r)) {:cannot-run (str "the plugin " (:name p) " cannot run: " (project/one-line (:err r)))}
      (= 1 (:exit r)) {:findings (mapv raw (if (seq findings) findings [(str (:name p) ": plugin-findings: the plugin exited 1 naming no finding")]))
                       :warnings (mapv raw warnings)}
      (= 0 (:exit r)) {:warnings (mapv raw warnings) :findings (mapv raw findings)}
      :else {:cannot-run (str "the plugin " (:name p) " exited " (:exit r) ": " (project/one-line (:err r)))})))

(defn- report-of [answers]
  (let [cr (keep :cannot-run answers)]
    (cond-> {:findings (vec (mapcat :findings answers))
             :warnings (mapv #(assoc % :warning? true) (mapcat :warnings answers))}
      (seq cr) (assoc :exit 2 :why (str/join "; " cr)))))

(defn deliver
  "After the core's stages: each subscriber receives each event it subscribes to, in declared order."
  [run published]
  (let [plugins (get-in run [:cfg :plugins] [])]
    (report-of (for [[p input] (deliveries plugins published)]
                 (answer p (run-plugin p input (:root run)))))))

(defn gate
  "Asks every plugin answering :Gate, in order; stops at the first that fails."
  [run request]
  (loop [ps (answerers (get-in run [:cfg :plugins] []) :Gate) acc []]
    (if (empty? ps)
      (report-of acc)
      (let [p (first ps)
            a (answer p (run-plugin p request (:root run)))]
        (if (or (:cannot-run a) (seq (:findings a)))
          (let [rep (report-of (conj acc a))] (if (:exit rep) rep (assoc rep :exit 1)))
          (recur (rest ps) (conj acc a)))))))

;; ------------------------------------------------------------------ templates

(defn- source-templates-dir []
  (let [u (str (io/resource "rdm_spec/kernel/command.clj"))]
    (when (str/starts-with? u "file:")
      (let [d (fs/path (fs/parent (fs/parent (fs/parent (fs/parent (fs/path (subs u 5)))))) "plugins")]
        (when (fs/directory? d) d)))))

(defn- jar-templates
  "Templates carried as classpath resources under rdm-spec/plugins/: {name {rel bytes-reader}}."
  []
  (let [u (str (io/resource "rdm-spec/plugins/"))]
    (when (str/starts-with? u "jar:file:")
      (let [jar (java.util.jar.JarFile. ^String (second (re-find #"^jar:file:(.*?)!/" u)))]
        (try
          (reduce (fn [acc ^java.util.jar.JarEntry e]
                    (let [n (.getName e)]
                      (if (and (str/starts-with? n "rdm-spec/plugins/") (not (.isDirectory e)))
                        (let [[t & more] (str/split (subs n (count "rdm-spec/plugins/")) #"/")]
                          (if (seq more)
                            (assoc-in acc [t (str/join "/" more)] (slurp (.getInputStream jar e)))
                            acc))
                        acc)))
                  {} (enumeration-seq (.entries jar)))
          (finally (.close jar)))))))

(defn template-files
  "Every template rdm-spec carries: {name {rel text}}."
  []
  (if-let [d (source-templates-dir)]
    (into {} (for [t (fs/list-dir d) :when (fs/exists? (fs/path t "template.edn"))]
               [(str (fs/file-name t))
                (into {} (for [f (fs/glob t "**" {:hidden true}) :when (fs/regular-file? f)]
                           [(str (fs/relativize t f)) (str f)]))]))
    (or (jar-templates) {})))

(defn- content [v] (if (fs/exists? v) (slurp v) v))

(defn templates []
  (vec (for [[n files] (sort-by key (template-files))
             :let [t (edn/read-string (content (get files "template.edn")))]]
         {:name n :version (:version t) :plugin (:plugin t) :files files})))

(defn plugin-task
  "plugin add NAME: copies a template into dhf/harness/plugins/NAME and says how to declare it."
  [run]
  (let [[sub n] (:args run)
        root (:root run)]
    (when-not (= "add" sub) (project/cannot-run! (str "unknown plugin task " sub ": rdm-spec plugin add NAME")))
    (let [ts (templates)
          t (some #(when (= n (:name %)) %) ts)
          listing (str/join ", " (map #(str (:name %) " (version " (:version %) ")") ts))
          dest (str "dhf/harness/plugins/" n)]
      (cond
        (nil? t) (project/cannot-run! (str "rdm-spec carries no template " n ": the templates are " listing))
        (fs/exists? (fs/path root dest))
        {:exit 1 :findings [(project/finding dest "plugin-exists" (str dest " already exists: plugin add copies nothing over it"))]}
        :else
        (do
          (doseq [[rel v] (:files t)
                  :let [f (fs/path root dest rel)]]
            (fs/create-dirs (fs/parent f))
            (if (fs/exists? v)
              (fs/copy v f {:copy-attributes true})
              (spit (str f) v)))
          (spit (str (fs/path root dest "origin.edn")) (str (project/edn-str {:template n :version (:version t) :copied (project/today)}) "\n"))
          {:result {:plugin {:added n :to dest :version (:version t) :declare (:plugin t)
                             :says "add the :declare entry to :plugins in dhf/harness/config.edn"}}})))))

;; ------------------------------------------------------------------ the :Check request

(defn ask-check
  "Asks the plugin named n answering :Check, in dir, with request. {:outcome :passed|:failed|:refused :findings :reason}."
  [plugins n request dir]
  (let [p (some #(when (and (= n (:name %)) (= :Check (:answers %))) %) plugins)
        r (run-plugin p request dir)
        a (answer p r)]
    (cond (:cannot-run a) {:outcome :refused :reason (:cannot-run a)}
          (seq (:findings a)) {:outcome :failed :findings (:findings a) :warnings (:warnings a)
                               :reason (str "the plugin " n " answered with findings")}
          (not (zero? (:exit r))) {:outcome :failed :reason (str "the plugin " n " exited " (:exit r))}
          :else {:outcome :passed :warnings (:warnings a)})))
