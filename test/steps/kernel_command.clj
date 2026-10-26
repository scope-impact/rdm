(ns steps.kernel-command
  "The kernel's step definitions: dhf/spec/kernel/command.feature, and every
  step text the features of more than one context share (dhf/spec/README.md,
  \"Step definitions are the scope's own\"). Each shared step is defined
  here once, when this file loads; beside each, the context keys it sets,
  which are the only keys another step file reads of it (\"A shared step's
  context keys are the kernel's\").

  Context keys, as the kernel's steps set them:
    :dir           the project a command runs in
    :run           the last run, {:dir :args :exit :out :err}
    :env           the scenario's extra environment ({name value}, nil unsets)
    :placeholders  {WORD path} an upper-case path placeholder of a step stands for
    :room          ROOM: the room a step made, or the new path a command was given
    :spec-before   {path text} of the project's spec before the last run
    :bound         {SYMBOL value} what an exactly-EDN Then bound its placeholders to
    :stale-view    the key of the view a Given made stale
    :page :footnote :citation   the wiki page a Given wrote, its footnote and citation
    :stack-word    the stack word a Given configured
    :missing-dir   the configured results directory a Given made sure is missing
    :cycle :unstamped   the cycle a Given wrote (\"03\"), the unstamped scenario {:file :line}
    :file :line    the feature file and line a Given wrote a scenario at

  Helpers other step files use: config-of, project-set-up!, run!, run-line,
  the-run, exits!, stdout-edn, finding-lines, warning-line?,
  cannot-run-lines, refused-naming!.

  A fixture made by init or adopt never runs its git hooks
  (dhf/spec/README.md, \"A fixture's git hooks never run\"):
  project-set-up! turns them off (steps.support/hooks-off!)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Projects

(defn config-of
  "The configuration of the project `dir`, read; {} when it has none."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/regular-file? f) (edn/read-string (slurp (str f))) {})))

(defn- write-config! [dir c]
  (s/write! dir "dhf/harness/config.edn" (str (pr-str c) "\n")))

(defn- update-config! [dir f]
  (write-config! dir (f (config-of dir))))

(defn run!
  "Runs rdm-spec `args` in `dir` with the scenario's environment: the run."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn project-set-up!
  "ctx with :dir, a project `rdm-spec init` wrote (its git hooks off), unless
  an earlier Given made one."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (run! ctx parent ["init" "project"])
          dir (str (fs/file parent "project"))]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/hooks-off! dir)
      (assoc ctx :dir dir))))

;; ---------------------------------------------------------------------------
;; Running the command

(defn the-run
  "The last run; fails when no command ran."
  [ctx]
  (or (:run ctx) (s/fail! "no rdm-spec command was run")))

(defn- spec-files [dir]
  (let [d (fs/file dir (or (:spec-dir (config-of dir)) "dhf/spec"))]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                           [(str (fs/relativize dir f)) (slurp (str f))]))
      {})))

(defn- substitute
  "The word `w` with an upper-case placeholder it starts with replaced."
  [ctx w]
  (let [[_ head tail] (re-matches #"([A-Z][A-Z0-9]*)(/.*)?" w)
        value (when head
                (or (get-in ctx [:placeholders head])
                    (case head
                      "ROOM" (:room ctx)
                      "DIR" (or (:sources-dir ctx) (:results ctx))
                      nil)))]
    (if value (str value tail) w)))

(defn run-line
  "Runs `rdm-spec LINE` in the scenario's project (when no Given made one, a
  copy of the room, as a clean room holds it, or a new empty directory for
  init), each placeholder replaced: ctx with :run (and :dir, :room when it
  made them)."
  ([ctx line] (run-line ctx line nil))
  ([ctx line sub]
   (let [words (s/split-args line)
         ctx (cond
               (:dir ctx) ctx
               (= "init" (first words)) (assoc ctx :dir (s/tmp-dir))
               :else (assoc ctx :dir (s/copy-of-room!)))
         ctx (if (and (some #(re-matches #"ROOM(/.*)?" %) words) (not (:room ctx)) (not (get-in ctx [:placeholders "ROOM"])))
               (assoc ctx :room (str (fs/file (s/tmp-dir) "room")))
               ctx)
         dir (if sub (str (fs/file (:dir ctx) sub)) (:dir ctx))
         args (mapv #(substitute ctx %) words)
         before (spec-files dir)]
     (assoc ctx :run (run! ctx dir args) :spec-before before))))

(defn stdout-edn
  "A run's standard output, read as EDN; fails when it is not."
  [run]
  (let [v (try (edn/read-string (str (:out run))) (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show run)))))]
    v))

(defn exits!
  "Checks the last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "exit " (:exit r) ", not " code "\n" (s/show r)))
    r))

(defn warning-line?
  "Whether a line of the error stream is a warning."
  [l]
  (boolean (re-find #"(^|: )warning: " (str l))))

(defn cannot-run-lines
  "The lines of a run's error stream saying why it cannot run."
  [run]
  (filterv #(str/starts-with? % "cannot-run: ") (s/err-lines run)))

(defn finding-lines
  "The lines of a run's error stream that are findings: neither warnings,
  events nor why it cannot run."
  [run]
  (vec (remove #(or (warning-line? %) (str/starts-with? % "event: ") (str/starts-with? % "cannot-run: "))
               (s/err-lines run))))

(defn refused-naming!
  "Checks the last run cannot run (exit 2), one line saying why naming every
  one of `parts`, and nothing on standard output."
  [ctx & parts]
  (let [r (exits! ctx 2)
        ls (cannot-run-lines r)]
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) ls)
              (str "no cannot-run line names all of " (pr-str parts) "\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
    r))

;; --- the shared When: :run, :spec-before (and :dir, :room as run-line makes them)

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run in it(?: again)?"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line sub] (run-line ctx line sub)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that (?:results )?directory as DIR"
  (fn [ctx line] (run-line ctx line)))

;; --- outcomes (set nothing)

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

(defn- wildcard? [x] (and (symbol? x) (re-matches #"[A-Z][A-Z0-9]*" (name x))))

(defn- match-edn
  "{:ok bool :bound {SYMBOL value}}: `got` against `want`, an upper-case
  symbol of `want` matching any value and binding it."
  [want got]
  (cond
    (wildcard? want) {:ok true :bound {(name want) got}}
    (and (map? want) (map? got))
    (if (= (set (keys want)) (set (keys got)))
      (reduce (fn [acc k] (let [m (match-edn (get want k) (get got k))]
                            (if (:ok m) (update acc :bound merge (:bound m)) (reduced m))))
              {:ok true :bound {}} (keys want))
      {:ok false})
    (and (sequential? want) (sequential? got))
    (if (= (count want) (count got))
      (reduce (fn [acc [w g]] (let [m (match-edn w g)]
                                (if (:ok m) (update acc :bound merge (:bound m)) (reduced m))))
              {:ok true :bound {}} (map vector want got))
      {:ok false})
    :else {:ok (= want got)}))

;; sets :bound
(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [r (the-run ctx)
          want (edn/read-string doc)
          got (stdout-edn r)
          m (match-edn want got)]
      (s/check! (:ok m) (str "standard output is\n" (pr-str got) "\nnot\n" (pr-str want) "\n" (s/show r)))
      (assoc ctx :bound (:bound m)))))

;; sets nothing: paired with the outcome, a command that ran
(defstep #"no file in the spec changes"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the command did not run\n" (s/show r)))
      (s/check! (contains? ctx :spec-before) "the spec was not kept before the run")
      (let [now (spec-files (:dir r))]
        (s/check! (= (:spec-before ctx) now)
                  (str "the spec changed: "
                       (pr-str (sort (for [k (distinct (concat (keys now) (keys (:spec-before ctx))))
                                           :when (not= (get now k) (get (:spec-before ctx) k))]
                                       k)))))))
    nil))

;; ---------------------------------------------------------------------------
;; Shared Givens

;; sets :dir
(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

;; sets :dir, :missing-dir
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (or (:results-dir (config-of dir)) "dhf/allure-results")]
      (fs/delete-tree (fs/file dir rd))
      (assoc ctx :dir dir :missing-dir (str/replace rd #"/+$" "")))))

(defn- stale-view!
  "Makes the view `k` of the project `dir` record another model hash."
  [dir k]
  (let [views (or (:views-dir (config-of dir)) "dhf/views")
        f (fs/file dir views (str k ".mmd"))
        _ (s/check! (fs/regular-file? f) (str "the project has no view " k))
        [first-line & more] (str/split-lines (slurp (str f)))
        other (apply str (repeat 64 "0"))]
    (spit (str f) (str (str/join "\n" (cons (str/replace first-line #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " other))
                                          more))
                       "\n"))))

;; sets :dir, :stale-view
(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (stale-view! dir "context")
      (assoc ctx :dir dir :stale-view "context"))))

;; sets :dir, :page, :footnote, :citation
(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (str/replace (or (:wiki-dir (config-of dir)) "dhf/wiki") #"/+$" "")
          rel "pages/footnoted.md"]
      (s/write! dir (str wiki "/" rel)
                (str "# Footnoted\n\nA claim the page makes.[^1]\n\n[^1]: `" citation "`, \"The idea\".\n"))
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [Footnoted](" rel ")\n") :append true)
      (assoc ctx :dir dir :page (str wiki "/" rel) :footnote "[^1]" :citation citation))))

;; sets :dir, :stack-word
(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (update-config! dir #(update % :stack-words (fn [ws] (vec (distinct (conj (vec ws) word))))))
      (assoc ctx :dir dir :stack-word word))))

;; --- a cycle at the cut spec-3 ----------------------------------------------------

(def ^:private git-env
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env git-env}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(defn- cycle-feature [unstamped?]
  (str "# A fixture feature file of the kernel's cycle steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       "\n  @spec-0012 @e2e\n  Scenario: A view with no description fails the lint\n"
       "    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"
       (when unstamped?
         "\n  @e2e\n  Scenario: A scenario nobody stamped\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n")))

(defn- cycle-project!
  "A copy of the room whose spec holds only the lint's feature file, a git
  repository whose commit is tagged spec-3: {:dir :unstamped}."
  [unstamped?]
  (let [dir (s/copy-of-room!)
        text (cycle-feature unstamped?)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature text)
    (git! dir "init" "-q")
    (git! dir "config" "commit.gpgsign" "false")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "spec-3")
    {:dir dir
     :unstamped (when unstamped?
                  {:file lint-feature
                   :line (inc (count (take-while #(not (str/includes? % "A scenario nobody stamped"))
                                                 (str/split-lines text))))})}))

(defn- cycle! [ctx unstamped?]
  (let [{:keys [dir unstamped]} (if (:dir ctx) {:dir (:dir ctx)} (cycle-project! unstamped?))
        f (fs/file dir "dhf/cycles/03/cycle.edn")]
    (when-not (fs/exists? f)
      (s/write! dir "dhf/cycles/03/cycle.edn"
                (str (pr-str {:cut "spec-3" :scope :project
                              :builder {:name "builder-a" :actor :agent}
                              :grader {:name "grader-b" :actor :agent}
                              :spec-owner {:name "Sami" :actor :human}})
                     "\n")))
    (cond-> (assoc ctx :dir dir :cycle "03" :room (or (:room ctx) (s/tmp-dir)))
      unstamped (assoc :unstamped unstamped))))

;; sets :dir, :cycle, :room
(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx] (cycle! ctx false)))

;; sets :dir, :cycle, :room, :unstamped
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (s/check! (not (:dir ctx)) "the scenario's project was made by an earlier Given")
    (cycle! ctx true)))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature

(defn- help-table
  "The tasks as spec-0176 states them: [{\"task\" \"usage\" \"does\"}]."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))]
    (s/check! sc "the spec holds no task table (spec-0176)")
    (vec (for [r (gherkin/outline-rows sc)] (:values r)))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          tasks (get-in (stdout-edn r) [:help :tasks])]
      (s/check! (some #(= {:task task :usage usage :does does} (select-keys % [:task :usage :does])) tasks)
                (str "help does not list " (pr-str {:task task :usage usage :does does}) "\n" (s/show r)))
      nil)))

(defn- result! [dir rel id]
  (s/write! dir (str rel "/" (random-uuid) "-result.json")
            (str "{\"name\":\"a test\",\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}\n")))

;; sets :dir (its results directories dhf/allure-results and other each hold a result)
(defstep #"the configured results directory is (\S+)"
  (fn [ctx rd]
    (let [ctx (project-set-up! ctx)
          dir (:dir ctx)]
      (update-config! dir #(assoc % :results-dir rd))
      (result! dir rd "spec-0001")
      (result! dir "other" "spec-0001")
      ctx)))

;; sets :env, :placeholders R1
(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (s/tmp-dir)]
        (result! d "." "spec-0001")
        (-> ctx
            (assoc-in [:env "RESULTS_DIR"] d)
            (assoc-in [:placeholders v] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx word]
    (let [r (exits! ctx 0)
          want (get-in ctx [:placeholders word] word)
          got (get-in (stdout-edn r) [:status :results])]
      (s/check! (= want got) (str "it read " (pr-str got) ", not " want "\n" (s/show r)))
      nil)))

(defn- level-less [title]
  (str "\n  @spec-9001\n  Scenario: " title "\n    Given a step\n    Then it holds\n"))

;; sets :dir, :stale-view, :file, :line
(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rel "dhf/spec/kernel/bus.feature"
          f (fs/file dir rel)
          before (slurp (str f))]
      (spit (str f) (str before (level-less "A scenario with no level")))
      (stale-view! dir "context")
      (assoc ctx :dir dir :stale-view "context" :file rel
             :line (+ 3 (count (str/split-lines before)))))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)]
      (s/check! (some #(str/includes? % ": scenario-no-level: ") ls) (str "no finding names the missing level\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\""))) ls)
                (str "no finding names the stale view\n" (s/show r)))
      nil)))

;; sets :dir
(defstep #"a spec that passes every check"
  (fn [ctx] (project-set-up! ctx)))

;; sets :dir, :file, :line
(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [ctx (project-set-up! ctx)
          dir (:dir ctx)
          cfg (config-of dir)
          model-rel (or (:model cfg) "dhf/spec/model.edn")
          m (edn/read-string (slurp (str (fs/file dir model-rel))))
          rel "dhf/spec/kernel/demo.feature"
          text (str "@UN-001\nFeature: Demo\n  A demo.\n" (level-less "A scenario with no level"))]
      (s/write! dir model-rel (pr-str (assoc m :kernel [{:id :kernel/demo :name "Demo" :spec "kernel/demo"
                                                          :description "A demo kernel component"}])))
      (s/write! dir rel text)
      (assoc ctx :file rel :line 6))))

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (finding-lines r)]
      (s/check! (seq ls) (str "no finding\n" (s/show r)))
      (doseq [l ls]
        (s/check! (re-matches #"(?:[^:\s][^:]*:(?:\d+:)? )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*" l)
                  (str "not a finding line: " l)))
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": scenario-no-level: ")) ls)
                (str "no finding names " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          forms (try (edn/read-string (str "[" (:out r) "]")) (catch Exception e (s/fail! (str "not EDN: " (ex-message e)))))]
      (s/check! (and (= 1 (count forms)) (map? (first forms))) (str "standard output is not one EDN map\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (refused-naming! ctx (or (:missing-dir ctx) (s/fail! "no directory was made missing"))) nil))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")
          err (str (:err r))]
      (doseq [t (map #(get % "task") (help-table))]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) err) (str "the error stream does not list " t "\n" (s/show r))))
      nil)))
