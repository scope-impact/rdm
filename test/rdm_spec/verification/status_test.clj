(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.status :as status]))

(def outline {:id "spec-0001" :outline? true :examples [{:headings ["n"] :rows [["1"] ["2"]]}]})
(defn r [s & [n]] (cond-> {:spec "spec-0001" :status s} n (assoc :parameters [{"name" "n" "value" n}])))

(deftest a-scenario-s-status-is-derived-from-its-results
  (is (= :unverified (status/status-of {:id "spec-0001"} [])))
  (is (= :verified (status/status-of {:id "spec-0001"} [(r "passed")])))
  (is (= :failed (status/status-of {:id "spec-0001"} [(r "passed") (r "unknown")])))
  (is (= :planned (status/status-of {:id "spec-0001" :wip? true} [(r "passed")]))))

(deftest an-outline-is-verified-by-every-row
  (is (= :verified (status/status-of outline [(r "passed" "1") (r "passed" "2")])))
  (is (= :unverified (status/status-of outline [(r "passed" "1")])))
  (is (= :unverified (status/status-of outline [(r "passed") (r "passed")])))
  (is (= :failed (status/status-of outline [(r "passed" "1") (r "failed" "2")]))))
