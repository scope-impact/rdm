(ns rdm-spec.measurement.oracle
  "The oracle task (dhf/spec/measurement/oracle.feature): the shorthand that
  runs the oracle the project keeps, its plugin named \"oracle\" (which
  answers :Gate, so fence and verify stop while it fails; kernel/plugins asks
  it there). Runs its command in the project's root with the task's own
  arguments and RDM_SPEC set, its output passed through unchanged, and exits
  as it exits. No plugin named \"oracle\" is no oracle, said on standard output."
  (:require [babashka.process :as p]
            [rdm-spec.kernel.command :as cmd]))

(defn oracle-of
  "The plugin named \"oracle\", or nil."
  [config]
  (first (filter #(= "oracle" (:name %)) (:plugins config))))

(defn command-of
  "The oracle's command line with the task's arguments after it."
  [oracle args]
  (into (vec (:command oracle)) args))

(defn stage [{:keys [root config args]}]
  (if-let [o (oracle-of config)]
    (let [cmdline (command-of o args)
          r (try
              @(p/process cmdline {:dir (str root) :in :inherit :out :inherit :err :inherit
                                   :extra-env {"RDM_SPEC" (cmd/this-command-line)}})
              (catch java.io.IOException _
                (cmd/cannot-run! (str "the oracle's command " (pr-str (:command o)) " cannot be started: it was not found"))))]
      {:stop true :raw true :exit (:exit r)})
    {:result {:oracle {:oracle nil
                       :note "there is no oracle to run: the configuration's :plugins names no plugin \"oracle\""}}}))
