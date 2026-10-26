(ns rdm-spec.kernel.command
  "The command (dhf/spec/kernel/command.feature): `rdm-spec <task>`, the one
  entry point, and the composition root. It belongs to no bounded context:
  it dispatches each task to the stages of the contexts that own it, in the
  order of the task table.

  A run: the words are read (a task, its arguments, its options), the
  project's configuration is read (dhf/harness/config.edn under the working
  directory; init, adopt and help need none), the bus is built with
  rdm-spec's own catalogue (rdm-spec.kernel.bus/catalogue, never the
  configured :events: a project's catalogue is its design record), every
  stage's subscriptions are made, the task's revision is resolved when it
  names one, then the stages run in order. Each stage is (fn [req]) ->
  {:result map :findings [finding]}; the results are merged into the task's
  result. A gate stops the task when it has findings that are not warnings;
  a quiet stage reports nothing (its findings are read from its event by a
  later stage); a stage with :when runs only when that holds. A stage whose
  code is not built refuses the task (exit 2).

  The request a stage gets:
    {:task :args :opts :root :config :env :results :revision :state :bus}
  :results is the results directory (--results, else RESULTS_DIR, else the
  configured :results-dir), for the tasks that read or write results; for
  trace and publish, a results directory nobody named that does not exist
  is no results (nil).

  How every task reports (dhf/spec/README.md): standard output holds only
  the result, as EDN ({} when there is none); the error stream one line per
  finding, `FILE:LINE: RULE: what`, then one per warning; with --events,
  one line per event published, `event: NAME published-by PUBLISHER`, after
  them. Exit 0 a pass, 1 a finding, 2 a task that cannot run (`cannot-run:
  why`, nothing on standard output).

  API for the stages: config-file, read-config, config-value, path, rel,
  finding, warning, warning?, format-finding, cannot-run!, cannot-run?,
  publish!; for the tasks that run another: execute."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; The configuration

(def config-file
  "Where a project's configuration lives, relative to its root."
  "dhf/harness/config.edn")

(def defaults
  "What a configuration holds when it does not say: the skeleton's values."
  {:id-prefix "spec"
   :spec-dir "dhf/spec"
   :ledger "dhf/spec/ids.edn"
   :model "dhf/spec/model.edn"
   :events "dhf/spec/events.edn"
   :needs "dhf/spec/needs.edn"
   :views-dir "dhf/views"
   :raw-dir "dhf/raw"
   :wiki-dir "dhf/wiki"
   :stack-words []
   :steps-dir "test/steps"
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :c4 {}
   :agency {:watch ["/" "~"] :tmp "/tmp"}})

(defn config-value
  "The configuration value `k` of a request (or {:config c}), else its default."
  [req k]
  (let [c (:config req)]
    (if (and (map? c) (contains? c k)) (get c k) (get defaults k))))

;; ---------------------------------------------------------------------------
;; Findings

(defn finding
  "A finding: (finding file line rule message), (finding file rule message)
  or (finding rule message)."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning, with finding's arities."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message] (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The line of a finding or warning on the error stream."
  [{:keys [file line rule message] :as f}]
  (str (when file (str file (when line (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       rule ": " message))

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run? [e] (boolean (and (instance? clojure.lang.ExceptionInfo e) (::cannot-run (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Paths

(defn- root-of [x] (if (map? x) (:root x) x))

(defn path
  "The file `rel` names in the project of `x` (a request or a root): `rel`
  itself when absolute."
  [x rel]
  (let [r (str rel)]
    (if (fs/absolute? r) (fs/file r) (fs/file (str (or (root-of x) ".")) r))))

(defn rel
  "The path of the file `f` relative to the project of `x`, with /."
  [x f]
  (let [root (fs/absolutize (str (or (root-of x) ".")))
        abs (fs/absolutize (str f))]
    (str/replace (str (fs/relativize root abs)) "\\" "/")))

(defn read-config
  "The configuration of the project at `root`: nil when it has none; the
  task cannot run when it is not an EDN map."
  [root]
  (let [f (path root config-file)]
    (when (fs/exists? f)
      (let [c (try (edn/read-string (slurp (str f)))
                   (catch Exception e (cannot-run! (str config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? c) (cannot-run! (str config-file " is not an EDN map")))
        c))))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` from `publisher` on the run's bus (none outside a run)."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (try (bus/publish! b publisher event payload)
         (catch clojure.lang.ExceptionInfo e
           (if (bus/refused? e) (cannot-run! (str "the bus refuses: " (ex-message e))) (throw e))))))

;; ---------------------------------------------------------------------------
;; The task table

(defn- regulated? [req] (contains? (:config req) :regulated))
(defn- results? [req] (some? (:results req)))

(def results-opt {:results :value})

(def tasks
  "Every task, in the order help lists them: its usage, what it does, the
  options it takes, how many arguments, and its stages."
  (array-map
   "help" {:usage "help" :does "list every task with its usage" :args [0 0] :config? false}
   "init" {:usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
           :opts {:id-prefix :value} :args [1 1] :config? false
           :stages [{:fn 'rdm-spec.project.init/init}]}
   "adopt" {:usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
            :args [0 0] :config? false
            :stages [{:fn 'rdm-spec.project.adopt/adopt}]}
   "stamp" {:usage "stamp" :does "give every unstamped scenario its id" :args [0 0]
            :stages [{:fn 'rdm-spec.spec.stamper/stamp}]}
   "check" {:usage "check [--sources DIR] [--events]"
            :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths"
            :opts {:sources :value} :args [0 0]
            :stages [{:fn 'rdm-spec.knowledge.sources/check}
                     {:fn 'rdm-spec.spec.check/check}
                     {:fn 'rdm-spec.architecture.lint/lint}
                     {:fn 'rdm-spec.architecture.views/check}
                     {:fn 'rdm-spec.compliance.gap/gap :when regulated?}
                     {:fn 'rdm-spec.regeneration.fence/paths}]}
   "views" {:usage "views" :does "generate and render every view to dhf/views/" :args [0 0]
            :stages [{:fn 'rdm-spec.architecture.views/views}]}
   "cut" {:usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag" :args [0 0]
          :stages [{:fn 'rdm-spec.regeneration.cut/cut}]}
   "scopes" {:usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts" :args [2 2]
             :stages [{:fn 'rdm-spec.regeneration.scopes/scopes}]}
   "fence" {:usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
            :opts {:context :value :component :value} :args [2 2]
            :stages [{:fn 'rdm-spec.measurement.oracle/oracle :gate? true}
                     {:fn 'rdm-spec.regeneration.fence/fence}]}
   "run" {:usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
          :opts results-opt :args [0 0] :results :required
          :stages [{:fn 'rdm-spec.kernel.runner/run}]}
   "ratify" {:usage "ratify ROOM CYCLE" :does "run the builder's tests on the room's code in a checkout of the cut"
             :args [2 2]
             :stages [{:fn 'rdm-spec.measurement.oracle/oracle :gate? true}
                      {:fn 'rdm-spec.ratification.ratify/ratify}]}
   "checklist" {:usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
                :args [1 1] :revision 'rdm-spec.measurement.checklist/revision
                :stages [{:fn 'rdm-spec.spec.parser/parse}
                         {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?}
                         {:fn 'rdm-spec.measurement.checklist/checklist}]}
   "status" {:usage "status [--results DIR]" :does "each scenario's status"
             :opts results-opt :args [0 0] :results :required
             :stages [{:fn 'rdm-spec.spec.parser/parse}
                      {:fn 'rdm-spec.verification.status/status}]}
   "trace" {:usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
            :opts results-opt :args [1 1] :results :optional
            :stages [{:fn 'rdm-spec.spec.parser/parse}
                     {:fn 'rdm-spec.verification.trace/trace}]}
   "release" {:usage "release [--results DIR]" :does "the release check"
              :opts results-opt :args [0 0] :results :required
              :stages [{:fn 'rdm-spec.spec.parser/parse}
                       {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?}
                       {:fn 'rdm-spec.verification.release/release}]}
   "score" {:usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a ratified cycle into its score.edn"
            :opts {:verdicts :value} :args [1 1]
            :stages [{:fn 'rdm-spec.measurement.scorer/score}]}
   "scoreboard" {:usage "scoreboard CYCLE CYCLE" :does "compare two cycles" :args [2 2]
                 :stages [{:fn 'rdm-spec.measurement.scoreboard/scoreboard}]}
   "oracle" {:usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
             :opts {:reason :value}
             :stages [{:fn 'rdm-spec.measurement.oracle/oracle}]}
   "publish" {:usage "publish [--results DIR]" :does "write the site and build it"
              :opts results-opt :args [0 0] :results :optional
              :stages [{:fn 'rdm-spec.knowledge.sources/check :quiet? true}
                       {:fn 'rdm-spec.spec.check/check :quiet? true}
                       {:fn 'rdm-spec.architecture.views/check :gate? true}
                       {:fn 'rdm-spec.verification.status/status :quiet? true :when results?}
                       {:fn 'rdm-spec.publishing.site/publish}]}
   "audit" {:usage "audit OUT [--results DIR]"
            :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
            :opts results-opt :args [1 1] :results :required :revision 'rdm-spec.compliance.audit/revision
            :stages [{:fn 'rdm-spec.compliance.audit/committed :gate? true}
                     {:fn 'rdm-spec.spec.parser/parse}
                     {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?}
                     {:fn 'rdm-spec.verification.release/release :gate? true}
                     {:fn 'rdm-spec.compliance.audit/audit}]}
   "observe" {:usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
              :does "record a run: its time, its cost and what it touched outside its room"
              :opts {:tokens :value}
              :stages [{:fn 'rdm-spec.agency.observe/observe}]}))

(defn- task-list [table] (str/join ", " (keys table)))

(defn help
  "The help task's result."
  [table]
  {:help {:tasks (vec (for [[t {:keys [usage does]}] table] {:task t :usage usage :does does}))}})

;; ---------------------------------------------------------------------------
;; Reading the words

(defn parse-words
  "{:task :args :opts}: options are --NAME VALUE, or --events alone."
  [words]
  (let [[task & more] (map str words)]
    (loop [ws more args [] opts {}]
      (if-let [[w & ws] (seq ws)]
        (if (str/starts-with? w "--")
          (let [k (keyword (subs w 2))]
            (if (= :events k)
              (recur ws args (assoc opts :events true))
              (if (seq ws)
                (recur (rest ws) args (assoc opts k (first ws)))
                (recur ws args (assoc opts k ::no-value)))))
          (recur ws (conj args w) opts))
        {:task task :args args :opts opts}))))

(defn- check-words! [table {:keys [task args opts]}]
  (let [spec (get table task)
        allowed (assoc (or (:opts spec) {}) :events :flag)
        [lo hi] (:args spec)]
    (doseq [[k v] opts]
      (when-not (contains? allowed k)
        (cannot-run! (str "rdm-spec " task " takes no option --" (name k) ": rdm-spec " (:usage spec))))
      (when (= ::no-value v)
        (cannot-run! (str "--" (name k) " needs a value: rdm-spec " (:usage spec)))))
    (when (and lo (< (count args) lo))
      (cannot-run! (str "rdm-spec " task " needs " lo " argument" (when (not= 1 lo) "s") ": rdm-spec " (:usage spec))))
    (when (and hi (> (count args) hi))
      (cannot-run! (str "rdm-spec " task " takes " (if (zero? hi) "no argument" (str hi " argument" (when (not= 1 hi) "s")))
                        ", not " (str/join " " args) ": rdm-spec " (:usage spec))))))

(defn results-of
  "The results directory of a request: --results, else RESULTS_DIR, else
  the configured :results-dir."
  [req]
  (or (not-empty (str (or (get-in req [:opts :results]) "")))
      (not-empty (str (or (get (:env req) "RESULTS_DIR") "")))
      (config-value req :results-dir)))

(defn- results-for [req mode]
  (case mode
    :required (results-of req)
    :optional (let [named (or (not-empty (str (or (get-in req [:opts :results]) "")))
                              (not-empty (str (or (get (:env req) "RESULTS_DIR") ""))))
                    r (results-of req)]
                (when (or named (fs/exists? (path req r))) r))
    nil))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage [sym]
  (or (try (requiring-resolve sym)
           (catch Exception e
             (if (re-find #"(?i)could not locate|not found|no such|FileNotFound" (str (ex-message e) (some-> e ex-cause ex-message)))
               nil
               (throw e))))
      (cannot-run! (str "the stage " sym " is not built: " (namespace sym)
                        " is not in this build, and a stage skipped would read as a pass"))))

(defn- subscriptions-of [sym]
  (some-> (find-ns (symbol (namespace sym))) (ns-resolve 'subscriptions)))

(defn- run-stages
  "Runs the task's stages: {:result :findings}."
  [req stages]
  (loop [[st & more] stages result {} findings []]
    (if-not st
      {:result result :findings findings}
      (if (and (:when st) (not ((:when st) req)))
        (recur more result findings)
        (let [f (resolve-stage (:fn st))
              out (f req)
              fs* (if (:quiet? st) [] (vec (:findings out)))
              result (merge result (when-not (:quiet? st) (:result out)))
              findings (into findings fs*)]
          (if (and (:gate? st) (some (complement warning?) fs*))
            {:result result :findings findings}
            (recur more result findings)))))))

(defn- report
  "The run's streams: {:exit :out :err}."
  [{:keys [result findings cannot-run events events?]}]
  (let [event-lines (when events? (map bus/event-line events))]
    (if cannot-run
      {:exit 2 :out ""
       :err (str/join (map #(str % "\n") (concat [(str "cannot-run: " cannot-run)] event-lines)))}
      {:exit (if (some (complement warning?) findings) 1 0)
       :out (binding [*print-namespace-maps* false] (str (pr-str (or result {})) "\n"))
       :err (str/join (map #(str % "\n")
                           (concat (map format-finding (remove warning? findings))
                                   (map format-finding (filter warning? findings))
                                   event-lines)))})))

(defn execute
  "Runs `rdm-spec words` in process, in the project `:root` with the
  environment `:env` (and, for tests, the task table `:tasks`): {:exit
  :result :findings :cannot-run :events :out :err}."
  [words {:keys [root env] :as opts}]
  (let [tasks (or (:tasks opts) tasks)
        root (str (fs/absolutize (str (or root "."))))
        env (or env (into {} (System/getenv)))
        b (bus/bus)
        parsed (parse-words words)
        out (try
              (let [{:keys [task]} parsed
                    spec (get tasks task)]
                (cond
                  (nil? task) (cannot-run! (str "no task was given: the tasks are " (task-list tasks)))
                  (nil? spec) (cannot-run! (str "unknown task " task ": the tasks are " (task-list tasks)))
                  :else
                  (do
                    (check-words! tasks parsed)
                    (if (= "help" task)
                      {:result (help tasks) :findings []}
                      (let [config (if (false? (:config? spec))
                                     (try (read-config root) (catch Exception _ nil))
                                     (or (read-config root)
                                         (cannot-run! (str "there is no " config-file " in " root
                                                           ": rdm-spec " task " runs in a project's root"))))
                            req (assoc parsed :root root :env env :config (or config {})
                                       :state (atom {}) :bus b)
                            req (assoc req :results (results-for req (:results spec)))
                            stages (vec (:stages spec))]
                        (doseq [st stages
                                :let [_ (resolve-stage (:fn st))
                                      subs-fn (subscriptions-of (:fn st))]
                                :when subs-fn
                                [event handler] (subs-fn req)]
                          (try (bus/subscribe! b event handler)
                               (catch clojure.lang.ExceptionInfo e
                                 (if (bus/refused? e) (cannot-run! (str "the bus refuses: " (ex-message e))) (throw e)))))
                        (let [req (if-let [r (:revision spec)] (assoc req :revision ((resolve-stage r) req)) req)]
                          (run-stages req stages)))))))
              (catch clojure.lang.ExceptionInfo e
                (if (cannot-run? e)
                  {:cannot-run (ex-message e)}
                  (throw e))))
        x (assoc out :events (bus/published b) :events? (boolean (get-in parsed [:opts :events])))]
    (merge x (report x))))

(defn -main
  "rdm-spec <task> [args]."
  [& args]
  (let [x (try (execute args {:root (System/getProperty "user.dir") :env (into {} (System/getenv))})
               (catch Throwable e
                 {:exit 2 :out ""
                  :err (str "cannot-run: rdm-spec failed: " (.getName (class e)) ": " (ex-message e) "\n")}))]
    (print (:out x))
    (flush)
    (binding [*out* *err*] (print (:err x)) (flush))
    (System/exit (:exit x))))
