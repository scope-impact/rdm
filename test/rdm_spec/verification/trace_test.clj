(ns rdm-spec.verification.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.trace :as trace]))

(def scs [{:id "spec-0001" :name "One" :component "Alpha" :context "demo" :level :e2e :needs ["UN-001" "UN-003"]}
          {:id "spec-0002" :name "Two" :component "Beta" :context "demo" :level :contract :needs ["UN-001"]}])

(def declared {"UN-001" {:text "First." :sources ["s:p@abc1234"]} "UN-003" {:text "Third."} "UN-007" {:text "Seventh."}})

(deftest what-an-id-names
  (is (= :need (trace/id-kind "spec" "UN-003")))
  (is (= :scenario (trace/id-kind "spec" "spec-0012")))
  (is (= :scenario (trace/id-kind "req" "req-0012")))
  (is (nil? (trace/id-kind "spec" "req-0012")))
  (is (nil? (trace/id-kind "spec" "frobnicate"))))

(deftest a-need-trace-has-the-contract-shape
  (let [t (trace/need-trace "UN-001" scs declared {"spec-0001" :verified "spec-0002" :failed})]
    (is (= :need (:kind t)))
    (is (= "UN-001" (:id t)))
    (is (= "First." (:text t)))
    (is (vector? (:scenarios t)))
    (is (= [{:id "spec-0001" :title "One" :component "Alpha" :context "demo" :level :e2e :status :verified}
            {:id "spec-0002" :title "Two" :component "Beta" :context "demo" :level :contract :status :failed}]
           (:scenarios t)))))

(deftest without-results-no-status
  (let [t (trace/need-trace "UN-003" scs declared nil)]
    (is (= [nil] (map :status (:scenarios t))))
    (is (= trace/no-status-note (:note t)))
    (is (false? (:derived? t)))))

(deftest a-need-nothing-traces-to
  (let [t (trace/need-trace "UN-007" scs declared nil)]
    (is (= [] (:scenarios t)))
    (is (= "no scenario traces to UN-007" (:note t)))))

(deftest a-scenario-trace
  (testing "with a derived status and its results"
    (let [t (trace/scenario-trace (first scs) declared {:status :failed :results [{:file "a" :status "failed"}]})]
      (is (= [:scenario "spec-0001" "Alpha" "demo" :e2e :failed]
             ((juxt :kind :id :component :context :level :status) t)))
      (is (= [{:id "UN-001" :text "First."} {:id "UN-003" :text "Third."}] (:needs t)))
      (is (= [{:file "a" :status "failed"}] (:results t)))))
  (testing "without results"
    (let [t (trace/scenario-trace (first scs) declared nil)]
      (is (nil? (:status t)))
      (is (= trace/no-status-note (:note t))))))
