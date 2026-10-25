(ns rdm-spec.spec.parser-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]))

(def model
  (pr-str {:containers [{:id :c :contexts [{:id :alpha :name "alpha"
                                             :components [{:id :alpha/one :name "One" :spec "alpha/one"}]}]}]
           :kernel [{:id :kernel/runner :name "Gherkin runner" :spec "kernel/runner"}]}))

(def needs-text ";; needs\n{\"UN-001\" {:text \"one\" :sources [\"a:b@1234567\"]}\n \"UN-002\" {:text \"two\"}}\n")

(def feature-one
  (str "@UN-001\nFeature: One\n  Prose.\n\n"
       "  @UN-002 @wip\n  Rule: R\n\n"
       "    @spec-0001 @e2e\n    Scenario: Plain\n      Given a\n      Then b\n\n"
       "    @spec-0002 @spec-0003 @contract @e2e\n    Scenario Outline: Outline <x>\n      Given <x>\n\n"
       "      Examples:\n        | x |\n        | 1 |\n        | 2 |\n"))

(def feature-kernel "@UN-001\nFeature: Runner\n\n  @spec-0010 @contract\n  Scenario: K\n    Given k\n")

(defn- write! [dir rel text]
  (let [f (fs/file dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- project [files]
  (let [dir (str (fs/create-temp-dir {:prefix "parser-test-"}))]
    (doseq [[rel text] files] (write! dir rel text))
    dir))

(def config {:spec-dir "dhf/spec" :id-prefix "spec" :model "dhf/spec/model.edn" :needs "dhf/spec/needs.edn"})

(defn- spec-of [files] (parser/read-spec (project files) config))

(def default-files
  {"dhf/spec/model.edn" model
   "dhf/spec/needs.edn" needs-text
   "dhf/spec/alpha/one.feature" feature-one
   "dhf/spec/kernel/runner.feature" feature-kernel
   "dhf/spec/README.md" "# The spec\n"})

(deftest tags
  (is (re-matches (parser/id-pattern "spec") "@spec-0001"))
  (is (re-matches (parser/id-pattern "spec") "@spec-12345"))
  (is (not (re-matches (parser/id-pattern "spec") "@spec-12")))
  (is (re-matches (parser/id-pattern "loyly") "@loyly-0001"))
  (is (re-matches parser/need-pattern "@UN-001"))
  (is (= #{"@e2e" "@contract"} parser/level-tags))
  (is (= "@wip" parser/wip-tag)))

(deftest a-scenario-carries-its-id-tags-needs-and-owner
  (let [spec (spec-of default-files)
        [plain outline kernel] (:scenarios spec)]
    (is (= :working-tree (:revision spec)))
    (is (= [] (:refusals spec)))
    (testing "a plain scenario"
      (is (= {:id "spec-0001" :ids ["spec-0001"] :title "Plain" :name "Plain" :file "dhf/spec/alpha/one.feature"
              :line 9 :keyword "Scenario" :outline? false :level :e2e :levels ["@e2e"]
              :needs ["UN-001" "UN-002"] :wip? true :rule "R" :rule-line 6 :rule-tags ["@UN-002" "@wip"]
              :context :alpha :component :alpha/one :context-name "alpha" :component-name "One"
              :rows nil :feature "One"}
             (select-keys plain [:id :ids :title :name :file :line :keyword :outline? :level :levels :needs :wip?
                                 :rule :rule-line :rule-tags :context :component :context-name :component-name
                                 :rows :feature])))
      (is (= [{:keyword "Given" :text "a"} {:keyword "Then" :text "b"}]
             (map #(select-keys % [:keyword :text]) (:steps plain))))
      (is (= ["@spec-0001" "@e2e"] (:tags plain))))
    (testing "an outline with two ids and two levels has no single id or level"
      (is (nil? (:id outline)))
      (is (= ["spec-0002" "spec-0003"] (:ids outline)))
      (is (nil? (:level outline)))
      (is (= ["@contract" "@e2e"] (:levels outline)))
      (is (:outline? outline))
      (is (= [{:line 19 :values {"x" "1"}} {:line 20 :values {"x" "2"}}] (:rows outline)))
      (is (= 17 (:line (first (:examples outline))))))
    (testing "a kernel scenario"
      (is (= [:kernel :kernel/runner "kernel" "Gherkin runner"]
             ((juxt :context :component :context-name :component-name) kernel)))
      (is (= :contract (:level kernel)))
      (is (false? (:wip? kernel))))
    (is (= [{:file "dhf/spec/alpha/one.feature" :line 2 :name "One" :tags ["@UN-001"]}
            {:file "dhf/spec/kernel/runner.feature" :line 2 :name "Runner" :tags ["@UN-001"]}]
           (map #(select-keys % [:file :line :name :tags]) (:features spec))))))

(deftest needs-are-read-with-their-lines
  (let [spec (spec-of default-files)]
    (is (= [{:id "UN-001" :text "one" :sources ["a:b@1234567"] :line 2}
            {:id "UN-002" :text "two" :sources [] :line 3}]
           (:needs spec)))
    (is (nil? (:needs-error spec)))
    (is (= "dhf/spec/needs.edn" (:needs-file spec))))
  (is (str/includes? (:needs-error (spec-of (dissoc default-files "dhf/spec/needs.edn"))) "does not exist"))
  (is (str/includes? (:needs-error (spec-of (assoc default-files "dhf/spec/needs.edn" "{"))) "not readable"))
  (is (str/includes? (:needs-error (spec-of (assoc default-files "dhf/spec/needs.edn" "[]"))) "not a map")))

(deftest without-a-model-the-owner-comes-from-the-path
  (let [spec (spec-of (dissoc default-files "dhf/spec/model.edn"))]
    (is (= [:alpha :alpha/one "alpha" "one"]
           ((juxt :context :component :context-name :component-name) (first (:scenarios spec))))))
  (testing "a file the model names no owner of"
    (let [spec (spec-of (assoc default-files "dhf/spec/beta/two.feature" feature-kernel))
          sc (first (filter #(= "dhf/spec/beta/two.feature" (:file %)) (:scenarios spec)))]
      (is (nil? (:component sc)))
      (is (nil? (:context sc))))))

(deftest every-file-of-the-spec-is-a-text-file
  (let [dir (project default-files)
        spec (parser/read-spec dir config)]
    (is (= ["dhf/spec/README.md" "dhf/spec/alpha/one.feature" "dhf/spec/kernel/runner.feature"
            "dhf/spec/model.edn" "dhf/spec/needs.edn"]
           (map :file (parser/text-files dir spec))))
    (is (= feature-one (:text (second (parser/text-files dir spec)))))))

(deftest refusals-are-named-by-file-line-and-rule
  (let [spec (spec-of (assoc default-files "dhf/spec/alpha/one.feature"
                             "@UN-001\nFeature: A\n\n  Scenario Template: X\n    Given a\nFeature: B\n"))
        fs (parser/refusal-findings spec)]
    (is (every? (set (map (juxt :file :line :rule) fs))
                [["dhf/spec/alpha/one.feature" 4 "gherkin-unknown-keyword"]
                 ["dhf/spec/alpha/one.feature" 6 "gherkin-one-feature"]]))
    (is (some #(str/includes? (:message %) "Scenario Template") fs))
    (is (every? #(= :error (:severity %)) fs))))

(def git-env
  {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
   "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.invalid"
   "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.invalid"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir dir :out :string :err :string :continue true :extra-env git-env :in ""}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (is (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(deftest the-spec-is-read-at-a-revision
  (let [dir (project default-files)]
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "cut")
    (git! dir "tag" "spec-1")
    (let [sha (git! dir "rev-parse" "HEAD")]
      (write! dir "dhf/spec/alpha/two.feature" feature-kernel)
      (write! dir "dhf/spec/alpha/one.feature" "@UN-001\nFeature: Changed\n")
      (let [at-cut (parser/read-spec dir (assoc config :revision "spec-1"))
            tree (parser/read-spec dir config)]
        (is (= sha (:revision at-cut)))
        (is (= ["spec-0001" "spec-0010"] (keep :id (:scenarios at-cut))))
        (is (= :alpha/one (:component (first (:scenarios at-cut)))))
        (is (= 2 (count (:needs at-cut))))
        (is (= ["spec-0010" "spec-0010"] (keep :id (:scenarios tree))))))
    (is (thrown? clojure.lang.ExceptionInfo (parser/read-spec dir (assoc config :revision "spec-9"))))))

(defn- req [dir task args extra-config]
  {:task task :args args :opts {} :root dir :config (merge config {:cycles-dir "dhf/cycles"} extra-config)
   :bus (bus/make-bus {:catalogue {:SpecParsed {:published-by :spec/parser
                                                :carries [:revision :scenarios :needs :refusals]}}})
   :state (atom {})})

(deftest the-stage-leaves-the-spec-in-the-state-and-publishes-spec-parsed
  (let [dir (project default-files)
        r (req dir "status" [] {})
        out (parser/parse r)
        [e] (bus/events (:bus r))]
    (is (= [] (:findings out)))
    (is (= 3 (count (:scenarios (::parser/spec @(:state r))))))
    (is (= [:SpecParsed :spec/parser] [(:event e) (:publisher e)]))
    (is (= #{:revision :scenarios :needs :refusals} (set (keys (:payload e)))))
    (is (= [{:id "UN-001" :text "one" :sources ["a:b@1234567"]} {:id "UN-002" :text "two" :sources []}]
           (:needs (:payload e))))))

(deftest the-checklist-reads-the-spec-at-its-cycles-cut
  (let [dir (project default-files)]
    (git! dir "init" "-q")
    (git! dir "add" "-A")
    (git! dir "commit" "-q" "-m" "cut")
    (git! dir "tag" "spec-3")
    (write! dir "dhf/spec/alpha/two.feature" feature-kernel)
    (write! dir "dhf/cycles/03/cycle.edn" "{:cut \"spec-3\"}")
    (let [r (req dir "checklist" ["03"] {})]
      (parser/parse r)
      (is (= 3 (count (:scenarios (::parser/spec @(:state r)))))))
    (testing "a cut the repository does not hold is left to the task"
      (write! dir "dhf/cycles/04/cycle.edn" "{:cut \"spec-9\"}")
      (let [r (req dir "checklist" ["04"] {})]
        (is (= [] (:findings (parser/parse r))))
        (is (nil? (::parser/spec @(:state r))))
        (is (empty? (bus/events (:bus r))))))
    (testing "a cycle naming no cut reads the working tree; the task refuses it"
      (let [r (req dir "checklist" ["05"] {})]
        (parser/parse r)
        (is (= :working-tree (:revision (::parser/spec @(:state r)))))))))
