(ns rdm-spec.ratification.ratify-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.ratification.ratify :as ratify]))

(defn- rules [fs] (set (map :rule fs)))

(def scs
  [{:ids ["spec-0012"] :wip? false :file "f.feature" :line 3}
   {:ids ["spec-0014"] :wip? false :file "f.feature" :line 9}
   {:ids ["spec-0013"] :wip? true :file "f.feature" :line 15}])

(deftest scenario-findings
  (testing "every scenario not @wip named, every result passed"
    (is (empty? (ratify/scenario-findings scs [{:spec ["spec-0012"] :status "passed"}
                                               {:spec ["spec-0014"] :status "passed"}]))))
  (testing "a scenario no result names"
    (let [f (ratify/scenario-findings scs [{:spec ["spec-0014"] :status "passed"}])]
      (is (= #{"scenario-not-run"} (rules f)))
      (is (= [["f.feature" 3]] (map (juxt :file :line) f)))
      (is (re-find #"spec-0012" (:message (first f))))))
  (testing "one result of an outline did not pass"
    (let [f (ratify/scenario-findings scs [{:spec ["spec-0012"] :status "passed"}
                                           {:spec ["spec-0012"] :status "broken"}
                                           {:spec ["spec-0014"] :status "passed"}])]
      (is (= #{"scenario-not-passed"} (rules f)))
      (is (re-find #"spec-0012.*broken" (:message (first f))))))
  (testing "a planned scenario needs no result"
    (is (empty? (ratify/scenario-findings [(nth scs 2)] [])))))

(deftest result-findings
  (is (= #{"no-results"} (rules (ratify/result-findings {:results [] :unreadable []} identity))))
  (is (= #{"unreadable-result"} (rules (ratify/result-findings {:results [{:spec []}] :unreadable [{:file "x" :why "bad"}]}
                                                               identity))))
  (is (empty? (ratify/result-findings {:results [{:spec ["spec-0012"]}] :unreadable []} identity))))

(deftest read-results
  (let [d (fs/create-temp-dir)]
    (spit (str (fs/file d "a-result.json")) "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0012\"}]}")
    (spit (str (fs/file d "b-result.json")) "not json")
    (spit (str (fs/file d "c-container.json")) "{}")
    (let [{:keys [results unreadable]} (ratify/read-results d)]
      (is (= [{:spec ["spec-0012"] :status "passed"}] (map #(dissoc % :file) results)))
      (is (= 1 (count unreadable))))))

(deftest spec-findings
  (let [f (ratify/spec-findings {:refusals [{:file "a.feature" :line 2 :rule "unknown-keyword" :message "no"}]
                                 :scenarios [{:ids [] :file "b.feature" :line 7 :name "x"}
                                             {:ids ["spec-0001"] :file "b.feature" :line 12 :name "y"}]})]
    (is (= #{"spec-unknown-keyword" "unstamped-scenario"} (rules f)))
    (is (= ["b.feature" 7] ((juxt :file :line) (first (filter #(= "unstamped-scenario" (:rule %)) f)))))))

(deftest ratified-text
  (let [m (ratify/ratified-map {:cut "spec-3" :room-tree ratify/empty-tree :outcome :passed
                                :checkout "dhf/cycles/03/build"})
        t (ratify/ratified-text m)]
    (is (= [:cut :room-tree :outcome :checkout] (keys m)))
    (is (= m (edn/read-string t)))
    (is (re-find #"^\{:cut \"spec-3\"\n :room-tree" t)))
  (is (= :reason (last (keys (ratify/ratified-map {:cut "c" :room-tree "t" :outcome :refused :checkout "b"
                                                   :reason "why"}))))))

(deftest tree-hash
  (let [d (str (fs/create-temp-dir))]
    (p/shell {:dir d :out :string :err :string} "git" "init" "-q")
    (testing "no implementation: the empty tree, in full"
      (is (= "4b825dc642cb6eb9a060e54bf8d69288fbee4904" (ratify/tree-hash d ["src" "test.clj"]))))
    (testing "only the implementation paths count, ignored files included"
      (spit (str (fs/file d ".gitignore")) "test.clj\n")
      (spit (str (fs/file d "test.clj")) "x\n")
      (spit (str (fs/file d "README.md")) "not implementation\n")
      (let [h (ratify/tree-hash d ["src" "test.clj"])]
        (is (re-matches #"[0-9a-f]{40}" h))
        (is (not= ratify/empty-tree h))
        (spit (str (fs/file d "README.md")) "changed\n")
        (is (= h (ratify/tree-hash d ["src" "test.clj"])))))))
