(ns rdm-spec.kernel.command
  "The command (dhf/spec/kernel/command.feature): rdm-spec <task>, the one
  entry point a hook, CI, an agent and a person all use, and the composition
  root. It belongs to no bounded context: it dispatches each task to the
  stages of the contexts that own it, by the task table (`tasks`).

  A task runs its stages in order on one event bus (kernel/bus), every
  stage's subscriptions made before the first stage runs. Every stage
  reports, so one run names every finding; a gate (:gate? in the table)
  stops the task when it has findings that are not warnings, and the stages
  after it never run. A quiet stage (:quiet?) runs for its events only: its
  result and findings are not reported. A stage marked :regulated? runs only
  when the configuration holds :regulated. A stage whose code is not built,
  a gate or not, refuses the task (exit 2) before any stage runs: a stage
  skipped is a check that reads as a pass (dhf/spec/glossary.md, \"Gate\").

  A stage is (fn [req] {:result map :findings [finding]}); req holds :task
  :root (absolute) :config :args :opts :env :results :revision :state (an
  atom the stages' subscriptions keep what they read in) and :bus.

  How every task reports (dhf/spec/README.md): standard output holds only
  the result, as EDN ({} when there is none); the error stream one line per
  finding, FILE:LINE: RULE: what, then one per warning, then with --events
  one line per event, `event: NAME published-by PUBLISHER`. Exit 0 is a
  pass, 1 a finding, 2 a task that cannot run (`cannot-run: why` on the
  error stream, nothing on standard output).

  The library every context uses: finding, warning, warning?,
  format-finding, cannot-run!, cannot-run?, config-file, read-config,
  config-value, path, rel, publish!, execute."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings

(defn finding
  "A finding: {:file :line :rule :message :severity :error}; file and line
  may be left out."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message] {:file file :line line :rule rule :message message :severity :error}))

(defn warning
  "A warning, a finding that does not fail the task."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message] (assoc (finding file line rule message) :severity :warning)))

(defn warning?
  "Whether the finding `f` is a warning."
  [f]
  (= :warning (:severity f)))

(defn format-finding
  "The line of the error stream naming `f`: FILE:LINE: RULE: what, the
  file and line left out where none applies, `warning: ` before the rule of
  a warning."
  [{:keys [file line rule message] :as f}]
  (str (when (and file (not (str/blank? (str file))))
         (str file (when line (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       rule ": " message))

;; ---------------------------------------------------------------------------
;; A task that cannot run

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying `why`."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "Whether `e` is a task that cannot run."
  [e]
  (boolean (and (instance? clojure.lang.ExceptionInfo e) (::cannot-run (ex-data e)))))

;; ---------------------------------------------------------------------------
;; The configuration and paths

(def config-file "dhf/harness/config.edn")

(def defaults
  "Each configuration value a task reads when the configuration names none."
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
   :stack-words []
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :agency {:watch ["/" "~"] :tmp "/tmp"}
   :c4 {}})

(defn config-value
  "The configured value `k` of the request (or {:config ..}), else its default."
  [req k]
  (let [c (:config req)]
    (if (and (map? c) (contains? c k) (some? (get c k)))
      (get c k)
      (get defaults k))))

(defn- root-of [root-or-req]
  (str (if (map? root-or-req) (:root root-or-req) root-or-req)))

(defn path
  "The path `rel` of the project (a root directory, or a request): itself
  when absolute."
  [root-or-req rel]
  (let [r (str rel)]
    (if (fs/absolute? r) r (str (fs/file (root-of root-or-req) r)))))

(defn rel
  "The file `f` relative to the project, written with /."
  [root-or-req f]
  (str/replace (str (fs/relativize (fs/absolutize (root-of root-or-req)) (fs/absolutize (str f)))) "\\" "/"))

(defn read-config
  "The configuration of the project at `root`, or nil when it has none or
  it is not an EDN map."
  [root]
  (let [f (fs/file root config-file)]
    (when (fs/regular-file? f)
      (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
        (when (map? c) c)))))

(defn- load-config [root]
  (let [f (fs/file root config-file)]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "there is no " config-file " in " root
                        ": run the task in a project set up by rdm-spec (rdm-spec init or adopt)")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (cannot-run! (str config-file " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (cannot-run! (str config-file " is not an EDN map")))
      c)))

(defn- catalogue-of
  "The project's event catalogue, read: {:catalogue map :source rel}; one
  that is missing or unreadable declares nothing (the model's lint names it)."
  [root config]
  (let [r (config-value {:config config} :events)
        f (fs/file root r)
        c (when (fs/regular-file? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))]
    {:catalogue (if (map? c) c {}) :source r}))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the request's bus."
  [req publisher event payload]
  (bus/publish! (:bus req) publisher event payload))

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn event-line
  "An event as the error stream lists it with --events."
  [{:keys [event publisher]}]
  (str "event: " (kw-name event) " published-by " (kw-name publisher)))

;; ---------------------------------------------------------------------------
;; The task table

(defn- stage [nm f & {:as more}] (merge {:name nm :fn f} more))

(def ^:private parse-stage (stage "parse" 'rdm-spec.spec.parser/parse))
(def ^:private gap-quietly (stage "gap" 'rdm-spec.compliance.gap/gap :subs 'rdm-spec.compliance.gap/subscriptions
                                  :regulated? true :quiet? true))

(def tasks
  "Every task: its usage, what it does, its arguments ([min max]), the
  options it takes, whether it needs the configuration, how it reads a
  results directory (:required or :optional), the revision it reads the
  spec at, and its stages in order."
  (array-map
   "help" {:usage "help" :does "list every task with its usage" :args [0 0] :config? false
           :stages [(stage "help" ::help)]}
   "init" {:usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
           :args [1 1] :opts #{:id-prefix} :config? false
           :stages [(stage "init" 'rdm-spec.project.init/init)]}
   "adopt" {:usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
            :args [0 0] :config? false
            :stages [(stage "adopt" 'rdm-spec.project.adopt/adopt)]}
   "stamp" {:usage "stamp" :does "give every unstamped scenario its id" :args [0 0]
            :stages [(stage "stamp" 'rdm-spec.spec.stamper/stamp)]}
   "check" {:usage "check [--sources DIR] [--events]"
            :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths"
            :args [0 0] :opts #{:sources}
            :stages [(stage "sources" 'rdm-spec.knowledge.sources/check)
                     (stage "spec" 'rdm-spec.spec.check/check)
                     (stage "lint" 'rdm-spec.architecture.lint/lint :subs 'rdm-spec.architecture.lint/subscriptions)
                     (stage "views" 'rdm-spec.architecture.views/check)
                     (stage "gap" 'rdm-spec.compliance.gap/gap :subs 'rdm-spec.compliance.gap/subscriptions
                            :regulated? true)
                     (stage "paths" 'rdm-spec.regeneration.fence/paths)]}
   "views" {:usage "views" :does "generate and render every view to dhf/views/" :args [0 0]
            :stages [(stage "views" 'rdm-spec.architecture.views/views)]}
   "cut" {:usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag" :args [0 0]
          :stages [(stage "cut" 'rdm-spec.regeneration.cut/cut)]}
   "scopes" {:usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts" :args [2 2]
             :stages [(stage "scopes" 'rdm-spec.regeneration.scopes/scopes)]}
   "fence" {:usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
            :args [2 2] :opts #{:context :component}
            :stages [(stage "oracle" 'rdm-spec.measurement.oracle/oracle :gate? true)
                     (stage "fence" 'rdm-spec.regeneration.fence/fence)]}
   "run" {:usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
          :args [0 0] :opts #{:results} :results :written
          :stages [(stage "run" 'rdm-spec.kernel.runner/run)]}
   "ratify" {:usage "ratify ROOM CYCLE" :does "run the builder's tests on the room's code in a checkout of the cut"
             :args [2 2]
             :stages [(stage "oracle" 'rdm-spec.measurement.oracle/oracle :gate? true)
                      (stage "ratify" 'rdm-spec.ratification.ratify/ratify)]}
   "checklist" {:usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
                :args [1 1] :revision 'rdm-spec.measurement.checklist/revision
                :stages [parse-stage gap-quietly
                         (stage "checklist" 'rdm-spec.measurement.checklist/checklist
                                :subs 'rdm-spec.measurement.checklist/subscriptions)]}
   "status" {:usage "status [--results DIR]" :does "each scenario's status" :args [0 0] :opts #{:results}
             :results :required
             :stages [parse-stage
                      (stage "status" 'rdm-spec.verification.status/status
                             :subs 'rdm-spec.verification.status/subscriptions)]}
   "trace" {:usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
            :args [1 1] :opts #{:results} :results :optional
            :stages [parse-stage
                     (stage "trace" 'rdm-spec.verification.trace/trace :subs 'rdm-spec.verification.trace/subscriptions)]}
   "release" {:usage "release [--results DIR]" :does "the release check" :args [0 0] :opts #{:results}
              :results :required
              :stages [parse-stage gap-quietly
                       (stage "release" 'rdm-spec.verification.release/release
                              :subs 'rdm-spec.verification.release/subscriptions)]}
   "score" {:usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a ratified cycle into its score.edn"
            :args [1 1] :opts #{:verdicts}
            :stages [(stage "score" 'rdm-spec.measurement.scorer/score)]}
   "scoreboard" {:usage "scoreboard CYCLE CYCLE" :does "compare two cycles" :args [2 2]
                 :stages [(stage "scoreboard" 'rdm-spec.measurement.scoreboard/scoreboard)]}
   "oracle" {:usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
             :args [0 1] :opts #{:reason}
             :stages [(stage "oracle" 'rdm-spec.measurement.oracle/oracle)]}
   "publish" {:usage "publish [--results DIR]" :does "write the site and build it" :args [0 0] :opts #{:results}
              :results :optional
              :stages [(stage "views" 'rdm-spec.architecture.views/check :gate? true)
                       (stage "sources" 'rdm-spec.knowledge.sources/check :quiet? true)
                       (stage "spec" 'rdm-spec.spec.check/check :quiet? true)
                       (stage "status" 'rdm-spec.verification.status/derive :quiet? true
                              :subs 'rdm-spec.verification.status/subscriptions)
                       (stage "site" 'rdm-spec.publishing.site/publish :subs 'rdm-spec.publishing.site/subscriptions)]}
   "audit" {:usage "audit OUT [--results DIR]"
            :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
            :args [1 1] :opts #{:results} :results :required :revision 'rdm-spec.compliance.audit/revision
            :stages [(stage "committed" 'rdm-spec.compliance.audit/committed :gate? true)
                     parse-stage gap-quietly
                     (stage "release" 'rdm-spec.verification.release/release :gate? true
                            :subs 'rdm-spec.verification.release/subscriptions)
                     (stage "audit" 'rdm-spec.compliance.audit/audit :subs 'rdm-spec.compliance.audit/subscriptions)]}
   "observe" {:usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
              :does "record a run: its time, its cost and what it touched outside its room"
              :args [1 4] :opts #{:tokens}
              :stages [(stage "observe" 'rdm-spec.agency.observe/observe)]}))

(defn help-entries
  "Every task with its usage and what it does, in the table's order."
  []
  (vec (for [[t {:keys [usage does]}] tasks] {:task t :usage usage :does does})))

(defn- task-list []
  (str/join ", " (keys tasks)))

;; ---------------------------------------------------------------------------
;; Arguments

(def ^:private value-flags
  {"--results" :results "--sources" :sources "--id-prefix" :id-prefix "--context" :context
   "--component" :component "--tokens" :tokens "--reason" :reason "--verdicts" :verdicts})

(defn parse-args
  "The positional arguments and options of `task`'s `words`: {:args :opts}.
  An option the task does not take, one with no value, or a wrong number of
  arguments cannot run."
  [task words]
  (let [{:keys [usage opts] [lo hi] :args} (get tasks task)
        allowed (conj (set opts) :events)
        out (loop [[w & more] words acc {:args [] :opts {}}]
              (cond
                (nil? w) acc
                (= "--events" w) (recur more (assoc-in acc [:opts :events] true))
                (str/starts-with? w "--")
                (let [k (get value-flags w)]
                  (when-not (and k (allowed k))
                    (cannot-run! (str task " takes no option " w ": rdm-spec " usage)))
                  (when (or (empty? more) (str/starts-with? (first more) "--"))
                    (cannot-run! (str w " takes a value: rdm-spec " usage)))
                  (recur (rest more) (assoc-in acc [:opts k] (first more))))
                :else (recur more (update acc :args conj w))))
        n (count (:args out))]
    (when-not (<= lo n hi)
      (cannot-run! (str task " takes " (if (= lo hi) lo (str lo " to " hi)) " argument" (when-not (= 1 hi) "s")
                        ", not " n (when (pos? n) (str " (" (str/join " " (:args out)) ")")) ": rdm-spec " usage)))
    out))

(defn results-dir
  "The results directory a task reads: --results, else RESULTS_DIR, else
  the configured one. An :optional one that is only configured, and does
  not exist, is none: the task runs without results."
  [req mode]
  (let [given (or (not-empty (get-in req [:opts :results])) (not-empty (get (:env req) "RESULTS_DIR")))
        configured (config-value req :results-dir)]
    (cond
      given given
      (= :optional mode) (when (fs/directory? (path req configured)) configured)
      :else configured)))

;; ---------------------------------------------------------------------------
;; Running stages

(defn- resolve-sym
  "The var `sym` names; the task cannot run when its code is not built."
  [what sym]
  (let [v (try (requiring-resolve sym)
               (catch java.io.FileNotFoundException e
                 (cannot-run! (str what " is not built: " (ex-message e)
                                   "; a stage that does not run is a check that reads as a pass")))
               (catch Exception e
                 (cannot-run! (str what " cannot be loaded (" sym "): " (ex-message e)))))]
    (or v (cannot-run! (str what " is not built: " sym " is not defined;"
                            " a stage that does not run is a check that reads as a pass")))))

(defn resolve-stage
  "The function of the stage `s` (help is the command's own)."
  [s]
  (if (= ::help (:fn s))
    (fn [_] {:result {:help (help-entries)} :findings []})
    (resolve-sym (str "the stage " (:name s)) (:fn s))))

(defn run-stages
  "Runs `stages` (those applying to the request) on the request: {:result
  :findings}. Every stage's code is resolved, and every subscription made,
  before the first one runs."
  [req stages]
  (let [regulated? (contains? (:config req) :regulated)
        stages (filterv #(or (not (:regulated? %)) regulated?) stages)
        fns (mapv resolve-stage stages)
        subs (vec (for [s stages :when (:subs s)] (resolve-sym (str "the stage " (:name s)) (:subs s))))]
    (doseq [sf subs
            [event handler] (sf req)]
      (bus/subscribe! (:bus req) event (keyword (str (:task req)) "stage") handler))
    (loop [[[s f] & more] (map vector stages fns) result {} findings []]
      (if (nil? s)
        {:result result :findings findings}
        (let [{r :result fs* :findings} (f req)
              result (if (:quiet? s) result (merge result r))
              findings (if (:quiet? s) findings (into findings fs*))]
          (if (and (:gate? s) (some (complement warning?) fs*))
            {:result result :findings findings}
            (recur more result findings)))))))

;; ---------------------------------------------------------------------------
;; Executing a task

(defn execute
  "Runs the task `words` in the project `root` with the environment `env`,
  in process: {:exit :result :findings :events :cannot-run :events?}."
  [words {:keys [root env]}]
  (let [root (str (fs/absolutize (or root ".")))
        env (or env (into {} (System/getenv)))
        events-of (atom [])]
    (try
      (let [[task & more] (map str words)
            _ (when (nil? task) (cannot-run! (str "no task was given: the tasks are " (task-list) " (rdm-spec help)")))
            t (or (get tasks task)
                  (cannot-run! (str "the task " task " is unknown: the tasks are " (task-list) " (rdm-spec help)")))
            {:keys [args opts]} (parse-args task more)
            config (when-not (false? (:config? t)) (load-config root))
            {:keys [catalogue source]} (when config (catalogue-of root config))
            b (bus/make-bus {:catalogue catalogue :source source})
            _ (reset! events-of b)
            req {:task task :root root :config config :args args :opts opts :env env
                 :state (atom {}) :bus b}
            req (if (#{:required :optional :written} (:results t))
                  (assoc req :results (results-dir req (if (= :optional (:results t)) :optional :required)))
                  req)
            req (if-let [rv (:revision t)]
                  (let [f (resolve-sym "the revision" rv)]
                    ;; every stage is resolved before the revision is read
                    (run! resolve-stage (:stages t))
                    (assoc req :revision (f req)))
                  req)
            {:keys [result findings]} (run-stages req (:stages t))
            errors (vec (remove warning? findings))
            warnings (vec (filter warning? findings))]
        {:exit (if (seq errors) 1 0)
         :result result
         :findings (into errors warnings)
         :events (bus/events b)
         :events? (boolean (:events opts))})
      (catch clojure.lang.ExceptionInfo e
        (if (cannot-run? e)
          {:exit 2 :cannot-run (ex-message e) :findings [] :events (let [b @events-of] (if (map? b) (bus/events b) []))}
          (throw e))))))

(defn- show [x]
  (binding [*print-namespace-maps* false] (pr-str x)))

(defn -main
  "rdm-spec <task> [arguments]: runs the task in the working directory,
  reports it and exits with its code."
  [& args]
  (let [x (try (execute args {:root (str (fs/cwd)) :env (into {} (System/getenv))})
               (catch Throwable e
                 {:exit 2 :cannot-run (str "the task failed unexpectedly: " (.getName (class e)) ": " (ex-message e))}))]
    (if (:cannot-run x)
      (binding [*out* *err*] (println (str "cannot-run: " (:cannot-run x))))
      (do
        (println (show (or (:result x) {})))
        (binding [*out* *err*]
          (doseq [f (:findings x)] (println (format-finding f)))
          (when (:events? x)
            (doseq [e (:events x)] (println (event-line e)))))))
    (flush)
    (binding [*out* *err*] (flush))
    (System/exit (:exit x))))
