(ns rdm-spec.verification.trace-test
  "One test per rule Trace enforces (05-verify-by-results/trace.feature, 12-several-specs/trace.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.trace :as trace]))

(def scs [{:id "spec-0001" :title "A" :needs ["UN-003"] :component-name "Cart" :context :shop :level :e2e}
          {:id "spec-0002" :title "B" :needs ["UN-001"] :component-name "Cart" :context :shop :level :e2e}])

(deftest a-need-traces-to-its-scenarios
  (is (= ["spec-0001"] (map :id (:scenarios (trace/need-trace {:id "UN-003" :text "t"} scs nil))))))

(deftest a-scenario-traces-to-its-need-and-its-evidence
  (is (= {:kind :scenario :id "spec-0001" :needs ["UN-003"] :component "Cart" :context "shop" :level :e2e}
         (select-keys (trace/scenario-trace (first scs) nil nil) [:kind :id :needs :component :context :level]))))

(deftest the-traces-result-has-one-shape
  (is (= #{:id :title :component :context :level :status} (set (keys (trace/scenario-row (first scs) {"spec-0001" :verified}))))))

(deftest the-trace-refuses-what-it-cannot-read
  (is (thrown? clojure.lang.ExceptionInfo (trace/find-target "spec-0999" [{:needs [] :scenarios scs :name nil}] nil))))

(deftest a-trace-reads-the-spec-that-holds-what-it-traces
  (is (thrown? clojure.lang.ExceptionInfo (trace/find-target "UN-001" [{:name "auth" :needs [{:id "UN-001"}] :scenarios []}
                                                                       {:name "infra" :needs [{:id "UN-001"}] :scenarios []}] nil))))
