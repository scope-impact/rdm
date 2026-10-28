(ns rdm-spec.spec.parser-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.spec.parser :as parser]))

(def every-construct
  "# a comment
@UN-001
Feature: Demo
  A description of the feature.

  Background:
    Given a background step

  Rule: The rule

    @spec-0001 @e2e
    Scenario: A plain scenario
      Given a table
        | a | b |
        | 1 | 2 |
      When a doc string
        \"\"\"
        line one
          line two
        \"\"\"
      Then it holds
      And more
      But not less

    @spec-0002 @contract
    Scenario Outline: An outline with <x>
      Given the value <x>
      Then it is <y>

      Examples:
        | x | y |
        | 1 | one |
        | 2 | two |
")

(deftest parses-every-construct
  (let [{:keys [feature scenarios refusals]} (parser/parse-feature every-construct "f.feature")]
    (is (empty? refusals))
    (is (= "Demo" (:name feature)))
    (is (= ["@UN-001"] (:tags feature)))
    (is (= 2 (count scenarios)))
    (let [[a b] scenarios]
      (is (= "A plain scenario" (:title a)))
      (is (= 12 (:line a)))
      (is (= ["@spec-0001" "@e2e"] (:tags a)))
      (is (= "The rule" (get-in a [:rule :name])))
      (is (= [{:keyword "Given" :text "a background step" :line 7}] (map #(select-keys % [:keyword :text :line]) (:background a))))
      (is (= ["Given" "When" "Then" "And" "But"] (map :keyword (:steps a))))
      (is (= [["a" "b"] ["1" "2"]] (:table (first (:steps a)))))
      (is (= "line one\n  line two" (:docstring (second (:steps a)))))
      (is (:outline? b))
      (is (= "Scenario Outline" (:keyword b)))
      (is (= ["x" "y"] (get-in b [:examples 0 :header])))
      (is (= [{"x" "1" "y" "one"} {"x" "2" "y" "two"}] (map :values (:rows b)))))))

(deftest refuses-unknown-keyword
  (let [{:keys [refusals]} (parser/parse-feature "Feature: X\n\n  Scenario Template: y\n    Given z\n" "f.feature")]
    (is (= 1 (count refusals)))
    (is (= 3 (:line (first refusals))))
    (is (re-find #"Scenario Template" (:message (first refusals))))))

(deftest refuses-second-feature
  (let [{:keys [refusals]} (parser/parse-feature "Feature: X\n  Scenario: a\n    Given b\nFeature: Y\n" "f.feature")]
    (is (= [4] (map :line refusals)))))

(deftest refuses-stray-line-after-steps
  (let [{:keys [refusals]} (parser/parse-feature "Feature: X\n  Scenario: a\n    Given b\n    stray words\n" "f.feature")]
    (is (= [4] (map :line refusals)))))

(deftest description-lines-with-colons-are-description
  (let [{:keys [refusals]} (parser/parse-feature "Feature: X\n  Status is a report: it exits 0.\n\n  Scenario: a\n    Given b\n" "f.feature")]
    (is (empty? refusals))))

(deftest outline-rows-substitute
  (let [{:keys [scenarios]} (parser/parse-feature every-construct "f.feature")
        b (second scenarios)]
    (is (= ["Given a background step" "Given the value 2" "Then it is two"]
           (map #(str (:keyword %) " " (:text %)) (parser/row-steps b (second (:rows b))))))))
