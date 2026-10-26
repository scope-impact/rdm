(ns rdm-spec.spec.parser-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.spec.parser :as parser]))

(def feature
  (str "@UN-001\nFeature: Desk\n  A fixture.\n\n"
       "  Background:\n    Given a desk\n\n"
       "  @spec-0001 @e2e\n  Scenario: One\n    Given a thing\n    Then it holds\n\n"
       "  @UN-002\n  Rule: A rule\n\n"
       "    @spec-0002 @spec-0003 @contract @wip\n    Scenario Outline: Two <n>\n      Given <n>\n\n"
       "      Examples:\n        | n |\n        | 1 |\n        | 2 |\n\n"
       "    @e2e\n    Scenario: Three\n      Given nothing\n"))

(deftest a-feature-file-is-read-into-scenarios
  (let [{:keys [scenarios refusals feature file]} (parser/parse-text "dhf/spec/kernel/desk.feature" feature {:prefix "spec"})
        [one two three] scenarios]
    (is (empty? refusals))
    (is (= "dhf/spec/kernel/desk.feature" file))
    (is (= "Desk" (:name feature)))
    (testing "ids"
      (is (= ["spec-0001"] (:ids one)))
      (is (= "spec-0001" (:id one)))
      (is (= ["spec-0002" "spec-0003"] (:ids two)))
      (is (nil? (:id two)))
      (is (= [] (:ids three))))
    (testing "levels, needs, planned work and rules"
      (is (= [:e2e] (:levels one)))
      (is (= :e2e (:level one)))
      (is (= :contract (:level two)))
      (is (= ["UN-001"] (:needs one)))
      (is (= ["UN-001" "UN-002"] (:needs two)))
      (is (:wip? two))
      (is (not (:wip? one)))
      (is (nil? (:rule one)))
      (is (= "A rule" (:rule two))))
    (testing "the scenario as written"
      (is (= "One" (:title one) (:name one)))
      (is (= "Scenario" (:keyword one)))
      (is (= "Scenario Outline" (:keyword two)))
      (is (:outline? two))
      (is (= [{:line 22 :values {"n" "1"}} {:line 23 :values {"n" "2"}}] (:rows two)))
      (is (= [{:keyword "Given" :text "a desk"}] (map #(select-keys % [:keyword :text]) (:background one))))
      (is (= [{:keyword "Given" :text "a thing"} {:keyword "Then" :text "it holds"}]
             (map #(select-keys % [:keyword :text]) (:steps one))))
      (is (= 9 (:line one)))
      (is (every? #(= "dhf/spec/kernel/desk.feature" (:file %)) scenarios)))))

(deftest refusals-are-kept
  (let [{:keys [refusals]} (parser/parse-text "a.feature" "Feature: A\n\n  Scenario Template: x\n" {:prefix "spec"})]
    (is (= ["gherkin-unknown-keyword"] (map :rule refusals)))
    (is (= [3] (map :line refusals)))))

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(def model
  (pr-str {:system {:id :s :name "S" :description "d"}
           :containers [{:id :app :name "app" :kind :runs
                         :contexts [{:id :desks :name "desks" :components [{:id :desks/desk :name "Desk" :spec "desks/desk"}]}]}]
           :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus"}]}))

(deftest the-spec-is-read-with-its-owners-and-needs
  (let [dir (str (fs/create-temp-dir {:prefix "parser-test-"}))]
    (try
      (write! dir "dhf/spec/model.edn" model)
      (write! dir "dhf/spec/needs.edn" "{\"UN-001\" {:text \"A need.\" :sources [\"s:p@1234567\"]}\n \"UN-002\" {:text \"B\" :sources []}}\n")
      (write! dir "dhf/spec/desks/desk.feature" feature)
      (write! dir "dhf/spec/kernel/bus.feature" "@UN-001\nFeature: Bus\n\n  @spec-0009 @e2e\n  Scenario: Nine\n    Given x\n")
      (let [spec (parser/read-spec dir {:id-prefix "spec"})
            by-title (into {} (map (juxt :title identity)) (:scenarios spec))]
        (is (= :working-tree (:revision spec)))
        (is (= {:context :desks :component :desks/desk :context-name "desks" :component-name "Desk"}
               (select-keys (by-title "One") [:context :component :context-name :component-name])))
        (is (= {:context :kernel :component :kernel/bus :context-name "kernel" :component-name "Event bus"}
               (select-keys (by-title "Nine") [:context :component :context-name :component-name])))
        (is (= [{:id "UN-001" :text "A need." :sources ["s:p@1234567"]} {:id "UN-002" :text "B" :sources []}]
               (:needs spec)))
        (is (= {"UN-001" 1 "UN-002" 2} (:needs-lines spec)))
        (is (some #(= "dhf/spec/model.edn" (:file %)) (:files spec))))
      (testing "the spec at a commit, not the working tree"
        (let [git #(apply p/shell {:dir dir :out :string :err :string :continue true
                                   :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t.invalid"
                                               "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t.invalid"}}
                          "git" %&)]
          (git "init" "-q")
          (git "add" "-A")
          (git "commit" "-q" "--no-verify" "-m" "c")
          (let [commit (str/trim (:out (git "rev-parse" "HEAD")))]
            (fs/delete (fs/file dir "dhf/spec/kernel/bus.feature"))
            (is (= 3 (count (:scenarios (parser/read-spec dir {:id-prefix "spec"})))))
            (let [at (parser/read-spec dir {:id-prefix "spec"} {:revision commit})]
              (is (= commit (:revision at)))
              (is (= 4 (count (:scenarios at))))))))
      (testing "needs that cannot be read are a finding"
        (write! dir "dhf/spec/needs.edn" "[not a map")
        (is (= ["needs-unreadable"] (map :rule (:needs-findings (parser/read-spec dir {:id-prefix "spec"}))))))
      (finally (fs/delete-tree dir)))))
