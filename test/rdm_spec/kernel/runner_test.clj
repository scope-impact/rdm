(ns rdm-spec.kernel.runner-test
  "Unit tests of the Gherkin runner. Each runs with a registry of its own;
  none makes a fixture on disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner]))

(defmacro with-registry [& body]
  `(binding [runner/*registry* (atom []) runner/*shadowed* (atom [])] ~@body))

(deftest one-pattern-keeps-one-meaning
  (with-registry
    (runner/register! #"a step" (fn [c] c) "a.clj" 1)
    (testing "a file registering its own pattern again replaces it"
      (runner/register! #"a step" (fn [c] (assoc c :again true)) "a.clj" 1)
      (is (= 1 (count @runner/*registry*)))
      (is (= {:again true} ((:fn (first @runner/*registry*)) {}))))
    (testing "another file's same pattern is shadowed, never a second definition"
      (runner/register! #"a step" (fn [c] c) "b.clj" 7)
      (is (= 1 (count @runner/*registry*)))
      (is (= [["b.clj" 7 "step-shadowed"]] (map (juxt :file :line :rule) (runner/shadow-warnings)))))
    (is (= ["a.clj"] (map :file (runner/matching "a step"))))))

(deftest steps-run-in-order-each-given-the-last-context
  (with-registry
    (runner/register! #"the spec holds (\d+) features" (fn [ctx n] (assoc ctx :n n)) "s.clj" 1)
    (runner/register! #"it is checked( twice)?" (fn [ctx twice] (assoc ctx :twice twice :seen (:n ctx))) "s.clj" 2)
    (runner/register! #"a check" (fn [ctx] nil) "s.clj" 3)
    (runner/register! #"a doc string" (fn [ctx doc] (assoc ctx :doc doc)) "s.clj" 4)
    (let [seen (atom nil)]
      (runner/register! #"the end" (fn [ctx] (reset! seen ctx)) "s.clj" 5)
      (let [out (runner/run-steps [{:keyword "Given" :text "the spec holds 20 features" :line 1}
                                   {:keyword "When" :text "it is checked" :line 2}
                                   {:keyword "Then" :text "a check" :line 3}
                                   {:keyword "And" :text "a doc string" :line 4 :doc-string "text"}
                                   {:keyword "And" :text "the end" :line 5}])]
        (is (= "passed" (:status out)))
        (is (= ["passed" "passed" "passed" "passed" "passed"] (map :status (:steps out))))
        (is (= {:n "20" :twice nil :seen "20" :doc "text"} @seen) "a step that returns no map keeps the context")))))

(deftest a-failing-step-fails-and-the-rest-do-not-run
  (with-registry
    (let [ran (atom [])]
      (runner/register! #"step (\d)" (fn [ctx n] (swap! ran conj n) (when (= n "2") (throw (ex-info "it broke" {}))) ctx)
                        "s.clj" 1)
      (let [out (runner/run-steps (for [i [1 2 3]] {:keyword "Given" :text (str "step " i) :line i}))]
        (is (= "failed" (:status out)))
        (is (str/includes? (:message out) "Given step 2"))
        (is (str/includes? (:message out) "it broke"))
        (is (= ["passed" "failed" "skipped"] (map :status (:steps out))))
        (is (= ["1" "2"] @ran))))))

(deftest an-unmatched-or-ambiguous-step-is-broken
  (with-registry
    (runner/register! #"a spec" identity "one.clj" 3)
    (runner/register! #"a (\w+)" (fn [c _] c) "two.clj" 9)
    (let [amb (runner/run-steps [{:keyword "Given" :text "a spec" :line 1}])
          none (runner/run-steps [{:keyword "Given" :text "nothing defines this" :line 1}])]
      (is (= "broken" (:status amb)))
      (is (and (str/includes? (:message amb) "one.clj:3") (str/includes? (:message amb) "two.clj:9")))
      (is (= "broken" (:status none)))
      (is (str/includes? (:message none) "nothing defines this")))))

(def feature
  (str "@UN-001\nFeature: Lint\n\n  Background:\n    Given a background\n\n"
       "  @spec-0012 @e2e\n  Scenario: Plain\n    Given a step\n\n"
       "  @spec-0013 @contract\n  Scenario Outline: Over <n>\n    Given the number <n>\n\n"
       "    Examples:\n      | n |\n      | 1 |\n      | 2 |\n\n"
       "  @spec-0014 @e2e @wip\n  Scenario: Planned\n    Given a step\n"))

(deftest a-feature-is-one-pickle-per-scenario-and-per-row
  (let [ps (runner/pickles "dhf/spec/architecture/lint.feature" (gherkin/parse-feature "f" feature)
                           {:prefix "spec" :owner {:context "architecture" :component "Lint"}})]
    (is (= ["spec-0012" "spec-0013" "spec-0013" "spec-0014"] (map :id ps)))
    (is (= ["Plain" "Over <n> [n=1]" "Over <n> [n=2]" "Planned"] (map :display-name ps)))
    (is (= ["a background" "the number 2"] (map :text (:steps (nth ps 2)))))
    (is (= [false false false true] (map :wip? ps)))
    (testing "a result carries its labels, its row and its steps"
      (let [r (runner/allure-result (nth ps 1) {:status "passed" :steps [{:name "Given x" :status "passed"}] :start 1 :stop 2})]
        (is (= {"spec" "spec-0013" "component" "Lint" "context" "architecture"}
               (select-keys (into {} (map (juxt :name :value) (:labels r))) ["spec" "component" "context" "planned"])))
        (is (= [{:name "n" :value "1"}] (:parameters r)))
        (is (= "dhf/spec/architecture/lint.feature:17" (:fullName r)))))
    (testing "a planned scenario is not run, and says so"
      (let [r (runner/run-pickle (nth ps 3))]
        (is (= "skipped" (:status r)))
        (is (some #{{:name "planned" :value "true"}} (:labels r)))
        (is (every? #(= "skipped" (:status %)) (:steps r)))))))

(deftest a-feature-file-is-owned-by-the-model-or-its-path
  (let [owners (runner/owners {:containers [{:contexts [{:id :architecture :name "architecture"
                                                         :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"}]}]}]
                               :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus"}]})]
    (is (= {:context "architecture" :component "Lint"} (runner/owner-of owners "dhf/spec" "dhf/spec/architecture/lint.feature")))
    (is (= {:context "kernel" :component "Event bus"} (runner/owner-of owners "dhf/spec/" "dhf/spec/kernel/bus.feature")))
    (is (= {:context "demo" :component "alpha"} (runner/owner-of owners "dhf/spec" "dhf/spec/demo/alpha.feature")))
    (is (nil? (runner/owner-of owners "dhf/spec" "elsewhere/x.feature")))))
