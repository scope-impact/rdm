(ns steps.verification-status
  "Step definitions for dhf/spec/verification/status.feature and
  dhf/spec/verification/results.feature (the Results reader's scenarios are
  read through the status report): `rdm-spec status` in a project of the
  verification context's steps (steps.verification-support), its results
  in the project's configured results directory.

  The report (standard output): {:results DIR :scenarios [{:id :title :file
  :line :status}] :counts {..} :unknown-results [{:file :spec}] :unreadable
  [file]}.

  Context keys set here: :dir, :id (the scenario a step is about), :ids,
  :file (a result file), :expected ({id status})."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- rd [ctx] (v/results-dir (:dir ctx)))

(defn- unit-result!
  "A result from a unit test, labelled with no scenario: the results
  directory holds a result, and it verifies nothing."
  [ctx]
  (v/result! (rd ctx) nil "passed")
  ctx)

(defn- report [ctx]
  (let [r (kc/the-run ctx)]
    (s/check! (#{0 1} (:exit r)) (str "status did not run\n" (s/show r)))
    (kc/stdout-edn r)))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a stamped scenario tagged @wip, with no result"
  (fn [ctx]
    (-> (v/one-feature! ctx [{:id "spec-0001" :title "A planned scenario" :wip? true}])
        unit-result!
        (assoc :id "spec-0001"))))

(defstep #"it lists the scenario as planned"
  (fn [ctx] (v/status-of! ctx (:id ctx) :planned)))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (assoc (v/one-feature! ctx [{:id "spec-0001" :title "A planned scenario" :wip? true}]) :id "spec-0001")))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (v/project-results! ctx [[(:id ctx) "skipped"] [(:id ctx) "passed"]])))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx] (v/status-of! ctx (:id ctx) :planned)))

;; ---------------------------------------------------------------------------
;; Derived, never written

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (-> (v/one-feature! ctx [{:id "spec-0001" :title "Passing"} {:id "spec-0002" :title "Failing"}
                             {:id "spec-0003" :title "Never run"}])
        (v/project-results! [["spec-0001" "passed"] ["spec-0002" "failed"]])
        (assoc :expected {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified}))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (doseq [[id st] (:expected ctx)] (v/status-of! ctx id st))
    nil))

;; ---------------------------------------------------------------------------
;; An outline is one requirement (the outline is the kernel's shared Given:
;; :dir and :id)

(defn- row-result! [ctx n status]
  (v/write-result! (rd ctx) (assoc (v/result-map (:id ctx) status)
                                   :name (str "An outline over " n " [n=" n "]")
                                   :parameters [{:name "n" :value (str n)}])))

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (row-result! ctx 1 "passed")
    (row-result! ctx 2 "passed")
    (row-result! ctx 3 "failed")
    ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx]
    (row-result! ctx 1 "passed")
    (row-result! ctx 2 "passed")
    ctx))

(defstep #"the scenario is (verified|failed|unverified)"
  (fn [ctx st] (v/status-of! ctx (:id ctx) (keyword st))))

;; ---------------------------------------------------------------------------
;; Allure results are the oracle

(defstep #"a stamped scenario @(spec-\d+)"
  (fn [ctx id] (assoc (v/one-feature! ctx [{:id id :title "The one scenario"}]) :id id)))

(defstep #"an Allure result labelled spec=(spec-\d+) with status (\S+)"
  (fn [ctx id st] (v/project-results! ctx [[id st]])))

(defstep #"two Allure results labelled spec=(spec-\d+), one passed and one failed"
  (fn [ctx id] (v/project-results! ctx [[id "passed"] [id "failed"]])))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (let [ids (mapv #(format "spec-%04d" %) (range 1 11))]
      (assoc (v/one-feature! ctx (for [id ids] {:id id :title (str "Scenario " id)})) :ids ids))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (v/project-results! ctx (for [id (take 9 (:ids ctx))] [id "passed"]))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (let [out (report ctx)]
      (s/check! (= 10 (count (:scenarios out))) (str "the report lists " (count (:scenarios out)) " scenarios"))
      (s/check! (= 10 (reduce + (vals (:counts out)))) (str "the report counts " (pr-str (:counts out))))
      nil)))

(defstep #"the tenth is unverified"
  (fn [ctx] (v/status-of! ctx (last (:ids ctx)) :unverified)))

;; ---------------------------------------------------------------------------
;; dhf/spec/verification/results.feature

(defn- with-spec-0001 [ctx]
  (if (:dir ctx) ctx (v/one-feature! ctx [{:id "spec-0001" :title "The one scenario"}])))

(defstep #"an Allure result labelled spec=(spec-\d+)"
  (fn [ctx id] (v/project-results! (with-spec-0001 ctx) [[id "passed"]])))

(defstep #"no scenario in the spec is tagged @(spec-\d+)"
  (fn [ctx id]
    (let [texts (map #(slurp (str %)) (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature"))]
      (s/check! (not-any? #(str/includes? % (str "@" id)) texts) (str "a scenario is tagged @" id))
      (assoc ctx :unknown id))))

(defstep #"the report lists (spec-\d+) as an unknown result"
  (fn [ctx id]
    (let [out (report ctx)]
      (s/check! (some #(= id (:spec %)) (:unknown-results out)) (str "the unknown results are " (pr-str (:unknown-results out))))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [out (report ctx)]
      (s/check! (= [["spec-0001" :unverified]] (map (juxt :id :status) (:scenarios out)))
                (str "the scenarios are " (pr-str (:scenarios out))))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (with-spec-0001 ctx)]
      (fs/create-dirs (rd ctx))
      (s/write! (rd ctx) "README.txt" "not a result\n")
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (kc/refused-naming! ctx "no test run wrote results there") nil))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (with-spec-0001 ctx)
          f (s/write! (rd ctx) "broken-result.json" "{\"status\": \"passed\", ")]
      (v/project-results! ctx [["spec-0001" "passed"]])
      (assoc ctx :file (str (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results") "/" (fs/file-name f))))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (filter #(str/includes? % (:file ctx)) (s/err-lines r))]
      (s/check! (= 1 (count ls)) (str (count ls) " lines name " (:file ctx) "\n" (s/show r)))
      (s/check! (str/includes? (first ls) "unreadable") (str "the line does not say unreadable: " (first ls)))
      (s/check! (some #{(:file ctx)} (:unreadable (report ctx))) "the report does not name the file")
      nil)))

(defn- js-result
  "A result as allure-js writes it."
  [id status]
  {:uuid (str (random-uuid)) :historyId (str (random-uuid)) :testCaseId (str (random-uuid))
   :name (str "a javascript test of " id) :fullName (str "suite.test.js#" id)
   :status status :stage "finished" :start 1700000000000 :stop 1700000000500
   :statusDetails (if (= "passed" status) {} {:message "expected true to be false" :trace "at test.js:1"})
   :steps [{:name "it does the thing" :status status :stage "finished" :steps [] :attachments [] :parameters []}]
   :attachments [] :parameters [] :links []
   :labels [{:name "language" :value "javascript"} {:name "framework" :value "jest"}
            {:name "suite" :value "suite"} {:name "host" :value "ci"} {:name "spec" :value id}]})

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [{:id "spec-0001" :title "Verified by a script"}
                                   {:id "spec-0002" :title "Failed by a script"}])]
      (v/write-result! (rd ctx) (js-result "spec-0001" "passed"))
      (v/write-result! (rd ctx) (js-result "spec-0002" "failed"))
      (s/write! (rd ctx) (str (random-uuid) "-container.json") (json/generate-string {:uuid "c" :children []}))
      (assoc ctx :expected {"spec-0001" :verified "spec-0002" :failed}))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [[id st] (:expected ctx)] (v/status-of! ctx id st))
    nil))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx] (unit-result! (with-spec-0001 ctx))))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [out (report ctx)]
      (s/check! (empty? (:unknown-results out)) (str "the unknown results are " (pr-str (:unknown-results out))))
      nil)))
