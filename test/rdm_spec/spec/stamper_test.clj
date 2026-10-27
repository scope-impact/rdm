(ns rdm-spec.spec.stamper-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.stamper :as stamper]))

(def text "@UN-001\nFeature: A\n\n  @e2e\n  Scenario: One\n    Given x\n\n  Scenario: Two\n    Given y\n")

(deftest an-id-joins-the-tag-line-or-gets-its-own
  (is (= "@UN-001\nFeature: A\n\n  @spec-0042 @e2e\n  Scenario: One\n    Given x\n\n  @spec-0043\n  Scenario: Two\n    Given y\n"
         (stamper/stamp-text text [[5 "spec-0042"] [8 "spec-0043"]]))))

(deftest the-ledger-keeps-its-comments
  (is (= ";; the ledger\n{:issued 43}\n" (stamper/ledger-text ";; the ledger\n{:issued 41}\n" 43)))
  (is (= "{:issued 1}\n" (stamper/ledger-text nil 1))))
