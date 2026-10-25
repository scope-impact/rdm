(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point, `rdm-spec
  <task>`, and the composition root. It belongs to no bounded context: it
  parses the command line, reads the configuration, makes the run's event
  bus, and runs the task's stages, each the code of the context that owns it.

  How every task reports (dhf/spec/README.md, \"How every task reports\"):
  standard output holds only the task's result, as EDN ({} when there is
  none); the error stream holds one line per finding (`format-finding`),
  then one per warning, then, with --events, one per event published. Exit
  0 is a pass, 1 a finding, 2 a task that cannot run (`cannot-run!`), which
  prints `cannot-run: why` and nothing on standard output.

  The task table (`tasks`). A stage is a function of the request returning
  {:result {...} :findings [...]}; its namespace's `subscriptions`, when it
  has one, gives [[event handler] ...] subscribed on the run's bus before the
  first stage runs. Every stage reports, so one run names every finding,
  except:
    a gate   runs first and, when it has findings that are not warnings,
             stops the task: the stages after it never run;
    a quiet  stage runs for what it publishes and refuses; its result and
             findings are not the task's.
  A stage whose code is not built refuses the task (exit 2). A stage is
  {:fn 'ns/fn} (or {:run f}, a function, as the unit tests give one), with
  :gate?, :quiet? and :when (a predicate of the request) where they apply.

  The request a stage receives:
    {:task \"check\" :args [positional] :opts {:sources .. :results ..}
     :root dir :config map-or-nil :env {name value} :results dir-or-nil
     :events? bool :bus bus :state (atom {})}
  :results is --results, else RESULTS_DIR, else the configured
  :results-dir; for a task whose results are optional (trace, publish) the
  configured one only when it exists."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

(def config-file "dhf/harness/config.edn")

;; ---------------------------------------------------------------------------
;; Refusals

(defn cannot-run
  "The exception of a task that cannot run as asked (exit 2)."
  ([message] (cannot-run message {}))
  ([message data] (ex-info (str message) (assoc (or data {}) ::cannot-run true))))

(defn cannot-run!
  "Refuses the task: it cannot run as asked (exit 2), saying why."
  [message & [data]]
  (throw (cannot-run message data)))

(defn cannot-run?
  "True when `e` refuses a task (cannot-run!)."
  [e]
  (boolean (::cannot-run (ex-data e))))

;; ---------------------------------------------------------------------------
;; Findings

(defn finding
  "A finding: what is wrong, where (`file` and `line` nil where none
  applies), under a stable kebab-case `rule`."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning: printed after the findings; it never fails a task."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message] (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The finding's line on the error stream: `FILE:LINE: RULE: what`, FILE and
  LINE left out where none applies; a warning's `FILE:LINE: warning: RULE:
  what`."
  [{:keys [file line rule message] :as f}]
  (str (when-not (str/blank? (str file)) (str file (when line (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       rule ": " message))

;; ---------------------------------------------------------------------------
;; Paths and configuration

(defn- root-of [base] (str (if (map? base) (:root base) base)))

(defn path
  "The file `rel` names, relative to the project root (`base`, a request or
  a root directory) unless absolute."
  [base rel]
  (let [r (str rel)]
    (if (fs/absolute? r) (fs/file r) (fs/file (root-of base) r))))

(defn rel
  "`f` as the project names it: relative to the root (with /), or absolute
  when it lies outside the project."
  [base f]
  (let [root (fs/normalize (fs/absolutize (root-of base)))
        abs (fs/normalize (fs/absolutize (path base f)))]
    (if (fs/starts-with? abs root)
      (str/replace (str (fs/relativize root abs)) "\\" "/")
      (str abs))))

(defn read-config
  "The configuration of the project at `root`, or nil when it has none.
  Cannot run when it is not an EDN map."
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
  (when-not (:config req) (cannot-run! (str "no configuration " config-file)))
  (let [v (get (:config req) k)]
    (when (nil? v) (cannot-run! (str "the configuration " config-file " sets no " k)))
    v))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the run's bus."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

(defn- make-bus
  "The run's bus, checking events against the configured catalogue (none
  when the configuration names none or it cannot be read as a map)."
  [root config]
  (let [rel* (:events config)
        f (when rel* (path root rel*))
        c (when (and f (fs/regular-file? f))
            (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))]
    (bus/make-bus {:catalogue (when (map? c) c) :catalogue-file (when (map? c) (str rel*))})))

;; ---------------------------------------------------------------------------
;; The task table

(defn- stage [f & {:as o}] (merge {:fn f} o))

(defn- regulated? [req] (contains? (:config req) :regulated))

(def tasks
  "Every task, in the order help lists them: :usage, :does, :args (the
  positional arguments), :optional-args, :opts {flag key}, :config
  (:required or :optional), :results (:required, :optional or nil), :stages."
  [{:task "help" :usage "help" :does "list every task with its usage" :config :none :stages []}
   {:task "init" :usage "init PATH [--id-prefix P]"
    :does "write the skeleton into a path that does not exist"
    :args ["PATH"] :opts {"--id-prefix" :id-prefix} :config :none
    :stages [(stage 'rdm-spec.project.init/init)]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :config :none :stages [(stage 'rdm-spec.project.adopt/adopt)]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :stages [(stage 'rdm-spec.spec.stamper/stamp)]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses"
    :opts {"--sources" :sources}
    :stages [(stage 'rdm-spec.knowledge.sources/check)
             (stage 'rdm-spec.spec.check/check)
             (stage 'rdm-spec.architecture.lint/check)
             (stage 'rdm-spec.architecture.views/check)
             (stage 'rdm-spec.compliance.gap/gap :when regulated?)]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :stages [(stage 'rdm-spec.architecture.views/views)]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :config :optional :stages [(stage 'rdm-spec.regeneration.cut/cut)]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args ["CUT" "CUT"] :config :optional :stages [(stage 'rdm-spec.regeneration.scopes/scopes)]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :args ["ROOM" "CUT"] :opts {"--context" :context "--component" :component} :config :optional
    :stages [(stage 'rdm-spec.measurement.oracle/gate :gate? true)
             (stage 'rdm-spec.regeneration.fence/fence)]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :opts {"--results" :results} :results :required
    :stages [(stage 'rdm-spec.kernel.runner/run)]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :args ["ROOM" "CYCLE"] :config :optional
    :stages [(stage 'rdm-spec.measurement.oracle/gate :gate? true)
             (stage 'rdm-spec.ratification.ratify/ratify)]}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args ["CYCLE"]
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?)
             (stage 'rdm-spec.measurement.checklist/checklist)]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :opts {"--results" :results} :results :required
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.verification.status/status)]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args ["ID"] :opts {"--results" :results} :results :optional
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.verification.trace/trace)]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :opts {"--results" :results} :results :required
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?)
             (stage 'rdm-spec.verification.release/release)]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args ["CYCLE"] :opts {"--verdicts" :verdicts} :required-opts [:verdicts]
    :stages [(stage 'rdm-spec.measurement.scorer/score)]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args ["CYCLE" "CYCLE"] :stages [(stage 'rdm-spec.measurement.scoreboard/scoreboard)]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :optional-args ["record"] :opts {"--reason" :reason} :config :optional
    :stages [(stage 'rdm-spec.measurement.oracle/oracle)]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :opts {"--results" :results} :results :optional
    :stages [(stage 'rdm-spec.knowledge.sources/check :quiet? true)
             (stage 'rdm-spec.spec.check/check :quiet? true)
             (stage 'rdm-spec.architecture.views/check :gate? true)
             (stage 'rdm-spec.verification.status/status :quiet? true)
             (stage 'rdm-spec.publishing.site/publish)]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args ["OUT"] :opts {"--results" :results} :results :required
    :stages [(stage 'rdm-spec.compliance.audit/prepare)
             (stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?)
             (stage 'rdm-spec.verification.release/release :gate? true)
             (stage 'rdm-spec.compliance.audit/audit)]}])

(def ^:private by-name (into {} (map (juxt :task identity)) tasks))

(defn task-list
  "The tasks as help lists them: [{:task :usage :does}]."
  []
  (mapv #(select-keys % [:task :usage :does]) tasks))

(defn- every-task [] (str/join ", " (map :task tasks)))

;; ---------------------------------------------------------------------------
;; The command line

(defn- usage-of [t] (str "rdm-spec " (:usage t)))

(defn parse-args
  "The words after the task name: {:args [..] :opts {..} :events? bool}.
  Cannot run on an unknown option, a missing value or the wrong number of
  arguments."
  [t words]
  (let [opts-of (:opts t)
        bad (fn [why] (cannot-run! (str why ": " (usage-of t))))]
    (loop [ws (seq words) args [] opts {} events? false]
      (if-let [[w & more] (seq ws)]
        (cond
          (= "--events" w) (recur more args opts true)
          (str/starts-with? w "--")
          (let [[flag inline] (str/split w #"=" 2)
                k (get opts-of flag)]
            (when-not k (bad (str "the task " (:task t) " takes no option " flag)))
            (cond
              inline (recur more args (assoc opts k inline) events?)
              (and (seq more) (not (str/starts-with? (first more) "--")))
              (recur (rest more) args (assoc opts k (first more)) events?)
              :else (bad (str "the option " flag " needs a value"))))
          :else (recur more (conj args w) opts events?))
        (let [n (count (:args t))
              optional (:optional-args t)]
          (cond
            (seq optional)
            (when-not (or (empty? args) (and (= 1 (count args)) (some #{(first args)} optional)))
              (bad (str "unexpected arguments " (str/join " " args))))
            (< (count args) n) (bad (str "missing " (str/join " " (drop (count args) (:args t)))))
            (> (count args) n) (bad (str "unexpected arguments " (str/join " " (drop n args)))))
          (doseq [k (:required-opts t) :when (nil? (get opts k))]
            (bad (str "the option --" (name k) " is required")))
          {:args args :opts opts :events? events?})))))

(defn- results-dir
  "The run's results directory: --results, else RESULTS_DIR, else the
  configured one (for optional results, only when it exists)."
  [t root config opts env]
  (when-let [mode (:results t)]
    (let [given (or (not-empty (:results opts)) (not-empty (str/trim (str (get env "RESULTS_DIR")))))
          configured (:results-dir config)]
      (cond
        given given
        (= :required mode) (or configured
                               (cannot-run! (str "no results directory: give --results DIR, set RESULTS_DIR,"
                                                 " or configure :results-dir in " config-file)))
        (and configured (fs/directory? (path root configured))) configured
        :else nil))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- built? [sym]
  (some? (io/resource (str (-> (namespace sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))))

(defn- resolve-fn
  "The var `sym` names, or nil when its code is not built."
  [sym]
  (when (built? sym)
    (requiring-resolve sym)))

(defn- stage-fn [{f :fn run :run}]
  (or run
      (resolve-fn f)
      (cannot-run! (str "the stage " f " is not built, so the task cannot run"))))

(defn- subscriptions-of [{f :fn}]
  (when (and f (built? f))
    (require (symbol (namespace f)))
    (ns-resolve (symbol (namespace f)) 'subscriptions)))

(defn- subscribe! [req stages]
  (doseq [st stages
          :let [subs (subscriptions-of st)]
          :when subs
          [event handler] (subs req)]
    (bus/subscribe! (:bus req) event (keyword (namespace (:fn st))) handler)))

(defn- errors [findings] (remove warning? findings))

(defn- run-stages
  "Runs the stages in order: {:result :findings}."
  [req stages]
  (loop [sts stages result {} findings []]
    (if-let [[st & more] (seq sts)]
      (let [out ((stage-fn st) req)
            fs* (vec (:findings out))]
        (cond
          (and (:gate? st) (seq (errors fs*)))
          {:result result :findings (into findings fs*)}

          (:quiet? st) (recur more result findings)

          (:gate? st) (recur more result (into findings fs*))

          :else (recur more (merge result (:result out)) (into findings fs*))))
      {:result result :findings findings})))

(defn execute
  "Runs the command line `args` (the task name first) in the project at
  `root`, with the environment `env` (default: this process's):
  {:exit 0|1|2 :task :result :findings [..] :events [..] :events? bool
   :cannot-run why-or-nil}."
  [args {:keys [root env]}]
  (let [root (str (fs/absolutize (or root (System/getProperty "user.dir"))))
        env (or env (into {} (System/getenv)))
        [task-name & words] args
        b (atom nil)]
    (try
      (let [t (or (get by-name task-name)
                  (cannot-run! (str (if (str/blank? task-name)
                                      "no task given"
                                      (str "unknown task " task-name))
                                    ": the tasks are " (every-task) " (rdm-spec help lists their usage)")))
            {:keys [args opts events?]} (parse-args t words)]
        (if (= "help" (:task t))
          {:exit 0 :task "help" :result {:tasks (task-list)} :findings [] :events []}
          (let [config (when-not (= :none (:config t)) (read-config root))
                _ (when (and (nil? config) (= :required (get t :config :required)))
                    (cannot-run! (str "no configuration " config-file " in " root
                                      ": run rdm-spec init or rdm-spec adopt first")))
                bus* (make-bus root config)
                _ (reset! b bus*)
                req {:task (:task t) :args args :opts opts :root root :config config :env env
                     :results (results-dir t root config opts env)
                     :events? events? :bus bus* :state (atom {})}
                stages (filter #(if-let [w (:when %)] (w req) true) (:stages t))
                _ (subscribe! req stages)
                {:keys [result findings]} (run-stages req stages)
                ordered (vec (concat (errors findings) (filter warning? findings)))]
            {:exit (if (seq (errors findings)) 1 0) :task (:task t)
             :result (or result {}) :findings ordered
             :events (bus/events bus*) :events? events?})))
      (catch clojure.lang.ExceptionInfo e
        (if (cannot-run? e)
          {:exit 2 :task task-name :cannot-run (ex-message e) :findings []
           :events (some-> @b bus/events)}
          (throw e))))))

(defn render
  "The run as printed: {:exit :out text :err [line]}."
  [{:keys [exit result findings events events? cannot-run]}]
  (if cannot-run
    {:exit exit :out "" :err [(str "cannot-run: " (str/replace cannot-run #"\s*\n\s*" " "))]}
    {:exit exit
     :out (str (binding [*print-namespace-maps* false] (pr-str (or result {}))) "\n")
     :err (vec (concat (map format-finding findings)
                       (when events? (map bus/format-event events))))}))

(defn run-task
  "Runs the command line `args` in `dir` with `env` (nil: this process's):
  {:exit :out :err [line]}, what `rdm-spec` prints."
  [args dir env]
  (render (execute args {:root dir :env env})))

(defn -main
  "`rdm-spec <task> ...`: runs the task in the current directory and exits
  with its status."
  [& args]
  (let [{:keys [exit out err]}
        (try (run-task (vec args) (System/getProperty "user.dir") nil)
             (catch Throwable e
               {:exit 2 :out ""
                :err [(str "cannot-run: rdm-spec failed: " (.getName (class e)) ": " (ex-message e))]}))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
