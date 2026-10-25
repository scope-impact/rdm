(ns steps.compliance-support
  "Fixtures the compliance steps share. No step is defined here.

  \"A regulated project\" (dhf/spec/compliance/gap.feature): a project set up
  by rdm-spec (`rdm-spec init`) whose configuration holds :regulated with the
  one clause list dhf/clauses/std.txt and the documents directory
  dhf/documents; by default the list holds STD:5.1 and dhf/documents/plan.md,
  with an id, a revision and a title, names it in an audit tag.

  \"A releasable regulated project\" (dhf/spec/compliance/audit.feature): a
  regulated project whose own spec (one need, two stamped scenarios) has a
  passing result for every scenario in its configured results directory, and
  whose every clause is named, committed.

  Every directory is made with steps.kernel-command/tmp-dir, so the run
  removes it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def config-rel "dhf/harness/config.edn")
(def std "dhf/clauses/std.txt")
(def plan "dhf/documents/plan.md")
(def results-rel "dhf/allure-results")

(defn document
  "A document's text: frontmatter of the [key value] pairs `front`, then
  `body`."
  [front body]
  (str "---\n" (str/join (for [[k v] front] (str k ": " v "\n"))) "---\n\n" body))

(def plan-front [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan"]])

(defn plan-doc
  "dhf/documents/plan.md's text with `body` (lines) under the default
  frontmatter, so its first body line is line 7."
  [& body-lines]
  (document plan-front (str (str/join "\n" body-lines) "\n")))

(def plan-body-start
  "The line of plan-doc's first body line."
  7)

(defn config-of [dir] (edn/read-string (slurp (str (fs/file dir config-rel)))))

(defn config! [dir f] (s/write! dir config-rel (pr-str (f (config-of dir)))))

(defn write! [ctx rel text] (s/write! (:dir ctx) rel text) ctx)

(defn initialised!
  "A project set up by rdm-spec: ctx with :dir."
  [ctx]
  (let [parent (kc/tmp-dir)
        r (kc/run! ctx parent ["init" "project"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
    (assoc ctx :dir (str (fs/file parent "project")))))

(defn regulated!
  "A regulated project: ctx with :dir."
  [ctx]
  (let [ctx (initialised! ctx)
        dir (:dir ctx)]
    (config! dir #(assoc % :regulated {:clause-lists [std] :documents "dhf/documents"}))
    (s/write! dir std "STD:5.1 Development plan\n")
    (s/write! dir plan (plan-doc "# Plan" "" "We plan the work [[STD:5.1]]."))
    ctx))

;; ---------------------------------------------------------------------------
;; A releasable regulated project

(def ^:private model
  {:system {:id :demo :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-c :name "demo" :kind :runs :description "The demo" :technology "t"
                 :contexts [{:id :demo :name "demo" :description "The demo context"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha"
                                           :description "The alpha component"}]}]}]
   :relationships [] :views []})

(def need-text "An engineer shows an auditor the record.")

(def scenario-titles
  {"spec-0001" "The first requirement holds"
   "spec-0002" "The second requirement holds"})

(def ^:private feature
  (str "@UN-001\nFeature: Alpha\n  The demo component.\n\n  Rule: The demo rule\n\n"
       (str/join "\n" (for [[id title] (sort scenario-titles)]
                        (str "    @" id " @e2e\n    Scenario: " title "\n      Given a step of " id "\n")))))

(defn result!
  "Writes an Allure result labelled spec=`id` with `status` into the
  project's results directory."
  [ctx id status]
  (let [uuid (str (java.util.UUID/randomUUID))
        dir (fs/file (:dir ctx) results-rel)]
    (fs/create-dirs dir)
    (spit (str (fs/file dir (str uuid "-result.json")))
          (json/generate-string {:uuid uuid :name (str "a test of " id) :status status :stage "finished"
                                 :labels [{:name "spec" :value id}] :steps []}))
    ctx))

(defn git!
  "Runs git in the project; fails the step on an error. The output, trimmed."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_AUTHOR_DATE" "2026-01-01T00:00:00Z"
                                      "GIT_COMMITTER_DATE" "2026-01-01T00:00:00Z"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn commit!
  "Commits every file of the project (the hook skipped: it would run an
  installed command)."
  [ctx]
  (git! (:dir ctx) "add" "-A")
  (git! (:dir ctx) "commit" "-q" "--no-verify" "--allow-empty" "-m" "the record")
  ctx)

(defn with-spec!
  "Gives the project its own spec: one need, two stamped scenarios."
  [ctx]
  (let [cfg (config-of (:dir ctx))]
    (-> ctx
        (write! (:model cfg) (pr-str model))
        (write! (:needs cfg) (pr-str {"UN-001" {:text need-text :sources ["demo:README.md@0000000"]}}))
        (write! (:ledger cfg) "{:issued 2}\n")
        (write! (str (:spec-dir cfg) "/demo/alpha.feature") feature))))

(defn releasable!
  "A releasable regulated project, committed. `statuses` {id status}
  overrides a scenario's passing result."
  ([ctx] (releasable! ctx {}))
  ([ctx statuses]
   (let [ctx (-> (regulated! ctx) with-spec!)]
     (doseq [id (sort (keys scenario-titles))]
       (result! ctx id (get statuses id "passed")))
     (commit! ctx))))

(defn out-file
  "The file `rel` under the placeholder path, as \"OUT/auditor/plan.md\"."
  [ctx rel]
  (let [[_ ph more] (re-matches #"(OUT\d*)(?:/(.*))?" rel)
        base (get-in ctx [:placeholders ph])]
    (s/check! base (str "no " ph " was given"))
    (if (str/blank? more) (fs/file base) (fs/file base more))))

(defn lines-of [f] (str/split-lines (slurp (str f))))

(defn err-line!
  "Checks some line of the run's error stream holds every one of `words`;
  returns it."
  [r & words]
  (let [l (some (fn [l] (when (every? #(str/includes? l (str %)) words) l)) (s/err-lines r))]
    (s/check! l (str "no line of the error stream names " (str/join ", " words) "\n" (s/show r)))
    l))
