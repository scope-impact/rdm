(ns rdm-spec.measurement.oracle-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.oracle :as oracle]))

(deftest diff
  (is (empty? (oracle/diff {:a 1} {:a 1})))
  (testing "items with an id are matched by id"
    (is (= [{:path [:items {:item "spec-0014"} :verdict] :golden :pass :produced :fail}]
           (oracle/diff {:items [{:id "spec-0012" :verdict :pass} {:id "spec-0014" :verdict :pass}]}
                        {:items [{:id "spec-0012" :verdict :pass} {:id "spec-0014" :verdict :fail}]})))
    (is (= [{:path ["items" {:item "b"}] :golden ::oracle/absent :produced {"id" "b"}}]
           (oracle/diff {"items" [{"id" "a"}]} {"items" [{"id" "a"} {"id" "b"}]})))
    (is (= [[:items :order]] (map :path (oracle/diff {:items [{:id "a"} {:id "b"}]} {:items [{:id "b"} {:id "a"}]})))))
  (testing "anything else is compared whole"
    (is (= [{:path [:n] :golden 1 :produced 2}] (oracle/diff {:n 1} {:n 2})))))

(deftest findings
  (let [[f] (oracle/difference-findings "g/score.edn" "{:items [{:id \"spec-0012\"}\n {:id \"spec-0014\"}]}"
                                        [{:path [:items {:item "spec-0014"} :verdict] :golden :pass :produced :fail}])]
    (is (= {:file "g/score.edn" :line 2 :rule "golden-differs"} (select-keys f [:file :line :rule])))
    (is (= ":items item spec-0014 :verdict: golden :pass, produced :fail" (:message f))))
  (is (= ["golden-missing"] (map :rule (oracle/judge-golden "g" nil "{}"))))
  (is (empty? (oracle/judge-golden "g" "{}" nil))))

(deftest no-oracle
  (let [root (str (fs/create-temp-dir {:prefix "rdm-spec-unit-"}))]
    (try
      (testing "the oracle task says there is none; a gate passes silently"
        (is (re-find #"no oracle to run" (get-in (oracle/oracle {:task "oracle" :root root :args [] :opts {}}) [:result :oracle :message])))
        (is (= {:result {} :findings []} (oracle/oracle {:task "fence" :root root :args ["R" "spec-3"] :opts {}}))))
      (testing "record needs a reason, before anything is read"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reason is required"
                              (oracle/oracle {:task "oracle" :root root :args ["record"] :opts {}}))))
      (finally (fs/delete-tree root)))))
