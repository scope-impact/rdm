(ns rdm-spec.spec.stamper-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.stamper :as stamper]))

(deftest stamps-on-the-tag-line-or-above-the-scenario
  (is (= "Feature: F\n\n  @spec-0003 @e2e\n  Scenario: a\n    Given b\n\n  @spec-0004\n  Scenario: c\n    Given d\n"
         (stamper/stamp-text "Feature: F\n\n  @e2e\n  Scenario: a\n    Given b\n\n  Scenario: c\n    Given d\n"
                             [[4 "spec-0003"] [7 "spec-0004"]]))))

(deftest advances-the-ledger-keeping-its-comments
  (is (= ";; the ledger\n{:issued 42}\n" (stamper/ledger-text ";; the ledger\n{:issued 41}\n" 42))))
