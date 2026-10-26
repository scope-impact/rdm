(ns rdm-spec.kernel.command
  "The command (dhf/spec/kernel/command.feature): the one entry point a hook,
  CI, an agent and a person all use, `rdm-spec <task>`, and the composition
  root. It belongs to no bounded context: it dispatches each task to the
  stages the contexts own, in the order of the task table, on one event bus
  per run (rdm-spec.kernel.bus).

  A stage is a function of the request, returning {:result {..} :findings
  [finding]}. The request:

    {:task :args [positional] :opts {flag value} :root DIR :config {..}
     :env {name value} :results DIR-or-nil :revision REV-or-nil
     :state (atom {}) :bus bus}

  Every stage reports, so one run names every finding, except:
    a gate   (:gate? true) stops the task when it has findings that are not
             warnings; the stages after it never run
    quiet    (:quiet? true) runs for its events; its result and findings are
             not reported
    :when    a stage whose condition does not hold is not run (gap analysis
             runs only under :regulated)
  A stage whose code is not built refuses the task (exit 2).

  How every task reports (dhf/spec/README.md): standard output holds only the
  result, as EDN ({} when there is none); the error stream one line per
  finding, `FILE:LINE: RULE: what` (FILE and LINE left out where none
  applies), then one per warning, `FILE:LINE: warning: RULE: what`; with
  --events, each event as `event: NAME published-by PUBLISHER`, after them.
  Exit 0 is a pass, 1 a finding, 2 a task that cannot run, which says
  `cannot-run: why` and prints nothing on standard output.

  The results directory of a task that reads results is --results, else the
  environment's RESULTS_DIR, else the configured :results-dir; for trace and
  publish, which also run with no results, the configured one only when it
  exists.

  API for the contexts: config-file, read-config, config-value, path, rel,
  finding, warning, warning?, format-finding, cannot-run!, cannot-run?,
  publish!, execute."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

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
  "A warning, as `finding` takes its arguments."
  ([rule message] (warning nil nil rule message))
  ([file rule message] (warning file nil rule message))
  ([file line rule message] (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn- one-line [s] (str/join " " (remove str/blank? (map str/trim (str/split-lines (str s))))))

(defn format-finding
  "The finding `f` as its line on the error stream."
  [{:keys [file line rule message] :as f}]
  (let [where (cond (and file line) (str file ":" line ": ")
                    file (str file ": ")
                    :else "")]
    (str where (when (warning? f) "warning: ") rule ": " (one-line message))))

;; ---------------------------------------------------------------------------
;; A task that cannot run

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), saying why."
  [why]
  (throw (ex-info (str why) {::cannot-run true})))

(defn cannot-run?
  "Whether the exception `e` says a task cannot run."
  [e]
  (boolean (::cannot-run (ex-data e))))

;; ---------------------------------------------------------------------------
;; Paths and the configuration

(def config-file "dhf/harness/config.edn")

(def defaults
  "What the configuration holds when it does not say."
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
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}})

(defn- req? [x] (and (map? x) (or (contains? x :config) (contains? x :root))))

(defn- root-of [x] (if (req? x) (:root x) x))

(defn path
  "The file `rel` names, relative to the root of `req-or-root` unless it is
  absolute."
  [req-or-root rel]
  (let [r (str rel)]
    (if (fs/absolute? r) (fs/file r) (fs/file (str (or (root-of req-or-root) ".")) r))))

(defn rel
  "The path of `f` relative to the root of `req-or-root`, with /; the
  absolute path when it lies outside the root."
  [req-or-root f]
  (let [root (fs/normalize (fs/absolutize (str (or (root-of req-or-root) "."))))
        abs (fs/normalize (fs/absolutize (str f)))]
    (if (fs/starts-with? abs root)
      (str/replace (str (fs/relativize root abs)) "\\" "/")
      (str abs))))

(defn config-value
  "The configuration's `k`, or its default. Takes a request or a
  configuration."
  [req-or-config k]
  (let [cfg (if (req? req-or-config) (:config req-or-config) req-or-config)
        v (get cfg k)]
    (if (nil? v) (get defaults k) v)))

(defn- read-config*
  "{:config m} | {:missing true} | {:unreadable why}."
  [root]
  (let [f (path root config-file)]
    (if-not (fs/regular-file? f)
      {:missing true}
      (let [v (try {:ok (edn/read-string (slurp (str f)))} (catch Exception e {:error (ex-message e)}))]
        (cond (:error v) {:unreadable (str "it is not readable EDN: " (:error v))}
              (not (map? (:ok v))) {:unreadable "it is not an EDN map"}
              :else {:config (:ok v)})))))

(defn read-config
  "The configuration of the project at `root`, or nil when it has none or
  it cannot be read."
  [root]
  (:config (read-config* root)))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the run's bus."
  [req publisher event payload]
  (bus/publish! (:bus req) publisher event payload))

(defn- catalogue [root cfg]
  (let [rel (config-value cfg :events)
        f (path root rel)]
    {:source rel
     :catalogue (try (let [v (edn/read-string (slurp (str f)))] (if (map? v) v {}))
                     (catch Exception _ {}))}))

;; ---------------------------------------------------------------------------
;; The task table

(defn- regulated? [req] (contains? (:config req) :regulated))

(def ^:private parser-stage {:fn 'rdm-spec.spec.parser/parse :quiet? true})

(def ^:private gap-quiet {:fn 'rdm-spec.compliance.gap/gap :quiet? true :when regulated?})

(def ^:private oracle-gate {:fn 'rdm-spec.measurement.oracle/oracle :gate? true})

(defn- cycle-cut
  "The cut the cycle named by the task's first argument names, or nil."
  [req]
  (let [cycle (first (:args req))
        f (path req (str (str/replace (str (config-value req :cycles-dir)) #"/+$" "") "/" cycle "/cycle.edn"))]
    (when (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (fs/regular-file? f))
      (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
        (when (and (map? c) (string? (:cut c)) (not (str/blank? (:cut c))))
          (:cut c))))))

(def tasks
  "Every task, in the order help lists them: {:task :usage :does :args [min
  max] :flags #{flag} :config? :results :required|:optional :revision fn
  :stages [{:fn sym :gate? :quiet? :when}]}."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :args [0 0] :config? false :stages [{:fn ::help}]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :args [1 1] :flags #{:id-prefix} :config? false :stages [{:fn 'rdm-spec.project.init/init}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :args [0 0] :config? false :stages [{:fn 'rdm-spec.project.adopt/adopt}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0] :stages [{:fn 'rdm-spec.spec.stamper/stamp}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses"
    :args [0 0] :flags #{:sources}
    :stages [{:fn 'rdm-spec.knowledge.sources/check}
             {:fn 'rdm-spec.spec.check/check}
             {:fn 'rdm-spec.architecture.lint/check}
             {:fn 'rdm-spec.architecture.views/check}
             {:fn 'rdm-spec.compliance.gap/gap :when regulated?}]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0] :stages [{:fn 'rdm-spec.architecture.views/views}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :stages [{:fn 'rdm-spec.regeneration.cut/cut}]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :stages [{:fn 'rdm-spec.regeneration.scopes/scopes}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :args [2 2] :flags #{:context :component}
    :stages [oracle-gate {:fn 'rdm-spec.regeneration.fence/fence}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :flags #{:results} :results :required :stages [{:fn 'rdm-spec.kernel.runner/run}]}
   {:task "ratify" :usage "ratify ROOM CYCLE" :does "run the builder's tests on the room's code in a checkout of the cut"
    :args [2 2] :stages [oracle-gate {:fn 'rdm-spec.ratification.ratify/ratify}]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1] :revision cycle-cut
    :stages [parser-stage gap-quiet {:fn 'rdm-spec.measurement.checklist/checklist}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :flags #{:results} :results :required
    :stages [parser-stage {:fn 'rdm-spec.verification.status/status}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :flags #{:results} :results :optional
    :stages [parser-stage {:fn 'rdm-spec.verification.trace/trace}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0] :flags #{:results} :results :required
    :stages [parser-stage gap-quiet {:fn 'rdm-spec.verification.release/release}]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :args [1 1] :flags #{:verdicts} :stages [{:fn 'rdm-spec.measurement.scorer/score}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2] :stages [{:fn 'rdm-spec.measurement.scoreboard/scoreboard}]}
   {:task "oracle" :usage "oracle [record --reason R]" :does "judge the instrument against its recorded goldens"
    :args [0 1] :flags #{:reason} :stages [{:fn 'rdm-spec.measurement.oracle/oracle}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0] :flags #{:results} :results :optional
    :stages [{:fn 'rdm-spec.architecture.views/check :gate? true}
             {:fn 'rdm-spec.knowledge.sources/check :quiet? true}
             {:fn 'rdm-spec.spec.check/check :quiet? true}
             {:fn 'rdm-spec.verification.status/derive! :quiet? true}
             {:fn 'rdm-spec.publishing.site/publish}]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :flags #{:results} :results :required
    :stages [{:fn 'rdm-spec.compliance.audit/prepare}
             parser-stage
             gap-quiet
             {:fn 'rdm-spec.verification.release/release :gate? true}
             {:fn 'rdm-spec.compliance.audit/audit}]}])

(defn task [nm] (first (filter #(= nm (:task %)) tasks)))

(defn- task-names [] (str/join ", " (map :task tasks)))

(defn- help [_]
  {:result {:help {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)}} :findings []})

;; ---------------------------------------------------------------------------
;; Arguments

(def ^:private every-task-flags #{:events})

(defn parse-args
  "{:args [positional] :opts {flag value}} of `words`, the flags among
  `flags` (keywords; :events takes no value). Cannot run on another flag or
  a flag with no value."
  [words flags]
  (loop [[w & more] words args [] opts {}]
    (cond
      (nil? w) {:args args :opts opts}

      (and (str/starts-with? w "--") (> (count w) 2))
      (let [[k v] (str/split (subs w 2) #"=" 2)
            kw (keyword k)]
        (when-not (contains? flags kw)
          (cannot-run! (str "unknown argument " w)))
        (if (= :events kw)
          (recur more args (assoc opts :events true))
          (let [[v more] (if v [v more] [(first more) (rest more)])]
            (when (or (nil? v) (str/starts-with? v "--"))
              (cannot-run! (str w " takes a value")))
            (recur more args (assoc opts kw (if-let [old (get opts kw)] (str old "," v) v))))))

      :else (recur more (conj args w) opts))))

(defn results-dir
  "The results directory of a task whose results are `mode` (:required or
  :optional): --results, else RESULTS_DIR, else the configured one (when it
  exists, for :optional)."
  [req mode opts env]
  (when mode
    (or (not-empty (:results opts))
        (not-empty (get env "RESULTS_DIR"))
        (let [d (config-value req :results-dir)]
          (when (or (= :required mode) (fs/directory? (path req d)))
            d)))))

;; ---------------------------------------------------------------------------
;; Running stages

(defn- stage-fn [f]
  (cond
    (= ::help f) help
    (symbol? f) (or (try (requiring-resolve f) (catch Throwable _ nil))
                    (cannot-run! (str "the stage " f " is not built, so the task cannot run")))
    :else f))

(defn- component-of [f]
  (when (symbol? f)
    (let [parts (str/split (namespace f) #"\.")]
      (keyword (nth parts (- (count parts) 2)) (last parts)))))

(defn subscribe-stages!
  "Subscribes, on the request's bus, every subscription of the namespaces
  the `stages` belong to, once each."
  [req stages]
  (doseq [ns-sym (distinct (keep #(when (symbol? (:fn %)) (symbol (namespace (:fn %)))) stages))
          :let [f (try (requiring-resolve (symbol (str ns-sym) "subscriptions")) (catch Throwable _ nil))]
          :when f
          [event handler] (f req)]
    (bus/subscribe! (:bus req) event (keyword (last (butlast (str/split (str ns-sym) #"\.")))
                                              (last (str/split (str ns-sym) #"\.")))
                    handler)))

(defn run-stages
  "Runs `stages` in order on `req`: {:result merged :findings reported}."
  [req stages]
  (loop [[st & more] stages result {} findings []]
    (if (nil? st)
      {:result result :findings findings}
      (if (and (:when st) (not ((:when st) req)))
        (recur more result findings)
        (let [out (or ((stage-fn (:fn st)) req) {})
              fs* (mapv #(merge {:severity :error} %) (:findings out))
              result (if (:quiet? st) result (merge result (:result out)))
              findings (if (:quiet? st) findings (into findings fs*))]
          (if (and (:gate? st) (some (complement warning?) fs*))
            {:result result :findings findings :stopped-by (component-of (:fn st))}
            (recur more result findings)))))))

;; ---------------------------------------------------------------------------
;; Executing a task

(defn- report
  "The standard output and the error stream of an execution."
  [{:keys [result findings cannot-run events events?]}]
  (let [event-lines (when events?
                      (for [{:keys [event publisher]} events]
                        (str "event: " (name event) " published-by "
                             (if (keyword? publisher) (subs (str publisher) 1) (str publisher)))))]
    (if cannot-run
      {:out "" :err (str/join (map #(str % "\n") (cons (str "cannot-run: " (one-line cannot-run)) event-lines)))}
      (let [lines (distinct (concat (map format-finding (remove warning? findings))
                                    (map format-finding (filter warning? findings))))]
        {:out (str (pr-str (or result {})) "\n")
         :err (str/join (map #(str % "\n") (concat lines event-lines)))}))))

(defn- prepare
  "The request of `t` for `words`, or a cannot-run."
  [t words root env]
  (let [{:keys [args opts]} (parse-args words (into every-task-flags (:flags t)))
        [lo hi] (:args t)
        _ (when-not (<= lo (count args) hi)
            (cannot-run! (str (:task t) " takes " (if (= lo hi) lo (str lo " to " hi)) " argument"
                              (when-not (= 1 hi) "s") ", not " (count args) ": rdm-spec " (:usage t))))
        rc (read-config* root)
        config (cond
                 (:config rc) (:config rc)
                 (false? (:config? t)) {}
                 (:missing rc) (cannot-run! (str "there is no configuration " config-file " in " root
                                                 ": rdm-spec " (:task t) " runs in a project (rdm-spec init or adopt)"))
                 :else (cannot-run! (str "the configuration " config-file " cannot be read: " (:unreadable rc))))
        base {:task (:task t) :args args :opts opts :root root :config config :env env :state (atom {})}
        {:keys [source] :as cat} (catalogue root config)
        req (assoc base
                   :results (results-dir base (:results t) opts env)
                   :bus (bus/make-bus {:catalogue (:catalogue cat) :source source}))]
    (assoc req :revision (when-let [f (:revision t)] (f req)))))

(defn execute
  "Runs `rdm-spec words` at (:root opts) with the environment (:env opts):
  {:exit :result :findings :cannot-run :events :out :err}."
  [words {:keys [root env]}]
  (let [root (str (fs/normalize (fs/absolutize (str (or root ".")))))
        env (or env (into {} (System/getenv)))
        [nm & more] words
        t (task nm)
        events? (boolean (some #{"--events"} more))
        req (atom nil)
        x (try
            (when-not t
              (cannot-run! (str (if nm (str "unknown task " nm) "no task was given")
                                "; the tasks are " (task-names) " (rdm-spec help lists each with its usage)")))
            (reset! req (prepare t (vec more) root env))
            (subscribe-stages! @req (:stages t))
            (let [{:keys [result findings]} (run-stages @req (:stages t))]
              {:exit (if (some (complement warning?) findings) 1 0) :result result :findings findings})
            (catch clojure.lang.ExceptionInfo e
              (if (cannot-run? e)
                {:exit 2 :cannot-run (ex-message e)}
                {:exit 2 :cannot-run (str "the task failed unexpectedly: " (ex-message e))}))
            (catch Throwable e
              {:exit 2 :cannot-run (str "the task failed unexpectedly: " (.getName (class e))
                                        (when (ex-message e) (str ": " (ex-message e))))}))
        x (assoc x :events (if @req (bus/events (:bus @req)) []) :events? events?)]
    (merge (dissoc x :events?) (report x))))

(defn -main [& args]
  (let [x (execute (vec args) {:root (str (fs/cwd)) :env (into {} (System/getenv))})]
    (print (:out x))
    (flush)
    (binding [*out* *err*]
      (print (:err x))
      (flush))
    (System/exit (:exit x))))
