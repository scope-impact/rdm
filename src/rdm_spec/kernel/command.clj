(ns rdm-spec.kernel.command
  "The command (dhf/spec/kernel/command.feature): `rdm-spec <task>`, the one
  entry point a hook, CI, an agent and a person all use. It belongs to no
  bounded context: it reads the command line against the task table,
  dispatches each task's stages to the contexts that own them, in order, on
  one event bus, and reports every task the same way
  (dhf/spec/README.md, \"How every task reports\"):

    standard output   the task's result, as EDN ({} when there is none), and
                      nothing when the task cannot run
    error stream      one line per finding, FILE:LINE: RULE: what, then one
                      per warning, FILE:LINE: warning: RULE: what (FILE and
                      LINE left out where none applies); `cannot-run: why`
                      when the task cannot run; with --events, a line
                      `event: NAME published-by PUBLISHER` per event, last
    exit              0 a pass, 1 a finding, 2 a task that cannot run

  A stage is a function of the request, returning {:result {..} :findings
  [..]}; the results of a task's stages are merged. A stage marked a gate
  stops the task when it has findings that are not warnings, and the stages
  after it never run; every other stage reports, so one run names every
  finding. A quiet stage runs for what it publishes and reports nothing. A
  stage whose code is not built refuses the task (exit 2), a gate's as any
  other's: a task never passes on a stage that did not run.

  The request a stage gets:

    {:task :args :opts {:results :sources :context :component :id-prefix
                        :reason :verdicts :tokens}
     :root    the project (the working directory)
     :config  its configuration, dhf/harness/config.edn
     :results the results directory: --results, else RESULTS_DIR, else the
              configured :results-dir (nil for a task that reads results
              only when there are some, when none was given and the
              configured one does not exist)
     :revision the commit a task reads the spec at (nil: the working tree)
     :env     the environment
     :bus     the run's event bus   :state  an atom stages keep things in}

  Helpers every context uses: finding, warning, warning?, format-finding,
  cannot-run!, cannot-run?, config-value, path, rel, read-config, publish!,
  execute."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

(def config-file "dhf/harness/config.edn")

(def defaults
  "The value of each configuration key a project leaves out."
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
   :agency {:watch ["/" "~"] :tmp "/tmp"}})

;; ---------------------------------------------------------------------------
;; Findings

(defn finding
  "A finding: what a task found wrong, at `file` and `line` when they apply."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning: said, never failing the task."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn- one-line [s] (str/replace (str s) #"\s*[\r\n]+\s*" " "))

(defn format-finding
  "The error stream's line of a finding or a warning."
  [{:keys [file line rule message severity]}]
  (let [where (cond (and file line) (str file ":" line ": ")
                    file (str file ": ")
                    :else "")]
    (str where (when (= :warning severity) "warning: ") rule ": " (one-line message))))

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "Whether `e` says a task cannot run."
  [e]
  (boolean (and (instance? clojure.lang.ExceptionInfo e) (::cannot-run (ex-data e)))))

;; ---------------------------------------------------------------------------
;; The project

(defn config-value
  "The configuration's `k` of a request (or of {:config c}), its default
  when the project leaves it out."
  [x k]
  (let [c (:config x)]
    (if (and (map? c) (contains? c k)) (get c k) (get defaults k))))

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `rel` names in the project of a request (or under a root
  directory); an absolute `rel` names itself."
  [x rel]
  (let [r (str rel)]
    (if (fs/absolute? r) (fs/file r) (fs/file (root-of x) r))))

(defn rel
  "The path of the file `f` relative to the project of a request (or to a
  root directory), / separated; absolute when it lies outside."
  [x f]
  (let [root (fs/normalize (fs/absolutize (root-of x)))
        p (fs/normalize (fs/absolutize (str f)))]
    (if (fs/starts-with? p root)
      (str/replace (str (fs/relativize root p)) "\\" "/")
      (str p))))

(defn read-config
  "The configuration of the project `dir`, or nil when it has none; a task
  cannot run on one that is not readable EDN, or not a map."
  [dir]
  (let [f (fs/file (str dir) config-file)]
    (when (fs/exists? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e (cannot-run! (str config-file " is not readable EDN: " (ex-message e)))))]
        (when-not (map? v) (cannot-run! (str config-file " is not a map")))
        v))))

(defn publish!
  "Publishes `event` on the run's bus, from `publisher`."
  [req publisher event payload]
  (bus/publish! (:bus req) publisher event payload))

;; ---------------------------------------------------------------------------
;; The task table

(def ^:private parse-spec 'rdm-spec.spec.parser/parse)
(def ^:private gap 'rdm-spec.compliance.gap/gap)
(def ^:private gap-subs 'rdm-spec.compliance.gap/subscriptions)
(def ^:private regulated? (fn [req] (contains? (:config req) :regulated)))

(def tasks
  "Every task: its usage and what it does (spec-0176), its arguments and
  options, and its stages in order."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :config? false :args [0 0] :help? true}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :config? false :args [1 1] :opts {"--id-prefix" :id-prefix}
    :stages [{:fn 'rdm-spec.project.init/init}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :config? false :args [0 0]
    :stages [{:fn 'rdm-spec.project.adopt/adopt}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0]
    :stages [{:fn 'rdm-spec.spec.stamper/stamp}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths"
    :args [0 0] :opts {"--sources" :sources}
    :stages [{:fn 'rdm-spec.knowledge.sources/check}
             {:fn 'rdm-spec.spec.check/check}
             {:fn 'rdm-spec.architecture.lint/lint :subscriptions 'rdm-spec.architecture.lint/subscriptions}
             {:fn 'rdm-spec.architecture.views/check}
             {:fn gap :subscriptions gap-subs :when regulated?}
             {:fn 'rdm-spec.regeneration.fence/paths}]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0]
    :stages [{:fn 'rdm-spec.architecture.views/views}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0]
    :stages [{:fn 'rdm-spec.regeneration.cut/cut}]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2]
    :stages [{:fn 'rdm-spec.regeneration.scopes/scopes}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :args [2 2] :opts {"--context" :context "--component" :component}
    :stages [{:fn 'rdm-spec.measurement.oracle/oracle :gate? true}
             {:fn 'rdm-spec.regeneration.fence/fence}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :results :required
    :stages [{:fn 'rdm-spec.kernel.runner/run}]}
   {:task "ratify" :usage "ratify ROOM CYCLE" :does "run the builder's tests on the room's code in a checkout of the cut"
    :args [2 2]
    :stages [{:fn 'rdm-spec.measurement.oracle/oracle :gate? true}
             {:fn 'rdm-spec.ratification.ratify/ratify}]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1] :revision 'rdm-spec.measurement.checklist/revision
    :stages [{:fn parse-spec :quiet? true}
             {:fn gap :subscriptions gap-subs :when regulated? :quiet? true}
             {:fn 'rdm-spec.measurement.checklist/checklist :subscriptions 'rdm-spec.measurement.checklist/subscriptions}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :results :required
    :stages [{:fn parse-spec :quiet? true}
             {:fn 'rdm-spec.verification.status/status :subscriptions 'rdm-spec.verification.status/subscriptions}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :results :optional
    :stages [{:fn parse-spec :quiet? true}
             {:fn 'rdm-spec.verification.trace/trace :subscriptions 'rdm-spec.verification.trace/subscriptions}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0] :results :required
    :stages [{:fn parse-spec :quiet? true}
             {:fn gap :subscriptions gap-subs :when regulated? :quiet? true}
             {:fn 'rdm-spec.verification.release/release :subscriptions 'rdm-spec.verification.release/subscriptions}]}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args [1 1] :opts {"--verdicts" :verdicts}
    :stages [{:fn 'rdm-spec.measurement.scorer/score}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2]
    :stages [{:fn 'rdm-spec.measurement.scoreboard/scoreboard}]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args [0 1] :opts {"--reason" :reason}
    :stages [{:fn 'rdm-spec.measurement.oracle/oracle}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0] :results :optional
    :stages [{:fn 'rdm-spec.architecture.views/check :gate? true}
             {:fn 'rdm-spec.knowledge.sources/check :quiet? true}
             {:fn 'rdm-spec.spec.check/check :quiet? true}
             {:fn 'rdm-spec.verification.status/status :quiet? true
              :subscriptions 'rdm-spec.verification.status/subscriptions :when (fn [req] (some? (:results req)))}
             {:fn 'rdm-spec.publishing.site/publish :subscriptions 'rdm-spec.publishing.site/subscriptions}]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :results :required :revision 'rdm-spec.compliance.audit/revision
    :stages [{:fn 'rdm-spec.compliance.audit/committed :gate? true}
             {:fn parse-spec :quiet? true}
             {:fn gap :subscriptions gap-subs :when regulated? :quiet? true}
             {:fn 'rdm-spec.verification.release/release :gate? true
              :subscriptions 'rdm-spec.verification.release/subscriptions}
             {:fn 'rdm-spec.compliance.audit/audit :subscriptions 'rdm-spec.compliance.audit/subscriptions}]}
   {:task "observe"
    :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :args [3 4] :opts {"--tokens" :tokens}
    :stages [{:fn 'rdm-spec.agency.observe/observe}]}])

(def ^:private by-task (into {} (map (juxt :task identity)) tasks))

(def ^:private repeatable #{:context :component})

(defn- task-names [] (str/join ", " (map :task tasks)))

(defn parse-args
  "The command line `words` read against the task table: {:task :entry :args
  :opts :events?}. An unknown task, argument or option cannot run."
  [words]
  (let [[task & more] words]
    (when (str/blank? task)
      (cannot-run! (str "no task was given: rdm-spec <task>, the tasks being " (task-names))))
    (let [entry (or (by-task task)
                    (cannot-run! (str "unknown task " task ": the tasks are " (task-names)
                                      " (rdm-spec help lists each with its usage)")))
          flags (merge (:opts entry) (when (:results entry) {"--results" :results}))
          usage (str "usage: rdm-spec " (:usage entry))]
      (loop [[w & ws] more args [] opts {} events? false]
        (cond
          (nil? w)
          (let [[lo hi] (:args entry)]
            (when-not (<= lo (count args) hi)
              (cannot-run! (str task " takes " (if (= lo hi) lo (str lo " to " hi)) " argument" (when-not (= 1 hi) "s")
                                ", not " (count args) (when (seq args) (str " (" (str/join " " args) ")")) "; " usage)))
            {:task task :entry entry :args args :opts opts :events? events?})

          (= "--events" w) (recur ws args opts true)

          (str/starts-with? w "--")
          (let [k (or (get flags w) (cannot-run! (str "unknown option " w " for " task "; " usage)))
                v (first ws)]
            (when (or (nil? v) (str/starts-with? v "--"))
              (cannot-run! (str "the option " w " takes a value; " usage)))
            (recur (rest ws) args
                   (if (and (repeatable k) (contains? opts k)) (update opts k str "," v) (assoc opts k v))
                   events?))

          :else (recur ws (conj args w) opts events?))))))

(defn results-dir
  "The results directory a task reads: --results, else RESULTS_DIR, else
  the configured one. When the task reads results only if there are some
  (`mode` :optional) and none was given, a configured directory that does
  not exist is no results directory (nil)."
  [opts env config root mode]
  (let [given (or (not-empty (:results opts)) (not-empty (get env "RESULTS_DIR")))
        configured (config-value {:config config} :results-dir)]
    (cond
      given given
      (and (= :optional mode) (not (fs/directory? (path root configured)))) nil
      :else configured)))

;; ---------------------------------------------------------------------------
;; Running stages

(defn- resolve-fn
  "The function a stage names: itself, or the var its symbol names, loaded;
  nil when its code is not built ({:why})."
  [f]
  (if (symbol? f)
    (try {:fn (or (requiring-resolve f) (throw (ex-info (str f " is not defined") {})))}
         (catch Throwable e {:why (str f " is not built (" (ex-message e) ")")}))
    {:fn f}))

(defn- stage-name [st] (str (if (symbol? (:fn st)) (:fn st) "a stage")))

(defn run-stages
  "Runs `stages` on the request `req`, in order: {:result :findings}. Every
  stage's subscriptions are made on the bus before the first stage runs."
  [req stages]
  (let [active (filter #(or (nil? (:when %)) ((:when %) req)) stages)
        resolved (mapv (fn [st] (assoc st :resolved (resolve-fn (:fn st)))) active)]
    (doseq [st resolved
            :when (nil? (get-in st [:resolved :fn]))]
      (cannot-run! (str "the " (if (:gate? st) "gate " "stage ") (stage-name st) " cannot run: "
                        (get-in st [:resolved :why]) "; a task never passes on a stage that did not run")))
    (when (:bus req)
      (doseq [st resolved
              :when (and (:subscriptions st) (get-in st [:resolved :fn]))
              :let [{subs :fn} (resolve-fn (:subscriptions st))]
              :when subs
              [event handler] (subs req)]
        (bus/subscribe! (:bus req) event (:fn st) handler)))
    (loop [[st & more] resolved result {} findings []]
      (if (nil? st)
        {:result result :findings findings}
        (let [out ((get-in st [:resolved :fn]) req)
              fs* (vec (:findings out))]
          (cond
            (:quiet? st) (recur more result findings)
            (and (:gate? st) (some (complement warning?) fs*))
            {:result (merge result (:result out)) :findings (into findings fs*)}
            :else (recur more (merge result (:result out)) (into findings fs*))))))))

(defn- catalogue-of [root config]
  (let [rel (config-value {:config config} :events)
        f (path root rel)]
    {:source rel
     :catalogue (when (fs/regular-file? f)
                  (try (let [v (edn/read-string (slurp (str f)))] (when (map? v) v))
                       (catch Exception _ nil)))}))

(defn- help-result []
  {:help {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)}})

(defn- exit-of [{:keys [cannot-run findings]}]
  (cond cannot-run 2
        (some (complement warning?) findings) 1
        :else 0))

(defn- kw-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn execute
  "Runs `rdm-spec words` in the project `root`, in this process:
  {:exit :result :findings :events :cannot-run}. `env` is the environment
  (the process's own when not given)."
  [words {:keys [root env]}]
  (let [root (str (fs/absolutize (or root ".")))
        env (or env (into {} (System/getenv)))
        b (atom nil)
        out (try
              (let [{:keys [task entry args opts events?]} (parse-args (vec words))]
                (if (:help? entry)
                  {:result (help-result) :findings [] :events? events?}
                  (let [config (read-config root)
                        _ (when (and (nil? config) (not (false? (:config? entry))))
                            (cannot-run! (str "there is no " config-file " in " root
                                              ": rdm-spec " task " reads the project's configuration"
                                              " (rdm-spec init or adopt writes one)")))
                        config (or config {})
                        bus* (bus/make-bus (catalogue-of root config))
                        _ (reset! b bus*)
                        req {:task task :args args :opts opts :root root :config config :env env
                             :bus bus* :state (atom {})
                             :results (when (:results entry)
                                        (results-dir opts env config root (:results entry)))}
                        req (if-let [rv (:revision entry)]
                              (let [{f :fn why :why} (resolve-fn rv)]
                                (when-not f (cannot-run! (str "rdm-spec " task " cannot run: " why)))
                                (assoc req :revision (f req)))
                              req)]
                    (assoc (run-stages req (:stages entry)) :events? events?))))
              (catch clojure.lang.ExceptionInfo e
                (if (cannot-run? e)
                  {:cannot-run (ex-message e)}
                  (throw e))))
        events (bus/events @b)]
    (assoc out
           :exit (exit-of out)
           :findings (vec (:findings out))
           :events (mapv (fn [{:keys [event publisher]}] {:event (kw-name event) :publisher (kw-name publisher)}) events))))

(defn report
  "The lines a run prints: {:out text :err [line]}."
  [{:keys [cannot-run result findings events events?]}]
  (if cannot-run
    {:out "" :err [(str "cannot-run: " (one-line cannot-run))]}
    {:out (binding [*print-namespace-maps* false] (str (pr-str (or result {})) "\n"))
     :err (vec (concat (map format-finding (remove warning? findings))
                       (map format-finding (filter warning? findings))
                       (when events?
                         (for [{:keys [event publisher]} events]
                           (str "event: " event " published-by " publisher)))))}))

(defn -main
  "rdm-spec <task> ...: runs the task in the working directory."
  [& words]
  (let [x (try (execute words {:root (str (fs/cwd))})
               (catch Throwable e
                 {:exit 2 :cannot-run (str "rdm-spec failed: " (.getName (class e)) ": " (ex-message e))}))
        {:keys [out err]} (report x)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit (:exit x 2))))
