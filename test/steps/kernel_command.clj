(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the owner of
  every step text the features of more than one context share
  (dhf/spec/README.md, \"Step definitions are the scope's own\").

  rdm-spec is run by path (steps.support/command), in the scenario's
  project, with the scenario's environment: the tests' own without
  RESULTS_DIR, GIT_CEILING_DIRECTORIES set to the project's parent so git
  never finds a repository the fixture lies in (README, \"A fixture inside
  another git repository is fenced from it\"), plus the context's :env (a
  nil value unsetting a variable).

  Helpers other step files use: config-of, spec-files, run!, run-line,
  the-run, exits!, stdout-edn, err-lines, finding-lines, warning-line?,
  cannot-run-lines, refused-naming!, project-set-up!; and the cycle
  fixtures: lint-rel, default-scenarios, lint-text, cycle-project!, cut!,
  default-cycle, write-cycle!, passing-room!, graded-ids.

  The shared steps, each with the context keys it sets (README, \"A shared
  step's context keys are the kernel's\"); another step file reads only
  these:

    \"rdm-spec LINE\" is run
    \"rdm-spec LINE\" is run with no --results
    \"rdm-spec LINE\" is run with that (results) directory as DIR
        :run (the run: {:dir :args :exit :out :err}), :spec-before ({rel
        text} of the spec before the run). In LINE, ROOM is :room; DIR is
        :sources-dir after --sources, :results after --results, else the
        placeholder DIR, :results or :sources-dir; any word :placeholders
        names is its value; a placeholder starting a path (DIR/sauna) is
        filled too. It runs in :dir; with none, in a new copy of the room's
        kept paths (steps.support/copy-of-room!), which it sets as :dir.
    it exits N                          (nothing)
    it passes                           (nothing)
    it exits 2 and names WORD           (nothing)
    standard output, read as EDN, is exactly:  (doc string)
        :bound {\"NAME\" value}: an upper-case symbol of the doc string
        matches any value, the same one each time it appears
    no file in the spec changes         (nothing)
    a project set up by rdm-spec
        :dir (a project `rdm-spec init` wrote, its hooks off)
    a copy of rdm-spec's own project in which the configured results directory does not exist
        :dir (a copy of the room's kept paths), :missing-results (the
        configured results directory)
    the configured stack words include \"W\"
        :dir (the scenario's project, else a copy of the room), :stack-word
    a wiki page whose footnote cites \"C\"
        :dir (the scenario's project, else a copy of the room), :page (the
        page, relative to the project), :footnote \"[^1]\", :citation C
    a rendered view whose recorded hash differs from the model's
        :dir (a copy of the room), :stale-view (the view's key)
    dhf/cycles/03/cycle.edn names the cut spec-3
        :dir (the scenario's project, else a cycle project: a copy of the
        room whose spec holds only the lint's feature file, its :verify the
        tests check, cut spec-3), :room (a room whose tests pass every
        stamped scenario of the cut not tagged @wip, unless one is given)
    the spec at cut spec-3 holds a scenario with no id
        :dir (a cycle project whose lint feature file holds a scenario with
        no id, cut spec-3, cycle 03 naming it), :room, :unstamped {:file
        :line} (the unstamped scenario)

  The command's own steps (command.feature) set :dir, :env and :stale-view
  as above."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Reading a project

(defn config-of
  "The configuration of the project `dir`, read ({} when it has none)."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/exists? f) (edn/read-string (slurp (str f))) {})))

(defn- write-config! [dir c]
  (s/write! dir "dhf/harness/config.edn" (str (pr-str c) "\n")))

(defn spec-files
  "{rel text} of every file under the spec directory of the project `dir`."
  [dir]
  (let [sd (or (:spec-dir (config-of dir)) "dhf/spec")
        d (fs/file dir sd)]
    (if (fs/directory? d)
      (into (sorted-map)
            (for [f (fs/glob d "**") :when (fs/regular-file? f)]
              [(str/replace (str (fs/relativize dir f)) "\\" "/") (slurp (str f))]))
      {})))

;; ---------------------------------------------------------------------------
;; Running rdm-spec

(defn- env-of [ctx dir]
  (merge {"GIT_CEILING_DIRECTORIES" (str (fs/parent (fs/absolutize dir)))}
         (:env ctx)))

(defn run!
  "Runs `rdm-spec args` in `dir` with the scenario's environment: the run."
  [ctx dir args]
  (s/command dir args (env-of ctx dir)))

(defn- substitute
  "The words of a line with its placeholders filled: ctx with :args (and
  :room, a new path, when ROOM was given none)."
  [ctx words]
  (reduce
   (fn [ctx [i w]]
     (let [prev (when (pos? i) (nth words (dec i)))
           ph (:placeholders ctx)
           [_ head tail] (re-matches #"([A-Z][A-Z0-9]*)(/.*)?" w)
           ctx (if (and (= "ROOM" head) (not (:room ctx)))
                 (assoc ctx :room (str (fs/file (s/tmp-dir) "room")))
                 ctx)
           v (cond
               (= "ROOM" head) (:room ctx)
               (= "DIR" head) (or (case prev
                                    "--sources" (:sources-dir ctx)
                                    "--results" (:results ctx)
                                    nil)
                                  (get ph "DIR") (:results ctx) (:sources-dir ctx)
                                  (s/fail! "no DIR was given"))
               (and head (contains? ph head)) (get ph head))]
       (update ctx :args conj (if (some? v) (str v tail) w))))
   (assoc ctx :args [])
   (map-indexed vector words)))

(declare project-set-up!)

(defn run-line
  "Runs `rdm-spec LINE` in the scenario's project: ctx with :run and
  :spec-before (see the namespace doc)."
  [ctx line]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        dir (:dir ctx)
        {:keys [args] :as ctx} (substitute ctx (s/split-args line))
        before (spec-files dir)]
    (assoc (dissoc ctx :args) :run (run! ctx dir args) :spec-before before)))

(defn the-run
  "The scenario's last run."
  [ctx]
  (or (:run ctx) (s/fail! "rdm-spec was not run")))

(defn exits!
  "Checks the last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "it exited " (:exit r) ", not " code "\n" (s/show r)))
    r))

(defn stdout-edn
  "A run's standard output, read as EDN."
  [run]
  (try (edn/read-string (:out run))
       (catch Exception e (s/fail! (str "the standard output is not EDN: " (ex-message e) "\n" (s/show run))))))

(defn err-lines [run] (s/err-lines run))

(defn warning-line?
  "Whether `l` is a warning line: `FILE:LINE: warning: ...`, `FILE: warning:
  ...` or `warning: ...`."
  [l]
  (boolean (re-find #"^(?:\S+?: )?warning: " (str l))))

(defn- event-line? [l] (str/starts-with? (str l) "event: "))

(defn- cannot-run-line? [l] (str/starts-with? (str l) "cannot-run: "))

(defn finding-lines
  "The finding lines of a run's error stream: neither warnings, events nor
  cannot-run."
  [run]
  (vec (remove #(or (warning-line? %) (event-line? %) (cannot-run-line? %)) (err-lines run))))

(defn cannot-run-lines
  "The cannot-run lines of a run's error stream."
  [run]
  (filterv cannot-run-line? (err-lines run)))

(defn refused-naming!
  "Checks the last run exited 2 and its cannot-run lines name every one of
  `words`; returns the run."
  [ctx & words]
  (let [r (exits! ctx 2)
        text (str/join "\n" (cannot-run-lines r))]
    (s/check! (seq (cannot-run-lines r)) (str "no cannot-run line\n" (s/show r)))
    (doseq [w words]
      (s/check! (str/includes? text (str w)) (str "the refusal does not name " w "\n" (s/show r))))
    r))

;; ---------------------------------------------------------------------------
;; Projects

(defn project-set-up!
  "ctx with :dir: the scenario's project, else a new one `rdm-spec init`
  wrote, its hooks off."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (run! ctx parent ["init" "sauna"])
          dir (str (fs/file parent "sauna"))]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/hooks-off! dir)
      (assoc ctx :dir dir))))

(defn- room-copy [ctx] (or (:dir ctx) (s/copy-of-room!)))

;; --- cycle projects ---------------------------------------------------------------

(def lint-rel "dhf/spec/architecture/lint.feature")

(def default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(defn- scenario-text [{:keys [id title level wip? no-id?]}]
  (str "\n  " (str/join " " (remove nil? [(when-not no-id? (str "@" id)) (str "@" (or level "e2e"))
                                          (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn lint-text [scenarios]
  (str "# A fixture feature file of the kernel's shared steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(def ^:private git-identity
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env (assoc git-identity "GIT_CEILING_DIRECTORIES" (str (fs/parent (fs/absolutize dir))))}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn cut! [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "commit.gpgsign" "false")
    (git! dir "config" "core.hooksPath" (s/tmp-dir)))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "the cut " tag))
  (git! dir "tag" "-f" tag)
  dir)

(defn cycle-project!
  "A copy of the room whose spec holds only the lint's feature file holding
  `scenarios`, its :verify the tests check (its room's results carry the
  spec label only, so its mapping asks for no more), cut spec-3."
  [scenarios]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-rel (lint-text scenarios))
    (write-config! dir (assoc (config-of dir)
                              :verify [{:kind :tests :command ["bb" "test.clj"]}]
                              :allure {:labels {"spec" :scenario-id}}))
    (cut! dir "spec-3")))

(def default-cycle
  {:cut "spec-3" :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn write-cycle! [dir]
  (s/write! dir "dhf/cycles/03/cycle.edn" (str (pr-str default-cycle) "\n")))

(defn passing-room!
  "A room whose tests exit 0 and write a passing result for each of `ids`."
  [ids]
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj"
              (str "(require '[cheshire.core :as json])\n"
                   "(def results-dir (System/getenv \"RESULTS_DIR\"))\n"
                   "(doseq [[i spec] (map-indexed vector " (pr-str (vec ids)) ")]\n"
                   "  (spit (str results-dir \"/r\" i \"-result.json\")\n"
                   "        (json/generate-string {:uuid (str \"r\" i) :name spec :status \"passed\"\n"
                   "                               :steps [] :labels [{:name \"spec\" :value spec}]})))\n"
                   "(System/exit 0)\n"))
    r))

(defn graded-ids [scenarios]
  (vec (for [sc scenarios :when (not (or (:wip? sc) (:no-id? sc)))] (:id sc))))

(defn- line-of [text part]
  (some (fn [[i l]] (when (str/includes? l part) (inc i))) (map-indexed vector (str/split-lines text))))

;; ---------------------------------------------------------------------------
;; Running: the shared Whens

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) "the line gives --results")
    (run-line (update ctx :env assoc "RESULTS_DIR" nil) line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that (?:results )?directory as DIR"
  (fn [ctx line] (run-line ctx line)))

;; ---------------------------------------------------------------------------
;; What a run shows: the shared Thens

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"it exits 2 and names ([^\s,]+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

(defn- placeholder? [x]
  (and (symbol? x) (re-matches #"[A-Z][A-Z0-9]*" (name x))))

(defn- match
  "Matches `actual` against `expected`, whose upper-case symbols match any
  value (the same one each time): the bindings, or nil when it does not
  match."
  [expected actual bound]
  (cond
    (nil? bound) nil
    (placeholder? expected) (let [k (name expected)]
                              (cond (not (contains? bound k)) (assoc bound k actual)
                                    (= (get bound k) actual) bound
                                    :else nil))
    (map? expected) (when (and (map? actual) (= (set (keys expected)) (set (keys actual))))
                      (reduce (fn [b k] (match (get expected k) (get actual k) b)) bound (keys expected)))
    (and (sequential? expected) (sequential? actual))
    (when (= (count expected) (count actual))
      (reduce (fn [b [e a]] (match e a b)) bound (map vector expected actual)))
    (set? expected) (when (= expected actual) bound)
    :else (when (= expected actual) bound)))

(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [r (exits! ctx 0)
          want (edn/read-string doc)
          got (stdout-edn r)
          b (match want got {})]
      (s/check! b (str "standard output is\n" (pr-str got) "\nnot\n" (pr-str want) "\n" (s/show r)))
      (assoc ctx :bound b))))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (let [r (the-run ctx)
          before (or (:spec-before ctx) (s/fail! "the spec was not kept before the run"))
          after (spec-files (:dir r))
          changed (sort (for [k (distinct (concat (keys before) (keys after)))
                              :when (not= (get before k) (get after k))]
                          k))]
      (s/check! (#{0 1} (:exit r)) (str "the command did not run\n" (s/show r)))
      (s/check! (empty? changed) (str "the spec changed: " (str/join ", " changed)))
      nil)))

;; ---------------------------------------------------------------------------
;; Projects: the shared Givens

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (or (:results-dir (config-of dir)) "dhf/allure-results")]
      (fs/delete-tree (fs/file dir rd))
      (assoc ctx :dir dir :missing-results rd :env (assoc (:env ctx) "RESULTS_DIR" nil)))))

(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (room-copy ctx)]
      (write-config! dir (update (config-of dir) :stack-words #(vec (distinct (conj (vec %) word)))))
      (assoc ctx :dir dir :stack-word word))))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (room-copy ctx)
          wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
          rel "pages/cited.md"
          page (str wiki "/" rel)]
      (s/write! dir page (str "# Cited\n\nThis page rests on a source.[^1]\n\n"
                              "[^1]: `" citation "`, \"A section\".\n"))
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [A cited page](" rel ")\n") :append true)
      (assoc ctx :dir dir :page page :footnote "[^1]" :citation citation))))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (room-copy ctx)
          views (or (:views-dir (config-of dir)) "dhf/views")
          k "context"
          f (fs/file dir views (str k ".mmd"))]
      (s/check! (fs/exists? f) (str "the project has no view " k))
      (let [[first-line & more] (str/split-lines (slurp (str f)))
            stale (str/replace first-line #"[0-9a-f]{64}" (apply str (repeat 64 "0")))]
        (s/check! (not= stale first-line) (str k ".mmd records no model hash: " first-line))
        (spit (str f) (str (str/join "\n" (cons stale more)) "\n")))
      (assoc ctx :dir dir :stale-view k))))

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (let [dir (or (:dir ctx) (cycle-project! default-scenarios))]
      (write-cycle! dir)
      (cond-> (assoc ctx :dir dir)
        (not (:room ctx)) (assoc :room (passing-room! (graded-ids default-scenarios)))))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj default-scenarios {:id "spec-0015" :title "A scenario nobody stamped" :no-id? true})
          dir (cycle-project! scs)
          text (slurp (str (fs/file dir lint-rel)))]
      (write-cycle! dir)
      (assoc ctx :dir dir
             :room (or (:room ctx) (passing-room! (graded-ids scs)))
             :unstamped {:file lint-rel :line (line-of text "Scenario: A scenario nobody stamped")}))))

;; ---------------------------------------------------------------------------
;; command.feature: everything runs through one command

(defn- help-of [run]
  (let [h (:help (stdout-edn run))]
    (s/check! (sequential? h) (str "the help is not a list of tasks\n" (s/show run)))
    h))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          entry (first (filter #(= task (:task %)) (help-of r)))]
      (s/check! entry (str "the help lists no task " task "\n" (s/show r)))
      (s/check! (= usage (:usage entry)) (str "the usage of " task " is " (pr-str (:usage entry))))
      (s/check! (= does (:does entry)) (str task " is described as " (pr-str (:does entry))))
      nil)))

(defn- results-project!
  "A project init wrote whose directories dhf/allure-results, R1 and other
  each hold one result."
  [ctx]
  (let [ctx (project-set-up! ctx)]
    (doseq [d ["dhf/allure-results" "R1" "other"]]
      (s/write! (:dir ctx) (str d "/a-result.json")
                "{\"uuid\": \"a\", \"name\": \"a\", \"status\": \"passed\", \"labels\": []}\n"))
    ctx))

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (results-project! ctx)]
      (write-config! (:dir ctx) (assoc (config-of (:dir ctx)) :results-dir "dhf/allure-results"))
      ctx)))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v] (update ctx :env assoc "RESULTS_DIR" (when-not (= "unset" v) v))))

(defstep #"it reads (\S+)"
  (fn [ctx d]
    (let [r (exits! ctx 0)
          got (get-in (stdout-edn r) [:status :results])]
      (s/check! (= d got) (str "it read " (pr-str got) ", not " d "\n" (s/show r)))
      nil)))

;; --- a task reports its stages ---------------------------------------------------

(defn- drop-level!
  "Removes the level tag of the first stamped scenario of the project
  `dir`'s spec: {:file :line} of that scenario."
  [dir]
  (let [f (first (filter #(re-find #"@spec-\d{4,} @(?:e2e|contract)" (slurp (str %)))
                         (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))))
        _ (s/check! f "the project holds no stamped scenario with a level")
        text (slurp f)]
    (spit f (str/replace-first text #"(@spec-\d{4,}) @(?:e2e|contract)" "$1"))
    {:file (str/replace (str (fs/relativize dir f)) "\\" "/")}))

(defn- stale-model! [dir]
  (let [rel (or (:model (config-of dir)) "dhf/spec/model.edn")
        f (fs/file dir rel)
        m (edn/read-string (slurp (str f)))]
    (spit (str f) (str (pr-str (assoc-in m [:system :description] "A description the views never drew")) "\n"))))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (room-copy ctx)
          dropped (drop-level! dir)]
      (stale-model! dir)
      (assoc ctx :dir dir :unlevelled dropped))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)]
      (s/check! (some #(and (str/includes? % ": scenario-no-level: ") (str/includes? % (get-in ctx [:unlevelled :file]))) ls)
                (str "no finding names the missing level\n" (s/show r)))
      (s/check! (some #(str/includes? % ": view-stale: ") ls) (str "no finding names a stale view\n" (s/show r)))
      nil)))

(defstep #"a spec that passes every check"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (room-copy ctx)]
      (assoc ctx :dir dir :unlevelled (drop-level! dir)))))

(def ^:private finding-re
  #"^(?:[^\s:]+(?::\d+)?: )?[a-z0-9]+(?:-[a-z0-9]+)*: .+$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (finding-lines r)]
      (s/check! (seq ls) (str "no finding line\n" (s/show r)))
      (doseq [l ls]
        (s/check! (re-matches finding-re l) (str "not a finding line: " l)))
      (s/check! (some #(str/starts-with? % (str (get-in ctx [:unlevelled :file]) ":")) ls)
                (str "no finding names " (get-in ctx [:unlevelled :file]) "\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rd (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          one (try (edn/read {:eof ::eof} rd) (catch Exception e (s/fail! (str "not EDN: " (ex-message e) "\n" (s/show r)))))
          two (try (edn/read {:eof ::eof} rd) (catch Exception _ ::more))]
      (s/check! (map? one) (str "the standard output is not one map\n" (s/show r)))
      (s/check! (= ::eof two) (str "the standard output holds more than the result\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (refused-naming! ctx (or (:missing-results ctx) (s/fail! "no directory is missing"))) nil))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (refused-naming! ctx "frobnicate" "help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence"
                     "run" "verify" "checklist" "status" "trace" "release" "score" "scoreboard" "oracle"
                     "publish" "audit" "observe")
    nil))
