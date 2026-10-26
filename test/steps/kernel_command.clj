(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the owner of
  every step text the features of more than one context share
  (dhf/spec/README.md, \"Step definitions are the scope's own\"). Other
  step files read only the context keys named here beside each shared step.

  A Given builds on the project an earlier Given of its scenario made (:dir);
  only the first Given makes one. A Then that says nothing changed is paired
  with the command's outcome (exit 0), so it fails when no command ran.

  Shared steps, and the context keys each sets:

    \"rdm-spec LINE\" is run                 :dir (a copy of the room when no
                                           Given made one), :run, :room (when
                                           LINE names ROOM and none was made),
                                           :spec-before (unless set)
    ... is run with that directory as DIR  as above; DIR is :sources-dir
    ... is run with that results directory as DIR  as above; DIR is :results
    ... is run with no --results           as above
    it exits N / it passes / it exits 2 and names WORD   (reads :run)
    no file in the spec changes            (reads :run, :spec-before)
    a project set up by rdm-spec           :dir (rdm-spec init wrote it)
    a copy of rdm-spec's own project in which the configured results
      directory does not exist             :dir, :missing-results (its path)
    a rendered view whose recorded hash differs from the model's
                                           :dir, :stale-view (the view's key)
    a wiki page whose footnote cites \"C\"   :dir, :page (relative to :dir),
                                           :footnote (\"[^1]\"), :citation C
    the configured stack words include \"W\" :dir, :stack-word W
    dhf/cycles/03/cycle.edn names the cut spec-3
                                           :dir, :cycle \"03\", and :room when
                                           it made the project
    the spec at cut spec-3 holds a scenario with no id
                                           :dir, :room, :cycle, :unstamped
                                           {:file :line}

  The When substitutes, in LINE: each key of :placeholders by its value;
  ROOM by :room (a new path that does not exist, made when there is none);
  DIR after --sources by :sources-dir, after --results by :results, else by
  :placeholders \"DIR\".

  API for other step files: config-of, the-run, exits!, finding-lines,
  warning-line?, event-line?, cannot-run-lines, refused-naming!, run!,
  run-line, stdout-edn, project-set-up!."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Reading a project and a run

(defn config-of
  "The configuration of the project `dir`, read ({} when it has none)."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/regular-file? f) (edn/read-string (slurp (str f))) {})))

(defn the-run
  "The scenario's last run; the step fails when no command ran."
  [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "The last run, which must have exited `code`."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "it exited " (:exit r) ", not " code "\n" (s/show r)))
    r))

(defn event-line? [l] (str/starts-with? (str l) "event: "))

(defn warning-line? [l] (boolean (re-find #"(?:^|: )warning: " (str l))))

(defn cannot-run-line? [l] (str/starts-with? (str l) "cannot-run: "))

(defn finding-lines
  "The error stream's finding lines: neither warnings, events nor cannot-run."
  [run]
  (vec (remove #(or (event-line? %) (warning-line? %) (cannot-run-line? %)) (s/err-lines run))))

(defn cannot-run-lines [run] (filterv cannot-run-line? (s/err-lines run)))

(defn stdout-edn
  "The run's standard output, read as one EDN form."
  [run]
  (try (edn/read-string (str (:out run)))
       (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show run))))))

(defn refused-naming!
  "The last run, which must have exited 2, printed nothing on standard
  output and named every one of `words` in its cannot-run line."
  [ctx & words]
  (let [r (exits! ctx 2)
        text (str/join "\n" (cannot-run-lines r))]
    (s/check! (seq (cannot-run-lines r)) (str "no cannot-run line\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "standard output is not empty\n" (s/show r)))
    (doseq [w words]
      (s/check! (str/includes? text (str w)) (str "the refusal does not name " w "\n" (s/show r))))
    r))

;; ---------------------------------------------------------------------------
;; Running rdm-spec

(defn run!
  "Runs rdm-spec `args` in `dir` with the scenario's environment (:env)."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn- spec-files
  "{rel text} of every file of the spec of the project `dir`."
  [dir]
  (let [d (fs/file dir (or (:spec-dir (config-of dir)) "dhf/spec"))]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                           [(str (fs/relativize dir f)) (slurp (str f))]))
      {})))

(defn- substitute
  "`words` with the scenario's placeholders replaced, and ctx (ROOM made
  when the line names it and there is none)."
  [ctx words]
  (let [ctx (if (and (some #{"ROOM"} words) (not (:room ctx)))
              (assoc ctx :room (str (fs/file (s/tmp-dir) "room")))
              ctx)
        ph (:placeholders ctx)]
    [ctx
     (vec (map-indexed
           (fn [i w]
             (let [prev (when (pos? i) (nth words (dec i)))]
               (cond
                 (contains? ph w) (str (get ph w))
                 (= "ROOM" w) (:room ctx)
                 (and (= "DIR" w) (= "--sources" prev)) (or (:sources-dir ctx) (s/fail! "no DIR of checkouts"))
                 (and (= "DIR" w) (= "--results" prev)) (or (:results ctx) (s/fail! "no results DIR"))
                 :else (reduce-kv (fn [acc k v] (str/replace acc (str k) (str v))) w (or ph {})))))
           words))]))

(defn run-line
  "Runs `rdm-spec LINE` (LINE without the command's name) in the scenario's
  project, a copy of the room when no Given made one: ctx with :run."
  [ctx line]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        ctx (if (contains? ctx :spec-before) ctx (assoc ctx :spec-before (spec-files (:dir ctx))))
        [ctx words] (substitute ctx (s/split-args line))]
    (assoc ctx :run (run! ctx (:dir ctx) words))))

(defn project-set-up!
  "ctx with a project: the one an earlier Given made (:dir), else a new one
  rdm-spec init wrote."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (s/command parent ["init" "project"] nil)]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

;; ---------------------------------------------------------------------------
;; The shared When

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no directory was given as DIR")
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results ctx) "no results directory was given as DIR")
    (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (str/includes? line "--results")) "the line gives --results")
    (run-line (assoc-in ctx [:env "RESULTS_DIR"] nil) line)))

;; ---------------------------------------------------------------------------
;; The shared outcomes

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"it exits 2 and names ([^\s,]+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

;; Paired with the outcome (exit 0), so it fails when no command ran (cycle 11:
;; spec-0100 passed while publish exited 2).
(defstep #"no file in the spec changes"
  (fn [ctx]
    (let [r (exits! ctx 0)
          before (or (:spec-before ctx) (s/fail! "the spec was not kept aside before the command ran"))
          after (spec-files (:dir ctx))
          changed (sort (filter #(not= (get before %) (get after %)) (distinct (concat (keys before) (keys after)))))]
      (s/check! (empty? changed) (str "files of the spec changed: " (str/join ", " changed) "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Shared projects

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          rd (or (:results-dir (config-of dir)) "dhf/allure-results")]
      (when (fs/exists? (fs/file dir rd)) (fs/delete-tree (fs/file dir rd)))
      (assoc ctx :dir dir :missing-results rd))))

(defn- first-view-key [dir]
  (let [m (edn/read-string (slurp (str (fs/file dir (or (:model (config-of dir)) "dhf/spec/model.edn")))))]
    (or (some :key (:views m)) (s/fail! "the model has no view"))))

(defn- stale! [dir k]
  (let [f (fs/file dir (or (:views-dir (config-of dir)) "dhf/views") (str k ".mmd"))
        _ (s/check! (fs/regular-file? f) (str "the view " k " is not rendered"))
        [first-line & more] (str/split (slurp (str f)) #"\n" -1)]
    (spit (str f) (str/join "\n" (cons (str/replace first-line #"model-sha256: [0-9a-f]+"
                                                    (str "model-sha256: " (apply str (repeat 64 "0"))))
                                        more)))))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          k (first-view-key dir)]
      (stale! dir k)
      (assoc ctx :dir dir :stale-view k))))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
          rel "pages/cited.md"
          page (str wiki "/" rel)]
      (s/write! dir page (str "# Cited\n\nThe checklist is derived, never written.[^1]\n\n"
                              "[^1]: `" citation "`, \"The idea\".\n"))
      (let [index (fs/file dir wiki "index.md")]
        (if (fs/exists? index)
          (spit (str index) (str "\n- [Cited](" rel ")\n") :append true)
          (s/write! dir (str wiki "/index.md") (str "# Wiki\n\n- [Cited](" rel ")\n"))))
      (assoc ctx :dir dir :page page :footnote "[^1]" :citation citation))))

(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          cfg (config-of dir)]
      (s/write! dir "dhf/harness/config.edn" (pr-str (update cfg :stack-words (fnil conj []) word)))
      (assoc ctx :dir dir :stack-word word))))

;; ---------------------------------------------------------------------------
;; Shared cycles (copied from the ratification context's helpers: a step
;; file's helpers are its own)

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(def ^:private cycle-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def ^:private cycle-edn
  {:cut "spec-3" :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- scenario-text [{:keys [id title level wip?]}]
  (str "\n  " (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn- feature-text [scenarios]
  (str "# A fixture feature file of the kernel's shared steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn- cycle-project!
  "A copy of the room whose spec holds only the lint feature with
  `scenarios`, committed and tagged spec-3."
  [scenarios]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature (feature-text scenarios))
    (git! dir "init" "-q")
    (git! dir "add" "-A" "-f" "--" ".")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "spec-3")
    dir))

(defn- result-json [id]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"passed\",\"steps\":[],"
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defn- room!
  "A room whose tests write a passing result for each of `ids` and exit 0."
  [ids]
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj"
              (str "(require '[clojure.java.io :as io])\n"
                   "(let [d (System/getenv \"RESULTS_DIR\")]\n  (.mkdirs (io/file d))\n"
                   (str/join (for [id ids]
                               (str "  (spit (io/file d (str (random-uuid) \"-result.json\")) "
                                    (pr-str (result-json id)) ")\n")))
                   ")\n(System/exit 0)\n"))
    r))

(defn- write-cycle! [dir m]
  (let [f (fs/file dir "dhf/cycles/03/cycle.edn")
        old (when (fs/regular-file? f) (edn/read-string (slurp (str f))))]
    (s/write! dir "dhf/cycles/03/cycle.edn" (pr-str (merge cycle-edn old m)))))

(defn- stamped-ids [scenarios] (vec (keep #(when (and (:id %) (not (:wip? %))) (:id %)) scenarios)))

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (let [made? (nil? (:dir ctx))
          dir (or (:dir ctx) (cycle-project! cycle-scenarios))]
      (write-cycle! dir {:cut "spec-3"})
      (cond-> (assoc ctx :dir dir :cycle "03")
        (and made? (not (:room ctx))) (assoc :room (room! (stamped-ids cycle-scenarios)))))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [unstamped {:title "A scenario nobody stamped"}
          scs (conj cycle-scenarios unstamped)
          dir (if (:dir ctx)
                (let [d (:dir ctx)]
                  (s/write! d lint-feature (feature-text scs))
                  (git! d "add" "-A" "-f" "--" ".")
                  (git! d "commit" "-q" "--no-verify" "-m" "an unstamped scenario")
                  (git! d "tag" "-f" "spec-3")
                  d)
                (cycle-project! scs))
          text (slurp (str (fs/file dir lint-feature)))
          line (some (fn [[i l]] (when (str/includes? l (:title unstamped)) (inc i)))
                     (map-indexed vector (str/split-lines text)))]
      (write-cycle! dir {:cut "spec-3"})
      (assoc ctx :dir dir :cycle "03" :room (or (:room ctx) (room! (stamped-ids scs)))
             :unstamped {:file lint-feature :line line}))))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature: everything runs through one command

(defn- task-table
  "The tasks of spec-0176's table, read from the room's spec: [{task usage does}]."
  []
  (let [rel "dhf/spec/kernel/command.feature"
        parsed (gherkin/parse-feature rel (slurp (str (fs/file s/room-root rel))))
        sc (or (first (filter #(str/includes? (:name %) "Each task is run as") (:scenarios parsed)))
               (s/fail! "spec-0176 is not in the room's spec"))]
    (vec (for [ex (:examples sc) row (:rows ex)] (zipmap (:header ex) (:cells row))))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          out (stdout-edn r)
          tasks (get-in out [:help :tasks])
          t (first (filter #(= task (:task %)) tasks))]
      (s/check! (seq tasks) (str "the result lists no task under [:help :tasks]\n" (s/show r)))
      (s/check! t (str "the task " task " is not listed\n" (s/show r)))
      (s/check! (= usage (:usage t)) (str "the usage of " task " is " (pr-str (:usage t))))
      (s/check! (= does (:does t)) (str task " is described as " (pr-str (:does t))))
      nil)))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (apply refused-naming! ctx "frobnicate" (map #(get % "task") (task-table)))
    nil))

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          r1 (str (fs/file (s/tmp-dir) "R1"))]
      (s/check! (= "dhf/allure-results" (:results-dir (config-of dir))) "the configured results directory differs")
      (doseq [d [(fs/file dir "dhf/allure-results") (fs/file dir "other") (fs/file r1)]]
        (s/write! d (str (random-uuid) "-result.json") (result-json "spec-0012")))
      (-> ctx (assoc :dir dir) (assoc-in [:placeholders "R1"] r1)))))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (assoc-in ctx [:env "RESULTS_DIR"] (when (= "R1" v) (or (get-in ctx [:placeholders "R1"]) (s/fail! "no R1"))))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (exits! ctx 0)
          want (get (:placeholders ctx) dir dir)
          got (:results (stdout-edn r))]
      (s/check! (= want got) (str "it read " (pr-str got) ", not " want "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A gate stops the task; every task reports the same way

(defn- drop-level!
  "Removes the level of the first scenario of the lint feature of `dir`:
  {:file :line} of that scenario."
  [dir]
  (let [f (fs/file dir lint-feature)
        lines (str/split (slurp (str f)) #"\n" -1)
        i (or (first (keep-indexed (fn [i l] (when (re-find #"^\s*@spec-\d+.*@(e2e|contract)" l) i)) lines))
              (s/fail! "the lint feature holds no tagged scenario"))]
    (spit (str f) (str/join "\n" (assoc lines i (str/replace (nth lines i) #"\s*@(e2e|contract)" ""))))
    {:file lint-feature :line (+ 2 i)}))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          at (drop-level! dir)
          k (first-view-key dir)]
      (stale! dir k)
      (assoc ctx :dir dir :unleveled at :stale-view k))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          ls (finding-lines r)]
      (s/check! (some #(str/includes? % ": scenario-no-level: ") ls) (str "no missing level is named\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\""))) ls)
                (str "the stale view is not named\n" (s/show r)))
      nil)))

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (assoc ctx :dir dir :unleveled (drop-level! dir)))))

(def ^:private finding-re
  "FILE:LINE: RULE: what, FILE and LINE left out where none applies."
  #"^(?:[^\s:][^:]*?(?::\d+)?: )?[a-z0-9]+(?:-[a-z0-9]+)*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (finding-lines r)
          {:keys [file line]} (:unleveled ctx)]
      (s/check! (seq ls) (str "no finding line\n" (s/show r)))
      (doseq [l ls] (s/check! (re-matches finding-re l) (str "not a finding line: " l)))
      (s/check! (= 1 (count (filter #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) ls)))
                (str "no one line names " file ":" line " and the rule scenario-no-level\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          v (try (edn/read {:eof ::eof} rdr) (catch Exception e (s/fail! (str "not EDN: " (ex-message e) "\n" (s/show r)))))
          more (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::bad))]
      (s/check! (map? v) (str "the result is not an EDN map\n" (s/show r)))
      (s/check! (= ::eof more) (str "standard output holds more than the result\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (refused-naming! ctx (or (:missing-results ctx) (s/fail! "no directory was removed")))
    nil))
