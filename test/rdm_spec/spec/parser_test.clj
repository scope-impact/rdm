(ns rdm-spec.spec.parser-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.spec.parser :as parser]))

(def text
  (str "@UN-001\nFeature: Desk\n  A fixture.\n\n"
       "  @UN-002\n  Rule: A rule\n\n"
       "    @spec-0001 @e2e @wip\n    Scenario: One\n      Given a desk\n\n"
       "    @spec-0002 @spec-0003 @e2e @contract\n    Scenario Outline: Two <n>\n      Given <n>\n\n"
       "      Examples:\n        | n |\n        | 1 |\n        | 2 |\n"))

(deftest a-scenario-as-specparsed-carries-it
  (let [{:keys [scenarios refusals]} (parser/parse-text "dhf/spec/a/desk.feature" text
                                                        {:prefix "spec" :owner {:context :a :component :a/desk
                                                                                :context-name "a" :component-name "Desk"}})
        [one two] scenarios]
    (is (empty? refusals))
    (is (= "spec-0001" (:id one)))
    (is (= :e2e (:level one)))
    (is (:wip? one))
    (is (= ["UN-001" "UN-002"] (:needs one)))
    (is (= "A rule" (:rule one)))
    (is (= 9 (:line one)))
    (testing "two ids or two levels: no single one"
      (is (nil? (:id two)))
      (is (= ["spec-0002" "spec-0003"] (:ids two)))
      (is (nil? (:level two)))
      (is (= [:e2e :contract] (:levels two))))
    (is (:outline? two))
    (is (= 2 (count (:rows two))))
    (is (= "Desk" (:component-name two)))))

(deftest the-prefix-is-configuration
  (let [sc (first (:scenarios (parser/parse-text "f.feature" "@UN-001\nFeature: F\n  @loyly-0001 @e2e\n  Scenario: S\n    Given x\n"
                                                 {:prefix "loyly"})))]
    (is (= "loyly-0001" (:id sc))))
  (is (re-matches (parser/id-re "spec") "@spec-12345"))
  (is (not (re-matches (parser/id-re "spec") "@spec-123"))))

(deftest owners-come-from-the-model
  (let [m {:containers [{:contexts [{:id :spec :name "spec" :components [{:id :spec/parser :name "Parser" :spec "spec/parser"}]}]}]
           :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus"}]}
        o (parser/owners m)]
    (is (= {:context :spec :component :spec/parser :context-name "spec" :component-name "Parser"} (o "spec/parser")))
    (is (= :kernel (:context (o "kernel/bus"))))
    (is (= {:context :x :component :x/y :context-name "x" :component-name "y"}
           (parser/owner-of o "dhf/spec" "dhf/spec/x/y.feature")))))

(deftest needs-are-read-and-a-broken-file-is-a-finding
  (let [r (parser/read-needs "n.edn" "{\"UN-002\" {:text \"b\" :sources []}\n \"UN-001\" {:text \"a\" :sources [\"s:p@1234\"]}}")]
    (is (= ["UN-001" "UN-002"] (map :id (:needs r))))
    (is (= {"UN-002" 1 "UN-001" 2} (:lines r)))
    (is (empty? (:findings r))))
  (is (= "needs-unreadable" (:rule (first (:findings (parser/read-needs "n.edn" nil))))))
  (is (= "needs-unreadable" (:rule (first (:findings (parser/read-needs "n.edn" "{\"UN-001\"")))))))

(deftest the-spec-is-read-from-the-working-tree-and-published
  (let [dir (str (fs/create-temp-dir {:prefix "parser-test-"}))]
    (try
      (spit (str (fs/file dir "x")) "")
      (fs/create-dirs (fs/file dir "dhf/spec/a"))
      (spit (str (fs/file dir "dhf/spec/a/desk.feature")) text)
      (spit (str (fs/file dir "dhf/spec/needs.edn")) "{\"UN-001\" {:text \"a\" :sources [\"s:p@1234\"]}}")
      (let [b (bus/make-bus)
            req {:root dir :config {} :bus b :task "status" :args []}]
        (parser/parse req)
        (let [[e] (bus/events b)]
          (is (= :SpecParsed (:event e)))
          (is (= :spec/parser (:publisher e)))
          (is (= :working-tree (get-in e [:payload :revision])))
          (is (= 2 (count (get-in e [:payload :scenarios]))))
          (is (= #{:revision :scenarios :needs :refusals} (set (keys (:payload e)))))))
      (finally (fs/delete-tree dir)))))
