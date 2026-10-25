(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as command]))

(defmacro with-dir
  "Runs body with `sym` bound to a new temporary directory, removed after."
  [[sym] & body]
  `(let [~sym (str (fs/create-temp-dir {:prefix "rdm-spec-command-test-"}))]
     (try ~@body (finally (fs/delete-tree ~sym)))))

(defn- refusal [f]
  (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

;; ---------------------------------------------------------------------------
;; Findings

(deftest findings-and-their-lines
  (is (= "a.feature:3: rule-x: wrong" (command/format-finding (command/finding "a.feature" 3 "rule-x" "wrong"))))
  (is (= "a.feature: rule-x: wrong" (command/format-finding (command/finding "a.feature" nil "rule-x" "wrong"))))
  (is (= "rule-x: wrong" (command/format-finding (command/finding nil nil "rule-x" "wrong"))))
  (is (= "a.md:2: warning: w: careful" (command/format-finding (command/warning "a.md" 2 "w" "careful"))))
  (is (= "a.md: warning: w: careful" (command/format-finding (command/warning "a.md" nil "w" "careful"))))
  (is (= "warning: w: careful" (command/format-finding (command/warning "w" "careful"))))
  (is (command/warning? (command/warning "w" "m")))
  (is (not (command/warning? (command/finding nil nil "r" "m")))))

(deftest refusals
  (is (= "no way" (refusal #(command/cannot-run! "no way"))))
  (is (command/cannot-run? (ex-info "x" {::command/cannot-run true})))
  (is (not (command/cannot-run? (ex-info "x" {})))))

;; ---------------------------------------------------------------------------
;; Paths and the configuration

(deftest paths
  (with-dir [d]
    (is (= (str (fs/file d "dhf/spec")) (str (command/path d "dhf/spec"))))
    (is (= (str (fs/file d "x")) (str (command/path {:root d} "x"))))
    (is (= "/abs/x" (str (command/path d "/abs/x"))))
    (is (= "dhf/spec/a.feature" (command/rel d (fs/file d "dhf/spec/a.feature"))))
    (is (= "dhf/spec/a.feature" (command/rel {:root d} "dhf/spec/a.feature")))
    (is (fs/absolute? (command/rel d "/elsewhere/a")) "outside the project stays absolute")))

(deftest the-configuration
  (with-dir [d]
    (is (nil? (command/read-config d)) "no configuration")
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{:spec-dir \"dhf/spec\"}")
    (is (= {:spec-dir "dhf/spec"} (command/read-config d)))
    (is (= "dhf/spec" (command/config-value {:config (command/read-config d)} :spec-dir)))
    (is (re-find #":model" (refusal #(command/config-value {:config {}} :model))))
    (spit (str (fs/file d command/config-file)) "{:a")
    (is (re-find #"not readable EDN" (refusal #(command/read-config d))))
    (spit (str (fs/file d command/config-file)) "[1 2]")
    (is (re-find #"not an EDN map" (refusal #(command/read-config d))))))

;; ---------------------------------------------------------------------------
;; The task table and the command line

(def spec-tasks
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify" "checklist"
   "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit"])

(deftest the-task-table
  (is (= spec-tasks (mapv :task command/tasks)))
  (testing "the oracle gates fence and ratify, first"
    (doseq [t ["fence" "ratify"]]
      (let [s (first (:stages (command/task-row t)))]
        (is (= 'rdm-spec.measurement.oracle/oracle (:stage s)))
        (is (:gate? s)))))
  (testing "publish gates on the views' freshness; audit on the release check"
    (is (some #(and (:gate? %) (= 'rdm-spec.architecture.views/check (:stage %))) (:stages (command/task-row "publish"))))
    (is (some #(and (:gate? %) (= 'rdm-spec.verification.release/release (:stage %))) (:stages (command/task-row "audit")))))
  (testing "the compliance stages run only under :regulated"
    (doseq [t command/tasks
            s (:stages t)
            :when (= "rdm-spec.compliance.gap" (namespace (:stage s)))]
      (is (= :regulated (:when s)) (:task t)))))

(deftest the-command-line
  (let [row #(command/task-row %)]
    (is (= {:args ["03"] :opts {}} (command/parse-args (row "checklist") ["03"])))
    (is (= {:args [] :opts {:results "other"}} (command/parse-args (row "status") ["--results" "other"])))
    (is (= {:args [] :opts {:events true}} (command/parse-args (row "check") ["--events"])))
    (is (= {:args ["ROOM" "spec-3"] :opts {:context "a,b" :component "spec/parser"}}
           (command/parse-args (row "fence") ["ROOM" "spec-3" "--context" "a,b" "--component" "spec/parser"])))
    (is (= {:args ["03"] :opts {:verdicts "v.edn"}} (command/parse-args (row "score") ["03" "--verdicts" "v.edn"])))
    (is (= {:args ["record"] :opts {:reason "why"}} (command/parse-args (row "oracle") ["record" "--reason" "why"])))
    (is (re-find #"unknown option --frob" (refusal #(command/parse-args (row "status") ["--frob"]))))
    (is (re-find #"needs a value" (refusal #(command/parse-args (row "status") ["--results"]))))
    (is (re-find #"usage: rdm-spec trace ID" (refusal #(command/parse-args (row "trace") []))))
    (is (re-find #"unexpected argument x" (refusal #(command/parse-args (row "status") ["x"]))))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn stage-a [req]
  (command/publish! req :demo/a :Demo {:n 1})
  {:result {:a 1} :findings [(command/warning "w" "a warning")]})
(defn stage-finds [_] {:result {:b 2} :findings [(command/finding "f.txt" 1 "found" "a finding")]})
(defn stage-c [req] {:result {:c (count (:seen @(:state req)))}})
(defn stage-refuses [_] (command/cannot-run! "it cannot"))
(def recorded (atom nil))
(defn record-results [req] (reset! recorded (:results req)) {})
(defn subscriptions [req] [[:Demo (fn [p] (swap! (:state req) update :seen conj p))]])

(defn- table [& rows]
  (vec (for [[task stages] (partition 2 rows)]
         {:task task :usage (str task " [--results DIR]") :does "d" :config :none :results :optional
          :stages (vec (for [s stages] (if (map? s) s {:stage s})))})))

(defn- run [argv]
  (command/execute argv {:root "." :env {}}))

(deftest stages
  (with-redefs [command/tasks
                (table "all" ['rdm-spec.kernel.command-test/stage-a 'rdm-spec.kernel.command-test/stage-finds
                              'rdm-spec.kernel.command-test/stage-c]
                       "gated" [{:stage 'rdm-spec.kernel.command-test/stage-finds :gate? true}
                                'rdm-spec.kernel.command-test/stage-c]
                       "quiet" [{:stage 'rdm-spec.kernel.command-test/stage-finds :quiet? true}
                                'rdm-spec.kernel.command-test/stage-a]
                       "refused" ['rdm-spec.kernel.command-test/stage-a 'rdm-spec.kernel.command-test/stage-refuses]
                       "unbuilt" [{:stage 'rdm-spec.no-such/stage :gate? true}]
                       "regulated" [{:stage 'rdm-spec.kernel.command-test/stage-finds :when :regulated}])]
    (testing "every stage reports; subscriptions are wired before the stages run"
      (let [r (run ["all"])]
        (is (= 1 (:exit r)))
        (is (= {:a 1 :b 2 :c 1} (:result r)))
        (is (= ["w" "found"] (map :rule (:findings r))) "in stage order")
        (is (= [:Demo] (map :event (:events r))))))
    (testing "a gate with findings stops the task"
      (let [r (run ["gated"])]
        (is (= 1 (:exit r)))
        (is (= {:b 2} (:result r)))))
    (testing "a quiet stage runs for its events only"
      (let [r (run ["quiet"])]
        (is (= 0 (:exit r)))
        (is (= {:a 1} (:result r)))))
    (testing "a refusal is exit 2 and prints nothing on standard output"
      (let [r (run ["refused"])]
        (is (= 2 (:exit r)))
        (is (= "it cannot" (:cannot-run r)))
        (is (= "" (:out (command/render r false))))
        (is (= ["cannot-run: it cannot"] (:err (command/render r false))))))
    (testing "a gate whose code is not built refuses the task"
      (let [r (run ["unbuilt"])]
        (is (= 2 (:exit r)))
        (is (re-find #"not built" (:cannot-run r)))))
    (testing "a :regulated stage runs only under :regulated"
      (is (= 0 (:exit (run ["regulated"])))))))

(deftest reporting
  (let [out {:exit 1 :result {:x 1}
             :findings [(command/warning "w" "warn") (command/finding "f" 2 "r" "m")]
             :events [{:event :SpecParsed :publisher :spec/parser}]}]
    (is (= ["f:2: r: m" "warning: w: warn"] (:err (command/render out false))) "findings, then warnings")
    (is (= ["f:2: r: m" "warning: w: warn" "event: SpecParsed published-by spec/parser"]
           (:err (command/render out true))) "then the events")
    (is (= {:x 1} (edn/read-string (:out (command/render out false)))))
    (is (= {} (edn/read-string (:out (command/render (assoc out :result nil) false)))))))

(deftest tasks-and-results
  (testing "help lists every task"
    (let [r (command/run-task ["help"] "." {})]
      (is (= 0 (:exit r)))
      (is (= spec-tasks (mapv :task (:tasks (edn/read-string (:out r))))))))
  (testing "an unknown task names itself and every task"
    (let [r (command/run-task ["frobnicate"] "." {})]
      (is (= 2 (:exit r)))
      (is (= "" (:out r)))
      (is (str/includes? (first (:err r)) "frobnicate"))
      (doseq [t spec-tasks] (is (str/includes? (str/join "\n" (:err r)) t)))))
  (testing "a task that needs the configuration cannot run without it"
    (with-dir [d]
      (let [r (command/execute ["status"] {:root d :env {}})]
        (is (= 2 (:exit r)))
        (is (re-find #"no configuration" (:cannot-run r))))))
  (testing "the results directory: --results, else RESULTS_DIR, else the configured one"
    (with-dir [d]
      (fs/create-dirs (fs/file d "dhf/harness"))
      (spit (str (fs/file d command/config-file)) (pr-str {:results-dir "dhf/allure-results"}))
      (with-redefs [command/tasks [(assoc (first (table "t" [])) :results :required :config :required
                                          :stages [{:stage 'rdm-spec.kernel.command-test/record-results}])]]
        (command/execute ["t"] {:root d :env {}})
        (is (= "dhf/allure-results" @recorded))
        (command/execute ["t"] {:root d :env {"RESULTS_DIR" "R1"}})
        (is (= "R1" @recorded))
        (command/execute ["t" "--results" "other"] {:root d :env {"RESULTS_DIR" "R1"}})
        (is (= "other" @recorded))))))


(deftest the-bus-checks-the-catalogue
  (with-dir [d]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d "dhf/harness/events.edn")) (pr-str {:Other {:published-by :x/y :carries []}}))
    (with-redefs [command/tasks [(assoc (first (table "t" ['rdm-spec.kernel.command-test/stage-a])) :config :required)]
                  command/read-config (fn [_] {:events "dhf/harness/events.edn"})]
      (let [r (command/execute ["t"] {:root d :env {}})]
        (is (= 2 (:exit r)))
        (is (str/includes? (:cannot-run r) "Demo"))
        (is (str/includes? (:cannot-run r) "dhf/harness/events.edn"))))))

(deftest format-event-is-the-bus's
  (is (= "event: A published-by b/c" (bus/format-event {:event :A :publisher :b/c}))))
