(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point,
  `rdm-spec <task> ...`, and how every task reports. It belongs to no bounded
  context: it parses the command line, reads the configuration, makes the
  run's event bus and dispatches the task to the stages of the contexts that
  own it.

  A task is a row of `tasks`: its usage (which is also its argument grammar:
  an UPPER-CASE word is a positional argument, `--flag VALUE` an option taking
  a value, `--flag` a switch, `[...]` optional), what it does, and its stages.

  A request (req), what every stage receives:
    {:task \"status\" :args [positional ...] :opts {:results \"DIR\" :events true ...}
     :root \"project dir\" :config {configuration map, or nil}
     :results \"DIR\" or nil   ; tasks taking --results: the results directory
     :bus bus :state (atom {})}

  A stage is a function of the request returning {:result map :findings [..]};
  the task's result is its stages' results merged, its findings theirs in
  order. Before the first stage runs, every stage's namespace that has a
  `subscriptions` function (req -> [[Event handler] ...]) is subscribed on the
  bus. A stage that cannot run throws `cannot-run!`, which ends the task.

  A finding: {:file :line :rule :message :severity :error|:warning}.

  Reporting (dhf/spec/README.md, \"How every task reports\"): standard output
  holds only the result, as EDN; the error stream holds one line per finding
  (`FILE:LINE: RULE: what`), then one per warning
  (`FILE:LINE: warning: RULE: what`), then, with --events, one per event
  (`event: NAME published-by PUBLISHER`). Exit 0 is a pass, 1 a finding, 2 a
  task that cannot run (`cannot-run: why`, nothing on standard output)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings and refusals

(def config-file
  "Where a project's configuration lives, relative to its root."
  "dhf/harness/config.edn")

(defn cannot-run!
  "Ends the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn finding
  "A finding (exit 1): FILE and LINE may be nil where none applies."
  ([rule message] (finding nil nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning: reported, never failing the task."
  ([rule message] (warning nil nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn- one-line [s] (str/replace (str/trim (str s)) #"\s*\r?\n\s*" " "))

(defn format-finding
  "A finding's line: `FILE:LINE: RULE: what`, or `FILE:LINE: warning: RULE:
  what` for a warning; FILE and LINE left out where none applies."
  [{:keys [file line rule message] :as f}]
  (let [where (cond (and file line) (str file ":" line ": ")
                    file (str file ": ")
                    :else "")]
    (str where (when (warning? f) "warning: ") rule ": " (one-line message))))

;; ---------------------------------------------------------------------------
;; Paths and configuration

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `p` names, relative to the project root (`root-or-req`: the root,
  or a request carrying :root); an absolute `p` stays as it is."
  [root-or-req p]
  (let [p (str p)]
    (if (fs/absolute? p)
      (fs/file p)
      (fs/file (root-of root-or-req) p))))

(defn rel
  "`f` as a path relative to the project root, with / as separator; a file
  outside the root stays absolute."
  [root-or-req f]
  (let [root (fs/absolutize (fs/normalize (fs/path (root-of root-or-req))))
        abs (fs/absolutize (fs/normalize (fs/path (str f))))
        r (if (str/starts-with? (str abs) (str root))
            (str (fs/relativize root abs))
            (str abs))]
    (str/replace r "\\" "/")))

(defn read-config
  "The configuration of the project at `root`, or nil when it has none.
  Cannot run when it is there but is not an EDN map."
  [root]
  (let [f (path root config-file)]
    (when (fs/regular-file? f)
      (let [c (try (edn/read-string (slurp f))
                   (catch Exception e
                     (cannot-run! (str "the configuration " config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? c)
          (cannot-run! (str "the configuration " config-file " is not a map")))
        c))))

(defn config-value
  "The configured value of `k`; cannot run when the configuration sets none."
  [req k]
  (let [v (get (:config req) k)]
    (if (nil? v)
      (cannot-run! (str "the configuration " config-file " sets no " k))
      v)))

(defn publish!
  "Publishes `event` from `publisher` on the run's bus (nothing without one)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

;; ---------------------------------------------------------------------------
;; The tasks

(defn- stage
  "A stage named by its var's symbol, resolved when the task runs: a context
  that is not built yet fails only the tasks that need it."
  [sym]
  {:sym sym})

(defn- status-stage
  "Derives the statuses (publishing VerificationDerived) when the run has a
  results directory: publish's reading of verification."
  [req]
  (if (:results req)
    (let [derive! (requiring-resolve 'rdm-spec.verification.status/derive!)
          {:keys [findings]} (derive! req)]
      {:result {} :findings findings})
    {:result {} :findings []}))

(def tasks
  "Every task, in the order help lists them. :config :required means the task
  cannot run without the project's configuration; :results says how the
  results directory defaults (:required: --results, else RESULTS_DIR, else
  the configured one; :optional: the same, but a configured one that does
  not exist means no results)."
  [{:task "help" :usage "help" :does "list every task with its usage" :stages [] :config :none}
   {:task "init" :usage "init PATH [--id-prefix P]"
    :does "write the skeleton into a path that does not exist"
    :stages ['rdm-spec.project.init/init] :config :none}
   {:task "adopt" :usage "adopt"
    :does "add the skeleton to the current repository, overwriting nothing"
    :stages ['rdm-spec.project.adopt/adopt] :config :none}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :stages ['rdm-spec.spec.stamper/stamp] :config :required}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki"
    :stages ['rdm-spec.knowledge.sources/check 'rdm-spec.spec.check/check
             'rdm-spec.architecture.lint/check 'rdm-spec.architecture.views/check]
    :config :required}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :stages ['rdm-spec.architecture.views/views] :config :required}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :stages ['rdm-spec.regeneration.cut/cut] :config :none}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :stages ['rdm-spec.regeneration.fence/fence] :config :none}
   {:task "run" :usage "run [--results DIR]"
    :does "run the scenarios against a project's step definitions"
    :stages ['rdm-spec.kernel.runner/run] :config :required :results :required}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :stages ['rdm-spec.ratification.ratify/ratify] :config :required}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.measurement.checklist/checklist] :config :required}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.status/status]
    :config :required :results :required}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.trace/trace]
    :config :required :results :optional}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.release/release]
    :config :required :results :required}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :stages ['rdm-spec.measurement.scorer/score] :config :required}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :stages ['rdm-spec.measurement.scoreboard/scoreboard] :config :required}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :stages ['rdm-spec.knowledge.sources/check 'rdm-spec.spec.check/check
             `status-stage 'rdm-spec.publishing.site/publish]
    :subscribers ['rdm-spec.verification.status]
    :config :required :results :optional}])

(def task-names (mapv :task tasks))

(defn task-of [name] (first (filter #(= name (:task %)) tasks)))

(defn- task-list [] (str/join ", " task-names))

;; ---------------------------------------------------------------------------
;; The command line, read by the task's usage

(defn grammar
  "The argument grammar a usage line states:
  {:positional [\"PATH\"] :options {\"--id-prefix\" {:value? true :required? false}}}."
  [usage]
  (loop [toks (rest (str/split usage #"\s+")) optional? false acc {:positional [] :options {}}]
    (if-let [t (first toks)]
      (let [open? (str/starts-with? t "[")
            close? (str/ends-with? t "]")
            word (str/replace t #"[\[\]]" "")
            opt? (or optional? open?)
            next-optional? (and opt? (not close?))]
        (if (str/starts-with? word "--")
          (let [v (second toks)
                value? (and v (not (str/starts-with? v "--")) (not (str/starts-with? v "["))
                            (= v (str/upper-case v)))
                close-at-value? (and value? (str/ends-with? v "]"))]
            (recur (drop (if value? 2 1) toks)
                   (and next-optional? (not close-at-value?))
                   (assoc-in acc [:options word] {:value? (boolean value?) :required? (not opt?)})))
          (recur (rest toks) next-optional? (update acc :positional conj word))))
      acc)))

(def global-options
  "Options every task takes: every run can print its events."
  {"--events" {:value? false :required? false}})

(defn parse-args
  "Reads `argv` (after the task's name) by the task's usage:
  {:args [...] :opts {:k v}}; cannot run on an unknown or missing argument."
  [{:keys [task usage]} argv]
  (let [{:keys [positional options]} (grammar usage)
        options (merge global-options options)
        usage-hint (str "usage: rdm-spec " usage)]
    (loop [argv argv args [] opts {}]
      (if-let [a (first argv)]
        (cond
          (= "--" a) (recur [] (into args (rest argv)) opts)
          (str/starts-with? a "--")
          (let [[flag inline] (str/split a #"=" 2)
                spec (or (get options flag)
                         (cannot-run! (str "unknown argument " a " for " task "; " usage-hint)))
                k (keyword (subs flag 2))]
            (if (:value? spec)
              (let [v (or inline (second argv))
                    _ (when (or (nil? v) (and (nil? inline) (str/starts-with? v "--")))
                        (cannot-run! (str flag " needs a value; " usage-hint)))
                    v (if (contains? opts k) (str (get opts k) "," v) v)]
                (recur (drop (if inline 1 2) argv) args (assoc opts k v)))
              (recur (rest argv) args (assoc opts k true))))
          :else (recur (rest argv) (conj args a) opts))
        (do
          (when (> (count args) (count positional))
            (cannot-run! (str "unexpected argument " (nth args (count positional)) " for " task "; " usage-hint)))
          (when (< (count args) (count positional))
            (cannot-run! (str task " needs " (str/join " " (drop (count args) positional)) "; " usage-hint)))
          (doseq [[flag {:keys [required?]}] options
                  :when (and required? (not (contains? opts (keyword (subs flag 2)))))]
            (cannot-run! (str task " needs " flag "; " usage-hint)))
          {:args args :opts opts})))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- results-dir
  "The run's results directory: --results, else RESULTS_DIR, else the
  configured one (for :optional, only when it exists)."
  [mode opts env root config]
  (when mode
    (let [given (or (not-empty (:results opts)) (not-empty (get env "RESULTS_DIR")))
          configured (:results-dir config)]
      (cond
        given given
        (nil? configured) (when (= :required mode)
                            (cannot-run! (str "no results directory: give --results DIR, set RESULTS_DIR,"
                                              " or set :results-dir in " config-file)))
        (= :required mode) configured
        (fs/directory? (path root configured)) configured
        :else nil))))

(defn- read-catalogue [root config]
  (when-let [rel (:events config)]
    (let [f (path root rel)]
      (when (fs/regular-file? f)
        (let [c (try (edn/read-string (slurp f))
                     (catch Exception e
                       (cannot-run! (str "the event catalogue " rel " is not readable EDN: " (ex-message e)))))]
          (when-not (or (nil? c) (map? c))
            (cannot-run! (str "the event catalogue " rel " is not a map")))
          c)))))

(defn- resolve-stage [sym]
  (or (try (requiring-resolve sym)
           (catch Exception e
             (cannot-run! (str "the task's stage " sym " cannot be loaded: " (ex-message e)))))
      (cannot-run! (str "the task's stage " sym " does not exist"))))

(defn request
  "The request for `task` run with `argv` in `root`, under `env`."
  [task argv root env]
  (let [{:keys [args opts]} (parse-args task argv)
        config (when-not (= :none (:config task)) (read-config root))
        _ (when (and (= :required (:config task)) (nil? config))
            (cannot-run! (str "no configuration: " config-file " does not exist in " root)))
        catalogue (read-catalogue root config)]
    {:task (:task task)
     :args args
     :opts opts
     :root (str root)
     :config config
     :results (results-dir (:results task) opts env root config)
     :bus (bus/make-bus {:catalogue catalogue :catalogue-file (:events config)})
     :state (atom {})}))

(defn- subscribe-all!
  "Subscribes, on the run's bus, every namespace among the task's stages and
  its :subscribers that has a `subscriptions` function, in that order."
  [req {:keys [stages subscribers]}]
  (doseq [ns-sym (distinct (concat (keep #(some-> % namespace symbol) stages) subscribers))
          :let [_ (require ns-sym)]
          :let [subs-fn (ns-resolve (the-ns ns-sym) 'subscriptions)]
          :when subs-fn
          [event handler] (subs-fn req)]
    (bus/subscribe! (:bus req) event (keyword (str/replace (str/replace (str ns-sym) #"^rdm-spec\." "") "." "/")) handler)))

(defn help-result []
  {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)})

(defn execute
  "Runs `argv` in `root`: {:status :passed|:found|:cannot-run :result
  :findings :events :why}. Never throws for a task that cannot run."
  [argv root env]
  (let [findings (atom [])
        req* (atom nil)]
    (try
      (let [[name & more] argv
            task (cond (nil? name) (cannot-run! (str "no task given; the tasks are: " (task-list)))
                       :else (or (task-of name)
                                 (cannot-run! (str "unknown task " name "; the tasks are: " (task-list)))))
            req (request task (vec more) root env)
            _ (reset! req* req)
            fns (mapv resolve-stage (:stages task))
            _ (subscribe-all! req task)
            result (if (= "help" (:task task))
                     (help-result)
                     (reduce (fn [acc f]
                               (let [{:keys [result] fs* :findings} (f req)]
                                 (swap! findings into fs*)
                                 (merge acc result)))
                             {} fns))]
        {:status (if (some (complement warning?) @findings) :found :passed)
         :result result
         :findings @findings
         :events (some-> @req* :bus bus/events)
         :events? (get-in req [:opts :events])})
      (catch clojure.lang.ExceptionInfo e
        (if (::cannot-run (ex-data e))
          {:status :cannot-run :why (ex-message e) :findings @findings
           :events (some-> @req* :bus bus/events) :events? (get-in @req* [:opts :events])}
          {:status :cannot-run :why (str "the task failed: " (ex-message e)) :findings @findings
           :events (some-> @req* :bus bus/events) :events? (get-in @req* [:opts :events])}))
      (catch Throwable e
        {:status :cannot-run :why (str "the task failed: " (.getName (class e)) ": " (ex-message e))
         :findings @findings
         :events (some-> @req* :bus bus/events) :events? (get-in @req* [:opts :events])}))))

(defn report
  "What a run prints: {:exit n :out \"EDN or empty\" :err [line ...]}."
  [{:keys [status result findings events events? why]}]
  (let [errors (remove warning? findings)
        warnings (filter warning? findings)]
    {:exit (case status :passed 0 :found 1 :cannot-run 2)
     :out (if (= :cannot-run status) "" (str (pr-str result) "\n"))
     :err (vec (concat (map format-finding errors)
                       (map format-finding warnings)
                       (when (= :cannot-run status) [(str "cannot-run: " (one-line why))])
                       (when events? (map bus/format-event events))))}))

(defn run-task
  "Runs the task `argv` in the project at `root` with the environment `env`
  (nil: the process's). Returns {:exit :out :err [line ...]}; prints nothing."
  [argv root env]
  (report (execute (vec argv) (str root) (or env (into {} (System/getenv))))))

(defn -main
  "`rdm-spec <task> ...`, run in the current directory."
  [& argv]
  (let [{:keys [exit out err]} (run-task (vec argv) (str (fs/cwd)) nil)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
