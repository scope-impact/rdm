(ns steps.measurement-cycle
  "The measurement context's cycle fixtures, used by its step files
  (checklist, scorer, scoreboard, oracle). No step is defined here.

  A cycle project is a copy of the room's allow-listed paths whose spec holds
  only the feature files a step gives ({path [scenario]}), a git repository
  whose cut is a tag; its cycles live under dhf/cycles/NN/.

  A scenario is {:id :title [:level \"e2e\"] [:wip? bool] [:no-id? bool]}.

  API: lint-feature, default-scenarios, default-cycle, feature-text,
  feature-text-of, project!, git!, cut!, cycle-path, write-cycle!, room!,
  passing-room!, write-edn!, read-edn, checklist-json, write-checklist!,
  write-ratified!, numbered.

  Other contexts' step files (spec_parser.clj, ratification_ratify.clj)
  require this namespace too; the spec says a helper needed from elsewhere is
  copied in (dhf/spec/README.md, \"Step definitions are the scope's own\"),
  so the API above is kept stable for them until they copy it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.support :as s]))

(def lint-feature "dhf/spec/architecture/lint.feature")

(def default-scenarios
  "spec-0012 and spec-0014 to grade, spec-0013 planned."
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def default-cycle
  {:cut "spec-3"
   :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn numbered
  "`n` scenarios numbered from spec-`from`."
  [from n]
  (vec (for [i (range n)]
         {:id (format "spec-%04d" (+ from i)) :title (str "Scenario number " (+ from i))})))

(defn- scenario-text [{:keys [id title level wip? no-id?]}]
  (str "\n  " (str/join " " (remove nil? [(when-not no-id? (str "@" id)) (str "@" (or level "e2e"))
                                          (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn feature-text-of
  "The text of a feature file named `feature` holding `scenarios`."
  [feature scenarios]
  (str "# A fixture feature file of the measurement context's steps.\n\n@UN-005\nFeature: " feature
       "\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn feature-text
  "The text of the lint feature file holding `scenarios`."
  [scenarios]
  (feature-text-of "Lint" scenarios))

(defn- feature-name [path]
  (let [b (str/replace (str (fs/file-name path)) #"\.feature$" "")]
    (str (str/upper-case (subs b 0 1)) (subs b 1))))

(defn project!
  "A copy of the room whose spec holds only `features`, {path [scenario]}."
  [features]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (doseq [[path scs] features]
      (s/write! dir path (feature-text-of (feature-name path) scs)))
    dir))

(defn git!
  "Runs git in `dir`; fails the step when it fails. Its trimmed output."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn cut!
  "Commits everything in `dir` outside dhf/cycles (a repository made when
  there is none) and tags it `tag`."
  [dir tag]
  (when-not (fs/exists? (fs/file dir ".git"))
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid")
    (git! dir "config" "commit.gpgsign" "false"))
  (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "the cut " tag))
  (git! dir "tag" "-f" tag)
  dir)

(defn cycle-path
  "dhf/cycles/<cycle>/<file>."
  [cycle file]
  (str "dhf/cycles/" cycle "/" file))

(defn write-edn!
  "Writes `x` as EDN to `rel` under `dir`."
  [dir rel x]
  (s/write! dir rel (str (pr-str x) "\n")))

(defn read-edn
  "The EDN of `rel` under `dir`, or nil when there is no such file."
  [dir rel]
  (let [f (fs/file dir rel)]
    (when (fs/exists? f) (edn/read-string (slurp (str f))))))

(defn write-cycle!
  "Writes cycle `cycle`'s cycle.edn: the default cycle with `overrides` (a nil
  value removes the key). Returns the cycle written."
  [dir cycle overrides]
  (let [m (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k v))) default-cycle overrides)]
    (write-edn! dir (cycle-path cycle "cycle.edn") m)
    m))

(defn room!
  "A small room whose tests exit 0."
  []
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj" "(System/exit 0)\n")
    r))

(defn passing-room!
  "A room whose tests exit 0 and write a passing Allure result for each of `ids`."
  [ids]
  (let [r (room!)]
    (s/write! r "test.clj"
              (str "(require '[cheshire.core :as json])\n"
                   "(def results-dir (System/getenv \"RESULTS_DIR\"))\n"
                   "(doseq [[i spec] (map-indexed vector " (pr-str (vec ids)) ")]\n"
                   "  (spit (str results-dir \"/r\" i \"-result.json\")\n"
                   "        (json/generate-string {:uuid (str \"r\" i) :name spec :status \"passed\"\n"
                   "                               :steps [] :labels [{:name \"spec\" :value spec}]})))\n"
                   "(System/exit 0)\n"))
    r))

(defn checklist-json
  "The text of a checklist.json (the contract of spec-0210)."
  [cycle cut items planned]
  (json/generate-string
   (array-map "cycle" cycle "cut" cut
              "items" (mapv (fn [{:keys [id title context component level]}]
                              (array-map "id" id "title" title "context" (or context "architecture")
                                         "component" (or component "Lint") "level" (or level "e2e")))
                            items)
              "planned" (mapv (fn [{:keys [id title]}] (array-map "id" id "title" title)) planned))
   {:pretty true}))

(defn write-checklist!
  "Writes cycle `cycle`'s checklist.json, its items and planned scenarios."
  [dir cycle cut items planned]
  (s/write! dir (cycle-path cycle "checklist.json") (checklist-json cycle cut items planned)))

(defn write-ratified!
  "Writes cycle `cycle`'s ratified.edn with `outcome` and the room tree `tree`."
  [dir cycle outcome tree]
  (write-edn! dir (cycle-path cycle "ratified.edn")
              (cond-> {:cut "spec-3" :room-tree tree :outcome outcome
                       :checkout (cycle-path cycle "build")}
                (= :refused outcome) (assoc :reason "the test command exited 1"))))
