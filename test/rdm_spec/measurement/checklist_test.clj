(ns rdm-spec.measurement.checklist-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.checklist :as checklist]))

(def scenarios
  [{:id "spec-0014" :title "b" :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint" :level :contract}
   {:id "spec-0012" :title "a" :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint" :level :e2e}
   {:id "spec-0013" :title "w" :context :architecture :component :architecture/lint :wip? true}
   {:id "spec-0031" :title "v" :context :verification :component :verification/status
    :context-name "verification" :component-name "Status" :level :e2e}])

(def clauses
  [{:key "STD:4" :description "General" :clause-list "dhf/clauses/std.txt"}
   {:key "STD:4" :description "General again" :clause-list "dhf/clauses/part.txt"}
   {:key "STD:5.1" :description "Plan" :clause-list "dhf/clauses/std.txt"}])

(deftest scopes
  (is (nil? (checklist/scope-parts :project)))
  (is (nil? (checklist/scope-parts nil)))
  (is (= [{:context :verification}] (checklist/scope-parts {:context :verification})))
  (is (= [{:component :spec/parser}] (checklist/scope-parts {:component :spec/parser})))
  (is (= [{:context :knowledge} {:component :spec/parser}]
         (checklist/scope-parts [{:context :knowledge} {:component :spec/parser}])))
  (testing "another shape is invalid"
    (doseq [s [:everything {:context "verification"} {:component :parser} [] {:context :a :component :a/b} 3]]
      (is (= :invalid (checklist/scope-parts s)) (pr-str s)))))

(deftest items-sorted-by-id-planned-apart
  (let [cl (checklist/derive-checklist "03" "spec-3" scenarios nil nil)]
    (is (= ["cycle" "cut" "items" "planned"] (keys cl)))
    (is (= ["spec-0012" "spec-0014" "spec-0031"] (map #(get % "id") (get cl "items"))))
    (is (= ["id" "title" "context" "component" "level"] (keys (first (get cl "items")))))
    (is (= [{"id" "spec-0013" "title" "w"}] (get cl "planned")))
    (is (= {"id" "spec-0014" "title" "b" "context" "architecture" "component" "Lint" "level" "contract"}
           (second (get cl "items"))))))

(deftest a-scope-keeps-its-own
  (let [cl (checklist/derive-checklist "03" "spec-3" scenarios clauses [{:context :verification}])]
    (is (= ["spec-0031"] (map #(get % "id") (get cl "items"))))
    (is (= [] (get cl "planned")))))

(deftest clauses-follow-scenarios-each-key-once
  (let [cl (checklist/derive-checklist "03" "spec-3" scenarios clauses nil)
        items (get cl "items")]
    (is (= ["spec-0012" "spec-0014" "spec-0031" "STD:4" "STD:5.1"] (map #(get % "id") items)))
    (is (= {"id" "STD:4" "title" "General" "context" "compliance" "component" "dhf/clauses/std.txt" "level" "clause"}
           (nth items 3))))
  (testing "a scope naming compliance carries them, another does not"
    (is (checklist/clauses-in-scope? [{:context :compliance}]))
    (is (not (checklist/clauses-in-scope? [{:component :compliance/gap}])))
    (is (not (checklist/clauses-in-scope? [{:context :architecture}])))))

(deftest the-json-keeps-key-order
  (let [text (json/generate-string (checklist/derive-checklist "03" "spec-3" scenarios nil nil))]
    (is (< (.indexOf text "\"cycle\"") (.indexOf text "\"cut\"") (.indexOf text "\"items\"") (.indexOf text "\"planned\"")))))

(deftest unstamped-scenarios-are-findings
  (let [fs (checklist/unstamped-findings [{:id nil :ids [] :file "f.feature" :line 7 :title "x"}
                                          {:id nil :ids ["spec-1" "spec-2"] :file "f.feature" :line 9 :title "y"}
                                          {:id "spec-0001" :ids ["spec-0001"] :file "f.feature" :line 11}])]
    (is (= [["f.feature" 7 "checklist-unstamped"] ["f.feature" 9 "checklist-ids"]]
           (map (juxt :file :line :rule) fs)))))
