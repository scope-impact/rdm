(ns rdm-spec.spec.parser-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]))

(def model
  {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser :name "Parser" :spec "spec/parser"}]}]}]
   :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus"}]})

(def feature
  (str "@UN-001\n"
       "Feature: Parser\n"
       "  Prose.\n\n"
       "  @spec-0001 @e2e\n"
       "  Scenario: Plain\n"
       "    Given a step\n\n"
       "  @UN-002\n"
       "  Rule: A rule\n\n"
       "    @spec-0002 @contract @wip @UN-003\n"
       "    Scenario Outline: Rows of <x>\n"
       "      Given <x>\n\n"
       "      Examples:\n"
       "        | x |\n"
       "        | a |\n"
       "        | b |\n\n"
       "    @spec-0003 @spec-0004\n"
       "    Scenario: Two ids\n"
       "      Given a step\n"))

(defn- project []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/file dir "dhf/spec/spec"))
    (fs/create-dirs (fs/file dir "dhf/spec/kernel"))
    (spit (str (fs/file dir "dhf/spec/spec/parser.feature")) feature)
    (spit (str (fs/file dir "dhf/spec/kernel/bus.feature"))
          "@UN-001\nFeature: Bus\n\n  @spec-0009 @e2e\n  Scenario: B\n    Given a step\n")
    (spit (str (fs/file dir "dhf/spec/model.edn")) (pr-str model))
    (spit (str (fs/file dir "dhf/spec/needs.edn"))
          "{\"UN-001\" {:text \"one\" :sources [\"a:b@1234567\"]}\n \"UN-002\" {:text \"two\"}}\n")
    dir))

(def opts {:spec-dir "dhf/spec" :id-prefix "spec" :model "dhf/spec/model.edn" :needs "dhf/spec/needs.edn"})

(deftest reads-every-scenario-with-its-tags-needs-level-and-owner
  (let [spec (parser/read-spec (project) opts)
        [bus plain outline two] (:scenarios spec)]
    (is (= :working-tree (:revision spec)))
    (is (= ["dhf/spec/kernel/bus.feature" "dhf/spec/spec/parser.feature"] (:files spec)))
    (is (= {:id "spec-0009" :context :kernel :component :kernel/bus}
           (select-keys bus [:id :context :component])))
    (is (= {:id "spec-0001" :ids ["spec-0001"] :title "Plain" :file "dhf/spec/spec/parser.feature" :line 6
            :level :e2e :needs ["UN-001"] :wip? false :outline? false :context :spec :component :spec/parser
            :spec-path "spec/parser"}
           (select-keys plain [:id :ids :title :file :line :level :needs :wip? :outline? :context :component :spec-path])))
    (is (= [{:keyword "Given" :text "a step" :line 7}] (:steps plain)))
    (testing "an outline: its rows, own, rule and feature needs, @wip"
      (is (= ["UN-003" "UN-002" "UN-001"] (:needs outline)))
      (is (:wip? outline))
      (is (:outline? outline))
      (is (= :contract (:level outline)))
      (is (= "A rule" (:rule outline)))
      (is (= [{:line 18 :values {"x" "a"}} {:line 19 :values {"x" "b"}}] (:rows outline))))
    (testing "two ids: no one :id, both in :ids, no level"
      (is (nil? (:id two)))
      (is (= ["spec-0003" "spec-0004"] (:ids two)))
      (is (nil? (:level two))))
    (is (= [{:id "UN-001" :text "one" :sources ["a:b@1234567"] :line 1}
            {:id "UN-002" :text "two" :sources [] :line 2}]
           (:needs spec)))
    (is (= [] (:refusals spec)))))

(deftest a-construct-outside-the-subset-is-refused-with-its-line
  (let [dir (project)]
    (spit (str (fs/file dir "dhf/spec/spec/parser.feature"))
          "@UN-001\nFeature: P\n\n  @spec-0001 @e2e\n  Scenario Template: X\n    Given a\n")
    (let [spec (parser/read-spec dir opts)
          [r] (:refusals spec)]
      (is (= ["dhf/spec/spec/parser.feature" 5 "gherkin-unknown-keyword"] [(:file r) (:line r) (:rule r)]))
      (is (str/includes? (:message r) "Scenario Template"))
      (is (= [{:file "dhf/spec/spec/parser.feature" :line 5 :rule "gherkin-unknown-keyword"
               :message (:message r) :severity :error}]
             (take 1 (parser/refusal-findings spec)))))))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                                      "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"}}
                 "git" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" "-c" "core.hooksPath=/dev/null" args)]
    (assert (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(deftest the-spec-is-read-at-a-revision-not-from-the-working-tree
  (let [dir (project)]
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "cut")
    (git! dir "tag" "spec-3")
    (let [sha (git! dir "rev-parse" "HEAD")]
      ;; after the cut: a new feature file and a changed one
      (spit (str (fs/file dir "dhf/spec/spec/extra.feature"))
            "@UN-001\nFeature: E\n\n  @spec-0010 @e2e\n  Scenario: New\n    Given a\n")
      (fs/delete (fs/file dir "dhf/spec/kernel/bus.feature"))
      (let [at (parser/read-spec dir (assoc opts :revision "spec-3"))
            now (parser/read-spec dir opts)]
        (is (= sha (:revision at)))
        (is (= #{"spec-0001" "spec-0002" "spec-0009"} (set (keep :id (:scenarios at)))))
        (is (= #{"spec-0001" "spec-0002" "spec-0010"} (set (keep :id (:scenarios now)))))
        (is (= :spec/parser (:component (first (filter #(= "spec-0001" (:id %)) (:scenarios at))))))
        (is (= 2 (count (:needs at))))
        (is (some #(= "dhf/spec/kernel/bus.feature" (:file %)) (parser/text-files dir at)))))))

(deftest the-stage-publishes-spec-parsed-at-the-cut-a-checklist-names
  (let [dir (project)]
    (spit (str (fs/file dir "dhf/spec/spec/parser.feature")) "Feature: refused twice\nFeature: again\n")
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "cut")
    (git! dir "tag" "spec-3")
    (spit (str (fs/file dir "dhf/spec/spec/parser.feature")) feature)
    (fs/create-dirs (fs/file dir "dhf/cycles/03"))
    (spit (str (fs/file dir "dhf/cycles/03/cycle.edn")) (pr-str {:cut "spec-3"}))
    (let [catalogue {:SpecParsed {:published-by :spec/parser :carries [:revision :scenarios :needs :refusals]}}
          seen (atom [])
          b (bus/make-bus {:catalogue catalogue})
          req (fn [task args] {:task task :args args :root dir :bus b :state (atom {})
                               :config (merge opts {:cycles-dir "dhf/cycles"})})]
      (bus/subscribe! b :SpecParsed :t/t #(swap! seen conj %))
      (testing "checklist 03 reads spec-3, and leaves its refusals to the checklist"
        (is (= [] (:findings (parser/parse (req "checklist" ["03"])))))
        (is (= (git! dir "rev-parse" "spec-3") (:revision (last @seen))))
        (is (seq (:refusals (last @seen)))))
      (testing "status reads the working tree"
        (is (= [] (:findings (parser/parse (req "status" [])))))
        (is (= :working-tree (:revision (last @seen))))
        (is (= #{:revision :scenarios :needs :refusals} (set (keys (last @seen)))))))))
