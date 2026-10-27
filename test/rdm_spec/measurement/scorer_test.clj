(ns rdm-spec.measurement.scorer-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.scorer :as scorer]))

(defn- rules [files] (map :rule (scorer/verdict-findings files #{"spec-0012" "spec-0013"})))

(deftest verdicts-are-held-to-one-shape-across-files
  (is (empty? (rules [["a.edn" [{:id "spec-0012" :verdict :pass :evidence "x"}]]])))
  (is (= ["verdicts-malformed"] (rules [["a.edn" {:id "spec-0012"}]])))
  (is (= ["verdict-malformed"] (rules [["a.edn" [{:id "spec-0012" :verdict :maybe :evidence "x"}]]])))
  (is (= ["verdict-malformed"] (rules [["a.edn" [{:id "spec-0012" :verdict :pass}]]])))
  (is (= ["verdict-twice"] (rules [["a.edn" [{:id "spec-0012" :verdict :pass :evidence "x"}]]
                                   ["b.edn" [{:id "spec-0012" :verdict :fail :evidence "y"}]]])))
  (is (= ["verdict-off-checklist"] (rules [["a.edn" [{:id "spec-0999" :verdict :pass :evidence "x"}]]]))))

(deftest an-ungraded-item-is-a-fail
  (is (= [{:id "spec-0012" :verdict :pass :evidence "x"}
          {:id "spec-0013" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}]
         (scorer/items ["spec-0012" "spec-0013"] [{:id "spec-0012" :verdict :pass :evidence "x"}]))))
