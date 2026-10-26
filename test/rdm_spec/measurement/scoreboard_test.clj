(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.scoreboard :as sb]))

(defn score [scope m] {:scope scope :items (for [[i v] m] {:id i :verdict v})})

(deftest both-directions
  (let [b (sb/compare-scores "01" (score :project {"a" :pass "b" :fail "c" :pass}) "02" (score :project {"a" :fail "b" :pass "c" :pass "d" :pass}))]
    (is (= [3 1 1 ["b"] ["a"]] ((juxt :compared :better :worse :better-items :worse-items) b)))
    (is (= {"01" [] "02" ["d"]} (:only-in b)))
    (is (some #(re-find #"denominator moved from 3 to 4" %) (:statements b)))))
