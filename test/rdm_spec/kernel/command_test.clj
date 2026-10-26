(ns rdm-spec.kernel.command-test
  "Unit tests of the command: findings, the configuration, options and the
  task table. Fixtures on disk are removed by each test."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(deftest findings-are-formatted-one-per-line
  (is (= "a.feature:3: scenario-no-level: no level"
         (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "no level"))))
  (is (= "a.feature: rule: what" (command/format-finding (command/finding "a.feature" "rule" "what"))))
  (is (= "rule: what" (command/format-finding (command/finding "rule" "what"))))
  (is (= "a.md:2: warning: rule: what" (command/format-finding (command/warning "a.md" 2 "rule" "what"))))
  (is (= "a.md: warning: rule: what" (command/format-finding (command/warning "a.md" "rule" "what"))))
  (is (= "warning: rule: what" (command/format-finding (command/warning "rule" "what"))))
  (is (command/warning? (command/warning "rule" "what")))
  (is (not (command/warning? (command/finding "rule" "what"))))
  (testing "a refusal from the reader keeps its line"
    (is (= "f:9: gherkin-x: y" (command/format-finding {:file "f" :line 9 :rule "gherkin-x" :message "y" :severity :error})))))

(deftest cannot-run-is-an-exception-the-command-recognises
  (let [e (try (command/cannot-run! "why") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "why" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {}))))
  (is (not (command/cannot-run? nil))))

(deftest configuration-values-default
  (is (= "dhf/spec" (command/config-value {:config {}} :spec-dir)))
  (is (= "x" (command/config-value {:config {:spec-dir "x"}} :spec-dir)))
  (is (= "spec" (command/config-value {} :id-prefix)))
  (is (= "dhf/allure-results" (command/config-value {} :results-dir)))
  (is (map? (command/config-value {} :site))))

(deftest paths-are-relative-to-the-project
  (is (= "/r/a/b" (command/path "/r" "a/b")))
  (is (= "/abs" (command/path {:root "/r"} "/abs")))
  (is (= "a/b.md" (command/rel {:root "/r"} "/r/a/b.md"))))

(deftest options-are-parsed-by-the-task-table
  (is (= {:args ["ROOM" "spec-3"] :opts {:context "a,b"}}
         (command/parse-args "fence" ["ROOM" "spec-3" "--context" "a,b"])))
  (is (= {:args [] :opts {:events true}} (command/parse-args "check" ["--events"])))
  (is (command/cannot-run? (try (command/parse-args "check" ["--nope"]) (catch Exception e e))))
  (is (command/cannot-run? (try (command/parse-args "status" ["--results"]) (catch Exception e e))))
  (is (command/cannot-run? (try (command/parse-args "scopes" ["spec-3"]) (catch Exception e e)))))

(deftest the-results-directory
  (testing "--results, else RESULTS_DIR, else the configured one"
    (is (= "x" (command/results-dir {:opts {:results "x"} :env {"RESULTS_DIR" "y"} :config {}} :required)))
    (is (= "y" (command/results-dir {:opts {} :env {"RESULTS_DIR" "y"} :config {}} :required)))
    (is (= "dhf/allure-results" (command/results-dir {:opts {} :env {} :config {}} :required))))
  (testing "an optional one that does not exist is none"
    (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
      (try
        (is (nil? (command/results-dir {:root d :opts {} :env {} :config {}} :optional)))
        (fs/create-dirs (fs/file d "dhf/allure-results"))
        (is (= "dhf/allure-results" (command/results-dir {:root d :opts {} :env {} :config {}} :optional)))
        (finally (fs/delete-tree d))))))

(deftest every-task-has-a-usage
  (is (= "help" (:task (first (command/help-entries)))))
  (is (every? #(and (:usage %) (:does %)) (command/help-entries))))

(deftest a-task-reports-and-exits
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try
      (testing "an unknown task cannot run, and the tasks are listed"
        (let [x (command/execute ["frobnicate"] {:root d :env {}})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:cannot-run x) "frobnicate"))
          (is (str/includes? (:cannot-run x) "scoreboard"))))
      (testing "help needs no configuration"
        (is (= 0 (:exit (command/execute ["help"] {:root d :env {}})))))
      (testing "a task needing a configuration cannot run without one"
        (let [x (command/execute ["check"] {:root d :env {}})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:cannot-run x) "dhf/harness/config.edn"))))
      (finally (fs/delete-tree d)))))

(deftest a-stage-not-built-refuses-the-task
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try
      (spit (str (fs/file (fs/create-dirs (fs/file d "dhf/harness")) "config.edn")) "{}")
      (with-redefs [command/tasks (assoc command/tasks "check"
                                         (assoc (get command/tasks "check")
                                                :stages [{:name "nothing" :fn 'rdm-spec.not-built/stage}]))]
        (let [x (command/execute ["check"] {:root d :env {}})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:cannot-run x) "not built"))))
      (finally (fs/delete-tree d)))))

(deftest a-gate-stops-the-task
  (let [ran (atom [])]
    (with-redefs [command/resolve-stage (fn [{:keys [name]}]
                                          (fn [_] (swap! ran conj name)
                                            {:result {} :findings (if (= "gate" name) [(command/finding "r" "m")] [])}))]
      (let [x (command/run-stages {:state (atom {})} [{:name "gate" :gate? true} {:name "after"}])]
        (is (= ["gate"] @ran))
        (is (= 1 (count (:findings x))))))
    (reset! ran [])
    (with-redefs [command/resolve-stage (fn [{:keys [name]}]
                                          (fn [_] (swap! ran conj name)
                                            {:result {name 1} :findings [(command/warning "r" "m")]}))]
      (let [x (command/run-stages {:state (atom {})} [{:name "gate" :gate? true} {:name "after"}])]
        (is (= ["gate" "after"] @ran) "a warning does not stop the task")
        (is (= {"gate" 1 "after" 1} (:result x)))))))
