(ns rdm-spec.measurement.oracle-test
  "Unit tests of the Oracle's pure parts: comparing an answer with its
  golden, what a record may change, and the order goldens are judged in.
  No fixture on disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.oracle :as oracle]))

(def ^:private golden
  {:out {:score {:cycle "01" :passed 1}} :exit 0 :err ""
   :wrote {"dhf/cycles/01/score.edn" {:items [{:id "spec-0012" :verdict :pass} {:id "spec-0014" :verdict :pass}]}}})

(deftest an-answer-that-is-its-golden-differs-in-nothing
  (is (empty? (oracle/differences golden golden))))

(deftest every-difference-is-named-with-both-values
  (let [actual (assoc-in golden [:wrote "dhf/cycles/01/score.edn" :items 1 :verdict] :fail)
        [d & more] (oracle/differences golden actual)]
    (is (nil? more))
    (is (= [:pass :fail] [(:golden d) (:actual d)]))
    (is (= "spec-0014" (:item d))))
  (testing "the exit and the error stream, word for word"
    (is (= #{"exit" "error stream"}
           (set (map :what (oracle/differences golden (assoc golden :exit 1 :err "a line"))))))))

(deftest timing-never-enters-a-golden
  (is (= {:verify {:checks [{:name "tests" :seconds :measured}]}}
         (oracle/normalize {:verify {:checks [{:name "tests" :seconds 1.23}]}}))))

(deftest goldens-are-judged-in-the-order-of-the-judging-components-then-by-case
  (is (= [[:measurement/scorer "01"] [:measurement/scorer "02"] [:regeneration/scopes "a"]]
         (oracle/cases {:regeneration/scopes {"a" nil} :measurement/scorer {"02" nil "01" nil} :cycles {}}))))

(deftest a-record-adds-new-cases-and-changes-only-named-goldens
  (let [plan (fn [changed added named] (oracle/record-refusals changed added named))]
    (is (empty? (plan [] [[:regeneration/scopes "new"]] #{})))
    (is (empty? (plan [[:regeneration/scopes "order"]] [[:regeneration/scopes "new"]] #{[:regeneration/scopes "order"]})))
    (is (= [[:regeneration/scopes "order"]] (plan [[:regeneration/scopes "order"]] [[:regeneration/scopes "new"]] #{})))
    (testing "a record that adds no case may change what it records, every change named in :recorded"
      (is (empty? (plan [[:regeneration/scopes "order"]] [] #{}))))))

(deftest changes-are-named-component-and-case
  (is (= #{[:regeneration/scopes "order"] [:measurement/scorer "01"]}
         (oracle/parse-changes "regeneration/scopes:order,measurement/scorer:01")))
  (is (empty? (oracle/parse-changes nil))))
