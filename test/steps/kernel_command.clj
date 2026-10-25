(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and every step text
  the features of more than one bounded context share (dhf/spec/README.md,
  \"Step definitions are the scope's own\"). Each is defined once, here, when
  this file loads; another step file uses these steps and helpers, and reads
  only the context keys named beside each shared step below (\"A shared
  step's context keys are the kernel's\").

  The command runs as its own process (`bb -cp <room>/src -m
  rdm-spec.kernel.command ...`) in the scenario's project, never the room,
  with the tests' environment, PATH and HOME included, except RESULTS_DIR,
  plus what the scenario's steps set (:env).

  Context keys of the kernel (set by the shared steps, read by any step file):
    :dir           the scenario's project: a fixture, never the room
    :run           the last run, {:exit :out :err :args}
    :env           {NAME value} the steps set for the command's environment
    :placeholders  {\"DIR\" path, \"OUT\" path, ...} what each upper-case
                   placeholder of a command line stood for
    :room          what ROOM stood for: a new directory the scenario names
    :results       a results directory a Given made (DIR in \"... is run with
                   that results directory as DIR\")
    :spec-before   {path text} of every file under the project's dhf/spec,
                   as spec-snapshot takes it, before the When
    :page :citation :footnote   the wiki page a shared Given wrote (path
                   relative to the project), its one citation and footnote
    :stack-word    the stack word a shared Given configured

  Keys the kernel's steps read but never set: :sources-dir (DIR, for a
  scenario whose Given gave checkouts with --sources)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures: every directory a step makes, removed when the suite ends

;; One directory holds every fixture of the suite's process; it is removed
;; when the process ends, whatever the outcome.
(defonce ^:private suite-root
  (delay
    (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-suite-"}))]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn [] (try (fs/delete-tree d) (catch Throwable _ nil)))))
      d)))

(defn tmp-dir
  "A new, empty directory for a fixture, inside the suite's own directory,
  which is removed when the suite's process ends (a suite removes every
  fixture it makes)."
  []
  (str (fs/create-temp-dir {:dir @suite-root :prefix "rdm-spec-test-"})))

;; steps.support/tmp-dir makes the other step files' fixtures: they go to the
;; suite's directory too, so the suite removes them, even after the runner
;; loads that file again.
(def ^:private routed-tmp-dir (with-meta (fn [] (tmp-dir)) {::routed true}))

(defn- route! [v] (alter-var-root v (constantly routed-tmp-dir)))

(add-watch #'s/tmp-dir ::route (fn [_ v _ now] (when-not (::routed (meta now)) (route! v))))
(route! #'s/tmp-dir)

(defn spec-snapshot
  "{relative path text} of every file under dhf/spec of `dir`: the shape of
  :spec-before."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn without-oracle!
  "Drops :oracle from the configuration of the project `dir`: a fixture
  never carries the room's oracle unless its scenario sets one."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (when (fs/exists? f)
      (let [text (slurp (str f))]
        (when (:oracle (edn/read-string text))
          (spit (str f) (str (pr-str (dissoc (edn/read-string text) :oracle)) "\n")))))
    dir))

(defn copy-of-room!
  "A fixture holding a copy of the room's allow-listed paths (the spec, the
  harness configuration, the raw sources, the wiki, the views, the ADR), its
  configuration naming no :oracle."
  []
  (let [dir (tmp-dir)]
    (doseq [a (get-in (s/room-config) [:clean-room :allow])
            :let [src (fs/file s/room-root a)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/file dir a))
        (do (fs/create-dirs (fs/parent (fs/file dir a)))
            (fs/copy src (fs/file dir a)))))
    (without-oracle! dir)))

;; ---------------------------------------------------------------------------
;; Running the command

(defn- command-env
  "The tests' environment without RESULTS_DIR, plus `extra`."
  [extra]
  (merge (into {} (remove (fn [[k _]] (= "RESULTS_DIR" k))) (System/getenv))
         extra))

(defn run!
  "Runs `rdm-spec args...` as its own process in `dir`: {:exit :out :err :args}."
  [ctx dir args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :env (command-env (:env ctx))}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)}))

(def path-placeholder
  "The upper-case words a command line uses as paths the scenario makes
  (dhf/spec/README.md: ROOM, and any other upper-case path placeholder):
  ROOM, DIR and OUT, each perhaps numbered (OUT2). Another upper-case word
  (UN, as an id prefix) is itself."
  #"(?:ROOM|DIR|OUT)\d*")

(defn- placeholder
  "ctx with what the placeholder `ph` stands for in :placeholders."
  [ctx ph]
  (if (get-in ctx [:placeholders ph])
    ctx
    (let [v (case ph
              "ROOM" (or (:room ctx) (str (fs/file (tmp-dir) "room")))
              "DIR" (or (:results ctx) (:sources-dir ctx) (str (fs/file (tmp-dir) "dir")))
              (str (fs/file (tmp-dir) (str/lower-case ph))))]
      (cond-> (assoc-in ctx [:placeholders ph] v)
        (= "ROOM" ph) (assoc :room v)))))

(defn- placeholder-of
  "The placeholder `word` starts with (ROOM, or DIR in DIR/sauna), or nil."
  [ctx word]
  (let [[_ ph] (re-matches #"([A-Z][A-Z0-9]+)(?:/.*)?" word)]
    (when (and ph (or (re-matches path-placeholder ph) (contains? (:placeholders ctx) ph))) ph)))

(defn words-of
  "The words of a command line, as a shell splits it: on white space, a
  '...' or \"...\" being one word without its quotes."
  [line]
  (mapv (fn [[_ sq dq w]] (or sq dq w))
        (re-seq #"'([^']*)'|\"([^\"]*)\"|(\S+)" (str line))))

(defn run-line
  "Runs the command line `line` (the words after rdm-spec) in the scenario's
  project (a copy of the room when no Given made one), each path placeholder
  (ROOM, DIR, OUT, OUT2, or one :placeholders names) standing for what
  :placeholders names, else a new path the scenario makes. Returns ctx with
  :run."
  [ctx line]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (copy-of-room!)))
        words (words-of line)
        ctx (reduce placeholder ctx (keep #(placeholder-of ctx %) words))
        args (mapv (fn [w] (if-let [ph (placeholder-of ctx w)]
                             (str (get-in ctx [:placeholders ph]) (subs w (count ph)))
                             w))
                   words)]
    (assoc ctx :run (run! ctx (:dir ctx) args))))

(defn the-run
  "The last run, or the step fails."
  [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "The last run, having exited `code`, or the step fails."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output read as one EDN value, or the step fails."
  [r]
  (let [v (try (edn/read-string (:out r)) (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e)
                                                                           "\n" (s/show r)))))]
    (s/check! (map? v) (str "standard output is not an EDN map\n" (s/show r)))
    v))

(defn cannot-run-lines [r] (filterv #(str/starts-with? % "cannot-run: ") (s/err-lines r)))

(defn refused-naming!
  "The last run exited 2, printed nothing on standard output and a
  cannot-run line naming each of `words`; returns the run."
  [ctx & words]
  (let [r (exits! ctx 2)]
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) words)) (cannot-run-lines r))
              (str "no cannot-run line names " (str/join ", " words) "\n" (s/show r)))
    r))

(def ^:private event-line #"event: \S+ published-by \S+")

(def finding-line
  "FILE:LINE: RULE: what, FILE: RULE: what, RULE: what, a warning, or the
  same with warning: before the rule."
  #"(?:([^\s:][^:]*?)(?::(\d+))?: )?(?:warning: )?([a-z][a-z0-9]*(?:-[a-z0-9]+)*): \S.*")

(defn finding-lines
  "The run's finding lines: no warning, no event."
  [r]
  (vec (remove #(or (re-matches event-line %) (re-find #"(^|: )warning: " %)) (s/err-lines r))))

;; ---------------------------------------------------------------------------
;; Shared: the When. Sets :run, :dir (a copy of the room when no Given made
;; a project), :placeholders and :room; reads :dir, :env, :placeholders,
;; :room, :results and :sources-dir.

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) (str "the command line gives --results: " line))
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that (results )?directory as DIR"
  (fn [ctx line results?]
    (let [dir (if results? (:results ctx) (or (:results ctx) (:sources-dir ctx)))]
      (s/check! dir "no Given made the directory DIR stands for")
      (run-line (assoc-in ctx [:placeholders "DIR"] dir) line))))

;; ---------------------------------------------------------------------------
;; Shared: what a run did. Read :run; set nothing.

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

(defstep #"it passes"
  (fn [ctx]
    (let [r (exits! ctx 0)]
      (s/check! (empty? (finding-lines r)) (str "a passing run named a finding\n" (s/show r)))
      nil)))

;; Shared: reads :dir and :spec-before, which the scenario's Given set as
;; spec-snapshot takes it.
(defstep #"no file in the spec changes"
  (fn [ctx]
    (s/check! (:spec-before ctx) "no snapshot of the spec was taken")
    (s/check! (= (:spec-before ctx) (spec-snapshot (:dir ctx))) "a file under dhf/spec changed")
    nil))

;; ---------------------------------------------------------------------------
;; Shared Givens

(defn- results-dir-of [dir] (:results-dir (config-of dir)))

;; Sets :dir: a copy of the room whose configured results directory is gone.
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (copy-of-room!)
          rd (fs/file dir (results-dir-of dir))]
      (when (fs/exists? rd) (fs/delete-tree rd))
      (s/check! (not (fs/exists? rd)) "the results directory exists")
      (assoc ctx :dir dir))))

(defn wiki-page!
  "Writes a wiki page into the project of ctx (a copy of the room when it
  has none), listed in the wiki's index, its one footnote citing
  `citation`: ctx with :dir, :page, :citation and :footnote."
  [ctx citation]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (copy-of-room!)))
        dir (:dir ctx)
        wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
        rel "pages/footnoted.md"
        index (fs/file dir wiki "index.md")]
    (s/write! dir (str wiki "/" rel)
              (str "# A footnoted page\n\n"
                   "The code is built from the spec, and can be deleted and built again.[^1]\n\n"
                   "[^1]: `" citation "`, \"What to steal\" 1.\n"))
    (if (fs/exists? index)
      (spit (str index) (str "\n- [A footnoted page](" rel ")\n") :append true)
      (s/write! dir (str wiki "/index.md") (str "# Wiki\n\n- [A footnoted page](" rel ")\n")))
    (assoc ctx :page (str wiki "/" rel) :citation citation :footnote "[^1]")))

;; Sets :dir (kept when a Given made one), :page, :citation, :footnote.
(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation] (wiki-page! ctx citation)))

;; Sets :dir (kept when a Given made one) and :stack-word.
(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (copy-of-room!)))
          c (config-of (:dir ctx))]
      (s/write! (:dir ctx) "dhf/harness/config.edn"
                (str (pr-str (update c :stack-words #(vec (distinct (conj (vec %) word))))) "\n"))
      (assoc ctx :stack-word word))))

;; Sets :dir: a project `rdm-spec init` wrote.
(defn project-set-up!
  "ctx with :dir, a new project `rdm-spec init` wrote."
  [ctx]
  (let [parent (tmp-dir)
        r (run! ctx parent ["init" "project"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
    (assoc ctx :dir (str (fs/file parent "project")))))

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature: everything runs through one command

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          tasks (:tasks (stdout-edn r))
          t (first (filter #(= task (:task %)) tasks))]
      (s/check! (vector? tasks) (str "the result holds no :tasks\n" (s/show r)))
      (s/check! t (str "help does not list " task "\n" (s/show r)))
      (s/check! (= (str/trim usage) (:usage t)) (str task "'s usage is " (pr-str (:usage t))))
      (s/check! (= (str/trim does) (:does t)) (str task " is described as " (pr-str (:does t))))
      nil)))

(defn- result-file! [dir id]
  (let [uuid (str (random-uuid))]
    (s/write! dir (str uuid "-result.json")
              (str "{\"uuid\":\"" uuid "\",\"name\":\"a test\",\"status\":\"passed\","
                   "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [dir (copy-of-room!)]
      (s/check! (= rel (results-dir-of dir)) (str "the configured results directory is " (results-dir-of dir)))
      ;; every directory a row may read holds a result, so the task reads it
      (doseq [d [rel "R1" "other"]] (result-file! (fs/file dir d) "spec-0001"))
      (assoc ctx :dir dir))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (update ctx :env dissoc "RESULTS_DIR")
      (assoc-in ctx [:env "RESULTS_DIR"] v))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (exits! ctx 0)
          out (stdout-edn r)]
      (s/check! (= dir (:results out)) (str "it read " (pr-str (:results out)) ", not " dir "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A gate stops the task; every other stage reports

(defn- drop-level!
  "Removes the level tag of the first scenario of kernel/command.feature in
  `dir`: {:file :line} of that scenario."
  [dir]
  (let [rel "dhf/spec/kernel/command.feature"
        f (fs/file dir rel)
        lines (str/split-lines (slurp (str f)))
        i (first (keep-indexed (fn [i l] (when (re-find #"^\s*@spec-\d+ @(e2e|contract)\s*$" l) i)) lines))
        _ (s/check! i (str rel " holds no tag line with a level"))
        changed (assoc (vec lines) i (str/replace (nth lines i) #" @(e2e|contract)" ""))]
    (spit (str f) (str (str/join "\n" changed) "\n"))
    {:file rel :line (+ i 2)}))

(defn- stale-view!
  "Makes the view containers record another model hash in `dir`: its path."
  [dir]
  (let [rel (str (or (:views-dir (config-of dir)) "dhf/views") "/containers.mmd")
        f (fs/file dir rel)
        text (slurp (str f))
        changed (str/replace-first text #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " (apply str (repeat 64 "0"))))]
    (s/check! (not= text changed) (str rel " records no model hash"))
    (spit (str f) changed)
    rel))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (copy-of-room!)
          {:keys [file line]} (drop-level! dir)]
      (assoc ctx :dir dir :no-level [file line] :stale (stale-view! dir)))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          [file line] (:no-level ctx)
          lines (finding-lines r)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (:stale ctx))) lines)
                (str "no finding names the stale view " (:stale ctx) "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Every task reports the same way

(defstep #"a spec that passes every check"
  (fn [ctx]
    (let [ctx (project-set-up! ctx)
          v (run! ctx (:dir ctx) ["views"])]
      (s/check! (zero? (:exit v)) (str "rdm-spec views failed\n" (s/show v)))
      ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (copy-of-room!)
          {:keys [file line]} (drop-level! dir)]
      (assoc ctx :dir dir :no-level [file line]))))

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          [file line] (:no-level ctx)
          lines (remove #(re-matches event-line %) (s/err-lines r))]
      (s/check! (seq lines) (str "the error stream is empty\n" (s/show r)))
      (doseq [l lines]
        (s/check! (re-matches finding-line l) (str "not a finding line: " l "\n" (s/show r))))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)))
                (str "not exactly one line names the missing level at " file ":" line "\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          v (stdout-edn r)
          rest-text (try (let [rdr (java.io.PushbackReader. (java.io.StringReader. (:out r)))]
                           (edn/read rdr)
                           (edn/read {:eof ::eof} rdr))
                         (catch Exception e e))]
      (s/check! (map? v) "standard output is not a map")
      (s/check! (= ::eof rest-text) (str "standard output holds more than one value\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (refused-naming! ctx (results-dir-of (:dir ctx))) nil))

(def task-names
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify" "checklist"
   "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit"])

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")
          line (first (filter #(str/includes? % "frobnicate") (cannot-run-lines r)))]
      (doseq [t task-names]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) (str/replace line "frobnicate" ""))
                  (str "the refusal does not list " t ": " line)))
      nil)))
