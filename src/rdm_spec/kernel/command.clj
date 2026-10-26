(ns rdm-spec.kernel.command
  "The kernel component Command (dhf/spec/kernel/command.feature): the one
  entry point, `rdm-spec <task>`, and the composition root. It belongs to no
  bounded context: it dispatches each task to the stages of the contexts
  that own it, wires them to one event bus per run, and reports for all of
  them the same way (dhf/spec/README.md, \"How every task reports\").

  A task is a list of stages, each a function of the request returning
  {:result map :findings [finding]}; the results are merged into the
  task's result. A stage may be marked:
    :gate       it stops the task when it has findings that are not warnings
                (glossary, \"Gate\"); the stages after it never run
    :quiet      it runs for what it publishes; its result and findings are dropped
    :regulated  it runs only when the configuration holds :regulated
  A stage whose namespace is not built refuses the task (exit 2).

  The request a stage gets:
    {:task :args :opts :root :config :env :state (atom) :bus
     :results (status, trace, release, publish, audit) :revision}
  :results is --results, else RESULTS_DIR, else the configured results
  directory when it exists, else nil (spec-0219).

  A finding is {:file :line :rule :message :severity :error | :warning};
  `cannot-run!` throws, and the task exits 2 saying `cannot-run: why`.

  API: tasks, task, execute, -main, finding, warning, warning?,
  format-finding, cannot-run!, cannot-run?, config-file, config-value,
  path, rel, publish!, parse-args, results-dir, self-command."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings

(defn- finding-of [severity args]
  (let [[file line rule message] (case (count args)
                                   2 [nil nil (first args) (second args)]
                                   3 [(first args) nil (second args) (nth args 2)]
                                   4 args)]
    {:file file :line line :rule rule :message message :severity severity}))

(defn finding
  "A finding: (finding rule message), (finding file rule message) or
  (finding file line rule message); file and line may be nil."
  [& args]
  (finding-of :error args))

(defn warning
  "A warning, with the arguments of `finding`."
  [& args]
  (finding-of :warning args))

(defn warning?
  "Whether the finding `f` is a warning."
  [f]
  (= :warning (:severity f)))

(defn- one-line [s] (str/trim (str/replace (str s) #"\s*\r?\n\s*" " ")))

(defn format-finding
  "The error-stream line of the finding `f`: FILE:LINE: RULE: what, FILE
  and LINE left out where none applies, `warning: ` before the rule of a
  warning. A relayed line (:raw) is printed as it is."
  [{:keys [file line rule message raw] :as f}]
  (or raw
      (str (when file (str file (when line (str ":" line)) ": "))
           (when (warning? f) "warning: ")
           rule ": " (one-line message))))

(defn cannot-run!
  "Stops the task: it cannot run as asked, and exits 2 saying `why`."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "Whether `e` is a task's refusal to run."
  [e]
  (boolean (::cannot-run (ex-data e))))

;; ---------------------------------------------------------------------------
;; The configuration and the project's paths

(def config-file "dhf/harness/config.edn")

(def defaults
  "What a configuration that names nothing else means."
  {:id-prefix "spec"
   :spec-dir "dhf/spec"
   :ledger "dhf/spec/ids.edn"
   :model "dhf/spec/model.edn"
   :events "dhf/spec/events.edn"
   :needs "dhf/spec/needs.edn"
   :domain "dhf/spec/domain.edn"
   :views-dir "dhf/views"
   :raw-dir "dhf/raw"
   :wiki-dir "dhf/wiki"
   :stack-words []
   :steps-dir "test/steps"
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :agency {:watch ["/" "~"] :tmp "/tmp"}
   :c4 {:view-element-limit 20 :description-max-chars 45}})

(defn config-value
  "The configuration's `k` of `x` (a request, or anything holding :config),
  else its default."
  [x k]
  (let [cfg (when (map? x) (:config x))]
    (if (and (map? cfg) (contains? cfg k)) (get cfg k) (get defaults k))))

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `rel` names in the project of `x` (a request or a root); an
  absolute `rel` is itself."
  [x rel]
  (let [r (str rel)]
    (if (fs/absolute? r) (fs/file r) (fs/file (root-of x) r))))

(defn rel
  "The path of `f` relative to the project of `x`, written with /."
  [x f]
  (str/replace (str (fs/relativize (fs/absolutize (root-of x)) (fs/absolutize f))) "\\" "/"))

(defn- read-config [root]
  (let [f (fs/file root config-file)]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "there is no " config-file " in " root ": run rdm-spec in a project set up by rdm-spec"
                        " init or adopt")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (cannot-run! (str config-file " is not readable EDN: " (one-line (ex-message e))))))]
      (when-not (map? c) (cannot-run! (str config-file " is not an EDN map")))
      c)))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the run's bus (none
  outside a run). A refusal of the bus is a defect of rdm-spec: cannot-run."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (try (bus/publish! b publisher event payload)
         (catch clojure.lang.ExceptionInfo e
           (if (bus/refused? e) (cannot-run! (ex-message e)) (throw e))))))

;; ---------------------------------------------------------------------------
;; The task table

(def tasks
  "Every task: its usage, what it does, its options (a value, or :flag),
  how many arguments it takes (nil: any), whether it needs the
  configuration, the commit it reads the spec at, and its stages."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :arity 0 :config? false :stages ['rdm-spec.kernel.command/help]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :arity 1 :options {"--id-prefix" :id-prefix} :config? false :stages ['rdm-spec.project.init/init]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :arity 0 :config? false :stages ['rdm-spec.project.adopt/adopt]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :arity 0 :stages ['rdm-spec.spec.stamper/stamp]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the domain model, the views' freshness, sources, wiki, clauses, paths"
    :arity 0 :options {"--sources" :sources}
    :stages ['rdm-spec.knowledge.sources/check
             'rdm-spec.spec.check/check
             'rdm-spec.architecture.lint/lint
             'rdm-spec.architecture.domain/check
             'rdm-spec.architecture.views/check
             [:regulated 'rdm-spec.compliance.gap/gap]
             'rdm-spec.regeneration.fence/paths]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :arity 0 :stages ['rdm-spec.architecture.views/views]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :arity 0 :stages ['rdm-spec.regeneration.cut/cut]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :arity 2 :stages ['rdm-spec.regeneration.scopes/scopes]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :arity 2 :options {"--context" :context "--component" :component}
    :stages [[:gate 'rdm-spec.measurement.oracle/gate] 'rdm-spec.regeneration.fence/fence]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :arity 0 :options {"--results" :results} :stages ['rdm-spec.kernel.runner/run]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :arity 2 :stages [[:gate 'rdm-spec.measurement.oracle/gate] 'rdm-spec.verification.verify/verify]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :arity 1 :revision 'rdm-spec.measurement.checklist/revision
    :stages ['rdm-spec.spec.parser/parse
             [:quiet :regulated 'rdm-spec.compliance.gap/gap]
             'rdm-spec.measurement.checklist/checklist]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :arity 0 :options {"--results" :results} :results? true
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.status/status]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :arity 1 :options {"--results" :results} :results? true
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.trace/trace]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :arity 0 :options {"--results" :results} :results? true
    :stages ['rdm-spec.spec.parser/parse
             [:quiet :regulated 'rdm-spec.compliance.gap/gap]
             'rdm-spec.verification.release/release]}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a verified cycle into its score.edn"
    :arity 1 :options {"--verdicts" :verdicts} :stages ['rdm-spec.measurement.scorer/score]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :arity 2 :stages ['rdm-spec.measurement.scoreboard/scoreboard]}
   {:task "oracle" :usage "oracle [ARGS]" :does "run the oracle the project keeps, the command its configuration names"
    :arity nil :passthrough? true :stages ['rdm-spec.measurement.oracle/oracle]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :arity 0 :options {"--results" :results} :results? true
    :stages [[:gate 'rdm-spec.architecture.views/check]
             [:quiet 'rdm-spec.knowledge.sources/check]
             [:quiet 'rdm-spec.spec.check/check]
             'rdm-spec.verification.status/derive
             'rdm-spec.publishing.site/publish]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :arity 1 :options {"--results" :results} :results? true :revision 'rdm-spec.compliance.audit/revision
    :stages [[:gate 'rdm-spec.compliance.audit/committed]
             'rdm-spec.spec.parser/parse
             [:quiet 'rdm-spec.compliance.gap/gap]
             [:gate 'rdm-spec.verification.release/release]
             'rdm-spec.compliance.audit/audit]}
   {:task "observe" :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :arity nil :options {"--tokens" :tokens} :stages ['rdm-spec.agency.observe/observe]}])

(defn task
  "The task named `nm`, or nil."
  [nm]
  (first (filter #(= nm (:task %)) tasks)))

(defn help
  "The help task's stage: every task with its usage."
  [_req]
  {:result {:help (mapv #(select-keys % [:task :usage :does]) tasks)} :findings []})

;; ---------------------------------------------------------------------------
;; Arguments

(defn parse-args
  "The arguments and options of the task `nm`'s `argv`: {:args :opts}.
  --events is every task's flag; an option the task does not take, or one
  with no value, cannot run."
  [nm argv]
  (let [t (task nm)]
    (if (:passthrough? t)
      {:args (vec argv) :opts {}}
      (loop [[a & more] argv args [] opts {}]
        (cond
          (nil? a) {:args args :opts opts}
          (= "--events" a) (recur more args (assoc opts :events true))
          (str/starts-with? a "--")
          (if-let [k (get (:options t) a)]
            (if (or (nil? (first more)) (str/starts-with? (first more) "--"))
              (cannot-run! (str a " needs a value: rdm-spec " (:usage t)))
              (recur (rest more) args (assoc opts k (first more))))
            (cannot-run! (str "the argument " a " is not one rdm-spec " nm " takes: rdm-spec " (:usage t))))
          :else (recur more (conj args a) opts))))))

(defn results-dir
  "The results directory of the request `req`: --results, else RESULTS_DIR,
  else the configured one when it exists, else nil."
  [req]
  (or (not-empty (get-in req [:opts :results]))
      (not-empty (get (:env req) "RESULTS_DIR"))
      (let [d (config-value req :results-dir)]
        (when (fs/directory? (path req d)) d))))

;; ---------------------------------------------------------------------------
;; Running a task

(def src-dir
  "The source directory this namespace was loaded from."
  (some-> *file* str not-empty fs/absolutize fs/parent fs/parent fs/parent str))

(defn self-command
  "A command line that runs this same rdm-spec: the bb running it, with the
  source directory this namespace was loaded from (RDM_SPEC, spec-0344)."
  []
  (let [bb (or (try (let [exe (str (fs/read-link "/proc/self/exe"))] (when (re-find #"(^|/)bb$" exe) exe))
                    (catch Exception _ nil))
               (some-> (fs/which "bb") str)
               "bb")]
    (str/join " " [bb (str "-Djava.io.tmpdir=" (System/getProperty "java.io.tmpdir"))
                   "-cp" src-dir "-m" "rdm-spec.kernel.command"])))

(defn- stage-spec [s]
  (if (vector? s)
    {:sym (last s) :flags (set (butlast s))}
    {:sym s :flags #{}}))

(defn- resolve-stage! [t sym]
  (or (try (requiring-resolve sym) (catch Exception _ nil))
      (cannot-run! (str "the stage " sym " of the task " (:task t) " is not built: a stage skipped is a check"
                        " that reads as a pass"))))

(defn- subscriptions-of [ns-sym]
  (when-let [v (ns-resolve (find-ns ns-sym) 'subscriptions)]
    (when (fn? @v) @v)))

(defn- run-task [t argv {:keys [root env]}]
  (let [{:keys [args opts]} (parse-args (:task t) argv)
        _ (when (and (:arity t) (not= (:arity t) (count args)))
            (cannot-run! (str "rdm-spec " (:task t) " takes " (:usage t) ", not " (pr-str (vec argv)))))
        cfg (if (false? (:config? t)) (or (try (read-config root) (catch Exception _ nil)) {}) (read-config root))
        b (bus/bus)
        req0 {:task (:task t) :args args :opts opts :root (str root) :config cfg :env env
              :state (atom {}) :bus b}
        req (cond-> req0 (:results? t) (assoc :results (results-dir req0)))
        stages (for [s (:stages t)
                     :let [{:keys [sym flags]} (stage-spec s)]
                     :when (or (not (:regulated flags)) (contains? cfg :regulated))]
                 {:sym sym :flags flags :f (resolve-stage! t sym)})
        stages (vec stages)
        req (if-let [r (:revision t)] (assoc req :revision ((resolve-stage! t r) req)) req)]
    (doseq [ns-sym (distinct (map (comp symbol namespace :sym) stages))
            :let [subs-fn (subscriptions-of ns-sym)]
            :when subs-fn
            [event handler] (subs-fn req)]
      (try (bus/subscribe! b event handler)
           (catch clojure.lang.ExceptionInfo e
             (if (bus/refused? e) (cannot-run! (ex-message e)) (throw e)))))
    (loop [[{:keys [sym flags f]} & more] stages result {} findings []]
      (if (nil? f)
        {:result result :findings findings :events (bus/published b)}
        (let [out (f req)]
          (if (:relay out)
            (assoc out :events (bus/published b))
            (let [fs* (vec (:findings out))
                  stop? (and (:gate flags) (some (complement warning?) fs*))]
              (if (:quiet flags)
                (recur more result findings)
                (let [result (merge result (:result out))
                      findings (into findings fs*)]
                  (if stop?
                    {:result result :findings findings :events (bus/published b)}
                    (recur more result findings)))))))))))

(defn execute
  "Runs the task `argv` (task name first) in the project `root` with the
  environment `env`, in this process: {:exit :result :findings :events
  :cannot-run :relay}."
  [argv {:keys [root env] :as opts}]
  (let [[nm & more] argv
        t (task nm)]
    (try
      (when-not t
        (cannot-run! (str (if nm (str "unknown task " nm) "no task given")
                          ": the tasks are " (str/join ", " (map :task tasks)) " (rdm-spec help lists their usage)")))
      (let [out (run-task t (vec more) {:root (or root (str (fs/cwd))) :env (or env {})})]
        (if-let [rl (:relay out)]
          (assoc out :exit (:exit rl))
          (assoc out :exit (if (some (complement warning?) (:findings out)) 1 0))))
      (catch clojure.lang.ExceptionInfo e
        (cond (cannot-run? e) {:exit 2 :cannot-run (one-line (ex-message e))}
              (bus/refused? e) {:exit 2 :cannot-run (one-line (ex-message e))}
              :else {:exit 2 :cannot-run (one-line (str "rdm-spec " nm " failed: " (ex-message e)))}))
      (catch Throwable e
        {:exit 2 :cannot-run (one-line (str "rdm-spec " nm " failed: " (.getName (class e)) ": " (ex-message e)))}))))

(defn -main [& argv]
  (let [events? (some #{"--events"} (when-not (= "oracle" (first argv)) argv))
        {:keys [exit result findings events cannot-run relay]}
        (execute (vec argv) {:root (str (fs/cwd)) :env (into {} (System/getenv))})]
    (cond
      cannot-run (binding [*out* *err*] (println (str "cannot-run: " cannot-run)))
      relay (do (print (:out relay)) (flush)
                (binding [*out* *err*] (print (:err relay)) (flush)))
      :else
      (do (binding [*print-namespace-maps* false] (println (pr-str (or result {}))))
          (binding [*out* *err*]
            (doseq [f (remove warning? findings)] (println (format-finding f)))
            (doseq [f (filter warning? findings)] (println (format-finding f))))))
    (when events?
      (binding [*out* *err*] (doseq [e events] (println (bus/event-line e)))))
    (flush)
    (binding [*out* *err*] (flush))
    (System/exit exit)))
