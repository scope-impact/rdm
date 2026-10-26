(ns rdm-spec.spec.parser-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]))

(def published-keys
  #{:id :title :name :keyword :file :line :level :needs :wip? :outline? :rows :examples :background :rule
    :steps :context :component :context-name :component-name})

(def feature
  (str "@UN-001\nFeature: F\n  A fixture.\n\n"
       "  Background:\n    Given a background\n\n"
       "  @spec-0001 @e2e\n  Scenario: One\n    Given x\n\n"
       "  Rule: R\n\n"
       "    @spec-0002 @contract @wip @UN-002\n    Scenario Outline: Two <n>\n      Given <n>\n\n"
       "      Examples:\n        | n |\n        | 1 |\n        | 2 |\n\n"
       "    @e2e @spec-0003 @spec-0004\n    Scenario: Three\n      Given y\n"))

(deftest reading-one-feature-file
  (let [{:keys [scenarios refusals feature]} (parser/parse-text "f.feature" feature {:prefix "spec"})
        [one two three] scenarios]
    (is (empty? refusals))
    (is (= "F" (:name feature)))
    (is (= ["spec-0001" "One" "One" "Scenario" "f.feature" 9 :e2e ["UN-001"] false false nil]
           ((juxt :id :title :name :keyword :file :line :level :needs :wip? :outline? :rule) one)))
    (is (= [{:keyword "Given" :text "a background"}] (:background one)))
    (is (= [{:keyword "Given" :text "x"}] (:steps one)))
    (testing "an outline, under a rule, planned"
      (is (= ["spec-0002" :contract ["UN-001" "UN-002"] true true "R"]
             ((juxt :id :level :needs :wip? :outline? :rule) two)))
      (is (= [{"n" "1"} {"n" "2"}] (:rows two)))
      (is (= ["n"] (:header (first (:examples two))))))
    (testing "two ids: no single id, both kept for the check"
      (is (nil? (:id three)))
      (is (= ["spec-0003" "spec-0004"] (:ids three))))))

(deftest tags
  (is (re-matches (parser/id-re "spec") "@spec-0001"))
  (is (not (re-matches (parser/id-re "spec") "@spec-01")))
  (is (re-matches parser/need-re "@UN-001"))
  (is (some #{"@e2e"} parser/levels)))

(deftest refusals-are-kept
  (let [{:keys [refusals]} (parser/parse-text "f.feature" "Feature: A\nFeature: B\n" {:prefix "spec"})]
    (is (= ["gherkin-one-feature"] (map :rule refusals)))
    (is (= "f.feature" (:file (first refusals))))))

(defn- with-project [f]
  (let [d (str (fs/create-temp-dir {:prefix "parser-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(defn- write! [d rel text]
  (let [f (fs/file d rel)] (fs/create-dirs (fs/parent f)) (spit (str f) text)))

(def model
  (pr-str {:system {:id :s :name "S" :description "d"}
           :containers [{:id :app :name "App" :kind :runs
                         :contexts [{:id :demo :name "Demo" :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha"}]}]}]
           :kernel [{:id :kernel/cli :name "CLI" :spec "kernel/cli"}]}))

(deftest reading-a-spec
  (with-project
    (fn [d]
      (write! d "dhf/spec/model.edn" model)
      (write! d "dhf/spec/needs.edn" "{\"UN-001\" {:text \"A need.\" :sources [\"s:p@1\"]}\n \"UN-002\" {:text \"Another.\" :sources []}}\n")
      (write! d "dhf/spec/demo/alpha.feature" feature)
      (write! d "dhf/spec/kernel/cli.feature" "@UN-001\nFeature: CLI\n\n  @spec-0009 @e2e\n  Scenario: Nine\n    Given z\n")
      (let [spec (parser/read-spec d {})
            by-id (into {} (map (juxt :id identity)) (:scenarios spec))]
        (is (= :working-tree (:revision spec)))
        (is (= [:demo :demo/alpha "Demo" "Alpha"]
               ((juxt :context :component :context-name :component-name) (by-id "spec-0001"))))
        (is (= [:kernel :kernel/cli "kernel" "CLI"]
               ((juxt :context :component :context-name :component-name) (by-id "spec-0009"))))
        (is (= ["UN-001" "UN-002"] (map :id (:needs spec))))
        (is (= {"UN-001" 1 "UN-002" 2} (:needs-lines spec)))
        (is (some #(= "dhf/spec/needs.edn" (:file %)) (:files spec)))
        (testing "SpecParsed carries exactly the declared keys of each scenario"
          (let [b (bus/make-bus)]
            (parser/publish! {:bus b} spec)
            (let [{:keys [event publisher payload]} (first (bus/events b))]
              (is (= [:SpecParsed :spec/parser] [event publisher]))
              (is (= #{:revision :scenarios :needs :refusals} (set (keys payload))))
              (is (every? #(= published-keys (set (keys %))) (:scenarios payload))))))))))

(deftest needs-that-cannot-be-read
  (with-project
    (fn [d]
      (write! d "dhf/spec/needs.edn" "not a map")
      (is (= ["needs-unreadable"] (map :rule (:needs-findings (parser/read-spec d {}))))))))

(defn- git! [d & args]
  (apply p/shell {:dir d :out :string :err :string
                  :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t.invalid"
                              "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t.invalid"}}
         "git" args))

(deftest reading-at-a-revision
  (with-project
    (fn [d]
      (write! d "dhf/spec/model.edn" model)
      (write! d "dhf/spec/demo/alpha.feature" "@UN-001\nFeature: A\n\n  @spec-0001 @e2e\n  Scenario: At the cut\n    Given x\n")
      (git! d "init" "-q")
      (git! d "add" "-A")
      (git! d "commit" "-q" "--no-verify" "-m" "cut")
      (git! d "tag" "spec-3")
      (write! d "dhf/spec/demo/alpha.feature" "@UN-001\nFeature: A\n\n  @spec-0002 @e2e\n  Scenario: Later\n    Given x\n")
      (let [at (parser/read-spec d {} "spec-3")]
        (is (= ["spec-0001"] (map :id (:scenarios at))))
        (is (re-matches #"[0-9a-f]{40}" (:revision at)))
        (is (= "Demo" (:context-name (first (:scenarios at))))))
      (is (nil? (parser/read-spec d {} "spec-9")) "a revision the repository does not hold reads nothing"))))
