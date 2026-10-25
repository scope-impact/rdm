(ns rdm-spec.verification.trace-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.trace :as trace]))

(def scs [{:id "spec-0001" :name "a" :needs ["UN-001" "UN-003"] :component "A" :level :e2e}
          {:id "spec-0002" :name "b" :needs ["UN-001"] :component "B" :level :contract}])

(deftest a-need-lists-its-scenarios
  (let [t (trace/trace-need "UN-003" {:text "t"} scs {:derived? false})]
    (is (= ["spec-0001"] (map :id (:scenarios t))))
    (is (= trace/no-status (:status-note t))))
  (let [t (trace/trace-need "UN-007" {:text "t"} scs {:derived? false})]
    (is (empty? (:scenarios t)))
    (is (= "no scenario traces to UN-007" (:note t)))))

(deftest a-scenario-carries-its-status-and-results-when-derived
  (let [derived {:derived? true :results "r"
                 :scenarios [{:id "spec-0001" :status :failed
                              :results [{:status "passed" :file "x"} {:status "failed" :file "y"}]}]}
        t (trace/trace-scenario (first scs) {"UN-001" {:text "one"}} derived)]
    (is (= :failed (:status t)))
    (is (= 2 (count (:results t)))))
  (let [t (trace/trace-scenario (first scs) {} {:derived? false})]
    (is (nil? (:status t)))
    (is (= trace/no-status (:note t)))))
