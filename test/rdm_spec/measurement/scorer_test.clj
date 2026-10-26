(ns rdm-spec.measurement.scorer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scorer :as scorer]))

(def cycle-edn
  {:cut "spec-3" :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- rules [fs] (set (map :rule fs)))

(defn- says? [fs & words]
  (boolean (some (fn [f] (every? #(str/includes? (:message f) %) words)) fs)))

(deftest role-findings
  (testing "three distinct roles pass"
    (is (empty? (scorer/role-findings "c.edn" cycle-edn))))
  (testing "a missing role is named"
    (let [fs (scorer/role-findings "c.edn" (dissoc cycle-edn :spec-owner))]
      (is (= 1 (count fs)))
      (is (says? fs ":spec-owner"))))
  (testing "a role that is not {:name :actor :human|:agent} is a finding"
    (is (seq (scorer/role-findings "c.edn" (assoc cycle-edn :grader {:name "g" :actor :robot})))))
  (testing "the builder as grader or spec owner is refused"
    (is (says? (scorer/role-findings "c.edn" (assoc-in cycle-edn [:grader :name] "builder-a"))
               "builder-a" "may not grade or read its own score"))
    (is (says? (scorer/role-findings "c.edn" (assoc-in cycle-edn [:spec-owner :name] "builder-a"))
               "may not grade or read its own score"))))

(deftest ratification-findings
  (is (empty? (scorer/ratification-findings "r.edn" {:outcome :passed :room-tree "abc"})))
  (is (says? (scorer/ratification-findings "r.edn" {:outcome :refused}) "no passing ratification"))
  (is (says? (scorer/ratification-findings "r.edn" nil) "no passing ratification")))

(deftest verdict-findings
  (let [ok [{:id "a" :verdict :pass :evidence "e"} {:id "b" :verdict :fail :evidence "f"}]]
    (is (empty? (scorer/verdict-findings "v.edn" ok)))
    (is (says? (scorer/verdict-findings "v.edn" {:id "a"}) "vector"))
    (is (says? (scorer/verdict-findings "v.edn" (conj ok {:id "a" :verdict :pass :evidence "g"})) "a" "twice"))
    (is (says? (scorer/verdict-findings "v.edn" [{:id "a" :verdict :maybe :evidence "e"}]) "a" ":maybe"))
    (is (says? (scorer/verdict-findings "v.edn" [{:id "a" :verdict :pass}]) "a" "evidence"))
    (is (says? (scorer/verdict-findings "v.edn" [{:id "a" :verdict :pass :evidence "  "}]) "a" "evidence"))
    (is (says? (scorer/verdict-findings "v.edn" ["a"]) "not a map"))
    (testing "each broken rule is its own finding"
      (is (= 2 (count (scorer/verdict-findings "v.edn" [{:id "a" :verdict :maybe}])))))))

(deftest unknown-findings
  (let [fs (scorer/unknown-findings "v.edn" ["a" "b"] [{:id "a"} {:id "spec-0999"}])]
    (is (= 1 (count fs)))
    (is (says? fs "spec-0999"))
    (is (= #{"score-unknown-item"} (rules fs)))))

(deftest score-items
  (testing "the checklist's order; an ungraded item is a fail, marked"
    (is (= [{:id "a" :verdict :pass :evidence "e"}
            {:id "b" :verdict :fail :evidence "ungraded: no verdict was given" :ungraded true}
            {:id "c" :verdict :fail :evidence "f"}]
           (scorer/score-items ["a" "b" "c"] [{:id "c" :verdict :fail :evidence "f" :extra 1}
                                              {:id "a" :verdict :pass :evidence "e"}])))))

(deftest score-shape
  (let [items [{:id "a" :verdict :pass :evidence "e"} {:id "b" :verdict :fail :evidence "f"}]
        sc (scorer/score-of "03" cycle-edn {:room-tree "t" :outcome :passed} items)]
    (is (= {:cycle "03" :cut "spec-3" :scope :project :room-tree "t"
            :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
            :spec-owner {:name "Sami" :actor :human} :items items :passed 1 :scored 2}
           sc))
    (is (= [:cycle :cut :scope :room-tree :builder :grader :spec-owner :items :passed :scored] (keys sc)))
    (testing "a cycle.edn with no :scope is the project"
      (is (= :project (:scope (scorer/score-of "03" (dissoc cycle-edn :scope) {:room-tree "t"} items)))))
    (testing "the text reads back as the same score"
      (is (= sc (read-string (scorer/score-text sc)))))))
