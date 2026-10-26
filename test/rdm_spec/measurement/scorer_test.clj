(ns rdm-spec.measurement.scorer-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scorer :as scorer]))

(def v1 {:id "spec-0012" :verdict :pass :evidence "seen"})
(def v2 {:id "spec-0014" :verdict :fail :evidence "not seen"})

(deftest a-verdicts-file-has-one-shape
  (is (nil? (scorer/malformed [v1 v2])))
  (is (re-find #"vector" (scorer/malformed {:id "x"})))
  (is (re-find #"twice" (scorer/malformed [v1 v1])))
  (is (re-find #":maybe" (scorer/malformed [(assoc v1 :verdict :maybe)])))
  (is (re-find #"evidence" (scorer/malformed [(dissoc v1 :evidence)])))
  (is (re-find #"evidence" (scorer/malformed [(assoc v1 :evidence " ")]))))

(deftest roles-are-checked
  (let [c {:builder {:name "a" :actor :agent} :grader {:name "b" :actor :agent} :spec-owner {:name "c" :actor :human}}]
    (is (nil? (scorer/role-problem c)))
    (is (re-find #":spec-owner" (scorer/role-problem (dissoc c :spec-owner))))
    (is (re-find #"may not grade or read its own score" (scorer/role-problem (assoc-in c [:grader :name] "a"))))
    (is (re-find #"may not grade or read its own score" (scorer/role-problem (assoc-in c [:spec-owner :name] "a"))))
    (is (re-find #":actor" (scorer/role-problem (assoc-in c [:grader :actor] :robot))))))

(deftest an-ungraded-item-is-a-fail
  (is (= {:items [v1 {:id "spec-0014" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}]
          :passed 1 :scored 2}
         (scorer/score-items ["spec-0012" "spec-0014"] [v1]))))
