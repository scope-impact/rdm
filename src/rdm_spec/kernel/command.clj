(ns rdm-spec.kernel.command
  "The kernel component Command (dhf/spec/kernel/command.feature): the one
  entry point, `rdm-spec <task>`. It belongs to no bounded context: it
  reads the project's configuration, builds the request every stage is
  given, runs the task's stages in the order the task table lists them, and
  reports (dhf/spec/README.md, \"How every task reports\").

  A stage is a function of the request returning {:result map :findings
  [finding]}; the task's result is the merge of its stages' results. A
  finding is {:file :line :rule :message :severity :error | :warning}.
  Every stage reports, so one run names every finding; a stage the table
  marks :gate runs where the table puts it and stops the task when it has a
  finding that is not a warning (its result is never the task's); a stage
  marked :quiet reports nothing (it runs for the events it publishes). A
  stage whose code is not built refuses the task (exit 2): a stage skipped
  is a check that reads as a pass (glossary, \"Gate\").

  The request: {:task :args :opts :root :config :env :results :revision
  :state :bus}. :results is the results directory: --results, else
  RESULTS_DIR, else the configured :results-dir when it exists, else nil.
  :revision is what the task's :revision function returns (a commit), else
  nil (the working tree). :state is an atom the stages' subscriptions keep
  events in; :bus carries rdm-spec's own events (rdm-spec.kernel.bus): each
  stage namespace's `subscriptions` (fn [req] [[event handler]]) is
  subscribed before the first stage runs.

  Exit 0 is a pass, 1 a finding, 2 a task that cannot run (`cannot-run:
  why` on the error stream, nothing on standard output), a refusal of the
  bus included.

  API for the components: cannot-run!, cannot-run?, finding, warning,
  warning?, format-finding, config-file, config-value, path, rel, publish!,
  execute. For test.clj: format-finding."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Refusals and findings

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "Whether `e` is a task's refusal to run."
  [e]
  (boolean (and (instance? clojure.lang.ExceptionInfo e) (::cannot-run (ex-data e)))))

(defn- one-line [s] (str/replace (str s) #"\s*\r?\n\s*" " "))

(defn finding
  "A finding: (finding rule message), (finding file rule message) or
  (finding file line rule message)."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file file :line line :rule rule :message message :severity :error}))

(defn warning
  "A warning: (warning rule message), (warning file rule message) or
  (warning file line rule message)."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message]
   {:file file :line line :rule rule :message message :severity :warning}))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The line of a finding or a warning: FILE:LINE: [warning: ]RULE: what,
  FILE and LINE left out where none applies."
  [{:keys [file line rule message] :as f}]
  (str (when (some? file) (str file (when (some? line) (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       rule ": " (one-line message)))

;; ---------------------------------------------------------------------------
;; The configuration

(def config-file "dhf/harness/config.edn")

(def defaults
  "What a configuration that names none of these means."
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
   :steps-dir "test/steps"
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :stack-words []
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :agency {:watch ["/" "~"] :tmp "/tmp"}
   :c4 {:view-element-limit 20 :description-max-chars 45}})

(defn config-value
  "The configuration's `k` (of `x`, a request or {:config c}), else its default."
  [x k]
  (let [v (get (:config x) k)]
    (if (some? v) v (get defaults k))))

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `p` names in the project of `x` (a request or a root): `p` when
  absolute, else under the root."
  [x p]
  (let [p (str p)]
    (if (fs/absolute? p) p (str (fs/file (root-of x) p)))))

(defn rel
  "The path of `f` relative to the project of `x`, with forward slashes."
  [x f]
  (str/replace (str (fs/relativize (fs/absolutize (root-of x)) (fs/absolutize f))) "\\" "/"))

(defn- read-config [root]
  (let [f (fs/file root config-file)]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "there is no " config-file " in " root ": run rdm-spec in a project rdm-spec set up")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (cannot-run! (str config-file " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (cannot-run! (str config-file " is not an EDN map")))
      c)))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` on the run's bus (nothing without one)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

;; ---------------------------------------------------------------------------
;; The task table

(defn- s [sym & {:as more}] (merge {:fn sym} more))

(defn- regulated? [req] (contains? (:config req) :regulated))

(def ^:private oracle-gate (s 'rdm-spec.measurement.oracle/gate :gate true))

(def tasks
  "Every task, in the order help lists them: :task :usage :does :args
  [min max] positionals, :config? (whether it needs a project's
  configuration), :revision (a function of the request giving the commit it
  reads), :stages."
  [{:task "help" :usage "help" :does "list every task with its usage" :args [0 0] :config? false
    :stages []}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args [1 1] :config? false :stages [(s 'rdm-spec.project.init/init)]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [0 0] :config? false :stages [(s 'rdm-spec.project.adopt/adopt)]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0] :stages [(s 'rdm-spec.spec.stamper/stamp)]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the domain model, the views' freshness, sources, wiki, clauses, paths"
    :args [0 0]
    :stages [(s 'rdm-spec.knowledge.sources/check)
             (s 'rdm-spec.spec.check/check)
             (s 'rdm-spec.architecture.lint/lint)
             (s 'rdm-spec.architecture.domain/check)
             (s 'rdm-spec.architecture.views/check)
             (s 'rdm-spec.compliance.gap/gap :when regulated?)
             (s 'rdm-spec.regeneration.fence/paths)]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0] :stages [(s 'rdm-spec.architecture.views/views)]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :stages [(s 'rdm-spec.regeneration.cut/cut)]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :stages [(s 'rdm-spec.regeneration.scopes/scopes)]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :args [2 2] :stages [oracle-gate (s 'rdm-spec.regeneration.fence/fence)]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :stages [(s 'rdm-spec.kernel.runner/run)]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :args [2 2] :stages [oracle-gate (s 'rdm-spec.verification.verify/verify)]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1] :revision 'rdm-spec.measurement.checklist/revision
    :stages [(s 'rdm-spec.spec.parser/parse)
             (s 'rdm-spec.compliance.gap/gap :when regulated? :quiet true)
             (s 'rdm-spec.measurement.checklist/checklist)]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :stages [(s 'rdm-spec.spec.parser/parse) (s 'rdm-spec.verification.status/status)]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :stages [(s 'rdm-spec.spec.parser/parse) (s 'rdm-spec.verification.trace/trace)]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0]
    :stages [(s 'rdm-spec.spec.parser/parse)
             (s 'rdm-spec.compliance.gap/gap :when regulated? :quiet true)
             (s 'rdm-spec.verification.release/release)]}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a verified cycle into its score.edn"
    :args [1 1] :stages [(s 'rdm-spec.measurement.scorer/score)]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2] :stages [(s 'rdm-spec.measurement.scoreboard/scoreboard)]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args [0 1] :flags ["--reason" "--changes"] :stages [(s 'rdm-spec.measurement.oracle/oracle)]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0]
    :stages [(s 'rdm-spec.architecture.views/check :gate true)
             (s 'rdm-spec.knowledge.sources/check :quiet true)
             (s 'rdm-spec.spec.check/check :quiet true)
             (s 'rdm-spec.verification.status/derive)
             (s 'rdm-spec.publishing.site/publish)]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :revision 'rdm-spec.compliance.audit/revision
    :stages [(s 'rdm-spec.compliance.audit/committed :gate true)
             (s 'rdm-spec.spec.parser/parse)
             (s 'rdm-spec.compliance.gap/gap :quiet true)
             (s 'rdm-spec.verification.release/release :gate true)
             (s 'rdm-spec.compliance.audit/audit)]}
   {:task "observe"
    :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :args [3 4] :stages [(s 'rdm-spec.agency.observe/observe)]}])

(defn task-of [nm] (first (filter #(= nm (:task %)) tasks)))

(defn help-entries
  "Every task with its usage and what it does: [{:task :usage :does}]."
  []
  (mapv #(select-keys % [:task :usage :does]) tasks))

(defn- task-names [] (str/join ", " (map :task tasks)))

;; ---------------------------------------------------------------------------
;; Arguments

(defn- flags-of
  "The options `t` takes: those its usage names, and --events."
  [t]
  (set (concat ["--events"] (:flags t) (re-seq #"--[a-z-]+" (:usage t)))))

(defn parse-args
  "The positionals and options of the task `t`'s arguments `argv`:
  {:args [..] :opts {kw value}}, --events true. Anything the task does not
  take cannot run."
  [t argv]
  (let [allowed (flags-of t)
        {:keys [args opts]}
        (loop [xs (seq argv) args [] opts {}]
          (if-not xs
            {:args args :opts opts}
            (let [[x & more] xs]
              (cond
                (= "--events" x) (recur more args (assoc opts :events true))
                (str/starts-with? x "--")
                (do (when-not (allowed x)
                      (cannot-run! (str "rdm-spec " (:task t) " takes no option " x ": " (:usage t))))
                    (when (or (nil? more) (str/starts-with? (first more) "--"))
                      (cannot-run! (str "the option " x " needs a value: " (:usage t))))
                    (recur (next more) args (assoc opts (keyword (subs x 2)) (first more))))
                :else (recur more (conj args x) opts)))))
        [lo hi] (:args t)]
    (when-not (<= lo (count args) hi)
      (cannot-run! (str "rdm-spec " (:task t) " takes " (if (= lo hi) lo (str lo " to " hi))
                        " argument" (when-not (= 1 hi) "s") ", not " (count args) ": " (:usage t))))
    {:args args :opts opts}))

(defn results-of
  "The results directory of the request: --results, else RESULTS_DIR, else
  the configured :results-dir when it exists, else nil."
  [req]
  (or (not-empty (get-in req [:opts :results]))
      (not-empty (get (:env req) "RESULTS_DIR"))
      (let [d (config-value req :results-dir)]
        (when (fs/directory? (path req d)) d))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- stage-fn [sym]
  (or (try (requiring-resolve sym) (catch Throwable _ nil))
      (cannot-run! (str "the stage " sym " is not built: a stage skipped is a check that reads as a pass"))))

(defn- subscribe-all! [req stages]
  (doseq [ns-sym (distinct (map (comp symbol namespace :fn) stages))
          :let [subs-fn (try (requiring-resolve (symbol (str ns-sym) "subscriptions")) (catch Throwable _ nil))]
          :when subs-fn
          [event handler] (subs-fn req)]
    (bus/subscribe! (:bus req) event handler)))

(defn- sort-findings [fs*]
  (vec (concat (remove warning? fs*) (filter warning? fs*))))

(defn run-task
  "Runs the task `t` on the request `req0` (its :root :config :opts :args
  :env): {:exit :result :findings :err-lines :cannot-run :events}."
  [t req0]
  (let [req (merge {:state (atom {}) :bus (bus/bus) :task (:task t)} req0)]
    (try
      (let [stages (filterv #(or (nil? (:when %)) ((:when %) req)) (:stages t))
            _ (doseq [st stages] (stage-fn (:fn st)))
            req (assoc req :results (results-of req))
            req (if-let [r (:revision t)] (assoc req :revision ((stage-fn r) req)) req)
            _ (subscribe-all! req stages)
            {:keys [result findings]}
            (reduce (fn [acc st]
                      (let [out ((stage-fn (:fn st)) req)
                            fs* (vec (:findings out))]
                        (cond
                          (:quiet st) acc
                          (:gate st) (let [acc (update acc :findings into fs*)]
                                       (if (some (complement warning?) fs*) (reduced acc) acc))
                          :else (-> acc
                                    (update :result merge (:result out))
                                    (update :findings into fs*)))))
                    {:result {} :findings []}
                    stages)
            fs* (sort-findings findings)]
        {:exit (if (some (complement warning?) fs*) 1 0)
         :result result
         :findings fs*
         :err-lines (mapv format-finding fs*)
         :events (mapv bus/event-line (bus/published (:bus req)))})
      (catch clojure.lang.ExceptionInfo e
        (if (or (cannot-run? e) (bus/refused? e))
          {:exit 2 :cannot-run (one-line (ex-message e))
           :err-lines [(str "cannot-run: " (one-line (ex-message e)))]
           :events (mapv bus/event-line (bus/published (:bus req)))}
          (throw e))))))

(defn execute
  "Runs `rdm-spec argv` in the project at `:root` with the environment
  `:env`: {:exit :result :findings :err-lines :cannot-run :events}, plus
  :help for help. Nothing is printed."
  [argv {:keys [root env]}]
  (let [root (str (or root (System/getProperty "user.dir")))
        env (or env (into {} (System/getenv)))
        [nm & argv] argv]
    (try
      (let [t (or (task-of nm)
                  (cannot-run! (str (if nm (str "there is no task " nm) "no task was given")
                                    ": the tasks are " (task-names) " (rdm-spec help lists their usage)")))
            {:keys [args opts]} (parse-args t argv)]
        (if (= "help" nm)
          {:exit 0 :result {:help (help-entries)} :findings [] :err-lines [] :events []}
          (let [config (if (false? (:config? t))
                         (let [f (fs/file root config-file)]
                           (if (fs/regular-file? f) (read-config root) {}))
                         (read-config root))]
            (assoc (run-task t {:root root :config config :opts opts :args args :env env})
                   :events? (:events opts)))))
      (catch clojure.lang.ExceptionInfo e
        (if (cannot-run? e)
          {:exit 2 :cannot-run (one-line (ex-message e)) :err-lines [(str "cannot-run: " (one-line (ex-message e)))]}
          (throw e))))))

(defn output
  "What a run prints on standard output: its result as EDN, nothing when it
  cannot run."
  [r]
  (if (= 2 (:exit r))
    ""
    (binding [*print-namespace-maps* false]
      (str (pr-str (or (:result r) {})) "\n"))))

(defn -main [& argv]
  (let [r (try (execute (vec argv) {})
               (catch Throwable e
                 {:exit 2 :err-lines [(str "cannot-run: rdm-spec failed: " (one-line (str (.getName (class e)) ": " (ex-message e))))]}))]
    (print (output r))
    (flush)
    (binding [*out* *err*]
      (doseq [l (:err-lines r)] (println l))
      (when (:events? r) (doseq [l (:events r)] (println l)))
      (flush))
    (System/exit (:exit r))))
