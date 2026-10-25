(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "rdm-spec-command-test-"})))

(defn- project [cfg]
  (let [d (tmp)]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) (pr-str cfg))
    d))

(deftest findings-format
  (is (= "a.feature:3: scenario-level: no level" (command/format-finding (command/finding "a.feature" 3 "scenario-level" "no level"))))
  (is (= "a.svg: stale: x" (command/format-finding (command/finding "a.svg" nil "stale" "x"))))
  (is (= "rule: x" (command/format-finding (command/finding "rule" "x"))))
  (is (= "a.md:2: warning: r: x" (command/format-finding (command/warning "a.md" 2 "r" "x"))))
  (is (= "warning: r: x" (command/format-finding (command/warning "r" "x")))))

(deftest paths
  (is (= "/p/dhf/spec" (str (command/path "/p" "dhf/spec"))))
  (is (= "/abs" (str (command/path {:root "/p"} "/abs"))))
  (is (= "dhf/spec/a.feature" (command/rel "/p" "/p/dhf/spec/a.feature")))
  (is (= "dhf/x" (command/rel {:root "/p"} "dhf/x")))
  (is (= "/elsewhere/x" (command/rel "/p" "/elsewhere/x"))))

(deftest config
  (is (nil? (command/read-config (tmp))))
  (is (= {:a 1} (command/read-config (project {:a 1}))))
  (let [d (tmp)]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{:a")
    (is (:rdm-spec.kernel.command/cannot-run (ex-data (try (command/read-config d) (catch Exception e e))))))
  (is (= 1 (command/config-value {:config {:a 1}} :a)))
  (is (thrown? clojure.lang.ExceptionInfo (command/config-value {:config {}} :a))))

(deftest arguments
  (let [t (command/task-of "check")]
    (is (= {:args [] :opts {:sources "d" :events true}} (command/parse-args t ["--sources" "d" "--events"])))
    (is (thrown? clojure.lang.ExceptionInfo (command/parse-args t ["--nope"])))
    (is (thrown? clojure.lang.ExceptionInfo (command/parse-args t ["--sources"])))
    (is (thrown? clojure.lang.ExceptionInfo (command/parse-args t ["extra"])))))

(deftest results-directory
  (let [d (project {:results-dir "dhf/allure-results"})
        req (fn [opts] {:root d :config {:results-dir "dhf/allure-results"} :opts opts})]
    (is (= ["x" true] (command/results-dir (req {:results "x"}) :always "/env")))
    (is (= ["/env" true] (command/results-dir (req {}) :always "/env")))
    (is (= ["dhf/allure-results" false] (command/results-dir (req {}) :always nil)))
    (testing "a task that reads results only when there are some"
      (is (= [nil false] (command/results-dir (req {}) :if-exists nil)))
      (fs/create-dirs (fs/file d "dhf/allure-results"))
      (is (= ["dhf/allure-results" false] (command/results-dir (req {}) :if-exists nil))))))

(deftest run-task-reports
  (testing "help prints every task as EDN"
    (let [{:keys [exit out]} (command/run-task ["help"] (tmp) nil)]
      (is (= 0 exit))
      (is (= (mapv :task command/tasks) (mapv :task (:tasks (edn/read-string out)))))))
  (testing "an unknown task cannot run and lists the tasks"
    (let [{:keys [exit out err]} (command/run-task ["frobnicate"] (tmp) nil)]
      (is (= 2 exit))
      (is (= "" out))
      (is (str/includes? (first err) "frobnicate"))
      (is (str/includes? (str/join "\n" err) "scoreboard"))))
  (testing "a task outside a project cannot run"
    (is (= 2 (:exit (command/run-task ["stamp"] (tmp) nil))))))
