(ns rdm-spec.kernel.runner-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.runner :as runner]))

(def model
  {:containers [{:contexts [{:id :architecture :name "architecture"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"}]}]}]
   :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus"}]})

(def feature
  (str "@UN-001\nFeature: Lint\n\n  Rule: A rule\n\n    @spec-0012 @e2e\n    Scenario: One\n      Given a <x>\n\n"
       "    @spec-0013 @contract @UN-002\n    Scenario Outline: Rows <written>\n      Given a <written> and <kept>\n\n"
       "      Examples:\n        | written | kept |\n        | plan | 3 |\n        | draft | 4 |\n"))

(deftest pickles-are-one-per-scenario-and-one-per-row
  (let [ps (runner/pickles-of "dhf/spec/architecture/lint.feature" feature {:owners (runner/owners model) :spec-dir "dhf/spec"})]
    (is (= ["spec-0012" "spec-0013" "spec-0013"] (map :id ps)))
    (is (= [nil [["written" "plan"] ["kept" "3"]] [["written" "draft"] ["kept" "4"]]] (map :parameters ps)))
    (is (= ["Given a plan and 3"] (map :name (:steps (second ps)))))
    (is (= "Rows plan" (:display-name (second ps))))
    (is (= {:context-name "architecture" :component-name "Lint"} (select-keys (first ps) [:context-name :component-name])))
    (is (= ["UN-001" "UN-002"] (:needs (second ps))))
    (is (= "A rule" (:rule (first ps))))))

(deftest a-kernel-feature-is-owned-by-the-context-kernel
  (is (= {:context-name "kernel" :component-name "Event bus"} (get (runner/owners model) "kernel/bus"))))

(defn- with-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "runner-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(deftest a-step-runs-against-the-definitions-that-match-it
  (let [defs [{:re #"a (\d+)" :fn (fn [ctx n] (assoc ctx :n n)) :file "a.clj" :line 1}
              {:re #"it throws" :fn (fn [_] (throw (ex-info "no such view" {}))) :file "a.clj" :line 2}
              {:re #"a spec" :fn (fn [ctx] ctx) :file "a.clj" :line 3}
              {:re #"a [a-z]+" :fn (fn [ctx] ctx) :file "b.clj" :line 4}]]
    (testing "one match: the function gets the context and the captures"
      (is (= {:n "20"} (:ctx (runner/run-step defs {} {:keyword "Given" :text "a 20"})))))
    (testing "a throw fails the step"
      (let [r (runner/run-step defs {} {:keyword "When" :text "it throws"})]
        (is (= "failed" (:status r)))
        (is (str/includes? (:message r) "no such view"))))
    (testing "no match, or two, break it"
      (is (= "broken" (:status (runner/run-step defs {} {:keyword "Then" :text "nothing"}))))
      (let [r (runner/run-step defs {} {:keyword "Given" :text "a spec"})]
        (is (= "broken" (:status r)))
        (is (and (str/includes? (:message r) "a.clj:3") (str/includes? (:message r) "b.clj:4")))))))

(deftest a-result-is-written-in-allures-model
  (with-dir
    (fn [d]
      (let [ps (runner/pickles-of "dhf/spec/architecture/lint.feature" feature {:owners (runner/owners model) :spec-dir "dhf/spec"})
            defs [{:re #"a (\S+) and (\S+)" :fn (fn [ctx _ _] (runner/attach! "note" "seen") ctx) :file "a.clj" :line 1}]
            r (runner/run-pickle defs d (second ps))
            files (map str (fs/glob d "*"))]
        (is (= "passed" (:status r)))
        (is (= "spec-0013[plan, 3]" (:historyId r)))
        (is (= [{:name "written" :value "plan"} {:name "kept" :value "3"}] (:parameters r)))
        (is (= [{:type "issue" :name "UN-001"} {:type "issue" :name "UN-002"}] (:links r)))
        (is (some #(= {:name "story" :value "A rule"} %) (:labels r)))
        (is (some #(= {:name "framework" :value "gherkin"} %) (:labels r)))
        (let [att (first (:attachments (first (:steps r))))]
          (is (= "text/plain" (:type att)))
          (is (= "seen" (slurp (str (fs/file d (:source att)))))))
        (is (= 2 (count files)))
        (is (some #(str/ends-with? % "-result.json") files))
        (is (= "passed" (get (json/parse-string (slurp (first (filter #(str/ends-with? % "-result.json") files)))) "status")))))))

(deftest a-planned-scenario-is-skipped-and-labelled
  (with-dir
    (fn [d]
      (let [pk (first (runner/pickles-of "dhf/spec/a/b.feature"
                                         "Feature: F\n  @spec-0001 @e2e @wip\n  Scenario: S\n    Given x\n" {:owners {} :spec-dir "dhf/spec"}))
            ran (atom false)
            r (runner/run-pickle [{:re #"x" :fn (fn [c] (reset! ran true) c) :file "a" :line 1}] d pk)]
        (is (not @ran))
        (is (= "skipped" (:status r)))
        (is (some #(= {:name "planned" :value "true"} %) (:labels r)))))))
