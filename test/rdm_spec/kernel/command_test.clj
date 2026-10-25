(ns rdm-spec.kernel.command-test
  "Unit tests of the command. The few that need a project make one in a
  temporary directory and remove it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "command-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest findings-and-warnings-are-lines-of-one-shape
  (is (= "a.feature:3: scenario-no-level: why" (command/format-finding (command/finding "a.feature" 3 "scenario-no-level" "why"))))
  (is (= "a.md: document-unidentified: why" (command/format-finding (command/finding "a.md" "document-unidentified" "why"))))
  (is (= "site-build: why" (command/format-finding (command/finding "site-build" "why"))))
  (is (= "a.md:4: warning: clause-unknown: why" (command/format-finding (command/warning "a.md" 4 "clause-unknown" "why"))))
  (is (= "warning: scoreboard: why" (command/format-finding (command/warning "scoreboard" "why"))))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest a-task-that-cannot-run-says-why
  (let [e (try (command/cannot-run! "no such thing") (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (= "no such thing" (ex-message e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest paths-are-the-project's
  (is (= "/p/dhf/spec" (str (command/path {:root "/p"} "dhf/spec"))))
  (is (= "/abs/x" (str (command/path "/p" "/abs/x"))))
  (is (= "dhf/spec/a.feature" (command/rel {:root "/p"} "/p/dhf/spec/a.feature")))
  (is (= "/elsewhere/a" (command/rel "/p" "/elsewhere/a"))))

(deftest configured-values-have-defaults
  (let [req {:config {:spec-dir "spec/"}}]
    (is (= "spec/" (command/config-value req :spec-dir)))
    (is (= "spec/ids.edn" (command/config-value req :ledger)))
    (is (= "spec/events.edn" (command/config-value req :events))))
  (is (= "dhf/spec" (command/config-value {} :spec-dir)))
  (is (= "spec" (command/config-value {} :id-prefix)))
  (is (= "test/steps" (command/config-value {} :steps-dir))))

(deftest arguments-are-read-as-the-usage-says
  (let [fence (first (filter #(= "fence" (:task %)) command/tasks))]
    (is (= {:args ["R" "spec-3"] :opts {:context "a,b" :component "spec/parser"}}
           (command/parse-args fence ["R" "spec-3" "--context" "a" "--context=b" "--component" "spec/parser"])))
    (is (= {:args ["R" "spec-3"] :opts {:events true}} (command/parse-args fence ["--events" "R" "spec-3"])))
    (testing "an argument the task does not take cannot run"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"--frob" (command/parse-args fence ["R" "C" "--frob" "x"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes 2 arguments" (command/parse-args fence ["R"])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs a value" (command/parse-args fence ["R" "C" "--context"]))))))

(deftest help-lists-every-task-in-order
  (let [{:keys [exit out err]} (command/run-task ["help"] "." {})
        tasks (get-in (edn/read-string out) [:help :tasks])]
    (is (= 0 exit))
    (is (= [] err))
    (is (= (map :task command/tasks) (map :task tasks)))
    (is (every? (comp not str/blank? :usage) tasks))))

(deftest an-unknown-task-cannot-run-and-lists-the-tasks
  (let [{:keys [exit out err]} (command/run-task ["frobnicate"] "." {})]
    (is (= 2 exit))
    (is (= "" out))
    (is (= 1 (count err)))
    (is (str/starts-with? (first err) "cannot-run: unknown task frobnicate"))
    (is (every? #(str/includes? (first err) (:task %)) command/tasks))))

(deftest a-task-needs-a-configuration
  (with-tmp
    (fn [d]
      (let [{:keys [exit err]} (command/run-task ["status"] d {})]
        (is (= 2 exit))
        (is (str/includes? (first err) command/config-file))))))

(deftest the-results-directory-is-results-else-results-dir-else-the-configured-one
  (with-tmp
    (fn [d]
      (spit (str (fs/file d "x")) "")
      (let [rd #'command/results-dir
            status {:results :required}
            trace {:results :optional}
            cfg {:results-dir "dhf/allure-results"}]
        (is (= "dhf/allure-results" (rd status {} {} d cfg)))
        (is (= "/r1" (rd status {} {"RESULTS_DIR" "/r1"} d cfg)))
        (is (= "other" (rd status {:results "other"} {"RESULTS_DIR" "/r1"} d cfg)))
        (testing "trace and publish read the configured one only when it exists"
          (is (nil? (rd trace {} {} d cfg)))
          (fs/create-dirs (fs/file d "dhf/allure-results"))
          (is (= "dhf/allure-results" (rd trace {} {} d cfg))))
        (is (nil? (rd {} {:results "x"} {} d cfg)) "a task that reads no results is given none")))))

(deftest a-gate-stops-the-task-and-every-other-stage-reports
  (let [run #'command/run-stages
        ran (atom [])
        stage (fn [k fs*] (fn [_] (swap! ran conj k) {:result {k true} :findings fs*}))
        bad [(command/finding "f" 1 "r" "m")]
        warn [(command/warning "f" 1 "w" "m")]]
    (is (= {:result {:a true :b true} :findings (into bad bad)}
           (run {} [{:fn (stage :a bad)} {:fn (stage :b bad)}])))
    (reset! ran [])
    (is (= {:result {:g true} :findings bad} (run {} [{:fn (stage :g bad) :gate? true} {:fn (stage :b [])}])))
    (is (= [:g] @ran) "the stages after a gate with findings never run")
    (reset! ran [])
    (is (= {:result {:g true :b true} :findings warn}
           (run {} [{:fn (stage :g warn) :gate? true} {:fn (stage :b [])}]))
        "a warning does not stop a gate")
    (is (= {:result {:b true} :findings []} (run {} [{:fn (stage :q bad) :quiet? true} {:fn (stage :b [])}]))
        "a quiet stage reports nothing")))

(deftest a-stage-whose-code-is-not-built-refuses-the-task
  (let [e (try (#'command/resolve-stage {:fn 'rdm-spec.nowhere.nothing/stage :gate? true}) (catch Exception e e))]
    (is (command/cannot-run? e))
    (is (str/includes? (ex-message e) "not built"))))

(deftest events-are-listed-after-findings-and-warnings
  (let [out (command/render {:exit 1 :result {:x 1}
                             :findings [(command/warning "w" "a warning") (command/finding "f" "a finding")]
                             :events [{:event :SpecParsed :publisher :spec/parser}]
                             :opts {:events true}})]
    (is (= 1 (:exit out)))
    (is (= "{:x 1}\n" (:out out)))
    (is (= ["f: a finding" "warning: w: a warning" "event: SpecParsed published-by spec/parser"] (:err out)))))
