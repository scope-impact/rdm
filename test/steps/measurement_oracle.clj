(ns steps.measurement-oracle
  "Step definitions of dhf/spec/measurement/oracle.feature. Each fixture's
  oracle is a small command the scenario writes; the real one is never run."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn oracle-script
  "An oracle that records its directory, arguments and RDM_SPEC under `rec`,
  prints `out` and `err`, and exits `exit`."
  [rec out err exit]
  (str "(require '[babashka.fs :as fs])\n"
       "(spit \"" rec "/cwd\" (str (fs/absolutize \".\")))\n"
       "(spit \"" rec "/args\" (pr-str (vec *command-line-args*)))\n"
       "(spit \"" rec "/rdm-spec\" (str (System/getenv \"RDM_SPEC\")))\n"
       "(print " (pr-str out) ") (flush)\n"
       "(binding [*out* *err*] (print " (pr-str err) ") (flush))\n"
       "(System/exit " exit ")\n"))

(defn oracle-project!
  ([ctx] (oracle-project! ctx "{:oracle {:differences 0}}\n" "note: 0 cases differ\n" 0))
  ([ctx out err exit]
   (let [ctx (k/project! ctx)
         rec (k/path-in ctx "oracle-record")]
     (fs/create-dirs rec)
     (k/write-files! (:dir ctx) {"dhf/harness/oracle/oracle.clj" (oracle-script rec out err exit)})
     (k/update-edn! ctx "dhf/harness/config.edn" assoc :oracle {:command ["bb" "dhf/harness/oracle/oracle.clj"]})
     (assoc ctx ::rec rec ::out out ::err err))))

(defn recorded [ctx k] (let [f (fs/file (::rec ctx) k)] (when (fs/exists? f) (slurp f))))

(defstep #"a configuration whose :oracle is \{:command \[\"bb\" \"dhf/harness/oracle/oracle.clj\"\]\}, a command that prints \{:oracle \{:differences 0\}\} and exits 0"
  (fn [ctx] (oracle-project! ctx)))

(defstep #"it runs the command in the project's root, prints its standard output and error stream unchanged, and exits as it exits"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (recorded ctx "cwd")))))
    (assert (= (::out ctx) (:out ctx)) (:out ctx))
    (assert (= (::err ctx) (:err ctx)) (:err ctx))
    ctx))

(defstep #"a configuration whose :oracle names a command"
  (fn [ctx] (oracle-project! ctx)))

(defstep #"the command runs with the arguments record, --reason and the spec grew, in that order"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "[\"record\" \"--reason\" \"the spec grew\"]" (recorded ctx "args")) (recorded ctx "args"))
    ctx))

(defstep #"the command runs with the environment variable RDM_SPEC set to a command line that runs this same rdm-spec, so the oracle judges the instrument that is about to gate"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [words (str/split (str/trim (recorded ctx "rdm-spec")) #" ")
          r (apply k/sh (:dir ctx) {} (concat words ["help"]))]
      (assert (= 0 (:exit r)) (pr-str words r))
      (assert (str/starts-with? (:out r) "{:help") (:out r))
      (assert (some #(str/includes? % (str k/room "/src")) words) (pr-str words))
      ctx)))

(defstep #"a configuration whose :oracle names the command \[\"no-such-oracle\"\]"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :oracle {:command ["no-such-oracle"]}))))

(defstep #"it exits 2 and names the command"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "no-such-oracle")))

(defstep #"a project whose configured oracle command exits 1, printing a finding on its error stream"
  (fn [ctx]
    (let [ctx (k/cycle-ready! (oracle-project! ctx "{:oracle {:differences 1}}\n" "dhf/harness/oracle/cases/x: differs: :exit 1 where 0 was recorded\n" 1))]
      (k/tag-cut! ctx "spec-3"))))

(defstep #"it exits 1, prints the oracle's findings unchanged, and (builds no room|writes no ratified.edn)"
  (fn [ctx what]
    (k/check-exit ctx 1)
    (assert (str/includes? (:err ctx) (::err ctx)) (:err ctx))
    (if (= what "builds no room")
      (assert (not (fs/exists? (fs/path (k/guard! (get-in ctx [:ph "ROOM"])) ".git"))) "a room was built")
      (assert (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn"))) "ratified.edn was written"))
    ctx))

(defstep #"a project whose configuration names no :oracle"
  (fn [ctx] (k/project! ctx)))

(defstep #"it exits 0 and says there is no oracle to run"
  (fn [ctx] (k/check-exit ctx 0) (assert (str/includes? (:out ctx) "no oracle to run") (:out ctx)) ctx))
