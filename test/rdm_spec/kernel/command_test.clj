(ns rdm-spec.kernel.command-test
  "One test per rule the command enforces (01-start-a-project/command.feature, 11-project-in-a-folder/command.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.project :as project]))

(deftest everything-runs-through-one-command
  (is (= "help" (:task (first (:help (:result (command/help {})))))))
  (is (= 22 (count command/tasks)))
  (is (= {:args ["a"] :opts {"--results" "r"}} (command/parse-args "trace" ["a" "--results" "r"]))))

(deftest a-gate-stops-the-task
  (is (command/blocking? {:findings [(project/finding "f" 1 "r" "m")]}))
  (is (not (command/blocking? {:findings [(project/warning "f" 1 "r" "m")]}))))

(deftest every-task-reports-the-same-way
  (is (= "f.feature:3: level-missing: no level" (project/finding-line (project/finding "f.feature" 3 "level-missing" "no level"))))
  (is (= "f: r: what" (project/finding-line (project/finding "f" nil "r" "what"))))
  (is (= "f:2: warning: r: what" (project/finding-line (project/warning "f" 2 "r" "what"))))
  (let [{:keys [exit out err]} (command/execute ["frobnicate"])]
    (is (= 2 exit))
    (is (= "" out))
    (is (str/includes? err "cannot-run: unknown task frobnicate"))))

(deftest a-project-is-the-folder-its-configuration-is-in
  (is (= 2 (:exit (command/execute ["status" "--results" "nowhere-at-all"]))) "no configuration in this folder... or a missing directory"))
