(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- with-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest findings-are-formatted-one-per-line
  (is (= "a.feature:3: scenario-no-level: no level"
         (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "no level"))))
  (is (= "a.feature: rule-x: what" (command/format-finding (command/finding "a.feature" "rule-x" "what"))))
  (is (= "rule-x: what" (command/format-finding (command/finding "rule-x" "what"))))
  (is (= "a.md:2: warning: rule-y: careful" (command/format-finding (command/warning "a.md" 2 "rule-y" "careful"))))
  (is (= "a.md: warning: rule-y: careful" (command/format-finding (command/warning "a.md" "rule-y" "careful"))))
  (is (= "warning: rule-y: careful" (command/format-finding (command/warning "rule-y" "careful"))))
  (testing "a message is kept on one line"
    (is (= "r: one two" (command/format-finding (command/finding "r" "one\ntwo")))))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest cannot-run-is-an-exception-of-its-own
  (let [e (try (command/cannot-run! "no way") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "no way" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest configuration-values-default
  (is (= "dhf/spec" (command/config-value {:config {}} :spec-dir)))
  (is (= "x" (command/config-value {:config {:spec-dir "x"}} :spec-dir)))
  (is (= "dhf/allure-results" (command/config-value {} :results-dir))))

(deftest paths-are-relative-to-the-project
  (with-dir
    (fn [d]
      (is (= (str (fs/file d "a/b.txt")) (str (command/path d "a/b.txt"))))
      (is (= (str (fs/file d "a/b.txt")) (str (command/path {:root d} "a/b.txt"))))
      (is (= "/x/y" (str (command/path d "/x/y"))))
      (is (= "a/b.txt" (command/rel d (fs/file d "a/b.txt")))))))

(deftest a-gate-stops-the-task
  (let [ran (atom [])
        stage (fn [k fs*] (fn [_] (swap! ran conj k) {:result {k true} :findings fs*}))
        out (command/run-stages {:config {}} [{:fn (stage :a [(command/finding "r" "bad")]) :gate? true}
                                              {:fn (stage :b [])}])]
    (is (= [:a] @ran))
    (is (= {:a true} (:result out)))
    (is (= 1 (count (:findings out)))))
  (testing "a gate's warnings do not stop it"
    (let [out (command/run-stages {:config {}} [{:fn (fn [_] {:result {} :findings [(command/warning "r" "w")]}) :gate? true}
                                                {:fn (fn [_] {:result {:b 1} :findings []})}])]
      (is (= {:b 1} (:result out)))))
  (testing "without a gate every stage reports"
    (let [out (command/run-stages {:config {}} [{:fn (fn [_] {:result {:a 1} :findings [(command/finding "r" "one")]})}
                                                {:fn (fn [_] {:result {:b 2} :findings [(command/finding "r" "two")]})}])]
      (is (= {:a 1 :b 2} (:result out)))
      (is (= ["r: one" "r: two"] (map command/format-finding (:findings out))))))
  (testing "a quiet stage says nothing"
    (let [out (command/run-stages {:config {}} [{:fn (fn [_] {:result {:a 1} :findings [(command/finding "r" "one")]}) :quiet? true}])]
      (is (= {} (:result out)))
      (is (empty? (:findings out))))))

(deftest a-stage-not-built
  (testing "a gate whose code is not built refuses the task"
    (is (command/cannot-run? (try (command/run-stages {:config {}} [{:fn 'rdm-spec.nowhere.none/gate :gate? true}])
                                  nil (catch Exception e e)))))
  (testing "so does any other stage: a task never passes on a stage that did not run"
    (let [ran (atom false)]
      (is (command/cannot-run? (try (command/run-stages {:config {}} [{:fn (fn [_] (reset! ran true) {:result {} :findings []})}
                                                                      {:fn 'rdm-spec.nowhere.none/stage}])
                                    nil (catch Exception e e))))
      (is (not @ran)))))

(deftest the-command-line-is-read-against-the-task-table
  (is (= {:task "status" :args [] :opts {:results "x"} :events? false}
         (select-keys (command/parse-args ["status" "--results" "x"]) [:task :args :opts :events?])))
  (is (:events? (command/parse-args ["check" "--events"])))
  (is (= {:context "a,b" :component "c/d"}
         (:opts (command/parse-args ["fence" "R" "spec-1" "--context" "a" "--context" "b" "--component" "c/d"]))))
  (testing "an unknown task, argument or option cannot run"
    (doseq [words [["frobnicate"] ["status" "--frob"] ["status" "extra"] ["trace"] ["status" "--results"] []]]
      (is (command/cannot-run? (try (command/parse-args words) nil (catch Exception e e))) (pr-str words)))))

(deftest every-task-reports-the-same-way
  (with-dir
    (fn [d]
      (testing "help lists every task"
        (let [x (command/execute ["help"] {:root d})]
          (is (= 0 (:exit x)))
          (is (= (map :task command/tasks) (map :task (get-in x [:result :help :tasks]))))))
      (testing "an unknown task cannot run, and lists the tasks"
        (let [x (command/execute ["frobnicate"] {:root d})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:cannot-run x) "frobnicate"))
          (is (every? #(str/includes? (:cannot-run x) %) (map :task command/tasks)))))
      (testing "a task that needs the configuration cannot run without it"
        (let [x (command/execute ["status"] {:root d})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:cannot-run x) command/config-file)))))))

(deftest the-results-directory
  (is (= "a" (command/results-dir {:results "a"} {"RESULTS_DIR" "b"} {:results-dir "c"} "/r" :required)))
  (is (= "b" (command/results-dir {} {"RESULTS_DIR" "b"} {:results-dir "c"} "/r" :required)))
  (is (= "c" (command/results-dir {} {} {:results-dir "c"} "/r" :required)))
  (with-dir
    (fn [d]
      (testing "an optional results directory that is not there is no results directory"
        (is (nil? (command/results-dir {} {} {:results-dir "c"} d :optional)))
        (fs/create-dirs (fs/file d "c"))
        (is (= "c" (command/results-dir {} {} {:results-dir "c"} d :optional)))))))
