(ns rdm-spec.kernel.command
  "The rdm-spec command (dhf/spec/kernel/command.feature): the one entry
  point, dispatching each task to the stages of the contexts that own it,
  through the task table. It belongs to no bounded context, and holds the
  helpers every context uses: findings, reading the project at a revision,
  running a command.

  How every task reports (dhf/spec/README.md): standard output holds only
  the task's result as EDN; the error stream one line per finding, then one
  per warning, then, with --events, one per event published. Exit 0 passes,
  1 has findings, 2 cannot run (`cannot-run: why`, nothing on standard
  output).

  A task runs its stages in order and every stage reports, so one run names
  every finding; a stage marked as a gate stops the task when it has findings
  that are not warnings. A stage whose code is not built refuses the task.
  The context namespaces are required only when a task runs one of their
  stages, as they require this one."
  (:require [babashka.classpath :as classpath]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.runner :as runner]))

;; ---------------------------------------------------------------------------
;; Findings and refusals

(defn finding
  "A finding about FILE (nil when none applies) at LINE (nil when none)."
  [file line rule message]
  {:file file :line line :rule rule :message message})

(defn cannot-run!
  "Refuses the task: exit 2, saying why."
  [why]
  (throw (ex-info (str why) {:cannot-run (str why)})))

(defn- one-line [s] (str/replace (str s) #"\s*\r?\n\s*" " "))

(defn finding-line
  "A finding as the error stream holds it; a relayed line is kept as it is."
  [{:keys [file line rule message line-text]}]
  (or line-text
      (str (when file (str file (when line (str ":" line)) ": ")) rule ": " (one-line message))))

(defn warning-line
  "A warning as the error stream holds it."
  [{:keys [file line rule message line-text]}]
  (or line-text
      (str (when file (str file (when line (str ":" line)) ": ")) "warning: " rule ": " (one-line message))))

;; ---------------------------------------------------------------------------
;; Running a command

(defn tmp-dir
  "The temporary directory: the caller's TMPDIR, else the runtime's."
  []
  (let [t (System/getenv "TMPDIR")]
    (if (str/blank? t) (System/getProperty "java.io.tmpdir") t)))

(defn temp-file [suffix] (fs/create-temp-file {:dir (tmp-dir) :prefix "rdm-spec-" :suffix suffix}))

(defn temp-dir [prefix] (fs/create-temp-dir {:dir (tmp-dir) :prefix prefix}))

(defn sh
  "Runs cmd: {:exit :out :err}, or :not-found when it cannot be started.
  opts: :dir, :env (added to the environment), :in."
  [{:keys [dir env in]} & cmd]
  (try
    (let [r @(p/process (mapv str cmd) (cond-> {:out :string :err :string :in (or in "")}
                                         dir (assoc :dir (str dir))
                                         env (assoc :extra-env env)))]
      {:exit (:exit r) :out (:out r) :err (:err r)})
    (catch java.io.IOException e
      {:exit 127 :out "" :err (ex-message e) :not-found true})))

(defn git
  "Runs git in root: {:exit :out :err}."
  [root & args]
  (apply sh {:dir root} "git" args))

(defn git!
  "Runs git in root; its output, trimmed, or a refusal."
  [root & args]
  (let [{:keys [exit out err not-found]} (apply git root args)]
    (cond
      not-found (cannot-run! "git is not installed")
      (not= 0 exit) (cannot-run! (str "git " (str/join " " args) " failed: " (one-line err)))
      :else (str/trim out))))

;; ---------------------------------------------------------------------------
;; Reading the project at a revision (nil or :working-tree, the files on disk)

(defn working-tree? [rev] (or (nil? rev) (= :working-tree rev)))

(defn read-at
  "The text of path at rev, or nil when it holds none."
  [root rev path]
  (if (working-tree? rev)
    (let [f (fs/file root path)] (when (fs/regular-file? f) (slurp f)))
    (let [{:keys [exit out]} (git root "show" (str rev ":" path))]
      (when (= 0 exit) out))))

(defn files-at
  "Every file under dir at rev, relative to root, sorted."
  [root rev dir]
  (if (working-tree? rev)
    (let [d (fs/path root dir)]
      (if (fs/directory? d)
        (->> (fs/glob d "**" {:hidden true})
             (filter fs/regular-file?)
             (map #(str (fs/relativize (fs/absolutize root) (fs/absolutize %))))
             sort vec)
        []))
    (let [{:keys [exit out]} (git root "ls-tree" "-r" "--name-only" rev "--" dir)]
      (if (= 0 exit) (vec (sort (remove str/blank? (str/split-lines out)))) []))))

(defn read-edn [text] (edn/read-string text))

(defn results-dir
  "The results directory a task reads: --results, else RESULTS_DIR, else the
  configured one."
  [{:keys [opts env config]}]
  (or (:results opts)
      (let [e (get env "RESULTS_DIR")] (when-not (str/blank? e) e))
      (:results-dir config)
      "dhf/allure-results"))

;; ---------------------------------------------------------------------------
;; This rdm-spec, as a command line (RDM_SPEC)

(defn command-line
  "The command line that starts this same rdm-spec from its classpath, words
  separated by spaces."
  [bb entries]
  (str bb " -cp " (str/join ":" (map #(str (fs/absolutize %)) entries)) " -m rdm-spec.kernel.command"))

(defn this-command-line
  "The command line that starts this rdm-spec, however it was installed: from
  its sources or as a package, the classpath it runs with."
  []
  (let [bb (or (try (let [e (str (fs/read-link "/proc/self/exe"))] (when (str/ends-with? e "/bb") e)) (catch Exception _ nil)) "bb")]
    (command-line bb (remove str/blank? (str/split (str (classpath/get-classpath)) #":")))))

;; ---------------------------------------------------------------------------
;; The task table

(def check-stages
  "What check runs, in order: every stage reports."
  '[rdm-spec.knowledge.sources/stage
    rdm-spec.spec.parser/stage
    rdm-spec.spec.check/stage
    rdm-spec.architecture.model/stage
    rdm-spec.architecture.lint/stage
    rdm-spec.architecture.views/freshness-stage
    rdm-spec.knowledge.wiki/stage
    rdm-spec.compliance.gap/stage
    rdm-spec.regeneration.fence/paths-stage])

(defn- st [sym & {:as more}] (merge {:fn sym} more))

(declare run-stage help-stage gate-stage)

(def tasks
  "Every task: its usage, what it does, its options, its arguments and its stages."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :anywhere true :stages [(st `help-stage)]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :anywhere true :opts {"--id-prefix" :value} :args [1 1]
    :stages [(st 'rdm-spec.project.init/stage)]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :anywhere true :stages [(st 'rdm-spec.project.adopt/stage)]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :stages [(st 'rdm-spec.spec.stamper/stage)]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths, and the plugins that subscribe to what it reads"
    :opts {"--sources" :value} :subscribers true
    :stages (mapv st check-stages)}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :stages [(st 'rdm-spec.architecture.views/generate-stage)]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :stages (vec (concat [(st 'rdm-spec.regeneration.cut/precondition-stage)]
                         (map #(st % :gate true) check-stages)
                         [(st 'rdm-spec.regeneration.cut/stage)]))}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :stages [(st 'rdm-spec.regeneration.scopes/stage)]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :args [2 2] :opts {"--context" :value "--component" :value}
    :stages [(st `gate-stage :gate true) (st 'rdm-spec.regeneration.fence/stage)]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :opts {"--results" :value} :stages [(st `run-stage)]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :args [2 2]
    :stages [(st 'rdm-spec.verification.verify/prepare-stage) (st `gate-stage :gate true)
             (st 'rdm-spec.spec.parser/stage :quiet true) (st 'rdm-spec.verification.verify/stage)]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1]
    :stages [(st 'rdm-spec.measurement.checklist/prepare-stage) (st 'rdm-spec.spec.parser/stage)
             (st 'rdm-spec.compliance.gap/stage :quiet true) (st 'rdm-spec.measurement.checklist/stage)]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :opts {"--results" :value}
    :stages [(st 'rdm-spec.spec.parser/stage) (st 'rdm-spec.verification.status/stage)]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :opts {"--results" :value} :results-optional true
    :stages [(st 'rdm-spec.spec.parser/stage) (st 'rdm-spec.verification.status/stage)
             (st 'rdm-spec.verification.trace/stage)]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :opts {"--results" :value}
    :stages [(st 'rdm-spec.spec.parser/stage) (st 'rdm-spec.verification.status/stage)
             (st 'rdm-spec.compliance.gap/stage :quiet true) (st 'rdm-spec.verification.release/stage)]}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a verified cycle into its score.edn"
    :args [1 1] :opts {"--verdicts" :value} :stages [(st 'rdm-spec.measurement.scorer/stage)]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2] :stages [(st 'rdm-spec.measurement.scoreboard/stage)]}
   {:task "oracle" :usage "oracle [ARGS]" :does "run the oracle the project keeps: its plugin named \"oracle\""
    :passthrough true :stages [(st 'rdm-spec.measurement.oracle/stage)]}
   {:task "plugin" :usage "plugin add NAME" :does "copy a plugin template rdm-spec carries into dhf/harness/plugins/NAME"
    :args [2 2] :stages [(st 'rdm-spec.kernel.plugins/add-stage)]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :opts {"--results" :value} :results-optional true
    :stages [(st 'rdm-spec.knowledge.sources/stage :quiet true) (st 'rdm-spec.spec.parser/stage)
             (st 'rdm-spec.spec.check/stage :quiet true)
             (st 'rdm-spec.architecture.views/freshness-stage :gate true)
             (st 'rdm-spec.verification.status/stage) (st 'rdm-spec.publishing.site/stage)]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :opts {"--results" :value}
    :stages [(st 'rdm-spec.compliance.audit/precondition-stage)
             (st 'rdm-spec.compliance.audit/committed-stage :gate true)
             (st 'rdm-spec.spec.parser/stage) (st 'rdm-spec.verification.status/stage)
             (st 'rdm-spec.compliance.gap/stage :quiet true)
             (st 'rdm-spec.verification.release/stage :gate true)
             (st 'rdm-spec.compliance.audit/stage)]}
   {:task "observe" :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :args [1 4] :opts {"--tokens" :value} :stages [(st 'rdm-spec.agency.observe/stage)]}])

(defn task-of [name] (first (filter #(= name (:task %)) tasks)))

(defn parse-args
  "A task's words as {:args [...] :opts {:name value}}; --events is every
  task's flag. An option the task does not take cannot run."
  [task words]
  (if (:passthrough task)
    {:args (vec words) :opts {}}
    (let [known (merge {"--events" :flag} (:opts task))
          {:keys [args opts]}
          (loop [ws words args [] opts {}]
            (if-let [w (first ws)]
              (if (str/starts-with? w "--")
                (case (known w)
                  :flag (recur (rest ws) args (assoc opts (keyword (subs w 2)) true))
                  :value (if-let [v (second ws)]
                           (recur (drop 2 ws) args (assoc opts (keyword (subs w 2)) v))
                           (cannot-run! (str "the option " w " takes a value: usage: rdm-spec " (:usage task))))
                  (cannot-run! (str "rdm-spec " (:task task) " takes no option " w ": usage: rdm-spec " (:usage task))))
                (recur (rest ws) (conj args w) opts))
              {:args args :opts opts}))
          [lo hi] (or (:args task) [0 0])]
      (when-not (<= lo (count args) hi)
        (cannot-run! (str "rdm-spec " (:task task) " takes " (if (= lo hi) lo (str lo " to " hi))
                          " argument" (when-not (= 1 hi) "s") ", not " (count args) ": usage: rdm-spec " (:usage task))))
      {:args args :opts opts})))

;; ---------------------------------------------------------------------------
;; The command's own stages

(defn help-stage [_]
  {:result {:help (mapv #(select-keys % [:task :usage :does]) tasks)}})

(defn- plugins-fn [sym]
  (or (try (requiring-resolve (symbol "rdm-spec.kernel.plugins" (name sym))) (catch Exception _ nil))
      (cannot-run! "the plugins' code (rdm-spec.kernel.plugins) is not built: no plugin can run")))

(defn gate-stage
  "The plugins answering :Gate, asked before the task."
  [ctx]
  (if (seq (get-in ctx [:config :plugins]))
    ((plugins-fn 'gate-stage) ctx)
    {}))

(defn run-stage
  "rdm-spec run: the project's scenarios against its step definitions, the
  results into the results directory."
  [{:keys [root config] :as ctx}]
  (let [spec-dir (:spec-dir config "dhf/spec")
        files (runner/feature-files root spec-dir)]
    (when (empty? files) (cannot-run! (str "no scenario was found: " spec-dir " holds no feature file")))
    (let [dir (results-dir ctx)
          abs (fs/absolutize (fs/path root dir))]
      (fs/create-dirs abs)
      (let [{:keys [results counts refusals]}
            (binding [*out* *err*]
              (runner/load-steps! root (:steps-dir config "test/steps"))
              (runner/run-features {:files (map (fn [f] {:file f :text (slurp (fs/file root f))}) files)
                                    :results-dir (str abs) :parallel 1
                                    :owner (runner/model-owners root config)
                                    :needs-path (:needs config "dhf/spec/needs.edn")
                                    :id-prefix (:id-prefix config "spec")}))
            not-passed (remove #(or (= "passed" (get % "status"))
                                    (some #{{"name" "planned" "value" "true"}} (get % "labels")))
                               results)]
        {:findings (vec (concat refusals
                                (for [r not-passed]
                                  (finding (::runner/file r) (::runner/line r) (str "scenario-" (get r "status"))
                                           (str (or (::runner/id r) (get r "name")) " " (get r "status") ": "
                                                (get-in r ["statusDetails" "message"]))))))
         :result {:run {:results dir :counts counts}}}))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage [sym]
  (or (try (requiring-resolve sym) (catch Exception _ nil))
      (cannot-run! (str "the stage " sym " is not built: a stage skipped is a check that reads as a pass"))))

(defn read-config
  "The project's configuration, or a refusal: rdm-spec runs in a project's root."
  [root]
  (let [f (fs/file root "dhf/harness/config.edn")]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "no dhf/harness/config.edn in " root ": rdm-spec runs in a project's root")))
    (let [c (try (edn/read-string (slurp f))
                 (catch Exception e (cannot-run! (str "dhf/harness/config.edn cannot be read: " (one-line (ex-message e))))))]
      (when-not (map? c) (cannot-run! "dhf/harness/config.edn is not an EDN map"))
      c)))

(defn run-task
  "Runs a task's stages: {:result :findings :warnings :exit}."
  [task ctx]
  (bus/start!)
  (let [published (atom [])]
    (doseq [e (keys bus/catalogue)]
      (bus/subscribe! e :kernel/command (fn [payload] (swap! published conj [e payload]))))
    (let [ctx (cond-> ctx
                (and (not (:anywhere task)) (seq (get-in ctx [:config :plugins])))
                (as-> c (do ((plugins-fn 'validate!) (:config c)) c)))
          out (loop [stages (:stages task) ctx ctx acc {:result {} :findings [] :warnings []}]
                (if-let [s (first stages)]
                  (let [r (or ((resolve-stage (:fn s)) ctx) {})
                        fs' (vec (remove :warning? (:findings r)))
                        acc (cond-> (update acc :result merge (:result r))
                              (not (:quiet s)) (-> (update :findings into fs')
                                                   (update :warnings into (:warnings r))))
                        ctx (merge ctx (:ctx r))]
                    (cond
                      (:stop r) (assoc acc :exit (:exit r 0) :stopped true :raw (:raw r))
                      (and (:gate s) (seq fs')) (assoc acc :stopped true)
                      :else (recur (rest stages) ctx acc)))
                  (assoc acc :ctx ctx)))]
      (if (and (:subscribers task) (not (:stopped out)) (seq (get-in ctx [:config :plugins])))
        (let [r ((plugins-fn 'subscribers-stage) (assoc (:ctx out) :published @published))]
          (-> out
              (update :result merge (:result r))
              (update :findings into (:findings r))
              (update :warnings into (:warnings r))))
        out))))

(defn- print-result [result]
  (binding [*print-namespace-maps* false]
    (pp/pprint (or result {}))))

(defn main
  "Runs rdm-spec with its command-line words in root; the exit status."
  [root words]
  (let [[name & more] words
        name (or name "help")
        task (task-of name)
        events? (some #{"--events"} more)]
    (try
      (when-not task
        (cannot-run! (str "rdm-spec has no task " name "; the tasks are " (str/join ", " (map :task tasks)))))
      (let [{:keys [args opts]} (parse-args task more)
            config (if (:anywhere task)
                     (try (read-config root) (catch Exception _ {}))
                     (read-config root))
            ctx {:root root :config config :args args :opts opts :task name
                 :env (into {} (System/getenv)) :revision nil
                 :results-optional (:results-optional task)}
            {:keys [result findings warnings exit raw]} (run-task task ctx)
            exit (or exit (if (seq findings) 1 0))]
        (when-not raw (print-result result))
        (flush)
        (binding [*out* *err*]
          (doseq [l (distinct (map finding-line findings))] (println l))
          (doseq [l (distinct (map warning-line warnings))] (println l))
          (when (and events? (not (:passthrough task)))
            (doseq [[e pub] (bus/published)]
              (println (str "event: " (clojure.core/name e) " published-by " (subs (str pub) 1))))))
        exit)
      (catch clojure.lang.ExceptionInfo e
        (if-let [why (:cannot-run (ex-data e))]
          (do (binding [*out* *err*] (println (str "cannot-run: " (one-line why)))) 2)
          (throw e))))))

(defn -main [& words]
  (flush)
  (let [code (main (str (fs/normalize (fs/absolutize "."))) words)]
    (flush)
    (binding [*out* *err*] (flush))
    (System/exit code)))
