(ns rdm-spec.ratification.ratify-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.ratification.ratify :as ratify]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "ratify-test-"})))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit f text)))

(def scs
  [{:id "spec-0001" :ids ["spec-0001"] :wip? false :file "a.feature" :line 5 :name "One"}
   {:id "spec-0002" :ids ["spec-0002"] :wip? true :file "a.feature" :line 9 :name "Two"}
   {:id "spec-0003" :ids ["spec-0003"] :wip? false :file "a.feature" :line 13 :name "Three"}])

(defn- res [status & ids]
  {:file (str "r/" (java.util.UUID/randomUUID) "-result.json")
   :result {:name "a test" :status status :labels (mapv #(hash-map :name "spec" :value %) ids)}})

(defn- rules [m]
  (map :rule (ratify/result-findings (merge {:exit 0 :command-line "bb test.clj" :results-name "r" :scs scs} m))))

(deftest a-run-passes-only-when-every-scenario-passed
  (is (empty? (rules {:results [(res "passed" "spec-0001") (res "passed" "spec-0003")]})))
  (testing "a planned scenario needs no result, and its skipped result refuses nothing"
    (is (empty? (rules {:results [(res "passed" "spec-0001") (res "passed" "spec-0003") (res "skipped" "spec-0002")]}))))
  (testing "a failing test command, whatever the results"
    (is (= ["test-command-failed"] (rules {:exit 3 :results [(res "passed" "spec-0001") (res "passed" "spec-0003")]}))))
  (testing "a green exit with no results"
    (is (= ["no-results"] (rules {:results []}))))
  (testing "a scenario no result names"
    (is (= ["scenario-not-named"] (rules {:results [(res "passed" "spec-0001")]}))))
  (testing "a result that did not pass, even beside one that passed"
    (doseq [st ["failed" "broken" "skipped" "unknown"]]
      (is (= ["result-not-passed"] (rules {:results [(res "passed" "spec-0001") (res st "spec-0001") (res "passed" "spec-0003")]})) st)))
  (testing "a failed unit test (a result naming no scenario)"
    (is (= ["result-not-passed"] (rules {:results [(res "passed" "spec-0001") (res "passed" "spec-0003") (res "failed")]})))
    (is (empty? (rules {:results [(res "passed" "spec-0001") (res "passed" "spec-0003") (res "skipped")]}))))
  (testing "an unreadable result"
    (is (= ["unreadable-result"] (rules {:results [(res "passed" "spec-0001") (res "passed" "spec-0003")
                                                   {:file "r/x-result.json" :error "it is not JSON"}]})))))

(deftest findings-name-what-refused
  (let [fs (ratify/result-findings {:exit 0 :command-line "bb test.clj" :results-name "dhf/cycles/03/results" :scs scs
                                    :results [(res "failed" "spec-0001")]})
        lines (map #(str (:file %) ":" (:line %) " " (:message %)) fs)]
    (is (some #(re-find #"spec-0001 is failed" %) lines))
    (is (some #(re-find #"a\.feature:13 .*spec=spec-0003" %) lines))
    (is (not-any? #(re-find #"spec-0002" %) lines)))
  (is (re-find #"failed with exit status 7"
               (:message (first (ratify/result-findings {:exit 7 :command-line "x" :results-name "r" :scs []
                                                         :results [(res "passed")]}))))))

(deftest the-spec-at-the-cut
  (let [dir (tmp)]
    (write! dir "spec/a/b.feature"
            (str "@UN-001\nFeature: B\n\n  Rule: R\n\n    @spec-0001 @e2e\n    Scenario: One\n      Given x\n\n"
                 "    @e2e @wip\n    Scenario: Two\n      Given x\n\n    @spec-0003 @e2e @wip\n    Scenario: Three\n      Given x\n"))
    (let [spec (ratify/scenarios (str (fs/file dir "spec")) "spec" #(str (fs/relativize dir %)))]
      (is (= [["spec-0001" false 7] [nil true 11] ["spec-0003" true 15]]
             (map (juxt :id :wip? :line) (:scenarios spec))))
      (let [[f :as fs] (ratify/spec-findings spec)]
        (is (= 1 (count fs)))
        (is (= ["spec/a/b.feature" 11 "unstamped-scenario"] [(:file f) (:line f) (:rule f)]))))))

(deftest ratified-edn-reads-back
  (let [rec {:cut "spec-3" :room-tree "4b825dc" :outcome :passed :checkout "dhf/cycles/03/build"}]
    (is (= rec (edn/read-string (ratify/ratified-text rec))))
    (is (str/starts-with? (ratify/ratified-text rec) "{:cut \"spec-3\"\n :room-tree"))
    (is (= (assoc rec :outcome :refused :reason "why")
           (edn/read-string (ratify/ratified-text (assoc rec :outcome :refused :reason "why")))))))

(deftest the-room-tree
  (let [dir (tmp)]
    (testing "a room with no implementation is the empty tree"
      (is (= ratify/empty-tree (ratify/tree-hash dir ["src" "test.clj"])))
      (is (= "4b825dc" (ratify/short-hash ratify/empty-tree))))
    (testing "the hash depends on the implementation's content, and only on it"
      (write! dir "src/a.clj" "(ns a)\n")
      (let [h1 (ratify/tree-hash dir ["src" "test.clj"])]
        (is (re-matches #"[0-9a-f]{40}" h1))
        (write! dir "dhf/x.md" "not implementation\n")
        (is (= h1 (ratify/tree-hash dir ["src" "test.clj"])))
        (write! dir "src/a.clj" "(ns a2)\n")
        (is (not= h1 (ratify/tree-hash dir ["src" "test.clj"])))))))

(deftest the-implementation-is-replaced-never-merged
  (let [room (tmp) build (tmp)]
    (write! room "src/new.clj" "new")
    (write! room "test.clj" "t")
    (write! build "src/old.clj" "old")
    (write! build "bb.edn" "{}")
    (write! build "dhf/spec/x.feature" "kept")
    (ratify/replace-implementation! room build ["src" "test.clj" "bb.edn"])
    (is (fs/exists? (fs/file build "src/new.clj")))
    (is (not (fs/exists? (fs/file build "src/old.clj"))))
    (is (not (fs/exists? (fs/file build "bb.edn"))))
    (is (= "t" (slurp (str (fs/file build "test.clj")))))
    (is (= "kept" (slurp (str (fs/file build "dhf/spec/x.feature")))))))
