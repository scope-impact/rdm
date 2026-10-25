(ns rdm-spec.measurement.scorer-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scorer :as scorer]))

(def a-cycle
  {:cut "spec-3" :builder {:name "b" :actor :agent} :grader {:name "g" :actor :human}
   :spec-owner {:name "o" :actor :human} :rdm-spec.measurement.checklist/file "c.edn"})

(deftest roles
  (is (empty? (scorer/role-findings a-cycle)))
  (is (= ["role-unnamed"] (map :rule (scorer/role-findings (dissoc a-cycle :grader)))))
  (is (= ["role-unnamed"] (map :rule (scorer/role-findings (assoc a-cycle :grader {:name "g" :actor :robot})))))
  (testing "the builder may not grade or read its own score, whatever the case"
    (is (= ["builder-not-independent"] (map :rule (scorer/role-findings (assoc a-cycle :grader {:name " B " :actor :agent})))))
    (is (= ["builder-not-independent"] (map :rule (scorer/role-findings (assoc a-cycle :spec-owner {:name "b" :actor :human})))))))

(defn- vf [value ids] (scorer/verdict-findings {:file "v.edn" :text (pr-str value) :value value} (set ids)))

(deftest verdicts-have-one-shape
  (is (empty? (vf [{:id "a" :verdict :pass :evidence "e"}] ["a" "b"])))
  (is (re-find #"not a map" (:message (first (vf {:id "a"} ["a"])))))
  (is (re-find #"graded 2 times" (:message (first (vf [{:id "a" :verdict :pass :evidence "e"} {:id "a" :verdict :fail :evidence "f"}] ["a"])))))
  (is (re-find #":maybe" (:message (first (vf [{:id "a" :verdict :maybe :evidence "e"}] ["a"])))))
  (is (re-find #"no evidence" (:message (first (vf [{:id "a" :verdict :pass :evidence " "}] ["a"])))))
  (is (re-find #"not a map" (:message (first (vf ["a"] ["a"])))))
  (is (= ["verdict-not-on-checklist"] (map :rule (vf [{:id "z" :verdict :pass :evidence "e"}] ["a"])))))

(deftest an-ungraded-item-fails
  (let [items (scorer/score-items [{"id" "a"} {"id" "b"}] [{:id "b" :verdict :pass :evidence "e"}])]
    (is (= [:fail :pass] (map :verdict items)))
    (is (:ungraded (first items)))))

(deftest score-text-keeps-its-order
  (let [m {:cycle "03" :cut "spec-3" :scope :project :room-tree "t" :builder {:name "b" :actor :agent}
           :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}
           :items [{:id "a" :verdict :pass :evidence "e"}] :passed 1 :scored 1}
        text (scorer/score-text m)]
    (is (= m (edn/read-string text)))
    (is (re-find #"(?s)^\{:cycle .*:cut .*:scope .*:room-tree .*:builder .*:grader .*:spec-owner .*:items .*:passed .*:scored" text))))

(defn- project []
  (let [root (str (fs/create-temp-dir))
        d (doto (fs/file root "dhf/cycles/03") fs/create-dirs)]
    (spit (str d "/cycle.edn") (pr-str (dissoc a-cycle :rdm-spec.measurement.checklist/file)))
    (spit (str d "/checklist.json") (json/generate-string {:cycle "03" :cut "spec-3" :items [{:id "a"} {:id "b"}] :planned []}))
    (spit (str d "/ratified.edn") (pr-str {:cut "spec-3" :room-tree "t" :outcome :passed}))
    root))

(defn- req [root] {:root root :task "score" :args ["03"] :opts {:verdicts "v.edn"} :config {:cycles-dir "dhf/cycles"}})

(deftest the-stage
  (let [root (project)
        score (fs/file root "dhf/cycles/03/score.edn")]
    (try
      (testing "no verdict: cannot run, nothing written"
        (spit (str (fs/file root "v.edn")) "[]")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"grading did not happen" (scorer/score (req root))))
        (is (not (fs/exists? score))))
      (testing "a refused ratification is a finding"
        (spit (str (fs/file root "v.edn")) (pr-str [{:id "a" :verdict :pass :evidence "e"}]))
        (spit (str (fs/file root "dhf/cycles/03/ratified.edn")) (pr-str {:cut "spec-3" :outcome :refused}))
        (is (= ["not-ratified"] (map :rule (:findings (scorer/score (req root))))))
        (is (not (fs/exists? score))))
      (testing "filed"
        (spit (str (fs/file root "dhf/cycles/03/ratified.edn")) (pr-str {:cut "spec-3" :room-tree "t" :outcome :passed}))
        (let [{:keys [findings result]} (scorer/score (req root))]
          (is (empty? findings))
          (is (= {:passed 1 :scored 2 :ungraded 1} (select-keys (:score result) [:passed :scored :ungraded])))
          (is (= "t" (:room-tree (edn/read-string (slurp (str score))))))))
      (finally (fs/delete-tree root)))))
