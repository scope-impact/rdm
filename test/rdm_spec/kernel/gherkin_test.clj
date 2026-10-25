(ns rdm-spec.kernel.gherkin-test
  "Unit tests of the kernel's Gherkin reader. They make no fixture on disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.gherkin :as g]))

(def every-construct
  (str "# a comment\n"
       "@UN-001\n"
       "Feature: Parser\n"
       "  Its description.\n"
       "\n"
       "  Background:\n"
       "    Given a feature background step\n"
       "\n"
       "  @UN-002\n"
       "  Rule: A rule\n"
       "    The rule's description.\n"
       "\n"
       "    Background:\n"
       "      Given a rule background step\n"
       "\n"
       "    @spec-0001 @e2e\n"
       "    Scenario: Plain\n"
       "      Given a doc string:\n"
       "        \"\"\"\n"
       "        Some text, # not a comment.\n"
       "          indented\n"
       "        \"\"\"\n"
       "      And a data table:\n"
       "        | name | value |\n"
       "        | a    | 1\\|2  |\n"
       "      When it is read\n"
       "      Then it holds both\n"
       "      But nothing else\n"
       "\n"
       "    @spec-0002 @contract\n"
       "    Scenario Outline: Over <thing>\n"
       "      Given the thing <thing>\n"
       "\n"
       "      @wip\n"
       "      Examples:\n"
       "        | thing |\n"
       "        | one   |\n"
       "        | two   |\n"))

(deftest every-construct-of-the-subset-is-read
  (let [{:keys [feature scenarios refusals]} (g/parse-feature "f.feature" every-construct)
        [plain outline] scenarios]
    (is (= [] refusals))
    (is (= {:line 3 :name "Parser" :tags ["@UN-001"] :description "Its description."} feature))
    (is (= 2 (count scenarios)))
    (testing "a scenario's tags, rule and backgrounds"
      (is (= ["@spec-0001" "@e2e"] (:tags plain)))
      (is (= ["@UN-001" "@UN-002" "@spec-0001" "@e2e"] (:all-tags plain)))
      (is (= {:name "A rule" :line 10 :tags ["@UN-002"]} (:rule plain)))
      (is (= ["a feature background step" "a rule background step"] (map :text (:background plain))))
      (is (= 17 (:line plain)))
      (is (= "Scenario" (:keyword plain))))
    (testing "steps, a doc string and a data table"
      (is (= ["Given" "And" "When" "Then" "But"] (map :keyword (:steps plain))))
      (is (= "Some text, # not a comment.\n  indented" (:doc-string (first (:steps plain)))))
      (is (= [["name" "value"] ["a" "1|2"]] (:data-table (second (:steps plain)))))
      (is (= 18 (:line (first (:steps plain))))))
    (testing "an outline and its examples"
      (is (= "Scenario Outline" (:keyword outline)))
      (is (= [{:line 35 :tags ["@wip"] :name "" :header ["thing"]
               :rows [{:line 37 :cells ["one"]} {:line 38 :cells ["two"]}]}]
             (:examples outline)))
      (is (= [{:line 37 :values {"thing" "one"}} {:line 38 :values {"thing" "two"}}] (g/outline-rows outline))))))

(defn- rules-of [text] (map (juxt :line :rule) (:refusals (g/parse-feature "f.feature" text))))

(deftest what-is-outside-the-subset-is-refused-with-its-line
  (is (= [[4 "gherkin-unknown-keyword"]]
         (rules-of "@UN-001\nFeature: A\n\n  Scenario Template: X\n")))
  (is (some #{[3 "gherkin-unknown-keyword"]} (rules-of "Feature: A\n  Scenario: S\n    * a step\n")))
  (is (= [[3 "gherkin-one-feature"]] (rules-of "Feature: A\n\nFeature: B\n")))
  (is (= [[1 "gherkin-no-feature"]] (rules-of "Scenario: S\n")))
  (is (= [[2 "gherkin-misplaced"]] (rules-of "Feature: A\n  Given a step outside\n")))
  (is (= [[4 "gherkin-misplaced"]] (rules-of "Feature: A\n  Scenario: S\n    Given a\n    prose among steps\n")))
  (is (= [[3 "gherkin-misplaced"]] (rules-of "Feature: A\n  Scenario: S\n    Examples:\n")))
  (is (= [[2 "gherkin-outline-examples"]] (rules-of "Feature: A\n  Scenario Outline: S <x>\n    Given <x>\n")))
  (is (= [[4 "gherkin-doc-string"]] (rules-of "Feature: A\n  Scenario: S\n    Given a\n      \"\"\"\n      never closed\n")))
  (is (= [[1 "gherkin-tags"]] (rules-of "@a not-a-tag\nFeature: A\n")))
  (is (= [[6 "gherkin-table"]] (rules-of "Feature: A\n  Scenario: S\n    Given a\n      | a | b |\n      | 1 | 2 |\n      | 3 |\n")))
  (testing "a description may hold a colon"
    (is (= [] (rules-of "Feature: A\n  The domain wiki: every page indexed.\n")))))

(deftest a-refusal-names-its-file-and-is-an-error
  (let [[r] (:refusals (g/parse-feature "dhf/spec/a/b.feature" "Feature: A\nFeature: B\n"))]
    (is (= "dhf/spec/a/b.feature" (:file r)))
    (is (= :error (:severity r)))
    (is (str/includes? (:message r) "line 1"))))

(deftest substitution-replaces-every-placeholder
  (is (= "the thing one and one" (g/substitute "the thing <x> and <x>" {"x" "one"})))
  (is (nil? (g/substitute nil {"x" "1"}))))

(deftest a-row-splits-on-unescaped-bars
  (is (= ["a" "b|c" "" "d\\e"] (g/split-row "| a | b\\|c |  | d\\\\e |"))))
