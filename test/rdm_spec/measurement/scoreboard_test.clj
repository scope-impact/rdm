(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.scoreboard :as scoreboard]))

(def a {"s1" :fail "s2" :pass "s3" :pass "s4" :fail})
(def b {"s1" :pass "s2" :fail "s3" :pass "s4" :fail "s5" :pass "s6" :fail})

(deftest both-directions-never-the-net
  (let [r (scoreboard/board a b nil)]
    (is (= [4 1 1] [(:compared r) (:better r) (:worse r)]))
    (is (= ["s1"] (:better-ids r)))
    (is (= ["s2"] (:worse-ids r)))
    (is (= {:from 4 :to 6 :moved? true} (:denominator r)))
    (is (= ["s5" "s6"] (:new-ids r)))
    (is (not-any? #{:net} (keys r)))
    (is (nil? (:parts r)))))

(deftest a-scope-is-reported-first
  (let [r (scoreboard/board a b #{"s1" "s3"})]
    (is (= [:target :rest] (map :part (:parts r))))
    (is (= [2 1 0] ((juxt :compared :better :worse) (first (:parts r)))))
    (is (= [2 0 1] ((juxt :compared :better :worse) (second (:parts r)))))))
