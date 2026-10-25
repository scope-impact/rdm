(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point, `rdm-spec
  <task>`. It belongs to no bounded context: it is the composition root that
  reads the configuration, builds the run's request and event bus, and runs
  each task's stages, which live in the contexts that own them.

  A request (`req`), what every stage receives:

    {:task    \"check\"                  ; the task's name
     :args    [\"03\"]                    ; its positional arguments
     :opts    {:results \"DIR\" :events true ...}   ; its options, by name
     :root    \"/abs/project\"            ; the project: where the command runs
     :config  {..} | nil                  ; dhf/harness/config.edn, as read
     :env     {\"PATH\" ..}               ; the environment the task's processes get
     :results \"dhf/allure-results\" | nil ; the results directory, as given
     :state   (atom {})                   ; the run's state, shared by its stages
     :bus     bus}                        ; the run's event bus (kernel/bus)

  A stage is a function of the request returning {:result map :findings
  [finding]} (either may be left out); it refuses with `cannot-run!`. A
  finding is {:file :line :rule :message :severity :error}, a warning the
  same with :severity :warning (`finding`, `warning`).

  The task table (`tasks`) gives each task its usage, what it does, and its
  stages in order. A stage entry is {:stage 'ns/fn} plus
    :gate?   its findings (warnings aside) stop the task: the stages after it
             never run. A gate whose code is not built refuses the task.
    :quiet?  it runs for its events only: its result and findings are dropped
             (a refusal still stops the task).
    :when    :regulated: it runs only when the configuration holds :regulated.
  Before any stage runs, every stage's namespace that defines `subscriptions`
  (a function of the request returning [[event handler] ..]) is subscribed
  on the bus, in stage order. A task with no gate reports every stage's
  findings, so one run names them all.

  The results directory of a task that reads one is --results, else the
  environment's RESULTS_DIR, else the configured :results-dir. A task that
  only may read results (:results :optional) reads none when neither is given
  and the configured directory does not exist.

  Reporting (dhf/spec/README.md): standard output holds the result as EDN
  (`{}` when there is none); the error stream one line per finding, then per
  warning, then, with --events, one line per event. Exit 0 a pass, 1 a
  finding, 2 a task that cannot run (`cannot-run: why`, and nothing on
  standard output)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Refusals

(defn cannot-run!
  "Refuses the task: it cannot run as asked (exit 2), saying why."
  [message]
  (throw (ex-info (str message) {::cannot-run true})))

(defn cannot-run?
  "True when the exception `e` is a refusal (`cannot-run!`, or ex-data
  carrying ::cannot-run)."
  [e]
  (boolean (::cannot-run (ex-data e))))

;; ---------------------------------------------------------------------------
;; Findings

(defn finding
  "A finding: what is wrong in `file` at `line` (either may be nil) under the
  stable name `rule`. (finding rule message) when no file applies."
  ([rule message] (finding nil nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning: a finding that never fails the task. (warning rule message)
  when no file applies."
  ([rule message] (warning nil nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The finding's line on the error stream: `FILE:LINE: RULE: what`, FILE
  and LINE left out where none applies; a warning `FILE:LINE: warning: RULE:
  what`."
  [{:keys [file line rule message] :as f}]
  (let [where (cond (and (not (str/blank? (str file))) line) (str file ":" line ": ")
                    (not (str/blank? (str file))) (str file ": ")
                    :else "")]
    (str where (when (warning? f) "warning: ") rule ": " message)))

;; ---------------------------------------------------------------------------
;; Paths and the configuration

(def config-file "dhf/harness/config.edn")

(defn- root-of [root-or-req]
  (str (if (map? root-or-req) (:root root-or-req) root-or-req)))

(defn path
  "The file `rel` names, relative to the project (a root or a request); an
  absolute `rel` is kept."
  [root-or-req rel]
  (let [r (str rel)]
    (if (fs/absolute? r)
      (fs/file r)
      (fs/file (root-of root-or-req) r))))

(defn rel
  "`f` relative to the project, with / between names; a path outside the
  project stays absolute."
  [root-or-req f]
  (let [root (fs/normalize (fs/absolutize (root-of root-or-req)))
        abs (fs/normalize (fs/absolutize (path root-or-req f)))]
    (if (str/starts-with? (str abs) (str root))
      (str/replace (str (fs/relativize root abs)) "\\" "/")
      (str abs))))

(defn read-config
  "The configuration under `root`, or nil when there is none. One that is not
  an EDN map cannot be run with."
  [root]
  (let [f (path root config-file)]
    (when (fs/regular-file? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e
                     (cannot-run! (str "the configuration " config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? v)
          (cannot-run! (str "the configuration " config-file " is not an EDN map")))
        v))))

(defn config-value
  "The configured value `k` of the request, or a refusal naming it."
  [req k]
  (let [v (get (:config req) k)]
    (if (nil? v)
      (cannot-run! (str "the configuration " config-file " sets no " k))
      v)))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` on the run's bus (kernel/bus)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

(defn- read-catalogue
  "The configured event catalogue, or nil when none is configured or it
  cannot be read (the model's lint names why)."
  [root config]
  (when-let [rel (:events config)]
    (let [f (path root rel)]
      (when (fs/regular-file? f)
        (let [v (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
          (when (map? v) v))))))

;; ---------------------------------------------------------------------------
;; The task table

(defn- stage [sym & {:as more}] (merge {:stage sym} more))

(def ^:private parse (stage 'rdm-spec.spec.parser/parse :quiet? true))
(def ^:private gap-quiet (stage 'rdm-spec.compliance.gap/gap :when :regulated :quiet? true))
(def ^:private oracle-gate (stage 'rdm-spec.measurement.oracle/oracle :gate? true))

(def tasks
  "Every task: {:task :usage :does :stages [stage] :config :required|:optional|:none
  :results :required|:optional (only for a task that reads results)}."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :config :none :stages []}
   {:task "init" :usage "init PATH [--id-prefix P]"
    :does "write the skeleton into a path that does not exist"
    :config :none :stages [(stage 'rdm-spec.project.init/init)]}
   {:task "adopt" :usage "adopt"
    :does "add the skeleton to the current repository, overwriting nothing"
    :config :none :stages [(stage 'rdm-spec.project.adopt/adopt)]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :config :required :stages [(stage 'rdm-spec.spec.stamper/stamp)]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses"
    :config :required
    :stages [(stage 'rdm-spec.knowledge.sources/check)
             (stage 'rdm-spec.spec.check/check)
             (stage 'rdm-spec.architecture.lint/check)
             (stage 'rdm-spec.architecture.views/check)
             (stage 'rdm-spec.compliance.gap/gap :when :regulated)]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :config :required :stages [(stage 'rdm-spec.architecture.views/views)]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :config :optional :stages [(stage 'rdm-spec.regeneration.cut/cut)]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :config :optional :stages [(stage 'rdm-spec.regeneration.scopes/scopes)]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :config :optional :stages [oracle-gate (stage 'rdm-spec.regeneration.fence/fence)]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :config :required :results :required :stages [(stage 'rdm-spec.kernel.runner/run)]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :config :optional :stages [oracle-gate (stage 'rdm-spec.ratification.ratify/ratify)]}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :config :required
    :stages [parse gap-quiet (stage 'rdm-spec.measurement.checklist/checklist)]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :config :required :results :required
    :stages [parse (stage 'rdm-spec.verification.status/status)]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :config :required :results :optional
    :stages [parse (stage 'rdm-spec.verification.trace/trace)]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :config :required :results :required
    :stages [parse gap-quiet (stage 'rdm-spec.verification.release/release)]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :config :required :stages [(stage 'rdm-spec.measurement.scorer/score)]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :config :required :stages [(stage 'rdm-spec.measurement.scoreboard/scoreboard)]}
   {:task "oracle" :usage "oracle [record --reason R]"
    :does "judge the instrument against its recorded goldens"
    :config :optional :stages [(stage 'rdm-spec.measurement.oracle/oracle)]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :config :required :results :optional
    :stages [(stage 'rdm-spec.architecture.views/check :gate? true)
             (stage 'rdm-spec.knowledge.sources/check :quiet? true)
             (stage 'rdm-spec.spec.check/check :quiet? true)
             (stage 'rdm-spec.verification.status/status :quiet? true)
             (stage 'rdm-spec.publishing.site/publish)]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :config :required :results :required
    :stages [(stage 'rdm-spec.compliance.audit/prepare)
             parse gap-quiet
             (stage 'rdm-spec.verification.release/release :gate? true)
             (stage 'rdm-spec.compliance.audit/audit)]}])

(defn task-row [task] (first (filter #(= task (:task %)) tasks)))

(defn- task-list [] (str/join ", " (map :task tasks)))

;; ---------------------------------------------------------------------------
;; The command line

(defn- usage-words [usage] (str/split usage #"\s+"))

(defn- usage-options
  "{\"--results\" true} for an option taking a value, false for a flag, as
  the usage gives them; --events is every task's."
  [usage]
  (let [ws (vec (map #(str/replace % #"[\[\]]" "") (rest (usage-words usage))))]
    (into {"--events" false}
          (keep-indexed (fn [i w]
                          (when (str/starts-with? w "--")
                            [w (boolean (some->> (get ws (inc i)) (re-matches #"[A-Z][A-Z0-9/,.]*")))])))
          ws)))

(defn- usage-positionals
  "[required n, optional? bool] of the usage's positional arguments."
  [usage]
  (let [ws (vec (rest (usage-words usage)))
        required (count (keep-indexed (fn [i w]
                                        (when (and (re-matches #"[A-Z][A-Z0-9]*" w)
                                                   (not (and (pos? i) (str/starts-with? (get ws (dec i)) "--"))))
                                          w))
                                      ws))
        optional? (boolean (re-find #"\[[a-z]" usage))]
    [required optional?]))

(defn parse-args
  "The task's command line `argv` (after the task's name) as {:args :opts},
  or a refusal naming what is wrong and the usage."
  [{:keys [task usage]} argv]
  (let [known (usage-options usage)
        bad #(cannot-run! (str % "; usage: rdm-spec " usage))]
    (loop [xs (seq argv) args [] opts {}]
      (if-let [[x & more] (seq xs)]
        (if (and (str/starts-with? x "--") (> (count x) 2))
          (if-not (contains? known x)
            (bad (str "unknown option " x " for " task))
            (if (get known x)
              (if (and more (not (str/starts-with? (first more) "--")))
                (recur (rest more) args (assoc opts (keyword (subs x 2)) (first more)))
                (bad (str "the option " x " needs a value")))
              (recur more args (assoc opts (keyword (subs x 2)) true))))
          (recur more (conj args x) opts))
        (let [[required optional?] (usage-positionals usage)]
          (cond
            (< (count args) required) (bad (str task " needs " required " argument" (when (not= 1 required) "s")))
            (and (> (count args) required) (not optional?))
            (bad (str "unexpected argument " (str/join " " (drop required args))))
            :else {:args args :opts opts}))))))

(defn- results-dir
  "The results directory the task reads: --results, else RESULTS_DIR, else
  the configured one (nil for an optional one that does not exist)."
  [row opts env root config]
  (when-let [mode (:results row)]
    (let [given (or (:results opts) (not-empty (get env "RESULTS_DIR")))]
      (cond
        given given
        (= :required mode) (:results-dir config)
        :else (let [d (:results-dir config)]
                (when (and d (fs/directory? (path root d))) d))))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- stage-ns [sym] (symbol (namespace sym)))

(defn- component-of
  "The component a stage belongs to, as the bus names a subscriber:
  rdm-spec.spec.check/check is :spec/check."
  [sym]
  (let [parts (str/split (str (stage-ns sym)) #"\.")]
    (keyword (str/join "." (butlast (rest parts))) (last parts))))

(defn- resolve-stage
  "The stage's function, or a refusal: a task whose code is not built cannot run."
  [{:keys [stage gate?]}]
  (or (try (requiring-resolve stage) (catch Throwable _ nil))
      (cannot-run! (str (if gate? "the gate " "the stage ") stage " is not built, so the task cannot run"))))

(defn- runs? [{w :when} config]
  (case w
    :regulated (contains? config :regulated)
    true))

(defn- subscribe!
  [req stages]
  (doseq [s (reduce (fn [out s] (if (some #(= (stage-ns (:stage s)) (stage-ns (:stage %))) out) out (conj out s)))
                    [] stages)
          :let [sub (try (requiring-resolve (symbol (str (stage-ns (:stage s))) "subscriptions"))
                         (catch Throwable _ nil))]
          :when sub
          [event handler] (sub req)]
    (bus/subscribe! (:bus req) event (component-of (:stage s)) handler)))

(defn- help-result []
  {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)})

(defn execute
  "Runs the task `argv` (its name first) in the project `root` with the
  environment `env` (nil: this process's): {:exit :result :findings :events
  :cannot-run}. Never throws."
  [argv {:keys [root env]}]
  (let [root (str (fs/absolutize (or root ".")))
        env (or env (into {} (System/getenv)))
        [task & argv] argv
        state (atom {})
        the-bus (atom nil)
        done (fn [m] (merge {:result {} :findings [] :events (some-> @the-bus bus/events)} m))]
    (try
      (let [row (or (task-row task)
                    (cannot-run! (str (if (str/blank? task) "no task given" (str "unknown task " task))
                                      "; the tasks are: " (task-list)
                                      (str/join (for [t tasks] (str "\n  rdm-spec " (:usage t)))))))
            {:keys [args opts]} (parse-args row argv)
            config (when-not (= :none (:config row)) (read-config root))
            _ (when (and (= :required (:config row)) (nil? config))
                (cannot-run! (str "no configuration " config-file " in " root
                                  ": rdm-spec " task " runs in a project set up by rdm-spec")))
            b (bus/make-bus {:catalogue (read-catalogue root config) :catalogue-file (:events config)})
            _ (reset! the-bus b)
            req {:task task :args args :opts opts :root root :config config :env env
                 :results (results-dir row opts env root config) :state state :bus b}
            stages (filter #(runs? % config) (:stages row))]
        (if (= "help" task)
          (done {:exit 0 :result (help-result)})
          (do
            (subscribe! req stages)
            (loop [ss stages result {} findings []]
              (if-let [[s & more] (seq ss)]
                (let [f (resolve-stage s)
                      out (f req)
                      out (if (map? out) out {})
                      fs* (vec (:findings out))
                      result (if (:quiet? s) result (merge result (:result out)))
                      findings (if (:quiet? s) findings (into findings fs*))]
                  (if (and (:gate? s) (seq (remove warning? fs*)))
                    (done {:exit 1 :result result :findings findings})
                    (recur more result findings)))
                (done {:exit (if (seq (remove warning? findings)) 1 0)
                       :result result :findings findings}))))))
      (catch Throwable e
        (done {:exit 2 :result nil :findings []
               :cannot-run (if (cannot-run? e)
                             (ex-message e)
                             (str "the task failed unexpectedly: " (.getName (class e)) ": " (ex-message e)))})))))

(defn render
  "What an outcome prints: {:out text :err [line]}."
  [{:keys [exit result findings events cannot-run]} events?]
  (let [lines (concat (map format-finding (remove warning? findings))
                      (map format-finding (filter warning? findings))
                      (when cannot-run (str/split-lines (str "cannot-run: " cannot-run)))
                      (when events? (map bus/format-event events)))]
    {:out (if (= 2 exit) "" (str (binding [*print-namespace-maps* false] (pr-str (or result {}))) "\n"))
     :err (vec lines)}))

(defn run-task
  "Runs the task `argv` in `dir` with the environment `env` (nil: this
  process's), as the command would: {:exit :out :err [line]}."
  [argv dir env]
  (let [outcome (execute argv {:root dir :env env})
        events? (boolean (some #{"--events"} (rest argv)))]
    (assoc (render outcome events?) :exit (:exit outcome))))

(defn -main
  "The rdm-spec command: runs the task in the current directory."
  [& argv]
  (let [{:keys [exit out err]} (run-task (vec argv) (System/getProperty "user.dir") nil)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
