(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the steps every
  context shares: running the command (`\"rdm-spec ...\" is run`), its exit
  code, and the project Givens the command's scenarios set up.

  The shared When runs the command as its own process in the scenario's
  project (ctx :dir; a copy of the room's allow-list when the scenario made
  none), with ROOM in the line filled from ctx :room and DIR from ctx
  :sources-dir (else :results, else :results-dir). Its environment is the
  scenario's own: RESULTS_DIR is never inherited from the test run, and is
  set only when the scenario sets it (ctx :env). The run lands in ctx :run as
  {:exit :out :err :args}, :err being the error stream as one string.

  Context keys: :dir the project, :room what ROOM stands for, :env the
  variables the scenario sets, :run the last run, :aliases {written-path
  real-path} for the paths an Examples table names that stand for a fresh
  directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Running the command

(defn- scenario-env
  "The process environment for a run: the test run's, without RESULTS_DIR,
  plus what the scenario set (a nil value unsets it)."
  [ctx]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v)))
             (dissoc (into {} (System/getenv)) "RESULTS_DIR")
             (or (:env ctx) {})))

(defn- dir-arg [ctx]
  (or (:sources-dir ctx) (:results ctx) (:results-dir ctx)))

(defn- fill
  "The words of `line` with ROOM and DIR filled from the context."
  [ctx line]
  (vec (for [w (s/split-args line)]
         (case w
           "ROOM" (or (some-> (:room ctx) str) (s/fail! "ROOM stands for nothing: the scenario set no room"))
           "DIR" (or (some-> (dir-arg ctx) str) (s/fail! "DIR stands for nothing: the scenario set no directory"))
           w))))

(defn run!
  "Runs `rdm-spec args...` in `dir` with the scenario's environment."
  [ctx dir args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :env (scenario-env ctx)}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)}))

(defn run-line
  "Runs the command line `line` (without rdm-spec) in the scenario's project:
  ctx with :dir and :run."
  [ctx line]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        ctx (assoc ctx :dir dir)]
    (assoc ctx :run (run! ctx dir (fill ctx line)))))

(defn stdout-edn
  "The run's result: its standard output read as EDN (fails the step when it
  is not exactly one EDN value)."
  [r]
  (let [out (str/trim (str (:out r)))
        rdr (java.io.PushbackReader. (java.io.StringReader. out))
        v (try (edn/read {:eof ::eof} rdr)
               (catch Exception e (s/fail! (str "the standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
    (s/check! (not= ::eof v) (str "the standard output is empty\n" (s/show r)))
    (s/check! (= ::eof (edn/read {:eof ::eof} rdr))
              (str "the standard output holds more than the result\n" (s/show r)))
    v))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Fails unless the last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    r))

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) (str "the line gives --results: " line))
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (dir-arg ctx) "the scenario made no directory to stand for DIR")
    (run-line ctx line)))

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

;; ---------------------------------------------------------------------------
;; Projects

(defn- config-file [dir] (str (fs/file dir "dhf/harness/config.edn")))

(defn- read-config [dir] (edn/read-string (slurp (config-file dir))))

(defn- configure!
  "Sets `k` to `v` in the project's configuration."
  [dir k v]
  (spit (config-file dir) (pr-str (assoc (read-config dir) k v))))

(defn- project [ctx] (or (:dir ctx) (s/copy-of-room!)))

(defstep #"a project set up by rdm-spec"
  ;; rdm-spec's own project is one: a copy of the room's allow-list
  (fn [ctx] (assoc ctx :dir (project ctx))))

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (project ctx))))

(defn- passing-result
  "An Allure result file in `dir` for `id`, passed, its name marked `mark`."
  [dir id mark]
  (fs/create-dirs dir)
  (spit (str (fs/file dir (str mark "-result.json")))
        (json/generate-string {:uuid mark :name (str "read from " mark) :status "passed" :stage "finished"
                               :labels [{:name "spec" :value id}] :steps []})))

(def ^:private some-id "spec-0193")

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [dir (project ctx)]
      (configure! dir :results-dir rel)
      (passing-result (fs/file dir rel) some-id "configured")
      (assoc ctx :dir dir :configured-results rel))))

(defstep #"the configured results directory does not exist"
  ;; the project stays a copy of the room, so every task that reads results
  ;; (status, trace, publish) can run in it
  (fn [ctx]
    (let [dir (project ctx)
          rel (:results-dir (read-config dir))]
      (s/check! (string? rel) "the configuration names no results directory")
      (when (fs/exists? (fs/file dir rel)) (fs/delete-tree (fs/file dir rel)))
      (assoc ctx :dir dir :missing-results rel))))

(defn- alias-dir
  "A fresh directory standing for the absolute path `written`, holding a
  result: an Examples table may name a path, never one every run shares."
  [ctx written]
  (let [real (str (fs/file (s/tmp-dir) "results"))]
    (passing-result real some-id "env")
    (assoc-in ctx [:aliases written] real)))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc ctx :env (assoc (:env ctx) "RESULTS_DIR" nil))
      (let [ctx (if (fs/absolute? v) (alias-dir ctx v) ctx)
            real (get-in ctx [:aliases v] v)]
        (when-not (fs/absolute? v) (passing-result (fs/file (:dir ctx) v) some-id "env"))
        (assoc ctx :env (assoc (:env ctx) "RESULTS_DIR" real))))))

(defstep #"it reads (\S+)"
  ;; Which directory a task read shows in its result; a directory that does
  ;; not exist shows in the line saying it cannot run, which names it
  (fn [ctx written]
    (let [r (the-run ctx)
          want (get-in ctx [:aliases written] written)]
      (case (long (:exit r))
        0 (let [got (:results (stdout-edn r))]
            (s/check! (= want got) (str "it read " (pr-str got) ", not " (pr-str want) "\n" (s/show r))))
        2 (do (s/check! (not (fs/exists? (fs/file (:dir ctx) want)))
                        (str want " exists, yet the task could not run\n" (s/show r)))
              (s/check! (some #(and (str/starts-with? % "cannot-run: ")
                                    (str/includes? % (str "results directory " want " does not exist")))
                              (s/err-lines r))
                        (str "the task did not read " want "\n" (s/show r))))
        (s/fail! (str "expected exit 0 or 2\n" (s/show r))))
      nil)))

;; ---------------------------------------------------------------------------
;; The task list

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          listed (:tasks (stdout-edn r))
          entry (first (filter #(= task (:task %)) listed))]
      (s/check! entry (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= (str/trim usage) (:usage entry))
                (str "the usage of " task " is " (pr-str (:usage entry)) ", not " (pr-str usage)))
      (s/check! (= (str/trim does) (:does entry))
                (str task " is described as " (pr-str (:does entry)) ", not " (pr-str does))))))

(def ^:private every-task
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "fence" "run" "ratify" "checklist"
   "status" "trace" "release" "score" "scoreboard" "publish"])

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (exits! ctx 2)]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (str/includes? (:err r) "frobnicate") (str "frobnicate is not named\n" (s/show r)))
      (doseq [t every-task]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) (:err r)) (str "the task " t " is not listed\n" (s/show r)))))))

;; ---------------------------------------------------------------------------
;; How every task reports

(def ^:private finding-line
  "FILE:LINE: RULE: what, with FILE and LINE left out where none applies."
  #"^(?:[^:\s]+(?::\d+)?: )?[a-z][a-z0-9-]*: \S.*$")

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [r (exits! ctx 2)
          missing (or (:missing-results ctx) (s/fail! "the scenario named no missing directory"))]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % "cannot-run: ") (str/includes? % missing)) (s/err-lines r))
                (str "no cannot-run line names " missing "\n" (s/show r))))))

(def ^:private level-feature "dhf/spec/kernel/command.feature")

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (project ctx)
          f (str (fs/file dir level-feature))
          lines (str/split-lines (slurp f))
          i (or (first (keep-indexed (fn [i l] (when (re-find #"@spec-\d+ @contract\s*$" l) i)) lines))
                (s/fail! (str level-feature " holds no scenario tagged @contract")))
          changed (assoc lines i (str/replace (nth lines i) #" @contract" ""))]
      (spit f (str (str/join "\n" changed) "\n"))
      ;; the scenario's line is the one after its tags
      (assoc ctx :dir dir :finding {:file level-feature :line (+ i 2)}))))

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (remove #(or (str/includes? % "warning: ") (str/starts-with? % "event: ")) (s/err-lines r))
          {:keys [file line]} (:finding ctx)]
      (s/check! (seq lines) (str "the error stream names no finding\n" (s/show r)))
      (doseq [l lines]
        (s/check! (re-matches finding-line l) (str "not a finding line: " (pr-str l) "\n" (s/show r))))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": check-level: ")) lines)))
                (str "no one line names " file ":" line " with its rule\n" (s/show r))))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [v (stdout-edn (the-run ctx))]
      (s/check! (map? v) (str "the result is not a map: " (pr-str v))))))
