(ns rdm-spec.project.adopt-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.project.adopt :as adopt]))

(deftest missing-ignore-lines
  (is (= ["site/"] (adopt/missing-lines "a/\nsite-src/\n" ["site-src/" "site/"]))))
