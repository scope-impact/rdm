(ns rdm-spec.spec.check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.spec.check :as check]))

(defn- sc [m]
  (merge {:file "dhf/spec/a/b.feature" :line 5 :title "A scenario" :ids ["spec-0001"] :id "spec-0001"
          :tags ["@spec-0001" "@e2e"] :levels ["@e2e"] :needs ["UN-001"]}
         m))

(defn- rules [fs*] (mapv :rule fs*))

(deftest one-id-per-scenario
  (is (= ["scenario-no-id"] (rules (check/id-findings "spec" [(sc {:ids [] :id nil})] 10))))
  (is (str/includes? (:message (first (check/id-findings "spec" [(sc {:ids [] :id nil})] 10))) "rdm-spec stamp"))
  (is (= ["scenario-two-ids"] (rules (check/id-findings "spec" [(sc {:ids ["spec-0007" "spec-0008"] :id nil})] 10))))
  (let [fs* (check/id-findings "spec" [(sc {}) (sc {:file "dhf/spec/c/d.feature"})] 10)]
    (is (= ["id-duplicate"] (rules fs*)))
    (is (every? #(str/includes? (:message (first fs*)) %) ["dhf/spec/a/b.feature" "dhf/spec/c/d.feature" "spec-0001"])))
  (is (= ["id-not-issued"] (rules (check/id-findings "spec" [(sc {:ids ["spec-0901"]})] 273))))
  (is (empty? (check/id-findings "spec" [(sc {})] nil))))

(deftest one-level-per-scenario
  (is (empty? (check/level-findings [(sc {})])))
  (is (= ["scenario-no-level"] (rules (check/level-findings [(sc {:levels []})]))))
  (is (= ["scenario-two-levels"] (rules (check/level-findings [(sc {:levels ["@e2e" "@contract"]})])))))

(deftest only-known-tags-where-they-go
  (let [[f] (check/tag-findings "spec" [] [(sc {:tags ["@spec-0001" "@e2e" "@persona"]})])]
    (is (= "tag-unknown" (:rule f)))
    (is (every? #(str/includes? (:message f) %) ["spec-0001" "@persona" "@e2e" "@contract" "@UN-" "@wip"])))
  (is (= ["tag-misplaced"] (rules (check/tag-findings "spec" [{:file "f" :line 1 :name "F" :tags ["@UN-1" "@wip"]}] []))))
  (is (empty? (check/tag-findings "spec" [{:file "f" :line 1 :name "F" :tags ["@UN-1"]}]
                                  [(sc {:tags ["@spec-0001" "@contract" "@wip" "@UN-002"]})]))))

(deftest needs
  (let [needs [{:id "UN-001" :sources ["a:b@abcd"] :line 2} {:id "UN-004" :sources [] :line 3}]]
    (is (= ["need-no-source"] (rules (check/need-findings [(sc {})] needs "n.edn" nil))))
    (is (= #{"scenario-no-need" "need-undeclared" "need-no-source"}
           (set (rules (check/need-findings [(sc {:needs []}) (sc {:needs ["UN-009"]})] needs "n.edn" nil)))))
    (is (= ["needs-unreadable"] (rules (check/need-findings [] [] "n.edn" "missing"))))))

(deftest stack-words
  (let [fs* (check/stack-word-findings [{:file "f.feature" :text "Feature: F\n  Written in cobalt.\n  Cobaltic.\n"}]
                                       ["Cobalt" ""])]
    (is (= [["f.feature" 2 "spec-stack-word"]] (map (juxt :file :line :rule) fs*)))
    (is (str/includes? (:message (first fs*)) "Cobalt"))))
