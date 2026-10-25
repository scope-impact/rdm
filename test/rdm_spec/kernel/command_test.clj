(ns rdm-spec.kernel.command-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]))

(defn- refusal [f] (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest findings-and-their-lines
  (is (= "a.feature:3: rule-x: wrong" (command/format-finding (command/finding "a.feature" 3 "rule-x" "wrong"))))
  (is (= "a.feature: rule-x: wrong" (command/format-finding (command/finding "a.feature" "rule-x" "wrong"))))
  (is (= "rule-x: wrong" (command/format-finding (command/finding "rule-x" "wrong"))))
  (is (= "a.md:2: warning: rule-y: hm" (command/format-finding (command/warning "a.md" 2 "rule-y" "hm"))))
  (is (= "a.md: warning: rule-y: hm" (command/format-finding (command/warning "a.md" nil "rule-y" "hm"))))
  (is (= "warning: rule-y: hm" (command/format-finding (command/warning "rule-y" "hm"))))
  (is (= "x: relayed" (command/format-finding {:file nil :line nil :rule "x" :message "relayed"})))
  (is (command/warning? (command/warning "r" "m")))
  (is (not (command/warning? (command/finding "r" "m")))))

(deftest refusing
  (is (= "why" (refusal #(command/cannot-run! "why"))))
  (is (not (command/cannot-run? (ex-info "other" {})))))

(deftest arguments
  (let [row (first (filter #(= "fence" (:task %)) command/tasks))]
    (is (= {:args ["R" "spec-3"] :opts {:context "a,b" :events true}}
           (command/parse-args row ["R" "--context" "a,b" "spec-3" "--events"])))
    (is (re-find #"usage: rdm-spec fence" (refusal #(command/parse-args row ["R"]))))
    (is (re-find #"no option --frob" (refusal #(command/parse-args row ["R" "C" "--frob"]))))
    (is (re-find #"needs a value" (refusal #(command/parse-args row ["R" "C" "--context"])))))
  (let [row (first (filter #(= "score" (:task %)) command/tasks))]
    (is (re-find #"needs --verdicts" (refusal #(command/parse-args row ["03"]))))))

(deftest the-results-directory
  (let [row {:results :required}]
    (is (= "flag" (command/results-dir row {:results "flag"} {:results-dir "c"} {"RESULTS_DIR" "env"} ".")))
    (is (= "env" (command/results-dir row {} {:results-dir "c"} {"RESULTS_DIR" "env"} ".")))
    (is (= "c" (command/results-dir row {} {:results-dir "c"} {"RESULTS_DIR" ""} ".")))
    (is (re-find #"no results directory" (refusal #(command/results-dir row {} {} {} ".")))))
  (testing "optional: the configured one only when it exists"
    (let [d (str (fs/create-temp-dir))]
      (try
        (is (nil? (command/results-dir {:results :optional} {} {:results-dir "r"} {} d)))
        (fs/create-dirs (fs/file d "r"))
        (is (= "r" (command/results-dir {:results :optional} {} {:results-dir "r"} {} d)))
        (finally (fs/delete-tree d))))))

(deftest the-task-table
  (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "ratify" "checklist"
          "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit"]
         (command/task-names)))
  (testing "the oracle gates fence and ratify; the release check gates the audit copy"
    (doseq [[t gate] [["fence" 'rdm-spec.measurement.oracle/oracle] ["ratify" 'rdm-spec.measurement.oracle/oracle]
                      ["audit" 'rdm-spec.verification.release/release]]]
      (is (some #(and (:gate %) (= gate (:stage %))) (:stages (first (filter #(= t (:task %)) command/tasks)))) t)))
  (testing "gap analysis is a stage of check, only under :regulated"
    (is (some #(and (= 'rdm-spec.compliance.gap/gap (:stage %)) (= :regulated (:when %)))
              (:stages (first (filter #(= "check" (:task %)) command/tasks)))))))

(deftest executing
  (let [d (str (fs/create-temp-dir))]
    (try
      (testing "an unknown task cannot run and lists the tasks"
        (let [r (command/run-task ["frobnicate"] d {})]
          (is (= 2 (:exit r)))
          (is (= "" (:out r)))
          (is (str/includes? (first (:err r)) "frobnicate"))
          (is (str/includes? (first (:err r)) "audit"))))
      (testing "help prints every task as EDN"
        (let [r (command/run-task ["help"] d {})]
          (is (= 0 (:exit r)))
          (is (= 20 (count (:tasks (read-string (:out r))))))))
      (testing "a stage's findings: exit 1, errors before warnings, the result still printed"
        (with-redefs [command/tasks [{:task "t" :usage "t" :does "" :args [0 0]
                                      :stages [{:stage `stage-a} {:stage `stage-b :gate true} {:stage `stage-c}]}]]
          (let [r (command/run-task ["t"] d {})]
            (is (= 1 (:exit r)))
            (is (= ["b: no" "warning: a: hm"] (:err r)) "the gate stopped stage c")
            (is (= {:a 1 :b 2} (read-string (:out r)))))))
      (finally (fs/delete-tree d)))))

(defn stage-a [_] {:result {:a 1} :findings [(command/warning "a" "hm")]})
(defn stage-b [_] {:result {:b 2} :findings [(command/finding "b" "no")]})
(defn stage-c [_] {:result {:c 3} :findings [(command/finding "c" "never")]})
