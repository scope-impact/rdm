(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.scoreboard :as scoreboard]))

(deftest both-directions-never-the-net
  (let [a {"s1" :fail "s2" :pass "s3" :fail "s4" :pass}
        b {"s1" :pass "s2" :fail "s3" :fail "s4" :pass}
        r (scoreboard/compare-items (keys a) a b)]
    (is (= [4 1 1] [(:items r) (:better r) (:worse r)]))
    (is (= ["s1"] (:better-ids r)))
    (is (= ["s2"] (:worse-ids r)))
    (is (not-any? #{:net} (keys r)))))
