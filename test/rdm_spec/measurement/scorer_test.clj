(ns rdm-spec.measurement.scorer-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scorer :as scorer]))

(def cyc
  {:cut "spec-3" :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- rules [fs] (map :rule fs))

(deftest roles-are-named-and-independent
  (is (empty? (scorer/role-findings "c" cyc)))
  (is (= ["missing-role"] (rules (scorer/role-findings "c" (dissoc cyc :spec-owner)))))
  (is (re-find #"spec owner" (:message (first (scorer/role-findings "c" (dissoc cyc :spec-owner))))))
  (is (= ["missing-role"] (rules (scorer/role-findings "c" (assoc-in cyc [:grader :actor] :robot)))))
  (testing "the builder may not grade or read its own score"
    (is (= ["builder-independence"] (rules (scorer/role-findings "c" (assoc-in cyc [:grader :name] "builder-a")))))
    (is (= ["builder-independence"] (rules (scorer/role-findings "c" (assoc-in cyc [:spec-owner :name] "Builder-A")))))))

(deftest only-a-passing-ratification-of-the-cut
  (is (empty? (scorer/ratification-findings "r" {:cut "spec-3" :outcome :passed} "spec-3")))
  (doseq [r [nil {:cut "spec-3" :outcome :refused} {:cut "spec-2" :outcome :passed} []]]
    (is (= ["not-ratified"] (rules (scorer/ratification-findings "r" r "spec-3"))) (pr-str r))))

(def ids #{"spec-0012" "spec-0014"})

(defn- vf [v] (rules (scorer/verdict-findings "v.edn" (pr-str v) v ids)))

(deftest verdicts-have-one-shape
  (is (empty? (vf [{:id "spec-0012" :verdict :pass :evidence "e"}])))
  (is (= ["malformed-verdicts"] (vf {:id "spec-0012" :verdict :pass :evidence "e"})))
  (is (= ["malformed-verdicts"] (vf [{:id "spec-0012" :verdict :maybe :evidence "e"}])))
  (is (= ["malformed-verdicts"] (vf [{:id "spec-0012" :verdict :pass}])))
  (is (= ["malformed-verdicts"] (vf [{:id "spec-0012" :verdict :pass :evidence "e"} {:id "spec-0012" :verdict :fail :evidence "f"}])))
  (is (= ["malformed-verdicts"] (vf ["spec-0012"])))
  (is (= ["not-on-checklist"] (vf [{:id "spec-0999" :verdict :pass :evidence "e"}]))))

(deftest an-ungraded-item-is-a-fail
  (let [items (scorer/score-items [{:id "spec-0012"} {:id "spec-0014"}] [{:id "spec-0012" :verdict :pass :evidence "e"}])]
    (is (= [:pass :fail] (map :verdict items)))))

(deftest score-edn-reads-back
  (let [rec {:cycle "03" :cut "spec-3" :scope :project :room-tree "abc"
             :builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent} :spec-owner {:name "s" :actor :human}
             :items [{:id "spec-0012" :verdict :pass :evidence "e"}] :passed 1 :scored 1}]
    (is (= rec (edn/read-string (scorer/score-text rec))))))
