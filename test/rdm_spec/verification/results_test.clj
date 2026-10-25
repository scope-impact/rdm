(ns rdm-spec.verification.results-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.results :as results]))

(deftest interpret-reads-any-allure-result
  (testing "spec labels and status"
    (is (= {:ids ["spec-0001" "spec-0002"] :status "failed" :name "x"}
           (results/interpret {"name" "x" "status" "failed"
                               "labels" [{"name" "spec" "value" "spec-0001"}
                                         {"name" "language" "value" "javascript"}
                                         {"name" "spec" "value" "spec-0002"}]}))))
  (testing "no spec label: no ids"
    (is (= [] (:ids (results/interpret {"status" "passed" "labels" [{"name" "suite" "value" "unit"}]})))))
  (testing "a status outside Allure's five is unknown"
    (is (= "unknown" (:status (results/interpret {"status" "green" "labels" []}))))
    (is (= "unknown" (:status (results/interpret {"labels" []})))))
  (testing "not a result"
    (is (:unreadable (results/interpret [1 2])))
    (is (:unreadable (results/interpret {"labels" "spec=spec-0001"})))))

(deftest read-dir-names-unreadable-files-and-refuses-an-empty-directory
  (let [root (str (fs/create-temp-dir))
        req {:root root}
        dir (fs/file root "r")]
    (fs/create-dirs dir)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no test run wrote results there" (results/read-dir req "r")))
    (spit (fs/file dir "a-result.json") "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")
    (spit (fs/file dir "b-result.json") "{not json")
    (spit (fs/file dir "c-container.json") "{}")
    (let [r (results/read-dir req "r")]
      (is (= [{:ids ["spec-0001"] :status "passed" :name nil :file "r/a-result.json"}] (:results r)))
      (is (= ["r/b-result.json"] (map :file (:unreadable r)))))))

(deftest by-scenario-sorts-known-unknown-and-unlabelled
  (let [rs [{:ids ["spec-0001"] :status "passed" :file "a"}
            {:ids ["spec-9999"] :status "passed" :file "b"}
            {:ids [] :status "failed" :file "c"}]
        {:keys [by-id unknown]} (results/by-scenario #{"spec-0001" "spec-0002"} rs)]
    (is (= {"spec-0001" [(first rs)]} by-id))
    (is (= [{:id "spec-9999" :file "b"}] unknown))))
