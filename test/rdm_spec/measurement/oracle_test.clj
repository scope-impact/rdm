(ns rdm-spec.measurement.oracle-test
  "One test per rule the Oracle task enforces (08-plugins/oracle.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.oracle :as oracle]))

(deftest the-oracle-task-runs-the-plugin-named-oracle-as-it-is
  (is (= {:name "oracle" :answers :Gate :command ["x"]} (oracle/the-oracle {:plugins [{:name "a" :answers :Gate :command ["y"]} {:name "oracle" :answers :Gate :command ["x"]}]})))
  (is (nil? (oracle/the-oracle {:plugins []}))))

(deftest nothing-is-fenced-or-verified-while-the-oracle-is-red
  (is (= ["oracle"] (map :name (rdm-spec.kernel.plugins/answerers [{:name "oracle" :answers :Gate}] :Gate)))))
