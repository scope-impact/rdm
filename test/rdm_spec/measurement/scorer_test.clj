(ns rdm-spec.measurement.scorer-test
  "One test per rule the Scorer enforces (07-measure/scorer.feature, 13-slice-development/scorer.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.measurement.scorer :as scorer]))

(deftest a-cycle-is-one-directory-declared-before-it-is-verified
  (is (nil? (scorer/scope-problem :project)))
  (is (nil? (scorer/scope-problem [{:context :knowledge} {:component :spec/parser}])))
  (is (scorer/scope-problem "everything")))

(deftest verdicts-have-one-shape
  (is (str/includes? (scorer/verdicts-problem {:id "x"}) "vector"))
  (is (str/includes? (scorer/verdicts-problem [{:id "spec-0012" :verdict :maybe :evidence "x"}]) ":maybe"))
  (is (str/includes? (scorer/verdicts-problem [{:id "spec-0012" :verdict :pass}]) "evidence")))

(deftest the-instrument-refuses-to-record-what-it-did-not-measure
  (is (= {:id "b" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}
         (second (scorer/score-items ["a" "b"] {"a" {:id "a" :verdict :pass :evidence "e"}})))))

(deftest the-builder-neither-grades-nor-reads-its-own-score
  (is (str/includes? (scorer/roles-problem {:builder {:name "a"} :grader {:name "a"} :spec-owner {:name "c"}}) "may not grade"))
  (is (str/includes? (scorer/roles-problem {:builder {:name "a"} :grader {:name "b"}}) "spec owner")))

(deftest only-a-verified-build-is-scored
  (is (scorer/ratified-problem {:outcome :refused}))
  (is (scorer/ratified-problem nil))
  (is (nil? (scorer/ratified-problem {:outcome :passed}))))

(deftest a-slice-cycle-is-scored-for-its-slice-and-for-what-it-regressed
  (is (= {:passed 1 :scored 2} (scorer/tally [{:verdict :pass} {:verdict :fail}]))))
