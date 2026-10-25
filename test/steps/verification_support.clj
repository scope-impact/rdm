(ns steps.verification-support
  "Helpers of the verification context's step files (no step is defined
  here): a small project the scenario makes, its feature files and its
  Allure results.

  The project (`project!`): the room's configuration without :oracle, a
  model with the bounded contexts demo (Alpha, Beta, Gamma) and architecture
  (Lint), the needs UN-001, UN-003, UN-005 and UN-007, a ledger, and the
  feature files a step gives. Results go into the project's configured
  results directory unless a step names another.

  Context keys the verification steps set: :dir (the project), :results (a
  results directory outside the project, when a step makes one), :id (the
  scenario a step is about), :ids, :expected ({id status}), :file (a result
  file), :feature-ids, :rule-ids, :scenario-id, :unknown, :need."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def model
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                 :contexts [{:id :demo :name "demo" :description "The demo context"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha" :description "Alpha"}
                                          {:id :demo/beta :name "Beta" :spec "demo/beta" :description "Beta"}
                                          {:id :demo/gamma :name "Gamma" :spec "demo/gamma" :description "Gamma"}]}
                            {:id :architecture :name "architecture" :description "The C4 model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "The lint"}]}]}]
   :relationships [] :views []})

(def needs
  {"UN-001" {:text "A user needs the first thing." :sources ["demo:README.md@abc1234"]}
   "UN-003" {:text "A user needs to know what is verified." :sources ["demo:README.md@abc1234"]}
   "UN-005" {:text "A user needs a model." :sources ["demo:README.md@abc1234"]}
   "UN-007" {:text "A user needs something nothing traces to." :sources ["demo:README.md@abc1234"]}})

;; ---------------------------------------------------------------------------
;; Feature files

(defn- scenario-text [indent {:keys [id title level wip? tags rows]}]
  (let [tag-line (str/join " " (remove nil? [(when id (str "@" id)) (str "@" (name (or level :e2e)))
                                              (when wip? "@wip") tags]))]
    (str "\n" indent tag-line "\n"
         indent (if rows "Scenario Outline: " "Scenario: ") title (when rows " <n>") "\n"
         indent "  Given a step" (when rows " with <n>") "\n"
         indent "  Then it holds\n"
         (when rows
           (str "\n" indent "  Examples:\n" indent "    | n |\n"
                (str/join (map #(str indent "    | " % " |\n") rows)))))))

(defn feature-text
  "A feature file: `tags` on the feature, its `scenarios`, then each rule of
  `rules` ({:name :tags :scenarios})."
  [{:keys [tags fname scenarios rules]}]
  (str "# A fixture feature file of the verification context's steps.\n\n"
       (when tags (str tags "\n"))
       "Feature: " (or fname "Fixture") "\n  A fixture.\n"
       (str/join (map #(scenario-text "  " %) scenarios))
       (str/join (for [r rules]
                   (str "\n" (when (:tags r) (str "  " (:tags r) "\n"))
                        "  Rule: " (:name r) "\n"
                        (str/join (map #(scenario-text "    " %) (:scenarios r))))))))

(defn results-dir
  "The configured results directory of the project `dir`, absolute."
  [dir]
  (str (fs/file dir (or (:results-dir (kc/config-of dir)) "dhf/allure-results"))))

(defn project!
  "ctx with :dir, a new project holding the feature files `features`
  ({spec-path {:tags :fname :scenarios :rules}}, spec-path as demo/alpha)."
  [ctx features]
  (let [dir (s/project!)]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/needs.edn" (pr-str needs))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (doseq [[sp f] features]
      (s/write! dir (str "dhf/spec/" sp ".feature") (feature-text f)))
    (assoc ctx :dir dir)))

(defn one-feature!
  "ctx with a project whose one feature file, demo/alpha under UN-001, holds
  `scenarios`."
  [ctx scenarios]
  (project! ctx {"demo/alpha" {:tags "@UN-001" :fname "Alpha" :scenarios scenarios}}))

;; ---------------------------------------------------------------------------
;; Allure results

(defn result-map
  "An Allure result, as any stack writes it: the minimum."
  [id status]
  (cond-> {:uuid (str (random-uuid)) :name (str "a test of " (or id "a unit"))
           :status status :stage "finished"
           :labels (if id [{:name "spec" :value id}] [{:name "suite" :value "unit"}])}
    (not= "passed" status) (assoc :statusDetails {:message (str "it " status)})))

(defn write-result!
  "Writes the Allure result `m` into the directory `rd`; returns its path."
  [rd m]
  (fs/create-dirs rd)
  (let [f (fs/file rd (str (or (:uuid m) (random-uuid)) "-result.json"))]
    (spit (str f) (json/generate-string m {:pretty true}))
    (str f)))

(defn result!
  "Writes a result labelled spec=`id` (nil: no spec label) with `status`
  into the directory `rd`; returns its path."
  [rd id status]
  (write-result! rd (result-map id status)))

(defn project-results!
  "Writes the results `[[id status] ..]` into the project's configured
  results directory."
  [ctx pairs]
  (doseq [[id st] pairs] (result! (results-dir (:dir ctx)) id st))
  ctx)

;; ---------------------------------------------------------------------------
;; Reading a run

(defn out-of
  "The last run's standard output, read."
  [ctx]
  (kc/stdout-edn (kc/the-run ctx)))

(defn statuses
  "{id status} of the last status run (status a keyword)."
  [ctx]
  (let [r (kc/the-run ctx)
        out (kc/stdout-edn r)]
    (s/check! (sequential? (:scenarios out)) (str "the report lists no :scenarios\n" (s/show r)))
    (into {} (map (juxt :id :status)) (:scenarios out))))

(defn status-of!
  "Checks the scenario `id` has the status `st` in the last status run."
  [ctx id st]
  (let [got (get (statuses ctx) id)]
    (s/check! (= st got) (str id " is " (pr-str got) ", not " st "\n" (s/show (kc/the-run ctx))))
    nil))

(defn read-edn [s] (edn/read-string s))
