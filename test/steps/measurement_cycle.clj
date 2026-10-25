(ns steps.measurement-cycle
  "Step definitions for dhf/spec/measurement/checklist.feature, and the
  fixtures the measurement context's steps share: throwaway git repositories
  holding a spec at the cut spec-3, and cycle directories (cycle.edn,
  checklist.json, ratified.edn, score.edn, verdicts files) built by hand.

  Two step texts here are also used by dhf/spec/ratification/ratify.feature
  (\"dhf/cycles/03/cycle.edn names the cut spec-3\" and \"the spec at cut spec-3
  holds a scenario with no id\"); their definitions are written to serve both:
  they leave a git repository in :dir whose tag spec-3 holds the spec, with a
  cycle.edn for cycle 03 naming that cut.

  Every Given ends by recording a snapshot of the project (:before), so a Then
  saying \"writes nothing\" can compare the project after the run with it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn git!
  "Runs git in `dir`, isolated from any user or system configuration; fails
  the step when git fails."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
                                      "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (:out r)))

(defn git-repo?
  [dir]
  (fs/directory? (fs/file dir ".git")))

(defn cut!
  "Commits everything in `dir` (a git repository, made one if it is not) and
  tags the commit `cut`, as a cut is tagged."
  [dir cut]
  (when-not (git-repo? dir) (git! dir "init" "-q" "-b" "main"))
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "--allow-empty" "-m" (str "the spec at " cut))
  (git! dir "tag" "-f" cut)
  dir)

(defn spec-project!
  "A copy of the room's allow-listed paths whose feature files are replaced by
  `features` ({\"dhf/spec/<context>/<component>.feature\" text}), committed and
  tagged `cut`."
  ([features] (spec-project! features "spec-3"))
  ([features cut]
   (let [dir (s/copy-of-room!)]
     (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
     (doseq [[path text] features] (s/write! dir path text))
     (cut! dir cut))))

(defn feature-text
  "A feature file of the component Lint (bounded context architecture) holding
  `scenarios`: [{:id \"spec-0012\" :title \"...\" :level \"e2e\" :wip? false}];
  :id nil writes an unstamped scenario."
  [scenarios]
  (str "@UN-005\nFeature: Lint\n  A feature for the measurement steps.\n\n  Rule: The lint holds the model\n"
       (str/join
        (for [{:keys [id title level wip?]} scenarios]
          (str "\n    " (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
               "\n    Scenario: " title
               "\n      Given a model\n      When \"rdm-spec check\" is run\n      Then it passes\n")))))

(defn copy-of-room-model!
  "A project holding the room's configuration and its C4 model, and no git
  repository."
  []
  (let [dir (s/project!)]
    (s/write! dir "dhf/spec/model.edn" (slurp (str (fs/file s/room-root "dhf/spec/model.edn"))))
    dir))

(def lint-feature "dhf/spec/architecture/lint.feature")

(def roles
  {:builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn cycle-path [cycle name] (str "dhf/cycles/" cycle "/" name))

(defn write-cycle!
  "Writes cycle `cycle`'s cycle.edn: the cut, the scope and the three roles,
  merged with `extra`."
  [dir cycle extra]
  (s/write! dir (cycle-path cycle "cycle.edn")
            (pr-str (merge {:cut "spec-3" :scope :project} roles extra))))

(defn snapshot
  "Every file under `dir` but .git, with its content."
  [dir]
  (into {} (for [f (fs/glob dir "**" {:hidden true})
                 :let [rel (str (fs/relativize dir f))]
                 :when (and (fs/regular-file? f) (not (str/starts-with? rel ".git/")))]
             [rel (slurp (str f))])))

(defn settle
  "The context with a snapshot of its project taken now."
  [ctx]
  (assoc ctx :before (snapshot (:dir ctx))))

(defn wrote-nothing!
  "Fails unless the project holds exactly what it held before the run."
  [ctx]
  (let [before (:before ctx)
        after (snapshot (:dir ctx))
        added (remove (set (keys before)) (keys after))
        removed (remove (set (keys after)) (keys before))
        changed (filter #(and (contains? after %) (not= (before %) (after %))) (keys before))]
    (s/check! (and (empty? added) (empty? removed) (empty? changed))
              (str "the run wrote: added " (vec added) ", removed " (vec removed) ", changed " (vec changed)))))

(defn run-result
  "The EDN the run printed on stdout."
  [ctx]
  (let [r (:run ctx)]
    (try (edn/read-string (:out r))
         (catch Exception e (s/fail! (str "stdout is not EDN: " (ex-message e) "\n" (s/show r)))))))

(defn ran-clean!
  "Fails unless the run passed: exit 0. While the spec parser, a stage the
  checklist task also runs, is not built, the command reports that as its one
  finding (exit 1); that run is accepted, nothing else."
  [r]
  (let [lines (s/err-lines r)
        only-not-built? (and (= 1 (:exit r)) (seq lines)
                             (every? #(re-find #"^not-built: rdm-spec\.spec\.parser/" %) lines))]
    (s/check! (or (zero? (:exit r)) only-not-built?) (s/show r))))

(defn read-checklist-json [ctx cycle]
  (let [f (fs/file (:dir ctx) (cycle-path cycle "checklist.json"))]
    (s/check! (fs/exists? f) (str "no " (cycle-path cycle "checklist.json") "\n" (s/show (:run ctx))))
    {:text (slurp (str f)) :data (json/parse-string (slurp (str f)) true)}))

;; ---------------------------------------------------------------------------
;; Shared with ratify.feature

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx]
    (let [dir (or (:dir ctx) (cut! (s/copy-of-room!) "spec-3"))]
      (when-not (and (git-repo? dir)
                     (zero? (:exit (p/shell {:dir dir :out :string :err :string :continue true}
                                            "git" "rev-parse" "--verify" "--quiet" "refs/tags/spec-3"))))
        (cut! dir "spec-3"))
      (write-cycle! dir "03" {})
      (settle (assoc ctx :dir dir :cycle "03" :room (or (:room ctx) (str (s/tmp-dir))))))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          file "dhf/spec/measurement/checklist.feature"
          f (fs/file dir file)
          lines (vec (str/split-lines (slurp (str f))))
          i (first (keep-indexed (fn [i l] (when (re-find #"^\s*Scenario:" l) i)) lines))
          tag-line (nth lines (dec i))]
      (s/check! (re-find #"@spec-\d+" tag-line) (str "line " i " of " file " carries no id"))
      (spit (str f) (str (str/join "\n" (assoc lines (dec i) (str/replace tag-line #"@spec-\d+\s*" ""))) "\n"))
      (cut! dir "spec-3")
      (write-cycle! dir "03" {})
      (settle (assoc ctx :dir dir :cycle "03" :room (or (:room ctx) (str (s/tmp-dir)))
                     :unstamped {:file file :line (inc i)})))))

;; ---------------------------------------------------------------------------
;; checklist.feature

(defstep #"the spec at cut spec-3 has (\d+) stamped scenarios, one of them tagged @wip"
  (fn [ctx n]
    (let [n (parse-long n)
          scs (vec (for [i (range n)]
                     {:id (format "spec-%04d" (+ 101 i)) :title (str "Scenario number " (inc i))
                      :level (if (even? i) "e2e" "contract") :wip? (= i 4)}))
          dir (spec-project! {lint-feature (feature-text (shuffle scs))})]
      (settle (assoc ctx :dir dir :scenarios scs)))))

(defstep #"dhf/cycles/03/checklist.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (ran-clean! (:run ctx))
    (let [{:keys [data]} (read-checklist-json ctx "03")
          expected (for [sc (sort-by :id (remove :wip? (:scenarios ctx)))]
                     {:id (:id sc) :title (:title sc) :context "architecture" :component "Lint" :level (:level sc)})]
      (s/check! (= (parse-long n) (count (:items data))) (str (count (:items data)) " items: " (pr-str (:items data))))
      (doseq [i (:items data)]
        (s/check! (= #{:id :title :context :component :level} (set (keys i))) (str "an item's keys: " (pr-str i))))
      (s/check! (= expected (:items data)) (str "items " (pr-str (:items data)) "\nexpected " (pr-str expected))))))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [data]} (read-checklist-json ctx "03")
          wip (first (filter :wip? (:scenarios ctx)))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] (:planned data)) (str "planned: " (pr-str (:planned data))))
      (s/check! (not-any? #(= (:id wip) (:id %)) (:items data)) "the @wip scenario is an item to grade"))))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (let [dir (spec-project! {lint-feature (feature-text [{:id "spec-0013" :title "A component that owns no feature file fails the lint"
                                                           :level "e2e" :wip? true}
                                                          {:id "spec-0012" :title "A view with no description fails the lint"
                                                           :level "e2e"}])})]
      (write-cycle! dir "03" {})
      (settle (assoc ctx :dir dir :cycle "03" :room (or (:room ctx) (str (s/tmp-dir))))))))

(defstep #"dhf/cycles/03/checklist.json holds:"
  (fn [ctx doc]
    (ran-clean! (:run ctx))
    (let [{:keys [data text]} (read-checklist-json ctx "03")
          expected (json/parse-string doc true)]
      (s/check! (= expected data) (str "checklist.json holds\n" text "\nexpected\n" doc))
      (assoc ctx :checklist-text text))))

(defn- key-order [text ks]
  (map #(str/index-of text (str "\"" % "\"")) ks))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (:checklist-text ctx)
          {:keys [data]} (read-checklist-json ctx "03")
          top (key-order text ["cycle" "cut" "items" "planned"])]
      (s/check! (and (every? some? top) (apply < top)) (str "top-level keys out of order:\n" text))
      ;; each item's keys: id, title, context, component, level
      (let [item-text (subs text (str/index-of text "\"items\"") (str/index-of text "\"planned\""))
            positions (key-order item-text ["id" "title" "context" "component" "level"])]
        (s/check! (and (every? some? positions) (apply < positions)) (str "an item's keys out of order:\n" text)))
      (doseq [k [:items :planned]]
        (s/check! (= (sort (map :id (k data))) (map :id (k data))) (str (name k) " are not sorted by id"))))))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [r (:run ctx)
          {:keys [file line]} (:unstamped ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (wrote-nothing! ctx)
      (s/check! (str/includes? (:err r) (str file ":" line ":")) (str "no finding names " file ":" line "\n" (s/show r))))))
