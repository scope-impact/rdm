(ns rdm-spec.verification.verify-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.verify :as verify]))

(def impl ["src" "test" "test.clj"])

(deftest verify-configurations
  (is (nil? (verify/verify-problem [{:kind :tests :command ["bb" "test.clj"]}] impl)))
  (is (re-find #"no :verify" (verify/verify-problem nil impl)))
  (is (re-find #"tests first" (verify/verify-problem [{:kind :runs :name "x" :command ["x"]}] impl)))
  (is (re-find #":renders is not a kind" (verify/verify-problem [{:kind :tests :command ["x"]} {:kind :renders}] impl)))
  (is (re-find #"no command" (verify/verify-problem [{:kind :tests}] impl)))
  (is (re-find #"implementation" (verify/verify-problem [{:kind :tests :command ["x"]} {:kind :serves :start ["a"] :port 1 :budget {:seconds 1 :polls 1} :probe ["bb" "src/p.clj"]}] impl))))

(deftest allure-mappings
  (is (nil? (verify/allure-problem verify/default-mapping)))
  (is (re-find #":aggregate is not an element of the spec" (verify/allure-problem (assoc verify/default-mapping :epic :aggregate))))
  (is (re-find #":colour is not an element of an Allure result" (verify/allure-problem (assoc verify/default-mapping :colour :context))))
  (is (re-find #"label spec" (verify/allure-problem (update verify/default-mapping :labels dissoc "spec")))))

(deftest margins
  (is (= 18.77 (verify/margin 30 11.234))))
