(ns steps.verification-fixtures
  "Fixtures the verification steps share: small projects holding their own
  spec (feature files, needs, model, ledger), and Allure result files written
  as any test runner would write them. No step is defined here."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [steps.support :as s]))

(def results-dir
  "Where a fixture project's configuration says its results are."
  "dhf/allure-results")

(def demo-model
  {:system {:id :demo :name "Demo" :description "A demo"}
   :containers [{:id :demo-c :name "demo" :kind :runs :description "d" :technology "t"
                 :contexts [{:id :demo :name "demo" :description "d"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha" :description "d"}
                                          {:id :demo/beta :name "Beta" :spec "demo/beta" :description "d"}
                                          {:id :demo/gamma :name "Gamma" :spec "demo/gamma" :description "d"}]}]}]})

(def demo-needs
  {"UN-001" {:text "A first need." :sources ["demo:README.md@0000000"]}
   "UN-003" {:text "A third need." :sources ["demo:README.md@0000000"]}
   "UN-007" {:text "A seventh need." :sources ["demo:README.md@0000000"]}})

(defn scenario-text
  "One scenario: {:id \"spec-0001\" :name .. :tags [\"@wip\"] :level \"@e2e\"}."
  [{:keys [id name tags level]}]
  (str "    " (str/join " " (concat (when id [(str "@" id)]) [(or level "@e2e")] tags)) "\n"
       "    Scenario: " (or name (str "Scenario " id)) "\n"
       "      Given a step of " (or id "an unstamped scenario") "\n"))

(defn outline-text
  "One scenario outline with `n` example rows."
  [id n]
  (str "    @" id " @contract\n"
       "    Scenario Outline: An outline over <n>\n"
       "      Given the value <n>\n\n"
       "      Examples:\n"
       "        | n |\n"
       (str/join (for [i (range 1 (inc n))] (str "        | " i " |\n")))))

(defn feature-text
  "A feature file: feature tags, a name, and rules, each {:name :tags :body}
  where :body is scenario text."
  [{:keys [tags name rules]}]
  (str (when (seq tags) (str (str/join " " tags) "\n"))
       "Feature: " name "\n"
       "  A feature for the verification steps.\n"
       (str/join
        (for [{rn :name rtags :tags body :body} rules]
          (str "\n"
               (when (seq rtags) (str "  " (str/join " " rtags) "\n"))
               "  Rule: " rn "\n\n"
               body)))))

(defn project!
  "A temporary project with its own spec: `features` maps a component's spec
  path (\"demo/alpha\") to feature text. Returns the directory."
  ([features] (project! features {}))
  ([features {:keys [needs]}]
   (let [dir (s/project! {:results-dir results-dir})]
     (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
     (s/write! dir "dhf/spec/needs.edn" (pr-str (or needs demo-needs)))
     (s/write! dir "dhf/spec/model.edn" (pr-str demo-model))
     (s/write! dir "dhf/spec/events.edn" (slurp (str (fs/file s/room-root "dhf/spec/events.edn"))))
     (doseq [[spec-path text] features]
       (s/write! dir (str "dhf/spec/" spec-path ".feature") text))
     dir)))

(defn one-feature
  "A project whose one component, demo/alpha, holds `body` (scenario text)
  under a feature tagged @UN-001."
  [body]
  (project! {"demo/alpha" (feature-text {:tags ["@UN-001"] :name "Alpha"
                                         :rules [{:name "Demo rule" :body body}]})}))

(defn allure-result
  "An Allure result as a test runner writes it. `spec` may be nil (no spec
  label). `extra` is merged in."
  ([spec status] (allure-result spec status {}))
  ([spec status extra]
   (let [uuid (str (java.util.UUID/randomUUID))]
     (merge {:uuid uuid
             :historyId (str (hash [spec status uuid]))
             :name (str "a test of " (or spec "something"))
             :fullName (str "tests/" (or spec "unit"))
             :status status
             :statusDetails {}
             :stage "finished"
             :start 1700000000000
             :stop 1700000000100
             :labels (vec (concat (when spec [{:name "spec" :value spec}])
                                  [{:name "framework" :value "fixture"}]))
             :steps []}
            extra))))

(defn write-result!
  "Writes `result` (a map) into `dir` as <uuid>-result.json. Returns the file."
  [dir result]
  (fs/create-dirs dir)
  (let [f (fs/file dir (str (:uuid result) "-result.json"))]
    (spit f (json/generate-string result {:pretty true}))
    (str f)))

(defn results-of
  "The configured results directory of the project `dir` (what its
  dhf/harness/config.edn says, else the fixtures' own)."
  [dir]
  (let [cfg (try (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))) (catch Exception _ nil))]
    (str (fs/file dir (or (:results-dir cfg) results-dir)))))

(defn scenario-ids
  "The ids of the scenarios in the project `dir` whose parsed form satisfies
  `pred` (a kernel Gherkin scenario), in file order. Used where a Given another
  context defines built the project."
  [dir pred]
  (let [cfg (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))]
    (vec (for [f (sort (map str (fs/glob (fs/file dir (:spec-dir cfg "dhf/spec")) "**.feature")))
               sc (:scenarios (gherkin/parse-feature f (slurp f)))
               :when (pred sc)
               t (:tags sc)
               :when (re-matches #"@spec-\d+" t)]
           (subs t 1)))))

(defn add-result!
  "Writes a result for `spec` with `status` into the project's results."
  ([ctx spec status] (add-result! ctx spec status {}))
  ([ctx spec status extra]
   (let [f (write-result! (results-of (:dir ctx)) (allure-result spec status extra))]
     (update ctx :result-files (fnil conj []) f))))

(defn report
  "The EDN result the last run printed on stdout."
  [ctx]
  (let [r (:run ctx)]
    (s/check! (:run ctx) "the command was not run")
    (try (edn/read-string (:out r))
         (catch Exception e (s/fail! (str "stdout is not EDN: " (ex-message e) "\n" (s/show r)))))))

(defn status-of
  "The status the report gives the scenario `id`, as a keyword, or nil."
  [rep id]
  (some #(when (= id (:id %)) (keyword (name (:status %)))) (:scenarios rep)))

(defn spec-snapshot
  "Every file under dhf/spec of `dir` with its content."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn planned-id
  "The id of the one scenario tagged @wip in the project `dir`, or the step
  fails."
  [dir]
  (let [ids (scenario-ids dir #(some #{"@wip"} (:all-tags %)))]
    (s/check! (= 1 (count ids)) (str "the project holds " (count ids) " planned scenarios"))
    (first ids)))
