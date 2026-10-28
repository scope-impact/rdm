(ns steps.measurement-oracle
  "Steps of the component Oracle (dhf/spec/08-plugins/oracle.feature). The
  scenario's oracle is a small command of its own, never the room's."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn oracle! [ctx body & [{:keys [command]}]]
  (let [ctx (if (:dir ctx) ctx (k/spec-project! ctx))]
    (k/write! ctx "dhf/harness/oracle/oracle.sh" (str "#!/bin/sh\n" body "\n"))
    (k/config! ctx assoc :plugins [{:name "oracle" :answers :Gate :command (or command ["sh" "dhf/harness/oracle/oracle.sh"])}])
    ctx))

(defstep #"a configuration whose :plugins holds \{:name \"oracle\" :answers :Gate :command \[\"bb\" \"dhf/harness/oracle/oracle.clj\"\]\}, a command that prints \{:oracle \{:differences 0\}\} and exits 0"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (k/write! ctx "dhf/harness/oracle/oracle.clj"
                "(spit \"oracle.pwd\" (System/getProperty \"user.dir\"))\n(println (pr-str {:oracle {:differences 0}}))\n(binding [*out* *err*] (println \"judged 0 cases\"))\n")
      (k/config! ctx assoc :plugins [{:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}])
      ctx)))

(defstep #"it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= "{:oracle {:differences 0}}\n" (:out ctx)) "it printed " (pr-str (:out ctx)))
    (k/check! (= "judged 0 cases\n" (:err ctx)) "its error stream is " (pr-str (:err ctx)))
    (k/check! (= (str (fs/canonicalize (:dir ctx))) (k/slurp-rel ctx "oracle.pwd")) "it ran in " (k/slurp-rel ctx "oracle.pwd"))
    ctx))

(defstep #"a configuration whose plugin \"oracle\" names a command"
  (fn [ctx] (oracle! ctx "for a in \"$@\"; do echo \"$a\" >> oracle.args; done\necho \"$RDM_SPEC\" > oracle.env\n$RDM_SPEC help > oracle.help\nexit 0")))

(defstep #"the command runs with the arguments record, --reason and the spec grew, in that order"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= "record\n--reason\nthe spec grew\n" (k/slurp-rel ctx "oracle.args")) "the arguments were " (pr-str (k/slurp-rel ctx "oracle.args")))
    ctx))

(defstep #"the command runs with the environment variable RDM_SPEC set to a command line that starts this same rdm-spec, however it was installed \(from its sources or as a package\), so the oracle judges the instrument that is about to gate"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (str/join " " (k/rdm-command-line)) (str/trim (k/slurp-rel ctx "oracle.env"))) "RDM_SPEC was " (k/slurp-rel ctx "oracle.env"))
    ctx))

(defstep #"\"\$RDM_SPEC help\", run by the oracle, answers as \"rdm-spec help\" does"
  (fn [ctx]
    (let [h (k/run-rdm ctx "help")]
      (k/check! (= (:out h) (k/slurp-rel ctx "oracle.help")) "$RDM_SPEC help answered " (k/slurp-rel ctx "oracle.help"))
      ctx)))

(defstep #"a configuration whose plugin \"oracle\" names the command \[\"no-such-oracle\"\]"
  (fn [ctx] (oracle! ctx "" {:command ["no-such-oracle"]})))

(defstep #"it exits 2 and names the command"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no-such-oracle") ctx))

(defstep #"a project whose plugin \"oracle\", answering :Gate, exits 1, writing a finding on its error stream"
  (fn [ctx]
    (let [ctx (k/verify-project! ctx)]
      (oracle! ctx "echo 'oracle.edn:1: oracle-differs: :spec/check \"a case\": at [:exit] the golden holds 1 and the instrument gives 0' >&2\nexit 1"))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified.edn)"
  (fn [ctx does]
    (k/exits! ctx 1)
    (k/check! (some #{"oracle.edn:1: oracle-differs: :spec/check \"a case\": at [:exit] the golden holds 1 and the instrument gives 0"} (k/err-lines ctx))
              "the finding is not printed unchanged: " (k/shows ctx))
    (if (str/starts-with? does "builds")
      (k/check! (not (fs/exists? (str (get-in ctx [:ph "ROOM"]) "/.git"))) "a room was built")
      (k/check! (not (fs/exists? (k/path ctx "dhf/cycles/03/ratified.edn"))) "ratified.edn was written"))
    ctx))

(defstep #"a project whose :plugins names no plugin \"oracle\""
  (fn [ctx] (k/spec-project! ctx)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx] (k/exits! ctx 0) (k/out-has! ctx "there is no oracle to run") ctx))
