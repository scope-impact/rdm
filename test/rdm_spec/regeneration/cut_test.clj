(ns rdm-spec.regeneration.cut-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.cut :as cut]))

(deftest next-cut
  (is (= "spec-1" (cut/next-tag [])))
  (is (= "spec-7" (cut/next-tag ["spec-6" "spec-2" "v1.0" "spec-x"]))))

(deftest cuts-at
  (is (= ["spec-7"] (cut/cut-tags ["spec-7" "release"]))))
