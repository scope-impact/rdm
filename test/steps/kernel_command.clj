(ns steps.kernel-command
  "The kernel's step definitions: dhf/spec/kernel/command.feature, and every
  step text the features of more than one context share (dhf/spec/README.md,
  \"Step definitions are the scope's own\"). Another step file reads only the
  context keys named here beside each shared step (README, \"A shared step's
  context keys are the kernel's\").

  Shared steps, and the context keys each sets:

    When \"rdm-spec LINE\" is run [with no --results | with that results
         directory as DIR | with that directory as DIR]
        runs LINE in :dir (a copy of the room when there is none);
        sets :run (the run, {:dir :args :exit :out :err}), :dir, :room (a
        new path when LINE names ROOM and none was given), :placeholders
        (OUT, OUT2: new paths when LINE names them), :spec-before (the
        spec's files before the run). Words of LINE: a key of :placeholders
        is its value; ROOM is :room; DIR is :placeholders DIR, else after
        --results :results, after --sources :sources-dir. The command's
        environment is the tests' own without RESULTS_DIR, plus :env.
    Then it exits N / it passes (exit 0) / it exits 2 and names WORD
        read :run; set nothing.
    Then standard output, read as EDN, is exactly: DOC
        DOC's upper-case symbols (M12) are placeholders; sets :bound
        {\"M12\" value}.
    Then no file in the spec changes        reads :run and :spec-before.
    Given a project set up by rdm-spec      :dir (a project `rdm-spec init`
        wrote, its hooks off), unless :dir is set.
    Given the configured stack words include \"W\"
        :dir (a copy of the room unless :dir is set), :stack-word.
    Given a wiki page whose footnote cites \"C\"
        :dir (a copy of the room unless set), :page (the page, relative to
        :dir), :footnote (\"[^1]\"), :citation (C).
    Given a rendered view whose recorded hash differs from the model's
        :dir (a copy of the room unless set), :stale-view (the view's key).
    Given a copy of rdm-spec's own project in which the configured results
        directory does not exist                       :dir.
    Given dhf/cycles/03/cycle.edn names the cut spec-3
        :dir (a cycle project, below, unless set), :room (a room whose
        tests pass, unless set).
    Given the spec at cut spec-3 holds a scenario with no id
        :dir (a cycle project whose lint feature holds it), :room,
        :unstamped {:file :line}.

  A cycle project: a copy of the room whose spec holds only
  dhf/spec/architecture/lint.feature (stamped scenarios spec-0012 and
  spec-0014, and spec-0013 tagged @wip), its :verify the tests check `bb
  test.clj` and its :allure the spec label only, a git repository whose
  commit (dhf/cycles excluded) is tagged spec-3, and dhf/cycles/03/cycle.edn
  naming the cut.

  API for other step files: config-of, spec-files, run!, run-line,
  project-set-up!, the-run, exits!, stdout-edn, finding-lines,
  warning-line?, cannot-run-lines, refused-naming!.

  Context keys set by this file's own steps (command.feature): :dir, :run,
  :env, :placeholders (R1), :file and :line (a scenario that lost its
  level), :stale-view, :results-dirs."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Reading a project

(defn config-of
  "The configuration of the project `dir`, read; {} when it has none."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/regular-file? f) (edn/read-string (slurp (str f))) {})))

(defn spec-files
  "{rel text} of every file under the project `dir`'s configured spec directory."
  [dir]
  (let [spec-dir (str/replace (or (:spec-dir (config-of dir)) "dhf/spec") #"/+$" "")
        d (fs/file dir spec-dir)]
    (into (sorted-map)
          (when (fs/directory? d)
            (for [f (fs/glob d "**") :when (fs/regular-file? f)]
              [(str/replace (str (fs/relativize dir f)) "\\" "/") (slurp (str f))])))))

;; ---------------------------------------------------------------------------
;; Running rdm-spec

(defn run!
  "Runs rdm-spec `args` in `dir` with the scenario's environment (:env):
  the run, {:dir :args :exit :out :err}."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn project-set-up!
  "ctx with :dir: the project of ctx, else a new one `rdm-spec init` wrote,
  its git hooks off."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (s/command parent ["init" "project"] nil)
          dir (str (fs/file parent "project"))]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/hooks-off! dir)
      (assoc ctx :dir dir))))

(defn- new-path [] (str (fs/file (s/tmp-dir) "new")))

(defn- substitute
  "ctx and the words of `line` with every placeholder replaced."
  [ctx line]
  (loop [[w & more] (s/split-args line) prev nil ctx ctx out []]
    (if (nil? w)
      [ctx out]
      (let [ph (:placeholders ctx)]
        (cond
          (contains? ph w) (recur more w ctx (conj out (get ph w)))
          (some #(str/starts-with? w (str % "/")) (keys ph))
          (let [k (first (filter #(str/starts-with? w (str % "/")) (keys ph)))]
            (recur more w ctx (conj out (str (get ph k) (subs w (count k))))))
          (= "ROOM" w) (let [ctx (if (:room ctx) ctx (assoc ctx :room (new-path)))]
                         (recur more w ctx (conj out (:room ctx))))
          (#{"OUT" "OUT2"} w) (let [v (new-path)]
                                (recur more w (assoc-in ctx [:placeholders w] v) (conj out v)))
          (= "DIR" w) (let [v (case prev
                                "--results" (:results ctx)
                                "--sources" (:sources-dir ctx)
                                nil)]
                        (s/check! v (str "no directory was given as DIR for " (pr-str line)))
                        (recur more w ctx (conj out v)))
          :else (recur more w ctx (conj out w)))))))

(defn run-line
  "Runs `rdm-spec LINE` in the scenario's project (a copy of the room, what
  a clean room holds, when there is none): ctx with :run, :spec-before and
  what the placeholders made."
  [ctx line]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        [ctx args] (substitute ctx line)
        before (spec-files (:dir ctx))]
    (assoc ctx :spec-before before :run (run! ctx (:dir ctx) args))))

(defn the-run
  "The scenario's last run; fails the step when nothing was run."
  [ctx]
  (or (:run ctx) (s/fail! "rdm-spec was not run")))

(defn exits!
  "The last run, checked to have exited `n`."
  [ctx n]
  (let [r (the-run ctx)]
    (s/check! (= n (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn stdout-edn
  "The standard output of the run `r`, read as EDN."
  [r]
  (try (edn/read-string (:out r))
       (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r))))))

(defn warning-line?
  "Whether the error-stream line `l` is a warning."
  [l]
  (boolean (re-find #"(^|: )warning: " l)))

(defn- event-line? [l] (str/starts-with? l "event: "))

(defn cannot-run-lines
  "The cannot-run lines of the run `r`'s error stream."
  [r]
  (filterv #(str/starts-with? % "cannot-run: ") (s/err-lines r)))

(defn finding-lines
  "The finding lines of the run `r`'s error stream: no warning, no
  cannot-run line, no event line."
  [r]
  (vec (remove #(or (warning-line? %) (event-line? %) (str/starts-with? % "cannot-run: ")) (s/err-lines r))))

(defn refused-naming!
  "Checks the last run exited 2, printed nothing on standard output, and
  says each of `words` in a cannot-run line; the run."
  [ctx & words]
  (let [r (exits! ctx 2)
        lines (cannot-run-lines r)]
    (s/check! (seq lines) (str "no cannot-run line\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "a refusal printed a result\n" (s/show r)))
    (doseq [w words]
      (s/check! (some #(str/includes? % (str w)) lines) (str "no cannot-run line names " (pr-str w) "\n" (s/show r))))
    r))

;; ---------------------------------------------------------------------------
;; The shared When and Thens

(defstep #"\"rdm-spec ([^\"]*)\" is run(?: with no --results| with that results directory as DIR| with that directory as DIR)?"
  (fn [ctx line] (run-line ctx line)))

(defstep #"it exits (\d)"
  (fn [ctx n] (exits! ctx (parse-long n)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

(defn- placeholder? [x] (and (symbol? x) (re-matches #"[A-Z][A-Z0-9]*" (name x))))

(defn match
  "The bindings {name value} under which `want` (EDN, its upper-case
  symbols placeholders) is `got`, or nil."
  [want got bound]
  (cond
    (nil? bound) nil
    (placeholder? want) (let [k (name want)]
                          (cond (not (contains? bound k)) (assoc bound k got)
                                (= got (get bound k)) bound))
    (and (map? want) (map? got)) (when (= (set (keys want)) (set (keys got)))
                                   (reduce (fn [b k] (match (get want k) (get got k) b)) bound (keys want)))
    (and (sequential? want) (sequential? got)) (when (= (count want) (count got))
                                                 (reduce (fn [b [w g]] (match w g b)) bound (map vector want got)))
    :else (when (= want got) bound)))

(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [r (exits! ctx 0)
          want (edn/read-string doc)
          bound (match want (stdout-edn r) {})]
      (s/check! bound (str "standard output is not exactly\n" (pr-str want) "\n" (s/show r)))
      (assoc ctx :bound bound))))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (let [r (exits! ctx 0)
          before (or (:spec-before ctx) (s/fail! "the spec was not kept before the run"))
          after (spec-files (:dir ctx))]
      (s/check! (= before after)
                (str "the spec changed: " (pr-str (sort (filter #(not= (get before %) (get after %))
                                                                (distinct (concat (keys before) (keys after))))))
                     "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; The shared Givens

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defn- write-config! [dir cfg]
  (s/write! dir "dhf/harness/config.edn" (str (pr-str cfg) "\n")))

(defn- room-project [ctx] (or (:dir ctx) (s/copy-of-room!)))

(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (room-project ctx)
          cfg (config-of dir)]
      (write-config! dir (update cfg :stack-words (fnil conj []) word))
      (assoc ctx :dir dir :stack-word word))))

(defn- wiki-rel [dir] (str/replace (or (:wiki-dir (config-of dir)) "dhf/wiki") #"/+$" ""))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (room-project ctx)
          wiki (wiki-rel dir)
          rel (str "pages/cited-" (count (fs/list-dir (fs/file dir wiki))) ".md")]
      (s/write! dir (str wiki "/" rel)
                (str "# A cited page\n\nThe page rests on a source.[^1]\n\n[^1]: `" citation "`, \"The idea\".\n"))
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [A cited page](" rel ")\n") :append true)
      (assoc ctx :dir dir :page (str wiki "/" rel) :footnote "[^1]" :citation citation))))

(defn- views-rel [dir] (str/replace (or (:views-dir (config-of dir)) "dhf/views") #"/+$" ""))

(defn stale-view!
  "Makes the first view of the project `dir` record another model hash;
  returns its key."
  [dir]
  (let [m (edn/read-string (slurp (str (fs/file dir (or (:model (config-of dir)) "dhf/spec/model.edn")))))
        k (or (:key (first (:views m))) (s/fail! "the model has no view"))]
    (doseq [ext ["mmd" "svg"]
            :let [f (fs/file dir (views-rel dir) (str k "." ext))]]
      (s/check! (fs/regular-file? f) (str "the view " k " is not rendered: " f))
      (spit (str f) (str/replace (slurp (str f)) #"model-sha256: [0-9a-f]{64}"
                                 (str "model-sha256: " (apply str (repeat 64 "0"))))))
    k))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (room-project ctx)]
      (assoc ctx :dir dir :stale-view (stale-view! dir)))))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (or (:results-dir (config-of dir)) "dhf/allure-results")]
      (fs/delete-tree (fs/file dir rd))
      (assoc-in (assoc ctx :dir dir) [:env "RESULTS_DIR"] nil))))

;; --- a cycle project (copied in from the measurement context's fixtures) ------

(def lint-feature "dhf/spec/architecture/lint.feature")

(def cycle-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(defn- scenario-text [{:keys [id title wip?]}]
  (str "\n  " (str/join " " (remove nil? [(when id (str "@" id)) "@e2e" (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn lint-feature-text
  "The lint feature file holding `scenarios`."
  [scenarios]
  (str "# A fixture feature file of the kernel's steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn git!
  "git `args` in `dir`; fails the step when git fails. Its trimmed output."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn cut!
  "Commits everything in `dir` but dhf/cycles (a repository made when there
  is none, its hooks off) and tags the commit `tag`."
  [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid")
    (git! dir "config" "commit.gpgsign" "false")
    (git! dir "config" "tag.gpgsign" "false")
    (s/hooks-off! dir))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "the cut " tag))
  (git! dir "tag" "-f" tag)
  dir)

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

(def default-cycle
  {:cut "spec-3" :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn write-cycle!
  "Writes dhf/cycles/03/cycle.edn naming the cut spec-3."
  [dir]
  (s/write! dir "dhf/cycles/03/cycle.edn" (str (pr-str default-cycle) "\n")))

(defn cycle-project!
  "A cycle project whose lint feature holds `scenarios`, cut spec-3."
  [scenarios]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature (lint-feature-text scenarios))
    (write-config! dir (assoc (config-of dir)
                              :verify [{:kind :tests :command ["bb" "test.clj"]}]
                              :allure {:labels {"spec" :scenario-id}}))
    (cut! dir "spec-3")))

(defn- cycle-room [ctx scenarios]
  (or (:room ctx) (passing-room! (keep #(when-not (:wip? %) (:id %)) scenarios))))

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (let [dir (or (:dir ctx) (cycle-project! cycle-scenarios))]
      (write-cycle! dir)
      (assoc ctx :dir dir :room (cycle-room ctx cycle-scenarios)))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj cycle-scenarios {:title "A scenario nobody stamped"})
          dir (cycle-project! scs)
          text (slurp (str (fs/file dir lint-feature)))
          line (inc (count (take-while #(not (str/includes? % "A scenario nobody stamped")) (str/split-lines text))))]
      (write-cycle! dir)
      (assoc ctx :dir dir :room (cycle-room ctx scs) :unstamped {:file lint-feature :line line}))))

;; ---------------------------------------------------------------------------
;; command.feature: everything runs through one command

(defn task-table
  "The tasks spec-0176 lists: [{\"task\" \"usage\" \"does\"}], read from the room's spec."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/kernel/command.feature"))
        sc (first (filter #(some #{"@spec-0176"} (:tags %)) (:scenarios (gherkin/parse-feature f (slurp f)))))]
    (s/check! sc "the spec holds no spec-0176")
    (mapv :values (gherkin/outline-rows sc))))

(defstep #"it lists the task (\S+) with its usage (.+?), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          help (:help (stdout-edn r))
          entry (first (filter #(= task (:task %)) help))]
      (s/check! (sequential? help) (str "the result holds no {:help [..]}\n" (s/show r)))
      (s/check! entry (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= usage (:usage entry)) (str task "'s usage is " (pr-str (:usage entry)) ", not " (pr-str usage)))
      (s/check! (= does (:does entry)) (str task " is described as " (pr-str (:does entry)) ", not " (pr-str does)))
      nil)))

;; --- the results directory ---------------------------------------------------

(defn- result! [dir]
  (s/write! dir (str (random-uuid) "-result.json")
            "{\"name\":\"a test\",\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-9001\"}]}\n"))

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (project-set-up! ctx)
          dir (:dir ctx)]
      (s/check! (= "dhf/allure-results" (:results-dir (config-of dir)))
                (str "the configured results directory is " (pr-str (:results-dir (config-of dir)))))
      (result! (fs/file dir "dhf/allure-results"))
      (result! (fs/file dir "other"))
      ctx)))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (s/tmp-dir)]
        (result! d)
        (-> ctx (assoc-in [:env "RESULTS_DIR"] d) (assoc-in [:placeholders "R1"] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (exits! ctx 0)
          want (get-in ctx [:placeholders dir] dir)
          got (get-in (stdout-edn r) [:status :results])]
      (s/check! (= want got) (str "it read " (pr-str got) ", not " (pr-str want) "\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (refused-naming! ctx (or (:results-dir (config-of (:dir ctx))) "dhf/allure-results"))
    nil))

;; --- a gate stops the task; every task reports the same way -------------------

(defn drop-level!
  "Removes the level tag of the first stamped scenario of the project
  `dir`'s spec: {:file :line} of that scenario."
  [dir]
  (let [f (first (filter #(re-find #"@spec-\d{4,} @(?:e2e|contract)" (slurp (str %)))
                         (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))))
        _ (s/check! f "the project holds no stamped scenario with a level")
        text (slurp f)
        lines (str/split-lines text)
        i (first (keep-indexed (fn [i l] (when (re-find #"@spec-\d{4,} @(?:e2e|contract)" l) i)) lines))]
    (spit f (str/replace-first text #"(@spec-\d{4,}) @(?:e2e|contract)" "$1"))
    {:file (str (fs/relativize dir f))
     :line (+ 2 i (count (take-while #(re-matches #"\s*(@\S+\s*)*" %) (drop (inc i) lines))))}))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge ctx {:dir dir :stale-view (stale-view! dir)} (drop-level! dir)))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)]
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": scenario-no-level: ")) ls)
                (str "no finding names the missing level at " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\""))) ls)
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (merge ctx {:dir dir} (drop-level! dir)))))

(def ^:private finding-re
  "A finding line: FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what."
  #"^(?:[^\s:][^:]*?(?::\d+)?: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*$")

(def ^:private warning-re
  #"^(?:[^\s:][^:]*?(?::\d+)?: )?warning: [a-z0-9]+(?:-[a-z0-9]+)*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (s/err-lines r)
          findings (finding-lines r)]
      (doseq [l ls]
        (s/check! (or (re-matches finding-re l) (re-matches warning-re l))
                  (str "the line is neither a finding nor a warning: " (pr-str l))))
      (s/check! (some #(str/starts-with? % (str (:file ctx) ":" (:line ctx) ": scenario-no-level: ")) findings)
                (str "no finding names " (:file ctx) ":" (:line ctx) "\n" (s/show r)))
      (let [first-warning (first (keep-indexed (fn [i l] (when (warning-line? l) i)) ls))
            last-finding (last (keep-indexed (fn [i l] (when-not (warning-line? l) i)) ls))]
        (s/check! (or (nil? first-warning) (nil? last-finding) (< last-finding first-warning))
                  (str "a warning comes before a finding\n" (s/show r))))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          forms (try (edn/read-string (str "[" (:out r) "]"))
                     (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
      (s/check! (and (= 1 (count forms)) (map? (first forms)))
                (str "standard output holds " (count forms) " forms, not one result\n" (s/show r)))
      nil)))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")
          l (first (filter #(str/includes? % "frobnicate") (cannot-run-lines r)))]
      (doseq [{:strs [task]} (task-table)]
        (s/check! (re-find (re-pattern (str "\\b" task "\\b")) l) (str "the refusal does not list the task " task ": " l)))
      nil)))
