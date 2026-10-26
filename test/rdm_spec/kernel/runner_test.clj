(ns rdm-spec.kernel.runner-test
  "Unit tests of the Gherkin runner's pure parts: matching steps, deciding a
  result's status, filling a result from its scenario, and the Allure
  mapping. None makes a fixture on disk; definitions registered here are
  removed again."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner]))

(def ^:private lint-text
  (str "Feature: Lint\n  A fixture.\n\n"
       "  Rule: Every view is described\n\n"
       "    @spec-0012 @e2e @UN-002\n"
       "    Scenario: Lint names the view\n"
       "      Given a model\n      When it is linted\n      Then it names the view\n\n"
       "    @spec-0013 @contract\n"
       "    Scenario Outline: Rows <written>\n"
       "      Given the value <written>\n      Then it keeps <kept>\n\n"
       "      Examples:\n        | written | kept |\n        | plan    | 3    |\n        | build   | 4    |\n"))

(def ^:private owner
  {:context :architecture :component :architecture/lint :context-name "architecture" :component-name "Lint"})

(defn- pickles []
  (runner/pickles-of "dhf/spec/architecture/lint.feature"
                     (gherkin/parse-feature "dhf/spec/architecture/lint.feature" lint-text)
                     {:prefix "spec" :owner owner}))

(deftest an-outline-is-one-pickle-per-row-and-a-scenario-one
  (let [ps (pickles)]
    (is (= 3 (count ps)))
    (is (= ["spec-0012" "spec-0013" "spec-0013"] (map :id ps)))
    (is (= [nil {"written" "plan" "kept" "3"} {"written" "build" "kept" "4"}] (map (comp :values :row) ps)))
    (testing "a row's steps are substituted"
      (is (= ["Given the value plan" "Then it keeps 3"] (map runner/step-name (:steps (second ps))))))
    (testing "And and But take the keyword of the step before them"
      (is (= ["Given" "When" "Then"] (map :primary (:steps (first ps))))))))

(deftest a-step-is-matched-by-its-whole-text
  (let [defs [{:pattern #"a model" :ns "a" :line 1} {:pattern #"a (\w+)" :ns "b" :line 2}
              {:pattern #"it is (\w+)" :ns "c" :line 3}]]
    (is (= 2 (count (runner/matching defs "a model"))))
    (is (= [["linted"]] (map :args (runner/matching defs "it is linted"))))
    (is (empty? (runner/matching defs "it is linted now")))))

(deftest failed-is-a-then-whose-assertion-did-not-hold
  (let [assertion (AssertionError. "it does not hold")
        support (ex-info "no" {:steps.support/failed true})
        other (RuntimeException. "boom")]
    (is (= "failed" (runner/status-of-error "Then" assertion)))
    (is (= "failed" (runner/status-of-error "Then" support)))
    (is (= "broken" (runner/status-of-error "Then" other)))
    (is (= "broken" (runner/status-of-error "Given" assertion)))
    (is (= "broken" (runner/status-of-error "When" support)))))

(defn- outcome [status]
  {:status status :start 10 :stop 20
   :steps (vec (for [n ["Given a model" "When it is linted" "Then it names the view"]]
                 {:name n :status status :start 10 :stop 20}))})

(defn- label [r n] (vec (keep #(when (= n (:name %)) (:value %)) (:labels r))))

(deftest a-result-carries-each-allure-element-filled-from-the-scenario
  (let [r (runner/allure-result (first (pickles)) (outcome "passed") runner/default-mapping)]
    (is (= "Lint names the view" (:name r)))
    (is (= "dhf/spec/architecture/lint.feature:Lint names the view" (:fullName r)))
    (is (= "spec-0012" (:historyId r)))
    (is (= ["passed" "finished"] [(:status r) (:stage r)]))
    (is (= ["Lint"] (label r "feature")))
    (is (= ["Every view is described"] (label r "story")))
    (is (= ["architecture"] (label r "epic") (label r "suite") (label r "context")))
    (is (= ["architecture/lint"] (label r "subSuite")))
    (is (= ["spec-0012" "e2e" "UN-002"] (label r "tag")))
    (is (= ["spec-0012"] (label r "spec")))
    (is (= ["Lint"] (label r "component")))
    (is (= ["gherkin"] (label r "framework")))
    (is (= [] (label r "planned")))
    (is (= [{:type "issue" :name "UN-002" :url "dhf/spec/needs.edn#UN-002"}] (:links r)))
    (is (= 3 (count (:steps r))))))

(deftest an-outline-rows-result-names-its-row
  (let [r (runner/allure-result (second (pickles)) (outcome "passed") runner/default-mapping)]
    (is (= [{:name "written" :value "plan"} {:name "kept" :value "3"}] (:parameters r)))
    (is (str/starts-with? (:historyId r) "spec-0013"))
    (is (not= (:historyId r)
              (:historyId (runner/allure-result (nth (pickles) 2) (outcome "passed") runner/default-mapping))))))

(deftest a-planned-scenario-is-labelled-planned
  (let [p (assoc (first (pickles)) :wip? true)
        r (runner/allure-result p (outcome "skipped") runner/default-mapping)]
    (is (= ["true"] (label r "planned")))))

(deftest the-mapping-is-checked-before-it-is-used
  (is (nil? (runner/mapping-problem runner/default-mapping)))
  (is (str/includes? (runner/mapping-problem (assoc runner/default-mapping :epic :aggregate))
                     ":aggregate is not an element of the spec"))
  (is (str/includes? (runner/mapping-problem (assoc runner/default-mapping :colour :context))
                     ":colour is not an element of an Allure result"))
  (is (str/includes? (runner/mapping-problem (update runner/default-mapping :labels dissoc "spec"))
                     "the label spec, from the scenario's id, is required")))

(deftest a-result-is-held-to-the-mapping
  (let [p (first (pickles))
        good (runner/allure-result p (outcome "passed") runner/default-mapping)
        rules (fn [r m] (set (map (juxt :rule :element) (runner/mapping-differences p r m))))]
    (is (empty? (runner/mapping-differences p good runner/default-mapping)))
    (is (= #{["steps-differ-from-scenario" nil]}
           (rules (update good :steps #(assoc-in % [1 :name] "When it is reworded")) runner/default-mapping)))
    (is (= #{["steps-differ-from-scenario" nil]} (rules (update good :steps pop) runner/default-mapping)))
    (is (= #{["mapping-differs" "story"]}
           (rules (update good :labels (fn [ls] (mapv #(if (= "story" (:name %)) (assoc % :value "Something else") %) ls)))
                  runner/default-mapping)))
    (is (= #{["mapping-differs" "links"]} (rules (assoc good :links []) runner/default-mapping)))
    (is (= #{["evidence-missing" "browser-trace"]}
           (rules good (assoc runner/default-mapping :evidence {:e2e ["browser-trace"]}))))
    (testing "evidence attached to a step counts"
      (is (empty? (rules (assoc-in good [:steps 0 :attachments] [{:name "browser-trace" :source "x" :type "text/plain"}])
                         (assoc runner/default-mapping :evidence {:e2e ["browser-trace"]})))))))
