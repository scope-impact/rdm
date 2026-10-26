(ns rdm-spec.verification.verify-test
  "Unit tests of Verify's pure parts: the declared checks, a :runs check's
  answer, and what the tests check finds in a run's results. No fixture on
  disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.verification.verify :as verify]))

(def ^:private impl ["src" "test" "test.clj"])

(deftest the-declared-checks-are-checked-before-anything-runs
  (is (nil? (verify/checks-problem [{:kind :tests :command ["bb" "test.clj"]}] impl)))
  (is (str/includes? (verify/checks-problem nil impl) "the configuration declares no :verify"))
  (is (str/includes? (verify/checks-problem [{:kind :runs :name "a" :command ["x"]}] impl)
                     "a project is verified by its tests first"))
  (is (str/includes? (verify/checks-problem [{:kind :tests :command ["x"]} {:kind :renders}] impl)
                     ":renders is not a kind of check"))
  (is (str/includes? (verify/checks-problem [{:kind :tests}] impl) "the tests check names no command"))
  (is (str/includes? (verify/checks-problem [{:kind :tests :command ["x"]}
                                             {:kind :serves :name "app" :start ["x"] :port 1 :budget {:seconds 1 :polls 1}
                                              :probe ["bb" "src/probe.clj" "{url}"]}]
                                            impl)
                     "the probe is in the implementation the builder writes")))

(deftest a-runs-check-answers-as-expected-or-names-the-difference
  (let [check {:kind :runs :name "answers" :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 10}}]
    (is (nil? (verify/runs-difference check {:exit 0 :out "{:help 1}\n"})))
    (is (str/includes? (verify/runs-difference check {:exit 3 :out "{:help"}) "the exit 3 where 0 was expected"))
    (is (str/includes? (verify/runs-difference check {:exit 0 :out "usage\nmore\n"}) "usage"))
    (is (str/includes? (verify/runs-difference check {:timed-out? true}) "the budget of 10 seconds"))))

(def ^:private text
  (str "@UN-001\nFeature: Lint\n\n  Rule: R\n\n"
       "    @spec-0003 @e2e\n    Scenario: Three\n      Given a model\n\n"
       "    @spec-0004 @e2e @wip\n    Scenario: Four\n      Given a model\n"))

(defn- pickles []
  (runner/pickles-of "dhf/spec/a/b.feature" (gherkin/parse-feature "dhf/spec/a/b.feature" text)
                     {:prefix "spec" :owner {:context :a :component :a/b :context-name "a" :component-name "B"}}))

(deftest the-tests-check-names-every-scenario-not-passed-or-not-named
  (let [ps (pickles)
        good (runner/allure-result (first ps) {:status "passed" :steps [{:name "Given a model" :status "passed"}]}
                                   runner/default-mapping)]
    (testing "untested"
      (is (= ["scenario-untested"] (map :rule (verify/scenario-findings ps [] runner/default-mapping)))))
    (testing "passed and following the mapping"
      (is (empty? (verify/scenario-findings ps [{:spec "spec-0003" :status "passed" :result good :file "r.json"}]
                                            runner/default-mapping))))
    (testing "failed"
      (is (= ["scenario-not-passed"]
             (map :rule (verify/scenario-findings ps [{:spec "spec-0003" :status "failed" :result (assoc good :status "failed") :file "r.json"}]
                                                  runner/default-mapping)))))))
