(ns steps.measurement-oracle
  "Steps of the component Oracle (08-plugins/oracle.feature): the oracle task
  runs the plugin named \"oracle\"; here a script the fixture keeps."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def oracle-script
  "(spit \"oracle-ran.edn\" (pr-str {:pwd (System/getProperty \"user.dir\") :args (vec *command-line-args*) :rdm-spec (System/getenv \"RDM_SPEC\")}))
(when-let [cmd (System/getenv \"RDM_SPEC\")]
  (let [r (apply babashka.process/shell {:out :string :err :string :continue true} (concat (clojure.string/split cmd #\" \") [\"help\"]))]
    (spit \"oracle-help.edn\" (pr-str {:exit (:exit r) :out (:out r)}))))
(println (pr-str {:oracle {:differences 0}}))
(binding [*out* *err*] (println \"warning: oracle-note: judged by the fixture\"))
(System/exit 0)
")

(defn oracle-project! [& [command]]
  (let [ctx (k/cycle-project!)]
    (k/write! (:dir ctx) "dhf/harness/oracle/oracle.clj" oracle-script)
    (k/plugins! ctx [{:name "oracle" :answers :Gate :command (or command ["bb" "dhf/harness/oracle/oracle.clj"])}])))

(defn ran [ctx] (some-> (k/slurp-in (:dir ctx) "oracle-ran.edn") edn/read-string))

(defstep #"a configuration whose :plugins holds \{:name \"oracle\" :answers :Gate :command \[\"bb\" \"dhf/harness/oracle/oracle.clj\"\]\}, a command that prints \{:oracle \{:differences 0\}\} and exits 0"
  (fn [_] (oracle-project!)))

(defstep #"it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (:pwd (ran ctx))))) ctx "not in the project's root")
    (k/expect! (= "{:oracle {:differences 0}}\n" (:out ctx)) ctx "standard output changed")
    (k/expect! (= "warning: oracle-note: judged by the fixture\n" (:err ctx)) ctx "the error stream changed")
    (k/write! (:dir ctx) "dhf/harness/oracle/oracle.clj" "(binding [*out* *err*] (println \"x:1: oracle-differs: one\")) (System/exit 1)")
    (let [r (k/rdm! ctx "oracle")] (k/exits! r 1) (k/expect! (= "x:1: oracle-differs: one\n" (:err r)) r "not relayed"))
    ctx))

(defstep #"a configuration whose plugin \"oracle\" names a command"
  (fn [_] (oracle-project!)))

(defstep #"the command runs with the arguments record, --reason and the spec grew, in that order"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= ["record" "--reason" "the spec grew"] (:args (ran ctx))) ctx (str "the arguments are " (:args (ran ctx)))) ctx))

(defn rdm-spec-answers! [ctx]
  (let [h (edn/read-string (k/slurp-in (:dir ctx) "oracle-help.edn"))
        mine (k/run-rdm ["help"] {:dir (:dir ctx) :ceiling (:top ctx)})]
    (k/expect! (and (zero? (:exit h)) (= (:out mine) (:out h))) ctx "\"$RDM_SPEC help\" does not answer as rdm-spec help does")))

(defstep #"the command runs with the environment variable RDM_SPEC set to a command line that starts this same rdm-spec, however it was installed \(from its sources or as a package\), so the oracle judges the instrument that is about to gate"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (str/includes? (:rdm-spec (ran ctx)) (str @k/root "/src")) ctx (str "RDM_SPEC is " (:rdm-spec (ran ctx))))
    (let [jar (k/build-jar!)
          r (k/rdm! (assoc ctx :argv ["bb" jar]) "oracle")]
      (k/exits! r 0)
      (k/expect! (str/includes? (:rdm-spec (ran ctx)) jar) ctx (str "RDM_SPEC from the package is " (:rdm-spec (ran ctx))))
      (rdm-spec-answers! ctx))
    ctx))

(defstep #"\"\$RDM_SPEC help\", run by the oracle, answers as \"rdm-spec help\" does"
  (fn [ctx] (rdm-spec-answers! ctx) ctx))

(defstep #"a configuration whose plugin \"oracle\" names the command \[\"no-such-oracle\"\]"
  (fn [_] (oracle-project! ["no-such-oracle"])))

(defstep #"it exits 2 and names the command"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no-such-oracle") ctx))

(defstep #"a project whose plugin \"oracle\", answering :Gate, exits 1, writing a finding on its error stream"
  (fn [_] (let [ctx (oracle-project!)]
            (k/write! (:dir ctx) "dhf/harness/oracle/oracle.clj" "(binding [*out* *err*] (println \"case-1:1: oracle-differs: the golden differs\")) (System/exit 1)")
            ctx)))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified.edn)"
  (fn [ctx does]
    (k/exits! ctx 1)
    (k/expect! (some #{"case-1:1: oracle-differs: the golden differs"} (k/err-lines ctx)) ctx "not unchanged")
    (if (= does "builds no room")
      (k/expect! (not (fs/exists? (k/path (get-in ctx [:vars "ROOM"]) ".git"))) ctx "a room was built")
      (k/expect! (not (fs/exists? (k/path (:dir ctx) "dhf/cycles/03/ratified.edn"))) ctx "ratified.edn was written"))
    ctx))

(defstep #"a project whose :plugins names no plugin \"oracle\""
  (fn [_] (k/new-project)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx] (k/exits! ctx 0) (k/out-has! ctx "there is no oracle to run") ctx))
