(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.status :as status]))

(def s {:id "spec-0001" :outline? false :wip? false})
(def o {:id "spec-0002" :outline? true :wip? false :examples [{:headings ["a" "b"] :rows [{:cells ["1" "2"]} {:cells ["3" "4"]}]}]})
(defn r [id st & [params]] {:spec id :status st :parameters (vec (for [[k v] params] {"name" k "value" v}))})

(deftest single-scenarios
  (is (= :unverified (status/status-of s [])))
  (is (= :verified (status/status-of s [(r "spec-0001" "passed")])))
  (is (= :failed (status/status-of s [(r "spec-0001" "passed") (r "spec-0001" "skipped")])))
  (is (= :planned (status/status-of (assoc s :wip? true) [(r "spec-0001" "passed")]))))

(deftest outlines
  (is (= :verified (status/status-of o [(r "spec-0002" "passed" [["a" "1"] ["b" "2"]]) (r "spec-0002" "passed" [["a" "3"] ["b" "4"]])])))
  (is (= :unverified (status/status-of o [(r "spec-0002" "passed" [["a" "1"] ["b" "2"]])])))
  (is (= :unverified (status/status-of o [(r "spec-0002" "passed") (r "spec-0002" "passed")])))
  (is (= :failed (status/status-of o [(r "spec-0002" "failed" [["a" "1"] ["b" "2"]]) (r "spec-0002" "passed" [["a" "3"] ["b" "4"]])]))))
