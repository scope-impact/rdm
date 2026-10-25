(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scoreboard :as sb]))

(defn- cyc [nn scope verdicts]
  {:cycle nn :scope scope :bootstrap? false
   :score {:items (vec (for [[id v] verdicts] {:id id :verdict v}))}})

(deftest both-directions-never-the-net
  (let [r (sb/compare-cycles (cyc "01" :project [["a" :fail] ["b" :pass] ["c" :pass]])
                             (cyc "02" :project [["a" :pass] ["b" :fail] ["c" :pass]]))]
    (is (= {:count 1 :items ["a"]} (:better r)))
    (is (= {:count 1 :items ["b"]} (:worse r)))
    (is (= 3 (:compared r)))
    (is (not (:moved (:denominator r))))
    (is (not-any? #(str/includes? (name %) "net") (keys r)))))

(deftest a-moved-denominator-and-new-items
  (let [r (sb/compare-cycles (cyc "01" :project [["a" :pass]])
                             (cyc "02" :project [["a" :pass] ["b" :pass]]))]
    (is (= {:from 1 :to 2 :moved true} (:denominator r)))
    (is (= {:cycle "02" :count 1 :items ["b"]} (:new r)))
    (is (some #(str/includes? % "denominator moved from 1 to 2") (:says r)))))

(deftest a-scoped-cycle-target-first
  (let [r (sb/compare-cycles (cyc "01" :project [["a" :pass] ["v" :fail]])
                             (cyc "02" {:context :verification} [["v" :pass]]))]
    (is (= {:count 1 :items ["v"]} (:better r)))
    (is (= {:cycle "02" :count 1 :items ["a"]} (:not-scored r)))
    (is (str/includes? (first (:says r)) "{:context :verification}"))
    (is (some #(str/includes? % "not scored in cycle 02") (:says r)))))

(deftest a-bootstrap-is-never-a-baseline
  (let [r (sb/compare-cycles {:cycle "00" :bootstrap? true :scope :project}
                             (cyc "01" :project [["a" :pass]]))]
    (is (= ["00"] (:bootstrap r)))
    (testing "nothing is counted"
      (is (not-any? #(contains? r %) [:better :worse :compared])))))
