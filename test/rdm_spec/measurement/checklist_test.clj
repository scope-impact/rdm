(ns rdm-spec.measurement.checklist-test
  "One test per rule the Checklist enforces (07-measure, 10, 11, 12 and 13's checklist.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.measurement.checklist :as checklist]))

(def scs [{:id "spec-0002" :title "B" :context :shop :context-name "shop" :component :shop/cart :component-name "Cart" :level :e2e}
          {:id "spec-0001" :title "A" :context :shop :context-name "shop" :component :shop/cart :component-name "Cart" :level :contract :wip? true}])

(deftest the-checklist-is-derived-from-the-cycles-cut
  (let [{:keys [items planned]} (checklist/derive-items scs {:scope :project})]
    (is (= ["spec-0002"] (map #(get % "id") items)))
    (is (= [{"id" "spec-0001" "title" "A"}] planned))))

(deftest an-unstamped-scenario-stops-the-checklist
  (is (= ["scenario-unstamped"] (map :rule (checklist/unstamped-findings [{:ids [] :file "f" :line 3 :title "x"}])))))

(deftest under-the-regulated-profile-every-clause-is-an-item-to-grade
  (is (= {"id" "STD:5.1" "title" "Development plan" "context" "compliance" "component" "dhf/clauses/std.txt" "level" "clause"}
         (checklist/clause-item {:key "STD:5.1" :description "Development plan" :declared "dhf/clauses/std.txt"}))))

(deftest a-cycle-of-a-project-in-a-folder-is-the-folders
  (is (= "dhf/cycles/03/checklist.json" (checklist/checklist-path {:cycles-dir "dhf/cycles"} "03"))))

(deftest a-cycle-may-be-scoped-to-one-spec
  (is (= ["a"] (map :id (checklist/in-scope [{:id "a" :spec "auth"} {:id "b" :spec "infra"}] [{:spec "auth"}] {})))))

(deftest a-slice-is-graded-whole-and-its-checklist-tells-its-own-from-its-regressions
  (is (= "slice" (checklist/role-of {:slice "12-a"} "12-a")))
  (is (= "regression" (checklist/role-of {:slice "02-b"} "12-a"))))
