(ns rdm-spec.kernel.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.spec.parser :as parser]))

(defn scenario [text]
  (first (:scenarios (parser/parse-feature text "f.feature"))))

(def two-steps
  "Feature: F
  Scenario: S
    Given a 1
    Then b holds
    And c
")

(deftest steps-thread-the-context
  (let [seen (atom nil)
        reg (runner/registry
             [[#"a (\d+)" (fn [ctx n] (assoc ctx :n n)) "x:1"]
              [#"b holds" (fn [ctx] (reset! seen ctx) :not-a-map) "x:2"]
              [#"c" (fn [ctx] (assert (= "1" (:n ctx))) ctx) "x:3"]])
        r (runner/run-example reg (scenario two-steps) nil {})]
    (is (= {:n "1"} @seen))
    (is (= "passed" (:status r)))
    (is (= ["Given a 1" "Then b holds" "And c"] (map :name (:steps r))))))

(deftest a-then-assertion-fails-and-the-rest-is-skipped
  (let [reg (runner/registry
             [[#"a (\d+)" (fn [ctx _] ctx) "x:1"]
              [#"b holds" (fn [_] (runner/fail! "no such view")) "x:2"]
              [#"c" (fn [ctx] ctx) "x:3"]])
        r (runner/run-example reg (scenario two-steps) nil {})]
    (is (= "failed" (:status r)))
    (is (re-find #"no such view" (get-in r [:statusDetails :message])))
    (is (= ["passed" "failed" "skipped"] (map :status (:steps r))))))

(deftest a-given-that-throws-is-broken
  (let [reg (runner/registry
             [[#"a (\d+)" (fn [_ _] (throw (ex-info "boom" {}))) "x:1"]
              [#"b holds" (fn [ctx] ctx) "x:2"]
              [#"c" (fn [ctx] ctx) "x:3"]])
        r (runner/run-example reg (scenario two-steps) nil {})]
    (is (= "broken" (:status r)))))

(deftest a-then-that-throws-a-non-assertion-is-broken
  (let [reg (runner/registry
             [[#"a (\d+)" (fn [ctx _] ctx) "x:1"]
              [#"b holds" (fn [_] (throw (ex-info "boom" {}))) "x:2"]
              [#"c" (fn [ctx] ctx) "x:3"]])
        r (runner/run-example reg (scenario two-steps) nil {})]
    (is (= "broken" (:status r)))))

(deftest unmatched-and-ambiguous-steps-are-broken
  (let [reg (runner/registry [[#"a (\d+)" (fn [ctx _] ctx) "x:1"]
                              [#"a \d" (fn [ctx] ctx) "y:9"]])
        r (runner/run-example reg (scenario two-steps) nil {})]
    (is (= "broken" (:status r)))
    (is (re-find #"x:1" (get-in r [:statusDetails :message])))
    (is (re-find #"y:9" (get-in r [:statusDetails :message]))))
  (let [r (runner/run-example (runner/registry []) (scenario two-steps) nil {})]
    (is (= "broken" (:status r)))
    (is (re-find #"Given a 1" (get-in r [:statusDetails :message])))))
