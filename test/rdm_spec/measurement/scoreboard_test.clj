(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scoreboard :as scoreboard]))

(defn- score [scope verdicts]
  {:scope scope :items (vec (for [[id v] verdicts] {:id id :verdict v :evidence "e"}))})

(deftest both-directions-never-the-net
  (is (= {:cycles ["01" "02"] :scored {"01" 5 "02" 5} :compared 5 :better 1 :worse 1
          :better-items ["spec-0002"] :worse-items ["spec-0001"] :only-in {"01" [] "02" []} :statements []}
         (scoreboard/compare-cycles
          ["01" "02"]
          {"01" {:score (score :project [["spec-0001" :pass] ["spec-0002" :fail] ["spec-0003" :pass] ["spec-0004" :pass] ["spec-0005" :pass]])}
           "02" {:score (score :project [["spec-0001" :fail] ["spec-0002" :pass] ["spec-0003" :pass] ["spec-0004" :pass] ["spec-0005" :pass]])}}))))

(deftest a-moved-denominator-is-said
  (let [sb (scoreboard/compare-cycles
            ["01" "02"]
            {"01" {:score (score :project (for [i (range 1 3)] [(format "spec-%04d" i) :pass]))}
             "02" {:score (score :project (for [i (range 1 5)] [(format "spec-%04d" i) :pass]))}})]
    (is (= 2 (:compared sb)))
    (is (= {"01" [] "02" ["spec-0003" "spec-0004"]} (:only-in sb)))
    (is (some #(re-find #"denominator moved from 2 to 4" %) (:statements sb)))))

(deftest a-scoped-cycle-is-reported-target-first
  (is (= {:cycles ["02" "03"]
          :target {:scope {:component :shop/till} :cycle "03" :compared 2 :better 1 :worse 0
                   :better-items ["spec-0005"] :worse-items []}
          :rest {:compared 0 :better 0 :worse 0 :better-items [] :worse-items [] :not-scored {"03" 3}}
          :statements ["cycle 03 is scoped to {:component :shop/till}: its scope is reported first"
                       "the rest was not scored in cycle 03: 3 items of cycle 02 outside the scope are not compared"]}
         (scoreboard/compare-cycles
          ["02" "03"]
          {"02" {:score (score :project [["spec-0001" :pass] ["spec-0002" :pass] ["spec-0003" :pass]
                                         ["spec-0004" :pass] ["spec-0005" :fail]])}
           "03" {:score (score {:component :shop/till} [["spec-0004" :pass] ["spec-0005" :pass]])}}))))

(deftest a-bootstrap-is-never-a-baseline
  (let [sb (scoreboard/compare-cycles ["00" "01"] {"00" {:cycle {:bootstrap true}} "01" {:score (score :project [["a" :pass]])}})]
    (is (= ["00"] (:bootstrap sb)))
    (is (not-any? #(contains? sb %) [:better :worse :target :rest]))))
