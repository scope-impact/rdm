(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the owner of
  every step text the features of more than one context share
  (dhf/spec/README.md, \"Step definitions are the scope's own\" and \"A
  shared step's context keys are the kernel's\").

  rdm-spec is run by path (steps.support/command), in the scenario's
  project (:dir), with the scenario's extra environment (:env).

  The shared steps, each with the context keys it sets (another step file
  reads only these):

    a project set up by rdm-spec                      :dir (built on, when set)
    \"rdm-spec LINE\" is run                            :run, :spec-before, :dir (when none
                                                      was set), :room (when LINE names
                                                      ROOM and none was set)
    \"rdm-spec LINE\" is run with that [results] directory as DIR
                                                      as above
    \"rdm-spec LINE\" is run with no --results          as above
    it exits N / it passes / it exits 2 and names W   (none)
    standard output, read as EDN, is exactly: DOC     :bound ({\"M12\" value}: each
                                                      upper-case symbol of DOC, bound)
    no file in the spec changes                       (none)
    a copy of rdm-spec's own project in which the configured results directory does not exist
                                                      :dir
    a rendered view whose recorded hash differs from the model's
                                                      :dir, :stale-view (its key)
    the configured stack words include \"W\"            :dir, :stack-word
    a wiki page whose footnote cites \"C\"              :dir, :page (relative to :dir),
                                                      :footnote (\"[^1]\"), :citation
    dhf/cycles/03/cycle.edn names the cut spec-3      :dir, :room, :cycle (the cycle.edn)
    the spec at cut spec-3 holds a scenario with no id
                                                      :dir, :room, :unstamped {:file :line}

  and, in steps.kernel-runner (the kernel's too):
    a stamped scenario outline with three example rows
                                                      :dir, :id \"spec-0013\", :rows ([[heading value] ..]
                                                      of each row), :log

  Placeholders in a When's LINE: every key of :placeholders ({\"OUT\" path}),
  then ROOM (:room, else a new path that does not exist yet, kept as :room)
  and DIR (:placeholders \"DIR\", else :results, else :sources-dir); a word
  that is the placeholder, or starts with it and a /, is replaced.

  API for the other step files: config-of, project-set-up!, run!, run-line,
  the-run, exits!, finding-lines, warning-line?, cannot-run-lines,
  refused-naming!, stdout-edn, spec-files."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Projects

(defn config-of
  "The configuration of the project `dir`, read ({} when it has none)."
  [dir]
  (let [f (fs/file dir "dhf/harness/config.edn")]
    (if (fs/exists? f) (edn/read-string (slurp (str f))) {})))

(defn- write-config! [dir c]
  (s/write! dir "dhf/harness/config.edn" (str (pr-str c) "\n")))

(defn- update-config! [dir f]
  (write-config! dir (f (config-of dir))))

(defn run!
  "Runs rdm-spec `args` in `dir` with the scenario's environment (:env):
  the run, {:dir :args :exit :out :err}."
  [ctx dir args]
  (s/command dir args (:env ctx)))

(defn project-set-up!
  "ctx with :dir: the scenario's project when an earlier Given made one,
  else a new project `rdm-spec init` wrote, its hooks off."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [parent (s/tmp-dir)
          r (run! ctx parent ["init" "project"])
          dir (str (fs/file parent "project"))]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/hooks-off! dir)
      (assoc ctx :dir dir))))

(defn spec-files
  "{path text} of every file of the spec of the project `dir`."
  [dir]
  (let [d (fs/file dir (or (:spec-dir (config-of dir)) "dhf/spec"))]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                           [(str (fs/relativize dir f)) (slurp (str f))]))
      {})))

;; ---------------------------------------------------------------------------
;; Running

(defn- placeholder [ctx k]
  (case k
    "ROOM" (:room ctx)
    "DIR" (or (get-in ctx [:placeholders "DIR"]) (:results ctx) (:sources-dir ctx))
    (get-in ctx [:placeholders k])))

(defn- with-room
  "ctx with :room when `words` name ROOM and none is set: a new path that
  does not exist yet."
  [ctx words]
  (if (and (nil? (:room ctx)) (some #(or (= "ROOM" %) (str/starts-with? % "ROOM/")) words))
    (assoc ctx :room (str (fs/file (s/tmp-dir) "room")))
    ctx))

(defn- substitute [ctx word]
  (let [ks (concat (keys (:placeholders ctx)) ["ROOM" "DIR"])
        k (first (filter #(or (= word %) (str/starts-with? word (str % "/"))) ks))]
    (if k
      (let [v (or (placeholder ctx k) (s/fail! (str "the step names " k ", and no step gave it a value")))]
        (str v (subs word (count k))))
      word)))

(defn run-line
  "Runs `rdm-spec LINE` in the scenario's project (a new copy of the
  room's kept paths when no Given made one, kept as :dir), its placeholders replaced: ctx with :run
  and :spec-before (the spec's files before the run)."
  [ctx line]
  (let [words (s/split-args line)
        ctx (with-room ctx words)
        args (mapv #(substitute ctx %) words)
        dir (or (:dir ctx) (s/copy-of-room!))
        before (spec-files dir)]
    (assoc ctx :dir dir :run (run! ctx dir args) :spec-before before)))

(defn the-run
  "The scenario's last run; fails when nothing was run."
  [ctx]
  (or (:run ctx) (s/fail! "rdm-spec was not run")))

(defn exits!
  "Checks the last run exited `code`; returns the run."
  [ctx code]
  (let [r (the-run ctx)]
    (s/check! (= code (:exit r)) (str "it exited " (:exit r) ", not " code "\n" (s/show r)))
    r))

(defn warning-line?
  "Whether the error stream's line `l` is a warning."
  [l]
  (boolean (or (str/starts-with? l "warning: ") (str/includes? l ": warning: "))))

(defn- event-line? [l] (str/starts-with? l "event: "))

(defn cannot-run-lines
  "The lines of a run's error stream saying it cannot run."
  [run]
  (filterv #(str/starts-with? % "cannot-run: ") (s/err-lines run)))

(defn finding-lines
  "The finding lines of a run's error stream: neither warnings, events nor
  cannot-run lines."
  [run]
  (vec (remove #(or (warning-line? %) (event-line? %) (str/starts-with? % "cannot-run: ")) (s/err-lines run))))

(defn refused-naming!
  "Checks the last run exited 2, printed nothing on standard output, and
  said why in one cannot-run line naming every one of `parts`."
  [ctx & parts]
  (let [r (exits! ctx 2)]
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) (cannot-run-lines r))
              (str "no cannot-run line names all of " (pr-str parts) "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output, read as EDN; fails when it is not."
  [run]
  (try (edn/read-string (:out run))
       (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show run))))))

;; ---------------------------------------------------------------------------
;; Matching EDN with placeholders

(defn- placeholder-symbol? [x]
  (and (symbol? x) (re-matches #"[A-Z][A-Z0-9_]*" (name x))))

(defn match-edn
  "{:bound {name value}} when `got` is `want`, each upper-case symbol of
  `want` standing for any value (the same one each time); else {:at path}."
  ([want got] (match-edn want got [] {}))
  ([want got path bound]
   (cond
     (placeholder-symbol? want)
     (let [k (name want)]
       (if (and (contains? bound k) (not= (get bound k) got))
         {:at path}
         {:bound (assoc bound k got)}))

     (map? want)
     (if (and (map? got) (= (set (keys want)) (set (keys got))))
       (reduce (fn [acc k]
                 (let [r (match-edn (get want k) (get got k) (conj path k) (:bound acc))]
                   (if (:at r) (reduced r) r)))
               {:bound bound} (keys want))
       {:at path})

     (sequential? want)
     (if (and (sequential? got) (= (count want) (count got)))
       (reduce (fn [acc [i w g]]
                 (let [r (match-edn w g (conj path i) (:bound acc))]
                   (if (:at r) (reduced r) r)))
               {:bound bound} (map vector (range) want got))
       {:at path})

     (and (number? want) (number? got)) (if (== want got) {:bound bound} {:at path})
     :else (if (= want got) {:bound bound} {:at path}))))

;; ---------------------------------------------------------------------------
;; The shared steps: running and reading a run

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with that (?:results )?directory as DIR"
  (fn [ctx line] (run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (some #{"--results"} (s/split-args line))) "the line gives --results")
    (run-line (update ctx :env assoc "RESULTS_DIR" nil) line)))

(defstep #"it exits (\d)"
  (fn [ctx code] (exits! ctx (parse-long code)) nil))

(defstep #"it passes"
  (fn [ctx] (exits! ctx 0) nil))

(defstep #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [r (the-run ctx)
          got (stdout-edn r)
          want (edn/read-string doc)
          m (match-edn want got)]
      (s/check! (not (:at m)) (str "standard output differs at " (pr-str (:at m)) "\n" (s/show r)))
      (assoc ctx :bound (:bound m)))))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the command did not run\n" (s/show r)))
      (s/check! (:spec-before ctx) "the spec was not kept aside before the run")
      (let [now (spec-files (:dir ctx))
            before (:spec-before ctx)
            changed (sort (filter #(not= (get before %) (get now %)) (distinct (concat (keys before) (keys now)))))]
        (s/check! (empty? changed) (str "the spec's files changed: " (pr-str changed))))
      nil)))

;; ---------------------------------------------------------------------------
;; The shared Givens: projects

(defn- results-rel [dir] (or (:results-dir (config-of dir)) "dhf/allure-results"))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (fs/delete-tree (fs/file dir (results-rel dir)))
      (assoc ctx :dir dir))))

(defn- stale! [dir k]
  (let [views (or (:views-dir (config-of dir)) "dhf/views")
        zeros (apply str (repeat 64 "0"))]
    (doseq [ext ["mmd" "svg"]
            :let [f (fs/file dir views (str k "." ext))]]
      (s/check! (fs/exists? f) (str "the project holds no view " f))
      (spit (str f) (str/replace-first (slurp (str f)) #"model-sha256: [0-9a-f]+" (str "model-sha256: " zeros))))))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          k (-> (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn")))) :views first :key)]
      (stale! dir k)
      (assoc ctx :dir dir :stale-view k))))

(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (update-config! dir #(update % :stack-words (fn [ws] (vec (distinct (conj (vec ws) word))))))
      (assoc ctx :dir dir :stack-word word))))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
          rel (str wiki "/pages/cited.md")]
      (s/write! dir rel (str "# Cited\n\nA claim a source makes.[^1]\n\n[^1]: `" citation "`, the section.\n"))
      (spit (str (fs/file dir wiki "index.md")) "\n- [Cited](pages/cited.md)\n" :append true)
      (assoc ctx :dir dir :page rel :footnote "[^1]" :citation citation))))

;; ---------------------------------------------------------------------------
;; The shared Givens: a cycle at the cut spec-3 (helpers of the kernel's own)

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(def ^:private git-env
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env git-env}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(def ^:private cycle-03
  {:cut "spec-3" :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- lint-text [scenarios]
  (str "# A fixture feature file of the kernel's steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (for [{:keys [id title level wip?]} scenarios]
                   (str "\n  " (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
                        "\n  Scenario: " title
                        "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n")))))

(def ^:private stamped
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(defn- passing-room!
  "A room whose tests exit 0, writing a passing result for each of `ids`."
  [ids]
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj"
              (str "(require '[clojure.java.io :as io])\n"
                   "(let [d (System/getenv \"RESULTS_DIR\")]\n"
                   "  (.mkdirs (io/file d))\n"
                   "  (doseq [id " (pr-str (vec ids)) "]\n"
                   "    (spit (io/file d (str id \"-result.json\"))\n"
                   "          (str \"{\\\"uuid\\\":\\\"\" id \"\\\",\\\"name\\\":\\\"\" id \"\\\",\\\"status\\\":\\\"passed\\\","
                   "\\\"labels\\\":[{\\\"name\\\":\\\"spec\\\",\\\"value\\\":\\\"\" id \"\\\"}]}\"))))\n"
                   "(System/exit 0)\n"))
    r))

(defn- cut-project!
  "A copy of the room whose spec holds only the lint's feature file with
  `scenarios`, its :verify the tests check alone, committed and tagged
  spec-3."
  [scenarios]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature (lint-text scenarios))
    (update-config! dir #(assoc % :verify [{:kind :tests :command ["bb" "test.clj"]}]))
    (git! dir "init" "-q")
    (git! dir "config" "commit.gpgsign" "false")
    (git! dir "add" "-A" "-f" "--" ".")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "spec-3")
    dir))

(defn- cut? [dir]
  (and dir (fs/exists? (fs/file dir ".git"))
       (zero? (:exit (p/shell {:dir (str dir) :out :string :err :string :continue true :in ""}
                              "git" "rev-parse" "-q" "--verify" "refs/tags/spec-3")))))

(defn- with-cycle-room [ctx]
  (if (:room ctx) ctx (assoc ctx :room (passing-room! ["spec-0012" "spec-0014"]))))

(defstep #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (let [dir (if (cut? (:dir ctx)) (:dir ctx) (cut-project! stamped))]
      (s/write! dir "dhf/cycles/03/cycle.edn" (str (pr-str cycle-03) "\n"))
      (with-cycle-room (assoc ctx :dir dir :cycle cycle-03)))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [scs (conj stamped {:title "A scenario nobody stamped"})
          dir (cut-project! scs)
          text (slurp (str (fs/file dir lint-feature)))
          line (inc (count (take-while #(not (str/includes? % "A scenario nobody stamped")) (str/split-lines text))))]
      (s/write! dir "dhf/cycles/03/cycle.edn" (str (pr-str cycle-03) "\n"))
      (with-cycle-room (assoc ctx :dir dir :unstamped {:file lint-feature :line line})))))

;; ---------------------------------------------------------------------------
;; command.feature: the tasks

(defn- help-tasks [ctx]
  (let [r (exits! ctx 0)
        ts (get-in (stdout-edn r) [:help :tasks])]
    (s/check! (seq ts) (str "the help lists no {:help {:tasks [..]}}\n" (s/show r)))
    ts))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [ts (help-tasks ctx)
          t (first (filter #(= task (:task %)) ts))]
      (s/check! t (str "the help lists no task " task))
      (s/check! (= usage (:usage t)) (str "the usage of " task " is " (pr-str (:usage t)) ", not " (pr-str usage)))
      (s/check! (= does (:does t)) (str task " is described as " (pr-str (:does t)) ", not " (pr-str does)))
      nil)))

(def ^:private task-names
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist" "status"
   "trace" "release" "score" "scoreboard" "oracle" "publish" "audit" "observe"])

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")
          l (first (filter #(str/includes? % "frobnicate") (cannot-run-lines r)))]
      (doseq [t task-names]
        (s/check! (re-find (re-pattern (str "\\b" t "\\b")) l) (str "the refusal does not list the task " t ": " l)))
      nil)))

;; --- the results directory --------------------------------------------------------

(defn- result-json [id]
  (json/generate-string {:uuid (str (random-uuid)) :name (str "a test of " id) :status "passed" :stage "finished"
                         :labels [{:name "spec" :value id}]}))

(defn- results-in! [d]
  (s/write! d (str (random-uuid) "-result.json") (result-json "spec-0001"))
  d)

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (project-set-up! ctx)
          dir (:dir ctx)]
      (update-config! dir #(assoc % :results-dir "dhf/allure-results"))
      (results-in! (str (fs/file dir "dhf/allure-results")))
      ;; the directory a scenario's --results names, relative to the project
      (results-in! (str (fs/file dir "other")))
      ctx)))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (update ctx :env assoc "RESULTS_DIR" nil)
      (let [d (results-in! (s/tmp-dir))]
        (-> ctx
            (assoc-in [:placeholders v] d)
            (update :env assoc "RESULTS_DIR" d))))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (let [r (exits! ctx 0)
          want (substitute ctx dir)
          got (get-in (stdout-edn r) [:status :results])]
      (s/check! (= want got) (str "it read " (pr-str got) ", not " (pr-str want) "\n" (s/show r)))
      nil)))

;; --- how every task reports -------------------------------------------------------

(defn- drop-level!
  "Removes the level tag of the first scenario of the project's first
  feature file that has one: {:file :line} of that scenario."
  [dir]
  (let [f (first (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))
        lines (vec (str/split-lines (slurp f)))
        i (first (keep-indexed (fn [i l] (when (re-find #"^\s*@\S.*@(e2e|contract)\b" l) i)) lines))
        _ (s/check! i (str "no tagged scenario in " f))
        changed (assoc lines i (str/replace (nth lines i) #"\s*@(e2e|contract)\b" ""))]
    (spit f (str (str/join "\n" changed) "\n"))
    {:file (str (fs/relativize dir f))
     :line (+ 2 i (count (take-while #(not (re-find #"^\s*Scenario" %)) (drop (inc i) lines))))}))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          k (-> (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn")))) :views first :key)
          at (drop-level! dir)]
      (stale! dir k)
      (assoc ctx :dir dir :stale-view k :no-level at))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          {:keys [file line]} (:no-level ctx)
          ls (finding-lines r)]
      (s/check! (some #(and (str/starts-with? % (str file ":" line ": ")) (str/includes? % "level")) ls)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\""))) ls)
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

(defstep #"a spec that passes every check"
  (fn [ctx] (project-set-up! ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :no-level (drop-level! dir)))))

(def ^:private finding-re
  "A finding line: FILE:LINE: RULE: what, FILE: RULE: what, or RULE: what."
  #"^(?:[^\s:]+(?::\d+)?: )?[a-z0-9][a-z0-9-]*: \S.*$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          ls (finding-lines r)
          {:keys [file line]} (:no-level ctx)]
      (s/check! (seq ls) (str "the error stream holds no finding\n" (s/show r)))
      (doseq [l ls]
        (s/check! (re-matches finding-re l) (str "not a finding line: " (pr-str l))))
      (s/check! (some #(re-matches (re-pattern (str "\\Q" file ":" line ": \\E[a-z0-9-]+: .+")) %) ls)
                (str "no finding names " file ":" line " with its rule\n" (s/show r)))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          v (try (edn/read {:eof ::eof} rdr) (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e)))))
          more (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::junk))]
      (s/check! (map? v) (str "standard output is not one EDN map\n" (s/show r)))
      (s/check! (= ::eof more) (str "standard output holds more than the result\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (refused-naming! ctx (results-rel (:dir ctx))) nil))
