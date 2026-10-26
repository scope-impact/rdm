(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(deftest findings-are-formatted-as-the-readme-says
  (is (= "a/b.feature:3: scenario-no-level: why" (command/format-finding (command/finding "a/b.feature" 3 "scenario-no-level" "why"))))
  (is (= "a/b.feature: rule: why" (command/format-finding (command/finding "a/b.feature" "rule" "why"))))
  (is (= "rule: why" (command/format-finding (command/finding nil nil "rule" "why"))))
  (is (= "a.md:2: warning: rule: why" (command/format-finding (command/warning "a.md" 2 "rule" "why"))))
  (is (= "a.md: warning: rule: why" (command/format-finding (command/warning "a.md" "rule" "why"))))
  (is (= "warning: rule: why" (command/format-finding (command/warning nil nil "rule" "why"))))
  (testing "a message is one line"
    (is (= "f: r: one two" (command/format-finding (command/finding "f" "r" "one\ntwo"))))))

(deftest warnings-and-cannot-run-are-told-apart
  (is (command/warning? (command/warning "f" "r" "m")))
  (is (not (command/warning? (command/finding "f" "r" "m"))))
  (is (command/cannot-run? (try (command/cannot-run! "no") (catch Exception e e))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest arguments-are-split-into-positionals-and-options
  (is (= {:args ["ROOM" "spec-3"] :opts {:context "a,b" :component "x/y"}}
         (command/parse-args (command/task-of "fence") ["ROOM" "spec-3" "--context" "a,b" "--component" "x/y"])))
  (is (= {:args [] :opts {:events true :sources "D"}}
         (command/parse-args (command/task-of "check") ["--events" "--sources" "D"])))
  (testing "an option the task does not take, a missing value, a wrong count cannot run"
    (doseq [[t args] [["check" ["--results" "x"]] ["check" ["--sources"]] ["trace" []] ["status" ["extra"]]]]
      (is (command/cannot-run? (try (command/parse-args (command/task-of t) args) nil (catch Exception e e)))
          (pr-str t args)))))

(deftest every-task-has-a-usage-and-a-description
  (let [ts (command/help-entries)]
    (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist"
            "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit" "observe"]
           (mapv :task ts)))
    (is (every? #(and (string? (:usage %)) (string? (:does %))) ts))))

(deftest the-results-directory-is-the-option-else-the-environment-else-the-configured-one
  (let [root (str (fs/create-temp-dir))]
    (try
      (fs/create-dirs (fs/file root "dhf/allure-results"))
      (let [req {:root root :config {:results-dir "dhf/allure-results"}}]
        (is (= "o" (command/results-of (assoc req :opts {:results "o"} :env {"RESULTS_DIR" "e"}))))
        (is (= "e" (command/results-of (assoc req :opts {} :env {"RESULTS_DIR" "e"}))))
        (is (= "dhf/allure-results" (command/results-of (assoc req :opts {} :env {}))))
        (is (nil? (command/results-of {:root root :config {:results-dir "none"} :opts {} :env {}}))))
      (finally (fs/delete-tree root)))))

(deftest config-values-fall-back-to-the-defaults
  (is (= "dhf/spec" (command/config-value {:config {}} :spec-dir)))
  (is (= "x" (command/config-value {:config {:spec-dir "x"}} :spec-dir)))
  (is (= "dhf/spec/domain.edn" (command/config-value {:config {}} :domain))))

;; Fake stages, for the task runner

(defn stage-a [_] {:result {:a 1} :findings [(command/warning "f" "w" "a warning")]})
(defn stage-b [_] {:result {:b 2} :findings [(command/finding "f" 1 "r" "a finding")]})
(defn stage-c [_] {:result {:c 3} :findings []})
(defn stage-gate [_] {:result {:g 1} :findings [(command/finding "g" "gate" "red")]})
(defn stage-cannot [_] (command/cannot-run! "it cannot"))

(defn- run [stages]
  (command/run-task {:task "t" :stages stages} {:root "." :config {} :opts {} :args [] :env {}}))

(deftest every-stage-reports-and-a-gate-stops
  (let [r (run [{:fn `stage-a} {:fn `stage-b} {:fn `stage-c}])]
    (is (= {:a 1 :b 2 :c 3} (:result r)))
    (is (= 1 (:exit r)))
    (is (= ["f:1: r: a finding" "f: warning: w: a warning"] (:err-lines r))))
  (let [r (run [{:fn `stage-gate :gate true} {:fn `stage-c}])]
    (is (= 1 (:exit r)))
    (is (= {} (:result r)) "a gate's result is not the task's, and the stages after it never run"))
  (let [r (run [{:fn `stage-a :gate true} {:fn `stage-c}])]
    (is (= 0 (:exit r)) "a gate's warnings do not stop the task")
    (is (= {:c 3} (:result r))))
  (let [r (run [{:fn `stage-b :quiet true} {:fn `stage-c}])]
    (is (= 0 (:exit r)) "a quiet stage reports nothing")
    (is (= {:c 3} (:result r))))
  (let [r (run [{:fn `stage-a} {:fn `stage-cannot}])]
    (is (= 2 (:exit r)))
    (is (= ["cannot-run: it cannot"] (:err-lines r))))
  (let [r (run [{:fn 'rdm-spec.nowhere/stage}])]
    (is (= 2 (:exit r)) "a stage whose code is not built refuses the task")
    (is (str/includes? (first (:err-lines r)) "rdm-spec.nowhere"))))

(deftest an-unknown-task-cannot-run-and-lists-the-tasks
  (let [r (command/execute ["frobnicate"] {:root "." :env {}})]
    (is (= 2 (:exit r)))
    (is (str/includes? (:cannot-run r) "frobnicate"))
    (is (str/includes? (:cannot-run r) "observe"))))

(deftest help-lists-every-task
  (let [r (command/execute ["help"] {:root "." :env {}})]
    (is (= 0 (:exit r)))
    (is (= (command/help-entries) (:help (:result r))))
    (is (str/starts-with? (command/output r) "{:help"))))
