(ns rdm-spec.measurement.oracle
  "The Oracle component of the measurement context
  (dhf/spec/measurement/oracle.feature; glossary, \"Oracle (instrument)\").
  The oracle itself is not rdm-spec code: it is a command the project keeps
  by hand, outside its implementation, named by the configuration's
  `:oracle {:command [...]}` (rdm-spec's own is
  dhf/harness/oracle/oracle.clj). This component only runs it.

  `oracle` (the task `rdm-spec oracle [ARGS]`) runs the command in the
  project's root with ARGS after it and RDM_SPEC set to a command line
  running this same rdm-spec (kernel.command/self-command), relays its
  standard output and error stream unchanged, and exits as it exits. No
  :oracle is no oracle, said on standard output only, exit 0. A command
  that cannot be started cannot run (exit 2), naming it.

  `gate` (the first stage of fence and verify, marked a gate in the task
  table): runs the command with no argument; exit 0 passes and adds
  nothing; exit 1 is a finding per line of its error stream, relayed
  unchanged, which stops the task; any other exit cannot run. No :oracle
  passes."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def no-oracle "there is no oracle to run: the configuration names no :oracle")

(defn command-line
  "The command the configuration `cfg` names for the oracle, then `args`."
  [cfg args]
  (let [c (get-in cfg [:oracle :command])]
    (when-not (and (sequential? c) (seq c) (every? string? c))
      (command/cannot-run! (str ":oracle names no :command, a vector of strings: " (pr-str (:oracle cfg)))))
    (vec (concat c (map str args)))))

(defn- run! [req args]
  (let [cmd (command-line (:config req) args)]
    (try
      (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""
                      :extra-env (merge (:env req) {"RDM_SPEC" (command/self-command)})}
             cmd)
      (catch java.io.IOException e
        (command/cannot-run! (str "the oracle command " (str/join " " (get-in req [:config :oracle :command]))
                                  " cannot be started: " (ex-message e)))))))

(defn oracle
  "The oracle task's stage: the command's answer, relayed."
  [req]
  (if-not (contains? (:config req) :oracle)
    {:relay {:out (str (pr-str {:oracle {:says no-oracle}}) "\n") :err "" :exit 0}}
    (let [r (run! req (:args req))]
      {:relay {:out (:out r) :err (:err r) :exit (:exit r)}})))

(defn gate
  "The gate before fence and verify: the oracle passes, or the task stops."
  [req]
  (if-not (contains? (:config req) :oracle)
    {:result {} :findings []}
    (let [r (run! req [])
          nm (str/join " " (get-in req [:config :oracle :command]))]
      (case (int (:exit r))
        0 {:result {} :findings []}
        1 {:result {}
           :findings (let [ls (remove str/blank? (str/split-lines (str (:err r))))]
                       (if (seq ls)
                         (mapv (fn [l] {:raw l :severity :error}) ls)
                         [(command/finding "oracle-failed" (str "the oracle " nm " exited 1 and named no finding"))]))}
        (command/cannot-run! (str "the oracle " nm " cannot run (it exited " (:exit r) "): "
                                  (str/trim (str/replace (str (:err r)) #"\s*\n\s*" " "))))))))
