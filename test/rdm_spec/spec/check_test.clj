(ns rdm-spec.spec.check-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(defn- spec-of
  "The spec the parser reads from feature texts {file text}, with `needs`."
  [files needs]
  (let [parsed (for [[f t] files] (parser/parse-file {:id-prefix "spec" :spec-dir "dhf/spec"} f t))]
    {:revision :working-tree
     :texts (into (sorted-map) files)
     :features (vec (keep :feature parsed))
     :scenarios (vec (mapcat :scenarios parsed))
     :needs needs :needs-file "dhf/spec/needs.edn" :needs-error nil
     :refusals (vec (mapcat :refusals parsed))}))

(def need [{:id "UN-001" :text "A need." :sources ["demo:README.md@abc1234"] :line 1}])

(defn- feature [& scenario-tags]
  (str "@UN-001\nFeature: Demo\n  A demo.\n\n  Rule: The rule\n"
       (str/join (map-indexed (fn [i tags] (str "\n    " tags "\n    Scenario: S" i "\n      Given a step\n"))
                              scenario-tags))))

(defn- rules [spec & {:as opts}]
  (set (map :rule (check/check-spec spec (merge {:prefix "spec" :ledger {:issued 100} :ledger-file "ids.edn"} opts)))))

(deftest a-clean-spec-has-no-finding
  (is (empty? (rules (spec-of {"dhf/spec/demo/a.feature" (feature "@spec-0001 @e2e" "@spec-0002 @contract @wip")} need)))))

(deftest ids
  (is (contains? (rules (spec-of {"a.feature" (feature "@e2e")} need)) "scenario-no-id"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001 @spec-0002 @e2e")} need)) "scenario-ids"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001 @e2e") "b.feature" (feature "@spec-0001 @e2e")} need))
                 "id-duplicate"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0101 @e2e")} need)) "id-not-issued"))
  (testing "an unreadable ledger is a finding, and no id is held to it"
    (let [rs (rules (spec-of {"a.feature" (feature "@spec-0101 @e2e")} need) :ledger {:error "gone"})]
      (is (contains? rs "ledger-unreadable"))
      (is (not (contains? rs "id-not-issued"))))))

(deftest a-duplicate-names-every-file
  (let [fs* (check/id-findings "spec" (:scenarios (spec-of {"a.feature" (feature "@spec-0001 @e2e")
                                                            "b.feature" (feature "@spec-0001 @e2e")} need)) 100)]
    (is (= 2 (count fs*)))
    (is (every? #(and (str/includes? (:message %) "a.feature") (str/includes? (:message %) "b.feature")) fs*))))

(deftest levels-and-tags
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001")} need)) "scenario-no-level"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001 @e2e @contract")} need)) "scenario-levels"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001 @e2e @persona")} need)) "tag-unknown"))
  (is (contains? (rules (spec-of {"a.feature" (str "@spec-0009 " (feature "@spec-0001 @e2e"))} need)) "tag-misplaced")))

(deftest needs
  (is (contains? (rules (spec-of {"a.feature" (str/replace (feature "@spec-0001 @e2e") "@UN-001\n" "")} need))
                 "scenario-no-need"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001 @e2e @UN-009")} need)) "need-undeclared"))
  (is (contains? (rules (spec-of {"a.feature" (feature "@spec-0001 @e2e")} [(assoc (first need) :sources [])]))
                 "need-no-source")))

(deftest stack-words-are-whole-words
  (let [fs* (check/stack-word-findings [{:file "a.feature" :text "one\nwritten in Cobalt.\nCobalty"}] ["Cobalt"])]
    (is (= [["a.feature" 2]] (map (juxt :file :line) fs*)))
    (is (str/includes? (:message (first fs*)) "Cobalt"))))

(deftest the-ledger
  (is (= {:issued 3} (check/read-ledger "ids.edn" "{:issued 3}")))
  (is (:error (check/read-ledger "ids.edn" nil)))
  (is (:error (check/read-ledger "ids.edn" "{:issued")))
  (is (:error (check/read-ledger "ids.edn" "[1]"))))
