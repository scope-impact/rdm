(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scoreboard :as sb]))

(defn- items [& kvs] (vec (for [[id v] (partition 2 kvs)] {:id id :verdict v :evidence "e"})))

(defn- cyc
  ([nn its] (cyc nn its :project))
  ([nn its scope] {:name nn :score {:scope scope :items its} :cycle {:scope scope}}))

(deftest whole-project
  (let [a (cyc "01" (items "spec-0001" :pass "spec-0002" :fail "spec-0003" :pass "spec-0004" :pass "spec-0005" :pass))
        b (cyc "02" (items "spec-0001" :fail "spec-0002" :pass "spec-0003" :pass "spec-0004" :pass "spec-0005" :pass))]
    (is (= {:cycles ["01" "02"] :scored {"01" 5 "02" 5} :compared 5 :better 1 :worse 1
            :better-items ["spec-0002"] :worse-items ["spec-0001"]
            :only-in {"01" [] "02" []} :statements []}
           (sb/compare-cycles a b)))
    (is (= [:cycles :scored :compared :better :worse :better-items :worse-items :only-in :statements]
           (keys (sb/compare-cycles a b))))))

(deftest a-moved-denominator
  (let [a (cyc "01" (items "a" :pass "b" :fail "x" :pass))
        b (cyc "02" (items "a" :pass "b" :pass "c" :fail "d" :pass))
        r (sb/compare-cycles a b)]
    (is (= 2 (:compared r)))
    (is (= {"01" ["x"] "02" ["c" "d"]} (:only-in r)))
    (is (= ["b"] (:better-items r)))
    (is (= ["the denominator moved from 3 to 4: only the 2 items both scored are compared"
            "1 item was scored only in cycle 01: it is not compared"
            "2 items were scored only in cycle 02: they are counted apart, not compared"]
           (:statements r)))
    (testing "never a net"
      (is (not-any? #(str/includes? (name %) "net") (keys r))))))

(deftest an-ungraded-item-is-a-fail
  (let [a (cyc "01" [{:id "a" :verdict :pass :evidence "e"}])
        b (cyc "02" [{:id "a" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}])]
    (is (= ["a"] (:worse-items (sb/compare-cycles a b))))))

(deftest a-scoped-cycle
  (let [a (cyc "02" (items "spec-0001" :pass "spec-0002" :pass "spec-0003" :pass "spec-0004" :pass "spec-0005" :fail))
        b (cyc "03" (items "spec-0004" :pass "spec-0005" :pass) {:component :shop/till})
        r (sb/compare-cycles a b)]
    (is (= {:cycles ["02" "03"]
            :target {:scope {:component :shop/till} :cycle "03"
                     :compared 2 :better 1 :worse 0
                     :better-items ["spec-0005"] :worse-items []}
            :rest {:compared 0 :better 0 :worse 0
                   :better-items [] :worse-items []
                   :not-scored {"03" 3}}
            :statements ["cycle 03 is scoped to {:component :shop/till}: its scope is reported first"
                         "the rest was not scored in cycle 03: 3 items of cycle 02 outside the scope are not compared"]}
           r))
    (is (= [:cycles :target :rest :statements] (keys r)))
    (is (= [:scope :cycle :compared :better :worse :better-items :worse-items] (keys (:target r))))
    (testing "one item of the rest reads in the singular"
      (let [r (sb/compare-cycles (cyc "02" (items "x" :pass "y" :pass)) (cyc "03" (items "y" :pass) {:context :c}))]
        (is (= "the rest was not scored in cycle 03: 1 item of cycle 02 outside the scope is not compared"
               (second (:statements r))))))
    (testing "the earlier cycle may be the scoped one"
      (let [r (sb/compare-cycles b a)]
        (is (= "03" (get-in r [:target :cycle])))
        (is (= ["spec-0005"] (get-in r [:target :worse-items])))
        (is (= {"03" 3} (get-in r [:rest :not-scored])))))
    (testing "an item of the scope only one cycle scored is said aloud"
      (let [r (sb/compare-cycles a (cyc "03" (items "spec-0005" :pass "spec-0009" :pass) {:component :shop/till}))]
        (is (= 1 (get-in r [:target :compared])))
        (is (some #(str/includes? % "spec-0009") (:statements r)))))))

(deftest a-bootstrap-cycle
  (let [a (assoc-in (cyc "00" (items "a" :fail)) [:cycle :bootstrap] true)
        b (cyc "01" (items "a" :pass))
        r (sb/compare-cycles a b)]
    (is (= {:cycles ["00" "01"] :bootstrap ["00"] :scored {"00" 1 "01" 1}
            :statements ["cycle 00 is a bootstrap cycle: it is never a baseline, and nothing is compared against it"]}
           r))
    (testing "a bootstrap cycle need not have been scored"
      (is (= {"01" 1} (:scored (sb/compare-cycles (assoc a :score nil) b)))))))
