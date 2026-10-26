(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps: every step text the features of more than one context use
  (dhf/spec/README.md, \"Step definitions are the scope's own\"). Each step
  is defined once, here, when this file loads.

  The shared steps and the context keys each sets (another step file reads
  only these):

    \"rdm-spec LINE\" is run                      :dir (a new copy of the room when
                                                  none), :run, :spec-before; :room
                                                  when LINE names ROOM
    ... is run with no --results                  the same
    ... is run with that results directory as DIR the same; DIR is :results
    ... is run with that directory as DIR         the same; DIR is :sources-dir
    it exits N / it passes                        nothing
    it exits 2 and names WORD                     nothing
    standard output, read as EDN, is exactly:     :bound {PLACEHOLDER value}
    no file in the spec changes                   nothing
    a project set up by rdm-spec                  :dir
    a copy of rdm-spec's own project in which the configured results
      directory does not exist                    :dir
    a rendered view whose recorded hash differs
      from the model's                            :dir, :stale-view (the view's key)
    a wiki page whose footnote cites \"C\"          :dir, :page (relative to the
                                                  project), :footnote, :citation
    the configured stack words include \"W\"        :dir, :stack-word
    dhf/cycles/03/cycle.edn names the cut spec-3  :dir, :room, :cycle
    the spec at cut spec-3 holds a scenario
      with no id                                  :dir, :room, :cycle, :unstamped
                                                  {:file :line}

  In a LINE, ROOM is :room, else a new path that does not exist yet; DIR is
  :results after --results, :sources-dir after --sources; any other
  upper-case placeholder (OUT, R1, ...) is its value in :placeholders. A
  scenario's command runs with :env added to the tests' environment
  (steps.support/env-for).

  Helpers other step files use: config-of, run!, run-line, the-run, exits!,
  refused-naming!, stdout-edn, finding-lines, warning-line?, event-line?,
  cannot-run-lines, project-set-up!."
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
  "The configuration of the project `dir`, read ({} when it has none)."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/regular-file? f)
      (let [c (edn/read-string (slurp (str f)))] (if (map? c) c {}))
      {})))

(defn run!
  "Runs `rdm-spec args` in `dir` with the scenario's :env: the run."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn the-run
  "The scenario's last run; fails when no command was run."
  [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "Checks the last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "it exited " (:exit r) ", not " code "\n" (s/show r)))
    r))

(defn event-line?
  "Whether `line` is an event line: event: NAME published-by PUBLISHER."
  [line]
  (boolean (re-matches #"event: \S+ published-by \S+" (str line))))

(defn warning-line?
  "Whether `line` is a warning: FILE:LINE: warning: ..., FILE: warning:
  ..., or warning: ..."
  [line]
  (boolean (re-find #"^(?:\S+: )?warning: " (str line))))

(defn cannot-run-lines
  "The lines of a run's error stream that say why it cannot run."
  [run]
  (filterv #(str/starts-with? % "cannot-run: ") (s/err-lines run)))

(defn finding-lines
  "The lines of a run's error stream that are findings: neither warnings,
  events nor why it cannot run."
  [run]
  (vec (remove #(or (warning-line? %) (event-line? %) (str/starts-with? % "cannot-run: ")) (s/err-lines run))))

(defn stdout-edn
  "The run's standard output, read as EDN; fails when it is not."
  [run]
  (try (edn/read-string (:out run))
       (catch Exception e (s/fail! (str "standard output is not EDN (" (ex-message e) ")\n" (s/show run))))))

(defn refused-naming!
  "Checks the last run could not run (exit 2, nothing on standard output)
  and that why names every one of `words`; returns the run."
  [ctx & words]
  (let [r (exits! ctx 2)
        why (str/join "\n" (cannot-run-lines r))]
    (s/check! (seq (cannot-run-lines r)) (str "no line says why it cannot run (cannot-run: ...)\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
    (doseq [w words]
      (s/check! (str/includes? why (str w)) (str "why it cannot run does not name " (pr-str (str w)) "\n" (s/show r))))
    r))

(defn project-set-up!
  "ctx with :dir, a project `rdm-spec init` wrote; an earlier Given's
  project is built on."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (run! ctx parent ["init" "project"])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

(defn- spec-files
  "{rel text} of every file of the project's spec directory."
  [dir]
  (let [d (fs/file dir (str/replace (str (or (:spec-dir (config-of dir)) "dhf/spec")) #"/+$" ""))]
    (if (fs/directory? d)
      (into (sorted-map)
            (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
              [(str (fs/relativize dir f)) (slurp (str f))]))
      {})))

(def ^:private placeholder-re #"(?<![A-Za-z0-9_-])([A-Z][A-Z0-9]*)(?![A-Za-z0-9_-])")

(defn- substitute
  "[ctx words] with the placeholders of `words` replaced (see the namespace doc)."
  [ctx words]
  (reduce
   (fn [[ctx out] [prev w]]
     (let [ctx (if (and (re-find #"(?<![A-Za-z0-9_-])ROOM(?![A-Za-z0-9_-])" w) (not (:room ctx)))
                 (assoc ctx :room (str (fs/file (s/tmp-dir) "room")))
                 ctx)
           value (fn [k]
                   (cond
                     (= "ROOM" k) (:room ctx)
                     (contains? (:placeholders ctx) k) (get-in ctx [:placeholders k])
                     (= "DIR" k) (or (case prev
                                       "--results" (:results ctx)
                                       "--sources" (:sources-dir ctx)
                                       nil)
                                     (:results ctx) (:sources-dir ctx)
                                     (s/fail! "DIR names no directory: no step gave one"))
                     :else nil))
           w' (str/replace w placeholder-re (fn [[m k]] (str (or (value k) m))))]
       [ctx (conj out w')]))
   [ctx []]
   (map vector (cons nil words) words)))

(defn run-line
  "Runs `rdm-spec LINE` in the scenario's project (a new copy of the room
  when it has none), its placeholders replaced: ctx with :dir, :run and
  :spec-before (the spec's files before the run)."
  [ctx line]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        [ctx words] (substitute (assoc ctx :dir dir) (s/split-args line))
        before (spec-files dir)]
    (assoc ctx :run (run! ctx dir words) :spec-before before)))

;; ---------------------------------------------------------------------------
;; Shared: running the command and its outcome

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) "the line gives --results")
    (run-line (assoc-in ctx [:env "RESULTS_DIR"] nil) line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results ctx) "no results directory was given")
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no directory was given")
    (run-line ctx line)))

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

(defn- placeholder? [x] (and (symbol? x) (re-matches #"[A-Z][A-Z0-9]*" (name x))))

(defn- unify
  "The bindings under which `want` (placeholders as upper-case symbols)
  equals `got`, or nil."
  [want got bound]
  (cond
    (nil? bound) nil
    (placeholder? want) (let [k (name want)]
                          (cond (not (contains? bound k)) (assoc bound k got)
                                (= (get bound k) got) bound))
    (map? want) (when (and (map? got) (= (set (keys want)) (set (keys got))))
                  (reduce (fn [b k] (unify (get want k) (get got k) b)) bound (keys want)))
    (sequential? want) (when (and (sequential? got) (= (count want) (count got)))
                         (reduce (fn [b [w g]] (unify w g b)) bound (map vector want got)))
    :else (when (= want got) bound)))

(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [r (the-run ctx)
          got (stdout-edn r)
          b (unify (edn/read-string doc) got {})]
      (s/check! b (str "standard output is not as stated:\n" (pr-str got) "\n" (s/show r)))
      (assoc ctx :bound b))))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (exits! ctx 0)
    (let [before (or (:spec-before ctx) (s/fail! "the spec was not kept aside before the run"))
          now (spec-files (:dir ctx))
          changed (sort (filter #(not= (get before %) (get now %)) (distinct (concat (keys before) (keys now)))))]
      (s/check! (seq before) "the project holds no spec")
      (s/check! (empty? changed) (str "files of the spec changed: " (str/join ", " changed))))
    nil))

;; ---------------------------------------------------------------------------
;; Shared: projects

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defn- results-rel [dir] (str/replace (str (or (:results-dir (config-of dir)) "dhf/allure-results")) #"/+$" ""))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (fs/delete-tree (fs/file dir (results-rel dir)))
      (assoc ctx :dir dir))))

(defn- stale!
  "Makes the view `k` of the project `dir` record a hash that is not the
  model's: the first line of each of its outputs."
  [dir k]
  (let [views (str/replace (str (or (:views-dir (config-of dir)) "dhf/views")) #"/+$" "")
        files (filter fs/regular-file? [(fs/file dir views (str k ".mmd")) (fs/file dir views (str k ".svg"))])]
    (s/check! (seq files) (str "the view " k " was never rendered"))
    (doseq [f files
            :let [[first-line & more] (str/split (slurp (str f)) #"\n" -1)]]
      (s/check! (re-find #"model-sha256: [0-9a-f]{64}" first-line) (str f " records no model hash"))
      (spit (str f) (str/join "\n" (cons (str/replace first-line #"model-sha256: [0-9a-f]{64}"
                                                      (str "model-sha256: " (apply str (repeat 64 "0"))))
                                         more))))))

(defn- model-of [dir]
  (edn/read-string (slurp (str (fs/file dir (or (:model (config-of dir)) "dhf/spec/model.edn"))))))

(defn- stale-view! [ctx]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        k (or (first (keep :key (filter map? (:views (model-of dir)))))
              (s/fail! "the model holds no view"))]
    (stale! dir k)
    (assoc ctx :dir dir :stale-view k)))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx] (stale-view! ctx)))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (str/replace (str (or (:wiki-dir (config-of dir)) "dhf/wiki")) #"/+$" "")
          rel (str wiki "/pages/cited.md")]
      (s/write! dir rel (str "# Cited\n\nThis page rests on a source.[^1]\n\n"
                             "[^1]: `" citation "`, \"The section\".\n"))
      (spit (str (fs/file dir wiki "index.md")) "\n- [Cited](pages/cited.md): a page citing a source.\n" :append true)
      (assoc ctx :dir dir :page rel :footnote "[^1]" :citation citation))))

(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          cfg (config-of dir)]
      (s/write! dir "dhf/harness/config.edn"
                (str (pr-str (update cfg :stack-words #(vec (distinct (conj (vec %) word))))) "\n"))
      (assoc ctx :dir dir :stack-word word))))

;; ---------------------------------------------------------------------------
;; Shared: a cycle at the cut spec-3
;;
;; A cycle project is a copy of the room whose spec holds only the lint's
;; feature file, made a git repository and tagged spec-3, with
;; dhf/cycles/03/cycle.edn written after the cut. Its room holds a test.clj
;; that writes a passing result for each stamped scenario.

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(def ^:private stamped ["spec-0012" "spec-0014"])

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- scenario-text [{:keys [id title]}]
  (str "\n  " (if id (str "@" id " @e2e") "@e2e")
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn- feature-text [scenarios]
  (str "# A fixture feature file of the kernel's cycle steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn- result-json [id]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"passed\",\"steps\":[],"
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defn- room! []
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj"
              (str ";; the fixture's tests: a passing result for each stamped scenario\n"
                   "(require '[clojure.java.io :as io])\n"
                   "(let [d (System/getenv \"RESULTS_DIR\")]\n"
                   "  (.mkdirs (io/file d))\n"
                   (str/join (for [id stamped]
                               (str "  (spit (io/file d (str (random-uuid) \"-result.json\")) "
                                    (pr-str (result-json id)) ")\n")))
                   ")\n"))
    r))

(defn- cycle-project!
  "ctx with a cycle project whose cut holds `scenarios`, cycle 03 and a room."
  [ctx scenarios]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature (feature-text scenarios))
    (git! dir "init" "-q")
    (git! dir "add" "-A" "-f" "--" ".")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "spec-3")
    (assoc ctx :dir dir)))

(defn- cycle! [ctx]
  (s/write! (:dir ctx) "dhf/cycles/03/cycle.edn"
            (str (pr-str {:cut "spec-3" :scope :project
                          :builder {:name "builder-a" :actor :agent}
                          :grader {:name "grader-b" :actor :agent}
                          :spec-owner {:name "Sami" :actor :human}})
                 "\n"))
  (assoc ctx :cycle "03" :room (or (:room ctx) (room!))))

(def ^:private stamped-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint"}])

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (cycle! (if (:dir ctx) ctx (cycle-project! ctx stamped-scenarios)))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [title "A scenario nobody stamped"
          ctx (cycle! (cycle-project! ctx (conj stamped-scenarios {:title title})))
          text (slurp (str (fs/file (:dir ctx) lint-feature)))
          line (inc (count (take-while #(not (str/includes? % (str "Scenario: " title))) (str/split-lines text))))]
      (assoc ctx :unstamped {:file lint-feature :line line}))))

;; ---------------------------------------------------------------------------
;; command.feature: everything runs through one command

(defn- task-table
  "The tasks spec-0176 lists: [{\"task\" \"usage\" \"does\"}], read from the
  room's spec, never restated here."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))]
    (s/check! sc "the spec holds no task table (spec-0176)")
    (mapv :values (gherkin/outline-rows sc))))

(defstep #"it lists the task (\S+) with its usage (.*), described as (.*)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          out (stdout-edn r)
          tasks (:help out)]
      (s/check! (sequential? tasks) (str "standard output holds no {:help [..]}\n" (s/show r)))
      (s/check! (some #(= {:task task :usage usage :does does} (select-keys % [:task :usage :does])) tasks)
                (str "the task " task " is not listed as " (pr-str {:task task :usage usage :does does})
                     "\n" (s/show r)))
      (s/check! (= (count tasks) (count (distinct (map :task tasks)))) "a task is listed twice")
      nil)))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")]
      (doseq [{task "task"} (task-table)]
        (s/check! (re-find (re-pattern (str "(?<![\\w-])" (java.util.regex.Pattern/quote task) "(?![\\w-])")) (:err r))
                  (str "the error stream does not list the task " task "\n" (s/show r))))
      nil)))

;; --- the results directory ----------------------------------------------------

(defn- result! [rd]
  (s/write! rd (str (random-uuid) "-result.json")
            "{\"name\":\"a unit test\",\"status\":\"passed\",\"labels\":[{\"name\":\"suite\",\"value\":\"unit\"}]}"))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [ctx (project-set-up! ctx)
          dir (:dir ctx)]
      (s/write! dir "dhf/harness/config.edn" (str (pr-str (assoc (config-of dir) :results-dir rel)) "\n"))
      (result! (fs/file dir rel))
      (result! (fs/file dir "other"))
      ctx)))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (s/tmp-dir)]
        (result! d)
        (-> ctx
            (assoc-in [:placeholders v] d)
            (assoc-in [:env "RESULTS_DIR"] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (exits! ctx 0)
          want (get-in ctx [:placeholders dir] dir)
          got (:results (stdout-edn r))]
      (s/check! (= want got) (str "it read " (pr-str got) ", not " want "\n" (s/show r)))
      nil)))

;; --- a gate stops the task, every other stage reports ---------------------------

(defn- drop-level!
  "Removes the level of the first stamped scenario of the project `dir`:
  {:file :line} of the scenario."
  [dir]
  (let [f (first (filter #(re-find #"@\S+-\d{4,} @(?:e2e|contract)" (slurp (str %)))
                         (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))))
        _ (s/check! f "the project holds no stamped scenario with a level")
        text (slurp f)
        [_ before] (re-find #"(?s)^(.*?)@\S+-\d{4,} @(?:e2e|contract)" text)]
    (spit f (str/replace-first text #"(@\S+-\d{4,}) @(?:e2e|contract)" "$1"))
    ;; the scenario's line is the one after its tags
    {:file (str (fs/relativize dir f)) :line (+ 2 (count (filter #{\newline} before)))}))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          at (drop-level! dir)]
      (stale-view! (assoc ctx :dir dir :no-level at)))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)
          {:keys [file line]} (:no-level ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) ls)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\""))) ls)
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

;; --- every task reports the same way ----------------------------------------------

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :no-level (drop-level! dir)))))

(def ^:private finding-re
  "FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what."
  #"(?:[^\s:]+(?::\d+)?: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (s/err-lines r)
          {:keys [file line]} (:no-level ctx)]
      (doseq [l ls]
        (s/check! (or (event-line? l) (warning-line? l) (re-matches finding-re l))
                  (str "a line of the error stream is neither a finding nor a warning: " l)))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) ls)))
                (str "the missing level is not named once, as " file ":" line ": scenario-no-level: ...\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          forms (try (vec (take-while #(not= ::eof %) (repeatedly #(edn/read {:eof ::eof} rdr))))
                     (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
      (s/check! (and (= 1 (count forms)) (map? (first forms)))
                (str "standard output is not one EDN map\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (refused-naming! ctx (results-rel (:dir ctx))) nil))
