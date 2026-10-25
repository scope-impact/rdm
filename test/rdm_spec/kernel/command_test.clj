(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "command-test-"})))

(defn- refusal [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         {:message (ex-message e) :cannot-run? (::command/cannot-run (ex-data e))})))

(deftest findings-and-their-lines
  (is (= {:file "a.feature" :line 3 :rule "check-level" :message "no level" :severity :error}
         (command/finding "a.feature" 3 "check-level" "no level")))
  (is (command/warning? (command/warning "x" "y")))
  (is (not (command/warning? (command/finding "x" "y"))))
  (is (= "a.feature:3: check-level: no level" (command/format-finding (command/finding "a.feature" 3 "check-level" "no level"))))
  (is (= "a.edn: rule: what" (command/format-finding (command/finding "a.edn" nil "rule" "what"))))
  (is (= "rule: what" (command/format-finding (command/finding "rule" "what"))))
  (is (= "a.edn:2: warning: rule: what" (command/format-finding (command/warning "a.edn" 2 "rule" "what"))))
  (is (= "warning: rule: what" (command/format-finding (command/warning "rule" "what"))))
  (testing "a finding is one line"
    (is (= "rule: one two" (command/format-finding (command/finding "rule" "one\n  two"))))))

(deftest cannot-run-is-marked
  (is (= {:message "no spec" :cannot-run? true} (refusal #(command/cannot-run! "no spec")))))

(deftest paths-against-the-root
  (let [root (tmp)]
    (is (= (str (fs/file root "dhf/spec")) (str (command/path root "dhf/spec"))))
    (is (= (str (fs/file root "dhf/spec")) (str (command/path {:root root} "dhf/spec"))))
    (is (= "/elsewhere/x" (str (command/path root "/elsewhere/x"))))
    (is (= "dhf/spec/a.feature" (command/rel root (fs/file root "dhf/spec/a.feature"))))
    (is (= "dhf/spec/a.feature" (command/rel {:root root} (str root "/dhf/./spec/a.feature"))))))

(deftest the-configuration
  (let [root (tmp)]
    (is (nil? (command/read-config root)))
    (fs/create-dirs (fs/file root "dhf/harness"))
    (spit (str (fs/file root command/config-file)) "{:spec-dir \"dhf/spec\"}")
    (is (= {:spec-dir "dhf/spec"} (command/read-config root)))
    (is (= "dhf/spec" (command/config-value {:config (command/read-config root)} :spec-dir)))
    (is (:cannot-run? (refusal #(command/config-value {:config {}} :model))))
    (spit (str (fs/file root command/config-file)) "[1 2]")
    (is (:cannot-run? (refusal #(command/read-config root))))
    (spit (str (fs/file root command/config-file)) "{:a")
    (is (:cannot-run? (refusal #(command/read-config root))))))

(deftest a-usage-is-the-grammar
  (is (= {:positional ["PATH"] :options {"--id-prefix" {:value? true :required? false}}}
         (command/grammar "init PATH [--id-prefix P]")))
  (is (= {:positional [] :options {"--sources" {:value? true :required? false}
                                   "--events" {:value? false :required? false}}}
         (command/grammar "check [--sources DIR] [--events]")))
  (is (= {:positional ["CYCLE"] :options {"--verdicts" {:value? true :required? true}}}
         (command/grammar "score CYCLE --verdicts FILE")))
  (is (= ["ROOM" "CUT"] (:positional (command/grammar (:usage (command/task-of "fence"))))))
  (is (= #{"--context" "--component"} (set (keys (:options (command/grammar (:usage (command/task-of "fence"))))))))
  (is (= ["CYCLE" "CYCLE"] (:positional (command/grammar "scoreboard CYCLE CYCLE")))))

(deftest arguments-read-by-the-usage
  (let [fence (command/task-of "fence")]
    (is (= {:args ["room" "spec-3"] :opts {:context "spec,verification" :component "spec/parser"}}
           (command/parse-args fence ["room" "spec-3" "--context" "spec" "--context" "verification"
                                      "--component=spec/parser"])))
    (is (:cannot-run? (refusal #(command/parse-args fence ["room"]))))
    (is (:cannot-run? (refusal #(command/parse-args fence ["room" "spec-3" "extra"]))))
    (is (:cannot-run? (refusal #(command/parse-args fence ["room" "spec-3" "--bogus"]))))
    (is (:cannot-run? (refusal #(command/parse-args fence ["room" "spec-3" "--context"])))))
  (testing "every task takes --events"
    (is (= {:args [] :opts {:events true}} (command/parse-args (command/task-of "status") ["--events"]))))
  (testing "a required option"
    (is (str/includes? (:message (refusal #(command/parse-args (command/task-of "score") ["03"]))) "--verdicts"))))

(deftest every-task-is-listed-once
  (is (= (count command/task-names) (count (set command/task-names))))
  (is (every? #(str/starts-with? (:usage %) (:task %)) command/tasks))
  (let [r (command/run-task ["help"] (tmp) {})]
    (is (= 0 (:exit r)))
    (is (= command/task-names (map :task (:tasks (edn/read-string (:out r))))))))

(deftest how-a-task-reports
  (testing "an unknown task cannot run, names itself and lists the tasks"
    (let [r (command/run-task ["frobnicate"] (tmp) {})]
      (is (= 2 (:exit r)))
      (is (= "" (:out r)))
      (is (= 1 (count (:err r))))
      (is (str/starts-with? (first (:err r)) "cannot-run: unknown task frobnicate"))
      (is (every? #(str/includes? (first (:err r)) %) command/task-names))))
  (testing "no task"
    (is (= 2 (:exit (command/run-task [] (tmp) {})))))
  (testing "a task needing the configuration cannot run without one"
    (let [r (command/run-task ["status"] (tmp) {})]
      (is (= 2 (:exit r)))
      (is (str/includes? (first (:err r)) command/config-file))))
  (testing "errors, then warnings, then events"
    (let [r (command/report {:status :found :result {:a 1}
                             :findings [(command/warning "w" "first") (command/finding "f" "second")]
                             :events [{:event :SpecParsed :publisher :spec/parser}] :events? true})]
      (is (= 1 (:exit r)))
      (is (= {:a 1} (edn/read-string (:out r))))
      (is (= ["f: second" "warning: w: first" "event: SpecParsed published-by spec/parser"] (:err r)))))
  (testing "a task that cannot run prints nothing on standard output"
    (is (= {:exit 2 :out "" :err ["cannot-run: why"]}
           (command/report {:status :cannot-run :why "why" :findings []})))))

(defn- project []
  (let [root (tmp)]
    (fs/create-dirs (fs/file root "dhf/harness"))
    (spit (str (fs/file root command/config-file)) (pr-str {:results-dir "dhf/allure-results"}))
    root))

(deftest the-results-directory
  (let [root (project)
        results (fn [task argv env] (:results (command/request (command/task-of task) argv root env)))]
    (testing "--results, else RESULTS_DIR, else the configured one"
      (is (= "dhf/allure-results" (results "status" [] {})))
      (is (= "/r1" (results "status" [] {"RESULTS_DIR" "/r1"})))
      (is (= "other" (results "status" ["--results" "other"] {"RESULTS_DIR" "/r1"}))))
    (testing "where results are optional, a configured directory that does not exist is no results"
      (is (nil? (results "trace" ["spec-0001"] {})))
      (fs/create-dirs (fs/file root "dhf/allure-results"))
      (is (= "dhf/allure-results" (results "trace" ["spec-0001"] {}))))
    (testing "a task that reads no results has none"
      (is (nil? (results "stamp" [] {"RESULTS_DIR" "/r1"}))))))

(deftest stages-share-one-request
  (let [root (project)
        seen (atom [])]
    (with-redefs [command/tasks [{:task "demo" :usage "demo" :does "a demo" :config :required
                                  :stages [`demo-one `demo-two]}]]
      (intern 'rdm-spec.kernel.command-test 'demo-one
              (fn [req] (swap! seen conj (:task req)) {:result {:one 1} :findings [(command/warning "w" "x")]}))
      (intern 'rdm-spec.kernel.command-test 'demo-two
              (fn [req] (swap! seen conj (:root req)) {:result {:two 2} :findings []}))
      (let [r (command/run-task ["demo"] root {})]
        (is (= 0 (:exit r)) (pr-str r))
        (is (= {:one 1 :two 2} (edn/read-string (:out r))))
        (is (= ["demo" root] @seen))))))
