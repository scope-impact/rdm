(ns rdm-spec.measurement.oracle-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.oracle :as oracle]))

(def config {:plugins [{:name "lint-a" :subscribes [:SpecParsed] :command ["sh" "a.sh"]}
                       {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}]})

(deftest the-oracle-is-the-plugin-named-oracle
  (is (= "oracle" (:name (oracle/oracle-of config))))
  (is (nil? (oracle/oracle-of {:plugins [{:name "lint-a" :subscribes [:SpecParsed] :command ["x"]}]})))
  (is (nil? (oracle/oracle-of {}))))

(deftest its-arguments-are-passed-on
  (is (= ["bb" "dhf/harness/oracle/oracle.clj" "record" "--reason" "the spec grew"]
         (oracle/command-of (oracle/oracle-of config) ["record" "--reason" "the spec grew"]))))
