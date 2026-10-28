(ns rdm-spec.kernel.command
  "Kernel component Command: the one entry point, rdm-spec <task>. Holds the
  task table, dispatches each task to the stages of the contexts that own it,
  and reports every task the same way: the result as EDN on standard output,
  one line per finding then per warning on the error stream, with --events
  each event published, exit 0 a pass, 1 a finding, 2 a task that cannot run.

  A stage whose code is not built refuses the task (exit 2): a stage skipped
  is a check that reads as a pass."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]))

;; ------------------------------------------------------------------ the task table

(def tasks
  [{:task "help" :usage "help" :does "list every task with its usage" :project? false
    :run 'rdm-spec.kernel.command/help-task}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist" :project? false
    :run 'rdm-spec.project.init/init-task :flags {"--id-prefix" :value} :positional [1 1]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing" :project? false
    :run 'rdm-spec.project.adopt/adopt-task :positional [0 0]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :run 'rdm-spec.spec.stamper/stamp-task :positional [0 0]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths, and the plugins that subscribe to what it reads"
    :run 'rdm-spec.kernel.command/check-task :flags {"--sources" :value} :positional [0 0]}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :run 'rdm-spec.architecture.views/views-task :positional [0 0]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :run 'rdm-spec.regeneration.cut/cut-task :positional [0 0]}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :run 'rdm-spec.regeneration.scopes/scopes-task :positional [2 2]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :run 'rdm-spec.regeneration.fence/fence-task :flags {"--context" :value "--component" :value "--slice" :value}
    :positional [2 2]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :run 'rdm-spec.kernel.command/run-task :flags {"--results" :value} :positional [0 0]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :run 'rdm-spec.verification.verify/verify-task :positional [2 2]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :run 'rdm-spec.measurement.checklist/checklist-task :positional [1 1]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :run 'rdm-spec.verification.status/status-task :flags {"--results" :value "--spec" :value} :positional [0 0]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :run 'rdm-spec.verification.trace/trace-task :flags {"--results" :value "--spec" :value} :positional [1 1]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :run 'rdm-spec.verification.release/release-task :flags {"--results" :value "--spec" :value} :positional [0 0]}
   {:task "score" :usage "score CYCLE --verdicts FILE [--verdicts FILE ...]"
    :does "file the graders' verdicts on a verified cycle into its score.edn"
    :run 'rdm-spec.measurement.scorer/score-task :flags {"--verdicts" :multi} :positional [1 1]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :run 'rdm-spec.measurement.scoreboard/scoreboard-task :positional [2 2]}
   {:task "oracle" :usage "oracle [ARGS]" :does "run the oracle the project keeps: its plugin named \"oracle\""
    :run 'rdm-spec.measurement.oracle/oracle-task :raw-args? true}
   {:task "plugin" :usage "plugin add NAME" :does "copy a plugin template rdm-spec carries into dhf/harness/plugins/NAME"
    :run 'rdm-spec.kernel.plugins/plugin-task :positional [2 2]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :run 'rdm-spec.kernel.command/publish-task :flags {"--results" :value} :positional [0 0]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :run 'rdm-spec.compliance.audit/audit-task :flags {"--results" :value} :positional [1 1]}
   {:task "observe"
    :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :run 'rdm-spec.agency.observe/observe-task :flags {"--tokens" :value} :positional [3 4]}])

(defn task-names [] (mapv :task tasks))

(defn stage
  "The function of a stage, by its symbol; a stage whose code is not built
  refuses the task."
  [sym]
  (or (try (requiring-resolve sym) (catch Exception _ nil))
      (project/cannot-run! (str "the stage " sym " is not built, and a stage skipped is a check that reads as a pass"))))

;; ------------------------------------------------------------------ arguments

(defn parse-args
  "Splits ARGS by the task's flags: {:positional [...] :opts {flag value}}."
  [{:keys [flags positional task]} args]
  (loop [as args pos [] opts {}]
    (if (empty? as)
      (let [[lo hi] (or positional [0 0])]
        (when (or (< (count pos) lo) (> (count pos) hi))
          (project/cannot-run! (str task " takes " (if (= lo hi) lo (str lo " to " hi)) " argument" (when-not (= 1 hi) "s")
                                    ", not " (count pos) ": rdm-spec "
                                    (:usage (first (filter #(= task (:task %)) tasks))))))
        {:positional pos :opts opts})
      (let [a (first as)]
        (if (str/starts-with? a "--")
          (let [kind (get flags a)]
            (case kind
              :value (if (next as) (recur (nnext as) pos (assoc opts a (second as)))
                         (project/cannot-run! (str a " needs a value")))
              :multi (if (next as) (recur (nnext as) pos (update opts a (fnil conj []) (second as)))
                         (project/cannot-run! (str a " needs a value")))
              (project/cannot-run! (str "unknown argument " a " to " task))))
          (recur (rest as) (conj pos a) opts))))))

;; ------------------------------------------------------------------ running stages

(defn merge-reports [& rs]
  {:result (apply merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b)) (map :result rs))
   :findings (vec (mapcat :findings rs))
   :warnings (vec (mapcat :warnings rs))})

(defn run-stages
  "Runs each stage in order, every one reporting; a gate (:gate? true) with
  findings stops the stages after it."
  [run stages]
  (loop [ss stages acc (merge-reports)]
    (if (empty? ss)
      acc
      (let [{:keys [sym gate?]} (first ss)
            r ((stage sym) run)
            acc (merge-reports acc r)]
        (if (and gate? (seq (:findings r)))
          acc
          (recur (rest ss) acc))))))

(defn subscribers-report
  "Runs the plugins subscribing to the events this run published."
  [cfg]
  ((stage 'rdm-spec.kernel.plugins/run-subscribers!) cfg (bus/published)))

;; ------------------------------------------------------------------ tasks the kernel composes

(defn help-task [_]
  (project/report {:help (mapv #(select-keys % [:task :usage :does]) tasks)}))

(def check-stages
  [{:sym 'rdm-spec.knowledge.sources/index-stage}
   {:sym 'rdm-spec.knowledge.wiki/check-stage}
   {:sym 'rdm-spec.spec.parser/parse-stage}
   {:sym 'rdm-spec.spec.check/check-stage}
   {:sym 'rdm-spec.architecture.lint/check-stage}
   {:sym 'rdm-spec.architecture.views/freshness-stage}
   {:sym 'rdm-spec.regeneration.fence/paths-stage}
   {:sym 'rdm-spec.compliance.gap/check-stage}])

(defn check-task
  "The spec check, the model lint, the views' freshness, sources, wiki,
  clauses, paths, then the plugins subscribing to what it read."
  [{:keys [cfg] :as run}]
  ((stage 'rdm-spec.kernel.plugins/validate!) cfg)
  (project/validate-specs! (project/root) cfg)
  (let [r (run-stages run check-stages)]
    (merge-reports r (subscribers-report cfg))))

(defn publish-task
  "The views' freshness as a gate, then the site."
  [{:keys [cfg] :as run}]
  ((stage 'rdm-spec.kernel.plugins/validate!) cfg)
  (let [r (run-stages run [{:sym 'rdm-spec.architecture.views/freshness-stage :gate? true}
                           {:sym 'rdm-spec.knowledge.sources/publish-stage}
                           {:sym 'rdm-spec.spec.parser/parse-stage}
                           {:sym 'rdm-spec.verification.status/derive-stage}
                           {:sym 'rdm-spec.publishing.site/publish-stage}])]
    (merge-reports r (subscribers-report cfg))))

(defn run-task
  "Runs the project's scenarios against its step definitions."
  [{:keys [cfg opts]}]
  (let [rd (project/results-dir cfg opts)
        runner (stage 'rdm-spec.kernel.runner/run-project)
        read ((stage 'rdm-spec.spec.parser/read-specs) (project/root) cfg)]
    (when-not (seq (mapcat :scenarios read))
      (project/cannot-run! (str "no scenario was found under " (str/join ", " (map :dir (project/specs cfg)))
                                ": a run that finds nothing is refused")))
    (let [counts (runner {:root (project/root) :steps-dir (project/abs (:steps-dir cfg)) :results-dir (:abs rd)})]
      (project/report {:run (assoc (dissoc counts :total) :scenarios (:total counts) :results (:dir rd))}
                      (when (pos? (+ (:failed counts 0) (:broken counts 0)))
                        [(project/finding (:dir rd) "scenarios-not-passed"
                                          (str (:failed counts 0) " failed and " (:broken counts 0) " broken"))])))))

;; ------------------------------------------------------------------ reporting

(defn print-report!
  "Prints a report the one way every task reports; returns the exit."
  [{:keys [result findings warnings exit raw-out raw-err]} events?]
  (if raw-out
    (print raw-out)
    (println (pr-str (or result {}))))
  (binding [*out* *err*]
    (when raw-err (print raw-err))
    (doseq [f findings] (println (project/finding-line f)))
    (doseq [w warnings] (println (project/finding-line (assoc w :warning true))))
    (when events? (doseq [e (bus/published)] (println (bus/event-line e))))
    (flush))
  (flush)
  (or exit (if (seq findings) 1 0)))

(defn execute
  "Runs one task; prints its report; returns its exit."
  [args]
  (binding [bus/*bus* (bus/new-bus) project/*cache* (atom {})]
    (let [events? (some #{"--events"} args)
          [name & more] (remove #{"--events"} args)
          t (first (filter #(= name (:task %)) tasks))]
      (try
        (when-not t
          (project/cannot-run! (str "unknown task " (or name "(none)") ": the tasks are " (str/join ", " (task-names))
                                    " (rdm-spec help lists each with its usage)")))
        (let [cfg (when-not (false? (:project? t)) (project/config))
              {:keys [positional opts]} (if (:raw-args? t) {:positional (vec more) :opts {}} (parse-args t more))
              r ((stage (:run t)) {:cfg cfg :args positional :opts opts :task name})]
          (print-report! r events?))
        (catch clojure.lang.ExceptionInfo e
          (if (project/cannot-run? e)
            (do (binding [*out* *err*]
                  (doseq [f (:findings (ex-data e))] (println (project/finding-line f)))
                  (println (str "cannot-run: " (project/one-line (ex-message e))))
                  (when events? (doseq [ev (bus/published)] (println (bus/event-line ev))))
                  (flush))
                2)
            (do (binding [*out* *err*] (println (str "cannot-run: an error in rdm-spec: " (project/one-line (ex-message e)))) (flush))
                2)))
        (catch Throwable e
          (binding [*out* *err*]
            (println (str "cannot-run: an error in rdm-spec: " (project/one-line (or (ex-message e) (str (class e))))))
            (flush))
          2)))))

(defn run
  "Runs rdm-spec in process: {:dir :env :args} to {:exit :out :err}."
  [{:keys [dir env args]}]
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)
        exit (binding [*out* out *err* err
                       project/*root* (str (fs/absolutize dir))
                       project/*env* env]
               (execute args))]
    {:exit exit :out (str out) :err (str err)}))

(defn -main [& args]
  (let [exit (binding [project/*root* (str (fs/cwd))] (execute (vec args)))]
    (shutdown-agents)
    (System/exit exit)))
