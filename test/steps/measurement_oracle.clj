(ns steps.measurement-oracle
  "Step definitions of dhf/spec/measurement/oracle.feature. A scenario's
  oracle is a small command it writes, never the project's real one."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn- file [ctx p] (fs/file (k/guard! (:dir ctx)) p))

(defn oracle!
  "A project whose plugin \"oracle\", answering :Gate, runs `command`, with
  the files `scripts` written."
  [ctx command scripts]
  (let [ctx (k/project! ctx {:config {:plugins [{:name "oracle" :answers :Gate :command command}]}})]
    (k/write-files! (:dir ctx) scripts)
    ctx))

(def recording-oracle
  "Records its arguments, where it ran and RDM_SPEC, and what \"$RDM_SPEC help\" answers."
  (str "for a in \"$@\"; do echo \"$a\"; done > args.txt\n"
       "pwd > cwd.txt\n"
       "printf '%s' \"$RDM_SPEC\" > rdm-spec.txt\n"
       "$RDM_SPEC help > help.out 2> help.err\n"
       "echo $? > help.exit\n"))

(defstep #"a configuration whose :plugins holds (\{:name \"oracle\" .+\}), a command that prints \{:oracle \{:differences 0\}\} and exits 0"
  (fn [ctx entry]
    (let [e (edn/read-string entry)]
      (-> (k/project! ctx {:config {:plugins [e]}})
          (doto (-> :dir (k/write-files! {"dhf/harness/oracle/oracle.clj"
                                          (str "(spit \"cwd.txt\" (str (babashka.fs/absolutize \".\")))\n"
                                               "(println {:oracle {:differences 0}})\n"
                                               "(binding [*out* *err*] (println \"oracle: 0 differences\"))\n")})))))))

(defstep #"it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "{:oracle {:differences 0}}\n" (:out ctx)) (pr-str (:out ctx)))
    (assert (= "oracle: 0 differences\n" (:err ctx)) (pr-str (:err ctx)))
    (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (str/trim (slurp (file ctx "cwd.txt")))))))
    ;; and exits as it exits
    (k/write-files! (:dir ctx) {"dhf/harness/oracle/oracle.clj" "(println :differs)\n(System/exit 1)\n"})
    (let [r (k/rdm ctx ["oracle"])]
      (k/check-exit r 1)
      (assert (= ":differs\n" (:out r)) (:out r)))
    ctx))

(defstep #"a configuration whose plugin \"oracle\" names a command"
  (fn [ctx] (oracle! ctx ["sh" "dhf/harness/oracle/run.sh"] {"dhf/harness/oracle/run.sh" recording-oracle})))

(defstep #"the command runs with the arguments record, --reason and the spec grew, in that order"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= ["record" "--reason" "the spec grew"] (str/split-lines (slurp (file ctx "args.txt")))))
    ctx))

(defn- answers-as-help [ctx]
  (let [help (k/rdm ctx ["help"])]
    (k/check-exit help 0)
    (assert (= "0" (str/trim (slurp (file ctx "help.exit")))) (slurp (file ctx "help.err")))
    (assert (= (:out help) (slurp (file ctx "help.out"))) (slurp (file ctx "help.out")))))

(defstep #"the command runs with the environment variable RDM_SPEC set to a command line that starts this same rdm-spec, however it was installed \(from its sources or as a package\), so the oracle judges the instrument that is about to gate"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [line (slurp (file ctx "rdm-spec.txt"))]
      (assert (str/includes? line (str (fs/path k/room-root "src"))) line)
      ;; as a package: a jar of these sources, run by bb
      (let [jar (k/path-in ctx "rdm-spec.jar")
            b (k/sh k/room-root {} "bb" "uberjar" jar "-m" "rdm-spec.kernel.command")]
        (assert (= 0 (:exit b)) (:err b))
        (fs/delete-if-exists (file ctx "rdm-spec.txt"))
        (let [r (k/sh (:dir ctx) {} "bb" "-cp" jar "-m" "rdm-spec.kernel.command" "oracle")
              packaged (slurp (file ctx "rdm-spec.txt"))]
          (assert (= 0 (:exit r)) (:err r))
          (assert (str/includes? packaged jar) packaged)
          (assert (not (str/includes? packaged (str (fs/path k/room-root "src")))) packaged)
          (answers-as-help ctx))))
    (assoc ctx ::checked true)))

(defstep #"\"\$RDM_SPEC help\", run by the oracle, answers as \"rdm-spec help\" does"
  (fn [ctx] (answers-as-help ctx) ctx))

(defstep #"a configuration whose plugin \"oracle\" names the command \[\"no-such-oracle\"\]"
  (fn [ctx] (oracle! ctx ["no-such-oracle"] {})))

(defstep #"it exits 2 and names the command"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "no-such-oracle")))

(def finding "dhf/spec/kernel/core.feature:5: oracle-differs: the output of check differs from its golden")

(defstep #"a project whose plugin \"oracle\", answering :Gate, exits 1, writing a finding on its error stream"
  (fn [ctx]
    (-> (oracle! ctx ["sh" "dhf/harness/oracle/run.sh"]
                 {"dhf/harness/oracle/run.sh" (str "echo '" finding "' >&2\nexit 1\n")})
        k/room!
        (k/cycle! "03" (merge {:cut "spec-3" :scope :project} k/roles))
        (k/tag-cut! "spec-3"))))

(defn- prints-findings [ctx]
  (k/check-exit ctx 1)
  (assert (some #{finding} (k/err-lines ctx)) (:err ctx))
  ctx)

(defstep #"it exits 1, prints the oracle's findings unchanged, and builds no room"
  (fn [ctx]
    (prints-findings ctx)
    (assert (not (fs/exists? (fs/path (get-in ctx [:ph "ROOM"]) ".git"))) "a room was built")
    ctx))

(defstep #"it exits 1, prints the oracle's findings unchanged, and writes no ratified.edn"
  (fn [ctx]
    (prints-findings ctx)
    (assert (not (fs/exists? (file ctx "dhf/cycles/03/ratified.edn"))))
    (assert (not (fs/exists? (file ctx "dhf/cycles/03/build"))))
    ctx))

(defstep #"a project whose :plugins names no plugin \"oracle\""
  (fn [ctx]
    (let [ctx (k/project! ctx {:config {:plugins [{:name "lint-a" :subscribes [:SpecParsed] :command ["sh" "lint.sh"]}]}})]
      (k/write-files! (:dir ctx) {"lint.sh" "echo ran >> ran.log\n"})
      ctx)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (str/includes? (:out ctx) "no oracle to run") (:out ctx))
    (assert (str/blank? (:err ctx)) (:err ctx))
    (assert (not (fs/exists? (file ctx "ran.log"))))
    ctx))
