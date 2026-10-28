(ns rdm-spec.measurement.oracle
  "The oracle task: runs the plugin named \"oracle\" the project keeps, in its
  root, with its own arguments and RDM_SPEC set, relays what it says
  unchanged, and exits as it exits (dhf/spec/08-plugins/oracle.feature). As a
  plugin answering :Gate, it stops fence and verify while it fails."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.plugins :as plugins]
            [rdm-spec.kernel.project :as project]))

(defn the-oracle [cfg] (some #(when (= "oracle" (:name %)) %) (:plugins cfg)))

(defn oracle [run]
  (plugins/validate! run)
  (if-let [o (the-oracle (:cfg run))]
    (let [r (try
              @(p/process (into (vec (:command o)) (:args run))
                          {:dir (:root run) :in "" :out :string :err :string
                           :extra-env (merge (:env o) {"RDM_SPEC" (plugins/rdm-spec-command)})})
              (catch Exception e
                (project/cannot-run! (str "the oracle's command " (pr-str (:command o)) " cannot be started: " (project/one-line (ex-message e))))))]
      {:raw true :exit (:exit r) :raw-out (:out r) :raw-err (:err r)})
    {:result {:oracle {:says "there is no oracle to run: no plugin is named \"oracle\""}}}))
