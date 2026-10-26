(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the owner of
  every step text more than one context's features use (dhf/spec/README.md,
  \"Step definitions are the scope's own\"). Each shared step names, beside
  it, the context keys it sets; another step file reads only those.

  Context keys the kernel's steps set:
    :dir          the project a command runs in (a copy of the room when no
                  Given made one)
    :run          the last run, {:dir :args :exit :out :err}
    :room         ROOM: a path the scenario names (new and absent, unless a
                  step made one)
    :placeholders {\"OUT\" path, \"R1\" path, \"DIR\" path ...}: upper-case
                  words of a command line a step resolved
    :env          extra environment of the scenario's commands ({name value},
                  nil unsetting it)
    :results      DIR after --results: a results directory a step made
    :sources-dir  DIR after --sources: a directory of source checkouts
    :spec-before  {path text} of the project's spec before the last run
    :stale-view   the key of a view whose recorded hash a step changed
    :page :footnote :citation   a wiki page a step wrote, its footnote's
                  label and the citation it holds
    :stack-word   a stack word a step added to the configuration
    :cycle        \"03\", the cycle a step declared
    :unstamped    {:file :line} of a scenario with no id a step wrote
    :file :line   a feature file and the scenario line a step changed
    :bound        {\"M12\" value}: what an EDN placeholder of an exact
                  comparison stood for

  Helpers other step files use (each documented below): config-of, run!,
  run-line, the-run, exits!, stdout-edn, finding-lines, warning-line?,
  event-line?, cannot-run-lines, refused-naming!, project-set-up!."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn config-of
  "The configuration of the project `dir`, read ({} when there is none)."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/regular-file? f) (edn/read-string (slurp (str f))) {})))

(defn run!
  "Runs `rdm-spec args` in `dir` with the scenario's environment (:env):
  the run."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn- spec-files [dir]
  (let [d (fs/file dir (or (:spec-dir (config-of dir)) "dhf/spec"))]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                           [(str (fs/relativize dir f)) (slurp (str f))]))
      {})))

(defn- substitute
  "The words of a command line with ROOM, DIR and the scenario's other
  placeholders resolved: [ctx words]."
  [ctx words]
  (loop [ctx ctx [w & more] words prev nil out []]
    (if (nil? w)
      [ctx out]
      (let [ph (:placeholders ctx)
            [ctx v] (cond
                      (= "ROOM" w) (let [room (or (:room ctx) (str (fs/file (s/tmp-dir) "room")))]
                                     [(assoc ctx :room room) room])
                      (and (= "DIR" w) (= "--results" prev))
                      [ctx (or (:results ctx) (get ph "DIR") (s/fail! "no results directory was made for DIR"))]
                      (and (= "DIR" w) (= "--sources" prev))
                      [ctx (or (:sources-dir ctx) (get ph "DIR") (s/fail! "no source checkouts were given for DIR"))]
                      (contains? ph w) [ctx (get ph w)]
                      :else
                      (if-let [[k v] (first (filter (fn [[k _]] (str/starts-with? w (str k "/"))) ph))]
                        [ctx (str v (subs w (count k)))]
                        [ctx w]))]
        (recur ctx more w (conj out v))))))

(defn run-line
  "Runs the command line `line` (the words after rdm-spec) in the
  scenario's project, a copy of the room when no Given made one: ctx with
  :run, :dir and :spec-before."
  [ctx line]
  (let [[ctx words] (substitute ctx (s/split-args line))
        dir (or (:dir ctx) (s/copy-of-room!))
        before (spec-files dir)
        r (run! ctx dir words)]
    (assoc ctx :dir dir :run r :spec-before before)))

(defn the-run
  "The last run; the step fails when nothing was run."
  [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Checks the last run exited `code`; returns it."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "it exited " (:exit r) ", not " code "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output, read as EDN (nil when it holds none)."
  [run]
  (let [out (str (:out run))]
    (when-not (str/blank? out)
      (try (binding [*default-data-reader-fn* tagged-literal] (edn/read-string out))
           (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show run))))))))

(defn event-line? [l] (str/starts-with? (str l) "event: "))

(defn warning-line? [l] (boolean (re-find #"(^|: )warning: " (str l))))

(defn- cannot-run-line? [l] (str/starts-with? (str l) "cannot-run: "))

(defn cannot-run-lines
  "The lines of the run's error stream saying why it could not run."
  [run]
  (filterv cannot-run-line? (s/err-lines run)))

(defn finding-lines
  "The finding lines of the run's error stream: not a warning, an event or
  a cannot-run line."
  [run]
  (filterv #(not (or (event-line? %) (warning-line? %) (cannot-run-line? %))) (s/err-lines run)))

(defn refused-naming!
  "Checks the last run could not run (exit 2) and that its cannot-run lines
  name every one of `words`; returns the run."
  [ctx & words]
  (let [r (exits! ctx 2)
        ls (cannot-run-lines r)]
    (s/check! (seq ls) (str "no line says why it cannot run\n" (s/show r)))
    (doseq [w words]
      (s/check! (some #(str/includes? % (str w)) ls) (str "it does not name " (pr-str (str w)) "\n" (s/show r))))
    (s/check! (str/blank? (:out r)) (str "a run that cannot run printed a result\n" (s/show r)))
    r))

(defn project-set-up!
  "ctx with :dir: the project an earlier Given made, else a new one
  `rdm-spec init` wrote."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (run! ctx parent ["init" "project"])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- drop-level!
  "Removes the level of the first stamped scenario of the project's spec:
  {:file :line} of that scenario."
  [dir]
  (let [f (first (filter #(re-find #"@spec-\d{4,} @(?:e2e|contract)" (slurp (str %)))
                         (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))))
        _ (s/check! f "the project holds no stamped scenario with a level")
        text (slurp f)
        ls (str/split-lines text)
        tag-i (first (keep-indexed (fn [i l] (when (re-find #"@spec-\d{4,} @(?:e2e|contract)" l) i)) ls))
        sc-i (first (keep-indexed (fn [i l] (when (and (> i tag-i) (re-find #"^\s*Scenario" l)) i)) ls))]
    (spit f (str/replace-first text #"(@spec-\d{4,}) @(?:e2e|contract)" "$1"))
    {:file (str (fs/relativize dir f)) :line (inc sc-i)}))

(defn- stale-view!
  "Makes the recorded hash of the project's context view differ from the
  model's: the view's key."
  [dir]
  (let [views (fs/file dir (or (:views-dir (config-of dir)) "dhf/views"))
        k (or (first (for [k ["context" "containers"] :when (fs/exists? (fs/file views (str k ".mmd")))] k))
              (some-> (first (sort (map str (fs/glob views "*.mmd")))) fs/file-name str (str/replace #"\.mmd$" ""))
              (s/fail! "the project holds no rendered view"))]
    (doseq [ext ["mmd" "svg"]
            :let [f (fs/file views (str k "." ext))]
            :when (fs/exists? f)]
      (spit (str f) (str/replace-first (slurp (str f)) #"model-sha256: [0-9a-f]{64}"
                                       (str "model-sha256: " (apply str (repeat 64 "0"))))))
    k))

(defn- result-json [id status]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
       "\"steps\":[],\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

;; ---------------------------------------------------------------------------
;; Running the command (shared by every context). Sets :run, :dir,
;; :spec-before, and :room when the line names ROOM.

(defstep #"\"rdm-spec ([^\"]*)\" is run(?: with no --results| in it| in it again)?"
  (fn [ctx line] (run-line ctx line)))

;; DIR is the results directory an earlier step made (:results).
(defstep #"\"rdm-spec ([^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line] (run-line ctx line)))

;; DIR is the directory of source checkouts an earlier step made (:sources-dir).
(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line] (run-line ctx line)))

(defstep #"it exits (0|1)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

;; Used by trace and gap: one word the cannot-run line names.
(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

;; Reads :spec-before; paired with the outcome: the command exited 0.
(defstep #"no file in the spec changes"
  (fn [ctx]
    (let [r (exits! ctx 0)
          now (spec-files (:dir ctx))
          before (:spec-before ctx)]
      (s/check! (= before now)
                (str "the spec changed: "
                     (pr-str (sort (for [k (distinct (concat (keys before) (keys now)))
                                         :when (not= (get before k) (get now k))]
                                     k)))
                     "\n" (s/show r)))
      nil)))

;; Reads :bound placeholders it binds: an upper-case symbol of the expected
;; EDN (M12, T13) stands for any value, the same wherever it appears.
(defn- match [expected actual bound]
  (cond
    (and (symbol? expected) (re-matches #"[A-Z][A-Z0-9]*" (name expected)))
    (let [k (name expected)]
      (if (contains? bound k)
        (when (= (get bound k) actual) bound)
        (assoc bound k actual)))

    (and (map? expected) (map? actual))
    (when (= (set (keys expected)) (set (keys actual)))
      (reduce (fn [b k] (if b (match (get expected k) (get actual k) b) (reduced nil))) bound (keys expected)))

    (and (sequential? expected) (sequential? actual))
    (when (= (count expected) (count actual))
      (reduce (fn [b [e a]] (if b (match e a b) (reduced nil))) bound (map vector expected actual)))

    :else (when (= expected actual) bound)))

;; Sets :bound.
(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [r (the-run ctx)
          want (edn/read-string doc)
          got (stdout-edn r)
          b (match want got {})]
      (s/check! (some? b) (str "standard output is not exactly\n" doc "\n" (s/show r)))
      (assoc ctx :bound b))))

;; ---------------------------------------------------------------------------
;; Projects shared by several contexts

;; Sets :dir: a copy of the room with no results directory.
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (fs/delete-tree (fs/file dir (or (:results-dir (config-of dir)) "dhf/allure-results")))
      (assoc ctx :dir dir))))

;; Sets :dir (a copy of the room, unless an earlier Given made a project) and
;; :stale-view.
(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (assoc ctx :dir dir :stale-view (stale-view! dir)))))

;; Sets :dir (a copy of the room, unless an earlier Given made a project),
;; :page, :footnote and :citation.
(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
          page (str wiki "/pages/cited.md")]
      (s/write! dir page (str "# Cited\n\nA claim that rests on a source.[^1]\n\n"
                              "[^1]: `" citation "`, \"A section\".\n"))
      (spit (str (fs/file dir wiki "index.md")) "\n- [Cited](pages/cited.md)\n" :append true)
      (assoc ctx :dir dir :page page :footnote "[^1]" :citation citation))))

;; Sets :dir (a copy of the room, unless an earlier Given made a project) and
;; :stack-word.
(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          cfg (config-of dir)]
      (s/write! dir "dhf/harness/config.edn"
                (str (pr-str (update cfg :stack-words #(vec (distinct (conj (vec %) word))))) "\n"))
      (assoc ctx :dir dir :stack-word word))))

;; ---------------------------------------------------------------------------
;; Cycles shared by ratification and measurement. A cycle project is a copy
;; of the room whose spec holds one feature file, the lint's, with spec-0012,
;; spec-0014 and spec-0013 tagged @wip, committed and tagged spec-3; a room
;; is a directory whose test.clj writes a passing result for spec-0012 and
;; spec-0014 into RESULTS_DIR and exits 0.

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(defn- cycle-feature [extra]
  (str "# A fixture feature file of the kernel's cycle steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       "\n  @spec-0012 @e2e\n  Scenario: A view with no description fails the lint\n    Given a model\n    Then it passes\n"
       "\n  @spec-0014 @contract\n  Scenario: A view over the limit fails the lint\n    Given a model\n    Then it passes\n"
       "\n  @spec-0013 @e2e @wip\n  Scenario: A component that owns no feature file fails the lint\n"
       "    Given a model\n    Then it passes\n"
       extra))

(def ^:private unstamped-scenario
  "\n  @e2e\n  Scenario: A scenario nobody stamped\n    Given a model\n    Then it passes\n")

(defn- cycle-project! [extra]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature (cycle-feature extra))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid")
    (git! dir "config" "commit.gpgsign" "false")
    (git! dir "add" "-A" "-f" "--" ".")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "-f" "spec-3")
    dir))

(defn- cycle-edn! [dir]
  (s/write! dir "dhf/cycles/03/cycle.edn"
            (pr-str {:cut "spec-3" :scope :project
                     :builder {:name "builder-a" :actor :agent}
                     :grader {:name "grader-b" :actor :agent}
                     :spec-owner {:name "Sami" :actor :human}})))

(defn- room! []
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj"
              (str ";; the fixture's tests\n(require '[clojure.java.io :as io])\n"
                   "(let [d (System/getenv \"RESULTS_DIR\")]\n  (.mkdirs (io/file d))\n"
                   (str/join (for [id ["spec-0012" "spec-0014"]]
                               (str "  (spit (io/file d (str (random-uuid) \"-result.json\")) "
                                    (pr-str (result-json id "passed")) ")\n")))
                   ")\n(System/exit 0)\n"))
    r))

;; Sets :dir (the cycle project, unless an earlier Given made one), :room
;; and :cycle.
(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (let [dir (or (:dir ctx) (cycle-project! ""))]
      (cycle-edn! dir)
      (assoc ctx :dir dir :room (or (:room ctx) (room!)) :cycle "03"))))

;; Sets :dir, :room, :cycle and :unstamped {:file :line}.
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [dir (cycle-project! unstamped-scenario)
          text (slurp (str (fs/file dir lint-feature)))
          line (some (fn [[i l]] (when (str/includes? l "Scenario: A scenario nobody stamped") (inc i)))
                     (map-indexed vector (str/split-lines text)))]
      (cycle-edn! dir)
      (assoc ctx :dir dir :room (room!) :cycle "03" :unstamped {:file lint-feature :line line}))))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature

(defn- task-table
  "The tasks spec-0176 lists: [{\"task\" \"usage\" \"does\"}]."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))]
    (s/check! sc "the spec holds no task table (spec-0176)")
    (vec (for [ex (:examples sc) row (:rows ex)] (zipmap (:header ex) (:cells row))))))

;; Sets :dir.
(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          tasks (get-in (stdout-edn r) [:help :tasks])]
      (s/check! (some #(= {:task task :usage usage :does does} (select-keys % [:task :usage :does])) tasks)
                (str "help does not list " (pr-str {:task task :usage usage :does does}) "\n" (s/show r)))
      nil)))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")
          l (str/join "\n" (cannot-run-lines r))]
      (doseq [t (map #(get % "task") (task-table))]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) l) (str "the task " t " is not listed\n" (s/show r))))
      nil)))

;; Results directories: the configured one, RESULTS_DIR's and --results',
;; each holding one result file nobody can read, so the error stream names
;; the directory a run read.
(defn- unreadable-result! [d]
  (s/write! d (str (random-uuid) "-result.json") "{ this is not JSON"))

;; Sets :dir; the project's configured results directory, and \"other\" in it,
;; each hold an unreadable result.
(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (project-set-up! ctx)
          dir (:dir ctx)]
      (s/check! (= "dhf/allure-results" (:results-dir (config-of dir))) "the configured results directory is not dhf/allure-results")
      (unreadable-result! (fs/file dir "dhf/allure-results"))
      (unreadable-result! (fs/file dir "other"))
      ctx)))

;; Sets :env RESULTS_DIR and the placeholder R1 (a new directory holding an
;; unreadable result).
(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (str (fs/file (s/tmp-dir) "r1"))]
        (unreadable-result! d)
        (-> ctx (assoc-in [:env "RESULTS_DIR"] d) (assoc-in [:placeholders "R1"] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (the-run ctx)
          _ (s/check! (#{0 1} (:exit r)) (str "the task did not run\n" (s/show r)))
          ph (:placeholders ctx)
          read-dir (get ph dir dir)
          candidates (remove #{read-dir} (concat ["dhf/allure-results" "other"] (vals ph)))
          ls (s/err-lines r)
          names? (fn [d] (some #(str/starts-with? % (str d "/")) ls))]
      (s/check! (names? read-dir) (str "the run did not read " read-dir "\n" (s/show r)))
      (doseq [c candidates]
        (s/check! (not (names? c)) (str "the run read " c "\n" (s/show r))))
      nil)))

;; Sets :dir, :file and :line.
(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge (assoc ctx :dir dir) (drop-level! dir)))))

;; Sets :dir, :file, :line and :stale-view.
(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge (assoc ctx :dir dir :stale-view (stale-view! dir)) (drop-level! dir)))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)]
      (s/check! (some #(and (str/starts-with? % (str (:file ctx) ":" (:line ctx) ": "))
                            (str/includes? % ": scenario-no-level: "))
                      ls)
                (str "no finding names the missing level\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\""))) ls)
                (str "no finding names the stale view\n" (s/show r)))
      nil)))

;; Sets :dir: a copy of the room, whose spec passes every check.
(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(def ^:private finding-re #"^(?:[^\s:]+(?::\d+)?: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (s/err-lines r)]
      (doseq [l ls]
        (s/check! (or (event-line? l) (re-matches finding-re l)) (str "the line " (pr-str l) " is not a finding\n" (s/show r))))
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": scenario-no-level: ")) ls)
                (str "no finding names " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          forms (loop [acc []]
                  (let [x (try (edn/read {:eof ::eof} rdr)
                               (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
                    (if (= ::eof x) acc (recur (conj acc x)))))]
      (s/check! (and (= 1 (count forms)) (map? (first forms)))
                (str "standard output holds " (count forms) " forms, not one result map\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (refused-naming! ctx (or (:results-dir (config-of (:dir ctx))) "dhf/allure-results"))
    nil))
