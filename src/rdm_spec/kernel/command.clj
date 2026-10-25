(ns rdm-spec.kernel.command
  "The rdm-spec command (dhf/spec/kernel/command.feature): the one entry point
  a hook, CI, an agent and a person all use. It belongs to no bounded
  context: it reads the arguments, builds the request, and runs the task's
  stages, each a function of the request owned by the context that owns the
  work.

  The task table (`tasks`) gives, for every task, its usage (which is also
  how its arguments are read), what it does, its gates and its stages, each
  a fully qualified symbol resolved when the task runs, so a context that is
  not built yet stops only the tasks that need it.

    gates    run first; a gate with an error finding stops the task, and the
             stages after it never run (glossary: Gate). A gate whose code is
             not built refuses the task (exit 2).
    stages   run in order; every stage reports, so one run names every
             finding. A stage whose code is not built refuses the task too.

  A stage is (fn [req] {:result map :findings [finding]}); results are merged
  into the task's one result. A stage namespace with a `subscriptions`
  function, (fn [req] [[event handler] ...]), is subscribed to the run's bus
  before any stage runs, in table order.

  The request:
    {:task \"status\" :args [positional] :opts {:results \"..\" ...}
     :root \"/abs/project\" :config map-or-nil :env {var value}
     :results dir-or-nil :bus bus :state (atom {}) :events? bool}

  A finding: {:file :line :rule :message :severity :error | :warning}.

  How every task reports (dhf/spec/README.md): standard output holds only the
  result as EDN ({} when there is none); the error stream holds one line per
  finding, then one per warning, then, with --events, one per event. Exit 0
  is a pass, 1 a finding, 2 a task that cannot run (`cannot-run: why` on the
  error stream, nothing on standard output)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Refusing, finding, warning

(def config-file
  "Where a project's configuration lives, relative to its root."
  "dhf/harness/config.edn")

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2), and `message` says why."
  [message]
  (throw (ex-info (str message) {::cannot-run true})))

(defn cannot-run?
  "Whether the throwable `e` is a refusal (a task that cannot run)."
  [e]
  (boolean (::cannot-run (ex-data e))))

(defn finding
  "An error finding: FILE:LINE: RULE: message (file and line may be nil)."
  ([rule message] (finding nil nil rule message))
  ([file line rule message]
   {:file (some-> file str) :line line :rule (str rule) :message (str message) :severity :error}))

(defn warning
  "A warning: FILE:LINE: warning: RULE: message (file and line may be nil)."
  ([rule message] (warning nil nil rule message))
  ([file line rule message]
   (assoc (finding file line rule message) :severity :warning)))

(defn warning? [f] (= :warning (:severity f)))

(defn format-finding
  "The finding's line on the error stream."
  [{:keys [file line rule message] :as f}]
  (let [where (cond (and file line) (str file ":" line ": ")
                    file (str file ": ")
                    :else "")]
    (str where (when (warning? f) "warning: ") rule ": " message)))

;; ---------------------------------------------------------------------------
;; Paths and the configuration

(defn- root-of [req-or-root]
  (str (if (map? req-or-root) (:root req-or-root) req-or-root)))

(defn path
  "The file at `rel` in the project of `req-or-root` (a request or a root
  directory); an absolute `rel` stays as it is."
  [req-or-root rel]
  (let [rel (str (or rel ""))]
    (if (and (not (str/blank? rel)) (fs/absolute? rel))
      (fs/file rel)
      (if (str/blank? rel)
        (fs/file (root-of req-or-root))
        (fs/file (root-of req-or-root) rel)))))

(defn rel
  "The path of the file `f` relative to the project root, with forward
  slashes; a file outside the project keeps its absolute path."
  [req-or-root f]
  (let [root (fs/absolutize (root-of req-or-root))
        f (fs/absolutize (str f))]
    (str/replace (if (str/starts-with? (str (fs/normalize f)) (str (fs/normalize root)))
                   (str (fs/relativize (fs/normalize root) (fs/normalize f)))
                   (str f))
                 "\\" "/")))

(defn read-config
  "The configuration of the project at `root`: a map, or nil when there is
  none. One that is not an EDN map cannot run."
  [root]
  (let [f (path root config-file)]
    (when (fs/exists? f)
      (let [c (try (edn/read-string (slurp (str f)))
                   (catch Exception e (cannot-run! (str "the configuration " config-file
                                                        " is not readable EDN: " (ex-message e)))))]
        (when-not (map? c) (cannot-run! (str "the configuration " config-file " is not a map")))
        c))))

(defn config-value
  "The configured value of `k`, or cannot run, naming the key."
  [req k]
  (let [v (get (:config req) k)]
    (when (nil? v)
      (cannot-run! (if (:config req)
                     (str "the configuration " config-file " sets no " k)
                     (str "no configuration " config-file " (it would set " k ")"))))
    v))

;; ---------------------------------------------------------------------------
;; Events

(defn publish!
  "Publishes `event` with `payload` from `publisher` on the run's bus; a
  request without a bus publishes nothing."
  [req publisher event payload]
  (when-let [b (:bus req)]
    (bus/publish! b publisher event payload))
  nil)

(defn- read-catalogue
  "The configured event catalogue, or nil when none can be read (the model
  lint names why)."
  [root config]
  (when-let [rel (:events config)]
    (let [f (path root rel)]
      (when (fs/regular-file? f)
        (let [c (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
          (when (map? c) c))))))

;; ---------------------------------------------------------------------------
;; The task table

(def tasks
  "Every task: :task, :usage (also how its arguments are read), :does, :gates
  and :stages (qualified symbols), and :results, how its results directory
  is found: :always (--results, else RESULTS_DIR, else the configured one) or
  :when-exists (the same, but the configured one only when it exists)."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :stages ['rdm-spec.kernel.command/help]}
   {:task "init" :usage "init PATH [--id-prefix P]"
    :does "write the skeleton into a path that does not exist"
    :stages ['rdm-spec.project.init/init]}
   {:task "adopt" :usage "adopt"
    :does "add the skeleton to the current repository, overwriting nothing"
    :stages ['rdm-spec.project.adopt/adopt]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :stages ['rdm-spec.spec.stamper/stamp]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki"
    :stages ['rdm-spec.knowledge.sources/check 'rdm-spec.spec.check/check
             'rdm-spec.architecture.lint/check 'rdm-spec.architecture.views/check]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :stages ['rdm-spec.architecture.views/views]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :stages ['rdm-spec.regeneration.cut/cut]}
   {:task "scopes" :usage "scopes CUT CUT"
    :does "list the scopes a spec change touched between two cuts"
    :stages ['rdm-spec.regeneration.scopes/scopes]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]"
    :does "build a clean room from a cut"
    :gates ['rdm-spec.measurement.oracle/oracle]
    :stages ['rdm-spec.regeneration.fence/fence]}
   {:task "run" :usage "run [--results DIR]"
    :does "run the scenarios against a project's step definitions"
    :results :always
    :stages ['rdm-spec.kernel.runner/run]}
   {:task "ratify" :usage "ratify ROOM CYCLE"
    :does "run the builder's tests on the room's code in a checkout of the cut"
    :gates ['rdm-spec.measurement.oracle/oracle]
    :stages ['rdm-spec.ratification.ratify/ratify]}
   {:task "checklist" :usage "checklist CYCLE"
    :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.measurement.checklist/checklist]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :results :always
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.status/status]}
   {:task "trace" :usage "trace ID [--results DIR]"
    :does "a need or a scenario with everything linked to it"
    :results :when-exists
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.trace/trace]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :results :always
    :stages ['rdm-spec.spec.parser/parse 'rdm-spec.verification.release/release]}
   {:task "score" :usage "score CYCLE --verdicts FILE"
    :does "file a grader's verdicts on a ratified cycle into its score.edn"
    :stages ['rdm-spec.measurement.scorer/score]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :stages ['rdm-spec.measurement.scoreboard/scoreboard]}
   {:task "oracle" :usage "oracle [record --reason R]"
    :does "judge the instrument against its recorded goldens"
    :stages ['rdm-spec.measurement.oracle/oracle]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :results :when-exists
    ;; One copy of the model hash: the views' freshness check is the gate
    ;; before the site (dhf/spec/publishing/site.feature, spec-0099).
    :gates ['rdm-spec.architecture.views/check]
    :stages ['rdm-spec.knowledge.sources/check 'rdm-spec.spec.check/check
             'rdm-spec.verification.status/derive! 'rdm-spec.publishing.site/publish]}])

(defn task-row [task] (first (filter #(= task (:task %)) tasks)))

(defn task-names [] (mapv :task tasks))

(defn help
  "The help task: every task with its usage and what it does."
  [_req]
  {:result {:tasks (mapv #(select-keys % [:task :usage :does]) tasks)}
   :findings []})

;; ---------------------------------------------------------------------------
;; Reading the arguments, as the usage says

(defn- usage-tokens
  "The usage's words after the task name: [{:word :optional? :group n}]."
  [usage]
  (let [body (str/trim (str/replace-first usage #"^\S+" ""))]
    (loop [s body group 0 out []]
      (let [s (str/triml s)]
        (cond
          (str/blank? s) out
          (str/starts-with? s "[")
          (let [end (str/index-of s "]")
                inner (subs s 1 end)]
            (recur (subs s (inc end)) (inc group)
                   (into out (for [w (str/split (str/trim inner) #"\s+")]
                               {:word w :optional? true :group (inc group)}))))
          :else
          (let [[w] (str/split s #"\s+" 2)]
            (recur (subs s (count w)) group (conj out {:word w :optional? false :group nil}))))))))

(defn arg-spec
  "What the usage `usage` accepts: {:positional [{:name :optional? :literal?}]
  :options {\"name\" {:key :value? :list? :required?}}}."
  [usage]
  (let [toks (usage-tokens usage)]
    (loop [[t & more] toks pos [] opts {}]
      (if (nil? t)
        {:positional pos :options opts}
        (let [w (:word t)]
          (if (str/starts-with? w "--")
            (let [nxt (first more)
                  value? (and nxt (= (:group nxt) (:group t)) (not (str/starts-with? (:word nxt) "--"))
                              (not (re-matches #"[a-z]+" (:word nxt))))
                  n (subs w 2)]
              (recur (if value? (rest more) more) pos
                     (assoc opts n {:key (keyword n) :value? (boolean value?)
                                    :list? (boolean (and value? (str/includes? (:word nxt) ",...")))
                                    :required? (not (:optional? t))})))
            (recur more (conj pos {:name w :optional? (:optional? t)
                                   :literal? (boolean (re-matches #"[a-z]+" w))})
                   opts)))))))

(defn parse-args
  "Reads `argv` (the task's arguments) as `usage` says: {:args [..] :opts
  {..}}, or cannot run, naming what is wrong and the usage. --events is
  accepted by every task (how every task reports)."
  [usage argv]
  (let [{:keys [positional options]} (arg-spec usage)
        options (assoc options "events" {:key :events :value? false})
        refuse! (fn [why] (cannot-run! (str why "; usage: rdm-spec " usage)))]
    (loop [[a & more] argv args [] opts {}]
      (if (nil? a)
        (let [required (count (remove :optional? positional))]
          (when (< (count args) required)
            (refuse! (str "missing " (str/join " " (map :name (drop (count args) (remove :optional? positional)))))))
          (doseq [[n o] options :when (and (:required? o) (not (contains? opts (:key o))))]
            (refuse! (str "missing --" n)))
          {:args args :opts opts})
        (if (and (str/starts-with? a "--") (> (count a) 2))
          (let [[n inline] (str/split (subs a 2) #"=" 2)
                o (or (get options n) (refuse! (str "unknown option --" n)))]
            (if (:value? o)
              (let [v (or inline (first more))
                    more (if inline more (rest more))]
                (when (or (nil? v) (and (nil? inline) (str/starts-with? v "--")))
                  (refuse! (str "--" n " needs a value")))
                (when (and (contains? opts (:key o)) (not (:list? o)))
                  (refuse! (str "--" n " is given twice")))
                (recur more args (if (:list? o)
                                   (update opts (:key o) #(if % (str % "," v) v))
                                   (assoc opts (:key o) v))))
              (do (when inline (refuse! (str "--" n " takes no value")))
                  (recur more args (assoc opts (:key o) true)))))
          (let [slot (get positional (count args))]
            (when (or (nil? slot) (and (:literal? slot) (not= a (:name slot))))
              (refuse! (str "unknown argument " a)))
            (recur more (conj args a) opts)))))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage
  "The function a stage symbol names, or cannot run when its code is not
  built."
  [sym kind]
  (let [f (try (requiring-resolve sym) (catch Exception _ nil))]
    (or f (cannot-run! (str "the " kind " " sym " is not built: its code is not in this rdm-spec")))))

(defn- subscriber-of
  "rdm-spec.verification.status -> :verification/status."
  [ns-sym]
  (let [parts (str/split (str ns-sym) #"\.")]
    (keyword (str/join "." (butlast (rest parts))) (last parts))))

(defn- subscribe-all!
  [req syms]
  (doseq [ns-sym (distinct (map (comp symbol namespace) syms))
          :let [subs-fn (ns-resolve (the-ns ns-sym) 'subscriptions)]
          :when subs-fn
          [event handler] (subs-fn req)]
    (bus/subscribe! (:bus req) event (subscriber-of ns-sym) handler)))

(defn- results-dir
  "The task's results directory: --results, else RESULTS_DIR, else the
  configured one (for :when-exists, only when it exists)."
  [row opts env config root]
  (when-let [mode (:results row)]
    (let [given (or (not-empty (:results opts)) (not-empty (get env "RESULTS_DIR")))
          configured (:results-dir config)]
      (cond
        given given
        (and configured (= mode :always)) configured
        (and configured (fs/exists? (path root configured))) configured
        (= mode :always) (cannot-run! (str "no results directory: give --results DIR, set RESULTS_DIR"
                                           " or the configuration's :results-dir"))
        :else nil))))

(defn- outcome [findings]
  (if (some (complement warning?) findings) 1 0))

(defn- run-stages
  "Runs the request's gates, then its stages: {:result :findings}."
  [req row]
  (let [gates (mapv #(vector % (resolve-stage % "gate")) (:gates row))
        stages (mapv #(vector % (resolve-stage % "stage")) (:stages row))]
    (subscribe-all! req (concat (:gates row) (:stages row)))
    (loop [[[_ f gate?] & more] (concat (map #(conj % true) gates) (map #(conj % false) stages))
           result {} findings []]
      (if (nil? f)
        {:result result :findings findings}
        (let [out (f req)
              out (if (map? out) out {})
              result (merge result (when (map? (:result out)) (:result out)))
              findings (into findings (:findings out))]
          (if (and gate? (= 1 (outcome (:findings out))))
            {:result result :findings findings :stopped-by-gate true}
            (recur more result findings)))))))

(defn execute
  "Runs the task `argv` names in the project at `:root` with the environment
  `:env` (a map; nil: this process's). Never prints and never exits:
  {:exit 0|1|2 :result :findings :cannot-run why-or-nil :events [event]
   :events? bool}."
  [argv {:keys [root env]}]
  (let [root (str (fs/absolutize (or root ".")))
        env (or env (into {} (System/getenv)))
        b (atom nil)
        events? (boolean (some #{"--events"} (rest argv)))]
    (try
      (let [[task & argv] argv
            row (cond
                  (nil? task) (cannot-run! (str "no task given; the tasks are " (str/join ", " (task-names))))
                  :else (or (task-row task)
                            (cannot-run! (str "unknown task " task "; the tasks are "
                                              (str/join ", " (task-names))))))
            {:keys [args opts]} (parse-args (:usage row) (vec argv))
            config (when-not (#{"help" "init"} task) (read-config root))
            bus* (bus/make-bus {:catalogue (read-catalogue root config) :catalogue-file (:events config)})
            _ (reset! b bus*)
            req {:task task :args args :opts opts :root root :config config :env env
                 :results (results-dir row opts env config root)
                 :bus bus* :state (atom {}) :events? (boolean (:events opts))}
            {:keys [result findings]} (run-stages req row)
            findings (vec findings)]
        {:exit (outcome findings) :result result :findings findings :cannot-run nil
         :events (if @b (bus/events @b) []) :events? events?})
      (catch Throwable e
        ;; A refusal says why; anything else is a task that could not run
        ;; to the end, never a pass.
        {:exit 2 :result nil :findings []
         :cannot-run (if (cannot-run? e)
                       (ex-message e)
                       (str "the task failed unexpectedly: " (.getName (class e)) ": " (ex-message e)))
         :events (if @b (bus/events @b) []) :events? events?}))))

(defn- sorted-findings
  "Errors first, then warnings, each in the order found."
  [findings]
  (concat (remove warning? findings) (filter warning? findings)))

(defn report
  "What the task prints: {:exit :out string :err [line]}."
  [{:keys [exit result findings cannot-run events events?]}]
  (let [event-lines (when events? (mapv bus/format-event events))]
    (if cannot-run
      {:exit 2 :out ""
       :err (vec (concat [(str "cannot-run: " (str/replace cannot-run #"\s*\n\s*" " "))] event-lines))}
      {:exit exit
       :out (str (binding [*print-namespace-maps* false] (pr-str (or result {}))) "\n")
       :err (vec (concat (map format-finding (sorted-findings findings)) event-lines))})))

(defn run-task
  "Runs `argv` in the project `dir` with the environment `env` (nil: this
  process's), in this process: {:exit :out string :err [line]}."
  [argv dir env]
  (report (execute (vec argv) {:root dir :env env})))

(defn -main
  "rdm-spec <task> [args]: runs the task in the current directory, prints
  its result and findings, and exits 0, 1 or 2."
  [& argv]
  (let [{:keys [exit out err]} (try (run-task argv (System/getProperty "user.dir") nil)
                                    (catch Throwable e
                                      {:exit 2 :out ""
                                       :err [(str "cannot-run: the task failed unexpectedly: "
                                                  (.getName (class e)) ": " (ex-message e))]}))]
    (print out)
    (flush)
    (binding [*out* *err*]
      (doseq [l err] (println l))
      (flush))
    (System/exit exit)))
