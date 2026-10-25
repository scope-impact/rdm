(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "rdm-spec-command-test-"})))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (when (command/cannot-run? e) (ex-message e)))))

(deftest findings-are-formatted-as-the-readme-says
  (is (= "a.feature:3: check-level: no level"
         (command/format-finding (command/finding "a.feature" 3 "check-level" "no level"))))
  (is (= "a.feature: wiki-no-index: no index"
         (command/format-finding (command/finding "a.feature" nil "wiki-no-index" "no index"))))
  (is (= "stamp-refused: nothing was stamped"
         (command/format-finding (command/finding "stamp-refused" "nothing was stamped"))))
  (is (= "n.edn:4: warning: citation-unchecked: not checked"
         (command/format-finding (command/warning "n.edn" 4 "citation-unchecked" "not checked"))))
  (is (= "warning: scoreboard: moved"
         (command/format-finding (command/warning "scoreboard" "moved"))))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest arguments-are-read-as-the-usage-says
  (is (= {:args ["room" "spec-3"] :opts {:context "a,b" :component "spec/parser"}}
         (command/parse-args "fence ROOM CUT [--context C,...] [--component C/N,...]"
                             ["room" "spec-3" "--context" "a" "--component" "spec/parser" "--context=b"])))
  (is (= {:args ["record"] :opts {:reason "why"}}
         (command/parse-args "oracle [record --reason R]" ["record" "--reason" "why"])))
  (is (= {:args [] :opts {:events true :sources "d"}}
         (command/parse-args "check [--sources DIR] [--events]" ["--events" "--sources" "d"])))
  (testing "every task takes --events"
    (is (= {:args [] :opts {:events true}} (command/parse-args "status [--results DIR]" ["--events"]))))
  (testing "what the usage does not allow cannot run, naming it and the usage"
    (is (re-find #"missing CUT.*usage: rdm-spec fence" (refusal #(command/parse-args "fence ROOM CUT" ["room"]))))
    (is (re-find #"unknown argument extra" (refusal #(command/parse-args "stamp" ["extra"]))))
    (is (re-find #"unknown option --bogus" (refusal #(command/parse-args "check [--events]" ["--bogus"]))))
    (is (re-find #"missing --verdicts" (refusal #(command/parse-args "score CYCLE --verdicts FILE" ["03"]))))
    (is (re-find #"--results needs a value" (refusal #(command/parse-args "status [--results DIR]" ["--results"]))))
    (is (re-find #"given twice" (refusal #(command/parse-args "status [--results DIR]" ["--results" "a" "--results" "b"]))))
    (is (re-find #"unknown argument frob" (refusal #(command/parse-args "oracle [record --reason R]" ["frob"]))))))

(deftest every-usage-in-the-table-reads
  (doseq [{:keys [usage]} command/tasks]
    (is (map? (command/arg-spec usage)) usage)))

(deftest paths-are-the-projects
  (let [d (tmp)]
    (is (= (str (fs/file d "a/b")) (str (command/path d "a/b"))))
    (is (= (str (fs/file d "a/b")) (str (command/path {:root d} "a/b"))))
    (is (= "/x/y" (str (command/path d "/x/y"))))
    (is (= "a/b.txt" (command/rel d (fs/file d "a/b.txt"))))
    (is (= "a/b.txt" (command/rel {:root d} (str d "/a/b.txt"))))))

(deftest the-configuration
  (let [d (tmp)]
    (is (nil? (command/read-config d)))
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) "{:spec-dir \"dhf/spec\"}")
    (is (= {:spec-dir "dhf/spec"} (command/read-config d)))
    (is (= "dhf/spec" (command/config-value {:config (command/read-config d)} :spec-dir)))
    (is (re-find #"sets no :ledger" (refusal #(command/config-value {:config {:spec-dir "x"}} :ledger))))
    (is (re-find #"no configuration" (refusal #(command/config-value {:config nil} :ledger))))
    (spit (str (fs/file d command/config-file)) "[1 2]")
    (is (re-find #"not a map" (refusal #(command/read-config d))))
    (spit (str (fs/file d command/config-file)) "{:a")
    (is (re-find #"not readable EDN" (refusal #(command/read-config d))))))

(deftest help-and-unknown-tasks
  (let [d (tmp)
        r (command/run-task ["help"] d {})]
    (is (= 0 (:exit r)))
    (is (= (map :task command/tasks) (map :task (:tasks (edn/read-string (:out r))))))
    (is (= [] (:err r))))
  (let [r (command/run-task ["frobnicate"] (tmp) {})]
    (is (= 2 (:exit r)))
    (is (= "" (:out r)))
    (is (str/starts-with? (first (:err r)) "cannot-run: unknown task frobnicate"))
    (doseq [t (map :task command/tasks)] (is (str/includes? (first (:err r)) t))))
  (is (= 2 (:exit (command/run-task [] (tmp) {})))))

;; ---------------------------------------------------------------------------
;; Gates and stages, with stages of this namespace

(def calls (atom []))

(defn red-gate [_req] (swap! calls conj :gate) {:result {:gate :red} :findings [(command/finding "g.edn" 1 "golden-differs" "red")]})
(defn warning-gate [_req] (swap! calls conj :gate) {:result {} :findings [(command/warning "gate-note" "only a warning")]})
(defn stage-a [_req] (swap! calls conj :a) {:result {:a 1} :findings [(command/finding "f" 2 "rule-a" "a")]})
(defn stage-b [req] (swap! calls conj :b) {:result {:b (:results req)} :findings [(command/warning "rule-b" "b")]})
(defn refusing [_req] (command/cannot-run! "nothing to read"))
(defn subscriptions [_req] [[:Nothing (fn [_] nil)]])

(defn- with-task [row f]
  (reset! calls [])
  (with-redefs [command/tasks [(merge {:task "t" :usage "t [--results DIR]" :does "a test task"} row)]]
    (f)))

(deftest a-gate-stops-the-task
  (with-task {:gates [`red-gate] :stages [`stage-a]}
    #(let [r (command/run-task ["t"] (tmp) {})]
       (is (= 1 (:exit r)))
       (is (= [:gate] @calls))
       (is (= ["g.edn:1: golden-differs: red"] (:err r))))))

(deftest a-gate-with-only-warnings-lets-the-task-run
  (with-task {:gates [`warning-gate] :stages [`stage-b]}
    #(let [r (command/run-task ["t"] (tmp) {})]
       (is (= 0 (:exit r)))
       (is (= [:gate :b] @calls)))))

(deftest every-stage-reports
  (with-task {:stages [`stage-a `stage-b]}
    #(let [r (command/run-task ["t"] (tmp) {})]
       (is (= 1 (:exit r)))
       (is (= {:a 1 :b nil} (edn/read-string (:out r))))
       (testing "errors first, then warnings"
         (is (= ["f:2: rule-a: a" "warning: rule-b: b"] (:err r)))))))

(deftest a-gate-or-stage-not-built-refuses-the-task
  (with-task {:gates ['rdm-spec.nowhere/gate] :stages [`stage-a]}
    #(let [r (command/run-task ["t"] (tmp) {})]
       (is (= 2 (:exit r)))
       (is (= [] @calls))
       (is (re-find #"cannot-run: the gate rdm-spec.nowhere/gate is not built" (first (:err r))))))
  (with-task {:stages ['rdm-spec.nowhere/stage]}
    #(is (= 2 (:exit (command/run-task ["t"] (tmp) {}))))))

(deftest a-refusal-prints-nothing-on-standard-output
  (with-task {:stages [`stage-a `refusing]}
    #(let [r (command/run-task ["t"] (tmp) {})]
       (is (= 2 (:exit r)))
       (is (= "" (:out r)))
       (is (= ["cannot-run: nothing to read"] (:err r))))))

(deftest the-results-directory
  (let [d (tmp)]
    (fs/create-dirs (fs/file d "dhf/harness"))
    (spit (str (fs/file d command/config-file)) (pr-str {:results-dir "dhf/allure-results"}))
    (testing "--results, else RESULTS_DIR, else the configured one"
      (with-task {:results :always :stages [`stage-b]}
        #(do (is (= {:b "dhf/allure-results"} (edn/read-string (:out (command/run-task ["t"] d {})))))
             (is (= {:b "/r1"} (edn/read-string (:out (command/run-task ["t"] d {"RESULTS_DIR" "/r1"})))))
             (is (= {:b "other"} (edn/read-string (:out (command/run-task ["t" "--results" "other"] d {"RESULTS_DIR" "/r1"}))))))))
    (testing "a task reading results only when there are any takes the configured one only when it exists"
      (with-task {:results :when-exists :stages [`stage-b]}
        #(do (is (= {:b nil} (edn/read-string (:out (command/run-task ["t"] d {})))))
             (fs/create-dirs (fs/file d "dhf/allure-results"))
             (is (= {:b "dhf/allure-results"} (edn/read-string (:out (command/run-task ["t"] d {}))))))))))

(deftest events-are-printed-last-with-events
  (with-task {:stages [`stage-a]}
    #(let [r (command/run-task ["t" "--events"] (tmp) {})]
       (is (= ["f:2: rule-a: a"] (:err r))))))

(deftest the-table
  (testing "fence and ratify are gated by the oracle, publish by the views' freshness"
    (is (= ['rdm-spec.measurement.oracle/oracle] (:gates (command/task-row "fence"))))
    (is (= ['rdm-spec.measurement.oracle/oracle] (:gates (command/task-row "ratify"))))
    (is (= ['rdm-spec.architecture.views/check] (:gates (command/task-row "publish")))))
  (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify" "checklist"
          "status" "trace" "release" "score" "scoreboard" "oracle" "publish"]
         (map :task command/tasks))))
