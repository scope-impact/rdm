(ns rdm-spec.kernel.command
  "The kernel component Command (dhf/spec/kernel/command.feature): the one
  entry point, `rdm-spec <task>`, and the composition root. It belongs to
  no bounded context; it dispatches each task to the stages of the
  contexts that own it, in the order the task table gives, over one event
  bus per run (rdm-spec.kernel.bus).

  How every task reports (dhf/spec/README.md): standard output holds only
  the result, as EDN ({} on exit 1 when there is none, nothing when the
  task cannot run); the error stream one line per finding, then one per
  warning (`format-finding`), then with --events one line per event
  published; `cannot-run: why` when it cannot run. Exit 0 a pass, 1 a
  finding, 2 a task that cannot run (an unknown task, argument or id, a
  missing directory, tool or configuration, a state that forbids it, a bus
  refusal: a defect of rdm-spec).

  A stage is {:fn f} (or {:stage 'ns/f}, resolved when the task runs; a
  stage whose code is not built refuses the task), optionally :gate? (its
  findings stop the task, and its result is never printed), :quiet? (its
  findings and result are dropped), :when (fn [req] bool) and :revision?
  (its return value is the request's :revision, read before anything
  else). Each stage's namespace may define `subscriptions` (fn [req]
  [[event handler]]), subscribed before any stage runs.

  The request a stage gets: {:task :args :opts :root :config :env :state
  :bus :results :revision}. :results is --results, else RESULTS_DIR, else
  the configured :results-dir when that directory exists, else nil.

  API for the contexts: config-file, config-value, path, rel, finding,
  warning, warning?, cannot-run!, cannot-run?, format-finding, publish!,
  execute, results-of."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; The configuration

(def config-file
  "Where a project's harness configuration lives."
  "dhf/harness/config.edn")

(def defaults
  "What a configuration that names no value means."
  {:id-prefix "spec"
   :spec-dir "dhf/spec"
   :ledger "dhf/spec/ids.edn"
   :model "dhf/spec/model.edn"
   :events "dhf/spec/events.edn"
   :needs "dhf/spec/needs.edn"
   :views-dir "dhf/views"
   :raw-dir "dhf/raw"
   :wiki-dir "dhf/wiki"
   :steps-dir "test/steps"
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :agency {:watch ["/" "~"] :tmp "/tmp"}})

(defn config-value
  "The value of `k` in the configuration of `req` ({:config ..}), else its
  default."
  [req k]
  (let [c (:config req)]
    (if (and (map? c) (contains? c k) (some? (get c k)))
      (get c k)
      (get defaults k))))

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `rel` names in the project of `root-or-req` (`rel` itself when
  absolute)."
  [root-or-req rel]
  (let [f (fs/file (str rel))]
    (if (fs/absolute? f) f (fs/file (root-of root-or-req) (str rel)))))

(defn rel
  "The path of `f` relative to the project of `root-or-req`, with /."
  [root-or-req f]
  (str/replace (str (fs/relativize (fs/absolutize (root-of root-or-req)) (fs/absolutize (str f)))) "\\" "/"))

;; ---------------------------------------------------------------------------
;; Findings, warnings and refusals

(defn finding
  "A finding: {:file :line :rule :message :severity :error}."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file file :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning, a finding of :severity :warning: it never fails a task."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning?
  "Whether the finding `f` is a warning."
  [f]
  (= :warning (:severity f)))

(defn cannot-run!
  "Stops the task: it cannot run as asked, for the reason `msg` (exit 2)."
  [msg]
  (throw (ex-info (str msg) {::cannot-run true})))

(defn cannot-run?
  "Whether `e` is a task's refusal to run."
  [e]
  (boolean (::cannot-run (ex-data e))))

(defn format-finding
  "The line the error stream holds for the finding `f`."
  [{:keys [file line rule message] :as f}]
  (str (when file (str file (when line (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       rule ": " message))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` with `payload` on the run's bus (no
  bus, as in a unit test: nothing)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload)))

;; ---------------------------------------------------------------------------
;; The results directory

(defn results-of
  "The results directory a task reads: --results, else RESULTS_DIR, else
  the configured one when it exists, else nil (dhf/spec/kernel/command.feature,
  spec-0219)."
  [req]
  (or (not-empty (get-in req [:opts :results]))
      (not-empty (get (:env req) "RESULTS_DIR"))
      (let [d (config-value req :results-dir)]
        (when (fs/directory? (path req d)) d))))

;; ---------------------------------------------------------------------------
;; The task table

(defn- st
  ([sym] {:stage sym})
  ([sym & {:as more}] (merge {:stage sym} more)))

(defn- regulated? [req] (contains? (:config req) :regulated))

(def oracle-gate (st 'rdm-spec.measurement.oracle/gate :gate? true))

(def tasks
  "Every task, in the order help lists them: its usage, what it does, its
  positional arguments ([min max]), its options (the boolean ones in
  :switches) and its stages."
  [{:task "help" :usage "help" :does "list every task with its usage" :arity [0 0]
    :stages [{:fn (fn [_] {:result {:help {:tasks (vec (for [t @(resolve `tasks)]
                                                          (select-keys t [:task :usage :does])))}}
                           :findings []})}]
    :no-config true}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :arity [1 1] :options #{"--id-prefix"} :no-config true
    :stages [(st 'rdm-spec.project.init/init)]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :arity [0 0] :no-config true
    :stages [(st 'rdm-spec.project.adopt/adopt)]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id" :arity [0 0]
    :stages [(st 'rdm-spec.spec.stamper/stamp)]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths"
    :arity [0 0] :options #{"--sources"}
    :stages [(st 'rdm-spec.knowledge.sources/check)
             (st 'rdm-spec.spec.check/check)
             (st 'rdm-spec.architecture.lint/lint)
             (st 'rdm-spec.architecture.views/check)
             (st 'rdm-spec.compliance.gap/gap :when regulated?)
             (st 'rdm-spec.regeneration.fence/paths)]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/" :arity [0 0]
    :stages [(st 'rdm-spec.architecture.views/views)]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag" :arity [0 0]
    :stages [(st 'rdm-spec.regeneration.cut/cut)]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :arity [2 2]
    :stages [(st 'rdm-spec.regeneration.scopes/scopes)]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :arity [2 2] :options #{"--context" "--component"}
    :stages [oracle-gate (st 'rdm-spec.regeneration.fence/fence)]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :arity [0 0] :options #{"--results"}
    :stages [(st 'rdm-spec.kernel.runner/run)]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :arity [2 2]
    :stages [oracle-gate (st 'rdm-spec.verification.verify/verify)]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :arity [1 1]
    :stages [(st 'rdm-spec.measurement.checklist/revision :revision? true)
             (st 'rdm-spec.spec.parser/parse)
             (st 'rdm-spec.compliance.gap/gap :when regulated? :quiet? true)
             (st 'rdm-spec.measurement.checklist/checklist)]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status" :arity [0 0] :options #{"--results"}
    :stages [(st 'rdm-spec.spec.parser/parse) (st 'rdm-spec.verification.status/status)]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :arity [1 1] :options #{"--results"}
    :stages [(st 'rdm-spec.spec.parser/parse) (st 'rdm-spec.verification.trace/trace)]}
   {:task "release" :usage "release [--results DIR]" :does "the release check" :arity [0 0] :options #{"--results"}
    :stages [(st 'rdm-spec.spec.parser/parse)
             (st 'rdm-spec.compliance.gap/gap :when regulated? :quiet? true)
             (st 'rdm-spec.verification.release/release)]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a verified cycle into its score.edn"
    :arity [1 1] :options #{"--verdicts"} :required #{"--verdicts"}
    :stages [(st 'rdm-spec.measurement.scorer/score)]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles" :arity [2 2]
    :stages [(st 'rdm-spec.measurement.scoreboard/scoreboard)]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :arity [0 1] :options #{"--reason"}
    :stages [(st 'rdm-spec.measurement.oracle/oracle)]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it" :arity [0 0]
    :options #{"--results"}
    :stages [(st 'rdm-spec.architecture.views/check :gate? true)
             (st 'rdm-spec.knowledge.sources/check :quiet? true)
             (st 'rdm-spec.spec.check/check :quiet? true)
             (st 'rdm-spec.verification.status/derive :quiet? true)
             (st 'rdm-spec.publishing.site/publish)]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :arity [1 1] :options #{"--results"}
    :stages [(st 'rdm-spec.compliance.audit/revision :revision? true)
             (st 'rdm-spec.compliance.audit/committed :gate? true)
             (st 'rdm-spec.spec.parser/parse)
             (st 'rdm-spec.compliance.gap/gap :quiet? true)
             (st 'rdm-spec.verification.release/release :gate? true)
             (st 'rdm-spec.compliance.audit/audit)]}
   {:task "observe" :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :arity [1 4] :options #{"--tokens"}
    :stages [(st 'rdm-spec.agency.observe/observe)]}])

(defn task-of [name] (first (filter #(= name (:task %)) tasks)))

(def ^:private switches
  "Options that take no value, allowed on every task."
  #{"--events"})

(defn parse-args
  "The command line `argv`: {:task :args :opts :spec} or {:cannot-run why}."
  [argv]
  (let [[name & more] argv
        t (task-of name)
        all (str/join ", " (map :task tasks))]
    (cond
      (str/blank? name)
      {:cannot-run (str "no task was given: rdm-spec <task>; the tasks are " all)}

      (nil? t)
      {:cannot-run (str "there is no task " name ": the tasks are " all " (rdm-spec help lists them with their usage)")}

      :else
      (loop [xs more args [] opts {}]
        (if-let [[x & rest*] (seq xs)]
          (cond
            (switches x) (recur rest* args (assoc opts (keyword (subs x 2)) true))
            (str/starts-with? x "--")
            (cond
              (not (contains? (:options t) x))
              {:cannot-run (str "rdm-spec " name " takes no option " x ": rdm-spec " (:usage t))}
              (or (empty? rest*) (str/starts-with? (first rest*) "--"))
              {:cannot-run (str "the option " x " takes a value: rdm-spec " (:usage t))}
              :else (recur (rest rest*) args (assoc opts (keyword (subs x 2)) (first rest*))))
            :else (recur rest* (conj args x) opts))
          (let [[lo hi] (:arity t)
                missing (remove #(contains? opts (keyword (subs % 2))) (:required t))]
            (cond
              (not (<= lo (count args) hi))
              {:cannot-run (str "rdm-spec " name " takes " (if (zero? hi) "no argument" (str/join " " (remove #(str/starts-with? % "[") (rest (str/split (:usage t) #"\s+")))))
                                (when (seq args) (str ", not " (str/join " " args)))
                                ": rdm-spec " (:usage t))}
              (seq missing)
              {:cannot-run (str "rdm-spec " name " needs " (str/join ", " missing) ": rdm-spec " (:usage t))}
              :else {:task name :args args :opts opts :spec t})))))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage [{:keys [stage] :as s}]
  (if (:fn s)
    s
    (let [f (try (requiring-resolve stage) (catch Throwable _ nil))]
      (when-not f
        (cannot-run! (str "the stage " stage " is not built, so the task cannot run: a stage skipped is a check"
                          " that reads as a pass")))
      (assoc s :fn f))))

(defn- subscribe-all! [req stages]
  (let [nss (distinct (keep #(some-> (:stage %) namespace symbol) stages))]
    (doseq [n nss
            :let [sub (ns-resolve (the-ns n) 'subscriptions)]
            :when sub
            [event handler] (sub req)]
      (bus/subscribe! (:bus req) event handler))))

(defn run-stages
  "Runs the stages in order over `req`: {:result :findings :stopped?}."
  [req stages]
  (loop [[s & more] stages req req result {} findings []]
    (if-not s
      {:result result :findings findings :stopped? false}
      (if (and (:when s) (not ((:when s) req)))
        (recur more req result findings)
        (let [out ((:fn s) req)]
          (cond
            (:revision? s) (recur more (assoc req :revision out) result findings)
            (:quiet? s) (recur more req result findings)
            (:gate? s) (let [fs* (vec (:findings out))]
                         (if (some (complement warning?) fs*)
                           {:result result :findings (into findings fs*) :stopped? true}
                           (recur more req result (into findings fs*))))
            :else (recur more req (merge result (:result out)) (into findings (:findings out)))))))))

(defn- read-config [root]
  (let [f (path root config-file)]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "there is no " config-file " in " root ": run rdm-spec in a project rdm-spec set up")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (cannot-run! (str config-file " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (cannot-run! (str config-file " is not an EDN map")))
      c)))

(defn execute
  "Runs the task `argv` in the project `root` (opts {:root :env}), in this
  process: {:exit :result :findings :events :cannot-run :opts}."
  [argv {:keys [root env]}]
  (let [root (str (or root (System/getProperty "user.dir")))
        env (or env (into {} (System/getenv)))
        parsed (parse-args argv)]
    (if (:cannot-run parsed)
      {:exit 2 :cannot-run (:cannot-run parsed) :findings [] :events []}
      (let [b (bus/bus)
            {:keys [task args opts spec]} parsed]
        (try
          (let [config (when-not (:no-config spec) (read-config root))
                req0 {:task task :args args :opts opts :root root :config config :env env
                      :state (atom {}) :bus b}
                req (assoc req0 :results (results-of req0))
                stages (mapv resolve-stage (:stages spec))
                _ (subscribe-all! req stages)
                {:keys [result findings]} (run-stages req stages)
                errors (remove warning? findings)]
            {:exit (if (seq errors) 1 0)
             :result result
             :findings (vec (concat errors (filter warning? findings)))
             :events (bus/published b)
             :opts opts})
          (catch clojure.lang.ExceptionInfo e
            (cond
              (cannot-run? e) {:exit 2 :cannot-run (ex-message e) :findings [] :events (bus/published b) :opts opts}
              (bus/refused? e) {:exit 2 :cannot-run (str "the event bus refused an event, a defect of rdm-spec: " (ex-message e))
                                :findings [] :events (bus/published b) :opts opts}
              :else (throw e))))))))

(defn -main
  "rdm-spec <task> [args]: runs the task in the working directory and exits
  with its code."
  [& argv]
  (let [{:keys [exit result findings events cannot-run opts]}
        (try (execute (vec argv) {})
             (catch Throwable e
               {:exit 2 :cannot-run (str "rdm-spec failed, a defect of rdm-spec: " (.getName (class e)) ": " (ex-message e))
                :findings [] :events []}))]
    (binding [*print-namespace-maps* false]
      (when-not cannot-run
        (println (pr-str (or result {}))))
      (binding [*out* *err*]
        (doseq [f findings] (println (format-finding f)))
        (when cannot-run (println (str "cannot-run: " cannot-run)))
        (when (:events opts)
          (doseq [e events] (println (bus/event-line e))))))
    (flush)
    (.flush System/err)
    (System/exit exit)))
