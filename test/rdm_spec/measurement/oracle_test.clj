(ns rdm-spec.measurement.oracle-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.measurement.oracle :as o]))

(deftest no-oracle-is-said
  (is (str/includes? (pr-str (o/stage {:root "." :config {} :args []})) "no oracle to run"))
  (is (= {} (o/gate-stage {:root "." :config {}}))))
