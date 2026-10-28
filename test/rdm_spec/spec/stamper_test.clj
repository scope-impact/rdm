(ns rdm-spec.spec.stamper-test
  "One test per rule the Stamper enforces (02-write-the-spec/stamper.feature, 12-several-specs/stamper.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.stamper :as stamper]))

(deftest the-stamper-hands-out-each-id-once-from-the-ledger
  (is (= "  @spec-0042 @e2e\n  Scenario: S\n" (stamper/tag-text "  @e2e\n  Scenario: S\n" 2 "@spec-0042")))
  (is (= "\n  @spec-0042\n  Scenario: S\n" (stamper/tag-text "\n  Scenario: S\n" 2 "@spec-0042")))
  (is (= ";; c\n{:issued 43}\n" (stamper/ledger-text ";; c\n{:issued 42}\n" 43))))

(deftest each-spec-stamps-from-its-own-ledger
  (is (= "auth-0004" (stamper/id-of "auth" 4))))
