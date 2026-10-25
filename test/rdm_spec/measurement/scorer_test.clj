(ns rdm-spec.measurement.scorer-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scorer :as scorer]))

(defn- rules [fs] (set (map :rule fs)))

(def cycle
  {:cut "spec-3" :builder {:name "a" :actor :agent} :grader {:name "b" :actor :agent}
   :spec-owner {:name "c" :actor :human}})

(deftest role-findings
  (is (empty? (scorer/role-findings "c.edn" cycle)))
  (is (re-find #"spec owner" (:message (first (scorer/role-findings "c.edn" (dissoc cycle :spec-owner))))))
  (is (= #{"role-actor"} (rules (scorer/role-findings "c.edn" (assoc-in cycle [:grader :actor] :robot)))))
  (testing "the builder may neither grade nor own the spec"
    (is (= #{"builder-not-independent"} (rules (scorer/role-findings "c.edn" (assoc-in cycle [:grader :name] "a")))))
    (is (= #{"builder-not-independent"} (rules (scorer/role-findings "c.edn" (assoc-in cycle [:spec-owner :name] "a")))))))

(deftest ratification-findings
  (is (empty? (scorer/ratification-findings "r.edn" {:outcome :passed})))
  (is (= #{"not-ratified"} (rules (scorer/ratification-findings "r.edn" {:outcome :refused}))))
  (is (= #{"not-ratified"} (rules (scorer/ratification-findings "r.edn" :rdm-spec.measurement.scorer/missing)))))

(deftest verdict-findings
  (let [ids #{"spec-0012" "spec-0014"}
        ok {:id "spec-0012" :verdict :pass :evidence "e"}]
    (is (empty? (scorer/verdict-findings "v.edn" [ok] ids)))
    (is (re-find #"map" (:message (first (scorer/verdict-findings "v.edn" {"spec-0012" :pass} ids)))))
    (is (re-find #"twice|2 times" (apply str (map :message (scorer/verdict-findings "v.edn" [ok ok] ids)))))
    (is (re-find #":maybe" (:message (first (scorer/verdict-findings "v.edn" [(assoc ok :verdict :maybe)] ids)))))
    (is (re-find #"evidence" (:message (first (scorer/verdict-findings "v.edn" [(dissoc ok :evidence)] ids)))))
    (is (re-find #"evidence" (:message (first (scorer/verdict-findings "v.edn" [(assoc ok :evidence " ")] ids)))))
    (is (= #{"verdict-not-on-checklist"}
           (rules (scorer/verdict-findings "v.edn" [(assoc ok :id "spec-0999")] ids))))))

(deftest score-items-and-text
  (let [items (scorer/score-items ["spec-0012" "spec-0014"] [{:id "spec-0012" :verdict :pass :evidence "e"}])
        entries (scorer/score-map (assoc cycle :cycle "03" :scope :project :room-tree "t" :items items))
        text (scorer/score-text entries)
        m (edn/read-string text)]
    (is (= {:id "spec-0014" :verdict :fail :evidence "not graded"} (second items)))
    (is (= [1 2] [(:passed m) (:scored m)]))
    (is (= [:cycle :cut :scope :room-tree :builder :grader :spec-owner :items :passed :scored]
           (map first entries)))
    (is (= (into {} entries) m))))
