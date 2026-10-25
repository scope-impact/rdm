(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps every context's features use:

    When \"rdm-spec ...\" is run                 (also: with no --results;
                                               with that directory as DIR)
    Then it exits N
    Given a copy of rdm-spec's own project in which the configured results
          directory does not exist

  The When runs the command as its own process (steps.support/rdm-spec!'s
  way) in the scenario's :dir, a fresh copy of the room's allow-listed paths
  when no Given set one. ROOM stands for the scenario's :room (a path in a
  fresh directory when none is set), DIR for its :sources-dir when it has
  one. The command sees only the environment the scenario's steps set
  (:env), never RESULTS_DIR from the tests' own.

  Context keys: :dir the project, :room, :sources-dir, :env {var value},
  :run the last run {:exit :out :err :args}.

  Helpers for other step files: run-line, run!, exits!, stdout-edn."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Running the command

(defn- env-of [ctx]
  (merge (dissoc (into {} (System/getenv)) "RESULTS_DIR") (:env ctx)))

(defn run!
  "Runs `rdm-spec argv...` as its own process in `dir`, with the environment
  the scenario set (ctx :env) and no RESULTS_DIR otherwise: {:exit :out :err
  :args}."
  [ctx dir argv]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :env (env-of ctx)}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" argv)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec argv)}))

(defn split-line
  "The words of a command line, as a shell splits them: a quoted part, in
  single or double quotes, is one word without its quotes."
  [line]
  (vec (for [[_ sq dq w] (re-seq #"'([^']*)'|\"([^\"]*)\"|(\S+)" (str line))]
         (or sq dq w))))

(defn run-line
  "Runs the command line `line` (without rdm-spec) in the scenario's project:
  ctx with :dir, :run (and :room when ROOM was given)."
  [ctx line]
  (let [ctx (cond-> ctx
              (nil? (:dir ctx)) (assoc :dir (s/copy-of-room!))
              (and (re-find #"\bROOM\b" line) (nil? (:room ctx)))
              (assoc :room (str (fs/file (s/tmp-dir) "room"))))
        args (mapv (fn [a]
                     (cond (= "ROOM" a) (:room ctx)
                           (and (= "DIR" a) (:sources-dir ctx)) (str (:sources-dir ctx))
                           :else a))
                   (split-line line))]
    (assoc ctx :run (run! ctx (:dir ctx) args))))

(defn the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Fails unless the scenario's last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output read as one EDN value; fails when it is not."
  [r]
  (try (edn/read-string (:out r))
       (catch Exception e (s/fail! (str "the standard output is not EDN: " (ex-message e) "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Projects

(defn- set-config! [dir f]
  (let [file (fs/file dir "dhf/harness/config.edn")]
    (spit (str file) (pr-str (f (edn/read-string (slurp (str file))))))))

(defn- write-result!
  "Writes one passing Allure result labelled `id` into `dir`/`rel`."
  [dir rel id]
  (s/write! dir (str rel "/" (random-uuid) "-result.json")
            (str "{\"name\":\"a result\",\"status\":\"passed\",\"steps\":[],"
                 "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}")))

(defn- drop-level!
  "Removes the level tag of the first scenario of the room copy `dir`'s
  kernel/bus.feature: {:level-file rel}."
  [dir]
  (let [rel "dhf/spec/kernel/bus.feature"
        f (fs/file dir rel)
        text (slurp (str f))
        changed (str/replace-first text #"(@spec-\d+) @(?:e2e|contract)" "$1")]
    (s/check! (not= text changed) (str "no scenario of " rel " carries a level"))
    (spit (str f) changed)
    {:level-file rel}))

(defn- stale-view!
  "Makes the first view output of `dir` record another model hash: {:stale-view rel}."
  [dir]
  (let [vd (or (:views-dir (s/room-config)) "dhf/views")
        f (first (sort (map str (fs/glob (fs/file dir vd) "*.mmd"))))
        _ (s/check! f (str "the room has no view under " vd))
        text (slurp f)
        changed (str/replace-first text #"model-sha256: [0-9a-f]+" (str "model-sha256: " (apply str (repeat 64 "0"))))]
    (s/check! (not= text changed) (str f " records no model hash"))
    (spit f changed)
    {:stale-view (str vd "/" (fs/file-name f))}))

(defn- spec-tasks
  "The tasks the spec lists (command.feature, spec-0176's examples):
  [{:task :usage :does}]."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        parsed (gherkin/parse-feature "command.feature" (slurp f))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios parsed)))]
    (vec (for [c (gherkin/expand sc) :let [v (get-in c [:example :values])]]
           {:task (get v "task") :usage (get v "usage") :does (get v "does")}))))

;; ---------------------------------------------------------------------------
;; The shared When and Thens

(defstep #"\"rdm-spec ([^\"]*)\" is run(?: with no --results| with that directory as DIR)?"
  (fn [ctx line] (run-line ctx line)))

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) ctx))

;; One step text, one meaning: a copy of the room's allow-listed paths, and
;; no configured results directory in it (site, trace and status alike).
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          results (:results-dir (s/room-config))
          f (fs/file dir results)]
      (when (fs/exists? f) (fs/delete-tree f))
      (s/check! (not (fs/exists? f)) "the results directory exists")
      (assoc ctx :dir dir :missing results))))

;; ---------------------------------------------------------------------------
;; Everything runs through one command

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (assoc ctx :dir (s/project!))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          rows (:tasks (stdout-edn r))
          row (first (filter #(= task (:task %)) rows))]
      (s/check! row (str "help does not list the task " task "\n" (s/show r)))
      (s/check! (= usage (:usage row)) (str "the usage of " task " is " (pr-str (:usage row)) ", not " (pr-str usage)))
      (s/check! (= does (:does row)) (str task " is described as " (pr-str (:does row)) ", not " (pr-str does))))))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx dir-rel]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (set-config! dir #(assoc % :results-dir dir-rel))
      ;; each directory a run could read holds a result, so the run reads it
      ;; rather than refusing it
      (doseq [rel [dir-rel "other"]] (write-result! dir rel "spec-0001"))
      (assoc ctx :dir dir))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (update ctx :env dissoc "RESULTS_DIR")
      (assoc-in ctx [:env "RESULTS_DIR"] v))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (the-run ctx)]
      (case (:exit r)
        ;; a directory that holds results: the report says which it read
        (0 1) (s/check! (= dir (:results (stdout-edn r)))
                        (str "it read " (pr-str (:results (stdout-edn r))) ", not " dir "\n" (s/show r)))
        ;; a directory that does not exist (never made here: /tmp/r1 is
        ;; nobody's to write): the refusal names the directory it would read
        2 (s/check! (re-find (re-pattern (str "results directory " (java.util.regex.Pattern/quote dir) "\\b"))
                             (:err r))
                    (str "the refusal does not name " dir "\n" (s/show r)))
        (s/fail! (str "unexpected exit\n" (s/show r)))))))

;; ---------------------------------------------------------------------------
;; A gate stops the task

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge ctx {:dir dir} (drop-level! dir) (stale-view! dir)))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          lines (s/err-lines r)]
      (s/check! (some #(and (str/starts-with? % (str (:level-file ctx) ":")) (re-find #"level" %)) lines)
                (str "no finding names the missing level in " (:level-file ctx) "\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % (str (:stale-view ctx) ":")) (re-find #"stale" %)) lines)
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Every task reports the same way

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge ctx {:dir dir} (drop-level! dir)))))

(def ^:private line-forms
  "FILE:LINE: RULE: what, FILE: RULE: what, RULE: what, each optionally a
  warning; cannot-run: why; an event line."
  [#"[^\s:]+:\d+: (?:warning: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*"
   #"[^\s:]+: (?:warning: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*"
   #"(?:warning: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*"
   #"event: \S+ published-by \S+"])

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (s/err-lines r)]
      (s/check! (seq lines) (str "the error stream is empty\n" (s/show r)))
      (doseq [l lines]
        (s/check! (some #(re-matches % l) line-forms) (str "not a finding line: " (pr-str l) "\n" (s/show r))))
      (s/check! (some #(re-matches (re-pattern (str (java.util.regex.Pattern/quote (:level-file ctx))
                                                    ":\\d+: [a-z0-9-]+: .*level.*")) %)
                      lines)
                (str "no line FILE:LINE: RULE: names the missing level in " (:level-file ctx) "\n" (s/show r))))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          forms (try (vec (take-while #(not= ::eof %) (repeatedly #(edn/read {:eof ::eof} rdr))))
                     (catch Exception e (s/fail! (str "the standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
      (s/check! (= 1 (count forms)) (str "the standard output holds " (count forms) " EDN values, not one\n" (s/show r)))
      (s/check! (map? (first forms)) (str "the result is not a map\n" (s/show r))))))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [r (exits! ctx 2)
          missing (or (:missing ctx) (:results-dir (s/room-config)))]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (re-find (re-pattern (str "cannot-run: .*" (java.util.regex.Pattern/quote missing))) (:err r))
                (str "it does not name " missing "\n" (s/show r))))))

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx task]
    (let [r (exits! ctx 2)
          err (:err r)]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (str/includes? err task) (str "it does not name " task "\n" (s/show r)))
      (doseq [{t :task} (spec-tasks)]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) err) (str "it does not list the task " t "\n" (s/show r)))))))
