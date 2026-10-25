(ns rdm-spec.verification.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.trace :as trace]))

(def spec
  {:needs [{:id "UN-003" :text "Know." :sources []} {:id "UN-007" :text "Nothing." :sources []}]
   :scenarios [{:id "spec-0001" :title "One" :needs ["UN-003"] :component-name "Alpha" :context-name "demo" :level :e2e}
               {:id "spec-0002" :title "Two" :needs ["UN-001"] :component-name "Beta" :context-name "demo" :level :contract}]})

(deftest a-need-traces-to-its-scenarios
  (is (= {:kind :need :id "UN-003" :text "Know."
          :scenarios [{:id "spec-0001" :title "One" :component "Alpha" :context "demo" :level :e2e :status :verified}]}
         (trace/trace-need spec "UN-003" {"spec-0001" :verified})))
  (testing "without results, it says no status was derived"
    (is (re-find #"no status was derived" (:note (trace/trace-need spec "UN-003" nil)))))
  (testing "a need nothing traces to says so"
    (is (re-find #"no scenario traces to UN-007" (:note (trace/trace-need spec "UN-007" {}))))))

(deftest a-scenario-traces-to-its-needs-and-results
  (let [t (trace/trace-scenario spec (first (:scenarios spec))
                                {:statuses {"spec-0001" :failed}
                                 :by-id {"spec-0001" [{:file "r/a" :status "passed"} {:file "r/b" :status "failed"}]}})]
    (is (= [{:id "UN-003" :text "Know."}] (:needs t)))
    (is (= :failed (:status t)))
    (is (= [{:file "r/a" :status :passed} {:file "r/b" :status :failed}] (:results t))))
  (is (nil? (:status (trace/trace-scenario spec (first (:scenarios spec)) nil)))))
