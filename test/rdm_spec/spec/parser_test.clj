(ns rdm-spec.spec.parser-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.spec.parser :as parser]))

(def text
  "@UN-001
Feature: Lint
  Its description.

  Background:
    Given a model

  @UN-002
  Rule: Every view is described

    @spec-0012 @e2e
    Scenario: Lint names the view
      Given a view with no description
      When the model is linted
      Then the view is named
      And a doc:
        \"\"\"
        text
        \"\"\"

    @spec-0013 @contract @wip
    Scenario Outline: Rows of <n>
      Given <n> rows
        | a | b |

      Examples:
        | n |
        | 1 |
        | 2 |
")

(deftest a-feature-is-read-whole
  (let [{:keys [scenarios refusals feature]} (parser/parse-feature "dhf/spec/architecture/lint.feature" text)
        [a b] scenarios]
    (is (empty? refusals))
    (is (= "Lint" (:name feature)))
    (is (= 2 (count scenarios)))
    (is (= {:id "spec-0012" :ids ["spec-0012"] :title "Lint names the view" :line 12 :level :e2e
            :needs ["UN-001" "UN-002"] :wip? false :outline? false :rule "Every view is described" :feature "Lint"}
           (select-keys a [:id :ids :title :line :level :needs :wip? :outline? :rule :feature])))
    (is (= [{:keyword "Given" :text "a model"}] (map #(select-keys % [:keyword :text]) (:background a))))
    (is (= ["Given" "When" "Then" "And"] (map :keyword (:steps a))))
    (is (= "text" (:doc (last (:steps a)))))
    (is (= [["a" "b"]] (:table (first (:steps b)))))
    (is (:wip? b))
    (is (:outline? b))
    (is (= [["1"] ["2"]] (:rows b)))
    (is (= ["n"] (:headings (first (:examples b)))))
    (is (= #{"UN-001" "spec-0013" "contract" "wip" "UN-002"} (set (:all-tags b))))))

(deftest what-is-outside-the-subset-is-refused
  (let [r (parser/parse-feature "f.feature" "Feature: A\n\n  Scenario: x\n    Given y\n\n  Scenario Template: z\n")]
    (is (= [[6 "gherkin-keyword-unknown"]] (map (juxt :line :rule) (:refusals r))))
    (is (str/includes? (:message (first (:refusals r))) "Scenario Template")))
  (let [r (parser/parse-feature "f.feature" "Feature: A\n\nFeature: B\n")]
    (is (= [3] (map :line (:refusals r))))))

(deftest an-id-is-a-tag-of-the-prefix
  (is (= ["spec-0001"] (parser/ids-of ["spec-0001" "e2e" "UN-001"] "spec")))
  (is (= ["auth-0004"] (parser/ids-of ["auth-0004" "spec-0001"] "auth"))))
