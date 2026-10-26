(ns rdm-spec.kernel.command-test
  "Unit tests of the command. The tasks they run are this namespace's own,
  given to execute as its task table, except help and an unknown task; the
  one fixture on disk is a temporary project removed after the test."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(deftest a-finding-has-one-line
  (is (= "a.feature:3: scenario-no-level: no level" (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "no level"))))
  (is (= "a.edn: ledger-unreadable: gone" (command/format-finding (command/finding "a.edn" "ledger-unreadable" "gone"))))
  (is (= "oracle-empty: nothing" (command/format-finding (command/finding "oracle-empty" "nothing"))))
  (is (= "a.md:2: warning: clause-unknown: x" (command/format-finding (command/warning "a.md" 2 "clause-unknown" "x"))))
  (is (= ".gitignore: warning: gitignore-kept: x" (command/format-finding (command/warning ".gitignore" "gitignore-kept" "x"))))
  (is (= "warning: hook-not-installed: x" (command/format-finding (command/warning "hook-not-installed" "x"))))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest a-task-that-cannot-run-says-so
  (let [e (try (command/cannot-run! "why") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "why" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest the-configuration-has-defaults
  (is (= "dhf/spec" (command/config-value {} :spec-dir)))
  (is (= "x" (command/config-value {:config {:spec-dir "x"}} :spec-dir)))
  (is (= "dhf/allure-results" (command/config-value {:config {}} :results-dir)))
  (is (map? (command/config-value {} :site))))

(deftest paths-are-the-projects
  (is (= (str (fs/file "/p" "a/b")) (str (command/path {:root "/p"} "a/b"))))
  (is (= "/abs/x" (str (command/path "/p" "/abs/x"))))
  (is (= "a/b.txt" (command/rel "/p" "/p/a/b.txt")))
  (is (= "a/b.txt" (command/rel {:root "/p"} (fs/file "/p/a/b.txt")))))

(deftest words-are-a-task-its-arguments-and-options
  (is (= {:task "check" :args [] :opts {:sources "D" :events true}}
         (command/parse-words ["check" "--sources" "D" "--events"])))
  (is (= {:task "trace" :args ["spec-0001"] :opts {:results "r"}}
         (command/parse-words ["trace" "spec-0001" "--results" "r"]))))

;; --- a task table of this namespace's own -----------------------------------------

(defn stage-a [_] {:result {:a 1} :findings [(command/warning "w" "a warning")]})
(defn stage-gate [_] {:result {:gate true} :findings [(command/finding "f.edn" 2 "gate-found" "found")]})
(defn stage-b [_] {:result {:b 2} :findings [(command/finding "b-found" "found")]})
(defn stage-publish [req] (command/publish! req :spec/check :SpecGated {:revision :working-tree :passed? true :findings []})
  {:result {} :findings []})
(defn stage-undeclared [req] (command/publish! req :shop/orders :OrderPlaced {:order 1}) {:result {} :findings []})
(defn subscriptions [req] [[:SpecGated (fn [p] (swap! (:state req) assoc ::gated p) nil)]])
(defn stage-read [req] {:result {:gated? (some? (::gated @(:state req)))} :findings []})

(def ^:private table
  {"report" {:usage "report" :does "reports" :stages [{:fn `stage-a} {:fn `stage-b}]}
   "gated" {:usage "gated" :does "gates" :stages [{:fn `stage-a} {:fn `stage-gate :gate? true} {:fn `stage-b}]}
   "quiet" {:usage "quiet" :does "quiet" :stages [{:fn `stage-gate :quiet? true} {:fn `stage-a}]}
   "events" {:usage "events" :does "events" :stages [{:fn `stage-publish} {:fn `stage-read}]}
   "undeclared" {:usage "undeclared" :does "x" :stages [{:fn `stage-undeclared}]}
   "unbuilt" {:usage "unbuilt" :does "x" :stages [{:fn 'rdm-spec.nowhere/stage}]}})

(defn- project []
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{}\n")
    d))

(defn- run [d words] (command/execute words {:root d :env {} :tasks table}))

(deftest every-stage-reports-and-a-gate-stops
  (let [d (project)]
    (try
      (testing "every stage reports"
        (let [x (run d ["report"])]
          (is (= 1 (:exit x)))
          (is (= {:a 1 :b 2} (:result x)))
          (is (= ["b-found: found" "warning: w: a warning"] (str/split-lines (:err x))))))
      (testing "a gate with findings stops the task"
        (let [x (run d ["gated"])]
          (is (= 1 (:exit x)))
          (is (not (contains? (:result x) :b)))
          (is (= ["f.edn:2: gate-found: found" "warning: w: a warning"] (str/split-lines (:err x))))))
      (testing "a quiet stage reports nothing"
        (let [x (run d ["quiet"])]
          (is (= 0 (:exit x)))
          (is (= "warning: w: a warning" (str/trim (:err x))))))
      (testing "events reach the subscribers and are listed with --events"
        (let [x (run d ["events" "--events"])]
          (is (= 0 (:exit x)))
          (is (= {:gated? true} (:result x)))
          (is (= "event: SpecGated published-by spec/check" (str/trim (:err x))))))
      (testing "the bus refuses an event rdm-spec does not declare: the task cannot run"
        (let [x (run d ["undeclared"])]
          (is (= 2 (:exit x)))
          (is (re-find #"^cannot-run: .*OrderPlaced" (:err x)))
          (is (= "" (:out x)))))
      (testing "a stage that is not built refuses the task"
        (let [x (run d ["unbuilt"])]
          (is (= 2 (:exit x)))
          (is (re-find #"rdm-spec\.nowhere" (:err x)))))
      (testing "an unknown option cannot run"
        (is (= 2 (:exit (run d ["report" "--frob" "x"])))))
      (finally (fs/delete-tree d)))))

(deftest help-and-unknown-tasks
  (let [x (command/execute ["help"] {:root "." :env {}})]
    (is (= 0 (:exit x)))
    (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify" "checklist"
            "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit" "observe"]
           (map :task (get-in x [:result :help :tasks])))))
  (let [x (command/execute ["frobnicate"] {:root "." :env {}})]
    (is (= 2 (:exit x)))
    (is (str/includes? (:err x) "frobnicate"))
    (is (str/includes? (:err x) "observe"))))

(deftest a-project-without-a-configuration-cannot-run
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try
      (let [x (command/execute ["status"] {:root d :env {}})]
        (is (= 2 (:exit x)))
        (is (str/includes? (:err x) command/config-file)))
      (finally (fs/delete-tree d)))))

(deftest the-results-directory-is-results-else-results-dir-else-the-configured-one
  (is (= "o" (command/results-of {:opts {:results "o"} :env {"RESULTS_DIR" "e"} :config {:results-dir "c"}})))
  (is (= "e" (command/results-of {:opts {} :env {"RESULTS_DIR" "e"} :config {:results-dir "c"}})))
  (is (= "c" (command/results-of {:opts {} :env {} :config {:results-dir "c"}})))
  (is (= "dhf/allure-results" (command/results-of {:opts {} :env {"RESULTS_DIR" ""} :config {}}))))
