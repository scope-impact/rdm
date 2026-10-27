(ns rdm-spec.kernel.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.runner :as runner]))

(def defs [{:pattern #"a spec" :f identity :where "one"}
           {:pattern #"a (\w+)" :f identity :where "two"}
           {:pattern #"the spec holds (\d+) features" :f identity :where "three"}])

(deftest a-step-matches-its-definitions
  (is (= ["20"] (:captured (first (runner/matches defs "the spec holds 20 features")))))
  (is (= 2 (count (runner/matches defs "a spec"))))
  (is (empty? (runner/matches defs "nothing"))))

(deftest a-then-that-does-not-hold-fails-and-anything-else-breaks
  (is (= "failed" (runner/status-of-error "Then" (AssertionError. "no"))))
  (is (= "failed" (runner/status-of-error "Then" (ex-info "no" {::runner/failed true}))))
  (is (= "broken" (runner/status-of-error "Given" (AssertionError. "no"))))
  (is (= "broken" (runner/status-of-error "Then" (ArithmeticException. "no")))))

(deftest and-and-but-take-the-kind-of-the-step-before
  (is (= ["Given" "Given" "Then" "Then"] (runner/kinds ["Given" "And" "Then" "But"]))))

(deftest a-row-is-its-headings-values
  (is (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}]
         (runner/parameters ["written" "kept"] ["plan" "3"])))
  (is (= "spec-0013|plan|3" (runner/history-id "spec-0013" ["plan" "3"])))
  (is (= "spec-0013" (runner/history-id "spec-0013" nil))))

(deftest a-step-text-takes-the-row-s-values
  (is (= "Given plan is written" (runner/fill "Given <written> is written" {"written" "plan"}))))
