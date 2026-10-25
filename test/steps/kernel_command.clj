(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps every other step file may use.

  Shared step texts (defined here once, one meaning everywhere):
    When \"rdm-spec ARGS\" is run
         \"rdm-spec ARGS\" is run with no --results
         \"rdm-spec ARGS\" is run with that directory as DIR
    Then it exits N
    Given a copy of rdm-spec's own project in which the configured results
          directory does not exist

  The shared When runs the command under test as its own process
  (steps.support/rdm-spec!'s way: bb -cp src -m rdm-spec.kernel.command) in
  the scenario's :dir, a fresh copy of rdm-spec's own project when the
  scenario has none. Upper-case path placeholders in ARGS are new
  directories the scenario makes (dhf/spec/README.md): ROOM is ctx :room, DIR
  is ctx :sources-dir, any other (R1, ...) ctx [:paths NAME]; one the
  scenario has not made yet is made, empty, here. The run sees only the
  environment the scenario's steps set: the test process's own, without
  RESULTS_DIR, then ctx :env ({NAME value}, nil meaning unset).

  Context keys: :dir the project, :run the last run ({:exit :out :err
  :args}), :room, :sources-dir, :paths, :env, :markers ({dir id}: the one
  scenario id whose result a results directory the scenario made holds)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Running the command (the API other step files use)

(defn- base-env
  "The environment a scenario's command sees before its steps set anything:
  the test process's, never its RESULTS_DIR."
  []
  (dissoc (into {} (System/getenv)) "RESULTS_DIR"))

(defn run-env
  "The environment of the scenario `ctx`: the base, then ctx :env."
  [ctx]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v))))
             (base-env) (or (:env ctx) {})))

(defn run!
  "Runs `rdm-spec args...` as its own process in `dir`, in the scenario's
  environment. Returns {:exit :out :err :args}."
  [ctx dir args]
  (let [args (vec args)
        r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :env (run-env ctx)}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args args}))

(defn split-line
  "The words of a command line, a single-quoted part one word without its quotes."
  [line]
  (vec (for [[_ quoted word] (re-seq #"'([^']*)'|(\S+)" (str line))]
         (or quoted word))))

(def ^:private placeholder #"[A-Z][A-Z0-9]*")

(defn- placeholder-dir
  "ctx with the directory standing for `word` made when it is not yet:
  [ctx dir]."
  [ctx word]
  (let [k (case word "ROOM" [:room] "DIR" [:sources-dir] [:paths word])]
    (if-let [d (get-in ctx k)]
      [ctx d]
      (let [d (s/tmp-dir)] [(assoc-in ctx k d) d]))))

(defn substitute
  "ctx and the args of `line`, each upper-case placeholder replaced by its
  directory: [ctx args]."
  [ctx line]
  (reduce (fn [[c out] w]
            (if (re-matches placeholder w)
              (let [[c d] (placeholder-dir c w)] [c (conj out d)])
              [c (conj out w)]))
          [ctx []] (split-line line)))

(defn run-line
  "Runs the command line `line` (the words after rdm-spec) in the scenario's
  project, a fresh copy of rdm-spec's own when it has none: ctx with :dir
  and :run."
  [ctx line]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        [ctx args] (substitute ctx line)]
    (assoc ctx :run (run! ctx (:dir ctx) args))))

(defn the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Fails unless the scenario's run exited `n`; returns the run."
  [ctx n]
  (let [r (the-run ctx)]
    (s/check! (= n (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output read as one EDN value; fails when it is not."
  [r]
  (let [out (str (:out r))]
    (try (let [rdr (java.io.PushbackReader. (java.io.StringReader. out))
               v (edn/read {:eof ::eof} rdr)
               more (edn/read {:eof ::eof} rdr)]
           (when (or (= ::eof v) (not= ::eof more))
             (s/fail! (str "standard output is not one EDN value\n" (s/show r))))
           v)
         (catch clojure.lang.ExceptionInfo e (throw e))
         (catch Exception e
           (s/fail! (str "standard output is not EDN (" (ex-message e) ")\n" (s/show r)))))))

(defn cannot-run-lines [r] (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- write-config! [dir cfg] (s/write! dir "dhf/harness/config.edn" (pr-str cfg)))

(defn- write-marker!
  "Writes into `dir` one passing Allure result labelled spec=`id`."
  [dir id]
  (fs/create-dirs dir)
  (spit (str (fs/file dir (str "marker-" id "-result.json")))
        (json/generate-string {:uuid (str "marker-" id) :name (str "marker " id) :status "passed"
                               :steps [] :labels [{:name "spec" :value id}]})))

(defn- strip-level!
  "Removes the level tag of the first stamped scenario of the spec in `dir`:
  {:file rel :line n :id id}."
  [dir]
  (let [spec-dir (:spec-dir (config-of dir))
        hit (first (for [f (sort (map str (fs/glob (fs/file dir spec-dir) "**.feature")))
                         [i l] (map-indexed vector (str/split-lines (slurp f)))
                         :let [m (re-matches #"(\s*)@(spec-\d+) @(?:e2e|contract)\s*" l)]
                         :when m]
                     {:abs f :index i :indent (nth m 1) :id (nth m 2)}))]
    (s/check! hit "the copy's spec holds no stamped scenario with a level")
    (let [lines (vec (str/split-lines (slurp (:abs hit))))]
      (spit (:abs hit) (str (str/join "\n" (assoc lines (:index hit) (str (:indent hit) "@" (:id hit)))) "\n")))
    {:file (str (fs/relativize dir (:abs hit))) :line (+ 2 (:index hit)) :id (:id hit)}))

(defn- stale-view!
  "Makes the view `key` of the project `dir` record a model hash other than
  the model's: the view is older than the model. Returns its .mmd path."
  [dir key]
  (let [rel (str (str/replace (:views-dir (config-of dir) "dhf/views") #"/+$" "") "/" key ".mmd")
        f (fs/file dir rel)
        _ (s/check! (fs/exists? f) (str "the copy holds no view " rel))
        [first-line & more] (str/split-lines (slurp f))
        stale (str/replace first-line #"model-sha256: [0-9a-f]+" (str "model-sha256: " (apply str (repeat 64 "0"))))]
    (s/check! (not= stale first-line) (str rel " records no model hash"))
    (spit f (str (str/join "\n" (cons stale more)) "\n"))
    rel))

(defn- task-table
  "The tasks the spec lists ([task usage does]), read from spec-0176's
  examples, independently of the command."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %))
                          (:scenarios (gherkin/parse-feature "command.feature" (slurp f)))))]
    (vec (for [c (gherkin/expand sc)
               :let [v (get-in c [:example :values])]]
           [(get v "task") (get v "usage") (get v "does")]))))

;; ---------------------------------------------------------------------------
;; Shared steps

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (some #{"--results"} (split-line line))) (str "the line gives --results: " line))
    (run-line (update ctx :env assoc "RESULTS_DIR" nil) line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no directory was given to stand for DIR")
    (run-line ctx line)))

(defstep #"it exits (\d+)"
  (fn [ctx n] (exits! ctx (parse-long n)) nil))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (:results-dir (config-of dir))]
      (s/check! (string? rd) "the room configures no results directory")
      (when (fs/exists? (fs/file dir rd)) (fs/delete-tree (fs/file dir rd)))
      (assoc ctx :dir dir :missing-results rd))))

;; ---------------------------------------------------------------------------
;; Everything runs through one command

(defstep #"a project set up by rdm-spec"
  (fn [ctx]
    (let [parent (s/tmp-dir)
          r (s/rdm-spec! parent "init" "project")]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          listed (:tasks (stdout-edn r))
          row (first (filter #(= task (:task %)) listed))]
      (s/check! (sequential? listed) (str "the result lists no :tasks\n" (s/show r)))
      (s/check! row (str "help does not list the task " task "\n" (s/show r)))
      (s/check! (= usage (:usage row)) (str task "'s usage is " (pr-str (:usage row)) ", not " (pr-str usage)))
      (s/check! (= does (:does row)) (str task " is described as " (pr-str (:does row)) ", not " (pr-str does)))
      (s/check! (= (map first (task-table)) (map :task listed))
                (str "help lists the tasks " (pr-str (map :task listed)) ", not the spec's "
                     (pr-str (map first (task-table))))))))

;; ---------------------------------------------------------------------------
;; A task's results directory

(def ^:private markers
  "The scenario whose passing result each results directory a scenario makes
  holds: real ids of rdm-spec's own spec, one per directory."
  {:configured "spec-0193" :env "spec-0219"})

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rd]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (write-config! dir (assoc (config-of dir) :results-dir rd))
      (when (fs/exists? (fs/file dir rd)) (fs/delete-tree (fs/file dir rd)))
      (write-marker! (fs/file dir rd) (:configured markers))
      (-> ctx (assoc :dir dir) (assoc-in [:markers rd] (:configured markers))))))

(defstep #"the environment variable (\S+) is (\S+)"
  (fn [ctx var value]
    (if (= "unset" value)
      (assoc-in ctx [:env var] nil)
      (let [_ (s/check! (re-matches placeholder value) (str value " is not a directory the scenario makes"))
            [ctx d] (placeholder-dir ctx value)]
        (when (= "RESULTS_DIR" var) (write-marker! d (:env markers)))
        (cond-> (assoc-in ctx [:env var] d)
          (= "RESULTS_DIR" var) (assoc-in [:markers d] (:env markers)))))))

(defn- resolve-word
  "What `word` names in the scenario: a placeholder's directory, else itself."
  [ctx word]
  (if (re-matches placeholder word)
    (or (case word "ROOM" (:room ctx) "DIR" (:sources-dir ctx) (get-in ctx [:paths word]))
        (s/fail! (str word " names no directory the scenario made")))
    word))

(defstep #"it reads (\S+)"
  (fn [ctx word]
    (let [r (the-run ctx)
          want (resolve-word ctx word)
          others (remove #{want} (keys (:markers ctx)))]
      (if (= 2 (:exit r))
        (let [lines (cannot-run-lines r)]
          (s/check! (some #(str/includes? % (str "results directory " want)) lines)
                    (str "the run reads no results directory " want "\n" (s/show r)))
          (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r))))
        (let [out (stdout-edn r)
              verified (set (get-in out [:by-status :verified]))]
          (s/check! (= want (:results out)) (str "the run read " (pr-str (:results out)) ", not " want "\n" (s/show r)))
          (when-let [id (get-in ctx [:markers want])]
            (s/check! (contains? verified id) (str id ", whose result is in " want ", is not verified\n" (s/show r))))
          (doseq [o others :let [id (get-in ctx [:markers o])] :when (not= id (get-in ctx [:markers want]))]
            (s/check! (not (contains? verified id)) (str id ", whose result is only in " o ", is verified")))))
      nil)))

;; ---------------------------------------------------------------------------
;; A gate stops the task; a task with no gate reports every stage

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          stripped (strip-level! dir)
          view (stale-view! dir "context")]
      (assoc ctx :dir dir :stripped stripped :stale-view view))))

(defn- finding-lines [r]
  (remove #(or (str/starts-with? % "event: ") (str/starts-with? % "cannot-run: ")
               (re-find #"(^|: )warning: " %))
          (s/err-lines r)))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          {:keys [file line]} (:stripped ctx)
          lines (finding-lines r)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % (str (:stale-view ctx) ":")) (str/includes? % "view-stale")) lines)
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Every task reports the same way

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :stripped (strip-level! dir)))))

(def ^:private finding-line
  "FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what; RULE kebab-case."
  #"(?:[^\s:][^:]*:(?:\d+:)? )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*")

(def ^:private warning-line
  #"(?:[^\s:][^:]*:(?:\d+:)? )?warning: [a-z0-9]+(?:-[a-z0-9]+)*: \S.*")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (s/err-lines r)
          kinds (for [l lines]
                  (cond (re-matches #"event: \S+ published-by \S+" l) :event
                        (re-matches warning-line l) :warning
                        (re-matches finding-line l) :finding
                        :else (s/fail! (str "a line of the error stream is no finding, warning or event: "
                                            (pr-str l) "\n" (s/show r)))))
          {:keys [file line]} (:stripped ctx)]
      (s/check! (= kinds (sort-by {:finding 0 :warning 1 :event 2} kinds))
                (str "findings, then warnings, then events: the error stream is out of order\n" (s/show r)))
      (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)
                (str "no line names the scenario's file and line, " file ":" line "\n" (s/show r))))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (map? (stdout-edn r)) (str "the result is not an EDN map\n" (s/show r))))))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [r (exits! ctx 2)
          missing (or (:missing-results ctx) (s/fail! "the scenario names no missing directory"))]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (some #(str/includes? % missing) (cannot-run-lines r))
                (str "no cannot-run line names " missing "\n" (s/show r))))))

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx word]
    (let [r (exits! ctx 2)
          lines (cannot-run-lines r)
          line (first (filter #(str/includes? % word) lines))]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! line (str "no cannot-run line names " word "\n" (s/show r)))
      (doseq [t (map first (task-table))]
        (s/check! (re-find (re-pattern (str "(?<![\\w-])" t "(?![\\w-])")) line)
                  (str "the refusal does not list the task " t "\n" (s/show r)))))))
