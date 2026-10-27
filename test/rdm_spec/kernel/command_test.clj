(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as cmd]))

(defn- cannot-run [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:cannot-run (ex-data e)))))

(deftest the-task-table
  (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist"
          "status" "trace" "release" "score" "scoreboard" "oracle" "plugin" "publish" "audit" "observe"]
         (map :task cmd/tasks)))
  (is (every? #(and (string? (:usage %)) (string? (:does %))) cmd/tasks)))

(deftest parsing-arguments
  (let [check (cmd/task-of "check")]
    (is (= {:args [] :opts {:sources "d" :events true}} (cmd/parse-args check ["--sources" "d" "--events"]))))
  (is (= {:args ["ROOM" "spec-3"] :opts {:context "a,b"}}
         (cmd/parse-args (cmd/task-of "fence") ["ROOM" "spec-3" "--context" "a,b"])))
  (testing "an option the task does not take cannot run"
    (is (str/includes? (cannot-run #(cmd/parse-args (cmd/task-of "check") ["--colour" "red"])) "--colour")))
  (testing "an option with no value cannot run"
    (is (str/includes? (cannot-run #(cmd/parse-args (cmd/task-of "status") ["--results"])) "--results")))
  (testing "a task given too few arguments cannot run, saying its usage"
    (is (str/includes? (cannot-run #(cmd/parse-args (cmd/task-of "scopes") ["spec-3"])) "scopes CUT CUT")))
  (testing "the oracle's arguments are its own"
    (is (= {:args ["record" "--reason" "x"] :opts {}} (cmd/parse-args (cmd/task-of "oracle") ["record" "--reason" "x"])))))

(deftest finding-lines
  (is (= "a.feature:3: level-missing: no level" (cmd/finding-line (cmd/finding "a.feature" 3 "level-missing" "no level"))))
  (is (= "a.edn: unreadable: gone" (cmd/finding-line (cmd/finding "a.edn" nil "unreadable" "gone"))))
  (is (= "rule-x: what" (cmd/finding-line (cmd/finding nil nil "rule-x" "what"))))
  (is (= "a.md:2: warning: w: x" (cmd/warning-line (cmd/finding "a.md" 2 "w" "x"))))
  (is (= "warning: w: x" (cmd/warning-line (cmd/finding nil nil "w" "x"))))
  (testing "a message is one line"
    (is (= "r: one two" (cmd/finding-line (cmd/finding nil nil "r" "one\ntwo")))))
  (testing "a relayed line is kept as it is"
    (is (= "x:1: oracle-differs: …" (cmd/finding-line {:line-text "x:1: oracle-differs: …"})))))

(deftest the-results-directory
  (is (= "a" (cmd/results-dir {:opts {:results "a"} :env {"RESULTS_DIR" "b"} :config {:results-dir "c"}})))
  (is (= "b" (cmd/results-dir {:opts {} :env {"RESULTS_DIR" "b"} :config {:results-dir "c"}})))
  (is (= "c" (cmd/results-dir {:opts {} :env {"RESULTS_DIR" " "} :config {:results-dir "c"}})))
  (is (= "dhf/allure-results" (cmd/results-dir {}))))

(deftest the-command-line-that-starts-this-rdm-spec
  (is (= "bb -cp /x/src:/y/lib.jar -m rdm-spec.kernel.command"
         (cmd/command-line "bb" ["/x/src" "/y/lib.jar"])))
  (testing "a relative classpath entry is made absolute"
    (is (str/includes? (cmd/command-line "bb" ["src"]) (str (fs/absolutize "src"))))))

(deftest reading-at-a-revision
  (is (cmd/working-tree? nil))
  (is (cmd/working-tree? :working-tree))
  (is (not (cmd/working-tree? "spec-3"))))

(deftest running-a-command
  (is (= 0 (:exit (cmd/sh {} "true"))))
  (is (:not-found (cmd/sh {} "no-such-command-anywhere"))))
