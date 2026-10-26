(ns rdm-spec.kernel.runner-test
  "Unit tests of the Gherkin runner's pure parts: pickles, matching,
  outcomes and the Allure result. No fixture on disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.gherkin :as g]
            [rdm-spec.kernel.runner :as runner]))

(def text
  (str "@UN-001\nFeature: Lint\n  A fixture.\n\n  Background:\n    Given a background\n\n"
       "  Rule: Every view is described\n\n"
       "    @spec-0012 @e2e @UN-002\n    Scenario: Lint names the view\n      Given a step\n      Then it holds\n\n"
       "    @spec-0013 @contract\n    Scenario Outline: Keeps <written>\n      Given a step with <written>\n\n"
       "      Examples:\n        | written | kept |\n        | plan    | 3    |\n        | code    | 4    |\n"))

(def owners {"architecture/lint" {:context "architecture" :component "Lint" :component-id "architecture/lint"}})

(defn- pickles [] (runner/pickles-of "dhf/spec/architecture/lint.feature" (g/parse-feature "f" text)
                                     {:spec-dir "dhf/spec" :id-prefix "spec" :owners owners}))

(deftest a-scenario-is-one-pickle-and-an-outline-one-per-row
  (let [ps (pickles)]
    (is (= 3 (count ps)))
    (is (= ["spec-0012" "spec-0013" "spec-0013"] (map :id ps)))
    (is (= ["Given a background" "Given a step" "Then it holds"] (map runner/step-name (:steps (first ps)))))
    (is (= "Given a step with plan" (runner/step-name (last (:steps (second ps))))))
    (is (= [["written" "plan"] ["kept" "3"]] (:parameters (second ps))))
    (is (= "spec-0013|plan|3" (runner/history-id (second ps))))
    (is (= "spec-0012" (runner/history-id (first ps))))))

(deftest a-step-and-its-keyword
  (testing "And and But take the keyword before them"
    (is (= ["Given" "Given" "Then" "Then"] (runner/effective-keywords [{:keyword "Given"} {:keyword "And"} {:keyword "Then"} {:keyword "But"}])))))

(deftest only-a-then-that-does-not-hold-fails
  (let [assertion (try (runner/fail! "no such view") (catch Throwable e e))]
    (is (= "failed" (runner/outcome "Then" assertion)))
    (is (= "broken" (runner/outcome "Given" assertion)))
    (is (= "broken" (runner/outcome "Then" (ex-info "boom" {}))))
    (is (= "failed" (runner/outcome "Then" (AssertionError. "x"))))
    (is (= "failed" (runner/outcome "Then" (ex-info "x" {:steps.support/failed true}))))
    (is (= "broken" (runner/outcome "When" (ArithmeticException. "/ by zero"))))))

(deftest matching-a-step
  (let [defs [{:pattern #"a spec" :f identity :file "a.clj"}
              {:pattern #"a (s\w+)" :f identity :file "b.clj"}
              {:pattern #"the spec holds (\d+) features" :f identity :file "c.clj"}]]
    (is (= ["20"] (:captured (first (runner/matches defs "the spec holds 20 features")))))
    (is (= 2 (count (runner/matches defs "a spec"))))
    (is (empty? (runner/matches defs "a spec holds")) "a pattern matches the whole text only")))

(deftest the-default-mapping-is-the-specs
  (is (= {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
          :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
          :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}}
          :evidence {}}
         runner/default-mapping)))

(deftest a-result-in-allures-model
  (let [p (first (pickles))
        r (runner/result-of p {:status "passed" :start 1 :stop 2 :steps [] :needs-rel "dhf/spec/needs.edn"})
        label (fn [n] (some #(when (= n (:name %)) (:value %)) (:labels r)))]
    (is (= "Lint names the view" (:name r)))
    (is (= "dhf/spec/architecture/lint.feature:Lint names the view" (:fullName r)))
    (is (= ["Lint" "Every view is described" "architecture" "architecture" "architecture/lint" "gherkin" "spec-0012" "Lint"]
           (map label ["feature" "story" "epic" "suite" "subSuite" "framework" "spec" "component"])))
    (is (= #{"UN-001" "spec-0012" "e2e" "UN-002"} (set (keep #(when (= "tag" (:name %)) (:value %)) (:labels r)))))
    (is (= [{:type "issue" :name "UN-001" :url "dhf/spec/needs.edn#UN-001"}
            {:type "issue" :name "UN-002" :url "dhf/spec/needs.edn#UN-002"}]
           (:links r)))
    (is (str/blank? (label "planned")))))
