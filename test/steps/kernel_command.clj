(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps every context's step files use:

    When \"rdm-spec ...\" is run                      (the shared When)
    When \"rdm-spec ...\" is run with no --results
    When \"rdm-spec ...\" is run with that directory as DIR
    Given the configured results directory does not exist
    Then it exits N

  The shared When runs the command as its own process in the scenario's
  project (:dir; a copy of the room's allow-listed paths when no Given set
  one), ROOM standing for the scenario's room (:room, a fresh path when none
  was set) and DIR for its --sources directory (:sources-dir). Its process
  sees the environment the tests run in without RESULTS_DIR, plus the
  scenario's own (:env, a map; a nil value unsets). It leaves {:exit :out
  :err :args} in :run.

  Helpers others use: `run-line` (the shared When as a function) and
  `stdout-edn` (a run's standard output, read as EDN)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Running the command

(defn split-line
  "The words of a command line; a word may be quoted with ' or \"."
  [line]
  (loop [cs (seq line) cur nil quote-ch nil out []]
    (if-let [[c & more] cs]
      (cond
        quote-ch (if (= c quote-ch)
                   (recur more (or cur "") nil out)
                   (recur more (str cur c) quote-ch out))
        (#{\' \"} c) (recur more (or cur "") c out)
        (Character/isWhitespace ^char c) (recur more nil nil (if cur (conj out cur) out))
        :else (recur more (str cur c) nil out))
      (if cur (conj out cur) out))))

(defn- env-of [ctx]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v))))
             (dissoc (into {} (System/getenv)) "RESULTS_DIR")
             (or (:env ctx) {})))

(defn run!
  "Runs `rdm-spec args...` in `dir` with the scenario's environment."
  [ctx dir args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :env (env-of ctx)}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)}))

(defn run-line
  "The shared When: runs the command line `line` (without rdm-spec) in the
  scenario's project. ctx with :dir, :room and :run."
  [ctx line]
  (let [ctx (cond-> ctx
              (nil? (:dir ctx)) (assoc :dir (s/copy-of-room!))
              (nil? (:room ctx)) (assoc :room (str (fs/file (s/tmp-dir) "room"))))
        args (mapv (fn [w]
                     (cond (= "ROOM" w) (str (:room ctx))
                           (and (= "DIR" w) (:sources-dir ctx)) (str (:sources-dir ctx))
                           :else w))
                   (split-line line))]
    (assoc ctx :run (run! ctx (:dir ctx) args))))

(defn stdout-edn
  "The run's standard output read as EDN; nil when it is not EDN."
  [r]
  (try (edn/read-string (:out r)) (catch Exception _ nil)))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- exits! [ctx n]
  (let [r (the-run ctx)]
    (s/check! (= n (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn- cannot-run-lines [r]
  (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))

;; ---------------------------------------------------------------------------
;; Projects

(defn- feature-files [dir]
  (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))

(defn- strip-level!
  "Removes the level tag of the first scenario of `rel` (a feature file of
  the project): {:file rel :line n}, n the scenario's line."
  [dir rel]
  (let [f (fs/file dir rel)
        lines (str/split-lines (slurp f))
        i (first (keep-indexed (fn [i l] (when (re-find #"^\s*@\S+.*@(e2e|contract)\b" l) i)) lines))
        _ (s/check! i (str rel " has no scenario with a level"))
        tagged (str/replace (nth lines i) #"\s*@(e2e|contract)\b" "")
        j (first (keep-indexed (fn [j l] (when (and (> j i) (re-find #"^\s*Scenario( Outline)?:" l)) j)) lines))
        scenario-line (inc j)]
    (spit f (str (str/join "\n" (assoc (vec lines) i tagged)) "\n"))
    {:file rel :line scenario-line}))

(def ^:private level-feature "dhf/spec/architecture/lint.feature")

(defn- stale-view!
  "Rewrites the first line of one view's source so it records another model
  hash: that view is older than the model. Returns the view's key."
  [dir]
  (let [views (sort (map str (fs/glob (fs/file dir "dhf/views") "*.mmd")))
        _ (s/check! (seq views) "the project holds no rendered view")
        f (first views)
        [first-line & more] (str/split-lines (slurp f))]
    (spit f (str (str/join "\n" (cons (str/replace first-line #"[0-9a-f]{64}" (apply str (repeat 64 "0")))
                                      more))
                 "\n"))
    (str/replace (str (fs/file-name f)) #"\.mmd$" "")))

;; ---------------------------------------------------------------------------
;; Shared steps

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) (str "the line gives --results: " line))
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no directory was given to stand for DIR")
    (run-line ctx line)))

(defstep #"the configured results directory does not exist"
  (fn [ctx]
    ;; a copy of the room, so a task that reads the spec (publish, trace) can
    ;; run; status still finds no results directory
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          cfg (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))
          results (fs/file dir (:results-dir cfg))]
      (when (fs/exists? results) (fs/delete-tree results))
      (assoc ctx :dir dir))))

(defstep #"it exits (\d+)"
  (fn [ctx n] (exits! ctx (parse-long n)) ctx))

;; ---------------------------------------------------------------------------
;; Everything runs through one command

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          listed (:tasks (stdout-edn r))
          entry (first (filter #(= task (:task %)) listed))]
      (s/check! (seq listed) (str "help listed no task\n" (s/show r)))
      (s/check! entry (str "help does not list " task "\n" (s/show r)))
      (s/check! (= usage (:usage entry)) (str task "'s usage is " (pr-str (:usage entry)) ", not " (pr-str usage)))
      (s/check! (= does (:does entry)) (str task " is described as " (pr-str (:does entry)) ", not " (pr-str does))))))

;; --- the results directory ---------------------------------------------------

(defstep #"the configured results directory is (\S+)"
  (fn [ctx dir]
    (let [d (or (:dir ctx) (s/copy-of-room!))
          cfg (edn/read-string (slurp (str (fs/file d "dhf/harness/config.edn"))))]
      (s/write! d "dhf/harness/config.edn" (pr-str (assoc cfg :results-dir dir)))
      (assoc ctx :dir d))))

(defstep #"the environment variable (\S+) is (\S+)"
  (fn [ctx var value]
    (assoc ctx :env (assoc (:env ctx) var (when-not (= "unset" value) value)))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (the-run ctx)
          named? (fn [l] (and (str/includes? l "results directory")
                              (re-find (re-pattern (str "(^|\\s)" (java.util.regex.Pattern/quote dir) "(\\s|$)")) l)))]
      (s/check! (or (= dir (:results (stdout-edn r)))
                    (some named? (cannot-run-lines r)))
                (str "the run did not read " dir "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A gate stops the task

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          stripped (strip-level! dir level-feature)
          view (stale-view! dir)]
      (assoc ctx :dir dir :stripped stripped :stale-view view))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          {:keys [file line]} (:stripped ctx)
          lines (s/err-lines r)]
      (s/check! (some #(and (str/starts-with? % (str file ":" line ": ")) (str/includes? % "level")) lines)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(and (str/includes? % "stale") (str/includes? % (:stale-view ctx))) lines)
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Every task reports the same way

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :stripped (strip-level! dir level-feature)))))

(def ^:private finding-line
  "FILE:LINE: RULE: what, FILE:  RULE: what, or RULE: what (and a warning's
  `warning: ` before its rule)."
  #"^(?:[^\s:]+(?::\d+)?: )?(?:warning: )?[a-z0-9][a-z0-9-]*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          {:keys [file line]} (:stripped ctx)
          lines (remove #(str/starts-with? % "event: ") (s/err-lines r))]
      (s/check! (seq lines) (str "the error stream is empty\n" (s/show r)))
      (doseq [l lines]
        (s/check! (re-matches finding-line l) (str "not a finding line: " (pr-str l) "\n" (s/show r))))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": ")) lines)))
                (str "not exactly one line names " file ":" line "\n" (s/show r))))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          v (try (edn/read-string (str "[" (:out r) "]")) (catch Exception e (s/fail! (str "not EDN: " (ex-message e)))))]
      (s/check! (= 1 (count v)) (str "standard output holds " (count v) " values\n" (s/show r)))
      (s/check! (map? (first v)) (str "standard output is not a result map\n" (s/show r))))))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [r (exits! ctx 2)
          cfg (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/harness/config.edn"))))
          missing (:results-dir cfg)]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
      (s/check! (some #(str/includes? % missing) (cannot-run-lines r))
                (str "no cannot-run line names " missing "\n" (s/show r))))))

(def ^:private every-task
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "fence" "run" "ratify" "checklist" "status"
   "trace" "release" "score" "scoreboard" "oracle" "publish"])

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx task]
    (let [r (exits! ctx 2)
          lines (s/err-lines r)]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
      (s/check! (some #(str/includes? % task) (cannot-run-lines r)) (str "no cannot-run line names " task "\n" (s/show r)))
      (doseq [t every-task]
        (s/check! (some #(re-find (re-pattern (str "\\brdm-spec " t "\\b")) %) lines)
                  (str "the error stream does not list the task " t "\n" (s/show r)))))))
