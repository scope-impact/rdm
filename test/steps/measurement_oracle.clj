(ns steps.measurement-oracle
  "Step definitions for dhf/spec/measurement/oracle.feature: `rdm-spec
  oracle [ARGS]`, and the oracle as the gate of fence and verify, on a cycle
  project (steps.measurement-cycle) whose configuration's :oracle names a
  small command the step writes into the project, never the room's oracle.

  The fixture oracle, dhf/harness/oracle/oracle.clj of the project, records
  what it was run with (its directory, arguments and RDM_SPEC) in a file
  outside the project, prints its answer and exits as the step says.

  Context keys set here: :dir (the project), :room, :record (the file the
  oracle records into), :finding (the line the oracle prints on its error
  stream)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def oracle-rel "dhf/harness/oracle/oracle.clj")

(def finding "oracle-differs: :verification/verify \"a case\": at [:exit] the golden holds 0 and the instrument gives 1")

(defn- oracle-text [record exit]
  (str "(spit " (pr-str record) " (pr-str {:dir (System/getProperty \"user.dir\") :args (vec *command-line-args*)"
       " :rdm-spec (System/getenv \"RDM_SPEC\")}))\n"
       "(println (pr-str {:oracle {:differences " (if (zero? exit) 0 1) "}}))\n"
       "(binding [*out* *err*] (println " (pr-str (if (zero? exit) "the oracle judged 1 case" finding)) "))\n"
       "(System/exit " exit ")\n"))

(defn- project!
  "ctx with a cycle project cut spec-3, cycle 03 declared, a passing room,
  and :oracle `oracle` (nil: none) in its configuration."
  [ctx oracle exit]
  (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})
        cfg (kc/config-of dir)
        record (str (fs/file (s/tmp-dir) "oracle-run.edn"))]
    (s/write! dir "dhf/harness/config.edn"
              (str (pr-str (cond-> (assoc cfg :verify [{:kind :tests :command ["bb" "test.clj"]}]
                                          :allure {:labels {"spec" :scenario-id}})
                             oracle (assoc :oracle oracle)))
                   "\n"))
    (s/write! dir oracle-rel (oracle-text record exit))
    (mc/cut! dir "spec-3")
    (s/hooks-off! dir)
    (mc/write-cycle! dir "03" {})
    (assoc ctx :dir dir :record record :finding finding
           :room (mc/passing-room! ["spec-0012" "spec-0014"]))))

(defn- recorded [ctx]
  (let [f (:record ctx)]
    (s/check! (fs/exists? f) (str "the oracle did not run\n" (s/show (kc/the-run ctx))))
    (edn/read-string (slurp f))))

;; ---------------------------------------------------------------------------

(defstep #"a configuration whose :oracle is \{:command \[\"bb\" \"dhf/harness/oracle/oracle\.clj\"\]\}, a command that prints \{:oracle \{:differences 0\}\} and exits 0"
  (fn [ctx] (project! ctx {:command ["bb" oracle-rel]} 0)))

(defstep #"it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (:dir (recorded ctx)))))
                (str "the oracle ran in " (:dir (recorded ctx))))
      (s/check! (= "{:oracle {:differences 0}}\n" (:out r)) (str "standard output is not the oracle's\n" (s/show r)))
      (s/check! (= "the oracle judged 1 case\n" (:err r)) (str "the error stream is not the oracle's\n" (s/show r)))
      nil)))

(defstep #"a configuration whose :oracle names a command"
  (fn [ctx] (project! ctx {:command ["bb" oracle-rel]} 0)))

(defstep #"the command runs with the arguments record, --reason and the spec grew, in that order"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= ["record" "--reason" "the spec grew"] (:args (recorded ctx))) (str "the arguments were " (pr-str (:args (recorded ctx)))))
    nil))

(defstep #"the command runs with the environment variable RDM_SPEC set to a command line that runs this same rdm-spec, so the oracle judges the instrument that is about to gate"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [line (:rdm-spec (recorded ctx))
          _ (s/check! (not (str/blank? line)) "RDM_SPEC is not set")
          words (str/split line #"\s+")
          cp (second (drop-while #(not= "-cp" %) words))
          r (apply p/shell {:dir (str (:dir ctx)) :out :string :err :string :continue true :in ""} (concat words ["help"]))]
      (s/check! (and cp (= (str (fs/canonicalize (fs/file s/room-root "src"))) (str (fs/canonicalize cp))))
                (str "RDM_SPEC runs the code of " cp ", not of this room: " line))
      (s/check! (and (zero? (:exit r)) (str/starts-with? (:out r) "{:help")) (str "RDM_SPEC help did not answer: " (pr-str r)))
      nil)))

(defstep #"a configuration whose :oracle names the command \[\"no-such-oracle\"\]"
  (fn [ctx] (project! ctx {:command ["no-such-oracle"]} 0)))

(defstep #"it exits 2 and names the command"
  (fn [ctx] (kc/refused-naming! ctx "no-such-oracle") nil))

(defstep #"a project whose configured oracle command exits 1, printing a finding on its error stream"
  (fn [ctx] (project! ctx {:command ["bb" oracle-rel]} 1)))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified\.edn)"
  (fn [ctx does]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #{(:finding ctx)} (s/err-lines r)) (str "the oracle's finding is not printed unchanged\n" (s/show r)))
      (s/check! (fs/exists? (:record ctx)) "the oracle did not run")
      (case does
        "builds no room" (s/check! (not (fs/exists? (fs/file (:room ctx) ".git"))) "a room was built")
        "writes no ratified.edn" (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn"))) "ratified.edn was written"))
      nil)))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx]
    (let [ctx (project! ctx nil 0)]
      (s/check! (not (contains? (kc/config-of (:dir ctx)) :oracle)) "the configuration names an :oracle")
      ctx)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (str/includes? (pr-str (kc/stdout-edn r)) "no oracle to run") (str "it does not say so\n" (s/show r)))
      (s/check! (str/blank? (:err r)) (str "it said so on the error stream\n" (s/show r)))
      (s/check! (not (fs/exists? (:record ctx))) "an oracle ran")
      nil)))
