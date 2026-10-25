(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the shared steps
  every feature uses to run the command: `\"rdm-spec ...\" is run` (in the
  scenario's :dir, else in a fresh copy of the room's allow-list) and `it
  exits N`.

  The shared When runs the command line as written, with two placeholders
  filled from the context: ROOM (the scenario's :room, else /tmp/room, the
  path the room-building steps of other features build) and DIR (the
  scenario's :sources-dir, when it has one). It keeps the run as :run
  ({:exit :out :err :args}) and the directory as :dir. The run inherits no
  RESULTS_DIR unless the scenario set one (:env-results-dir)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Running the command

(defn- fill
  "The command line's words with ROOM and DIR filled from the context."
  [ctx args]
  (mapv (fn [a]
          (cond (= "ROOM" a) (or (:room ctx) "/tmp/room")
                (and (= "DIR" a) (:sources-dir ctx)) (:sources-dir ctx)
                :else a))
        args))

(defn run-cli
  "Runs `rdm-spec args...` in `dir`, as steps.support/rdm-spec! does, with
  RESULTS_DIR set to `env-dir` when given."
  [dir args env-dir]
  (if env-dir
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                            :extra-env {"RESULTS_DIR" env-dir}}
                   s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
      {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)})
    (apply s/rdm-spec! dir args)))

(defn run-line
  "Runs the command line `line` (without its leading rdm-spec) for the
  scenario: ctx with :dir and :run."
  [ctx line]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        args (fill ctx (s/split-args line))]
    (assoc ctx :dir (str dir) :run (run-cli dir args (:env-results-dir ctx)))))

(defn- the-run [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn stdout-edn
  "The one EDN value the run printed on standard output; fails when stdout
  holds anything else."
  [r]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. (or (:out r) "")))
        forms (try (loop [acc []]
                     (let [x (edn/read {:eof ::eof} rdr)]
                       (if (= ::eof x) acc (recur (conj acc x)))))
                   (catch Exception e (s/fail! (str "stdout is not EDN: " (ex-message e) "\n" (s/show r)))))]
    (s/check! (= 1 (count forms)) (str "stdout holds " (count forms) " EDN values, not one\n" (s/show r)))
    (first forms)))

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) (str "the command line gives --results: " line))
    (run-line (dissoc ctx :env-results-dir) line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no directory was given to stand for DIR")
    (run-line ctx line)))

(defstep #"it exits (\d+)"
  (fn [ctx code]
    (let [r (the-run ctx)]
      (s/check! (= (parse-long code) (:exit r)) (str "expected exit " code "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Everything runs through one command

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (assoc ctx :dir (s/project!))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (the-run ctx)
          _ (s/check! (zero? (:exit r)) (s/show r))
          out (stdout-edn r)
          entry (first (filter #(= task (:task %)) (:tasks out)))]
      (s/check! entry (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= usage (:usage entry)) (str "the usage of " task " is " (pr-str (:usage entry))))
      (s/check! (= does (:does entry)) (str task " is described as " (pr-str (:does entry)))))))

;; --- the results directory ---------------------------------------------------

(defn- result-json [id status]
  (str "{\"uuid\":\"" (java.util.UUID/randomUUID) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}],\"steps\":[]}"))

(defn- add-result! [dir]
  (s/write! dir (str (java.util.UUID/randomUUID) "-result.json") (result-json "spec-0001" "passed")))

(defn- status-project!
  "A project with one stamped scenario, its results directory configured as
  `results-dir` and holding a passing result for it."
  [results-dir]
  (let [dir (s/project! {:results-dir results-dir})]
    (s/write! dir "dhf/spec/demo/alpha.feature"
              (s/feature "  Rule: One\n\n    @spec-0001 @e2e\n    Scenario: One\n      Given a step\n"))
    (add-result! (fs/file dir results-dir))
    dir))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [dir (status-project! rel)
          other (fs/file dir "other")
          env (str (fs/file (s/tmp-dir) "r1"))]
      ;; the two other directories an outline row may name: each holds a result too
      (add-result! other)
      (add-result! env)
      (assoc ctx :dir dir :configured rel :env-dir env))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (dissoc ctx :env-results-dir)
      ;; the scenario's own directory stands for the path the row names
      (assoc ctx :env-results-dir (:env-dir ctx) :env-literal v))))

(defstep #"it reads (\S+)"
  (fn [ctx want]
    (let [r (the-run ctx)
          want (if (and (:env-literal ctx) (= want (:env-literal ctx))) (:env-dir ctx) want)]
      (s/check! (zero? (:exit r)) (s/show r))
      (s/check! (= want (:results (stdout-edn r))) (str "it read " (pr-str (:results (stdout-edn r))) ", not " want)))))

;; --- how every task reports ----------------------------------------------------

(defn- check-room!
  "A copy of the room's allow-list in which rdm-spec check passes."
  []
  (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
                  'rdm-spec.architecture.lint 'rdm-spec.architecture.views)
  (s/copy-of-room!))

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (check-room!))))

(def ^:private level-scenario "dhf/spec/kernel/command.feature")

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (check-room!)
          f (fs/file dir level-scenario)
          text (slurp f)
          tag "@spec-0177 @contract"
          _ (s/check! (str/includes? text tag) (str level-scenario " holds no " tag))
          ;; the scenario's line: the Scenario line under its tags
          line (+ 2 (count (filter #{\newline} (subs text 0 (str/index-of text tag)))))]
      (spit f (str/replace text tag "@spec-0177"))
      (assoc ctx :dir dir :file level-scenario :line line))))

(def finding-line
  "A finding: FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what."
  #"^(?:\S+?(?::\d+)?: )?[a-z0-9][a-z0-9-]*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (s/err-lines r)
          findings (remove #(or (str/includes? % "warning: ") (str/starts-with? % "event: ")) lines)]
      (s/check! (seq findings) (str "no finding\n" (s/show r)))
      (doseq [l findings]
        (s/check! (re-matches finding-line l) (str "not a finding line: " (pr-str l))))
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": ")) findings)
                (str "no finding names " (:file ctx) ":" (:line ctx) "\n" (s/show r))))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [out (stdout-edn (the-run ctx))]
      (s/check! (map? out) (str "the result is not a map: " (pr-str out))))))

(defstep #"the configured results directory does not exist"
  (fn [ctx]
    ;; A copy of the room, so a task that reads the spec (publish, trace) can
    ;; run, with no results directory.
    (let [dir (s/copy-of-room!)]
      (s/check! (not (fs/exists? (fs/file dir (:results-dir (s/room-config))))) "the results directory exists")
      (assoc ctx :dir dir :missing (:results-dir (s/room-config))))))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) (:missing ctx)) (str "the error stream does not name " (:missing ctx) "\n" (s/show r))))))

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx task]
    (let [r (the-run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) task) (str "the error stream does not name " task))
      (doseq [t ["help" "init" "adopt" "stamp" "check" "views" "cut" "fence" "run" "ratify" "checklist"
                 "status" "trace" "release" "score" "scoreboard" "publish"]]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) (:err r)) (str "the task " t " is not listed\n" (s/show r)))))))
