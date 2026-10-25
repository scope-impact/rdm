(ns rdm-spec.kernel.command
  "The rdm-spec command (dhf/spec/kernel/command.feature): the one entry point
  a hook, CI, an agent and a person all use. It belongs to no bounded context:
  it dispatches each task to the stages of the contexts that own it, wires
  their subscriptions to one in-process bus per run, and reports for every
  task the same way.

  The task table (`tasks`) gives each task its usage, what it does, the
  arguments and options it takes, and its stages, run in order. Every stage
  is a function of the request returning {:result map :findings [finding]}.
  Every stage reports, so one run names every finding; a stage marked
  :gate? is the exception: it runs first and, when it has findings, the task
  exits 1 with the gate's findings unchanged and the stages after it never
  run (glossary: Gate). The oracle (rdm-spec.measurement.oracle/oracle) is
  the gate of fence and ratify.

  Stages are named by symbol and resolved when the task runs, never at load:
  every context requires this namespace, so it cannot require them back, and
  a context that is not built yet fails only the tasks that need it.

  The request a stage receives:
    {:task \"check\" :args [positional] :opts {:sources \"DIR\" ...}
     :root \"/abs/project\" :config {..}-or-nil :results \"dir\"-or-nil
     :env {\"VAR\" \"value\"} :state (atom {}) :bus bus}

  How every task reports (dhf/spec/README.md):
    standard output  the merged results of the stages, as EDN ({} when none)
    error stream     one line per finding (FILE:LINE: RULE: what), then one
                     per warning (FILE:LINE: warning: RULE: what), then with
                     --events one per event (event: NAME published-by P)
    exit             0 pass, 1 a finding, 2 cannot run (`cannot-run: why`
                     alone on the error stream, nothing on standard output)

  A task's results directory is --results, else RESULTS_DIR, else the
  configured :results-dir; a task that only may read results (trace,
  publish) takes the configured one only when it exists."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings, warnings, refusals

(def config-file "dhf/harness/config.edn")

(defn cannot-run!
  "Stops the task as one that cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "True when `e` is a refusal to run (thrown by cannot-run! or carrying ::cannot-run)."
  [e]
  (boolean (::cannot-run (ex-data e))))

(defn finding
  "A finding (exit 1): {:file :line :rule :message :severity :error}. FILE
  and LINE are nil where none applies."
  ([rule message] (finding nil nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (some-> rule str) :message (str message) :severity :error}))

(defn warning
  "A warning: reported, never failing the task."
  ([rule message] (warning nil nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The finding's line on the error stream: `FILE:LINE: RULE: what`, FILE and
  LINE left out where none applies; a warning says `warning:` before its rule.
  A relayed line (no file, line or rule) is printed unchanged."
  [{:keys [file line rule message] :as f}]
  (let [where (when file (str file (when line (str ":" line))))]
    (str/join ": " (remove nil? [where (when (warning? f) "warning") rule message]))))

;; ---------------------------------------------------------------------------
;; Paths and the configuration

(defn- root-of [base]
  (str (if (map? base) (:root base) base)))

(defn path
  "The file `rel` names: itself when absolute, else under the project root
  (`base` is a request or a root)."
  [base rel]
  (let [rel (str rel)]
    (if (fs/absolute? rel)
      (fs/file rel)
      (fs/file (root-of base) rel))))

(defn rel
  "`f` as the project names it: relative to the root, written with /, or
  absolute when it lies outside the root."
  [base f]
  (let [root (fs/normalize (fs/absolutize (root-of base)))
        abs (fs/normalize (fs/absolutize (str f)))]
    (if (str/starts-with? (str abs) (str root))
      (let [r (str/replace (str (fs/relativize root abs)) "\\" "/")]
        (if (str/blank? r) "." r))
      (str abs))))

(defn read-config
  "The configuration of the project at `root` (dhf/harness/config.edn), nil
  when it has none; cannot run when it is not readable as an EDN map."
  [root]
  (let [f (fs/file (str root) config-file)]
    (when (fs/regular-file? f)
      (let [c (try (edn/read-string (slurp f))
                   (catch Exception e
                     (cannot-run! (str config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? c) (cannot-run! (str config-file " is not an EDN map")))
        c))))

(defn config-value
  "The configured value `k`; cannot run when the configuration sets none."
  [req k]
  (let [v (get (:config req) k)]
    (if (nil? v)
      (cannot-run! (str "the configuration (" config-file ") sets no " k))
      v)))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` on the run's bus (none: nothing)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

;; ---------------------------------------------------------------------------
;; The task table

(defn statuses
  "Publish's verification stage: derives the statuses when a results
  directory was given (or the configured one exists), so the site can show them."
  [req]
  (if (:results req)
    (let [derive! (requiring-resolve 'rdm-spec.verification.status/derive!)]
      {:findings (vec (:findings (derive! req)))})
    {:findings []}))

(def oracle-stage
  "The oracle, a gate of fence and ratify, the stage of the oracle task."
  'rdm-spec.measurement.oracle/oracle)

(def tasks
  "Every task, in the order help lists them. :args names the positionals
  (:min of them required), :options the options each takes (true: takes a
  value), :config whether the project's configuration must exist, :results
  how the results directory is chosen (:read must exist, :write is made,
  :optional only when given or present), :stages the stages in order (a map
  with :gate? true is a gate), :subscribe the namespaces subscribing besides
  the stages'."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :config :none :stages []}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args ["PATH"] :options {"--id-prefix" :id-prefix} :config :none
    :stages ['rdm-spec.project.init/init]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :config :none :stages ['rdm-spec.project.adopt/adopt]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :stages ['rdm-spec.spec.stamper/stamp]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki"
    :options {"--sources" :sources}
    :stages ['rdm-spec.knowledge.sources/check 'rdm-spec.spec.check/check
             'rdm-spec.architecture.lint/check 'rdm-spec.architecture.views/check]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :stages ['rdm-spec.architecture.views/views]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :config :optional :stages ['rdm-spec.regeneration.cut/cut]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :args ["ROOM" "CUT"] :options {"--context" :context "--component" :component} :config :optional
    :stages [{:stage oracle-stage :gate? true} 'rdm-spec.regeneration.fence/fence]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :options {"--results" :results} :results :write
    :stages ['rdm-spec.kernel.runner/run]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :args ["ROOM" "CYCLE"]
    :stages [{:stage oracle-stage :gate? true} 'rdm-spec.ratification.ratify/ratify]}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args ["CYCLE"] :stages ['rdm-spec.spec.parser/parse 'rdm-spec.measurement.checklist/checklist]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :options {"--results" :results} :results :read
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.status/status]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args ["ID"] :options {"--results" :results} :results :optional
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.trace/trace]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :options {"--results" :results} :results :read
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.release/release]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args ["CYCLE"] :options {"--verdicts" :verdicts} :required-options ["--verdicts"]
    :stages ['rdm-spec.measurement.scorer/score]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args ["CYCLE" "CYCLE"] :stages ['rdm-spec.measurement.scoreboard/scoreboard]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args ["record"] :min-args 0 :options {"--reason" :reason} :config :optional
    :stages [oracle-stage]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :options {"--results" :results} :results :optional
    :subscribe ['rdm-spec.verification.status]
    :stages ['rdm-spec.knowledge.sources/check 'rdm-spec.spec.check/check `statuses
             'rdm-spec.publishing.site/publish]}])

(defn task-named [table task] (first (filter #(= task (:task %)) table)))

(defn- usage-lines [table]
  (for [{:keys [usage does]} table] (str "  rdm-spec " usage ": " does)))

;; ---------------------------------------------------------------------------
;; Arguments

(def global-options
  "Options every task takes: --events (a flag) prints the run's events."
  {"--events" :events?})

(defn parse-args
  "Splits a task's command-line arguments into {:args [..] :opts {..}};
  cannot run on an unknown option, a missing value or a wrong number of
  positionals, naming the task's usage."
  [entry argv]
  (let [{:keys [task usage options]} entry
        usage-note (str "usage: rdm-spec " usage)
        take-value (set (keys options))]
    (loop [xs (seq argv) args [] opts {}]
      (if-let [[x & more] (seq xs)]
        (if (and (str/starts-with? x "--") (> (count x) 2))
          (let [[flag inline] (let [i (str/index-of x "=")] (if i [(subs x 0 i) (subs x (inc i))] [x nil]))]
            (cond
              (take-value flag)
              (let [v (or inline (first more))]
                (when (or (nil? v) (and (nil? inline) (str/starts-with? v "--")))
                  (cannot-run! (str "the option " flag " of " task " takes a value; " usage-note)))
                (recur (if inline more (rest more)) args (assoc opts (get options flag) v)))

              (and (global-options flag) (nil? inline))
              (recur more args (assoc opts (global-options flag) true))

              :else
              (cannot-run! (str "unknown argument " x " for the task " task "; " usage-note))))
          (recur more (conj args x) opts))
        (let [names (:args entry)
              min-n (or (:min-args entry) (count names))]
          (when (< (count args) min-n)
            (cannot-run! (str task " needs " (str/join " " (drop (count args) (take min-n names))) "; " usage-note)))
          (when (> (count args) (count names))
            (cannot-run! (str "unknown argument " (nth args (count names)) " for the task " task "; " usage-note)))
          (doseq [o (:required-options entry)
                  :when (not (contains? opts (get options o)))]
            (cannot-run! (str task " needs the option " o "; " usage-note)))
          {:args args :opts opts})))))

;; ---------------------------------------------------------------------------
;; The results directory

(defn results-dir
  "The task's results directory: --results, else RESULTS_DIR (in `env`),
  else the configured one; for an :optional task the configured one only
  when it exists, else nil. A task reading results (:read) cannot run when
  the directory does not exist."
  [entry {:keys [opts config root env]}]
  (when-let [mode (:results entry)]
    (let [given (or (:results opts) (not-empty (str/trim (str (get env "RESULTS_DIR")))))
          configured (:results-dir config)
          dir (cond given given
                    (= :optional mode) (when (and configured (fs/directory? (path root configured))) configured)
                    :else (or configured
                              (cannot-run! (str "the configuration (" config-file ") sets no :results-dir"
                                                " and no results directory was given"))))]
      (when (and dir (= :read mode) (not (fs/directory? (path root dir))))
        (cannot-run! (str "the results directory " dir " does not exist")))
      dir)))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- stage-of [s] (if (map? s) (:stage s) s))

(defn- resolve-stage
  "The stage function `s` names (a symbol or a function)."
  [s]
  (if (symbol? s)
    (let [v (try (requiring-resolve s)
                 (catch Throwable e
                   (cannot-run! (str "the stage " s " cannot be loaded: " (ex-message e)))))]
      (or v (cannot-run! (str "the stage " s " is not built: " (namespace s) " defines no " (name s)))))
    s))

(defn- ns-built?
  "True when the namespace `ns-sym` is on the classpath."
  [ns-sym]
  (some? (or (find-ns ns-sym)
             (some #(clojure.java.io/resource (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) %))
                   [".clj" ".cljc" ".bb"]))))

(defn- run-gate
  "Runs a gate stage. The oracle's own request has no positionals and no
  options: the task's belong to the task. A gate whose namespace is not
  built at all is skipped with a warning (nothing exists that could judge)."
  [s req]
  (let [sym (stage-of s)]
    (if (and (symbol? sym) (not (ns-built? (symbol (namespace sym)))))
      {:findings [(warning "gate-not-built"
                           (str "the gate " sym " is not built, so it did not run before " (:task req)))]}
      ((resolve-stage sym) (assoc req :args [] :opts {})))))

(defn- subscribe-all!
  "Subscribes every namespace the task's stages belong to (and :subscribe
  names) that has `subscriptions`, in stage order, once each."
  [entry req]
  (let [nss (distinct (concat (for [s (:stages entry)
                                    :let [sym (stage-of s)]
                                    :when (and (symbol? sym) (not (:gate? s)) (namespace sym))]
                                (symbol (namespace sym)))
                              (:subscribe entry)))]
    (doseq [n nss
            :let [_ (try (require n)
                         (catch Throwable e
                           (cannot-run! (str "the namespace " n " cannot be loaded: " (ex-message e)))))
                  subs-fn (ns-resolve n 'subscriptions)]
            :when subs-fn
            [event handler] (subs-fn req)]
      (bus/subscribe! (:bus req) event (keyword (str n)) handler))))

(defn- run-stages
  "Runs the stages in order: {:result :findings :stopped-by}."
  [entry req]
  (loop [ss (:stages entry) result {} findings []]
    (if-let [[s & more] (seq ss)]
      (let [gate? (and (map? s) (:gate? s))
            out (if gate? (run-gate s req) ((resolve-stage (stage-of s)) req))
            out (if (map? out) out {})
            result (if gate? result (merge result (when (map? (:result out)) (:result out))))
            findings (into findings (:findings out))]
        (if (and gate? (seq (remove warning? (:findings out))))
          {:result (if (map? (:result out)) (:result out) {}) :findings (vec (:findings out)) :stopped-by (stage-of s)}
          (recur more result findings)))
      {:result result :findings findings})))

(defn- catalogue-of
  "The configured event catalogue for the bus, nil when there is none
  readable (the model lint names why)."
  [root config]
  (when-let [rel (:events config)]
    (let [f (path root rel)]
      (when (fs/regular-file? f)
        (let [c (try (edn/read-string (slurp f)) (catch Exception _ nil))]
          (when (map? c) c))))))

(defn execute
  "Runs `argv` (the task, then its arguments) for the project at `root`:
  {:exit 0|1|2 :result map :findings [..] :events [..] :events? bool
   :cannot-run why-or-nil :usage [line]}. `opts`: :root, :env (default the
  process environment), :table (default `tasks`)."
  [argv {:keys [root env table] :or {table tasks}}]
  (let [env (or env (into {} (System/getenv)))
        root (str (fs/absolutize (or root (System/getProperty "user.dir"))))
        [task & more] argv
        entry (task-named table task)]
    (if-not entry
      {:exit 2
       :cannot-run (if (str/blank? task)
                     "no task was given; the tasks are:"
                     (str "unknown task " task "; the tasks are:"))
       :usage (vec (usage-lines table))}
      (let [b (atom nil)
            events? (atom false)]
        (try
          (let [{:keys [args opts]} (parse-args entry more)
                _ (reset! events? (boolean (:events? opts)))
                config (when-not (= :none (:config entry)) (read-config root))
                _ (when (and (nil? config) (not (#{:none :optional} (:config entry))))
                    (cannot-run! (str "no " config-file " in " root ": " task " runs in a project set up by rdm-spec"
                                      " (rdm-spec init or rdm-spec adopt)")))
                the-bus (bus/make-bus {:catalogue (catalogue-of root config)
                                       :catalogue-file (:events config)})
                _ (reset! b the-bus)
                req {:task task :args args :opts opts :root root :config config :env env
                     :state (atom {}) :bus the-bus}
                req (assoc req :results (results-dir entry req))
                result (if (= "help" task)
                         {:result {:tasks (mapv #(select-keys % [:task :usage :does]) table)} :findings []}
                         (do (subscribe-all! entry req)
                             (run-stages entry req)))
                errors (remove warning? (:findings result))]
            (assoc result
                   :exit (if (seq errors) 1 0)
                   :events (bus/events the-bus)
                   :events? @events?))
          (catch Throwable e
            {:exit 2
             :cannot-run (if (cannot-run? e)
                           (ex-message e)
                           (str (.getName (class e)) ": " (ex-message e)))}))))))

(defn report
  "What a run prints: {:exit :out \"EDN\" :err [line]}."
  [{:keys [exit result findings events events? cannot-run usage]}]
  (if cannot-run
    {:exit exit :out "" :err (vec (concat [(str "cannot-run: " cannot-run)] usage))}
    {:exit exit
     :out (str (binding [*print-namespace-maps* false] (pr-str (or result {}))) "\n")
     :err (vec (concat (map format-finding (remove warning? findings))
                       (map format-finding (filter warning? findings))
                       (when events? (map bus/format-event events))))}))

(defn run-task
  "Runs the task `argv` in the project at `root`, in process, with the
  environment `env` (nil: the process's): {:exit :out :err [line]}."
  [argv root env]
  (report (execute (vec argv) {:root root :env env})))

(defn -main
  "rdm-spec <task> [args]: runs the task in the current directory and exits
  with its status."
  [& argv]
  (let [{:keys [exit out err]} (run-task argv nil nil)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
