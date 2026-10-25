(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point, `rdm-spec
  <task> ...`, and the composition root. It belongs to no bounded context: it
  dispatches each task to the stages of the contexts that own it, wires their
  subscriptions on one in-process bus (kernel/bus), and reports every task the
  same way (dhf/spec/README.md, \"How every task reports\").

  A stage is a function of the request returning {:result map :findings
  [finding]}; it says it cannot run by throwing (cannot-run! why). The task
  table (`tasks`) names each stage by symbol, resolved when the task runs, so
  a context that is not built only stops the tasks that need it. A stage row:

    {:stage sym            ; (f req)
     :subscribe sym        ; (f req) -> [[event handler] ...], wired before any stage runs
     :gate true            ; findings stop the task: the stages after it never run
     :quiet true           ; neither its result nor its findings are reported
     :when :regulated}     ; runs only when the configuration holds that key

  The request every stage receives:

    {:task \"check\" :args [positional] :opts {:sources .. :events true ..}
     :root \"project dir\" :config {..} or nil :env {\"VAR\" \"value\"}
     :results \"dir\" or nil :bus bus :state (atom {})}

  A finding: {:file :line :rule :message :severity :error|:warning}.

  Exit 0: a pass; 1: a finding; 2: the task cannot run (`cannot-run: why` on
  the error stream, nothing on standard output)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

(def config-file
  "Where a project's harness configuration lives, relative to its root."
  "dhf/harness/config.edn")

;; ---------------------------------------------------------------------------
;; Refusing to run

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), because `why`."
  ([why] (cannot-run! why {}))
  ([why data]
   (throw (ex-info (str why) (assoc data ::cannot-run true)))))

(defn cannot-run?
  "True when `e` is a refusal thrown by cannot-run! (or carrying its mark)."
  [e]
  (boolean (and (instance? clojure.lang.ExceptionInfo e) (::cannot-run (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Findings

(defn finding
  "An error finding: (finding rule message), (finding file rule message) or
  (finding file line rule message)."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning, with the arities of `finding`."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "A finding's line on the error stream: `FILE:LINE: RULE: what`, FILE and
  LINE left out where none applies; a warning says `warning:` before its
  rule."
  [{:keys [file line rule message] :as f}]
  (let [where (cond
                (and file line) (str file ":" line ": ")
                file (str file ": ")
                :else "")]
    (str where (when (warning? f) "warning: ") (when rule (str rule ": ")) message)))

;; ---------------------------------------------------------------------------
;; Paths and configuration

(defn- root-of [root-or-req]
  (str (if (map? root-or-req) (:root root-or-req) root-or-req)))

(defn path
  "The file `rel` names: itself when absolute, else under the project root
  (`root-or-req` is the root or a request)."
  [root-or-req rel]
  (let [rel (str rel)]
    (if (fs/absolute? rel)
      (fs/file rel)
      (fs/file (root-of root-or-req) rel))))

(defn rel
  "`f` relative to the project root, with / separators."
  [root-or-req f]
  (let [root (fs/absolutize (root-of root-or-req))
        f (fs/absolutize (str f))]
    (str/replace (str (fs/relativize root f)) "\\" "/")))

(defn read-config
  "The configuration of the project at `root`: a map, nil when there is none.
  Cannot run when it is not one EDN map."
  [root]
  (let [f (path root config-file)]
    (when (fs/regular-file? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e
                     (cannot-run! (str "the configuration " config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? v)
          (cannot-run! (str "the configuration " config-file " is not one EDN map")))
        v))))

(defn config-value
  "The configuration's value for `k`; cannot run when it sets none."
  [req k]
  (let [v (get (:config req) k)]
    (when (nil? v)
      (cannot-run! (str "the configuration " config-file " sets no " k)))
    v))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` on the request's bus (nothing when the
  request has none)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

;; ---------------------------------------------------------------------------
;; The task table

(def ^:private gate-oracle
  {:stage 'rdm-spec.measurement.oracle/oracle :gate true})

(def ^:private parse
  {:stage 'rdm-spec.spec.parser/parse})

(def ^:private gap-quiet
  {:stage 'rdm-spec.compliance.gap/gap :when :regulated :quiet true})

(def tasks
  "Every task, in the order help lists them: its usage, what it does, its
  arguments and options, where its results directory comes from, and its
  stages in order."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :args [0 0] :config :none :stages []}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args [1 1] :options {"--id-prefix" :id-prefix} :config :none
    :stages [{:stage 'rdm-spec.project.init/init}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [0 0] :stages [{:stage 'rdm-spec.project.adopt/adopt}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0] :stages [{:stage 'rdm-spec.spec.stamper/stamp}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses"
    :args [0 0] :options {"--sources" :sources}
    :stages [{:stage 'rdm-spec.knowledge.sources/check}
             {:stage 'rdm-spec.spec.check/check}
             {:stage 'rdm-spec.architecture.lint/check :subscribe 'rdm-spec.architecture.lint/subscriptions}
             {:stage 'rdm-spec.architecture.views/check}
             {:stage 'rdm-spec.compliance.gap/gap :when :regulated}]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0] :stages [{:stage 'rdm-spec.architecture.views/views}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :stages [{:stage 'rdm-spec.regeneration.cut/cut}]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :stages [{:stage 'rdm-spec.regeneration.scopes/scopes}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :args [2 2] :options {"--context" :context "--component" :component}
    :stages [gate-oracle {:stage 'rdm-spec.regeneration.fence/fence}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :options {"--results" :results} :results :any
    :stages [{:stage 'rdm-spec.kernel.runner/run}]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :args [2 2] :stages [gate-oracle {:stage 'rdm-spec.ratification.ratify/ratify}]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1]
    :stages [parse gap-quiet
             {:stage 'rdm-spec.measurement.checklist/checklist
              :subscribe 'rdm-spec.measurement.checklist/subscriptions}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :options {"--results" :results} :results :required
    :stages [parse {:stage 'rdm-spec.verification.status/status
                    :subscribe 'rdm-spec.verification.status/subscriptions}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :options {"--results" :results} :results :optional
    :stages [parse {:stage 'rdm-spec.verification.trace/trace
                    :subscribe 'rdm-spec.verification.trace/subscriptions}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0] :options {"--results" :results} :results :required
    :stages [parse gap-quiet
             {:stage 'rdm-spec.verification.release/release
              :subscribe 'rdm-spec.verification.release/subscriptions}]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args [1 1] :options {"--verdicts" :verdicts} :required-options [:verdicts]
    :stages [{:stage 'rdm-spec.measurement.scorer/score}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2] :stages [{:stage 'rdm-spec.measurement.scoreboard/scoreboard}]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args [0 1] :options {"--reason" :reason}
    :stages [{:stage 'rdm-spec.measurement.oracle/oracle}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0] :options {"--results" :results} :results :optional
    :stages [{:stage 'rdm-spec.knowledge.sources/check :quiet true}
             {:stage 'rdm-spec.spec.check/check :quiet true}
             {:stage 'rdm-spec.architecture.views/check :gate true}
             {:stage 'rdm-spec.verification.status/derive! :quiet true
              :subscribe 'rdm-spec.verification.status/subscriptions}
             {:stage 'rdm-spec.publishing.site/publish :subscribe 'rdm-spec.publishing.site/subscriptions}]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :options {"--results" :results} :results :required
    :stages [{:stage 'rdm-spec.compliance.audit/prepare}
             parse gap-quiet
             {:stage 'rdm-spec.verification.release/release :gate true
              :subscribe 'rdm-spec.verification.release/subscriptions}
             {:stage 'rdm-spec.compliance.audit/audit :subscribe 'rdm-spec.compliance.audit/subscriptions}]}])

(defn task-names [] (mapv :task tasks))

(defn- task-row [name] (first (filter #(= name (:task %)) tasks)))

(defn- tasks-text []
  (str "the tasks are: " (str/join ", " (task-names)) " (rdm-spec help lists each with its usage)"))

;; ---------------------------------------------------------------------------
;; Arguments

(def ^:private global-flags
  "Flags every task takes: --events prints the events the run published."
  #{"--events"})

(defn parse-args
  "{:args [positional] :opts {..}} of the task `row`'s arguments; cannot run
  on an unknown option, a missing option value or a wrong number of
  arguments."
  [row argv]
  (let [usage (str "usage: rdm-spec " (:usage row))
        options (:options row)]
    (loop [xs argv args [] opts {}]
      (if (empty? xs)
        (let [[lo hi] (:args row)]
          (when-not (<= lo (count args) hi)
            (cannot-run! (str "rdm-spec " (:task row) " takes "
                              (if (= lo hi) lo (str lo " to " hi)) " argument" (when-not (= 1 hi) "s")
                              ", not " (count args) " (" (str/join " " args) "); " usage)))
          (doseq [k (:required-options row)]
            (when-not (contains? opts k)
              (cannot-run! (str "rdm-spec " (:task row) " needs --" (name k) "; " usage))))
          {:args args :opts opts})
        (let [[x & more] xs]
          (cond
            (contains? global-flags x)
            (recur more args (assoc opts (keyword (subs x 2)) true))

            (contains? options x)
            (do (when (or (empty? more) (str/starts-with? (first more) "--"))
                  (cannot-run! (str x " needs a value; " usage)))
                (recur (rest more) args (assoc opts (get options x) (first more))))

            (str/starts-with? x "--")
            (cannot-run! (str "rdm-spec " (:task row) " has no option " x "; " usage))

            :else (recur more (conj args x) opts)))))))

;; ---------------------------------------------------------------------------
;; The results directory

(defn results-dir
  "The task's results directory: --results, else RESULTS_DIR, else the
  configured one. For a task whose results are optional, the configured one
  only when it exists (else nil)."
  [row opts config env root]
  (let [given (:results opts)
        from-env (get env "RESULTS_DIR")
        configured (:results-dir config)]
    (case (:results row)
      nil nil
      :any (or given (not-empty from-env) configured)
      :required (or given (not-empty from-env) configured
                    (cannot-run! (str "no results directory: give --results DIR, set RESULTS_DIR or configure"
                                      " :results-dir in " config-file)))
      :optional (or given (not-empty from-env)
                    (when (and configured (fs/directory? (path root configured))) configured)))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage! [task sym]
  (or (try (requiring-resolve sym) (catch Exception _ nil))
      (cannot-run! (str "rdm-spec " task " needs " sym ", which is not built"))))

(defn- read-catalogue [root config]
  (when-let [rel (:events config)]
    (let [f (path root rel)]
      (when (fs/regular-file? f)
        (let [v (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
          (when (map? v) {:catalogue v :catalogue-file (str rel)}))))))

(defn- active-stages [row config]
  (filterv #(or (nil? (:when %)) (contains? config (:when %))) (:stages row)))

(defn- run-stages
  "Runs the request's stages in order: {:result :findings}."
  [req stages]
  (let [subs (distinct (keep :subscribe stages))]
    (doseq [s subs
            :let [f (resolve-stage! (:task req) s)]
            [event handler] (f req)]
      (bus/subscribe! (:bus req) event (symbol (namespace s)) handler))
    (loop [ss stages result {} findings []]
      (if (empty? ss)
        {:result result :findings findings}
        (let [{:keys [stage gate quiet]} (first ss)
              out ((resolve-stage! (:task req) stage) req)
              fs* (vec (:findings out))]
          (cond
            quiet (recur (rest ss) result findings)
            (and gate (seq (remove warning? fs*))) {:result (merge result (:result out)) :findings (into findings fs*)}
            :else (recur (rest ss) (merge result (:result out)) (into findings fs*))))))))

(defn- env-of [env]
  (or env (into {} (System/getenv))))

(defn execute
  "Runs the task `argv` names in the project at `root`, in process:
  {:exit 0|1|2 :result map :findings [..] :events [..] :cannot-run why|nil}.
  `env` (default: this process's) is the environment the task sees."
  [argv {:keys [root env]}]
  (let [root (str (or root (fs/cwd)))
        env (env-of env)
        b (atom nil)]
    (try
      (let [[task & more] argv
            _ (when (str/blank? task) (cannot-run! (str "no task was given; " (tasks-text))))
            row (or (task-row task) (cannot-run! (str "unknown task " task "; " (tasks-text))))
            {:keys [args opts]} (parse-args row (vec more))
            ;; a stage that needs a value the project does not configure
            ;; says so itself (config-value); cut, fence, scopes and ratify
            ;; read the configuration at a cut, adopt has none yet
            config (when-not (= :none (:config row)) (read-config root))
            the-bus (bus/make-bus (or (read-catalogue root config) {}))
            _ (reset! b the-bus)
            req {:task task :args args :opts opts :root root :config config :env env
                 :results (results-dir row opts config env root)
                 :bus the-bus :state (atom {})}]
        (if (= "help" task)
          {:exit 0 :result {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)} :findings [] :events []}
          (let [{:keys [result findings]} (run-stages req (active-stages row config))]
            {:exit (if (some (complement warning?) findings) 1 0)
             :result result
             :findings findings
             :events (bus/events the-bus)
             :events? (boolean (:events opts))})))
      (catch Exception e
        {:exit 2
         :cannot-run (if (cannot-run? e)
                       (ex-message e)
                       (str "the task failed unexpectedly: " (.getName (class e)) ": " (ex-message e)))
         :findings []
         :events (some-> @b bus/events)}))))

(defn report-lines
  "What an executed task prints: {:out \"stdout\" :err [line]}."
  [{:keys [exit result findings events events? cannot-run]}]
  (if (= 2 exit)
    {:out "" :err [(str "cannot-run: " cannot-run)]}
    {:out (str (pr-str (or result {})) "\n")
     :err (vec (concat (map format-finding (remove warning? findings))
                       (map format-finding (filter warning? findings))
                       (when events? (map bus/format-event events))))}))

(defn run-task
  "Runs `argv` in the project at `dir` with the environment `env` (nil: this
  process's): {:exit :out :err [line] :result :findings}."
  [argv dir env]
  (let [r (execute (vec argv) {:root dir :env env})]
    (merge (select-keys r [:exit :result :findings :cannot-run]) (report-lines r))))

(defn -main
  "The rdm-spec command: `rdm-spec <task> ...` in the current directory."
  [& argv]
  (let [{:keys [exit out err]} (run-task (vec argv) (str (fs/cwd)) nil)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
