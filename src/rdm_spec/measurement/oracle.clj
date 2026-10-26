(ns rdm-spec.measurement.oracle
  "The oracle (dhf/spec/measurement/oracle.feature): runs the command the
  project keeps as its oracle (the configuration's :oracle), in the project's
  root, telling it which rdm-spec it judges, and relays its answer. As a gate
  of fence and verify, it stops them while it fails. The oracle itself is not
  this component: it is held still, outside the implementation."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

(defn- run [{:keys [root config]} args]
  (let [command (get-in config [:oracle :command])
        r (apply cmd/sh {:dir root :env {"RDM_SPEC" (str/join " " (cmd/self-command))}} (concat command args))]
    (when (:not-found r)
      (cmd/cannot-run! (str "the oracle command " (pr-str command) " cannot be started: " (first command) " was not found")))
    r))

(defn stage
  "rdm-spec oracle [ARGS]: the oracle's own output and exit, unchanged."
  [{:keys [config args] :as ctx}]
  (if (empty? (get-in config [:oracle :command]))
    {:result {:oracle {:command nil :note "the configuration names no :oracle: there is no oracle to run"}}}
    {:raw (run ctx args)}))

(defn gate-stage
  "The gate before fence and verify: nothing runs after it while it fails."
  [{:keys [config] :as ctx}]
  (if (empty? (get-in config [:oracle :command]))
    {}
    (let [{:keys [exit err]} (run ctx [])]
      (if (= 0 exit)
        {}
        {:stop true :exit (if (= 2 exit) 2 1) :err-raw err}))))
