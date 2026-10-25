(ns steps.compliance-support
  "Fixtures the compliance steps share, and the step text both of the
  context's features use (defined here, when this file loads):

    a project set up by rdm-spec whose configuration holds no :regulated

  Two kinds of regulated project (dhf/spec/compliance/gap.feature and
  audit.feature):
    `regulated!`   a project set up by rdm-spec (init) holding :regulated with
                   the clause list dhf/clauses/std.txt (STD:5.1) and
                   dhf/documents/plan.md naming it; for \"rdm-spec check\".
    `releasable!`  a small project with its own spec (two stamped scenarios
                   under UN-001, both passing), :regulated as above, the
                   clause STD:5.1 named in dhf/documents/quality.md and
                   dhf/documents/plan.md, committed; for \"rdm-spec audit\".
                   An untracked file lies beside it, which never counts.
  Every directory is made with the kernel's tmp-dir, so it is removed when
  the run ends."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def regulated-config {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})

(defn needs-compliance! []
  (s/needs-built! 'rdm-spec.compliance.gap 'rdm-spec.compliance.audit))

(defn document
  "A controlled document's text: frontmatter (each of :id :revision :title
  that is given), a blank line, then `body`."
  [{:keys [id revision title] :or {id "PLAN-001" revision 1 title "Development plan"}} body]
  (str "---\n"
       (when id (str "id: " id "\n"))
       (when revision (str "revision: " revision "\n"))
       (when title (str "title: " title "\n"))
       "---\n\n" body "\n"))

(defn config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn config! [dir f]
  (s/write! dir "dhf/harness/config.edn" (pr-str (f (config-of dir)))))

(defn init-project!
  "A project set up by rdm-spec init. Returns its directory."
  [ctx]
  (let [parent (kc/tmp-dir)
        r (kc/run! ctx parent ["init" "project"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
    (str (fs/file parent "project"))))

(defn regulated!
  "ctx with :dir a regulated project set up by rdm-spec."
  [ctx]
  (needs-compliance!)
  (let [dir (init-project! ctx)]
    (config! dir #(assoc % :regulated regulated-config))
    (s/write! dir "dhf/clauses/std.txt" "STD:5.1 Development plan\n")
    (s/write! dir "dhf/documents/plan.md" (document {} "We plan the work [[STD:5.1]]."))
    (assoc ctx :dir dir)))

;; ---------------------------------------------------------------------------
;; A releasable regulated project (copied in from the verification fixtures'
;; shape: a small spec of its own and Allure results written by hand)

(def results-dir "dhf/allure-results")

(def demo-model
  {:system {:id :demo :name "Demo" :description "A demo"}
   :containers [{:id :demo-c :name "demo" :kind :runs :description "d" :technology "t"
                 :contexts [{:id :demo :name "demo" :description "d"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha" :description "d"}]}]}]})

(def demo-needs
  {"UN-001" {:text "A first need." :sources ["demo:README.md@0000000"]}})

(def feature-text
  (str "@UN-001\nFeature: Alpha\n  A feature for the compliance steps.\n\n"
       "  Rule: Demo rule\n\n"
       "    @spec-0001 @e2e\n    Scenario: The first scenario\n      Given a step of spec-0001\n\n"
       "    @spec-0002 @e2e\n    Scenario: The second scenario\n      Given a step of spec-0002\n"))

(def scenario-titles {"spec-0001" "The first scenario" "spec-0002" "The second scenario"})

(defn write-result!
  "Writes an Allure result for `spec` with `status` into the project's results."
  [dir spec status]
  (let [uuid (str (java.util.UUID/randomUUID))
        result {:uuid uuid :historyId uuid :name (str "a test of " spec) :fullName (str "tests/" spec)
                :status status :statusDetails {} :stage "finished" :start 1700000000000 :stop 1700000000100
                :labels [{:name "spec" :value spec} {:name "framework" :value "fixture"}] :steps []}]
    (s/write! dir (str results-dir "/" uuid "-result.json") (json/generate-string result {:pretty true}))))

(defn git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""} "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (:out r)))

(defn commit!
  "Commits everything but the untracked file in the project of ctx; ctx."
  [ctx]
  (let [dir (:dir ctx)]
    (git! dir "add" "-A" "--" "." ":(exclude)untracked.txt")
    (git! dir "-c" "user.name=steps" "-c" "user.email=steps@rdm-spec.invalid"
          "commit" "-q" "--allow-empty" "-m" "the record")
    ctx))

(defn releasable!
  "ctx with :dir a releasable regulated project, committed. `statuses`
  {id status} are its results (default: both passed)."
  ([ctx] (releasable! ctx {"spec-0001" "passed" "spec-0002" "passed"}))
  ([ctx statuses]
   (needs-compliance!)
   (let [dir (s/project! {:results-dir results-dir :regulated regulated-config})]
     (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
     (s/write! dir "dhf/spec/needs.edn" (pr-str demo-needs))
     (s/write! dir "dhf/spec/model.edn" (pr-str demo-model))
     (s/write! dir "dhf/spec/events.edn" (slurp (str (fs/file s/room-root "dhf/spec/events.edn"))))
     (s/write! dir "dhf/spec/demo/alpha.feature" feature-text)
     (doseq [[id st] (sort statuses)] (write-result! dir id st))
     (s/write! dir "dhf/clauses/std.txt" "STD:5.1 Development plan\n")
     (s/write! dir "dhf/documents/quality.md"
               (document {:id "QUAL-001" :title "Quality manual"} "This manual meets [[STD:5.1]]."))
     (s/write! dir "dhf/documents/plan.md" (document {} "We plan the work [[STD:5.1]]."))
     (s/write! dir "untracked.txt" "never committed\n")
     (git! dir "init" "-q")
     (commit! (assoc ctx :dir dir)))))

;; ---------------------------------------------------------------------------
;; Reading a run

(defn result-of
  "The EDN result the last run printed."
  [ctx]
  (let [r (kc/the-run ctx)]
    (or (kc/stdout-edn r) (s/fail! (str "standard output is not EDN\n" (s/show r))))))

(defn out-dir
  "The path the placeholder `ph` (OUT, OUT2) stood for in the last run."
  [ctx ph]
  (or (get-in ctx [:placeholders ph]) (s/fail! (str "no command was run with " ph))))

(defn out-file [ctx rel]
  (let [[ph & more] (str/split rel #"/" 2)]
    (fs/file (out-dir ctx ph) (or (first more) ""))))

(defn lines-of [f]
  (s/check! (fs/regular-file? f) (str "no file " f))
  (str/split (slurp (str f)) #"\n" -1))

;; ---------------------------------------------------------------------------
;; The context's shared step

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx]
    (needs-compliance!)
    (let [dir (init-project! ctx)]
      (s/check! (not (contains? (config-of dir) :regulated)) "the configuration holds :regulated")
      (assoc ctx :dir dir))))
