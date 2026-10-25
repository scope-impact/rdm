(ns rdm-spec.kernel.command
  "Command (dhf/spec/kernel/command.feature): the one entry point,
  `rdm-spec <task> ...`. It belongs to no bounded context: it reads the
  project's configuration, makes the run's event bus, subscribes every stage
  of the task to the events it reads, runs the stages in the order the task
  table gives and reports the way every task reports (dhf/spec/README.md,
  \"How every task reports\").

  A stage is a function of the request returning {:result map :findings
  [finding]} (or nil). The request:

    {:task \"check\" :args [positional] :opts {:results .. :sources .. ..}
     :root \"project dir\" :config {..} :env {NAME value} :results dir-or-nil
     :events? bool :state (atom {}) :bus bus}

  The task table marks a stage
    :gate   it runs first and stops the task when it has a finding that is
            not a warning; the stages after it never run, and its result is
            never the task's. A gate whose code is not built refuses the task.
    :quiet  it runs for its events only: its result and findings are dropped
            (another stage relays what it must).
    :when   :regulated: it runs only when the configuration holds :regulated.
  Every other stage reports: the task's result merges theirs and every
  finding is named, so one run names every finding.

  The results directory a task reads is --results, else RESULTS_DIR, else
  the configured one; for trace and publish the configured one only when it
  exists (else no status is derived).

  Exit 0 a pass, 1 a finding that is not a warning, 2 a task that cannot run
  (an ex-info whose data holds ::cannot-run, thrown by cannot-run!)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; The configuration

(def config-file "dhf/harness/config.edn")

(def defaults
  "What a configuration that sets nothing means (dhf/harness/config.edn)."
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
   :cycles-dir "dhf/cycles"})

;; ---------------------------------------------------------------------------
;; Refusing, findings and warnings

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "Whether the exception `e` says the task cannot run."
  [e]
  (boolean (::cannot-run (ex-data e))))

(defn finding
  "A finding (exit 1): FILE:LINE: RULE: what. `file` and `line` may be nil."
  ([rule message] (finding nil nil rule message))
  ([file rule message] (finding file nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning, named after the findings; it never fails a task."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The finding's line on the error stream: `FILE:LINE: RULE: what`,
  `FILE: RULE: what` or `RULE: what`, a warning with `warning: ` before
  its rule."
  [{:keys [file line rule message] :as f}]
  (str (when (and file (not (str/blank? (str file))))
         (str file (when line (str ":" line)) ": "))
       (when (warning? f) "warning: ")
       rule ": " message))

;; ---------------------------------------------------------------------------
;; Paths

(defn- root-of [x] (str (if (map? x) (:root x) x)))

(defn path
  "The file `rel` names: relative to the project root (`x`, the root or a
  request), or absolute as given."
  [x rel]
  (let [r (str rel)]
    (if (fs/absolute? r) (fs/file r) (fs/file (root-of x) r))))

(defn rel
  "The path of `f` relative to the project root (`x`, the root or a
  request), with /; as given when it is outside the project."
  [x f]
  (let [root (fs/absolutize (root-of x))
        abs (fs/absolutize (str f))]
    (if (str/starts-with? (str (fs/normalize abs)) (str (fs/normalize root)))
      (str/replace (str (fs/relativize (fs/normalize root) (fs/normalize abs))) "\\" "/")
      (str f))))

(defn read-config
  "The configuration of the project at `root`, or nil when it has none.
  Cannot run when it is not one EDN map."
  [root]
  (let [f (path root config-file)]
    (when (fs/exists? f)
      (let [v (try (edn/read-string (slurp (str f)))
                   (catch Exception e (cannot-run! (str "the configuration " config-file " is not readable EDN: "
                                                        (ex-message e)))))]
        (when-not (map? v)
          (cannot-run! (str "the configuration " config-file " is not an EDN map")))
        v))))

(defn config-value
  "The request's configured `k`, else its default."
  [req k]
  (let [v (get-in req [:config k])]
    (if (some? v) v (get defaults k))))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the run's bus."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

;; ---------------------------------------------------------------------------
;; The task table

(defn- stage
  ([f] {:run f})
  ([f & {:as marks}] (merge {:run f} marks)))

(def tasks
  "Every task, in the order help lists them: its usage, what it does, its
  options ({flag key}, a value each), how many positional arguments it takes,
  whether it needs the configuration, which results directory it reads and
  its stages."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :args [0 0] :config? false :stages []}
   {:task "init" :usage "init PATH [--id-prefix P]"
    :does "write the skeleton into a path that does not exist"
    :options {"--id-prefix" :id-prefix} :args [1 1] :config? false
    :stages [(stage 'rdm-spec.project.init/init)]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [0 0] :config? false
    :stages [(stage 'rdm-spec.project.adopt/adopt)]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0]
    :stages [(stage 'rdm-spec.spec.stamper/stamp)]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses"
    :options {"--sources" :sources} :args [0 0]
    :stages [(stage 'rdm-spec.knowledge.sources/check)
             (stage 'rdm-spec.spec.check/check)
             (stage 'rdm-spec.architecture.lint/check)
             (stage 'rdm-spec.architecture.views/check)
             (stage 'rdm-spec.compliance.gap/gap :when :regulated)]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0]
    :stages [(stage 'rdm-spec.architecture.views/views)]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :config? false
    :stages [(stage 'rdm-spec.regeneration.cut/cut)]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2]
    :stages [(stage 'rdm-spec.regeneration.scopes/scopes)]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :options {"--context" :context "--component" :component} :args [2 2]
    :stages [(stage 'rdm-spec.measurement.oracle/oracle :gate true)
             (stage 'rdm-spec.regeneration.fence/fence)]}
   {:task "run" :usage "run [--results DIR]"
    :does "run the scenarios against a project's step definitions"
    :options {"--results" :results} :args [0 0] :results :run
    :stages [(stage 'rdm-spec.kernel.runner/run)]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :args [2 2]
    :stages [(stage 'rdm-spec.measurement.oracle/oracle :gate true)
             (stage 'rdm-spec.ratification.ratify/ratify)]}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1]
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.compliance.gap/gap :when :regulated :quiet true)
             (stage 'rdm-spec.measurement.checklist/checklist)]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :options {"--results" :results} :args [0 0] :results :required
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.verification.status/status)]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :options {"--results" :results} :args [1 1] :results :optional
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.verification.trace/trace)]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :options {"--results" :results} :args [0 0] :results :required
    :stages [(stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.compliance.gap/gap :when :regulated :quiet true)
             (stage 'rdm-spec.verification.release/release)]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :options {"--verdicts" :verdicts} :required #{"--verdicts"} :args [1 1]
    :stages [(stage 'rdm-spec.measurement.scorer/score)]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2]
    :stages [(stage 'rdm-spec.measurement.scoreboard/scoreboard)]}
   {:task "oracle" :usage "oracle [record --reason R]"
    :does "judge the instrument against its recorded goldens"
    :options {"--reason" :reason} :args [0 1]
    :stages [(stage 'rdm-spec.measurement.oracle/oracle)]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :options {"--results" :results} :args [0 0] :results :optional
    :stages [(stage 'rdm-spec.architecture.views/check :gate true)
             (stage 'rdm-spec.knowledge.sources/check :quiet true)
             (stage 'rdm-spec.spec.check/check :quiet true)
             (stage 'rdm-spec.verification.status/status :quiet true :when :results)
             (stage 'rdm-spec.publishing.site/publish)]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :options {"--results" :results} :args [1 1] :results :required
    :stages [(stage 'rdm-spec.compliance.audit/prepare)
             (stage 'rdm-spec.spec.parser/parse)
             (stage 'rdm-spec.compliance.gap/gap :quiet true)
             (stage 'rdm-spec.verification.release/release :gate true)
             (stage 'rdm-spec.compliance.audit/audit)]}])

(def global-flags
  "Flags every task takes: --events prints the events the run published."
  #{"--events"})

(defn task-named [n] (first (filter #(= n (:task %)) tasks)))

(defn- task-list [] (str/join ", " (map :task tasks)))

(defn usage-line [t] (str "rdm-spec " (:usage t)))

(defn help
  "The help task's result: every task with its usage and what it does."
  []
  {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)})

;; ---------------------------------------------------------------------------
;; Arguments

(defn parse-args
  "{:args [positional] :opts {key value} :events? bool} for the task `t`
  from the words after its name, or cannot run (an unknown argument, a
  flag with no value, too few or too many arguments)."
  [t words]
  (loop [ws words args [] opts {} events? false]
    (if-let [[w & more] (seq ws)]
      (cond
        (contains? global-flags w) (recur more args opts true)
        (str/starts-with? w "--")
        (if-let [k (get (:options t) w)]
          (if (and (seq more) (not (str/starts-with? (first more) "--")))
            (recur (rest more) args (assoc opts k (first more)) events?)
            (cannot-run! (str w " needs a value: " (usage-line t))))
          (cannot-run! (str "rdm-spec " (:task t) " takes no argument " w ": " (usage-line t))))
        :else (recur more (conj args w) opts events?))
      (let [[lo hi] (or (:args t) [0 0])]
        (when (> (count args) hi)
          (cannot-run! (str "rdm-spec " (:task t) " takes no argument " (nth args hi) ": " (usage-line t))))
        (when (< (count args) lo)
          (cannot-run! (str "rdm-spec " (:task t) " needs more arguments: " (usage-line t))))
        (doseq [flag (:required t) :when (nil? (get opts (get (:options t) flag)))]
          (cannot-run! (str "rdm-spec " (:task t) " needs " flag ": " (usage-line t))))
        {:args args :opts opts :events? events?}))))

(defn results-dir
  "The results directory the task reads: --results, else RESULTS_DIR, else
  the configured one (for an :optional reader only when it exists)."
  [t opts env config root]
  (let [given (or (:results opts) (not-empty (get env "RESULTS_DIR")))
        configured (or (:results-dir config) (:results-dir defaults))]
    (case (:results t)
      (:required :run) (or given configured)
      :optional (or given (when (fs/directory? (path root configured)) configured))
      nil)))

;; ---------------------------------------------------------------------------
;; Running the stages

(defn- resolve-stage!
  "The stage's function, its namespace required; cannot run when its code is
  not built."
  [t {:keys [run gate]}]
  (let [ns-sym (symbol (namespace run))]
    (try (require ns-sym)
         (catch Throwable e
           (if (re-find #"(?i)could not locate|not find" (str (ex-message e)))
             (cannot-run! (str (if gate (str "the gate " (name ns-sym)) (name ns-sym))
                               " is not built, so rdm-spec " (:task t) " cannot run"))
             (throw e))))
    (or (ns-resolve (the-ns ns-sym) (symbol (name run)))
        (cannot-run! (str run " is not built, so rdm-spec " (:task t) " cannot run")))))

(defn component-of
  "The component a namespace is, as the model names it: :architecture/lint."
  [ns-sym]
  (let [[ctx comp] (take-last 2 (str/split (str ns-sym) #"\."))]
    (keyword ctx comp)))

(defn- runs? [req {w :when}]
  (case w
    :regulated (contains? (:config req) :regulated)
    :results (some? (:results req))
    nil true
    true))

(defn- subscribe-all!
  "Subscribes each namespace's `subscriptions` (a function of the request
  giving [[event handler]]) to the run's bus, once per namespace."
  [req ns-syms]
  (doseq [n (distinct ns-syms)
          :let [subs (ns-resolve (the-ns n) 'subscriptions)]
          :when subs
          [event handler] (subs req)]
    (bus/subscribe! (:bus req) event (component-of n) handler)))

(defn run-stages
  "Runs the stages of the task `t` for `req`: {:result :findings}, and
  :gated the gate that stopped the task, if one did."
  [t req]
  (let [stages (filterv #(runs? req %) (:stages t))
        fns (mapv #(resolve-stage! t %) stages)]
    (subscribe-all! req (map #(symbol (namespace (:run %))) stages))
    (loop [[[s f] & more] (map vector stages fns) result {} findings []]
      (if-not s
        {:result result :findings findings}
        (let [out (f req)
              fs* (vec (:findings out))]
          (cond
            (:quiet s) (recur more result findings)
            (:gate s) (if (some (complement warning?) fs*)
                        {:result result :findings (into findings fs*) :gated (:run s)}
                        (recur more result (into findings fs*)))
            :else (recur more (merge result (:result out)) (into findings fs*))))))))

(defn- catalogue
  "The configured event catalogue for the bus: {:catalogue map :catalogue-file
  rel}; nil when the project has none (a missing or unreadable catalogue is
  the model lint's finding, not the bus's)."
  [root config]
  (when config
    (let [rel (or (:events config) (:events defaults))
          f (path root rel)]
      (when (fs/regular-file? f)
        (let [v (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
          (when (map? v) {:catalogue v :catalogue-file rel}))))))

(defn execute
  "Runs the task the words `words` name in the project at `root`, without
  printing: {:exit :task :result :findings :events :events? :cannot-run}."
  [words {:keys [root env]}]
  (let [root (str (or root (fs/cwd)))
        env (or env (into {} (System/getenv)))
        b (atom nil)
        events? (atom (boolean (some global-flags words)))]
    (try
      (let [[task-name & more] words
            t (or (when task-name (task-named task-name))
                  (cannot-run! (if task-name
                                 (str "unknown task " task-name "; the tasks are " (task-list)
                                      " (rdm-spec help gives each with its usage)")
                                 (str "no task given; the tasks are " (task-list)
                                      " (rdm-spec help gives each with its usage)"))))
            {:keys [args opts]} (parse-args t more)
            config (when-not (= "help" (:task t)) (read-config root))
            _ (when (and (not= false (:config? t)) (nil? config))
                (cannot-run! (str "no configuration " config-file " in this project: run rdm-spec " (:task t)
                                  " in a project set up by rdm-spec init or rdm-spec adopt")))
            _ (reset! b (bus/make-bus (catalogue root config)))
            req {:task (:task t) :args args :opts opts :root root :config (or config {}) :env env
                 :results (results-dir t opts env config root) :events? @events?
                 :state (atom {}) :bus @b}
            out (if (= "help" (:task t))
                  {:result (help) :findings []}
                  (run-stages t req))
            errors? (some (complement warning?) (:findings out))]
        (assoc out :exit (if errors? 1 0) :task (:task t)
               :events (bus/events @b) :events? @events?))
      (catch clojure.lang.ExceptionInfo e
        (if (cannot-run? e)
          {:exit 2 :cannot-run (ex-message e) :events (some-> @b bus/events) :events? @events?}
          (throw e))))))

(defn render
  "The outcome's standard output (text) and error stream (lines)."
  [{:keys [exit result findings cannot-run events events?]}]
  (let [event-lines (when events? (mapv bus/format-event events))]
    (if (= 2 exit)
      {:out "" :err (into [(str "cannot-run: " cannot-run)] event-lines)}
      {:out (str (binding [*print-namespace-maps* false] (pr-str (or result {}))) "\n")
       :err (vec (concat (map format-finding (remove warning? findings))
                         (map format-finding (filter warning? findings))
                         event-lines))})))

(defn run-task
  "Runs the task `words` in the project `dir` with the environment `env`
  (nil: this process's): {:exit :out :err [line]}, printing nothing."
  [words dir env]
  (let [outcome (try (execute words {:root dir :env env})
                     (catch Throwable e
                       {:exit 2 :cannot-run (str "the task failed unexpectedly: " (.getName (class e)) ": "
                                                 (ex-message e))}))]
    (assoc (render outcome) :exit (:exit outcome))))

(defn -main
  "rdm-spec <task> ...: runs the task in the current directory and exits
  with its code."
  [& words]
  (let [{:keys [exit out err]} (run-task (vec words) (str (fs/cwd)) (into {} (System/getenv)))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
