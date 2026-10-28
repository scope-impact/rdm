(ns rdm-spec.verification.status-test
  "One test per rule Status enforces (05-verify-by-results/status.feature, 12, 13)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.status :as status]))

(defn sc [m] (merge {:id "spec-0007"} m))
(defn r [spec st & [params]] {:spec spec :status st :parameters params})

(deftest statuss-result-has-one-shape
  (is (= {:verified 1 :failed 0 :unverified 0 :planned 0} (status/counts {"a" :verified}))))

(deftest planned-work-is-a-scenario-tagged-wip
  (is (= :planned (status/status-of (sc {:wip? true}) [(r "spec-0007" "passed")]))))

(deftest an-outline-is-one-requirement
  (let [o (sc {:outline? true :rows [{:values {"x" "1"} :order ["x"]} {:values {"x" "2"} :order ["x"]}]})]
    (is (= :verified (status/status-of o [(r "spec-0007" "passed" [{"name" "x" "value" "1"}]) (r "spec-0007" "passed" [{"name" "x" "value" "2"}])])))
    (is (= :failed (status/status-of o [(r "spec-0007" "passed" [{"name" "x" "value" "1"}]) (r "spec-0007" "failed" [{"name" "x" "value" "2"}])])))
    (is (= :unverified (status/status-of o [(r "spec-0007" "passed" [{"name" "x" "value" "1"}])])))
    (is (= :unverified (status/status-of o [(r "spec-0007" "passed") (r "spec-0007" "passed")])))))

(deftest allure-results-are-the-oracle-whoever-wrote-them
  (is (= :verified (status/status-of (sc {}) [(r "spec-0007" "passed")])))
  (is (= :failed (status/status-of (sc {}) [(r "spec-0007" "passed") (r "spec-0007" "skipped")])))
  (is (= :unverified (status/status-of (sc {}) []))))

(deftest status-reads-every-spec-and-each-slice
  (is (= [{:slice "11-a" :status "building" :verified 1 :scenarios 2}]
         (status/slice-rows [{:slice "11-a" :status "building"}] [{:id "a" :slice "11-a"} {:id "b" :slice "11-a"}] {"a" :verified "b" :unverified}))))
