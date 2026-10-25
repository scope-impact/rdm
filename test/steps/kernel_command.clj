(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps every context's step files use (each defined here once, with
  one meaning):

    When \"rdm-spec ...\" is run                       run in the scenario's project
    When \"rdm-spec ...\" is run with no --results     the same, the line naming no --results
    When \"rdm-spec ...\" is run with that directory as DIR   DIR is ctx :sources-dir
    Then it exits N / it passes                       the last run's exit status
    Given a project set up by rdm-spec                `rdm-spec init` in a new directory
    Given a copy of rdm-spec's own project in which the configured results directory does not exist

  The command runs as its own process (bb -cp src -m rdm-spec.kernel.command),
  in ctx :dir, a new copy of the room's allow-listed paths when the scenario
  made no project. Its environment is this process's without RESULTS_DIR,
  with ctx :env over it (a nil value removes a variable): a scenario's
  commands see only the environment its steps set.

  Placeholders in a command line are directories the scenario makes for
  itself: ROOM is ctx :room, DIR ctx :sources-dir, and any name in ctx
  :placeholders ({\"OUT\" path}); ROOM, OUT and OUT2 are made up (as paths that
  do not exist yet) when the scenario has none.

  Context keys: :dir :room :sources-dir :env :placeholders :run (the last
  run: {:exit :out :err :args}).

  Every directory the steps make with steps.support/tmp-dir lies under one
  directory of the test process, deleted once that process has ended."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixture directories are removed when the tests end

;; Every fixture directory lives under one directory of this test process,
;; which a watcher process deletes once this process has ended, however it
;; ends (the test command ends with an exit that runs no shutdown hook).

(defn- watch-and-delete! [base]
  (let [code (str "(let [h (.orElse (java.lang.ProcessHandle/of " (.pid (java.lang.ProcessHandle/current)) ") nil)]"
                  " (when h (.get (.onExit h)))"
                  " (babashka.fs/delete-tree " (pr-str (str base)) "))")]
    (doto (ProcessBuilder. ^java.util.List [s/bb "-e" code])
      (.redirectOutput java.lang.ProcessBuilder$Redirect/DISCARD)
      (.redirectError java.lang.ProcessBuilder$Redirect/DISCARD)
      (.redirectInput (java.io.File. "/dev/null"))
      (.start))))

(defonce ^:private fixtures-base
  ;; steps.support/tmp-dir makes each fixture directory (prefix
  ;; rdm-spec-test-); it is placed under the base. The temporary-directory
  ;; function is wrapped rather than tmp-dir, which every load of the step
  ;; files defines again.
  (let [base (str (fs/create-temp-dir {:prefix "rdm-spec-steps-"}))
        original fs/create-temp-dir]
    (alter-var-root #'fs/create-temp-dir
                    (fn [_] (fn ([] (original))
                              ([opts] (original (if (and (= "rdm-spec-test-" (:prefix opts)) (nil? (:dir opts))
                                                         (fs/directory? base))
                                                  (assoc opts :dir base)
                                                  opts))))))
    (watch-and-delete! base)
    base))

;; ---------------------------------------------------------------------------
;; Running the command

(defn split-line
  "The words of a command line; a quoted ('...' or \"...\") word may hold spaces."
  [line]
  (vec (for [[_ a b c] (re-seq #"'([^']*)'|\"([^\"]*)\"|(\S+)" (str line))]
         (or a b c))))

(defn- env-for [ctx]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
             (dissoc (into {} (System/getenv)) "RESULTS_DIR")
             (or (:env ctx) {})))

(defn run!
  "Runs `rdm-spec argv...` in `dir` as its own process, with the scenario's
  environment: {:exit :out :err :args}."
  [ctx dir argv]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :env (env-for ctx)}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" argv)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec argv)}))

(defn- fresh-path [] (str (fs/file (s/tmp-dir) "out")))

(defn- placeholders
  "ctx with every placeholder of `words` bound, and the bindings."
  [ctx words]
  (let [ctx (cond-> ctx
              (and (some #{"ROOM"} words) (nil? (:room ctx))) (assoc :room (fresh-path)))
        ctx (reduce (fn [c w]
                      (if (and (#{"OUT" "OUT2"} w) (nil? (get-in c [:placeholders w])))
                        (assoc-in c [:placeholders w] (fresh-path))
                        c))
                    ctx words)
        binds (merge (when (:room ctx) {"ROOM" (str (:room ctx))})
                     (when (:sources-dir ctx) {"DIR" (str (:sources-dir ctx))})
                     (:placeholders ctx))]
    [ctx binds]))

(defn run-line
  "Runs the command line `line` (\"check --events\", an optional leading
  \"rdm-spec \" dropped) in the scenario's project, a fresh copy of the room
  when it has none. ctx with :dir and :run."
  [ctx line]
  (let [words (split-line (str/replace (str line) #"^\s*rdm-spec\s+" ""))
        ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        [ctx binds] (placeholders ctx words)
        argv (mapv #(get binds % %) words)]
    (assoc ctx :run (run! ctx (:dir ctx) argv))))

(defn the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Fails unless the last run exited `n`; returns the run."
  [ctx n]
  (let [r (the-run ctx)]
    (s/check! (= n (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output read as EDN; fails when it is not."
  [r]
  (try (edn/read-string (:out r))
       (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r))))))

(defn- cannot-run-line [r]
  (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r))))

;; ---------------------------------------------------------------------------
;; The shared steps

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) (str "the line names --results: " line))
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no directory was given to stand for DIR")
    (run-line ctx line)))

(defstep #"it exits (\d)"
  (fn [ctx n] (exits! ctx (parse-long n)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"a project set up by rdm-spec"
  (fn [ctx]
    (let [parent (s/tmp-dir)
          r (run! ctx parent ["init" "project"])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (:results-dir (s/room-config))]
      (s/check! (string? rd) "the room's configuration names no results directory")
      (s/check! (not (fs/exists? (fs/file dir rd))) (str rd " exists in the copy"))
      (assoc ctx :dir dir :results-dir rd))))

;; ---------------------------------------------------------------------------
;; Everything runs through one command

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          listed (:tasks (stdout-edn r))
          row (first (filter #(= task (:task %)) listed))]
      (s/check! row (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= (str/trim usage) (:usage row)) (str task "'s usage is " (pr-str (:usage row))))
      (s/check! (= (str/trim does) (:does row)) (str task " is described as " (pr-str (:does row)))))))

;; ---------------------------------------------------------------------------
;; A task's results directory

(defn- results-with-one! [dir]
  (fs/create-dirs dir)
  (spit (str (fs/file dir "r0-result.json"))
        "{\"uuid\":\"r0\",\"name\":\"a test\",\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")
  (str dir))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [dir (s/copy-of-room!)]
      (s/check! (= rel (:results-dir (s/room-config))) (str "the room's configured results directory is not " rel))
      (results-with-one! (fs/file dir rel))
      (results-with-one! (fs/file dir "other"))
      (assoc ctx :dir dir))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (results-with-one! (fs/file (s/tmp-dir) v))]
        (-> ctx
            (assoc-in [:env "RESULTS_DIR"] d)
            (assoc-in [:placeholders v] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx want]
    (let [r (exits! ctx 0)
          read (:results (stdout-edn r))
          want (get (:placeholders ctx) want want)]
      (s/check! (= want read) (str "it read " (pr-str read) ", not " want "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; A gate stops the task; every task reports the same way

(defn- drop-level!
  "Removes the level tag of the first scenario of a feature file of the
  project `dir`: {:file rel :line n} of that scenario."
  [dir]
  (let [rel "dhf/spec/kernel/bus.feature"
        f (fs/file dir rel)
        text (slurp (str f))
        lines (str/split-lines text)
        i (first (keep-indexed (fn [i l] (when (re-find #"^\s*@spec-\d+ @(e2e|contract)\s*$" l) i)) lines))
        _ (s/check! i (str rel " holds no scenario tag line"))
        lines (update (vec lines) i #(str/replace % #"\s*@(e2e|contract)" ""))
        scenario-line (+ 2 i)]
    (spit (str f) (str (str/join "\n" lines) "\n"))
    (s/check! (str/includes? (nth lines (dec scenario-line)) "Scenario") "the tag is not above its scenario")
    {:file rel :line scenario-line}))

(defn- stale-model! [dir]
  (let [f (fs/file dir (:model (s/room-config)))
        text (slurp (str f))
        changed (str/replace-first text "Reads model.edn and the event catalogue" "Reads model.edn and its event catalogue")]
    (s/check! (not= text changed) "the model holds no description to change")
    (spit (str f) changed)))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          at (drop-level! dir)]
      (stale-model! dir)
      (assoc ctx :dir dir :no-level at))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          lines (s/err-lines r)
          {:keys [file line]} (:no-level ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(re-find #"^dhf/views/\S+: view-stale: " %) lines)
                (str "no finding names a stale view\n" (s/show r))))))

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :no-level (drop-level! dir)))))

(def ^:private finding-line
  #"^(?:[^\s:]+:(?:\d+: )?)?(?:warning: )?[a-z0-9][a-z0-9-]*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (s/err-lines r)
          {:keys [file line]} (:no-level ctx)]
      (s/check! (seq lines) "the error stream is empty")
      (doseq [l lines]
        (s/check! (re-matches finding-line l) (str "not a finding line: " (pr-str l))))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)))
                (str "not one line names the missing level at " file ":" line "\n" (s/show r))))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          forms (try (edn/read-string (str "[" (:out r) "]"))
                     (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e)))))]
      (s/check! (= 1 (count forms)) (str "standard output holds " (count forms) " values\n" (s/show r)))
      (s/check! (map? (first forms)) (str "the result is not a map\n" (s/show r))))))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [r (exits! ctx 2)
          l (cannot-run-line r)
          rd (or (:results-dir ctx) (:results-dir (s/room-config)))]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (and l (str/includes? l rd)) (str "no cannot-run line names " rd "\n" (s/show r))))))

(defn- spec-tasks
  "The tasks command.feature's table lists, read from the room's spec."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))
        ex (first (:examples sc))
        i (.indexOf (vec (:header ex)) "task")]
    (s/check! (<= 0 i) "command.feature's table has no task column")
    (mapv #(nth (:cells %) i) (:rows ex))))

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx word]
    (let [r (exits! ctx 2)
          l (cannot-run-line r)
          ts (spec-tasks)]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (and l (str/includes? l word)) (str "no cannot-run line names " word "\n" (s/show r)))
      (s/check! (seq ts) "the spec lists no task")
      (doseq [t ts]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) l) (str "the refusal does not list " t "\n" (s/show r)))))))
