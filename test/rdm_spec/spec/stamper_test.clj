(ns rdm-spec.spec.stamper-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.stamper :as stamper]))

(deftest stamps-into-the-tag-line-or-a-new-one
  (is (= "Feature: A\n\n  @spec-0042 @e2e\n  Scenario: S\n    Given x\n"
         (stamper/stamp-text "Feature: A\n\n  @e2e\n  Scenario: S\n    Given x\n" [4] ["spec-0042"])))
  (is (= "Feature: A\n\n  @spec-0007\n  Scenario: S\n    Given x\n"
         (stamper/stamp-text "Feature: A\n\n  Scenario: S\n    Given x\n" [3] ["spec-0007"]))))

(deftest ledger-text-keeps-its-comments
  (is (= ";; c\n{:issued 43}\n" (stamper/ledger-text ";; c\n{:issued 42}\n" 43))))

(deftest formats-ids
  (is (= "spec-0042" (stamper/format-id "spec" 42)))
  (is (= "loyly-12345" (stamper/format-id "loyly" 12345))))
