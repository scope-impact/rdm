(ns rdm-spec.project.adopt-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.project.adopt :as adopt]
            [rdm-spec.project.init :as init]))

(deftest missing-gitignore-lines-are-those-init-writes-that-are-absent
  (is (= (init/gitignore-lines) (adopt/missing-gitignore-lines "node_modules/\n")))
  (is (= ["site/"] (adopt/missing-gitignore-lines
                    "dhf/allure-results/\n dhf/cycles/*/results/\ndhf/cycles/*/build/\nsite-src/\n")))
  (is (= [] (adopt/missing-gitignore-lines (str/join "\n" (init/gitignore-lines))))))
