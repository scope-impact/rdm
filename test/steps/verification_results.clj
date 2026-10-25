(ns steps.verification-results
  "Step definitions for dhf/spec/verification/results.feature: Allure result
  files written by hand, as a test run in any stack would write them, read by
  `rdm-spec status` in a small project."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- known-project
  "A project with two stamped scenarios, spec-0001 and spec-0002."
  []
  (fx/one-feature (str (fx/scenario-text {:id "spec-0001"}) "\n" (fx/scenario-text {:id "spec-0002"}))))

(defn- counted-toward-nothing!
  "No scenario's status moved: spec-0001 passed and stays verified, spec-0002
  had no result and stays unverified, whatever else the directory holds."
  [ctx]
  (let [rep (fx/report ctx)]
    (s/check! (= :verified (fx/status-of rep "spec-0001")) (s/show (:run ctx)))
    (s/check! (= :unverified (fx/status-of rep "spec-0002")) (s/show (:run ctx)))
    (s/check! (= 2 (count (:scenarios rep))) (str "the report lists other scenarios\n" (s/show (:run ctx))))
    rep))

;; --- Allure results are the oracle, whoever wrote them --------------------

(defstep #"an Allure result labelled spec=(\S+)"
  (fn [ctx id]
    (-> (assoc ctx :dir (known-project) :unknown id)
        (fx/add-result! "spec-0001" "passed")
        (fx/add-result! id "passed"))))

(defstep #"no scenario in the spec is tagged @(\S+)"
  (fn [ctx id]
    (let [spec (str/join "\n" (vals (fx/spec-snapshot (:dir ctx))))]
      (s/check! (not (str/includes? spec (str "@" id))) (str "the spec tags a scenario @" id)))))

(defstep #"the report lists (\S+) as an unknown result"
  (fn [ctx id]
    (let [rep (fx/report ctx)
          unknown (:unknown-results rep)]
      (s/check! (some #(= id (:id %)) unknown) (str id " is not listed as unknown\n" (s/show (:run ctx))))
      (s/check! (str/includes? (:err (:run ctx)) id) (str "the error stream does not name " id "\n" (s/show (:run ctx)))))))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [rep (counted-toward-nothing! ctx)]
      (when-let [id (:unknown ctx)]
        (s/check! (nil? (fx/status-of rep id)) (str id " is listed as a scenario"))))))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [dir (known-project)]
      (fs/create-dirs (fx/results-of dir))
      (assoc ctx :dir dir))))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) "no test run wrote results") (s/show r))
      (s/check! (str/includes? (:err r) fx/results-dir) (str "the directory is not named\n" (s/show r))))))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [dir (known-project)
          name "0b6c3d1e-bad-result.json"]
      (s/write! (fx/results-of dir) name "{\"uuid\": \"0b6c3d1e\", \"status\": \"passed\", ")
      (assoc ctx :dir dir :bad-file name))))

(defstep #"the report names that file as unreadable"
  (fn [ctx]
    (let [r (:run ctx)
          rep (fx/report ctx)]
      (s/check! (some #(str/includes? (str (:file %)) (:bad-file ctx)) (:unreadable-files rep))
                (str "the report does not name " (:bad-file ctx) " as unreadable\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (:bad-file ctx)) (str/includes? % "unreadable")) (s/err-lines r))
                (str "no finding names " (:bad-file ctx) "\n" (s/show r))))))

(defn- js-result
  "A result as allure-js-commons writes it (its fields and labels), for a
  scenario id."
  [id status]
  (let [uuid (str (java.util.UUID/randomUUID))]
    {:uuid uuid
     :historyId (str "a1b2c3" (hash id))
     :testCaseId (str "d4e5f6" (hash id))
     :name (str "scenario " id)
     :fullName (str "features/demo.feature#" id)
     :status status
     :statusDetails {:known false :muted false :flaky false}
     :stage "finished"
     :start 1700000000000
     :stop 1700000000250
     :steps [{:name "Given a step" :status status :stage "finished" :steps [] :attachments [] :parameters []
              :statusDetails {} :start 1700000000000 :stop 1700000000200}]
     :attachments []
     :parameters []
     :links []
     :labels [{:name "language" :value "javascript"}
              {:name "framework" :value "cucumberjs"}
              {:name "host" :value "ci-runner"}
              {:name "thread" :value "pid-4242"}
              {:name "tag" :value (str "@" id)}
              {:name "spec" :value id}]}))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [dir (known-project)]
      (fx/write-result! (fx/results-of dir) (js-result "spec-0001" "passed"))
      (fx/write-result! (fx/results-of dir) (js-result "spec-0002" "failed"))
      (fx/write-result! (fx/results-of dir) (js-result "spec-0002" "passed"))
      (assoc ctx :dir dir))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    ;; The same outcomes, written as the fixtures write any other run's results,
    ;; give the same report.
    (let [js (fx/report ctx)
          other-dir (known-project)
          _ (fx/write-result! (fx/results-of other-dir) (fx/allure-result "spec-0001" "passed"))
          _ (fx/write-result! (fx/results-of other-dir) (fx/allure-result "spec-0002" "failed"))
          _ (fx/write-result! (fx/results-of other-dir) (fx/allure-result "spec-0002" "passed"))
          other (fx/report {:run (s/rdm-spec! other-dir "status")})
          view (fn [rep] (into {} (map (juxt :id :status) (:scenarios rep))))]
      (s/check! (= :verified (fx/status-of js "spec-0001")) (s/show (:run ctx)))
      (s/check! (= :failed (fx/status-of js "spec-0002")) (s/show (:run ctx)))
      (s/check! (= (view js) (view other)) (str "the reports differ: " (view js) " vs " (view other))))))

;; --- A result with no spec label verifies nothing --------------------------

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (-> (assoc ctx :dir (known-project))
        (fx/add-result! "spec-0001" "passed")
        (fx/add-result! nil "failed" {:name "a unit test" :fullName "my.ns-test/a-unit-test"
                                       :labels [{:name "suite" :value "unit"}
                                                {:name "package" :value "my.ns-test"}]}))))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [rep (fx/report ctx)]
      (s/check! (empty? (:unknown-results rep)) (str "unknown results are listed\n" (s/show (:run ctx))))
      (s/check! (empty? (:unreadable-files rep)) (str "unreadable files are listed\n" (s/show (:run ctx)))))))
