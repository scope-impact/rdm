(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest findings-print-as-the-readme-fixes-them
  (is (= "a.feature:3: scenario-no-level: why" (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "why"))))
  (is (= "a.feature: rule: why" (command/format-finding (command/finding "a.feature" "rule" "why"))))
  (is (= "rule: why" (command/format-finding (command/finding "rule" "why"))))
  (is (= "a.md:2: warning: rule: why" (command/format-finding (command/warning "a.md" 2 "rule" "why"))))
  (is (= "warning: rule: why" (command/format-finding (command/warning "rule" "why"))))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest refusals-are-marked
  (let [e (try (command/cannot-run! "no way") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "no way" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest paths-are-the-project's
  (is (= "dhf/spec" (command/rel "/p/q" "/p/q/dhf/spec")))
  (is (= "dhf/spec" (command/rel {:root "/p/q"} "dhf/spec")))
  (is (= "/elsewhere/x" (command/rel "/p/q" "/elsewhere/x")))
  (is (= "/p/q/a/b" (str (command/path "/p/q" "a/b"))))
  (is (= "/abs" (str (command/path {:root "/p/q"} "/abs")))))

(deftest the-command-line
  (let [task (fn [n] (first (filter #(= n (:task %)) command/tasks)))]
    (is (= {:args ["ROOM" "spec-3"] :opts {:context "spec"} :events? false}
           (command/parse-args (task "fence") ["ROOM" "spec-3" "--context" "spec"])))
    (is (= {:args [] :opts {:results "r"} :events? true}
           (command/parse-args (task "status") ["--events" "--results=r"])))
    (is (= {:args ["record"] :opts {:reason "why not"} :events? false}
           (command/parse-args (task "oracle") ["record" "--reason" "why not"])))
    (doseq [[t words] [["status" ["--sources" "x"]] ["status" ["extra"]] ["trace" []]
                       ["score" ["03"]] ["init" ["p" "--id-prefix"]] ["oracle" ["frob"]]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rdm-spec" (command/parse-args (task t) words))
          (pr-str t words)))))

(deftest every-task-is-listed-and-unknown-ones-refused
  (with-tmp
    (fn [dir]
      (let [help (command/execute ["help"] {:root dir})]
        (is (= 0 (:exit help)))
        (is (= (map :task command/tasks) (map :task (get-in help [:result :tasks])))))
      (let [r (command/run-task ["frobnicate"] dir {})]
        (is (= 2 (:exit r)))
        (is (= "" (:out r)))
        (is (str/starts-with? (first (:err r)) "cannot-run: unknown task frobnicate"))
        (is (every? #(str/includes? (first (:err r)) %) (map :task command/tasks)))))))

(deftest a-task-needs-a-configuration-and-its-results-directory
  (with-tmp
    (fn [dir]
      (is (re-find #"no configuration" (:cannot-run (command/execute ["status"] {:root dir :env {}}))))
      (fs/create-dirs (fs/file dir "dhf/harness"))
      (spit (str (fs/file dir "dhf/harness/config.edn")) "[:not :a :map]")
      (is (re-find #"not an EDN map" (:cannot-run (command/execute ["status"] {:root dir :env {}}))))
      (spit (str (fs/file dir "dhf/harness/config.edn")) "{:results-dir \"res\" :spec-dir \"dhf/spec\"}")
      (fs/create-dirs (fs/file dir "dhf/spec"))
      (testing "the configured results directory, missing, is named"
        (is (re-find #"res" (:cannot-run (command/execute ["status"] {:root dir :env {}})))))
      (testing "RESULTS_DIR, else --results, wins"
        (is (re-find #"envres" (:cannot-run (command/execute ["status"] {:root dir :env {"RESULTS_DIR" "envres"}}))))
        (is (re-find #"given" (:cannot-run (command/execute ["status" "--results" "given"]
                                                            {:root dir :env {"RESULTS_DIR" "envres"}}))))))))

(deftest stages-gate-and-stay-quiet
  (let [calls (atom [])
        stage (fn [k out] {:run (fn [_] (swap! calls conj k) out)})
        run-stages #'command/run-stages]
    (testing "every stage reports, a quiet one is not the task's, a gate's warnings do not stop"
      (is (= {:result {:a 1} :findings [(command/finding "a" "x") (command/warning "w" "z")]}
             (run-stages {} [(stage :a {:result {:a 1} :findings [(command/finding "a" "x")]})
                             (assoc (stage :q {:result {:q 1} :findings [(command/finding "q" "y")]}) :quiet? true)
                             (assoc (stage :g {:result {:g 1} :findings [(command/warning "w" "z")]}) :gate? true)])))
      (is (= [:a :q :g] @calls)))
    (testing "a gate with findings stops the task"
      (reset! calls [])
      (let [out (run-stages {} [(assoc (stage :g {:findings [(command/finding "g" "stop")]}) :gate? true)
                                (stage :after {:result {:after 1}})])]
        (is (= [:g] @calls))
        (is (= {:result {} :findings [(command/finding "g" "stop")]} out))))
    (testing "a stage whose code is not built refuses the task"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not built"
                            (run-stages {} [{:fn 'rdm-spec.nowhere.missing/stage}]))))))
