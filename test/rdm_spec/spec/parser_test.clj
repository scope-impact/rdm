(ns rdm-spec.spec.parser-test
  "One test per rule the parser enforces (dhf/spec/02-write-the-spec/parser.feature,
  dhf/spec/12-several-specs/parser.feature, dhf/spec/13-slice-development/model.feature)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [babashka.fs :as fs]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.kernel.project]))

(def every-construct
  (str/join "\n"
            ["# a comment"
             "@UN-001"
             "Feature: Every construct"
             "  A description."
             ""
             "  Background:"
             "    Given a background step"
             ""
             "  Rule: A rule"
             ""
             "    @spec-0001 @e2e"
             "    Scenario: A scenario"
             "      Given a table"
             "        | a | b |"
             "        | 1 | 2 |"
             "      When a doc string"
             "        \"\"\""
             "        text"
             "        \"\"\""
             "      Then it holds"
             "      And it holds again"
             "      But not this"
             ""
             "    @spec-0002 @contract"
             "    Scenario Outline: An outline <x>"
             "      Given <x>"
             ""
             "      Examples:"
             "        | x |"
             "        | 1 |"
             "        | 2 |"]))

(deftest the-declared-subset-is-read
  (let [{:keys [scenarios refusals]} (parser/parse-feature every-construct "f.feature" "spec")]
    (is (empty? refusals))
    (is (= ["spec-0001" "spec-0002"] (map :id scenarios)))
    (is (= "A rule" (:rule (first scenarios))))
    (is (= ["UN-001" "spec-0001" "e2e"] (:tags (first scenarios))))
    (is (= [["a" "b"] ["1" "2"]] (-> scenarios first :steps first :arg :table)))
    (is (= "text" (-> scenarios first :steps second :arg :doc-string)))
    (is (= [{:keyword "Given" :text "a background step"}] (map #(select-keys % [:keyword :text]) (:background (first scenarios)))))
    (is (= [{"x" "1"} {"x" "2"}] (map :values (:rows (second scenarios)))))))

(deftest an-unknown-keyword-is-refused-with-its-line
  (let [{:keys [refusals]} (parser/parse-feature "Feature: F\n\n  Scenario Template: x\n    Given y\n" "f.feature" "spec")]
    (is (= 3 (:line (first refusals))))
    (is (str/includes? (:message (first refusals)) "Scenario Template"))))

(deftest a-feature-file-holds-one-feature
  (let [{:keys [refusals]} (parser/parse-feature "Feature: F\n\nFeature: G\n" "f.feature" "spec")]
    (is (= [3] (map :line refusals)))))

(deftest the-spec-is-read-from-a-directory-at-a-revision
  (let [dir (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))})]
    (try
      (fs/create-dirs (fs/path dir "dhf/spec/a"))
      (spit (str (fs/path dir "dhf/spec/a/b.feature")) "@UN-001\nFeature: B\n\n  @spec-0001 @e2e\n  Scenario: One\n    Given x\n")
      (spit (str (fs/path dir "dhf/spec/model.edn")) "{:system {:id :s :name \"s\" :description \"d\"} :containers [{:id :c :name \"c\" :kind :runs :description \"d\" :contexts [{:id :a :name \"a\" :description \"d\" :components [{:id :a/b :name \"B\" :description \"d\" :spec \"a/b\"}]}]}]}")
      (let [spec (first (:specs (parser/read-specs (str dir) (rdm-spec.kernel.project/with-defaults {}))))]
        (is (= ["spec-0001"] (map :id (:scenarios spec))))
        (is (= :a/b (:component (first (:scenarios spec)))))
        (is (= "dhf/spec/a/b.feature" (:file (first (:scenarios spec))))))
      (finally (fs/delete-tree dir)))))

(deftest several-specs-are-read-one-folder-each
  (is (= "a spec's name is declared once"
         (parser/specs-problem [{:name "auth" :dir "a" :id-prefix "a"} {:name "auth" :dir "b" :id-prefix "b"}])))
  (is (= "an id prefix is declared once, so no two scenarios share an id"
         (parser/specs-problem [{:name "a" :dir "a" :id-prefix "x"} {:name "b" :dir "b" :id-prefix "x"}])))
  (is (= "a folder holds one spec"
         (parser/specs-problem [{:name "a" :dir "d" :id-prefix "a"} {:name "b" :dir "d" :id-prefix "b"}]))))

(deftest under-the-slices-layout-a-file-is-found-by-its-component-name
  (let [model {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser :name "Parser"}]}]}]
               :kernel [{:id :kernel/bus :name "Event bus"}]}]
    (is (= :spec/parser (parser/owner :slices model "12-several-specs/parser.feature")))
    (is (= :kernel/bus (parser/owner :slices model "08-plugins/bus.feature")))
    (is (nil? (parser/owner :slices model "parser.feature")))))
