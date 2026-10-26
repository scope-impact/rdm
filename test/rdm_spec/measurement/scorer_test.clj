(ns rdm-spec.measurement.scorer-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.scorer :as s]))

(deftest malformed-verdicts
  (is (re-find #"vector" (first (s/verdict-problems {:id "a"}))))
  (is (re-find #"twice" (first (s/verdict-problems [{:id "a" :verdict :pass :evidence "x"} {:id "a" :verdict :fail :evidence "y"}]))))
  (is (re-find #":maybe" (first (s/verdict-problems [{:id "a" :verdict :maybe :evidence "x"}]))))
  (is (re-find #"evidence" (first (s/verdict-problems [{:id "a" :verdict :pass}]))))
  (is (empty? (s/verdict-problems [{:id "a" :verdict :pass :evidence "x"}]))))

(deftest ungraded-is-a-fail
  (is (= [{:id "a" :verdict :pass :evidence "x"} {:id "b" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}]
         (s/items ["a" "b"] [{:id "a" :verdict :pass :evidence "x"}]))))
