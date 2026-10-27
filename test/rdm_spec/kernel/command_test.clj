(ns rdm-spec.kernel.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as cmd]))

(deftest a-finding-is-one-line-in-the-spec-s-format
  (is (= "a.feature:3: rule-x: what" (cmd/finding-line (cmd/finding "a.feature" 3 "rule-x" "what") false)))
  (is (= "a.feature: rule-x: what" (cmd/finding-line (cmd/finding "a.feature" nil "rule-x" "what") false)))
  (is (= "rule-x: what" (cmd/finding-line (cmd/finding nil nil "rule-x" "what") false)))
  (is (= "a.feature:3: warning: rule-x: what" (cmd/finding-line (cmd/finding "a.feature" 3 "rule-x" "what") true)))
  (is (= "warning: rule-x: what" (cmd/finding-line (cmd/finding nil nil "rule-x" "what") true)))
  (testing "a newline in a message becomes a space"
    (is (= "f: r: one two" (cmd/finding-line (cmd/finding "f" nil "r" "one\ntwo") false))))
  (testing "a relayed line is kept as it was"
    (is (= "x:1: oracle-differs: …" (cmd/finding-line {:raw "x:1: oracle-differs: …"} false)))))

(deftest arguments-are-positional-or-flags
  (is (= {:args ["ROOM" "spec-3"] :opts {:context ["a" "b"] :component ["spec/parser"]}}
         (cmd/parse-args "fence" ["ROOM" "spec-3" "--context" "a,b" "--component" "spec/parser"])))
  (is (= {:args ["03"] :opts {:verdicts ["a.edn" "b.edn"]}}
         (cmd/parse-args "score" ["03" "--verdicts" "a.edn" "--verdicts" "b.edn"])))
  (is (= {:args [] :opts {:events true}} (cmd/parse-args "check" ["--events"])))
  (is (thrown? clojure.lang.ExceptionInfo (cmd/parse-args "check" ["--nope"])))
  (is (thrown? clojure.lang.ExceptionInfo (cmd/parse-args "status" ["--results"]))))

(deftest the-results-directory-is-the-flag-else-the-environment-else-the-configuration
  (is (= "x" (cmd/results-dir {:opts {:results "x"} :env {"RESULTS_DIR" "y"} :config {:results-dir "z"}})))
  (is (= "y" (cmd/results-dir {:opts {} :env {"RESULTS_DIR" "y"} :config {:results-dir "z"}})))
  (is (= "z" (cmd/results-dir {:opts {} :env {} :config {:results-dir "z"}})))
  (is (= "dhf/allure-results" (cmd/results-dir {:opts {} :env {} :config {}}))))

(deftest every-task-has-its-usage
  (is (= "help" (:task (first cmd/tasks))))
  (is (= 22 (count cmd/tasks)))
  (is (every? #(and (:usage %) (:does %)) cmd/tasks)))

(deftest several-specs-are-read-as-one-configuration-each
  (let [cfg {:id-prefix "spec" :spec-dir "dhf/spec"
             :specs [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"}]}]
    (is (= [{:name "auth" :spec-dir "dhf/sdd/auth" :id-prefix "auth" :model "dhf/sdd/auth/model.edn"
             :events "dhf/sdd/auth/events.edn" :needs "dhf/sdd/auth/needs.edn" :ledger "dhf/sdd/auth/ids.edn"
             :glossary "dhf/sdd/auth/glossary.md" :domain "dhf/sdd/auth/domain.edn"}]
           (map #(select-keys % [:name :spec-dir :id-prefix :model :events :needs :ledger :glossary :domain])
                (cmd/spec-configs cfg)))))
  (is (= [nil] (map :name (cmd/spec-configs {:spec-dir "dhf/spec"})))))
