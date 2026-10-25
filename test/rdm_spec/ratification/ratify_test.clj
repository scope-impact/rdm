(ns rdm-spec.ratification.ratify-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.ratification.ratify :as ratify]))

(def scenarios
  [{:id "spec-0012" :file "f.feature" :line 7}
   {:id "spec-0014" :file "f.feature" :line 12}
   {:id "spec-0013" :file "f.feature" :line 17 :wip? true}])

(defn- rules [fs] (map :rule fs))

(deftest every-scenario-is-named-and-passed
  (is (empty? (ratify/result-findings [{:spec "spec-0012" :status "passed"} {:spec "spec-0014" :status "passed"}]
                                      scenarios "r")))
  (testing "a planned scenario needs no result, and its results do not count"
    (is (empty? (ratify/result-findings [{:spec "spec-0012" :status "passed"} {:spec "spec-0014" :status "passed"}
                                         {:spec "spec-0013" :status "skipped"}]
                                        scenarios "r"))))
  (testing "a scenario no result names"
    (let [[f] (ratify/result-findings [{:spec "spec-0012" :status "passed"}] scenarios "r")]
      (is (= ["scenario-unnamed" "f.feature" 12] [(:rule f) (:file f) (:line f)]))
      (is (re-find #"spec-0014" (:message f)))))
  (testing "a result that did not pass, with its status"
    (let [fs (ratify/result-findings [{:spec "spec-0012" :status "passed"} {:spec "spec-0012" :status "broken" :file "x"}
                                      {:spec "spec-0014" :status "passed"}]
                                     scenarios "r")]
      (is (= ["result-not-passed"] (rules fs)))
      (is (re-find #"spec-0012.*broken" (:message (first fs))))))
  (testing "a failed result naming no scenario, and a warning for an unknown id"
    (let [fs (ratify/result-findings [{:spec "spec-0012" :status "passed"} {:spec "spec-0014" :status "passed"}
                                      {:spec nil :status "failed" :name "a unit test"}
                                      {:spec "spec-0999" :status "passed"}]
                                     scenarios "r")]
      (is (= ["result-not-passed" "result-unknown"] (rules fs)))
      (is (= :warning (:severity (second fs)))))))

(deftest results-are-read-from-json
  (let [d (str (fs/create-temp-dir {:prefix "ratify-test-"}))]
    (try
      (spit (str (fs/file d "a-result.json"))
            "{\"name\":\"t\",\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0012\"}]}")
      (spit (str (fs/file d "b-result.json")) "[1,2]")
      (spit (str (fs/file d "other.json")) "{}")
      (let [{:keys [results findings]} (ratify/read-results {:root d} d)]
        (is (= [{:file "a-result.json" :status "passed" :name "t" :spec "spec-0012"}] results))
        (is (= ["result-unreadable"] (rules findings))))
      (finally (fs/delete-tree d)))))

(deftest an-unstamped-scenario-is-a-finding
  (let [d (str (fs/create-temp-dir {:prefix "ratify-test-"}))]
    (try
      (fs/create-dirs (fs/file d "dhf/spec/a"))
      (spit (str (fs/file d "dhf/spec/a/b.feature"))
            (str "@UN-001\nFeature: B\n\n  @spec-0001 @e2e\n  Scenario: Stamped\n    Then it passes\n\n"
                 "  @e2e @wip\n  Scenario: Not stamped\n    Then it passes\n"))
      (let [{:keys [scenarios findings]} (ratify/spec-scenarios d {:spec-dir "dhf/spec"})]
        (is (= [{:id "spec-0001" :file "dhf/spec/a/b.feature" :line 5 :wip? false}] scenarios))
        (is (= [["scenario-unstamped" "dhf/spec/a/b.feature" 9]] (map (juxt :rule :file :line) findings))))
      (finally (fs/delete-tree d)))))

(deftest the-room-tree-is-its-implementation-only
  (let [d (str (fs/create-temp-dir {:prefix "ratify-test-"}))]
    (try
      (fs/create-dirs (fs/file d "src"))
      (spit (str (fs/file d "src" "a.clj")) "(ns a)\n")
      (let [h (ratify/tree-hash {:root d} d ["src" "test.clj"])]
        (is (re-matches #"[0-9a-f]{40}" h))
        (spit (str (fs/file d "notes.md")) "not implementation\n")
        (is (= h (ratify/tree-hash {:root d} d ["src" "test.clj"])))
        (spit (str (fs/file d "test.clj")) "(System/exit 0)\n")
        (is (not= h (ratify/tree-hash {:root d} d ["src" "test.clj"])))
        (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" (ratify/tree-hash {:root d} d ["nothing"]))))
      (finally (fs/delete-tree d)))))
