(ns rdm-spec.ratification.ratify-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.ratification.ratify :as ratify]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "ratify-test-"})))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit f text)))

(def scenarios
  [{:id "spec-0012" :file "dhf/spec/a.feature" :line 7 :wip? false}
   {:id "spec-0014" :file "dhf/spec/a.feature" :line 12 :wip? false}
   {:id "spec-0013" :file "dhf/spec/a.feature" :line 17 :wip? true}])

(defn- result [id status] {:file (str "/r/" id "-" status "-result.json") :spec [id] :status status})

(defn- rules [findings] (mapv :rule findings))

(deftest judge-passes-when-every-due-scenario-passed
  (is (= [] (ratify/judge scenarios {:results [(result "spec-0012" "passed") (result "spec-0014" "passed")]
                                     :unreadable []} "R"))))

(deftest judge-refuses-a-green-exit-with-no-results
  (let [fs* (ratify/judge scenarios {:results [] :unreadable []} "R")]
    (is (= ["ratify-no-results"] (rules fs*)))
    (is (str/includes? (:message (first fs*)) "not a run"))))

(deftest judge-names-an-unnamed-scenario-where-it-is
  (let [[f & more] (ratify/judge scenarios {:results [(result "spec-0014" "passed")] :unreadable []} "R")]
    (is (empty? more))
    (is (= ["dhf/spec/a.feature" 7 "ratify-scenario-not-named"] [(:file f) (:line f) (:rule f)]))
    (is (str/includes? (:message f) "spec-0012"))))

(deftest judge-ignores-a-planned-scenario
  (testing "neither its absence nor a skipped result refuses"
    (is (= [] (ratify/judge scenarios {:results [(result "spec-0012" "passed") (result "spec-0014" "passed")
                                                 (result "spec-0013" "skipped")]
                                       :unreadable []} "R")))))

(deftest judge-names-every-result-that-did-not-pass
  (let [fs* (ratify/judge scenarios {:results [(result "spec-0012" "passed") (result "spec-0012" "broken")
                                               (result "spec-0014" "failed")]
                                     :unreadable []} "R")]
    (is (= ["ratify-result-not-passed" "ratify-result-not-passed"] (rules fs*)))
    (is (re-find #"spec-0012.*broken" (:message (first fs*))))
    (is (re-find #"spec-0014.*failed" (:message (second fs*))))))

(deftest judge-refuses-an-unreadable-result
  (is (= ["ratify-unreadable-result"]
         (rules (ratify/judge scenarios {:results [(result "spec-0012" "passed") (result "spec-0014" "passed")]
                                         :unreadable ["/r/x-result.json"]} "R")))))

(deftest read-results-reads-labels-and-status
  (let [d (tmp)]
    (spit (str (fs/file d "a-result.json")) "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0012\"},{\"name\":\"suite\",\"value\":\"x\"}]}")
    (spit (str (fs/file d "b-result.json")) "{\"labels\":[]}")
    (spit (str (fs/file d "c-result.json")) "not json")
    (spit (str (fs/file d "d-container.json")) "{}")
    (let [{:keys [results unreadable]} (ratify/read-results d)]
      (is (= [["spec-0012"] []] (mapv :spec results)))
      (is (= ["passed" "unknown"] (mapv :status results)))
      (is (= 1 (count unreadable))))))

(deftest read-spec-finds-ids-planned-work-and-unstamped-scenarios
  (let [d (tmp)]
    (write! d "dhf/spec/x/a.feature"
            (str "@UN-001\nFeature: A\n\n  @spec-0012 @e2e\n  Scenario: One\n    Given a\n\n"
                 "  @e2e @wip @spec-0013\n  Scenario: Two\n    Given b\n\n"
                 "  @e2e\n  Scenario: Three\n    Given c\n"))
    (let [{:keys [scenarios findings]} (ratify/read-spec d "dhf/spec" "spec")]
      (is (= [["spec-0012" false] ["spec-0013" true]] (mapv (juxt :id :wip?) scenarios)))
      (is (= [["dhf/spec/x/a.feature" 13 "ratify-unstamped-scenario"]] (mapv (juxt :file :line :rule) findings))))))

(deftest tree-hash-is-the-git-tree-of-the-implementation-paths
  (let [d (tmp)]
    (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" (ratify/tree-hash d ["src" "test.clj"])))
    (write! d "notes.txt" "outside every path")
    (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" (ratify/tree-hash d ["src" "test.clj"])))
    (write! d "src/a.clj" "(ns a)\n")
    (let [h (ratify/tree-hash d ["src" "test.clj"])]
      (is (re-matches #"[0-9a-f]{40}" h))
      (is (not= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" h))
      (is (= h (ratify/tree-hash d ["src" "test.clj"])) "the same files hash the same"))))

(deftest replace-implementation-never-merges
  (let [build (tmp) room (tmp)]
    (write! build "src/old.clj" "old")
    (write! build "src/kept.clj" "build's")
    (write! build "test.clj" "build's tests")
    (write! build "dhf/spec/a.feature" "the cut's")
    (write! room "src/kept.clj" "room's")
    (write! room "dhf/spec/a.feature" "edited")
    (ratify/replace-implementation! build room ["src" "test.clj"])
    (is (not (fs/exists? (fs/file build "src/old.clj"))))
    (is (= "room's" (slurp (str (fs/file build "src/kept.clj")))))
    (is (not (fs/exists? (fs/file build "test.clj"))) "a path the room lacks is absent")
    (is (= "the cut's" (slurp (str (fs/file build "dhf/spec/a.feature")))))))

(deftest a-missing-cycle-cannot-run
  (let [d (tmp)
        e (try (ratify/read-cycle d "dhf/cycles/03/cycle.edn") nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (::command/cannot-run (ex-data e)))
    (is (str/includes? (ex-message e) "dhf/cycles/03/cycle.edn"))))

(deftest a-cycle-naming-no-cut-cannot-run
  (let [d (tmp)]
    (write! d "dhf/cycles/03/cycle.edn" "{:scope :project}")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names no :cut"
                          (ratify/read-cycle d "dhf/cycles/03/cycle.edn")))))
