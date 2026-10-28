(ns rdm-spec.project.hooks-test
  "One test per rule Hooks enforces (01-start-a-project/hooks.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.project.init :as init]))

(deftest every-commit-is-checked
  (is (str/includes? init/hook-text "exec rdm-spec check"))
  (is (str/starts-with? init/hook-text "#!/bin/sh")))
