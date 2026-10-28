(ns rdm-spec.kernel.command
  "The rdm-spec command: one entry point, the task table, and how every task
  reports and exits (dhf/spec/01-start-a-project/command.feature). It belongs
  to no bounded context: it dispatches each task to the stages that own it, in
  order. Every stage reports, so one run names every finding; a gate stops the
  task when it has findings. A stage whose code is not built refuses the task."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]))

;; ------------------------------------------------------------------ the task table

(def tasks
  [{:task "help" :usage "help" :does "list every task with its usage"}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths, and the plugins that subscribe to what it reads"}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"}
   {:task "release" :usage "release [--results DIR]" :does "the release check"}
   {:task "score" :usage "score CYCLE --verdicts FILE [--verdicts FILE ...]" :does "file the graders' verdicts on a verified cycle into its score.edn"}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"}
   {:task "oracle" :usage "oracle [ARGS]" :does "run the oracle the project keeps: its plugin named \"oracle\""}
   {:task "plugin" :usage "plugin add NAME" :does "copy a plugin template rdm-spec carries into dhf/harness/plugins/NAME"}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"}
   {:task "audit" :usage "audit OUT [--results DIR]" :does "write the auditor's and the engineer's copy, with their evidence, to OUT"}
   {:task "observe" :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"}])

;; Flags each task accepts: true takes a value, :many takes several, false none.
(def flags
  {"help" {} "init" {"--id-prefix" true} "adopt" {} "stamp" {}
   "check" {"--sources" true} "views" {} "cut" {} "scopes" {}
   "fence" {"--context" true "--component" true "--slice" true}
   "run" {"--results" true} "verify" {} "checklist" {}
   "status" {"--results" true "--spec" true "--slice" true}
   "trace" {"--results" true "--spec" true}
   "release" {"--results" true "--spec" true "--slice" true}
   "score" {"--verdicts" :many} "scoreboard" {} "oracle" :passthrough "plugin" {}
   "publish" {"--results" true} "audit" {"--results" true} "observe" {"--tokens" true}})

(def positional
  {"help" [0 0] "init" [1 1] "adopt" [0 0] "stamp" [0 0] "check" [0 0] "views" [0 0] "cut" [0 0]
   "scopes" [2 2] "fence" [2 2] "run" [0 0] "verify" [2 2] "checklist" [1 1] "status" [0 0] "trace" [1 1]
   "release" [0 0] "score" [1 1] "scoreboard" [2 2] "plugin" [2 2] "publish" [0 0] "audit" [1 1] "observe" [1 4]})

(defn usage-of [task] (:usage (some #(when (= task (:task %)) %) tasks)))

(defn parse-args
  "Splits a task's arguments into positional ones and flags; cannot run on an unknown one."
  [task args]
  (let [fl (flags task)]
    (if (= fl :passthrough)
      {:args (vec args) :opts {}}
      (loop [as args pos [] opts {}]
        (if (empty? as)
          (let [[lo hi] (positional task)]
            (when-not (<= lo (count pos) hi)
              (project/cannot-run! (str task " takes " (if (= lo hi) lo (str lo " to " hi)) " argument" (when (not= 1 hi) "s")
                                        ", not " (count pos) ": rdm-spec " (usage-of task))))
            {:args pos :opts opts})
          (let [[a & more] as]
            (cond
              (and (str/starts-with? a "--") (contains? fl a))
              (if (nil? (seq more))
                (project/cannot-run! (str a " takes a value: rdm-spec " (usage-of task)))
                (recur (rest more) pos (if (= :many (fl a)) (update opts a (fnil conj []) (first more)) (assoc opts a (first more)))))
              (str/starts-with? a "--")
              (project/cannot-run! (str "unknown argument " a " to " task ": rdm-spec " (usage-of task)))
              :else (recur more (conj pos a) opts))))))))

;; ------------------------------------------------------------------ stages

(defn stage
  "Resolves a stage's function; a stage whose code is not built refuses the task."
  [sym]
  (let [f (try (requiring-resolve sym) (catch Throwable e (project/cannot-run! (str "the stage " sym " is not built: " (project/one-line (ex-message e))))))]
    (or f (project/cannot-run! (str "the stage " sym " is not built")))))

(defn call [sym & args] (apply (stage sym) args))

(defn results-dir
  "A task's results directory: --results, else RESULTS_DIR, else the configured one."
  [run]
  (or (get-in run [:opts "--results"])
      (not-empty (System/getenv "RESULTS_DIR"))
      (get-in run [:cfg :results-dir])))

(defn results-given?
  "Whether a results directory was asked for (by --results or RESULTS_DIR)."
  [run]
  (boolean (or (get-in run [:opts "--results"]) (not-empty (System/getenv "RESULTS_DIR")))))

(defn merge-report
  "Folds a stage's report {:findings :warnings :result :exit} into the run."
  [run {:keys [findings warnings result exit] :as rep}]
  (cond-> (merge run (dissoc rep :findings :warnings :result :exit))
    (seq findings) (update :findings (fnil into []) findings)
    (seq warnings) (update :warnings (fnil into []) warnings)
    result (update :result merge result)
    exit (update :exit (fnil max 0) exit)))

(defn blocking? [run] (seq (remove :warning? (:findings run))))

;; ------------------------------------------------------------------ tasks

(defn help [_] {:result {:help (mapv #(select-keys % [:task :usage :does]) tasks)}})

(defn check-stages
  "The stages of the check, in order; each reports. Returns the run."
  [run]
  (let [cfg (:cfg run)
        regulated? (contains? cfg :regulated)]
    (-> run
        (merge-report (call 'rdm-spec.knowledge.sources/index run))
        (as-> r (merge-report r (call 'rdm-spec.spec.parser/parse r)))
        (as-> r (merge-report r (call 'rdm-spec.spec.check/check r)))
        (as-> r (merge-report r (call 'rdm-spec.architecture.lint/lint r)))
        (as-> r (merge-report r (call 'rdm-spec.architecture.views/freshness r)))
        (as-> r (merge-report r (call 'rdm-spec.knowledge.wiki/check r)))
        (as-> r (if regulated? (merge-report r (call 'rdm-spec.compliance.gap/index r)) r))
        (as-> r (merge-report r (call 'rdm-spec.regeneration.fence/paths-check r))))))

(defn check [run]
  (call 'rdm-spec.kernel.plugins/validate! run)
  (call 'rdm-spec.spec.parser/validate-specs! (:root run) (:cfg run))
  (check-stages run))

(defn gate!
  "Asks every plugin answering :Gate; a failing gate stops the task."
  [run request]
  (call 'rdm-spec.kernel.plugins/validate! run)
  (merge-report run (call 'rdm-spec.kernel.plugins/gate run request)))

(defn dispatch [task run]
  (case task
    "help" (help run)
    "init" (call 'rdm-spec.project.init/init run)
    "adopt" (call 'rdm-spec.project.adopt/adopt run)
    "stamp" (do (call 'rdm-spec.spec.parser/validate-specs! (:root run) (:cfg run))
                (call 'rdm-spec.spec.stamper/stamp run))
    "check" (check run)
    "views" (call 'rdm-spec.architecture.views/generate run)
    "cut" (let [c (check run)]
            (if (blocking? c)
              (assoc c :exit 1 :result {:cut {:tagged nil :says "the spec fails the check: nothing is cut"}})
              (merge-report (select-keys c [:warnings]) (call 'rdm-spec.regeneration.cut/cut run))))
    "scopes" (call 'rdm-spec.regeneration.scopes/scopes run)
    "fence" (let [g (gate! run {:request :Gate :task :fence :cut (second (:args run))})]
              (if (or (blocking? g) (:exit g)) g (merge-report g (call 'rdm-spec.regeneration.fence/fence g))))
    "run" (call 'rdm-spec.kernel.runner/run-task run)
    "verify" (call 'rdm-spec.verification.verify/verify run gate!)
    "checklist" (call 'rdm-spec.measurement.checklist/checklist run)
    "status" (call 'rdm-spec.verification.status/status-task run)
    "trace" (call 'rdm-spec.verification.trace/trace run)
    "release" (call 'rdm-spec.verification.release/release-task run)
    "score" (call 'rdm-spec.measurement.scorer/score run)
    "scoreboard" (call 'rdm-spec.measurement.scoreboard/scoreboard run)
    "oracle" (call 'rdm-spec.measurement.oracle/oracle run)
    "plugin" (call 'rdm-spec.kernel.plugins/plugin-task run)
    "publish" (call 'rdm-spec.publishing.site/publish run)
    "audit" (call 'rdm-spec.compliance.audit/audit run)
    "observe" (call 'rdm-spec.agency.observe/observe run)))

(def no-project #{"help" "init" "adopt"})

(defn execute
  "Runs a task; returns {:exit :out :err} without exiting."
  [argv]
  (let [[task & args] argv
        events? (some #{"--events"} args)
        args (remove #{"--events"} args)]
    (bus/with-bus
      (try
        (when-not (some #(= task (:task %)) tasks)
          (project/cannot-run! (str "unknown task " (if task task "(none)") ": the tasks are "
                                    (str/join ", " (map :task tasks)) "; rdm-spec help lists each with its usage")))
        (let [{pos :args opts :opts} (parse-args task args)
              root (str (fs/absolutize (fs/cwd)))
              cfg (when-not (no-project task) (project/read-config root))
              base {:task task :root root :cfg cfg :args pos :opts opts :events? events? :findings [] :warnings []}
              out (dispatch task base)
              run (if (contains? out :cfg) out (merge-report base out))
              run (if (and (not (:raw run)) (not (no-project task)) (not= 2 (:exit run)))
                    (merge-report run (call 'rdm-spec.kernel.plugins/deliver run (bus/published)))
                    run)
              findings (remove :warning? (:findings run))
              warnings (concat (filter :warning? (:findings run)) (:warnings run))
              exit (max (or (:exit run) 0) (if (seq findings) 1 0))
              err (concat (map project/finding-line findings)
                          (map #(project/finding-line (assoc % :warning? true)) warnings)
                          (when events? (map bus/event-line (bus/published))))]
          (if (:raw run)
            {:exit (:exit run) :out (:raw-out run) :err (:raw-err run)}
            (if (= exit 2)
              {:exit 2 :out "" :err (str/join (map #(str % "\n") (concat err (when-let [w (:why run)] [(str "cannot-run: " w)]))))}
              {:exit exit
               :out (str (project/edn-str (or (:result run) {})) "\n")
               :err (str/join (map #(str % "\n") err))})))
        (catch clojure.lang.ExceptionInfo e
          (if (= 2 (:rdm-spec/exit (ex-data e)))
            {:exit 2 :out "" :err (str (str/join (map #(str (project/finding-line %) "\n") (:findings (ex-data e))))
                                       "cannot-run: " (project/one-line (ex-message e)) "\n"
                                       (when events? (str/join (map #(str (bus/event-line %) "\n") (bus/published)))))}
            {:exit 2 :out "" :err (str "cannot-run: " (project/one-line (ex-message e)) "\n")}))
        (catch Throwable e
          {:exit 2 :out "" :err (str "cannot-run: " (project/one-line (str (.getName (class e)) ": " (ex-message e))) "\n")})))))

(defn -main [& argv]
  (let [{:keys [exit out err]} (execute argv)]
    (print out) (flush)
    (binding [*out* *err*] (print err) (flush))
    (shutdown-agents)
    (System/exit (or exit 2))))
