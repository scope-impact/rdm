(ns harness.main
  "`bb harness <command>`: the harness's command line, run from a project's
  root (dhf/documents/design/harness.md)."
  (:require [babashka.fs :as fs]
            [harness.checklist :as checklist]
            [harness.config :as config]
            [harness.events :as events]
            [harness.score :as score]
            [harness.scoreboard :as scoreboard]
            [harness.stamp :as stamp]
            [harness.verify :as verify]))

(def commands {"stamp" stamp/run
               "checklist" checklist/run
               "score" score/run
               "scoreboard" scoreboard/run
               "verify" verify/run})

(defn -main [& [command & args]]
  (let [root (str (fs/cwd))
        {:keys [settings events]} (config/read-config root)
        dhf (str (fs/path root (:dhf settings)))]
    (if-let [run (commands command)]
      (events/conclude! (if (seq events) events (run root dhf args)))
      (do (binding [*out* *err*]
            (println "usage: bb harness <stamp | checklist | score NN | scoreboard NN | verify> [options]"))
          (System/exit 2)))))
