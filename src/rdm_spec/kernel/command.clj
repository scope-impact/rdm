(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point, `rdm-spec
  <task> ...`. It belongs to no bounded context: it reads the project's
  configuration, builds the request, runs the task's stages (each owned by a
  context) on one event bus, and reports.

  How every task reports (dhf/spec/README.md): standard output holds only the
  task's result, as EDN; the error stream holds one line per finding,
  `FILE:LINE: RULE: what is wrong` (FILE and LINE left out where none applies),
  then one line per warning, `FILE:LINE: warning: RULE: what`, then, with
  --events, one line per event published. Exit 0 is a pass, 1 a finding,
  2 a task that cannot run as asked.

  A request, as every stage receives it:
    {:task \"check\" :args [positional ...] :opts {:sources \"DIR\" ...}
     :root \"/abs/project\" :config {..} or nil
     :bus bus :state (atom {})            ; one per run, shared by the stages
     :results \"dir\" or nil :results-given? bool}
  A stage is a function of the request returning {:result map :findings [..]}
  (or nil). The stages' results are merged into the task's result.

  The results directory of a task that reads one is --results, else the
  environment variable RESULTS_DIR, else the configured :results-dir."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.bus :as bus]))

(def config-file "dhf/harness/config.edn")

;; ---------------------------------------------------------------------------
;; Refusals and findings

(defn cannot-run
  "An exception saying the task cannot run as asked (exit 2)."
  [message & [data]]
  (ex-info message (merge data {::cannot-run true})))

(defn cannot-run!
  "Throws: the task cannot run as asked (exit 2), because of `message`."
  [message & [data]]
  (throw (cannot-run message data)))

(defn finding
  "A finding (an error): {:file :line :rule :message :severity :error}.
  `file` and `line` may be nil."
  ([rule message] (finding nil nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule rule :message message :severity :error}))

(defn warning
  "A warning: reported, never failing the task."
  ([rule message] (warning nil nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "One line of the error stream: `FILE:LINE: RULE: message`, `FILE: RULE:
  message` or `RULE: message`; a warning carries `warning: ` before its rule."
  [{:keys [file line rule message] :as f}]
  (str (when file (str file (when line (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       (when rule (str rule ": "))
       message))

;; ---------------------------------------------------------------------------
;; Paths and configuration

(defn- root-of [root-or-req]
  (str (if (map? root-or-req) (:root root-or-req) root-or-req)))

(defn path
  "The file `p` names in the project: `p` itself when absolute, else `p`
  under the project root (a root directory or a request)."
  [root-or-req p]
  (let [p (str p)]
    (if (fs/absolute? p)
      (io/file p)
      (io/file (root-of root-or-req) p))))

(defn rel
  "How a file is named in findings: its path relative to the project root,
  with /, or its absolute path when it lies outside the root."
  [root-or-req f]
  (let [root (fs/normalize (fs/absolutize (root-of root-or-req)))
        abs (fs/normalize (fs/absolutize (path root-or-req f)))]
    (if (fs/starts-with? abs root)
      (str/replace (str (fs/relativize root abs)) "\\" "/")
      (str abs))))

(defn read-config
  "The project's harness configuration (dhf/harness/config.edn under `root`),
  or nil when there is none. Cannot run when it is not an EDN map."
  [root]
  (let [f (path root config-file)]
    (when (fs/exists? f)
      (let [c (try (edn/read-string (slurp f))
                   (catch Exception e
                     (cannot-run! (str "the configuration " config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? c)
          (cannot-run! (str "the configuration " config-file " is not a map")))
        c))))

(defn config-value
  "The configured value of `k`, or cannot run when the configuration sets none."
  [req k]
  (let [v (get (:config req) k)]
    (when (nil? v)
      (cannot-run! (str "the configuration " config-file " sets no " k)))
    v))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` on the run's bus (no bus, no event)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

(defn read-catalogue
  "The project's event catalogue (the configured :events), or nil when it is
  not configured, absent or unreadable (the model's lint names why)."
  [root config]
  (when-let [rel-path (:events config)]
    (let [f (path root rel-path)]
      (when (fs/exists? f)
        (try (let [c (edn/read-string (slurp f))] (when (map? c) c))
             (catch Exception _ nil))))))

;; ---------------------------------------------------------------------------
;; The tasks

(def tasks
  "Every task: its usage, what it does, its positional arguments, its options
  (:value takes one, :flag none), whether it needs the configuration, whether
  it reads a results directory (:results :always; :existing, which cannot run
  without it; or :if-exists, when without one it says no status was derived), its subscribers and its stages, the
  last being the task's own."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :args [] :config? false}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args ["PATH"] :opts {:id-prefix :value} :config? false
    :stages ['rdm-spec.project.init/init]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [] :config? false
    :stages ['rdm-spec.project.adopt/adopt]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args []
    :stages ['rdm-spec.spec.stamper/stamp]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki"
    :args [] :opts {:sources :value :events :flag}
    :subscribers [[:spec/check 'rdm-spec.spec.check/subscriptions]
                  [:architecture/lint 'rdm-spec.architecture.lint/subscriptions]]
    :stages ['rdm-spec.knowledge.sources/check
             'rdm-spec.spec.check/check
             'rdm-spec.architecture.lint/check
             'rdm-spec.architecture.views/check]
    :all-stages-equal? true}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args []
    :stages ['rdm-spec.architecture.views/views]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [] :config? false
    :stages ['rdm-spec.regeneration.cut/cut]}
   {:task "fence" :usage "fence ROOM CUT [--context C | --component C/N]" :does "build a clean room from a cut"
    :args ["ROOM" "CUT"] :opts {:context :value :component :value}
    :stages ['rdm-spec.regeneration.fence/fence]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [] :opts {:results :value} :results :always
    :stages ['rdm-spec.kernel.runner/run]}
   {:task "ratify" :usage "ratify ROOM CYCLE" :does "run the builder's tests on the room's code in a checkout of the cut"
    :args ["ROOM" "CYCLE"]
    :stages ['rdm-spec.ratification.ratify/ratify]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args ["CYCLE"]
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.measurement.checklist/checklist]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [] :opts {:results :value} :results :existing
    :subscribers [[:verification/status 'rdm-spec.verification.status/subscriptions]]
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.status/status]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args ["ID"] :opts {:results :value} :results :if-exists
    :subscribers [[:verification/trace 'rdm-spec.verification.trace/subscriptions]]
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.trace/trace]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [] :opts {:results :value} :results :existing
    :subscribers [[:verification/release 'rdm-spec.verification.release/subscriptions]]
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.release/release]}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args ["CYCLE"] :opts {:verdicts :value} :required-opts [:verdicts]
    :stages ['rdm-spec.measurement.scorer/score]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args ["CYCLE" "CYCLE"]
    :stages ['rdm-spec.measurement.scoreboard/scoreboard]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [] :opts {:results :value} :results :if-exists
    :subscribers [[:publishing/site 'rdm-spec.publishing.site/subscriptions]
                  [:spec/check 'rdm-spec.spec.check/subscriptions]
                  [:verification/status 'rdm-spec.verification.status/subscriptions]]
    :stages ['rdm-spec.knowledge.sources/check
             'rdm-spec.spec.check/check
             `derive-statuses
             'rdm-spec.publishing.site/publish]}])

(def task-names (mapv :task tasks))

(defn task-of [name] (first (filter #(= name (:task %)) tasks)))

(defn usage-lines []
  (mapv #(str "  rdm-spec " (:usage %) "  ; " (:does %)) tasks))

(defn help
  "The help task's result: every task with its usage."
  [_req]
  {:result {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)}
   :findings []})

;; ---------------------------------------------------------------------------
;; Arguments

(defn parse-args
  "Splits a task's arguments into {:args [..] :opts {..}} by the task's
  declared options, or cannot run on an unknown or incomplete one."
  [{:keys [task usage opts args] :as _t} argv]
  (loop [xs argv pos [] o {}]
    (if-let [[x & more] (seq xs)]
      (if (str/starts-with? x "--")
        (let [k (keyword (subs x 2))
              kind (get opts k)]
          (case kind
            :flag (recur more pos (assoc o k true))
            :value (if (or (empty? more) (str/starts-with? (first more) "--"))
                     (cannot-run! (str x " needs a value; usage: rdm-spec " usage))
                     (recur (rest more) pos (assoc o k (first more))))
            (cannot-run! (str "unknown argument " x " for the task " task "; usage: rdm-spec " usage))))
        (recur more (conj pos x) o))
      (do (when (not= (count pos) (count args))
            (cannot-run! (str "the task " task " takes " (if (seq args) (str/join " " args) "no argument")
                              (when (seq pos) (str ", not " (str/join " " pos)))
                              "; usage: rdm-spec " usage)))
          {:args pos :opts o}))))

(defn results-dir
  "The results directory a task reads: [dir given?]. --results, else the
  environment's RESULTS_DIR, else the configured :results-dir (for a task
  that reads results only :if-exists, only when that directory exists)."
  [{:keys [root config opts]} mode env]
  (cond
    (string? (:results opts)) [(:results opts) true]
    (not (str/blank? env)) [env true]
    :else (let [c (:results-dir config)]
            (cond (nil? c) [nil false]
                  (#{:always :existing} mode) [c false]
                  (fs/directory? (path root c)) [c false]
                  :else [nil false]))))

;; ---------------------------------------------------------------------------
;; Stages

(defn- ns-built? [ns-sym]
  (some? (io/resource (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))))

(defn resolve-stage
  "The function a stage symbol names, or nil when its namespace is not built."
  [sym]
  (let [n (symbol (namespace sym))]
    (if (or (find-ns n) (ns-built? n))
      (do (require n) (ns-resolve n (symbol (name sym))))
      nil)))

(defn derive-statuses
  "Publish's status stage: derives every status from the results directory,
  when there is one, so the site reads them from VerificationDerived."
  [req]
  (when (:results req)
    (if-let [derive! (resolve-stage 'rdm-spec.verification.status/derive!)]
      {:findings (:findings (derive! req))}
      {:findings [(finding "not-built" "rdm-spec.verification.status/derive! is not built yet: no status was derived")]})))

(defn- run-stages
  [t req]
  (let [stages (:stages t)
        own (last stages)]
    (reduce
     (fn [acc sym]
       (if-let [f (resolve-stage sym)]
         (let [out (f req)]
           (-> acc
               (update :result merge (:result out))
               (update :findings into (:findings out))))
         (if (and (= sym own) (not (:all-stages-equal? t)))
           (cannot-run! (str sym " is not built yet: the task " (:task t) " cannot run"))
           (update acc :findings conj
                   (finding "not-built" (str sym " is not built yet: its part of " (:task t) " was not run"))))))
     {:result {} :findings []}
     stages)))

(defn request
  "The request for task `t` with `argv`, run in `root`."
  ([t argv root] (request t argv root (System/getenv "RESULTS_DIR")))
  ([t argv root env]
   (let [{:keys [args opts]} (parse-args t argv)
         _ (doseq [k (:required-opts t) :when (nil? (get opts k))]
             (cannot-run! (str "the task " (:task t) " needs --" (name k) "; usage: rdm-spec " (:usage t))))
         config (read-config root)
         _ (when (and (nil? config) (not (false? (:config? t))))
             (cannot-run! (str "no " config-file " in " root ": not a project set up by rdm-spec"
                               " (rdm-spec init PATH, or rdm-spec adopt in an existing repository)")))
         catalogue (when (and config (:events config)) (read-catalogue root config))
         base {:task (:task t) :args args :opts opts :root root :config config
               :bus (bus/make-bus {:catalogue catalogue :catalogue-file (:events config)})
               :state (atom {})}
         [dir given?] (when (:results t) (results-dir base (:results t) env))]
     (when (and (= :existing (:results t)) dir (not (fs/directory? (path root dir))))
       (cannot-run! (str "the results directory " dir " does not exist")))
     (cond-> base
       (:results t) (assoc :results dir :results-given? (boolean given?))))))

(defn- subscribe-all! [t req]
  (doseq [[subscriber sym] (:subscribers t)
          :let [f (resolve-stage sym)]
          :when f
          [event handler] (f req)]
    (bus/subscribe! (:bus req) event subscriber handler)))

(defn- printable
  "The result with every value EDN can print and read back."
  [x]
  (walk/postwalk (fn [v] (cond (instance? java.io.File v) (str v)
                               (instance? java.nio.file.Path v) (str v)
                               (instance? java.util.regex.Pattern v) (str v)
                               (fn? v) (pr-str v)
                               :else v))
                 x))

(defn run-task
  "Runs `argv` (the task and its arguments) in `root`. Returns
  {:exit :out :err}: what the command prints and how it exits."
  ([argv root] (run-task argv root (System/getenv "RESULTS_DIR")))
  ([argv root env]
   (let [[task-name & more] argv
         req (atom nil)
         events-lines #(when (and @req (get-in @req [:opts :events]))
                         (mapv bus/format-event (bus/events (:bus @req))))]
     (try
       (let [t (task-of task-name)]
         (when-not t
           (cannot-run! (str (if task-name (str "unknown task " task-name) "no task given")
                             "; the tasks are: " (str/join ", " task-names) "\n"
                             (str/join "\n" (usage-lines)))))
         (if (= "help" (:task t))
           (do (parse-args t more)
               {:exit 0 :out (pr-str (:result (help nil))) :err []})
           (let [r (reset! req (request t more root env))
                 _ (subscribe-all! t r)
                 {:keys [result findings]} (run-stages t r)
                 findings (vec (remove nil? findings))
                 errors (remove warning? findings)
                 warns (filter warning? findings)]
             {:exit (if (seq errors) 1 0)
              :out (pr-str (printable result))
              :err (vec (concat (map format-finding errors) (map format-finding warns) (events-lines)))})))
       (catch clojure.lang.ExceptionInfo e
         (if (::cannot-run (ex-data e))
           {:exit 2 :out "" :err (vec (concat [(str "cannot-run: " (ex-message e))] (events-lines)))}
           {:exit 2 :out "" :err [(str "error: " (ex-message e) " " (pr-str (ex-data e)))]}))
       (catch Exception e
         {:exit 2 :out "" :err [(str "error: " (.getName (class e)) ": " (ex-message e))]})))))

(defn -main
  "The rdm-spec command: `rdm-spec <task> ...`, run in the current directory."
  [& argv]
  (let [{:keys [exit out err]} (run-task (vec argv) (System/getProperty "user.dir"))]
    (when (seq out) (println out))
    (binding [*out* *err*]
      (doseq [l err] (println l)))
    (flush)
    (System/exit exit)))
