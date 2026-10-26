(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(deftest findings-and-warnings-are-printed-one-way
  (is (= "a/b.feature:3: rule-x: what" (command/format-finding (command/finding "a/b.feature" 3 "rule-x" "what"))))
  (is (= "a/b.feature: rule-x: what" (command/format-finding (command/finding "a/b.feature" "rule-x" "what"))))
  (is (= "rule-x: what" (command/format-finding (command/finding "rule-x" "what"))))
  (is (= "a.md:2: warning: w: why" (command/format-finding (command/warning "a.md" 2 "w" "why"))))
  (is (= "a.md: warning: w: why" (command/format-finding (command/warning "a.md" "w" "why"))))
  (is (= "warning: w: why" (command/format-finding (command/warning "w" "why"))))
  (is (command/warning? (command/warning "w" "why")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest cannot-run-is-an-exception-of-its-own
  (let [e (try (command/cannot-run! "no such thing") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "no such thing" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "x" {})))))

(deftest configuration-values-have-defaults
  (is (= "dhf/spec" (command/config-value {} :spec-dir)))
  (is (= "x" (command/config-value {:config {:spec-dir "x"}} :spec-dir)))
  (is (= "site" (:dir (command/config-value {} :site))))
  (is (= "/r/a/b" (str (command/path "/r" "a/b"))))
  (is (= "/abs" (str (command/path {:root "/r"} "/abs"))))
  (is (= "a/b" (command/rel {:root "/r"} "/r/a/b"))))

(deftest arguments-are-parsed-by-the-task-table
  (is (= {:task "fence" :args ["R" "spec-3"] :opts {:context "a,b"}}
         (select-keys (command/parse-args ["fence" "R" "spec-3" "--context" "a,b"]) [:task :args :opts])))
  (is (= {:events true} (:opts (command/parse-args ["check" "--events"]))))
  (is (re-find #"--bogus" (:cannot-run (command/parse-args ["check" "--bogus"]))))
  (is (re-find #"frobnicate" (:cannot-run (command/parse-args ["frobnicate"]))))
  (is (re-find #"verify ROOM CYCLE" (:cannot-run (command/parse-args ["verify" "R"])))))

(deftest every-task-has-usage-and-help-lists-them-in-order
  (let [{:keys [result exit]} (command/execute ["help"] {:root "."})
        ts (get-in result [:help :tasks])]
    (is (= 0 exit))
    (is (= "help" (:task (first ts))))
    (is (some #(= {:task "verify" :usage "verify ROOM CYCLE"
                   :does "run the project's checks on the room's code in a checkout of the cut"} %) ts))
    (is (not-any? #(= "ratify" (:task %)) ts))))

(deftest an-unknown-task-cannot-run-and-lists-the-tasks
  (let [{:keys [exit cannot-run]} (command/execute ["frobnicate"] {:root "."})]
    (is (= 2 exit))
    (is (str/includes? cannot-run "frobnicate"))
    (is (str/includes? cannot-run "verify"))))

(deftest a-missing-configuration-cannot-run
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try
      (let [{:keys [exit cannot-run]} (command/execute ["status"] {:root d})]
        (is (= 2 exit))
        (is (str/includes? cannot-run "dhf/harness/config.edn")))
      (finally (fs/delete-tree d)))))

(deftest the-results-directory-is-the-flag-else-the-environment-else-the-configured-one
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try
      (fs/create-dirs (fs/file d "dhf/allure-results"))
      (is (= "x" (command/results-of {:root d :opts {:results "x"} :env {"RESULTS_DIR" "y"} :config {}})))
      (is (= "y" (command/results-of {:root d :opts {} :env {"RESULTS_DIR" "y"} :config {}})))
      (is (= "dhf/allure-results" (command/results-of {:root d :opts {} :env {} :config {}})))
      (is (nil? (command/results-of {:root d :opts {} :env {} :config {:results-dir "nope"}})))
      (finally (fs/delete-tree d)))))

(deftest a-gate-stops-the-task-and-a-quiet-stage-reports-nothing
  (let [stage (fn [rule] (fn [_] {:result {(keyword rule) true} :findings [(command/finding rule "m")]}))
        run (fn [stages] (command/run-stages {:root "." :state (atom {})} stages))]
    (testing "every stage reports"
      (is (= ["a" "b"] (map :rule (:findings (run [{:fn (stage "a")} {:fn (stage "b")}]))))))
    (testing "a gate with findings stops the task, and its result is not printed"
      (let [out (run [{:fn (stage "a") :gate? true} {:fn (stage "b")}])]
        (is (= ["a"] (map :rule (:findings out))))
        (is (= {} (:result out)))))
    (testing "a quiet stage's findings and result are dropped"
      (let [out (run [{:fn (stage "a") :quiet? true} {:fn (stage "b")}])]
        (is (= ["b"] (map :rule (:findings out))))
        (is (= {:b true} (:result out)))))))
