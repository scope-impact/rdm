(ns steps.spec-parser
  "Step definitions of dhf/spec/spec/parser.feature."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn n-scenarios [n]
  (k/feature-text {:name "Core" :tags ["UN-001"]
                   :scenarios (for [i (range 1 (inc n))] (k/sc i (str "Scenario " i)))}))

(defstep #"the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/write-files! (:dir ctx) {"dhf/spec/kernel/core.feature" (n-scenarios 12)})
      (k/tag-cut! ctx "spec-3")
      (k/write-files! (:dir ctx) {"dhf/spec/kernel/core.feature" (n-scenarios 13)})
      ctx)))

(defn checklist [ctx]
  (let [f (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03/checklist.json")]
    (when-not (fs/exists? f) (fail! (str "no checklist was written: " (:err ctx))))
    (json/parse-string (slurp f))))

(defstep #"the checklist holds the 12 scenarios of spec-3"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [items (get (checklist ctx) "items")]
      (assert (= (for [i (range 1 13)] (format "spec-%04d" i)) (map #(get % "id") items)) (pr-str items))
      ctx)))

(def every-construct
  "# A comment
@UN-001
Feature: Every construct
  A description of the feature.

  Background:
    Given a background step

  Rule: A rule

    @spec-0001 @e2e
    Scenario: A scenario
      Given a step with a doc string
        \"\"\"
        some text
        \"\"\"
      When a step with a table
        | a | b |
        | 1 | 2 |
      Then it holds
      And it also holds
      But not this

    # another comment
    @spec-0002 @contract
    Scenario Outline: An outline <x>
      Given the value <x>

      Examples:
        | x |
        | 1 |
        | 2 |
")

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [ctx]
    (k/project! ctx {:features {"dhf/spec/kernel/core.feature" every-construct}})))

(defstep #"a feature file containing a line starting with \"Scenario Template:\""
  (fn [ctx]
    (-> (k/project! ctx {:features {"dhf/spec/kernel/core.feature"
                                    "@UN-001\nFeature: Core\n\n  @spec-0001 @e2e\n  Scenario Template: Old\n    Given a step\n"}})
        (k/names+ "dhf/spec/kernel/core.feature:5" "Scenario Template:"))))

(defstep #"a feature file with two Feature lines"
  (fn [ctx]
    (-> (k/project! ctx {:features {"dhf/spec/kernel/core.feature"
                                    (str (n-scenarios 1) "\nFeature: Again\n")}})
        (k/names+ "dhf/spec/kernel/core.feature:9"))))

