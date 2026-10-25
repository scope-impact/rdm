(ns rdm-spec.measurement.scorer-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scorer :as scorer]))

(def cycle-edn
  {:cut "spec-3"
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- rules [fs] (mapv :rule fs))

(deftest roles
  (is (= [] (scorer/role-findings "c.edn" cycle-edn)))
  (testing "a missing role is named"
    (let [fs (scorer/role-findings "c.edn" (dissoc cycle-edn :spec-owner))]
      (is (= ["score-role-missing"] (rules fs)))
      (is (str/includes? (:message (first fs)) "spec owner"))))
  (testing "the builder may not grade or read its own score"
    (is (= ["score-builder-grades"] (rules (scorer/role-findings "c.edn" (assoc-in cycle-edn [:grader :name] "builder-a")))))
    (is (= ["score-builder-grades"] (rules (scorer/role-findings "c.edn" (assoc-in cycle-edn [:spec-owner :name] "builder-a"))))))
  (testing "each role is a human or an agent"
    (is (= ["score-role-actor"] (rules (scorer/role-findings "c.edn" (assoc-in cycle-edn [:grader :actor] :robot)))))))

(def ids ["spec-0012" "spec-0014"])

(deftest verdicts
  (is (= [] (scorer/verdict-findings "v.edn" [{:id "spec-0012" :verdict :pass :evidence "e"}] ids)))
  (is (= ["verdicts-malformed"] (rules (scorer/verdict-findings "v.edn" {:id "spec-0012"} ids))))
  (is (= ["verdicts-malformed"] (rules (scorer/verdict-findings "v.edn" [{:id "spec-0012" :verdict :pass :evidence "e"}
                                                                         {:id "spec-0012" :verdict :fail :evidence "f"}] ids))))
  (is (= ["verdicts-malformed"] (rules (scorer/verdict-findings "v.edn" [{:id "spec-0012" :verdict :maybe :evidence "e"}] ids))))
  (is (= ["verdicts-malformed"] (rules (scorer/verdict-findings "v.edn" [{:id "spec-0012" :verdict :pass}] ids))))
  (is (= ["verdicts-malformed"] (rules (scorer/verdict-findings "v.edn" [{:id "spec-0012" :verdict :pass :evidence " "}] ids))))
  (is (= ["verdict-unknown-item"] (rules (scorer/verdict-findings "v.edn" [{:id "spec-0999" :verdict :pass :evidence "e"}] ids)))))

(deftest the-score
  (let [sc (scorer/score-of "03" cycle-edn {:room-tree "t" :outcome :passed} ids
                            [{:id "spec-0012" :verdict :pass :evidence "e"}])]
    (is (= [:cycle :cut :scope :room-tree :builder :grader :spec-owner :items :passed :scored] (keys sc)))
    (is (= :project (:scope sc)))
    (is (= [1 2] [(:passed sc) (:scored sc)]))
    (testing "an ungraded item is a fail, never dropped"
      (is (= {:id "spec-0014" :verdict :fail :evidence scorer/not-graded} (second (:items sc)))))
    (testing "its text reads back as the score"
      (is (= sc (edn/read-string (scorer/score-text sc)))))))
