(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as command]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest how-a-finding-reads
  (is (= "a.feature:3: scenario-no-level: no level" (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "no level"))))
  (is (= "a.feature: rule: what" (command/format-finding (command/finding "a.feature" nil "rule" "what"))))
  (is (= "rule: what" (command/format-finding (command/finding "rule" "what"))))
  (is (= "a.md:2: warning: rule: what" (command/format-finding (command/warning "a.md" 2 "rule" "what"))))
  (is (= "a.md: warning: rule: what" (command/format-finding (command/warning "a.md" nil "rule" "what"))))
  (is (= "warning: rule: what" (command/format-finding (command/warning "rule" "what"))))
  (is (command/warning? (command/warning "r" "w")))
  (is (not (command/warning? (command/finding "r" "w"))))
  (is (not (command/warning? {:file "x" :rule "r" :message "m"}))))

(deftest cannot-run-is-an-ex-info
  (let [e (try (command/cannot-run! "why") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "why" (ex-message e)))
    (is (:rdm-spec.kernel.command/cannot-run (ex-data e)))))

(deftest paths-are-the-projects
  (is (= (str (fs/file "/p" "dhf/spec")) (str (command/path "/p" "dhf/spec"))))
  (is (= "/abs/x" (str (command/path {:root "/p"} "/abs/x"))))
  (is (= "dhf/spec/a.feature" (command/rel "/p" "/p/dhf/spec/a.feature")))
  (is (= "/elsewhere/x" (command/rel {:root "/p"} "/elsewhere/x")))
  (is (= "dhf/views" (command/config-value {:config {}} :views-dir)))
  (is (= "v" (command/config-value {:config {:views-dir "v"}} :views-dir))))

(deftest arguments
  (let [t (command/task-named "fence")]
    (is (= {:args ["R" "spec-3"] :opts {:context "a,b"} :events? false}
           (command/parse-args t ["R" "spec-3" "--context" "a,b"])))
    (is (:events? (command/parse-args t ["R" "spec-3" "--events"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--bogus" (command/parse-args t ["R" "spec-3" "--bogus" "x"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs more arguments" (command/parse-args t ["R"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes no argument extra" (command/parse-args t ["R" "c" "extra"])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--context needs a value" (command/parse-args t ["R" "c" "--context"]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs --verdicts" (command/parse-args (command/task-named "score") ["03"]))))

(deftest the-results-directory-a-task-reads
  (with-tmp
    (fn [root]
      (let [status (command/task-named "status")
            trace (command/task-named "trace")]
        (is (= "dhf/allure-results" (command/results-dir status {} {} {:results-dir "dhf/allure-results"} root)))
        (is (= "R1" (command/results-dir status {} {"RESULTS_DIR" "R1"} {:results-dir "dhf/allure-results"} root)))
        (is (= "other" (command/results-dir status {:results "other"} {"RESULTS_DIR" "R1"} {} root)))
        (testing "an optional reader reads the configured one only when it exists"
          (is (nil? (command/results-dir trace {} {} {:results-dir "dhf/allure-results"} root)))
          (fs/create-dirs (fs/file root "dhf/allure-results"))
          (is (= "dhf/allure-results" (command/results-dir trace {} {} {:results-dir "dhf/allure-results"} root))))
        (is (nil? (command/results-dir (command/task-named "check") {} {"RESULTS_DIR" "R1"} {} root)))))))

(deftest help-and-unknown-tasks
  (with-tmp
    (fn [root]
      (let [help (command/run-task ["help"] root {})]
        (is (= 0 (:exit help)))
        (is (= (map :task command/tasks) (map :task (:tasks (edn/read-string (:out help)))))))
      (let [r (command/run-task ["frobnicate"] root {})]
        (is (= 2 (:exit r)))
        (is (= "" (:out r)))
        (is (str/starts-with? (first (:err r)) "cannot-run: unknown task frobnicate"))
        (is (every? #(str/includes? (first (:err r)) %) (map :task command/tasks))))
      (testing "a task needing the configuration cannot run without one"
        (let [r (command/run-task ["status"] root {})]
          (is (= 2 (:exit r)))
          (is (str/includes? (first (:err r)) command/config-file)))))))

(deftest every-task-reports-the-same-way
  (let [b (bus/make-bus)]
    (bus/publish! b :spec/parser :SpecParsed {})
    (let [{:keys [out err]} (command/render {:exit 1 :result {:a 1} :events (bus/events b) :events? true
                                             :findings [(command/warning "w.md" 1 "w-rule" "a warning")
                                                        (command/finding "f.md" 2 "f-rule" "a finding")]})]
      (is (= {:a 1} (edn/read-string out)))
      (is (= ["f.md:2: f-rule: a finding" "w.md:1: warning: w-rule: a warning"
              "event: SpecParsed published-by spec/parser"]
             err))))
  (is (= {:out "" :err ["cannot-run: why"]} (command/render {:exit 2 :cannot-run "why"}))))

(deftest a-gate-stops-the-task
  (let [ran (atom [])
        req {:state (atom {}) :bus (bus/make-bus) :config {}}
        stage (fn [k findings] (fn [_] (swap! ran conj k) {:result {k true} :findings findings}))]
    ;; run-stages over stage functions resolved from vars of this namespace
    (intern 'rdm-spec.kernel.command-test 'gate-red (stage :gate [(command/finding "g" "red")]))
    (intern 'rdm-spec.kernel.command-test 'gate-warns (stage :gate [(command/warning "g" "amber")]))
    (intern 'rdm-spec.kernel.command-test 'after (stage :after [(command/finding "a" "after")]))
    (intern 'rdm-spec.kernel.command-test 'quiet (stage :quiet [(command/finding "q" "quiet")]))
    (let [out (command/run-stages {:task "demo" :stages [{:run 'rdm-spec.kernel.command-test/gate-red :gate true}
                                                          {:run 'rdm-spec.kernel.command-test/after}]}
                                  req)]
      (is (= [:gate] @ran))
      (is (= {} (:result out)))
      (is (= ["red"] (map :message (:findings out)))))
    (reset! ran [])
    (let [out (command/run-stages {:task "demo" :stages [{:run 'rdm-spec.kernel.command-test/gate-warns :gate true}
                                                          {:run 'rdm-spec.kernel.command-test/quiet :quiet true}
                                                          {:run 'rdm-spec.kernel.command-test/after}]}
                                  req)]
      (is (= [:gate :quiet :after] @ran))
      (is (= {:after true} (:result out)))
      (is (= ["amber" "after"] (map :message (:findings out)))))))
