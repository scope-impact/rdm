(ns rdm-spec.kernel.command
  "The rdm-spec command (dhf/spec/kernel/command.feature): the one entry
  point a hook, CI, an agent and a person all use. It belongs to no bounded
  context: it dispatches each task to the stages of the contexts that own
  it, as the task table (`tasks`) lists them, resolving each stage only when
  its task runs, so a context can be regenerated alone.

  A stage is a function of the request:

    {:task \"check\" :args [positional] :opts {:flag value}
     :root \"/abs/project\" :config {..} :env {name value}
     :bus bus :state (atom {}) :results DIR-or-nil}

  returning {:result {..} :findings [finding]}. A stage may also define
  `subscriptions`, (fn [req]) -> [[event handler]], subscribed on the run's
  bus before any stage runs. A task runs its stages in order and every stage
  reports, so one run names every finding; a stage marked :gate? stops the
  task when it has findings that are not warnings, and the stages after it
  never run; a :quiet? stage runs for what it publishes and reports
  nothing; a stage with :when runs only when the configuration holds that
  key. A stage whose code is not built refuses the task (exit 2). A task
  marked :config? false runs without a configuration in the working tree
  (cut, scopes, fence and ratify read the cut's; help, init and adopt
  write one).

  How every task reports (dhf/spec/README.md): standard output holds only
  the task's result, as EDN ({} when there is none); the error stream one
  line per finding, `FILE:LINE: RULE: what`, then one per warning, then,
  with --events, one per event, `event: NAME published-by PUBLISHER`. A task
  that cannot run says `cannot-run: why` and prints nothing on standard
  output. Exit 0 is a pass, 1 a finding, 2 a task that cannot run.

  The results directory of a task that reads one is --results, else
  RESULTS_DIR, else the configured :results-dir (for trace and publish, only
  when it exists: without one they say no status was derived)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

(def config-file
  "Where a project's harness configuration lives, relative to its root."
  "dhf/harness/config.edn")

;; ---------------------------------------------------------------------------
;; Findings, warnings and refusals

(defn finding
  "A finding (an error): {:file :line :rule :message :severity :error}."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message] {:file file :line line :rule rule :message message :severity :error}))

(defn warning
  "A warning: as a finding, :severity :warning. Never fails a task."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message] {:file file :line line :rule rule :message message :severity :warning}))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The error-stream line of a finding or warning."
  [{:keys [file line rule message] :as f}]
  (str (cond (and file line) (str file ":" line ": ")
             file (str file ": ")
             :else "")
       (when (warning? f) "warning: ")
       rule ": " message))

(defn cannot-run
  "The exception of a task that cannot run as asked, saying why."
  [why]
  (ex-info (str why) {::cannot-run true}))

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (cannot-run why)))

(defn cannot-run?
  "Whether `e` is a task that cannot run."
  [e]
  (boolean (::cannot-run (ex-data e))))

;; ---------------------------------------------------------------------------
;; Paths

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `rel` names: relative to the project root of `x` (a request or a
  root), or absolute."
  [x rel]
  (let [r (str rel)]
    (if (fs/absolute? r)
      (fs/file r)
      (fs/file (if (str/blank? (root-of x)) "." (root-of x)) r))))

(defn rel
  "The path of the file `f` relative to the project root of `x`, with /."
  [x f]
  (let [root (fs/normalize (fs/absolutize (if (str/blank? (root-of x)) "." (root-of x))))
        abs (fs/normalize (fs/absolutize (str f)))]
    (str/replace (str (if (fs/starts-with? abs root) (fs/relativize root abs) abs)) "\\" "/")))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the request's bus
  (nothing when the request has none)."
  [req publisher event payload]
  (bus/publish! (:bus req) publisher event payload))

;; ---------------------------------------------------------------------------
;; The configuration

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn config-value
  "The configured value of `k` for the request, or its default: :spec-dir
  dhf/spec, :id-prefix spec, :ledger, :model, :needs and :events beside the
  spec, :views-dir dhf/views, :raw-dir dhf/raw, :wiki-dir dhf/wiki,
  :steps-dir test/steps, :results-dir dhf/allure-results, :cycles-dir
  dhf/cycles."
  [req k]
  (let [c (:config req)
        v (get c k)
        spec-dir (strip-slash (or (:spec-dir c) "dhf/spec"))]
    (if (some? v)
      v
      (case k
        :spec-dir spec-dir
        :id-prefix "spec"
        :ledger (str spec-dir "/ids.edn")
        :model (str spec-dir "/model.edn")
        :needs (str spec-dir "/needs.edn")
        :events (str spec-dir "/events.edn")
        :views-dir "dhf/views"
        :raw-dir "dhf/raw"
        :wiki-dir "dhf/wiki"
        :steps-dir "test/steps"
        :results-dir "dhf/allure-results"
        :cycles-dir "dhf/cycles"
        nil))))

(defn read-config
  "The configuration of the project at `root`, read: a map, nil when there
  is none. A configuration that is not an EDN map cannot run."
  [root]
  (let [f (path root config-file)]
    (when (fs/regular-file? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e (cannot-run! (str "the configuration " config-file " is not readable EDN: "
                                                        (ex-message e)))))]
        (when-not (map? v)
          (cannot-run! (str "the configuration " config-file " is not an EDN map")))
        v))))

(defn- read-catalogue
  "The configured event catalogue: a map, {} when it is missing or not a
  map (the lint reports that)."
  [root config]
  (let [rel (config-value {:config config} :events)
        f (path root rel)]
    {:source rel
     :catalogue (or (when (fs/regular-file? f)
                      (try (let [v (edn/read-string (slurp (str f)))] (when (map? v) v))
                           (catch Exception _ nil)))
                    {})}))

;; ---------------------------------------------------------------------------
;; The task table

(def tasks
  "Every task, in the order help lists them: its usage, what it does, the
  arguments it takes and its stages."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :args [0 0] :config? false
    :stages [{:fn `help}]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args [1 1] :flags {"--id-prefix" :id-prefix} :config? false
    :stages [{:fn 'rdm-spec.project.init/init}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [0 0] :config? false
    :stages [{:fn 'rdm-spec.project.adopt/adopt}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0]
    :stages [{:fn 'rdm-spec.spec.stamper/stamp}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses"
    :args [0 0] :flags {"--sources" :sources}
    :stages [{:fn 'rdm-spec.knowledge.sources/check}
             {:fn 'rdm-spec.spec.check/check}
             {:fn 'rdm-spec.architecture.lint/check}
             {:fn 'rdm-spec.architecture.views/check}
             {:fn 'rdm-spec.compliance.gap/gap :when :regulated}]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0]
    :stages [{:fn 'rdm-spec.architecture.views/views}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :config? false
    :stages [{:fn 'rdm-spec.regeneration.cut/cut}]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :config? false
    :stages [{:fn 'rdm-spec.regeneration.scopes/scopes}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :args [2 2] :flags {"--context" :context "--component" :component} :config? false
    :stages [{:fn 'rdm-spec.measurement.oracle/oracle :gate? true}
             {:fn 'rdm-spec.regeneration.fence/fence}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :flags {"--results" :results} :results :write
    :stages [{:fn 'rdm-spec.kernel.runner/run}]}
   {:task "ratify" :usage "ratify ROOM CYCLE" :does "run the builder's tests on the room's code in a checkout of the cut"
    :args [2 2] :config? false
    :stages [{:fn 'rdm-spec.measurement.oracle/oracle :gate? true}
             {:fn 'rdm-spec.ratification.ratify/ratify}]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1]
    :stages [{:fn 'rdm-spec.spec.parser/parse}
             {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when :regulated}
             {:fn 'rdm-spec.measurement.checklist/checklist}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :flags {"--results" :results} :results :required
    :stages [{:fn 'rdm-spec.spec.parser/parse}
             {:fn 'rdm-spec.verification.status/status}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :flags {"--results" :results} :results :optional
    :stages [{:fn 'rdm-spec.spec.parser/parse}
             {:fn 'rdm-spec.verification.trace/trace}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0] :flags {"--results" :results} :results :required
    :stages [{:fn 'rdm-spec.spec.parser/parse}
             {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when :regulated}
             {:fn 'rdm-spec.verification.release/release}]}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args [1 1] :flags {"--verdicts" :verdicts}
    :stages [{:fn 'rdm-spec.measurement.scorer/score}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2]
    :stages [{:fn 'rdm-spec.measurement.scoreboard/scoreboard}]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args [0 1] :flags {"--reason" :reason}
    :stages [{:fn 'rdm-spec.measurement.oracle/oracle}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0] :flags {"--results" :results} :results :optional
    :stages [{:fn 'rdm-spec.architecture.views/check :gate? true}
             {:fn 'rdm-spec.knowledge.sources/check :quiet? true}
             {:fn 'rdm-spec.spec.check/check :quiet? true}
             {:fn 'rdm-spec.verification.status/derive! :quiet? true}
             {:fn 'rdm-spec.publishing.site/publish}]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :flags {"--results" :results} :results :required
    :stages [{:fn 'rdm-spec.compliance.audit/prepare}
             {:fn 'rdm-spec.spec.parser/parse}
             {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when :regulated}
             {:fn 'rdm-spec.verification.release/release :gate? true}
             {:fn 'rdm-spec.compliance.audit/audit}]}])

(def ^:private by-name (into {} (map (juxt :task identity)) tasks))

(defn- task-names [] (str/join ", " (map :task tasks)))

(defn help
  "The help task: every task with its usage."
  [_req]
  {:result {:help {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)}}
   :findings []})

;; ---------------------------------------------------------------------------
;; Arguments

(defn parse-args
  "{:args [positional] :opts {k v}} of a task's words after its name; cannot
  run on an argument the task does not take."
  [{:keys [task usage flags] [lo hi] :args} words]
  (let [usage-text (str "rdm-spec " usage)]
    (loop [[w & more] words args [] opts {}]
      (cond
        (nil? w)
        (do (when (or (< (count args) lo) (> (count args) hi))
              (cannot-run! (str task " takes " (if (= lo hi) lo (str lo " to " hi)) " argument"
                                (when-not (= 1 hi) "s") ", not " (count args)
                                (when (seq args) (str " (" (str/join " " args) ")")) ": " usage-text)))
            {:args args :opts opts})

        (= "--events" w) (recur more args (assoc opts :events true))

        (str/starts-with? w "--")
        (let [[flag inline] (str/split w #"=" 2)
              k (get flags flag)]
          (when-not k
            (cannot-run! (str "rdm-spec " task " takes no argument " flag ": " usage-text)))
          (let [v (or inline (first more))
                more (if inline more (rest more))]
            (when (nil? v)
              (cannot-run! (str flag " needs a value: " usage-text)))
            (recur more args (update opts k #(if % (str % "," v) v)))))

        :else (recur more (conj args w) opts)))))

(defn- results-dir
  "The results directory a task reads, as given: --results, else
  RESULTS_DIR, else the configured one (for :optional, when it exists)."
  [task opts env root config]
  (when-let [mode (:results task)]
    (let [given (or (not-empty (:results opts)) (not-empty (get env "RESULTS_DIR")))
          configured (config-value {:config config} :results-dir)]
      (case mode
        :required (or given configured)
        :write (or given configured)
        :optional (or given (when (fs/directory? (path root configured)) configured))))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- ns-file [sym]
  (str (-> (namespace sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(defn- resolve-stage
  "The function a stage names; the task cannot run when its code is not built."
  [{f :fn gate? :gate?}]
  (if (fn? f)
    f
    (or (try (requiring-resolve f)
             (catch Throwable e
               (cannot-run! (if (io/resource (ns-file f))
                              (str "the stage " f " could not be loaded: " (ex-message e))
                              (str (if gate? "the gate " "the stage ") f " is not built ("
                                   (ns-file f) " is missing), so " (namespace f) " cannot run")))))
        (cannot-run! (str "the stage " f " is not built: " (namespace f) " defines no " (name f))))))

(defn- subscriber [sym]
  (let [parts (str/split (namespace sym) #"\.")]
    (keyword (str/join "." (butlast (rest parts))) (last parts))))

(defn- subscribe-stages!
  "Subscribes each stage namespace's subscriptions, once; the stages are
  resolved (loaded) first."
  [req stages]
  (doseq [st stages] (resolve-stage st))
  (doseq [ns-sym (distinct (keep #(when (symbol? (:fn %)) (symbol (namespace (:fn %)))) stages))
          :let [subs (ns-resolve ns-sym 'subscriptions)]
          :when subs
          [event handler] (subs req)]
    (bus/subscribe! (:bus req) event (subscriber (symbol (str ns-sym) "x")) handler)))

(defn- run-stages
  "Runs the stages of a task: {:result :findings}."
  [req stages]
  (loop [[st & more] stages result {} findings []]
    (if (nil? st)
      {:result result :findings findings}
      (let [f (resolve-stage st)
            out (f req)
            fs* (vec (:findings out))]
        (cond
          (:quiet? st) (recur more result findings)
          (and (:gate? st) (some (complement warning?) fs*))
          {:result (merge result (:result out)) :findings (into findings fs*)}
          :else (recur more (merge result (:result out)) (into findings fs*)))))))

(defn execute
  "Runs `rdm-spec words...` in the project at :root (default the working
  directory) with the environment :env (default the process's):
  {:exit :result :findings :events :cannot-run :task :opts}."
  [words {:keys [root env]}]
  (let [root (str (fs/normalize (fs/absolutize (or root "."))))
        env (or env (into {} (System/getenv)))
        [task-name & more] (map str words)
        state (atom {})
        the-bus (atom nil)]
    (try
      (let [task (or (get by-name task-name)
                     (cannot-run! (if task-name
                                    (str "unknown task " task-name "; the tasks are " (task-names)
                                         " (rdm-spec help lists each with its usage)")
                                    (str "no task given; the tasks are " (task-names)
                                         " (rdm-spec help lists each with its usage)"))))
            {:keys [args opts]} (parse-args task more)
            _ (swap! state assoc :opts opts)
            config (read-config root)
            _ (when (and (not (false? (:config? task))) (nil? config))
                (cannot-run! (str "no " config-file " in " root ": rdm-spec " (:task task)
                                  " runs in a project set up by rdm-spec init or adopt")))
            config (or config {})
            {:keys [catalogue source]} (read-catalogue root config)
            b (bus/make-bus {:catalogue catalogue :source source})
            _ (reset! the-bus b)
            req {:task (:task task) :args args :opts opts :root root :config config :env env
                 :bus b :state (atom {}) :results (results-dir task opts env root config)}
            stages (filter #(or (nil? (:when %)) (contains? config (:when %))) (:stages task))
            _ (subscribe-stages! req stages)
            {:keys [result findings]} (run-stages req stages)
            findings (vec findings)]
        {:exit (if (some (complement warning?) findings) 1 0)
         :result (or result {})
         :findings findings
         :events (bus/events b)
         :task (:task task)
         :opts opts})
      (catch Throwable e
        {:exit 2
         :cannot-run (if (cannot-run? e)
                       (ex-message e)
                       (str (or task-name "rdm-spec") " failed: " (.getName (class e))
                            (when (ex-message e) (str ": " (ex-message e)))))
         :events (bus/events @the-bus)
         :opts (:opts @state)}))))

(defn- event-line [{:keys [event publisher]}]
  (letfn [(nm [k] (if (keyword? k) (subs (str k) 1) (str k)))]
    (str "event: " (nm event) " published-by " (nm publisher))))

(defn render
  "The output of an execution: {:exit :out :err [line]}."
  [{:keys [exit result findings events cannot-run opts]}]
  (let [event-lines (when (:events opts) (map event-line events))]
    (if cannot-run
      {:exit 2 :out "" :err (vec (concat [(str "cannot-run: " cannot-run)] event-lines))}
      {:exit exit
       :out (str (binding [*print-namespace-maps* false] (pr-str (or result {}))) "\n")
       :err (vec (concat (map format-finding (remove warning? findings))
                         (map format-finding (filter warning? findings))
                         event-lines))})))

(defn run-task
  "Runs `rdm-spec args...` in `root` with the environment `env` (nil: the
  process's): {:exit :out :err [line]}."
  [args root env]
  (render (execute args {:root root :env env})))

(defn -main
  "rdm-spec <task> [args]: runs the task in the working directory."
  [& args]
  (let [{:keys [exit out err]} (run-task args "." nil)]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
