(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Findings and how they are printed

(deftest a-finding-and-a-warning
  (is (= {:file "a.feature" :line 3 :rule "r" :message "m" :severity :error}
         (command/finding "a.feature" 3 "r" "m")))
  (is (= {:file nil :line nil :rule "r" :message "m" :severity :error} (command/finding "r" "m")))
  (is (command/warning? (command/warning "f" nil "r" "m")))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest every-finding-is-one-line-in-the-readme-s-form
  (is (= "a.feature:3: scenario-no-level: no level" (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "no level"))))
  (is (= "a.feature: r: m" (command/format-finding (command/finding "a.feature" nil "r" "m"))))
  (is (= "r: m" (command/format-finding (command/finding "r" "m"))))
  (is (= "a.feature:3: warning: r: m" (command/format-finding (command/warning "a.feature" 3 "r" "m"))))
  (is (= "a.feature: warning: r: m" (command/format-finding (command/warning "a.feature" nil "r" "m"))))
  (is (= "warning: r: m" (command/format-finding (command/warning "r" "m"))))
  (testing "a message of several lines stays one line"
    (is (= "r: a b" (command/format-finding (command/finding "r" "a\nb"))))))

(deftest cannot-run-is-an-ex-info-the-command-recognises
  (let [e (try (command/cannot-run! "why") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "why" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

;; ---------------------------------------------------------------------------
;; Paths and configuration

(deftest paths-are-the-project-s
  (is (= (str (fs/file "/p" "dhf/spec")) (str (command/path "/p" "dhf/spec"))))
  (is (= (str (fs/file "/p" "x")) (str (command/path {:root "/p"} "x"))))
  (is (= "/elsewhere/x" (str (command/path "/p" "/elsewhere/x"))))
  (is (= "dhf/spec/a.feature" (command/rel "/p" "/p/dhf/spec/a.feature")))
  (is (= "dhf/spec/a.feature" (command/rel {:root "/p"} (fs/path "/p/dhf/spec/a.feature")))))

(deftest the-configuration
  (let [d (str (fs/create-temp-dir))]
    (is (nil? (command/read-config d)) "missing: nil")
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{:spec-dir \"s\"}")
    (is (= {:spec-dir "s"} (command/read-config d)))
    (is (= "s" (command/config-value {:config {:spec-dir "s"}} :spec-dir)))
    (is (command/cannot-run? (try (command/config-value {:config {}} :spec-dir) (catch Exception e e))))
    (spit (str (fs/file d command/config-file)) "{:unclosed")
    (is (command/cannot-run? (try (command/read-config d) (catch Exception e e))) "unreadable: cannot run")))

;; ---------------------------------------------------------------------------
;; The task table

(deftest the-task-table-holds-every-task-of-the-spec-once
  (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify"
          "checklist" "status" "trace" "release" "score" "scoreboard" "oracle" "publish"]
         (map :task command/tasks)))
  (is (every? #(and (string? (:usage %)) (string? (:does %))) command/tasks))
  (testing "the oracle is the gate of fence and ratify, the views' freshness the gate of publish"
    (doseq [t ["fence" "ratify"]]
      (is (= 'rdm-spec.measurement.oracle/oracle (:stage (first (:stages (command/task t))))))
      (is (:gate? (first (:stages (command/task t))))))
    (is (= 'rdm-spec.architecture.views/check (:stage (first (:stages (command/task "publish"))))))
    (is (:gate? (first (:stages (command/task "publish")))))
    (is (not-any? :gate? (:stages (command/task "check"))))))

(deftest arguments-are-read-against-the-task-s-usage
  (is (= {:args ["ROOM" "spec-3"] :opts {:context "a,b" :component "spec/parser"}}
         (command/parse-args (command/task "fence")
                             ["ROOM" "spec-3" "--context" "a" "--component" "spec/parser" "--context" "b"])))
  (is (= {:args [] :opts {:events true :sources "d"}}
         (command/parse-args (command/task "check") ["--events" "--sources" "d"])))
  (doseq [bad [["--frob"] ["extra"] ["--sources"]]]
    (is (command/cannot-run? (try (command/parse-args (command/task "check") bad) (catch Exception e e)))
        (pr-str bad)))
  (is (command/cannot-run? (try (command/parse-args (command/task "trace") []) (catch Exception e e)))
      "too few arguments"))

(deftest the-results-directory-is-the-flag-else-the-environment-else-the-configured
  (let [d (str (fs/create-temp-dir))
        cfg {:results-dir "dhf/allure-results"}
        status (command/task "status")
        trace (command/task "trace")]
    (is (= "dhf/allure-results" (command/results-dir status {} {} cfg d)))
    (is (= "/r1" (command/results-dir status {} {"RESULTS_DIR" "/r1"} cfg d)))
    (is (= "other" (command/results-dir status {:results "other"} {"RESULTS_DIR" "/r1"} cfg d)))
    (is (= "dhf/allure-results" (command/results-dir status {} {"RESULTS_DIR" ""} cfg d)) "empty is unset")
    (testing "a task that reads results only when there are some takes the configured one only when it exists"
      (is (nil? (command/results-dir trace {} {} cfg d)))
      (fs/create-dirs (fs/file d "dhf/allure-results"))
      (is (= "dhf/allure-results" (command/results-dir trace {} {} cfg d))))))

;; ---------------------------------------------------------------------------
;; Running tasks

(defn- project []
  (let [d (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{:events \"events.edn\"}")
    (spit (str (fs/file d "events.edn")) "{:Seen {:published-by :kernel/command-test :carries [:n]}}")
    d))

(def ^:private ran (atom []))

(defn stage-a [_] (swap! ran conj :a) {:result {:a 1} :findings [(command/finding "a.f" 1 "rule-a" "from a")]})
(defn stage-b [req]
  (swap! ran conj :b)
  (command/publish! req :kernel/command-test :Seen {:n 1})
  {:result {:b 2} :findings [(command/warning "rule-b" "from b")]})
(defn gate-red [_] (swap! ran conj :gate) {:result {} :findings [(command/finding "g.f" nil "gate-rule" "red")]})
(defn gate-warns [_] (swap! ran conj :gate) {:findings [(command/warning "gate-rule" "only a warning")]})
(defn stage-refuses [_] (command/cannot-run! "the stage cannot run"))

(defn- table [& stages]
  [{:task "t" :usage "t [--events]" :does "a test task" :args [0 0] :options {"--events" {:key :events :flag? true}}
    :config :required :stages (vec stages)}])

(defn- run-with [tbl argv]
  (reset! ran [])
  (command/run-task argv (project) {} {:tasks tbl}))

(deftest a-task-with-no-gate-reports-every-stage
  (let [r (run-with (table {:stage `stage-a} {:stage `stage-b}) ["t"])]
    (is (= [:a :b] @ran))
    (is (= 1 (:exit r)))
    (is (= {:a 1 :b 2} (edn/read-string (:out r))))
    (is (= ["a.f:1: rule-a: from a" "warning: rule-b: from b"] (:err r)) "findings first, then warnings")))

(deftest a-gate-with-findings-stops-the-task
  (let [r (run-with (table {:stage `gate-red :gate? true} {:stage `stage-a}) ["t"])]
    (is (= [:gate] @ran))
    (is (= 1 (:exit r)))
    (is (= {} (edn/read-string (:out r))))
    (is (= ["g.f: gate-rule: red"] (:err r))))
  (testing "a gate's warnings never stop a task"
    (let [r (run-with (table {:stage `gate-warns :gate? true} {:stage `stage-a}) ["t"])]
      (is (= [:gate :a] @ran))
      (is (= ["a.f:1: rule-a: from a" "warning: gate-rule: only a warning"] (:err r)))))
  (testing "a gate that is not built refuses the task"
    (let [r (run-with (table {:stage 'rdm-spec.not-built/gate :gate? true} {:stage `stage-a}) ["t"])]
      (is (= [] @ran))
      (is (= 2 (:exit r)))
      (is (= "" (:out r)))
      (is (str/starts-with? (first (:err r)) "cannot-run: ")))))

(deftest events-are-printed-last-with-events
  (let [r (run-with (table {:stage `stage-b} {:stage `stage-a}) ["t" "--events"])]
    (is (= "event: Seen published-by kernel/command-test" (last (:err r))))
    (is (= 0 (:exit (run-with (table {:stage `stage-b}) ["t"]))))))

(deftest a-task-that-cannot-run-exits-2-and-prints-no-result
  (let [r (run-with (table {:stage `stage-a} {:stage `stage-refuses}) ["t"])]
    (is (= 2 (:exit r)))
    (is (= "" (:out r)))
    (is (= ["cannot-run: the stage cannot run"] (:err r))))
  (testing "a missing configuration"
    (let [r (command/run-task ["t"] (str (fs/create-temp-dir)) {} {:tasks (table {:stage `stage-a})})]
      (is (= 2 (:exit r)))
      (is (re-find #"dhf/harness/config\.edn" (first (:err r)))))))

(deftest an-unknown-task-is-named-with-every-task
  (let [r (command/run-task ["frobnicate"] (str (fs/create-temp-dir)) {})]
    (is (= 2 (:exit r)))
    (is (= "" (:out r)))
    (is (str/includes? (first (:err r)) "unknown task frobnicate"))
    (doseq [t (map :task command/tasks)]
      (is (re-find (re-pattern (str "\\b" t "\\b")) (first (:err r))) t))))

(deftest help-lists-every-task-as-edn
  (let [r (command/run-task ["help"] (str (fs/create-temp-dir)) {})
        listed (:tasks (edn/read-string (:out r)))]
    (is (= 0 (:exit r)))
    (is (= (map :task command/tasks) (map :task listed)))
    (is (= "fence ROOM CUT [--context C,...] [--component C/N,...]"
           (:usage (first (filter #(= "fence" (:task %)) listed)))))))

(def ^:private seen (atom []))

(defn subscriptions
  "This namespace's subscriptions, as a context's: wired by the command
  before the first stage runs."
  [_req]
  [[:Seen #(swap! seen conj %)]])

(deftest subscriptions-are-wired-before-the-first-stage
  (reset! seen [])
  (let [r (run-with (table {:stage `stage-b}) ["t"])]
    (is (= 0 (:exit r)))
    (is (= [{:n 1}] @seen))
    (is (= [[:Seen :kernel/command-test]]
           (map (juxt first second) (command/stage-subscriptions {} {:stage `stage-b})))
        "the subscriber is the stage's component")))
