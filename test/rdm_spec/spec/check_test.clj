(ns rdm-spec.spec.check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(defn- rules [text & {:keys [issued needs]}]
  (let [{:keys [scenarios]} (parser/parse-feature "f.feature" text "spec")]
    (map :rule (check/scenario-findings scenarios {:prefix "spec" :issued (or issued 10)
                                                   :needs (or needs #{"UN-001"})}))))

(deftest each-rule-is-its-own-finding
  (is (empty? (rules "@UN-001\nFeature: A\n  @spec-0001 @e2e\n  Scenario: x\n    Given y\n")))
  (is (= ["scenario-unstamped"] (rules "@UN-001\nFeature: A\n  @e2e\n  Scenario: x\n    Given y\n")))
  (is (= ["level-missing"] (rules "@UN-001\nFeature: A\n  @spec-0001\n  Scenario: x\n    Given y\n")))
  (is (= ["level-twice"] (rules "@UN-001\nFeature: A\n  @spec-0001 @e2e @contract\n  Scenario: x\n    Given y\n")))
  (is (= ["scenario-ids"] (rules "@UN-001\nFeature: A\n  @spec-0001 @spec-0002 @e2e\n  Scenario: x\n    Given y\n")))
  (is (= ["id-unissued"] (rules "@UN-001\nFeature: A\n  @spec-0901 @e2e\n  Scenario: x\n    Given y\n")))
  (is (= ["tag-unknown"] (rules "@UN-001\nFeature: A\n  @spec-0001 @e2e @persona\n  Scenario: x\n    Given y\n")))
  (is (= ["need-missing"] (rules "Feature: A\n  @spec-0001 @e2e\n  Scenario: x\n    Given y\n")))
  (is (= ["need-undeclared"] (rules "@UN-009\nFeature: A\n  @spec-0001 @e2e\n  Scenario: x\n    Given y\n")))
  (is (= ["tag-misplaced"] (rules "@UN-001 @e2e\nFeature: A\n  @spec-0001 @e2e\n  Scenario: x\n    Given y\n"))))

(deftest a-stack-word-is-a-whole-word
  (is (= [[2 "Cobalt"]] (map (juxt :line :word) (check/stack-words "a\nin Cobalt.\nCobalts\n" ["Cobalt"])))))
