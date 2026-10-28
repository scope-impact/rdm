(ns rdm-spec.measurement.scoreboard-test
  "One test per rule the Scoreboard enforces (07-measure/scoreboard.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.scoreboard :as scoreboard]))

(deftest the-scoreboard-compares-cycles-honestly
  (is (= {:compared 2 :better 1 :worse 1 :better-items ["b"] :worse-items ["a"]}
         (scoreboard/compare-items {"a" :pass "b" :fail} {"a" :fail "b" :pass}))))

(deftest a-scoped-cycle-is-reported-target-first
  (is (= ["cycle 03 is scoped to {:component :shop/till}: its scope is reported first"]
         (take 1 (scoreboard/scoped-statements "02" "03" {:component :shop/till} 3)))))

(deftest a-bootstrap-cycle-is-never-a-baseline
  (is (= ["00"] (scoreboard/bootstraps {"00" {:bootstrap true} "01" {}}))))

(deftest the-scoreboards-result-has-one-shape
  (is (= #{:cycles :scored :compared :better :worse :better-items :worse-items :only-in :statements}
         (set (keys (scoreboard/whole "01" "02" {"a" :pass} {"a" :pass}))))))
