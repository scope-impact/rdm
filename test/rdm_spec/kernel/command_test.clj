(ns rdm-spec.kernel.command-test
  "Unit tests of the command's pure parts: findings and their lines, the
  arguments, the configuration's defaults and the task table. The one
  fixture is a temporary directory, removed."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(deftest a-finding-is-one-line
  (is (= "a/b.feature:3: rule-x: what is wrong"
         (command/format-finding (command/finding "a/b.feature" 3 "rule-x" "what is wrong"))))
  (is (= "a/b.feature: rule-x: what" (command/format-finding (command/finding "a/b.feature" "rule-x" "what"))))
  (is (= "rule-x: what" (command/format-finding (command/finding "rule-x" "what"))))
  (is (= "a/b.feature: rule-x: two lines" (command/format-finding (command/finding "a/b.feature" nil "rule-x" "two\nlines"))))
  (testing "warnings"
    (is (= "f:2: warning: w-x: what" (command/format-finding (command/warning "f" 2 "w-x" "what"))))
    (is (= "f: warning: w-x: what" (command/format-finding (command/warning "f" "w-x" "what"))))
    (is (= "warning: w-x: what" (command/format-finding (command/warning "w-x" "what"))))
    (is (command/warning? (command/warning "w-x" "what")))
    (is (not (command/warning? (command/finding "r" "what")))))
  (testing "a finding with a severity, as the Gherkin reader writes it"
    (is (= "f:1: gherkin-x: m" (command/format-finding {:file "f" :line 1 :rule "gherkin-x" :message "m" :severity :error})))))

(deftest cannot-run-is-an-exception
  (let [e (try (command/cannot-run! "no such thing") nil (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "no such thing" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest arguments-and-options
  (is (= {:args ["03"] :opts {:verdicts "v.edn"}} (command/parse-args "score" ["03" "--verdicts" "v.edn"])))
  (is (= {:args [] :opts {:events true :sources "D"}} (command/parse-args "check" ["--events" "--sources" "D"])))
  (is (command/cannot-run? (try (command/parse-args "check" ["--colour" "x"]) nil (catch Exception e e))))
  (is (command/cannot-run? (try (command/parse-args "status" ["--results"]) nil (catch Exception e e))))
  (testing "the oracle passes every argument on"
    (is (= {:args ["record" "--reason" "the spec grew"] :opts {}}
           (command/parse-args "oracle" ["record" "--reason" "the spec grew"])))))

(deftest the-configuration-has-defaults
  (is (= "dhf/spec" (command/config-value {:config {}} :spec-dir)))
  (is (= "x" (command/config-value {:config {:spec-dir "x"}} :spec-dir)))
  (is (= "dhf/cycles" (command/config-value {} :cycles-dir)))
  (is (= "dhf/spec/domain.edn" (command/config-value {} :domain))))

(deftest paths-are-the-projects
  (is (= "/p/a/b" (str (command/path {:root "/p"} "a/b"))))
  (is (= "/abs" (str (command/path "/p" "/abs"))))
  (is (= "a/b" (command/rel "/p" "/p/a/b")))
  (is (= "a/b" (command/rel {:root "/p"} (fs/file "/p/a/b")))))

(deftest every-task-has-its-usage
  (let [names (map :task command/tasks)]
    (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist"
            "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit" "observe"]
           names))
    (is (every? #(and (:usage %) (:does %) (seq (:stages %))) command/tasks)))
  (testing "the oracle is the gate before fence and verify"
    (doseq [t ["fence" "verify"]]
      (is (= [:gate 'rdm-spec.measurement.oracle/gate] (first (:stages (command/task t))))))))

(deftest the-results-directory
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try
      (fs/create-dirs (fs/file d "dhf/allure-results"))
      (is (= "given" (command/results-dir {:root d :config {} :opts {:results "given"} :env {"RESULTS_DIR" "env"}})))
      (is (= "env" (command/results-dir {:root d :config {} :opts {} :env {"RESULTS_DIR" "env"}})))
      (is (= "dhf/allure-results" (command/results-dir {:root d :config {} :opts {} :env {}})))
      (fs/delete-tree (fs/file d "dhf/allure-results"))
      (is (nil? (command/results-dir {:root d :config {} :opts {} :env {}})))
      (finally (fs/delete-tree d)))))

(deftest an-unknown-task-lists-every-task
  (let [{:keys [exit cannot-run]} (command/execute ["frobnicate"] {:root "/" :env {}})]
    (is (= 2 exit))
    (is (str/includes? cannot-run "frobnicate"))
    (is (str/includes? cannot-run "scoreboard"))))

(deftest help-answers-without-a-project
  (let [{:keys [exit result]} (command/execute ["help"] {:root "/nonexistent" :env {}})]
    (is (= 0 exit))
    (is (= {:task "verify" :usage "verify ROOM CYCLE"
            :does "run the project's checks on the room's code in a checkout of the cut"}
           (first (filter #(= "verify" (:task %)) (:help result)))))))
