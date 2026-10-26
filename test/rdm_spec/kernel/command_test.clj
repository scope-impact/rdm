(ns rdm-spec.kernel.command-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as c]))

(deftest finding-lines
  (is (= "a.feature:3: level-missing: no level" (c/finding-line (c/finding "a.feature" 3 "level-missing" "no level"))))
  (is (= "a.feature: x: two\nlines" (str/replace (c/finding-line (c/finding "a.feature" nil "x" "two\nlines")) " lines" "\nlines")))
  (is (= "x: y z" (c/finding-line (c/finding nil nil "x" "y\nz"))))
  (is (= "a.md:2: warning: r: w" (c/warning-line (c/finding "a.md" 2 "r" "w"))))
  (is (= "warning: r: w" (c/warning-line (c/finding nil nil "r" "w")))))

(deftest help-lists-every-task-in-order
  (let [{:keys [exit out]} (c/run-task "help" [] {:root "."})
        help (:help (edn/read-string out))]
    (is (= 0 exit))
    (is (= ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist"
            "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit" "observe"]
           (map :task help)))
    (is (= "fence ROOM CUT [--context C,...] [--component C/N,...]" (:usage (nth help 8))))))

(deftest unknown-task
  (let [{:keys [exit err out]} (c/run-task "frobnicate" [] {:root "."})]
    (is (= 2 exit))
    (is (= "" out))
    (is (str/includes? err "frobnicate"))
    (is (str/includes? err "scoreboard"))))

(deftest results-directory
  (is (= "R1" (c/results-dir {:opts {} :env {"RESULTS_DIR" "R1"} :config {:results-dir "dhf/allure-results"}})))
  (is (= "other" (c/results-dir {:opts {:results "other"} :env {"RESULTS_DIR" "R1"} :config {:results-dir "x"}})))
  (is (= "dhf/allure-results" (c/results-dir {:opts {} :env {} :config {:results-dir "dhf/allure-results"}}))))

(deftest outside-a-project
  (let [{:keys [exit err]} (c/run-task "status" [] {:root "/"})]
    (is (= 2 exit))
    (is (str/starts-with? err "cannot-run: "))))
