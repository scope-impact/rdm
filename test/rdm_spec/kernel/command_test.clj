(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (when (command/cannot-run? e) (ex-message e)))))

(defn- project [config]
  (let [d (str (fs/create-temp-dir {:prefix "rdm-spec-command-test-"}))]
    (when config
      (fs/create-dirs (fs/file d "dhf/harness"))
      (spit (str (fs/file d command/config-file)) (pr-str config)))
    d))

(deftest formats-findings-and-warnings
  (is (= "a.feature:3: check-level: no level"
         (command/format-finding (command/finding "a.feature" 3 "check-level" "no level"))))
  (is (= "a.edn: model-edn: unreadable" (command/format-finding (command/finding "a.edn" nil "model-edn" "unreadable"))))
  (is (= "room-history: two commits" (command/format-finding (command/finding "room-history" "two commits"))))
  (is (= "a.md:2: warning: w-rule: hm" (command/format-finding (command/warning "a.md" 2 "w-rule" "hm"))))
  (is (= "warning: w-rule: hm" (command/format-finding (command/warning "w-rule" "hm"))))
  (testing "a relayed line is printed unchanged"
    (is (= "x.feature:9: r: m" (command/format-finding {:file nil :line nil :rule nil :message "x.feature:9: r: m"})))))

(deftest paths-are-relative-to-the-root
  (is (= (str (fs/file "/p" "dhf/spec")) (str (command/path {:root "/p"} "dhf/spec"))))
  (is (= "/abs/x" (str (command/path "/p" "/abs/x"))))
  (is (= "dhf/cycles/03" (command/rel {:root "/p"} "/p/dhf/cycles/03")))
  (is (= "/elsewhere/x" (command/rel "/p" "/elsewhere/x"))))

(deftest reads-the-configuration
  (is (nil? (command/read-config (project nil))))
  (is (= {:a 1} (command/read-config (project {:a 1}))))
  (let [d (project nil)]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{:a")
    (is (str/includes? (refusal #(command/read-config d)) "not readable EDN")))
  (is (= "x" (command/config-value {:config {:k "x"}} :k)))
  (is (str/includes? (refusal #(command/config-value {:config {}} :k)) ":k")))

(deftest parses-arguments-against-the-usage
  (let [fence (command/task-named command/tasks "fence")
        oracle (command/task-named command/tasks "oracle")
        score (command/task-named command/tasks "score")]
    (is (= {:args ["R" "spec-3"] :opts {:context "a,b"}} (command/parse-args fence ["R" "spec-3" "--context" "a,b"])))
    (is (= {:args ["R" "spec-3"] :opts {:component "x/y" :events? true}}
           (command/parse-args fence ["R" "--component=x/y" "spec-3" "--events"])))
    (is (str/includes? (refusal #(command/parse-args fence ["R"])) "CUT"))
    (is (str/includes? (refusal #(command/parse-args fence ["R" "C" "D"])) "unknown argument D"))
    (is (str/includes? (refusal #(command/parse-args fence ["R" "C" "--frob"])) "unknown argument --frob"))
    (is (str/includes? (refusal #(command/parse-args fence ["R" "C" "--context"])) "takes a value"))
    (is (= {:args [] :opts {}} (command/parse-args oracle [])))
    (is (= {:args ["record"] :opts {:reason "why not"}} (command/parse-args oracle ["record" "--reason" "why not"])))
    (is (str/includes? (refusal #(command/parse-args score ["03"])) "--verdicts"))))

(deftest chooses-the-results-directory
  (let [root (project nil)
        status (command/task-named command/tasks "status")
        trace (command/task-named command/tasks "trace")
        run (command/task-named command/tasks "run")
        cfg {:results-dir "dhf/allure-results"}]
    (testing "--results, else RESULTS_DIR, else the configured one"
      (is (= "dhf/allure-results" (command/results-dir run {:opts {} :config cfg :root root :env {}})))
      (is (= "/r1" (command/results-dir run {:opts {} :config cfg :root root :env {"RESULTS_DIR" "/r1"}})))
      (is (= "other" (command/results-dir run {:opts {:results "other"} :config cfg :root root
                                               :env {"RESULTS_DIR" "/r1"}}))))
    (testing "a task reading results cannot run without them"
      (is (str/includes? (refusal #(command/results-dir status {:opts {} :config cfg :root root :env {}}))
                         "the results directory dhf/allure-results does not exist")))
    (testing "an optional reader takes the configured one only when it exists"
      (is (nil? (command/results-dir trace {:opts {} :config cfg :root root :env {}})))
      (fs/create-dirs (fs/file root "dhf/allure-results"))
      (is (= "dhf/allure-results" (command/results-dir trace {:opts {} :config cfg :root root :env {}}))))
    (testing "a task taking no results has none"
      (is (nil? (command/results-dir (command/task-named command/tasks "check")
                                     {:opts {:results "x"} :config cfg :root root :env {}}))))))

;; --- stages, gates and reporting ---------------------------------------------

(def ^:private ran (atom []))

(defn stage-a [_] (swap! ran conj :a) {:result {:a 1} :findings [(command/finding "f" 1 "rule-a" "from a")]})
(defn stage-b [_] (swap! ran conj :b) {:result {:b 2} :findings [(command/warning "rule-b" "from b")]})
(defn gate-red [req] (swap! ran conj [:gate (:args req)]) {:result {:gate :red} :findings [(command/finding "g.edn" 4 "golden" "differs")]})
(defn gate-green [req] (swap! ran conj [:gate (:args req)]) {:result {:gate :green} :findings []})
(defn refuses [_] (command/cannot-run! "nothing to read"))

(defn- table [stages]
  [{:task "t" :usage "t X" :does "test" :args ["X"] :config :none :stages stages}])

(defn- exec [stages & argv]
  (reset! ran [])
  (command/execute (into ["t"] (or (seq argv) ["x"])) {:root (project nil) :env {} :table (table stages)}))

(deftest every-stage-reports
  (let [out (exec [`stage-a `stage-b])]
    (is (= [:a :b] @ran))
    (is (= 1 (:exit out)))
    (is (= {:a 1 :b 2} (:result out)))
    (is (= ["f:1: rule-a: from a" "warning: rule-b: from b"] (:err (command/report out))))
    (is (= {:a 1 :b 2} (edn/read-string (:out (command/report out)))))))

(deftest a-gate-stops-the-task
  (testing "a gate with findings: exit 1, the gate's findings unchanged, the later stages never run"
    (let [out (exec [{:stage `gate-red :gate? true} `stage-a])]
      (is (= [[:gate []]] @ran) "the gate sees no positionals of the task, and stage-a never ran")
      (is (= 1 (:exit out)))
      (is (= ["g.edn:4: golden: differs"] (:err (command/report out))))))
  (testing "a passing gate lets every stage run, and its result is not the task's"
    (let [out (exec [{:stage `gate-green :gate? true} `stage-b])]
      (is (= [[:gate []] :b] @ran))
      (is (= 0 (:exit out)))
      (is (= {:b 2} (:result out)))))
  (testing "a gate that is not built is skipped with a warning"
    (let [out (exec [{:stage 'rdm-spec.no-such.context/oracle :gate? true} `stage-b])]
      (is (= [:b] @ran))
      (is (= 0 (:exit out)))
      (is (some #(str/includes? % "gate-not-built") (:err (command/report out)))))))

(deftest cannot-run-exits-2-and-prints-nothing-on-standard-output
  (let [out (exec [`stage-a `refuses])]
    (is (= 2 (:exit out)))
    (is (= {:exit 2 :out "" :err ["cannot-run: nothing to read"]} (command/report out))))
  (testing "a stage that is not built cannot run"
    (is (= 2 (:exit (exec ['rdm-spec.no-such.context/stage])))))
  (testing "a missing positional cannot run"
    (reset! ran [])
    (is (= 2 (:exit (command/execute ["t"] {:root (project nil) :env {} :table (table [`stage-a])}))))
    (is (empty? @ran))))

(deftest an-unknown-task-lists-every-task
  (let [r (command/report (command/execute ["frobnicate"] {:root (project nil) :env {}}))]
    (is (= 2 (:exit r)))
    (is (= "" (:out r)))
    (is (str/includes? (first (:err r)) "frobnicate"))
    (is (= (count command/tasks) (count (rest (:err r)))))))

(deftest help-lists-every-task
  (let [out (command/execute ["help"] {:root (project nil) :env {}})]
    (is (= 0 (:exit out)))
    (is (= (mapv :task command/tasks) (mapv :task (:tasks (:result out)))))))

(deftest a-task-needing-the-configuration-cannot-run-without-it
  (let [out (command/execute ["status"] {:root (project nil) :env {}})]
    (is (= 2 (:exit out)))
    (is (str/includes? (:cannot-run out) command/config-file))))

(deftest the-task-table
  (testing "fence and ratify run the oracle first, as a gate"
    (doseq [t ["fence" "ratify"]]
      (is (= {:stage command/oracle-stage :gate? true} (first (:stages (command/task-named command/tasks t)))))))
  (testing "the oracle task runs the oracle as its own stage"
    (is (= [command/oracle-stage] (:stages (command/task-named command/tasks "oracle")))))
  (testing "no other task has a gate"
    (is (= #{"fence" "ratify"}
           (set (for [t command/tasks :when (some #(and (map? %) (:gate? %)) (:stages t))] (:task t)))))))

(deftest events-are-printed-last-with-events
  (let [stage (fn [req] (command/publish! req :ctx/a :A {}) {:findings [(command/warning "w" "x")]})
        out (command/execute ["t" "x" "--events"] {:root (project nil) :env {} :table (table [stage])})]
    (is (= ["warning: w: x" "event: A published-by ctx/a"] (:err (command/report out))))))
