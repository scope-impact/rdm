(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- with-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest findings-and-warnings
  (is (= {:file "a.feature" :line 3 :rule "r" :message "m" :severity :error} (command/finding "a.feature" 3 "r" "m")))
  (is (= {:file "a.edn" :line nil :rule "r" :message "m" :severity :error} (command/finding "a.edn" "r" "m")))
  (is (= {:file nil :line nil :rule "r" :message "m" :severity :error} (command/finding "r" "m")))
  (is (command/warning? (command/warning "a" 1 "r" "m")))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest one-line-per-finding
  (is (= "a.feature:3: r: m" (command/format-finding (command/finding "a.feature" 3 "r" "m"))))
  (is (= "a.edn: r: m" (command/format-finding (command/finding "a.edn" "r" "m"))))
  (is (= "r: m" (command/format-finding (command/finding "r" "m"))))
  (is (= "a.feature:3: warning: r: m" (command/format-finding (command/warning "a.feature" 3 "r" "m"))))
  (is (= "a.edn: warning: r: m" (command/format-finding (command/warning "a.edn" "r" "m"))))
  (is (= "warning: r: m" (command/format-finding (command/warning "r" "m"))))
  (testing "a message of several lines stays one line"
    (is (= "r: a b" (command/format-finding (command/finding "r" "a\nb"))))))

(deftest cannot-run
  (let [e (try (command/cannot-run! "why") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "why" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest paths-and-configuration
  (with-dir
    (fn [d]
      (is (= (str (fs/file d "x/y")) (str (command/path {:root d} "x/y"))))
      (is (= (str (fs/file d "x/y")) (str (command/path d "x/y"))))
      (is (= "/abs/p" (str (command/path d "/abs/p"))))
      (is (= "x/y" (command/rel {:root d} (fs/file d "x/y"))))
      (is (= "dhf/spec" (command/config-value {:config {}} :spec-dir)))
      (is (= "s" (command/config-value {:config {:spec-dir "s"}} :spec-dir)))
      (is (= "s" (command/config-value {:spec-dir "s"} :spec-dir)) "a configuration itself")
      (is (nil? (command/read-config d)))
      (spit (str (fs/file (doto (fs/file d "dhf/harness") fs/create-dirs) "config.edn")) "{:id-prefix \"x\"}")
      (is (= {:id-prefix "x"} (command/read-config d))))))

(deftest arguments
  (is (= {:args ["ROOM" "spec-3"] :opts {:context "a,b" :component "c/d"}}
         (command/parse-args ["ROOM" "spec-3" "--context" "a,b" "--component=c/d"] #{:context :component})))
  (is (= {:args [] :opts {:events true}} (command/parse-args ["--events"] #{:events})))
  (is (= "unknown" (try (command/parse-args ["--nope"] #{:events}) (catch Exception e (when (command/cannot-run? e) "unknown"))))))

(deftest the-results-directory
  (with-dir
    (fn [d]
      (let [req {:root d :config {:results-dir "dhf/allure-results"}}]
        (testing "--results, else RESULTS_DIR, else the configured one"
          (is (= "other" (command/results-dir req :required {:results "other"} {"RESULTS_DIR" "/r1"})))
          (is (= "/r1" (command/results-dir req :required {} {"RESULTS_DIR" "/r1"})))
          (is (= "dhf/allure-results" (command/results-dir req :required {} {}))))
        (testing "an optional one is the configured one only when it exists"
          (is (nil? (command/results-dir req :optional {} {})))
          (fs/create-dirs (fs/file d "dhf/allure-results"))
          (is (= "dhf/allure-results" (command/results-dir req :optional {} {}))))))))

(deftest the-task-table
  (let [names (map :task command/tasks)]
    (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify" "checklist"
            "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit"]
           names))
    (testing "the oracle is the gate before fence and ratify"
      (doseq [t ["fence" "ratify"]]
        (let [st (first (:stages (command/task t)))]
          (is (= 'rdm-spec.measurement.oracle/oracle (:fn st)))
          (is (:gate? st)))))
    (testing "publish's gate is the views' freshness"
      (is (= 'rdm-spec.architecture.views/check (:fn (first (filter :gate? (:stages (command/task "publish")))))))))
  (is (nil? (command/task "frobnicate"))))

(deftest executing
  (with-dir
    (fn [d]
      (testing "an unknown task cannot run and lists the tasks"
        (let [x (command/execute ["frobnicate"] {:root d :env {}})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:cannot-run x) "frobnicate"))
          (is (every? #(str/includes? (:cannot-run x) %) (map :task command/tasks)))
          (is (= "" (:out x)))))
      (testing "help needs no configuration"
        (let [x (command/execute ["help"] {:root d :env {}})]
          (is (= 0 (:exit x)))
          (is (= (map :task command/tasks) (map :task (get-in (edn/read-string (:out x)) [:help :tasks]))))))
      (testing "a task that needs a configuration cannot run without one"
        (let [x (command/execute ["status"] {:root d :env {}})]
          (is (= 2 (:exit x)))
          (is (str/includes? (:err x) "cannot-run: "))
          (is (str/includes? (:err x) "dhf/harness/config.edn"))))
      (testing "a missing argument cannot run"
        (is (= 2 (:exit (command/execute ["scoreboard" "01"] {:root d :env {}}))))))))

(deftest stages
  (testing "a gate with findings stops the task; a quiet stage reports nothing"
    (let [ran (atom [])
          stage (fn [k out] (fn [_] (swap! ran conj k) out))
          x (command/run-stages {:root "." :config {} :state (atom {})}
                                [{:fn (stage :quiet {:result {:q 1} :findings [(command/finding "q" "quiet")]}) :quiet? true}
                                 {:fn (stage :report {:result {:a 1} :findings [(command/warning "w" "a warning")]})}
                                 {:fn (stage :gate {:result {:b 2} :findings [(command/finding "g" "red")]}) :gate? true}
                                 {:fn (stage :after {:result {:c 3}})}])]
      (is (= [:quiet :report :gate] @ran))
      (is (= {:a 1 :b 2} (:result x)))
      (is (= ["w" "g"] (map :rule (:findings x))))))
  (testing "a stage whose condition does not hold is not run"
    (let [x (command/run-stages {:root "." :config {} :state (atom {})}
                                [{:fn (fn [_] {:result {:a 1}}) :when (constantly false)}])]
      (is (= {} (:result x))))))
