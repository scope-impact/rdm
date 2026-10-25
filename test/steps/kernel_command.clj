(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps: every step text the features of more than one bounded
  context use, defined once, here, when this file loads (kernel_runner.clj
  holds the one the runner's feature shares). Each names the context keys
  it sets; another step file reads only those.

  The shared steps and the keys they set:

    \"rdm-spec LINE\" is run[ with no --results | with that results directory
      as DIR | with that directory as DIR]
        :run       the run: {:dir :args :exit :out :err}
        :dir       the project it ran in: the scenario's, else a new copy of
                   the room (copy-of-room!)
        :spec-before  {path text} of the project's dhf/spec before the run,
                   unless a step set it already
        :room      when LINE names ROOM and no step set one: a new path, under a
                   new directory, that does not exist yet
      LINE's placeholders: ROOM is :room; OUT, OUT2, R1 ... are
      :placeholders; DIR is :placeholders DIR, else (\"with that results
      directory as DIR\") :results, (\"with that directory as DIR\")
      :sources-dir, else :sources-dir or :results, whichever is set.
    it exits N                         sets nothing
    it exits 2 and names WORD          sets nothing (a cannot-run line names WORD)
    it passes                          sets nothing (exit 0)
    no file in the spec changes        sets nothing (reads :spec-before)
    a copy of rdm-spec's own project in which the configured results
      directory does not exist
        :dir      the copy
        :missing  the configured results directory (relative), which does not exist
        :env      RESULTS_DIR unset
    a wiki page whose footnote cites \"C\"
        :dir      a new copy of the room
        :page     the page (dhf/wiki/regeneration.md), listed in the wiki's index
        :page-text its text   :citation C   :footnote \"[^1]\"
    the configured stack words include \"W\"
        :dir      the scenario's project, else a new copy of the room
        :stack-word W
    dhf/cycles/03/cycle.edn names the cut spec-3[ and the scope S]
        :dir      the scenario's project, else a new cycle project (a git
                  repository: a copy of the room whose spec is the default
                  scenarios, cut spec-3); cycle.edn written after the cut
        :room     the scenario's room, else a small one whose tests exit 0
        :cycle    \"03\"
    the spec at cut spec-3 holds a scenario with no id
        :dir :room :cycle as above, the cut's spec holding an unstamped scenario
        :unstamped {:file :line}
    a rendered view whose recorded hash differs from the model's
        :dir      the scenario's project, else a new copy of the room, its
                  views rendered
        :stale-view \"containers\" (both outputs record another hash)

  Helpers other step files use: tmp-dir, copy-of-room!, config-of,
  spec-snapshot, run!, run-line, the-run, exits!, refused-naming!,
  finding-lines, cannot-run-lines, stdout-edn, project-set-up!."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn tmp-dir [] (s/tmp-dir))

(defn copy-of-room! [] (s/copy-of-room!))

(defn config-of
  "The configuration of the project `dir`, read."
  [dir]
  (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn spec-snapshot
  "{path text} of every file under dhf/spec of the project `dir`, the path
  relative to `dir`."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn run!
  "Runs rdm-spec `args` in `dir`, with the scenario's :env."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn- placeholders [ctx dir-from]
  (into {}
        (remove (comp nil? val))
        (merge {"ROOM" (:room ctx)
                "DIR" (case dir-from
                        :results (:results ctx)
                        :sources-dir (:sources-dir ctx)
                        (or (:sources-dir ctx) (:results ctx)))}
               (:placeholders ctx))))

(defn- substitute [args ph]
  (vec (for [a args]
         (or (some (fn [[k v]]
                     (cond (= a k) (str v)
                           (str/starts-with? a (str k "/")) (str v (subs a (count k)))))
                   ph)
             a))))

(defn- run-line*
  [ctx line dir-from]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        ctx (if (contains? ctx :spec-before) ctx (assoc ctx :spec-before (spec-snapshot (:dir ctx))))
        ctx (if (or (:room ctx) (not (some #(re-matches #"ROOM(/.*)?" %) (s/split-args line))))
              ctx
              (assoc ctx :room (str (fs/file (s/tmp-dir) "room"))))
        args (substitute (s/split-args line) (placeholders ctx dir-from))]
    (assoc ctx :run (run! ctx (:dir ctx) args))))

(defn run-line
  "Runs the command line `line` (without rdm-spec) in the scenario's
  project, its placeholders substituted: ctx with :run (and :dir)."
  [ctx line]
  (run-line* ctx line nil))

(defn the-run
  "The scenario's last run; the step fails when nothing was run."
  [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Checks the last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "expected exit " code "\n" (s/show r)))
    r))

(defn cannot-run-lines
  "The run's cannot-run lines."
  [run]
  (filterv #(str/starts-with? % "cannot-run: ") (s/err-lines run)))

(defn refused-naming!
  "Checks the last run could not run (exit 2, nothing on standard output)
  and that a cannot-run line names every one of `parts`; returns the run."
  [ctx & parts]
  (let [r (exits! ctx 2)]
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) (cannot-run-lines r))
              (str "no cannot-run line names " (str/join ", " (map pr-str parts)) "\n" (s/show r)))
    r))

(defn event-line? [l] (str/starts-with? l "event: "))

(defn warning-line? [l] (boolean (re-find #"(^|: )warning: " l)))

(defn finding-lines
  "The run's finding lines: no warning, event or cannot-run line."
  [run]
  (vec (remove #(or (event-line? %) (warning-line? %) (str/starts-with? % "cannot-run: ")) (s/err-lines run))))

(defn stdout-edn
  "The run's standard output, read as EDN; the step fails when it is not."
  [run]
  (try (edn/read-string (:out run))
       (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show run))))))

(defn project-set-up!
  "ctx with :dir, a project `rdm-spec init` wrote."
  [ctx]
  (let [parent (s/tmp-dir)
        r (run! ctx parent ["init" "project"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
    (assoc ctx :dir (str (fs/file parent "project")))))

;; ---------------------------------------------------------------------------
;; Shared: the command is run, and how it exits

;; Shared. Sets :run, :dir (when unset), :spec-before (when unset), :room (when LINE names ROOM and none is set).
(defstep #"\"rdm-spec ([^\"]*)\" is run(?: with (no --results|that results directory as DIR|that directory as DIR))?"
  (fn [ctx line how]
    (when (= "no --results" how)
      (s/check! (not (some #{"--results"} (s/split-args line))) (str "the line gives --results: " line)))
    (run-line* ctx line (case how
                          "that results directory as DIR" :results
                          "that directory as DIR" :sources-dir
                          nil))))

;; Shared. Sets nothing.
(defstep #"it exits (\d+)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

;; Shared. Sets nothing.
(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

;; Shared. Sets nothing.
(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

;; Shared. Sets nothing; reads :spec-before and :dir.
(defstep #"no file in the spec changes"
  (fn [ctx]
    (s/check! (:spec-before ctx) "no snapshot of the spec was taken")
    (let [now (spec-snapshot (:dir ctx))
          changed (sort (filter #(not= (get (:spec-before ctx) %) (get now %))
                                (distinct (concat (keys (:spec-before ctx)) (keys now)))))]
      (s/check! (empty? changed) (str "files under dhf/spec changed: " (str/join ", " changed))))
    nil))

;; ---------------------------------------------------------------------------
;; Shared: projects

;; Shared. Sets :dir, :missing, :env RESULTS_DIR (unset).
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (:results-dir (config-of dir))]
      (s/check! rd "the configuration names no results directory")
      (when (fs/exists? (fs/file dir rd)) (fs/delete-tree (fs/file dir rd)))
      (-> ctx
          (assoc :dir dir :missing rd)
          (assoc-in [:env "RESULTS_DIR"] nil)))))

;; Shared. Sets :dir (a new copy of the room), :page, :page-text, :citation, :footnote.
(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (s/copy-of-room!)
          wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
          rel "regeneration.md"
          text (str "# Regeneration\n\n"
                    "The code is built from the spec, and can be deleted and built again.[^1]\n\n"
                    "[^1]: `" citation "`, \"What to steal\" 1.\n")]
      (s/write! dir (str wiki "/" rel) text)
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [A page](" rel ")\n") :append true)
      (assoc ctx :dir dir :page (str wiki "/" rel) :page-text text :citation citation :footnote "[^1]"))))

;; Shared. Sets :dir (when unset), :stack-word.
(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          c (config-of dir)]
      (s/write! dir "dhf/harness/config.edn"
                (pr-str (update c :stack-words #(vec (distinct (conj (vec %) word))))))
      (assoc ctx :dir dir :stack-word word))))

;; --- cycle projects (copied in: a step file's helpers are its own) -----------

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
  (str "# A fixture feature file of the kernel's shared steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- cut!
  "Commits everything in `dir` outside dhf/cycles and tags it `tag`."
  [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid"))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "the cut " tag))
  (git! dir "tag" "-f" tag)
  dir)

(defn- write-cycle! [dir overrides]
  (let [m (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) default-cycle overrides)]
    (s/write! dir "dhf/cycles/03/cycle.edn" (pr-str m))
    m))

(defn- cycle-project!
  "A copy of the room whose spec holds only the lint feature `text`."
  [text]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature text)
    dir))

(defn- small-room! []
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj" "(System/exit 0)\n")
    r))

(defn- line-of [text start]
  (some (fn [[i l]] (when (str/starts-with? (str/trim l) start) (inc i)))
        (map-indexed vector (str/split-lines text))))

;; Shared. Sets :dir (when unset), :room (when unset), :cycle.
(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3(?: and the scope (.+))?"
  (fn [ctx scope]
    (let [ctx (if (:dir ctx)
                ctx
                (let [dir (cycle-project! (feature-text default-scenarios))]
                  (cut! dir "spec-3")
                  (assoc ctx :dir dir)))]
      (write-cycle! (:dir ctx) (if scope {:scope (edn/read-string scope)} {}))
      (assoc ctx :room (or (:room ctx) (small-room!)) :cycle "03"))))

;; Shared. Sets :dir, :room, :cycle, :unstamped.
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [text (feature-text (conj (vec (remove :wip? default-scenarios))
                                   {:no-id? true :title "A scenario nobody stamped"}))
          dir (cycle-project! text)]
      (cut! dir "spec-3")
      (write-cycle! dir {})
      (assoc ctx :dir dir :room (small-room!) :cycle "03"
             :unstamped {:file lint-feature :line (line-of text "Scenario: A scenario nobody stamped")}))))

;; --- a stale view ---------------------------------------------------------------

;; Shared. Sets :dir (when unset), :stale-view.
(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          _ (when (s/built? 'rdm-spec.architecture.views)
              (let [r (run! ctx dir ["views"])]
                (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))))
          vd (fs/file dir (or (:views-dir (config-of dir)) "dhf/views"))
          k "containers"
          other (apply str (repeat 64 "0"))]
      (doseq [ext ["mmd" "svg"]
              :let [f (str (fs/file vd (str k "." ext)))
                    [first-line & more] (str/split-lines (slurp f))
                    changed (str/replace first-line #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " other))]]
        (s/check! (not= first-line changed) (str f " records no model hash: " first-line))
        (spit f (str (str/join "\n" (cons changed more)) "\n")))
      (assoc ctx :dir dir :stale-view k))))

;; ---------------------------------------------------------------------------
;; command.feature: everything runs through one command

(defn- the-tasks
  "The task table as the spec states it: spec-0176's examples, [{task usage does}]."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))]
    (s/check! sc "the spec holds no spec-0176")
    (vec (for [ex (:examples sc) row (:rows ex)] (zipmap (:header ex) (:cells row))))))

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          tasks (get-in (stdout-edn r) [:help :tasks])
          t (first (filter #(= task (:task %)) tasks))]
      (s/check! (vector? tasks) (str "the result holds no {:help {:tasks [..]}}\n" (s/show r)))
      (s/check! t (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= usage (:usage t)) (str task "'s usage is " (pr-str (:usage t)) ", not " (pr-str usage)))
      (s/check! (= does (:does t)) (str task " is described as " (pr-str (:does t)) ", not " (pr-str does)))
      nil)))

;; --- the results directory ------------------------------------------------------

(defn- result-json [id]
  (str "{\"uuid\":\"" id "\",\"name\":\"a test of " id "\",\"status\":\"passed\","
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [dir (s/copy-of-room!)
          r1 (s/tmp-dir)]
      (s/check! (= rel (:results-dir (config-of dir))) (str "the configured results directory is not " rel))
      (doseq [d [(fs/file dir rel) (fs/file dir "other") (fs/file r1)]]
        (s/write! d "fixture-result.json" (result-json "spec-0012")))
      (-> ctx
          (assoc :dir dir)
          (assoc-in [:placeholders "R1"] r1)))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (assoc-in ctx [:env "RESULTS_DIR"]
              (when-not (= "unset" v) (get-in ctx [:placeholders v] v)))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (exits! ctx 0)
          want (get-in ctx [:placeholders dir] dir)
          read (get-in (stdout-edn r) [:results])]
      (s/check! (= want (str read)) (str "it read " (pr-str read) ", not " want "\n" (s/show r)))
      nil)))

;; --- a gate stops the task; every other stage reports ------------------------------

(defn- drop-level!
  "Removes the level from the scenario `id` in the project `dir`: {:file :line}."
  [dir id]
  (let [f (first (filter #(str/includes? (slurp (str %)) (str "@" id " "))
                         (fs/glob (fs/file dir "dhf/spec") "**.feature")))
        _ (s/check! f (str "the project holds no " id))
        text (slurp (str f))
        changed (str/replace text (re-pattern (str "@" id " @(?:e2e|contract)")) (str "@" id))
        line (some (fn [[i l]] (when (str/includes? l (str "@" id)) i))
                   (map-indexed vector (str/split-lines changed)))]
    (s/check! (not= text changed) (str id " carries no level to remove"))
    (spit (str f) changed)
    ;; the scenario's line is the line after its tags
    {:file (str (fs/relativize dir f)) :line (+ 2 line)}))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          {:keys [file line]} (drop-level! dir "spec-0177")
          model (fs/file dir (or (:model (config-of dir)) "dhf/spec/model.edn"))
          text (slurp (str model))
          changed (str/replace-first text #":description \"([^\"]*)\"" ":description \"$1, changed after its views\"")]
      (s/check! (not= text changed) "the model holds no description to change")
      (spit (str model) changed)
      (assoc ctx :dir dir :file file :line line))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)]
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": scenario-no-level: ")) ls)
                (str "no finding names the missing level at " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      (s/check! (some #(str/includes? % ": view-stale: ") ls) (str "no finding names a stale view\n" (s/show r)))
      nil)))

;; --- every task reports the same way ------------------------------------------------

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge (assoc ctx :dir dir) (drop-level! dir "spec-0178")))))

(def ^:private finding-re
  "FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what."
  #"^(?:[^\s:][^:]*:(?:\d+:)? )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*$")

(def ^:private warning-re
  #"^(?:[^\s:][^:]*:(?:\d+:)? )?warning: [a-z0-9]+(?:-[a-z0-9]+)*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (s/err-lines r)
          findings (finding-lines r)]
      (doseq [l ls]
        (s/check! (or (re-matches warning-re l) (re-matches finding-re l))
                  (str "the line is no finding or warning: " (pr-str l) "\n" (s/show r))))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": scenario-no-level: ")) findings)))
                (str "not one line names the scenario with no level at " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      (s/check! (every? warning-line? (drop-while (complement warning-line?) ls))
                (str "a finding follows a warning: the findings come first\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          forms (try (with-open [rd (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))]
                       (loop [acc []]
                         (let [x (edn/read {:eof ::eof} rd)]
                           (if (= ::eof x) acc (recur (conj acc x))))))
                     (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
      (s/check! (= 1 (count forms)) (str "standard output holds " (count forms) " values, not one\n" (s/show r)))
      (s/check! (map? (first forms)) (str "the result is not a map\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (s/check! (:missing ctx) "no directory is missing")
    (refused-naming! ctx (:missing ctx))
    nil))

(defstep #"it exits 2, names (\S+), and lists every task"
  (fn [ctx word]
    (let [r (refused-naming! ctx word)
          err (str (:err r))]
      (doseq [{:strs [task]} (the-tasks)]
        (s/check! (re-find (re-pattern (str "\\b" task "\\b")) err) (str "the error stream does not list " task "\n" (s/show r))))
      nil)))
