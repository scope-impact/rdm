(ns rdm-spec.verification.trace-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.trace :as trace]))

(deftest an-id-is-a-need-or-a-scenario
  (is (= :need (trace/kind-of "UN-003")))
  (is (= :scenario (trace/kind-of "spec-0012"))))
