(ns rdm-spec.measurement.oracle
  "Component Oracle (measurement context): the oracle task, the shorthand
  for running the oracle the project keeps, its plugin named \"oracle\". It
  runs the command in the project's root with the task's arguments and
  RDM_SPEC naming this rdm-spec, relays what it prints unchanged, and exits
  as it exits. The gate itself is the plugins' (:Gate)."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.plugins :as plugins]))

(defn oracle-task [{:keys [cfg args]}]
  (plugins/validate! cfg)
  (if-let [o (first (filter #(= "oracle" (:name %)) (plugins/declared cfg)))]
    (let [env (merge (project/env) (:env o) {"RDM_SPEC" (plugins/rdm-spec-command-line)})
          argv (into (vec (:command o)) args)
          r (try @(p/process argv {:dir (project/root) :env env :in "" :out :string :err :string})
                 (catch java.io.IOException e
                   (project/cannot-run! (str "the oracle's command " (pr-str (:command o)) " cannot be started: " (project/one-line (ex-message e))))))]
      {:raw-out (:out r) :raw-err (:err r) :exit (:exit r) :findings [] :warnings []})
    (project/report {:oracle {:says "there is no oracle to run: the configuration's :plugins names no plugin \"oracle\""}})))
