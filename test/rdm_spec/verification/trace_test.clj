(ns rdm-spec.verification.trace-test
  "Unit tests of the Trace component's pure parts. They make no fixture on
  disk."
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.trace :as trace]))

(def scs
  [{:id "spec-0001" :name "One" :component "Alpha" :context "demo" :level :e2e :needs ["UN-001" "UN-003"]}
   {:id "spec-0002" :name "Two" :component "Beta" :context "demo" :level :contract :needs ["UN-001"]}
   {:id "spec-0003" :name "Three" :component "Beta" :context "demo" :level :e2e :needs ["UN-003"] :planned? true}])

(def needs {"UN-001" {:text "A first"} "UN-003" {:text "A third"} "UN-007" {:text "A seventh"}})

(deftest tracing-a-need
  (testing "every scenario that carries the need"
    (let [t (trace/trace-need "UN-003" scs needs nil)]
      (is (= ["spec-0001" "spec-0003"] (map :id (:scenarios t))))
      (is (= {:id "spec-0001" :title "One" :component "Alpha" :context "demo" :level :e2e}
             (select-keys (first (:scenarios t)) [:id :title :component :context :level])))
      (is (= trace/no-status (:no-status t)))
      (testing "a planned scenario shows planned without results"
        (is (= :planned (:status (second (:scenarios t))))))))
  (testing "a need nothing traces to says so"
    (let [t (trace/trace-need "UN-007" scs needs nil)]
      (is (empty? (:scenarios t)))
      (is (= "no scenario traces to UN-007" (:message t)))))
  (testing "with results, each scenario's status"
    (let [t (trace/trace-need "UN-001" scs needs {"spec-0001" {:status :verified} "spec-0002" {:status :failed}})]
      (is (= [:verified :failed] (map :status (:scenarios t))))
      (is (nil? (:no-status t))))))

(deftest tracing-a-scenario
  (let [t (trace/trace-scenario (first scs) nil false)]
    (is (= ["UN-001" "UN-003"] (:needs t)))
    (is (= ["Alpha" "demo" :e2e] [(:component t) (:context t) (:level t)]))
    (is (false? (:derived? t)))
    (is (= trace/no-status (:no-status t))))
  (let [rs [{:file "r/a-result.json" :status "passed"} {:file "r/b-result.json" :status "failed"}]
        t (trace/trace-scenario (first scs) {:status :failed :results rs} true)]
    (is (= :failed (:status t)))
    (is (= rs (:results t)))
    (is (nil? (:no-status t)))))
