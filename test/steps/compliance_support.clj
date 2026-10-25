(ns steps.compliance-support
  "Fixtures the compliance steps share (dhf/spec/compliance/gap.feature and
  audit.feature), and the one step text both features use.

  A regulated project (gap.feature's header): a project set up by `rdm-spec
  init` whose configuration holds :regulated with the one clause list
  dhf/clauses/std.txt and the documents directory dhf/documents. By default
  std.txt holds \"STD:5.1 Development plan\" and dhf/documents/plan.md, with
  an id, a revision and a title, names it on line 8.

  A releasable regulated project (audit.feature): a regulated project with
  its own spec (one component demo/plan, one need UN-001, two stamped
  scenarios), a passing result for each in a results directory outside the
  project (RESULTS_DIR, set for the scenario's commands), everything
  committed.

  Every directory made here is removed when the suite ends.

  Context keys set here: :dir (the project, as the kernel's), :env
  RESULTS_DIR, :placeholders OUT and OUT2 (new paths under a directory made
  here), :results-dir."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures, removed when the suite ends

(defonce ^:private made (atom []))

(defonce ^:private cleanup
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable (fn [] (doseq [d @made] (try (fs/delete-tree d) (catch Exception _ nil)))))))

(defn tmp-dir
  "A new temporary directory, removed when the suite ends."
  []
  (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-compliance-"}))]
    (swap! made conj d)
    d))

(defn write! [dir rel text] (s/write! dir rel text))

(defn read-config [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn write-config! [dir c] (spit (str (fs/file dir "dhf/harness/config.edn")) (pr-str c)))

(defn git!
  "Runs git in `dir`; the step fails when it does."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""}
                 "git" "-c" "user.name=steps" "-c" "user.email=steps@rdm-spec.invalid"
                 "-c" "commit.gpgsign=false" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn commit-all!
  "Commits everything in the project, the hook off (no installed rdm-spec runs)."
  [dir]
  (git! dir "add" "-A")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" "fixture")
  dir)

(defn init-project!
  "A project set up by rdm-spec: `rdm-spec init` in a new directory."
  [ctx]
  (let [parent (tmp-dir)
        r (kc/run! ctx parent ["init" "project"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
    (str (fs/file parent "project"))))

(defn document
  "A controlled document: frontmatter `front` ([[k v] ...], in order), a
  blank line, then `body` lines. With the three fields, the body starts on
  line 7."
  [front body]
  (str "---\n" (str/join (for [[k v] front] (str k ": " v "\n"))) "---\n\n"
       (str/join (map #(str % "\n") body))))

(defn plan
  "dhf/documents/plan.md with the id PLAN-001, the revision 1 and the title
  \"Development plan\", and `body`."
  [body]
  (document [["id" "PLAN-001"] ["revision" "1"] ["title" "Development plan"]] body))

(def default-plan
  "Names STD:5.1 on line 8."
  (plan ["# Plan" "We plan the work [[STD:5.1]]."]))

(defn regulated!
  "Makes the project `dir` regulated over dhf/clauses/std.txt and
  dhf/documents."
  [dir]
  (write-config! dir (assoc (read-config dir) :regulated {:clause-lists ["dhf/clauses/std.txt"]
                                                          :documents "dhf/documents"}))
  dir)

(defn regulated-project!
  "A regulated project: ctx with :dir."
  [ctx]
  (let [dir (init-project! ctx)]
    (regulated! dir)
    (write! dir "dhf/clauses/std.txt" "STD:5.1 Development plan\n")
    (write! dir "dhf/documents/plan.md" default-plan)
    (assoc ctx :dir dir)))

;; ---------------------------------------------------------------------------
;; A releasable regulated project

(def demo-model
  {:system {:id :demo :name "Demo" :description "A demo"}
   :containers [{:id :demo-c :name "demo" :kind :runs :description "d" :technology "t"
                 :contexts [{:id :demo :name "demo" :description "d"
                             :components [{:id :demo/plan :name "Planning" :spec "demo/plan" :description "d"}]}]}]})

(def need-text "A team shows an auditor where each clause is met.")

(def scenarios
  [{:id "spec-0001" :title "A plan names its clauses"}
   {:id "spec-0002" :title "A plan is reviewed"}])

(defn feature-text [scs]
  (str "@UN-001\nFeature: Planning\n  The plan of the work.\n"
       (str/join (for [{:keys [id title]} scs]
                   (str "\n  @" id " @e2e\n  Scenario: " title "\n    Given a plan\n    When it is read\n    Then it names its clauses\n")))))

(defn allure-result [id status]
  {:uuid (str "fixture-" id "-" status)
   :name (str "a test of " id) :fullName (str "tests/" id)
   :status status :statusDetails {} :stage "finished" :start 1700000000000 :stop 1700000000100
   :labels [{:name "spec" :value id} {:name "framework" :value "fixture"}]
   :steps []})

(defn write-results!
  "A results directory outside the project holding a result for each
  [id status]."
  [results]
  (let [d (str (fs/file (tmp-dir) "results"))]
    (fs/create-dirs d)
    (doseq [[id status] results
            :let [r (allure-result id status)]]
      (spit (str (fs/file d (str (:uuid r) "-result.json"))) (json/generate-string r {:pretty true})))
    d))

(defn releasable!
  "A releasable regulated project, all committed: ctx with :dir, RESULTS_DIR
  and the OUT placeholders. `opts`: :clauses {rel text} and :documents {rel
  text-or-nil} replacing the defaults, :results [[id status]]."
  ([ctx] (releasable! ctx {}))
  ([ctx {:keys [clauses documents results]}]
   (let [dir (init-project! ctx)
         out-parent (tmp-dir)]
     (regulated! dir)
     (write! dir "dhf/spec/model.edn" (pr-str demo-model))
     (write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text need-text :sources ["demo:README.md@0000000"]}}))
     (write! dir "dhf/spec/demo/plan.feature" (feature-text scenarios))
     (doseq [[rel text] (merge {"dhf/clauses/std.txt" "STD:5.1 Development plan\n"} clauses)]
       (write! dir rel text))
     (doseq [[rel text] (merge {"dhf/documents/plan.md" default-plan} documents)
             :when text]
       (write! dir rel text))
     (commit-all! dir)
     (let [rd (write-results! (or results (for [{:keys [id]} scenarios] [id "passed"])))]
       (-> ctx
           (assoc :dir dir :results-dir rd)
           (assoc-in [:env "RESULTS_DIR"] rd)
           (assoc-in [:placeholders "OUT"] (str (fs/file out-parent "out")))
           (assoc-in [:placeholders "OUT2"] (str (fs/file out-parent "out2"))))))))

(defn out-file
  "The file `rel` under the placeholder `ph` (OUT) of the scenario."
  [ctx ph rel]
  (let [o (get-in ctx [:placeholders ph])]
    (s/check! o (str "no " ph " was given"))
    (fs/file o rel)))

(defn creates-no-out! [ctx]
  (let [o (get-in ctx [:placeholders "OUT"])]
    (s/check! o "no OUT was given")
    (s/check! (not (fs/exists? o)) (str "OUT was created: " o "\n" (s/show (kc/the-run ctx))))))

(defn line-naming!
  "Checks some line of the error stream holds every one of `parts`; returns it."
  [r & parts]
  (or (some (fn [l] (when (every? #(str/includes? l (str %)) parts) l)) (s/err-lines r))
      (s/fail! (str "no line of the error stream names " (str/join ", " (map pr-str parts)) "\n" (s/show r)))))

;; ---------------------------------------------------------------------------
;; The step text both compliance features use

(defstep #"a project set up by rdm-spec whose configuration holds no :regulated"
  (fn [ctx]
    (let [dir (init-project! ctx)]
      (s/check! (not (contains? (read-config dir) :regulated)) "the configuration holds :regulated")
      (assoc-in (assoc ctx :dir dir) [:placeholders "OUT"] (str (fs/file (tmp-dir) "out"))))))
