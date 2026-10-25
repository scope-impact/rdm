(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps: every step text the features of more than one bounded
  context use (dhf/spec/README.md, \"Step definitions are the scope's own\").

  Running the command. `run!` and `run-line` run `rdm-spec` as its own
  process (the implementation under test on its classpath) in the scenario's
  project. Its environment is the tests' own, PATH and HOME included, except
  RESULTS_DIR, plus what the scenario's steps set in the context's :env
  ({name value}, a nil value unsetting the name): ORACLE_LAYOUT set by a step
  reaches the command (dhf/spec/README.md, \"A scenario's commands inherit
  the tests' environment\").

  Placeholders in a command line (dhf/spec/README.md, \"ROOM in a step is a
  new directory the scenario makes for itself\"): a word the context's
  :placeholders names stands for its path; else ROOM stands for the
  context's :room, or a new path; DIR for the directory the option before it
  takes (--results: :results, --sources: :sources-dir); OUT and OUT2 for new
  paths; a placeholder may begin a path (DIR/sauna). Each path used is kept
  in :placeholders, ROOM also as :room.

  The shared steps (each text owned here, defined once when this file
  loads):
    \"rdm-spec ...\" is run [with that results directory as DIR |
      with that directory as DIR | with no --results]
    it exits N / it passes
    no file in the spec changes
    the configured stack words include \"W\"
    a copy of rdm-spec's own project in which the configured results
      directory does not exist
    a rendered view whose recorded hash differs from the model's
    a wiki page whose footnote cites \"C\"
    dhf/cycles/03/cycle.edn names the cut spec-3[ and the scope S]
    the spec at cut spec-3 holds a scenario with no id
  Some of these are also defined, with the same meaning, in the step files
  of a context that used them first. The kernel owns them: a definition of
  one of the kernel's step patterns from another file is dropped from the
  registry (`own-shared-steps!`), so a step is never matched twice.

  Context keys: :dir the project, :run the last run ({:exit :out :err
  :args}), :env, :placeholders, :room, :results, :sources-dir."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers (a contract for the step files that use them)

(defn tmp-dir
  "A new temporary directory (a fixture; the suite removes it)."
  []
  (s/tmp-dir))

(defn the-run
  "The scenario's last run, or a failure when no command was run."
  [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn stdout-edn
  "The run's standard output read as one EDN value, or nil."
  [run]
  (try (let [out (str/trim (str (:out run)))]
         (when-not (str/blank? out) (edn/read-string out)))
       (catch Exception _ nil)))

(defn exits!
  "Checks the last run exited `n`; returns the run."
  [ctx n]
  (let [r (the-run ctx)
        n (if (string? n) (parse-long n) n)]
    (s/check! (= n (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn refused-naming!
  "Checks the last run could not run (exit 2), printed nothing on standard
  output, and that its error stream names every one of `parts`."
  [ctx & parts]
  (let [r (exits! ctx 2)
        err (str (:err r))]
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
    (doseq [part parts]
      (s/check! (str/includes? err (str part)) (str "the error stream does not name " (pr-str part) "\n" (s/show r))))
    r))

(defn spec-snapshot
  "{relative path content} of every file under dhf/spec of `dir`."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn command-env
  "The environment a scenario's command runs with: the tests' own without
  RESULTS_DIR, plus the context's :env (a nil value unsets the name)."
  [ctx]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v))))
             (dissoc (into {} (System/getenv)) "RESULTS_DIR")
             (or (:env ctx) {})))

(defn run!
  "Runs `rdm-spec args...` in `dir` with the scenario's environment:
  {:exit :out :err :args}."
  [ctx dir args]
  (let [r (apply p/shell {:dir (str dir) :env (command-env ctx) :out :string :err :string
                          :continue true :in ""}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)}))

(defn split-line
  "The words of a command line, as a shell splits them: quotes group words
  and are removed."
  [line]
  (let [out (atom []) cur (StringBuilder.) quote (atom nil) any (atom false)]
    (doseq [c (str line)]
      (cond
        @quote (if (= c @quote) (reset! quote nil) (.append cur c))
        (#{\' \"} c) (do (reset! quote c) (reset! any true))
        (Character/isWhitespace c) (when (or @any (pos? (.length cur)))
                                     (swap! out conj (str cur)) (.setLength cur 0) (reset! any false))
        :else (.append cur c)))
    (when (or @any (pos? (.length cur))) (swap! out conj (str cur)))
    @out))

(defn- placeholder
  "The path `word` stands for in `ctx` (after the word `before`), and ctx
  with it kept; nil when `word` is no placeholder."
  [ctx word before]
  (let [ph (:placeholders ctx)
        keep* (fn [ctx v] [v (cond-> (assoc-in ctx [:placeholders word] v) (= "ROOM" word) (assoc :room v))])]
    (cond
      (contains? ph word) [(get ph word) ctx]
      (= "ROOM" word) (keep* ctx (or (:room ctx) (str (fs/file (tmp-dir) "room"))))
      (= "DIR" word) (let [v (case before
                               "--results" (or (:results ctx) (:sources-dir ctx))
                               "--sources" (or (:sources-dir ctx) (:results ctx))
                               (or (:sources-dir ctx) (:results ctx)))]
                       (s/check! v "no directory was given for DIR")
                       (keep* ctx (str v)))
      (#{"OUT" "OUT2"} word) (keep* ctx (str (fs/file (tmp-dir) (str/lower-case word))))
      :else nil)))

(defn- substitute
  "`word` with its placeholder replaced (a whole word, or the first part of
  a path such as DIR/sauna), and ctx; nil when it holds none."
  [ctx word before]
  (or (placeholder ctx word before)
      (let [[head more] (str/split word #"/" 2)]
        (when (and more (re-matches #"[A-Z][A-Z0-9]*" head))
          (when-let [[v ctx'] (placeholder ctx head before)]
            [(str (fs/file v more)) ctx'])))))

(defn run-line
  "Runs the command line `line` (without \"rdm-spec\") in the scenario's
  project, a copy of the room's allow-list when it has none, its
  placeholders replaced: ctx with :dir and :run."
  [ctx line]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        words (split-line line)
        [args ctx] (reduce (fn [[args ctx] [before word]]
                             (if-let [[v ctx'] (substitute ctx word before)]
                               [(conj args v) ctx']
                               [(conj args word) ctx]))
                           [[] ctx]
                           (map vector (cons nil words) words))]
    (assoc ctx :run (run! ctx (:dir ctx) args))))

(defn err-text [run] (str (:err run)))

;; ---------------------------------------------------------------------------
;; The kernel owns its shared steps

(def ^:private this-file (str (fs/canonicalize (fs/file s/room-root "test/steps/kernel_command.clj"))))

(def ^:private owned (atom #{}))

(defmacro ^:private defshared
  "defstep, for a step the kernel owns: a definition of the same pattern
  from another step file is dropped."
  [pattern f]
  `(do (swap! owned conj (str ~pattern))
       (runner/register! ~pattern ~f ~(str *file*) ~(:line (meta &form)))))

(defn- drop-foreign
  "The registry without other files' definitions of the kernel's patterns,
  when the kernel defines them."
  [defs]
  (let [ours (set (for [d defs :when (= this-file (:file d))] (str (:pattern d))))]
    (vec (remove #(and (not= this-file (:file %))
                       (contains? @owned (str (:pattern %)))
                       (contains? ours (str (:pattern %))))
                 defs))))

(defn- own-shared-steps!
  "Keeps the registry free of other files' definitions of the kernel's
  patterns, now and as later step files load."
  []
  (add-watch runner/*registry* ::kernel-owns
             (fn [_ reg old new]
               (when (not= old new)
                 (let [kept (drop-foreign new)]
                   (when (not= (count kept) (count new))
                     (reset! reg kept))))))
  (swap! runner/*registry* drop-foreign))

;; ---------------------------------------------------------------------------
;; Shared Whens

(defshared #"\"rdm-spec ([^\"]*)\" is run(?: with that (?:results )?directory as DIR)?"
  (fn [ctx line] (run-line ctx line)))

(defshared #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (re-find #"--results" line)) "the command line gives --results")
    (run-line (assoc-in ctx [:env "RESULTS_DIR"] nil) line)))

;; ---------------------------------------------------------------------------
;; Shared Thens

(defshared #"it exits (\d+)"
  (fn [ctx n] (exits! ctx n) nil))

(defshared #"it exits 2 and names (\S+)"
  (fn [ctx what] (refused-naming! ctx what) nil))

(defshared #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defshared #"no file in the spec changes"
  (fn [ctx]
    (s/check! (:spec-before ctx) "no snapshot of the spec was taken")
    (s/check! (= (:spec-before ctx) (spec-snapshot (:dir ctx))) "a file under dhf/spec changed")
    nil))

;; ---------------------------------------------------------------------------
;; Shared Givens

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- write-config! [dir c] (spit (str (fs/file dir "dhf/harness/config.edn")) (pr-str c)))

(defshared #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [ctx (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))
          c (config-of (:dir ctx))]
      (write-config! (:dir ctx) (update c :stack-words #(vec (distinct (conj (vec %) word)))))
      (assoc ctx :stack-word word))))

(defshared #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (:results-dir (config-of dir))]
      (s/check! rd "the configuration names no results directory")
      (fs/delete-tree (fs/file dir rd))
      (-> ctx
          (assoc :dir dir :missing-results rd)
          (assoc-in [:env "RESULTS_DIR"] nil)))))

(defshared #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          _ (when (s/built? 'rdm-spec.architecture.views)
              (let [r (run! ctx dir ["views"])]
                (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))))
          k "containers"
          other (apply str (repeat 64 "0"))
          vd (fs/file dir (or (:views-dir (config-of dir)) "dhf/views"))]
      (doseq [ext ["mmd" "svg"]
              :let [f (str (fs/file vd (str k "." ext)))
                    [first-line & more] (str/split-lines (slurp f))
                    changed (str/replace first-line #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " other))]]
        (s/check! (not= first-line changed) (str f " records no model hash: " first-line))
        (spit f (str (str/join "\n" (cons changed more)) "\n")))
      (assoc ctx :dir dir :stale-view k))))

(def ^:private footnoted
  "A paragraph a wiki page footnotes."
  "The code is built from the spec, and can be deleted and built again.")

(defshared #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wd (or (:wiki-dir (config-of dir)) "dhf/wiki")
          rel "pages/regeneration.md"
          index (fs/file dir wd "index.md")]
      (s/write! dir (str wd "/" rel)
                (str "# Regeneration\n\n" footnoted "[^1]\n\n[^1]: `" citation "`, \"What to steal\" 1.\n"))
      (if (fs/regular-file? index)
        (spit (str index) (str "\n- [Regeneration](" rel "): what a regeneration measures.\n") :append true)
        (s/write! dir (str wd "/index.md") (str "# The wiki\n\n- [Regeneration](" rel ")\n")))
      (assoc ctx :dir dir :page (str wd "/" rel) :wiki-file rel :citation citation :footnote "[^1]"))))

;; A cycle project (the measurement and ratification contexts' features): a
;; throwaway git repository holding a copy of the room's allow-list with every
;; feature file removed and the fixture's own written instead; its tag spec-3
;; is the cut, and dhf/cycles/03/cycle.edn is written after the cut.

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(def ^:private default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def ^:private default-cycle
  {:cut "spec-3"
   :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- scenario-text [{:keys [id title level wip? no-id?]}]
  (str "\n  " (str/join " " (remove nil? [(when-not no-id? (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn- feature-text [scenarios]
  (str "# A fixture feature file of the measurement steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- cut! [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid"))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "the cut " tag))
  (git! dir "tag" "-f" tag)
  dir)

(defn- write-cycle! [dir cycle overrides]
  (let [m (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) default-cycle overrides)]
    (s/write! dir (str "dhf/cycles/" cycle "/cycle.edn") (pr-str m))
    m))

(defn- cycle-project! [text]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature text)
    dir))

(defn- small-room! []
  (let [r (tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj" "(System/exit 0)\n")
    r))

(defshared #"dhf/cycles/03/cycle\.edn names the cut spec-3(?: and the scope (.+))?"
  (fn [ctx scope]
    (let [ctx (if (:dir ctx)
                ctx
                (let [dir (cycle-project! (feature-text default-scenarios))]
                  (cut! dir "spec-3")
                  (assoc ctx :dir dir)))]
      (write-cycle! (:dir ctx) "03" (if scope {:scope (edn/read-string scope)} {}))
      (assoc ctx :room (or (:room ctx) (small-room!)) :cycle "03"))))

(defshared #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj (vec (remove :wip? default-scenarios))
                    {:no-id? true :title "A scenario nobody stamped"})
          text (feature-text scs)
          dir (cycle-project! text)
          line (some (fn [[i l]] (when (str/starts-with? (str/trim l) "Scenario: A scenario nobody stamped") (inc i)))
                     (map-indexed vector (str/split-lines text)))]
      (cut! dir "spec-3")
      (write-cycle! dir "03" {})
      (assoc ctx :dir dir :room (small-room!) :cycle "03"
             :unstamped {:file lint-feature :line line}))))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature

(defn task-table
  "The task table as the spec lists it: [{:task :usage :does}] from the
  examples of command.feature's @spec-0176."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %))
                          (:scenarios (gherkin/parse-feature "command.feature" (slurp f)))))]
    (vec (for [c (gherkin/expand sc)
               :let [v (get-in c [:example :values])]]
           {:task (get v "task") :usage (get v "usage") :does (get v "does")}))))

(defstep #"a project set up by rdm-spec"
  (fn [ctx]
    (let [parent (tmp-dir)
          r (run! ctx parent ["init" "project"])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          tasks (:tasks (stdout-edn r))
          t (first (filter #(= task (:task %)) tasks))]
      (s/check! (seq tasks) (str "help printed no :tasks\n" (s/show r)))
      (s/check! t (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= usage (:usage t)) (str "the usage of " task " is " (pr-str (:usage t))))
      (s/check! (= does (:does t)) (str task " is described as " (pr-str (:does t))))
      nil)))

;; The results directory

(defn- result-json
  "An Allure result labelled spec=`id`, passed."
  [id]
  (str "{\"uuid\":\"" (java.util.UUID/randomUUID) "\",\"name\":\"a result\",\"status\":\"passed\","
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}],\"steps\":[]}"))

(defn- results-in! [dir]
  (s/write! dir (str (java.util.UUID/randomUUID) "-result.json") (result-json "spec-0177"))
  (str dir))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rd]
    (let [dir (s/copy-of-room!)]
      (write-config! dir (assoc (config-of dir) :results-dir rd))
      (results-in! (fs/file dir rd))
      (results-in! (fs/file dir "other"))
      (-> ctx
          (assoc :dir dir)
          (assoc-in [:env "RESULTS_DIR"] nil)))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (results-in! (fs/file (tmp-dir) v))]
        (-> ctx
            (assoc-in [:placeholders v] d)
            (assoc-in [:env "RESULTS_DIR"] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx want]
    (let [r (exits! ctx 0)
          want (get-in ctx [:placeholders want] want)
          got (:results (stdout-edn r))]
      (s/check! (= want got) (str "it read " (pr-str got) ", not " (pr-str want) "\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (refused-naming! ctx (or (:missing-results ctx) (s/fail! "no directory was removed")))
    nil))

;; A gate stops the task; every task reports the same way

(defn- views! [ctx dir]
  (let [r (run! ctx dir ["views"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))))

(def ^:private unlevelled
  "The scenario whose level the fixtures remove, and its feature file."
  {:file "dhf/spec/kernel/command.feature" :id "@spec-0177"})

(defn- drop-level!
  "Removes the level of one scenario of the project's spec; ctx with
  :unlevelled {:file :line}."
  [ctx]
  (let [{:keys [file id]} unlevelled
        f (fs/file (:dir ctx) file)
        lines (str/split-lines (slurp (str f)))
        i (first (keep-indexed (fn [i l] (when (str/includes? l (str id " @contract")) i)) lines))]
    (s/check! i (str "no scenario " id " with a level in " file))
    (spit (str f) (str (str/join "\n" (update lines i #(str/replace % (str id " @contract") id))) "\n"))
    (assoc ctx :unlevelled {:file file :line (+ i 2)})))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          ctx (assoc ctx :dir dir)]
      (views! ctx dir)
      (let [m (fs/file dir (:model (config-of dir)))
            text (slurp (str m))]
        (spit (str m) (str/replace-first text #":description \"" ":description \"Changed. ")))
      (drop-level! ctx))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          lines (s/err-lines r)
          {:keys [file line]} (:unlevelled ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)
                (str "no scenario-no-level finding at " file ":" line "\n" (s/show r)))
      (s/check! (some #(re-find #": view-stale: " %) lines) (str "no view-stale finding\n" (s/show r)))
      nil)))

(defstep #"a spec that passes every check"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          ctx (assoc ctx :dir dir)]
      (views! ctx dir)
      ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          ctx (assoc ctx :dir dir)]
      (views! ctx dir)
      (drop-level! ctx))))

(def ^:private finding-line
  #"(?:[^\s:][^:]*?(?::\d+)?: )?(?:warning: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (remove #(str/starts-with? % "event: ") (s/err-lines r))
          {:keys [file line]} (:unlevelled ctx)]
      (doseq [l lines]
        (s/check! (re-matches finding-line l) (str "not a finding line: " (pr-str l) "\n" (s/show r))))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)))
                (str "not one line for the scenario with no level at " file ":" line "\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          v (try (edn/read {:eof ::eof} rdr) (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e)))))
          more (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::bad))]
      (s/check! (map? v) (str "standard output holds no EDN map\n" (s/show r)))
      (s/check! (= ::eof more) (str "standard output holds more than the result\n" (s/show r)))
      nil)))

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx task]
    (let [r (refused-naming! ctx task)]
      (doseq [{t :task} (task-table)]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) (str (:err r)))
                  (str "the error stream does not list the task " t "\n" (s/show r))))
      nil)))

(own-shared-steps!)
