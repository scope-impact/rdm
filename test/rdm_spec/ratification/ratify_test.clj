(ns rdm-spec.ratification.ratify-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.ratification.ratify :as ratify]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "rdm-spec-ratify-test-"})))

(defn- w [dir rel text]
  (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f text) (str f)))

(defn- sh [dir & args]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true
                          :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t.invalid"
                                      "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t.invalid"}}
                 args)]
    (is (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(deftest the-room-tree
  (testing "nothing under the implementation is the empty tree"
    (is (= empty-tree (ratify/tree-hash (tmp) ["src" "test.clj"]))))
  (testing "the tree is git's tree of the implementation paths, in full, whatever else the room holds"
    (let [room (tmp) twin (tmp)]
      (w room "src/a.clj" "(ns a)\n")
      (w room "test.clj" "(System/exit 0)\n")
      (w room "notes.txt" "not implementation\n")
      (w room ".gitignore" "src/\n")
      (w twin "src/a.clj" "(ns a)\n")
      (w twin "test.clj" "(System/exit 0)\n")
      (sh twin "git" "init" "-q")
      (sh twin "git" "add" "-A")
      (let [h (ratify/tree-hash room ["src" "test" "test.clj"])]
        (is (re-matches #"[0-9a-f]{40}" h))
        (is (= (sh twin "git" "write-tree") h))))))

(deftest export-and-replace
  (let [repo (tmp) room (tmp) build (str (fs/file (tmp) "build"))]
    (w repo "dhf/spec/x.feature" "spec\n")
    (w repo "src/old.clj" "old\n")
    (w repo "test.clj" "cut's\n")
    (sh repo "git" "init" "-q")
    (sh repo "git" "add" "-A")
    (sh repo "git" "commit" "-q" "-m" "c")
    (sh repo "git" "tag" "spec-3")
    (w room "src/new.clj" "new\n")
    (testing "a cut is a tag; any other name cannot run"
      (is (re-matches #"[0-9a-f]{40}" (ratify/resolve-cut repo "spec-3" "cycle.edn")))
      (is (thrown? Exception (ratify/resolve-cut repo "spec-9" "cycle.edn"))))
    (ratify/export! repo (ratify/resolve-cut repo "spec-3" "c") build)
    (is (= "spec\n" (slurp (fs/file build "dhf/spec/x.feature"))))
    (is (not (fs/exists? (fs/file build ".git"))) "an export has no history")
    (ratify/replace-implementation! build room ["src" "test.clj"])
    (is (not (fs/exists? (fs/file build "src/old.clj"))) "replaced, never merged")
    (is (= "new\n" (slurp (fs/file build "src/new.clj"))))
    (is (not (fs/exists? (fs/file build "test.clj"))) "a path the room lacks is gone")
    (is (fs/exists? (fs/file build "dhf/spec/x.feature")))))

(deftest reading-the-spec
  (let [build (tmp)]
    (w build "dhf/spec/a/b.feature"
       (str "@UN-001\nFeature: B\n  x\n\n  @spec-0001 @e2e\n  Scenario: One\n    Given a\n\n"
            "  @spec-0002 @e2e @wip\n  Scenario: Two\n    Given a\n\n"
            "  @e2e\n  Scenario: Three\n    Given a\n"))
    (let [{:keys [scenarios findings]} (ratify/read-spec build "dhf/spec" "spec")]
      (is (empty? findings))
      (is (= [["spec-0001" false] ["spec-0002" true] [nil false]] (map (juxt :id :wip?) scenarios)))
      (let [[f] (ratify/unstamped-findings scenarios)]
        (is (= ["dhf/spec/a/b.feature" 14 "unstamped-scenario"] ((juxt :file :line :rule) f)))))))

(def scs [{:id "spec-0001" :file "f" :line 5} {:id "spec-0002" :wip? true :file "f" :line 9}
          {:id "spec-0003" :file "f" :line 12}])

(defn- rules [m] (map :rule (ratify/judge (merge {:command "bb test.clj" :log "dhf/cycles/03/test.log"} m))))

(deftest judging-a-run
  (let [ok [{:file "r1" :status "passed" :specs ["spec-0001"]} {:file "r2" :status "passed" :specs ["spec-0003"]}]]
    (testing "every non-planned scenario named and passed, exit 0: a pass"
      (is (empty? (rules {:exit 0 :results ok :scenarios scs}))))
    (testing "a planned scenario's skipped result fails nothing"
      (is (empty? (rules {:exit 0 :results (conj ok {:file "r3" :status "skipped" :specs ["spec-0002"]}) :scenarios scs}))))
    (testing "a failing command names its exit status and its log"
      (let [[f] (ratify/judge {:exit 3 :results ok :scenarios scs :command "bb test.clj" :log "dhf/cycles/03/test.log"})]
        (is (= "test-command-failed" (:rule f)))
        (is (re-find #"exit status 3" (:message f)))
        (is (str/includes? (command/format-finding f) "dhf/cycles/03/test.log"))))
    (testing "a green exit with no results is not a run"
      (is (= ["no-results"] (rules {:exit 0 :results [] :scenarios scs}))))
    (testing "a scenario no result names"
      (let [fs (ratify/judge {:exit 0 :results (take 1 ok) :scenarios scs :command "c" :log "l"})]
        (is (= ["scenario-not-run"] (map :rule fs)))
        (is (re-find #"spec-0003" (:message (first fs))))))
    (testing "a result that did not pass, beside one that did, names the scenario and its status"
      (let [fs (ratify/judge {:exit 0 :results (conj ok {:file "r4" :status "broken" :specs ["spec-0001"]})
                              :scenarios scs :command "c" :log "l"})]
        (is (= ["scenario-not-passed"] (map :rule fs)))
        (is (re-find #"spec-0001.*broken" (:message (first fs))))))))

(deftest reading-results
  (let [d (tmp)]
    (w d "a-result.json" "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")
    (w d "b-result.json" "not json")
    (w d "c-container.json" "{}")
    (let [{:keys [results findings]} (ratify/read-results d "res")]
      (is (= [{:file "res/a-result.json" :status "passed" :specs ["spec-0001"]}] results))
      (is (= ["unreadable-result"] (map :rule findings))))))

(deftest a-whole-ratification
  (let [repo (tmp) room (tmp)]
    (w repo "dhf/harness/config.edn" (pr-str {:id-prefix "spec" :spec-dir "dhf/spec"
                                              :implementation ["src" "test.clj"]
                                              :test-command ["bb" "test.clj"]}))
    (w repo "dhf/spec/a/b.feature" "@UN-001\nFeature: B\n  x\n\n  @spec-0001 @e2e\n  Scenario: One\n    Given a\n")
    (sh repo "git" "init" "-q")
    (sh repo "git" "add" "-A")
    (sh repo "git" "commit" "-q" "-m" "c")
    (sh repo "git" "tag" "spec-3")
    (w repo "dhf/cycles/03/cycle.edn" (pr-str {:cut "spec-3"}))
    (w room "test.clj" (str "(spit (str (System/getenv \"RESULTS_DIR\") \"/x-result.json\")"
                            " \"{\\\"status\\\":\\\"passed\\\",\\\"labels\\\":[{\\\"name\\\":\\\"spec\\\",\\\"value\\\":\\\"spec-0001\\\"}]}\")\n"
                            "(println \"ran\")\n"))
    (let [req {:args [room "03"] :root repo :config nil :env (into {} (System/getenv))}
          out (ratify/ratify req)
          rec (edn/read-string (slurp (fs/file repo "dhf/cycles/03/ratified.edn")))]
      (is (empty? (:findings out)) (pr-str (:findings out)))
      (is (= {:cut "spec-3" :room-tree (ratify/tree-hash room ["src" "test.clj"]) :outcome :passed
              :checkout "dhf/cycles/03/build"}
             rec))
      (is (str/includes? (slurp (fs/file repo "dhf/cycles/03/test.log")) "ran")))
    (testing "a missing cycle.edn cannot run"
      (is (thrown-with-msg? Exception #"dhf/cycles/04/cycle\.edn"
                            (ratify/ratify {:args [room "04"] :root repo :env {}}))))))
