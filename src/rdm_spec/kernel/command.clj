(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point,
  `rdm-spec <task> ...`, and the composition root. It belongs to no bounded
  context: it reads the arguments and the configuration, builds the run's
  request and event bus, and runs the task's stages, each owned by a
  context, in the order the task table gives.

  A stage is a function of the request returning {:result map :findings
  [finding]}. The task's result is the merge of its stages' results. Every
  stage reports, so one run names every finding; a stage the table marks
  :gate? stops the task when it has findings that are not warnings, and the
  stages after it never run (glossary: Gate). A gate whose code is not built
  refuses the task. A stage that throws `cannot-run!` makes the task one that
  cannot run.

  The request:
    {:task \"status\" :args [positional] :opts {:results \"DIR\" ...}
     :root \"/abs/project\" :config {..} or nil :env {\"NAME\" \"value\"}
     :results \"dir\" or nil   ; --results, else RESULTS_DIR, else configured
     :state (atom {}) :bus bus}

  How every task reports (dhf/spec/README.md): standard output holds only the
  result, as EDN ({} on exit 1 when there is none, nothing when the task
  cannot run); the error stream holds one line per finding, then one per
  warning, then (with --events) one per event. Exit 0 a pass, 1 a finding, 2
  a task that cannot run."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings, warnings, refusals

(defn finding
  "A finding: something wrong in what the task checked (exit 1)."
  ([rule message] (finding nil nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning: reported, never failing the task."
  ([rule message] (warning nil nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn- one-line [s] (str/join " " (remove str/blank? (map str/trim (str/split-lines (str s))))))

(defn format-finding
  "The finding's line: `FILE:LINE: RULE: what`, `FILE: RULE: what` or
  `RULE: what`; a warning has `warning: ` before its RULE."
  [{:keys [file line rule message] :as f}]
  (let [where (cond (and file line) (str file ":" line ": ")
                    file (str file ": ")
                    :else "")]
    (str where (when (warning? f) "warning: ") rule ": " (one-line message))))

(defn cannot-run!
  "Refuses the task: it cannot run as asked (exit 2), and says why."
  [message]
  (throw (ex-info (str message) {::cannot-run true})))

(defn cannot-run? [e] (boolean (::cannot-run (ex-data e))))

;; ---------------------------------------------------------------------------
;; Paths and configuration

(def config-file "dhf/harness/config.edn")

(defn- root-of [root-or-req] (str (if (map? root-or-req) (:root root-or-req) root-or-req)))

(defn path
  "The file `rel` names in the project (`root`, or a request's :root); an
  absolute `rel` names itself."
  [root-or-req rel]
  (let [rel (str rel)]
    (if (fs/absolute? rel)
      (fs/file rel)
      (fs/file (root-of root-or-req) rel))))

(defn rel
  "The path of the file `f` relative to the project, written with /."
  [root-or-req f]
  (let [base (fs/normalize (fs/absolutize (root-of root-or-req)))
        p (fs/normalize (fs/absolutize (str f)))]
    (str/replace (str (fs/relativize base p)) "\\" "/")))

(defn read-config
  "The project's configuration (dhf/harness/config.edn under `root`), or nil
  when there is none. Cannot run when it is not an EDN map."
  [root]
  (let [f (path root config-file)]
    (when (fs/exists? f)
      (let [c (try (edn/read-string (slurp (str f)))
                   (catch Exception e
                     (cannot-run! (str "the configuration " config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? c) (cannot-run! (str "the configuration " config-file " is not an EDN map")))
        c))))

(defn config-value
  "The configured value of `k`; cannot run when the configuration sets none."
  [req k]
  (let [v (get (:config req) k)]
    (if (nil? v)
      (cannot-run! (str "the configuration (" config-file ") sets no " k))
      v)))

;; ---------------------------------------------------------------------------
;; The event bus of a run

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the run's bus (none:
  nothing is published)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload)))

(defn- read-catalogue
  "The configured event catalogue, or nil when there is none readable (the
  model reports a catalogue it cannot read; an absent one checks nothing)."
  [root config]
  (when-let [rel (:events config)]
    (let [f (path root rel)]
      (when (fs/regular-file? f)
        (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
          (when (map? c) c))))))

;; ---------------------------------------------------------------------------
;; The task table

(def ^:private results-opt {"--results" {:key :results}})

(def tasks
  "Every task: its usage and description (as help lists them), its
  arguments ([min max] positional), its options, how it needs the
  configuration (:required, :optional: the task reads a cut's own, :none),
  how it reads a results directory (:required, :optional: the configured
  one only when it exists, :any: the stage falls back itself), and its
  stages in order, a gate first."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :args [0 0] :config :none :stages [{:stage `help}]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args [1 1] :options {"--id-prefix" {:key :id-prefix}} :config :none
    :stages [{:stage 'rdm-spec.project.init/init}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [0 0] :config :none :stages [{:stage 'rdm-spec.project.adopt/adopt}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0] :config :required :stages [{:stage 'rdm-spec.spec.stamper/stamp}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki"
    :args [0 0] :options {"--sources" {:key :sources}} :config :required
    :stages [{:stage 'rdm-spec.knowledge.sources/check}
             {:stage 'rdm-spec.spec.check/check}
             {:stage 'rdm-spec.architecture.lint/check}
             {:stage 'rdm-spec.architecture.views/check}]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0] :config :required :stages [{:stage 'rdm-spec.architecture.views/views}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :config :optional :stages [{:stage 'rdm-spec.regeneration.cut/cut}]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :config :optional :stages [{:stage 'rdm-spec.regeneration.scopes/scopes}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :args [2 2] :options {"--context" {:key :context :repeat? true}
                          "--component" {:key :component :repeat? true}}
    :config :optional
    :stages [{:stage 'rdm-spec.measurement.oracle/oracle :gate? true}
             {:stage 'rdm-spec.regeneration.fence/fence}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :options results-opt :config :required :results :any
    :stages [{:stage 'rdm-spec.kernel.runner/run}]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :args [2 2] :config :optional
    :stages [{:stage 'rdm-spec.measurement.oracle/oracle :gate? true}
             {:stage 'rdm-spec.ratification.ratify/ratify}]}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1] :config :required
    :stages [{:stage 'rdm-spec.spec.parser/parse}
             {:stage 'rdm-spec.measurement.checklist/checklist}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :options results-opt :config :required :results :required
    :stages [{:stage 'rdm-spec.spec.parser/parse}
             {:stage 'rdm-spec.verification.status/status}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :options results-opt :config :required :results :optional
    :stages [{:stage 'rdm-spec.spec.parser/parse}
             {:stage 'rdm-spec.verification.trace/trace}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0] :options results-opt :config :required :results :required
    :stages [{:stage 'rdm-spec.spec.parser/parse}
             {:stage 'rdm-spec.verification.release/release}]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args [1 1] :options {"--verdicts" {:key :verdicts :required? true}} :config :required
    :stages [{:stage 'rdm-spec.measurement.scorer/score}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2] :config :required :stages [{:stage 'rdm-spec.measurement.scoreboard/scoreboard}]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args [0 1] :options {"--reason" {:key :reason}} :config :optional
    :stages [{:stage 'rdm-spec.measurement.oracle/oracle}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0] :options results-opt :config :required :results :optional
    :stages [{:stage 'rdm-spec.architecture.views/check :gate? true}
             {:stage 'rdm-spec.knowledge.sources/check}
             {:stage 'rdm-spec.spec.check/check}
             {:stage 'rdm-spec.verification.status/derive!}
             {:stage 'rdm-spec.publishing.site/publish}]}])

(def ^:private common-options
  "Options every task takes: --events prints the run's events."
  {"--events" {:key :events :flag? true}})

(defn task
  "The task named `name` in `table` (the task table by default), or nil."
  ([name] (task tasks name))
  ([table name] (first (filter #(= name (:task %)) table))))

(defn- task-names [table] (str/join ", " (map :task table)))

(defn- usage-error! [row message]
  (cannot-run! (str message "; usage: rdm-spec " (:usage row))))

(defn parse-args
  "Reads `argv` (the words after the task) against the task's usage:
  {:args [positional] :opts {key value}}. A repeated option's values are
  joined with commas. Cannot run on an unknown option, a missing value, or
  too few or too many arguments."
  [row argv]
  (let [options (merge common-options (:options row))]
    (loop [[a & more :as words] (seq argv) args [] opts {}]
      (cond
        (empty? words)
        (let [[lo hi] (or (:args row) [0 0])]
          (cond
            (< (count args) lo) (usage-error! row (str (:task row) " needs " lo " argument(s), got " (count args)))
            (> (count args) hi) (usage-error! row (str "unknown argument " (nth args hi)))
            :else
            (do (doseq [[flag {:keys [key required?]}] options
                        :when (and required? (not (contains? opts key)))]
                  (usage-error! row (str flag " is required")))
                {:args args :opts opts})))

        (str/starts-with? a "--")
        (let [{:keys [key flag? repeat?] :as o} (get options a)]
          (cond
            (nil? o) (usage-error! row (str "unknown argument " a))
            flag? (recur more args (assoc opts key true))
            (or (empty? more) (str/starts-with? (first more) "--"))
            (usage-error! row (str a " needs a value"))
            (and (contains? opts key) (not repeat?))
            (usage-error! row (str a " is given twice"))
            :else (recur (rest more) args
                         (if (contains? opts key)
                           (update opts key str "," (first more))
                           (assoc opts key (first more))))))

        :else (recur more (conj args a) opts)))))

(defn results-dir
  "The results directory the task `row` reads: --results, else the
  environment's RESULTS_DIR, else the configured :results-dir. A task that
  reads results only when there are some (:optional) takes the configured
  one only when it exists; one whose stage falls back itself (:any) gets nil
  then."
  [row opts env config root]
  (let [mode (:results row)
        given (or (not-empty (:results opts)) (not-empty (get env "RESULTS_DIR")))
        configured (:results-dir config)]
    (cond
      (nil? mode) nil
      given given
      (= :any mode) nil
      (nil? configured) (when (= :required mode)
                          (cannot-run! (str "no results directory: give --results DIR, or configure :results-dir in "
                                            config-file)))
      (= :optional mode) (when (fs/directory? (path root configured)) configured)
      :else configured)))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage
  "The stage's function, or nil when its code is not built."
  [sym]
  (try (requiring-resolve sym) (catch Throwable _ nil)))

(defn- component-of
  "The component a stage's namespace is: rdm-spec.verification.status ->
  :verification/status."
  [sym]
  (let [parts (str/split (namespace sym) #"\.")]
    (keyword (str/join "." (butlast (rest parts))) (last parts))))

(defn stage-subscriptions
  "The subscriptions of the namespace of `stage`: [[event subscriber handler]]
  from its public `subscriptions` function, if it has one."
  [req stage]
  (let [sym (:stage stage)]
    (when-let [f (resolve-stage (symbol (namespace sym) "subscriptions"))]
      (for [[event handler] (f req)]
        [event (component-of sym) handler]))))

(defn help
  "The help task: every task with its usage."
  [req]
  {:result {:tasks (mapv #(select-keys % [:task :usage :does]) (or (::table req) tasks))}
   :findings []})

(defn- group-by-ns
  "The first stage of each namespace, in stage order: {ns stage}."
  [stages]
  (reduce (fn [m st] (let [n (namespace (:stage st))] (if (contains? m n) m (assoc m n st))))
          (array-map) stages))

(defn- run-stages
  "Runs the stages of `row` on `req`: {:result :findings}, stopping after a
  gate with findings."
  [row req]
  (let [fns (for [st (:stages row)]
              (assoc st :fn (or (resolve-stage (:stage st))
                                (cannot-run! (str (if (:gate? st) "the gate " "the stage ") (:stage st)
                                                  " is not built, so " (:task row) " cannot run")))))]
    (doseq [st (vals (group-by-ns (:stages row)))
            [event subscriber handler] (stage-subscriptions req st)]
      (bus/subscribe! (:bus req) event subscriber handler))
    (loop [[st & more] fns result {} found []]
      (if-not st
        {:result result :findings found}
        (let [out ((:fn st) req)
              result (merge result (when (map? (:result out)) (:result out)))
              found (into found (:findings out))]
          (if (and (:gate? st) (some (complement warning?) (:findings out)))
            {:result result :findings found}
            (recur more result found)))))))

(defn execute
  "Runs the task `argv` (task name first) in the project `root`, with the
  environment `env`: {:exit :result :findings :events :cannot-run} where
  :cannot-run is why it could not run, or nil. `opts` may give another
  :tasks table."
  [argv {:keys [root env] table :tasks}]
  (let [table (or table tasks)
        root (str (fs/normalize (fs/absolutize (or root "."))))
        env (or env (into {} (System/getenv)))
        b (atom nil)
        events? (some #{"--events"} argv)]
    (try
      (let [[name & words] argv
            row (cond
                  (nil? name) (cannot-run! (str "no task given; the tasks are " (task-names table)))
                  :else (or (task table name)
                            (cannot-run! (str "unknown task " name "; the tasks are " (task-names table)))))
            {:keys [args opts]} (parse-args row words)
            config (case (:config row)
                     :none nil
                     :optional (try (read-config root) (catch Exception _ nil))
                     (or (read-config root)
                         (cannot-run! (str "no configuration: " config-file " is missing in " root))))
            bus' (bus/make-bus {:catalogue (read-catalogue root config) :catalogue-file (:events config)})
            _ (reset! b bus')
            req {:task name :args args :opts opts :root root :config config :env env
                 :results (results-dir row opts env config root)
                 :state (atom {}) :bus bus' ::table table}
            out (run-stages row req)
            errors (remove warning? (:findings out))]
        {:exit (if (seq errors) 1 0)
         :result (:result out)
         :findings (vec (:findings out))
         :events (bus/events bus')
         :events? (boolean events?)
         :cannot-run nil})
      (catch Exception e
        {:exit 2
         :result nil
         :findings []
         :events (some-> @b bus/events)
         :events? (boolean events?)
         :cannot-run (if (cannot-run? e)
                       (ex-message e)
                       (str "the task failed unexpectedly: " (.getName (class e)) ": " (ex-message e)))}))))

(defn- report
  "What `execute`'s outcome prints: {:out string :err [line]}."
  [{:keys [exit result findings events events? cannot-run]}]
  (let [event-lines (when events? (map bus/format-event events))]
    (if (= 2 exit)
      {:out "" :err (vec (concat [(str "cannot-run: " (one-line cannot-run))] event-lines))}
      {:out (str (pr-str (or result {})) "\n")
       :err (vec (concat (map format-finding (remove warning? findings))
                         (map format-finding (filter warning? findings))
                         event-lines))})))

(defn run-task
  "Runs `rdm-spec argv...` in `root` with the environment `env` (nil: this
  process's), in process: {:exit :out :err [line]}. Anything a stage prints
  on standard output goes to the error stream, so standard output is the
  result alone."
  ([argv root env] (run-task argv root env {}))
  ([argv root env opts]
   (let [stray (java.io.StringWriter.)
         outcome (binding [*out* stray] (execute argv (assoc opts :root root :env env)))
         {:keys [out err]} (report outcome)
         stray-lines (remove str/blank? (str/split-lines (str stray)))]
     {:exit (:exit outcome) :out out :err (vec (concat stray-lines err))})))

(defn -main
  "The rdm-spec command: `rdm-spec <task> ...` in the current directory."
  [& argv]
  (let [{:keys [exit out err]} (run-task (vec argv) (System/getProperty "user.dir") (into {} (System/getenv)))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
