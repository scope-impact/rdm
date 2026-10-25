(ns steps.compliance-support
  "Helpers of the compliance context's step files (no step is defined here).

  A regulated project (dhf/spec/compliance/gap.feature): a project `rdm-spec
  init` wrote, its configuration holding :regulated with the one clause list
  dhf/clauses/std.txt and the documents directory dhf/documents. By default
  std.txt holds \"STD:5.1 Development plan\" and dhf/documents/plan.md (id
  PLAN-001, revision 1, title \"Development plan\") names it.

  A releasable regulated project (dhf/spec/compliance/audit.feature): a
  regulated project with one component, demo/Alpha, whose feature file
  (under UN-001) holds spec-0001 and spec-0002, a passing result for each in
  the configured results directory, everything committed (--no-verify).

  Context keys the compliance steps set: :dir, :placeholders OUT and OUT2
  (new paths that do not exist), :changed (a file a step changed), :first-run
  (the first of two runs), :line, :out-file."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def plan-rel "dhf/documents/plan.md")
(def std-rel "dhf/clauses/std.txt")

(defn document
  "A controlled document: frontmatter, a blank line, a heading, then `body`
  (its first line is the document's line 8)."
  ([id title body] (document id 1 title body))
  ([id revision title body]
   (str "---\nid: " id "\nrevision: " revision "\ntitle: " title "\n---\n\n# " title "\n" body
        (when-not (str/ends-with? body "\n") "\n"))))

(def default-plan (document "PLAN-001" "Development plan" "We plan the work [[STD:5.1]].\n"))

(defn write!
  "Writes `text` to `rel` in the scenario's project."
  [ctx rel text]
  (s/write! (:dir ctx) rel text)
  ctx)

(defn- out-paths [ctx]
  (let [d (s/tmp-dir)]
    (-> ctx
        (assoc-in [:placeholders "OUT"] (str (fs/file d "out")))
        (assoc-in [:placeholders "OUT2"] (str (fs/file d "out2"))))))

(defn regulated!
  "ctx with :dir, a new regulated project (see the namespace doc)."
  [ctx]
  (let [ctx (kc/project-set-up! (dissoc ctx :dir))
        dir (:dir ctx)
        cfg (kc/config-of dir)]
    (s/write! dir "dhf/harness/config.edn"
              (str (pr-str (assoc cfg :regulated {:clause-lists [std-rel] :documents "dhf/documents"})) "\n"))
    (s/write! dir std-rel "STD:5.1 Development plan\n")
    (s/write! dir plan-rel default-plan)
    (out-paths ctx)))

(defn unregulated!
  "ctx with :dir, a project init wrote, with no :regulated."
  [ctx]
  (let [ctx (kc/project-set-up! (dissoc ctx :dir))]
    (s/check! (not (contains? (kc/config-of (:dir ctx)) :regulated)) "the configuration holds :regulated")
    (out-paths ctx)))

;; ---------------------------------------------------------------------------
;; git

(defn git!
  "Runs git in the project; returns its trimmed output."
  [ctx & args]
  (let [r (apply p/shell {:dir (str (:dir ctx)) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed: " (:err r)))
    (str/trim (:out r))))

(defn commit!
  "Commits everything in the project, without its hook."
  [ctx]
  (git! ctx "add" "-A")
  (git! ctx "commit" "-q" "--no-verify" "--allow-empty" "-m" "a fixture commit")
  ctx)

;; ---------------------------------------------------------------------------
;; A releasable regulated project

(def model
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                 :contexts [{:id :demo :name "demo" :description "The demo context"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha" :description "Alpha"}]}]}]
   :relationships [] :views []})

(def need-text "A user needs the work planned.")

(def feature
  (str "# A fixture feature file of the compliance context's steps.\n\n@UN-001\nFeature: Alpha\n  A fixture.\n\n"
       "  @spec-0001 @e2e\n  Scenario: The work is planned\n    Given a plan\n    Then it is followed\n\n"
       "  @spec-0002 @contract\n  Scenario: The plan has a format\n    Given a plan\n    Then it has a format\n"))

(defn results-dir [ctx]
  (str (fs/file (:dir ctx) (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results"))))

(defn result!
  "Writes a result labelled spec=`id` with `status` into the project's
  results directory."
  [ctx id status]
  (let [rd (results-dir ctx)
        uuid (str (random-uuid))]
    (fs/create-dirs rd)
    (spit (str (fs/file rd (str uuid "-result.json")))
          (str "{\"uuid\":\"" uuid "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
               "\"stage\":\"finished\",\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))
    ctx))

(defn releasable!
  "ctx with :dir, a new releasable regulated project, committed after
  `prepare` (a function of ctx) ran."
  ([ctx] (releasable! ctx identity))
  ([ctx prepare]
   (let [ctx (regulated! ctx)]
     (write! ctx "dhf/spec/model.edn" (pr-str model))
     (write! ctx "dhf/spec/needs.edn" (pr-str {"UN-001" {:text need-text :sources ["demo:README.md@abc1234"]}}))
     (write! ctx "dhf/spec/ids.edn" "{:issued 2}\n")
     (write! ctx "dhf/spec/demo/alpha.feature" feature)
     (result! ctx "spec-0001" "passed")
     (result! ctx "spec-0002" "passed")
     (let [ctx (or (prepare ctx) ctx)]
       (commit! ctx)
       ctx))))

;; ---------------------------------------------------------------------------
;; Reading OUT

(defn out-dir [ctx k] (or (get-in ctx [:placeholders k]) (s/fail! (str "no " k))))

(defn out-file
  "The text of `rel` under OUT."
  [ctx rel]
  (let [f (fs/file (out-dir ctx "OUT") rel)]
    (s/check! (fs/regular-file? f) (str "OUT holds no " rel "\n" (s/show (kc/the-run ctx))))
    (slurp (str f))))

(defn lines [text] (str/split-lines text))

(defn no-out!
  "Checks OUT was not created."
  [ctx]
  (s/check! (not (fs/exists? (out-dir ctx "OUT"))) (str "OUT was created\n" (s/show (kc/the-run ctx))))
  nil)

(defn read-edn [s] (edn/read-string s))
