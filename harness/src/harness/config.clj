(ns harness.config
  "Everything the harness knows about a project (DI-100), from one EDN file,
  `harness/config.edn` at the project's root. Each setting:

    :suite                the one command that runs the project's whole suite
                          (run by `sh -c` from the root; only its exit status counts)
    :port                 where the served graph is started (default 8901)
    :boot-budget-seconds  how long the served graph may take to answer (default 10)
    :poll-seconds         how often it is asked meanwhile (default 0.5)
    :known-answers        the instrument's known answers: each {:record path,
                          :gate :design or :release, :verdict :pass or :fail},
                          with :results for the release gate
    :rdm                  how RDM is run, as a command vector (default [\"rdm\"])
    :dhf                  the record (default \"dhf\")

  A setting the harness does not know is refused by name, so a misspelt one is
  never silently ignored."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [harness.events :refer [event]]))

(def file "harness/config.edn")

(def defaults {:port 8901 :boot-budget-seconds 10 :poll-seconds 0.5 :known-answers []
               :rdm ["rdm"] :dhf "dhf"})

(def known (conj (set (keys defaults)) :suite))

(def unknown-setting "Round Not Admitted / Unknown Setting")

(defn read-config
  "The settings, defaults under what the file declares, and the events of
  reading them."
  [root]
  (let [path (fs/path root file)
        declared (if (fs/exists? path) (edn/read-string (slurp (str path))) {})
        unknown (sort (remove known (keys declared)))]
    {:settings (merge defaults declared)
     :events (vec (for [k unknown]
                    (event unknown-setting (str file ": " k " is not a setting the harness knows ("
                                                (clojure.string/join ", " (sort known)) ")"))))}))

(defn print-settings
  "Each setting and its value, on standard output."
  [settings]
  (doseq [k (sort (keys settings))]
    (println (str k " " (pr-str (settings k)))))
  (flush))
