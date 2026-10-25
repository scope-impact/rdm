(ns rdm-spec.kernel.runner-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.runner :as runner]))

(defn- tmp [] (str (fs/create-temp-dir {:prefix "rdm-spec-runner-test-"})))

(defn- write! [dir rel content]
  (let [f (fs/file dir rel)] (fs/create-dirs (fs/parent f)) (spit f content)))

(def model {:containers [{:contexts [{:id :architecture :name "architecture"
                                      :components [{:name "Lint" :spec "architecture/lint"}]}]}]
            :kernel [{:name "Command" :spec "kernel/command"}]})

(deftest owners
  (is (= {:component "Lint" :context "architecture"} (runner/owner-of model "architecture/lint")))
  (is (= {:component "Command" :context "kernel"} (runner/owner-of model "kernel/command")))
  (is (nil? (runner/owner-of model "nobody/owns")))
  (is (= {:component "alpha" :context "demo"} (runner/owner-of nil "demo/alpha"))))

(deftest summarize-counts
  (is (= {:scenarios 3 :passed 2 :failed 1 :broken 0 :skipped 0}
         (runner/summarize [{:status "passed"} {:status "passed"} {:status "failed"}]))))

(deftest results-files
  (let [d (tmp)]
    (runner/write-result! d {:uuid "u1" :name "n"})
    (spit (str (fs/file d "keep.txt")) "x")
    (is (= "n" (:name (json/parse-string (slurp (str (fs/file d "u1-result.json"))) true))))
    (runner/clean-results! d)
    (is (= ["keep.txt"] (map #(str (fs/file-name %)) (fs/list-dir d))))))

(def feature
  (str "@UN-001\nFeature: Demo\n\n  Rule: R\n\n"
       "    @spec-0001 @e2e\n    Scenario: Passes\n      Given a value 3\n      Then it is 3\n\n"
       "    @spec-0002 @e2e\n    Scenario: Fails\n      Given a value 3\n      Then it is 4\n      And it is 3\n\n"
       "    @spec-0003 @e2e\n    Scenario: Unmatched\n      Given nothing defines this\n\n"
       "    @spec-0004 @e2e @wip\n    Scenario: Planned\n      Given a value 3\n\n"
       "    @spec-0005 @e2e\n    Scenario Outline: Rows <n>\n      Given a value <n>\n\n"
       "      Examples:\n        | n |\n        | 1 |\n        | 2 |\n"))

(def steps
  (str "(require '[rdm-spec.kernel.runner :refer [defstep]])\n"
       "(defstep #\"a value (\\d+)\" (fn [ctx n] (assoc ctx :v n)))\n"
       "(defstep #\"it is (\\d+)\" (fn [ctx n] (when-not (= n (:v ctx)) (throw (ex-info (str \"not \" n) {})))))\n"))

(deftest runs-a-spec
  (let [d (tmp)
        saved @runner/*registry*]
    (try
      (write! d "spec/architecture/lint.feature" feature)
      (write! d "steps/demo.clj" steps)
      (write! d "model.edn" (pr-str model))
      (let [out (runner/run-features {:root d :spec-dir "spec" :steps-dir "steps" :results-dir "results"
                                      :id-prefix "spec" :model "model.edn" :clean? true})
            by-id (group-by :id (:results out))
            files (fs/glob (fs/file d "results") "*-result.json")
            written (map #(json/parse-string (slurp (str %)) true) files)]
        (is (empty? (:refusals out)))
        (is (= 6 (count (:results out)) (count files)))
        (is (= "passed" (:status (first (by-id "spec-0001")))))
        (is (= "failed" (:status (first (by-id "spec-0002")))))
        (is (str/includes? (:message (first (by-id "spec-0002"))) "Then it is 4"))
        (is (= "broken" (:status (first (by-id "spec-0003")))))
        (is (= "skipped" (:status (first (by-id "spec-0004")))))
        (is (= ["passed" "passed"] (map :status (by-id "spec-0005"))))
        (testing "labels and steps"
          (let [r (first (filter #(= "Fails" (:name %)) written))]
            (is (= [{:name "spec" :value "spec-0002"} {:name "component" :value "Lint"} {:name "context" :value "architecture"}]
                   (take 3 (:labels r))))
            (is (= ["passed" "failed" "skipped"] (map :status (:steps r))))))
        (testing "a planned scenario is labelled planned"
          (let [r (first (filter #(= "Planned" (:name %)) written))]
            (is (some #{{:name "planned" :value "true"}} (:labels r))))))
      (finally (reset! runner/*registry* saved)))))

(deftest ambiguous-steps-are-broken
  (let [saved @runner/*registry*]
    (try
      (reset! runner/*registry* [])
      (runner/register! #"a spec" identity "one.clj" 1)
      (runner/register! #"a (\w+)" identity "two.clj" 1)
      (runner/register! #"a spec" identity "one.clj" 1)
      (is (= 2 (count @runner/*registry*)) "the same file and line replaces")
      (is (= 2 (count (runner/matching "a spec"))))
      (finally (reset! runner/*registry* saved)))))

(deftest finds-nothing-refuses
  (let [d (tmp)]
    (fs/create-dirs (fs/file d "spec"))
    (let [out (runner/run-features {:root d :spec-dir "spec" :steps-dir "steps" :results-dir "results"})]
      (is (str/includes? (:refused out) "no scenario was found"))
      (is (not (fs/exists? (fs/file d "results")))))))
