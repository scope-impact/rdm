(ns rdm-spec.kernel.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [rdm-spec.kernel.runner :as r]))

(def feature-text
  "@UN-001
Feature: Demo
  Some description: with a colon.

  Background:
    Given a base

  Rule: The rule

    @spec-0001 @e2e
    Scenario: One
      Given a step with \"quotes\"
      When a table
        | a | b |
        | 1 | 2 |
      Then a doc
        \"\"\"
        hello
          world
        \"\"\"

    @spec-0002 @contract
    Scenario Outline: Two <x>
      Given the value <x>
      But not <y>

      Examples:
        | x | y |
        | 1 | 2 |
        | 3 | 4 |
")

(deftest reads-a-feature
  (let [f (r/read-feature "dhf/spec/a/b.feature" feature-text)
        [s1 s2] (:scenarios f)]
    (is (empty? (:refusals f)))
    (is (= "Demo" (get-in f [:feature :name])))
    (is (= ["UN-001"] (map :tag (get-in f [:feature :tags]))))
    (is (= "One" (:name s1)))
    (is (= 11 (:line s1)))
    (is (= "The rule" (get-in s1 [:rule :name])))
    (is (= ["spec-0001" "e2e"] (map :tag (:tags s1))))
    (is (= [["1" "2"]] (map :cells (rest (:table (second (:steps s1)))))))
    (is (= "hello\n  world" (:doc-string (nth (:steps s1) 2))))
    (is (= ["Given a base"] (map #(str (:keyword %) " " (:text %)) (:background f))))
    (is (= "Scenario Outline" (:keyword s2)))
    (is (= ["x" "y"] (:headings (first (:examples s2)))))
    (is (= [["1" "2"] ["3" "4"]] (map :cells (:rows (first (:examples s2))))))))

(deftest refuses-unknown-keywords-and-two-features
  (let [f (r/read-feature "x.feature" "Feature: A\n\n  Scenario Template: B\n    Given x\n")]
    (is (= [3] (map :line (:refusals f))))
    )
  (let [f (r/read-feature "x.feature" "Feature: A\nFeature: B\n")]
    (is (= [2] (map :line (:refusals f))))))

(deftest expands-outline-rows
  (let [f (r/read-feature "x.feature" feature-text)
        runs (r/pickles f)]
    (is (= 3 (count runs)))
    (is (= ["Given a base" "Given the value 3" "But not 4"]
           (map #(str (:keyword %) " " (:text %)) (:steps (nth runs 2)))))
    (is (= [{"name" "x" "value" "3"} {"name" "y" "value" "4"}] (:parameters (nth runs 2))))))

(defn- tmpdir [] (str (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))})))

(deftest runs-steps-and-writes-results
  (let [dir (tmpdir)]
    (try
      (r/clear-steps!)
      (r/defstep #"a base" (fn [ctx] (assoc ctx :base true)))
      (r/defstep #"a step with \"(.*)\"" (fn [ctx q] (assert (:base ctx)) (assoc ctx :q q)))
      (r/defstep #"a table" (fn [ctx table] (r/attach! "t" "text") (assoc ctx :t table)))
      (r/defstep #"a doc" (fn [ctx doc] (when-not (= "quotes" (:q ctx)) (r/fail! "no q")) nil))
      (r/defstep #"the value (\d+)" (fn [ctx v] (when (= v "3") (throw (Exception. "boom"))) ctx))
      (r/defstep #"not (\d+)" (fn [ctx v] ctx))
      (let [summary (r/run-features {:root "." :files [{:file "dhf/spec/a/b.feature" :text feature-text}]
                                     :results-dir dir :owner (constantly {:context "a" :component "B" :sub-suite "a/b"})
                                     :needs-path "dhf/spec/needs.edn"})
            results (->> (fs/glob dir "*-result.json") (map #(json/parse-string (slurp (str %)))) (sort-by #(get % "historyId")))]
        (is (= {:passed 2 :broken 1} (select-keys (:counts summary) [:passed :broken])))
        (is (= ["spec-0001" "spec-0002|1|2" "spec-0002|3|4"] (map #(get % "historyId") results)))
        (is (= "broken" (get (nth results 2) "status")))
        (is (= ["passed" "broken" "skipped"] (map #(get % "status") (get (nth results 2) "steps"))))
        (is (some #(= {"name" "spec" "value" "spec-0001"} %) (get (first results) "labels")))
        (is (= [{"type" "issue" "name" "UN-001" "url" "dhf/spec/needs.edn#UN-001"}] (get (first results) "links")))
        (is (= 1 (count (fs/glob dir "*-attachment*")))))
      (finally (fs/delete-tree dir) (r/clear-steps!)))))

(deftest failed-only-for-a-then-assertion
  (let [dir (tmpdir)
        text "@UN-001\nFeature: F\n  @spec-0001 @e2e\n  Scenario: S\n    Given g\n    Then t\n"]
    (try
      (doseq [[g t expect] [[(fn [c] c) (fn [c] (assert false "nope")) "failed"]
                            [(fn [c] (assert false)) (fn [c] c) "broken"]
                            [(fn [c] c) (fn [c] (throw (Exception. "x"))) "broken"]]]
        (r/clear-steps!)
        (r/defstep #"g" g) (r/defstep #"t" t)
        (fs/delete-tree dir) (fs/create-dirs dir)
        (r/run-features {:root "." :files [{:file "x.feature" :text text}] :results-dir dir :owner (constantly nil)})
        (let [res (json/parse-string (slurp (str (first (fs/glob dir "*-result.json")))))]
          (is (= expect (get res "status")))))
      (finally (fs/delete-tree dir) (r/clear-steps!)))))

(deftest ambiguous-and-missing-steps-are-broken
  (let [dir (tmpdir)
        text "@UN-001\nFeature: F\n  @spec-0001 @e2e\n  Scenario: S\n    Given a spec\n  @spec-0002 @e2e\n  Scenario: T\n    Given nothing matches\n  @spec-0003 @e2e @wip\n  Scenario: U\n    Given a spec\n"]
    (try
      (r/clear-steps!)
      (r/defstep #"a spec" (fn [c] c))
      (r/defstep #"a (.*)" (fn [c _] c))
      (r/run-features {:root "." :files [{:file "x.feature" :text text}] :results-dir dir :owner (constantly nil)})
      (let [rs (->> (fs/glob dir "*-result.json") (map #(json/parse-string (slurp (str %)))) (sort-by #(get % "historyId")))]
        (is (= ["broken" "broken" "skipped"] (map #(get % "status") rs)))
        (is (re-find #"a spec.*a \(\.\*\)" (get-in (first rs) ["statusDetails" "message"])))
        (is (re-find #"nothing matches" (get-in (second rs) ["statusDetails" "message"])))
        (is (some #(= {"name" "planned" "value" "true"} %) (get (nth rs 2) "labels"))))
      (finally (fs/delete-tree dir) (r/clear-steps!)))))
