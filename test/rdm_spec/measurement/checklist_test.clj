(ns rdm-spec.measurement.checklist-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.checklist :as checklist]))

(def scenarios
  [{:id "spec-0014" :title "B" :level :contract :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint"}
   {:id "spec-0012" :title "A" :level :e2e :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint"}
   {:id "spec-0013" :title "C" :level :e2e :wip? true :context :spec :component :spec/parser
    :context-name "spec" :component-name "Parser"}])

(deftest id-order
  (is (= ["spec-0002" "spec-0010" "STD:4" "STD:5.1"] (checklist/id-order #{"STD:5.1" "spec-0010" "STD:4" "spec-0002"}))))

(deftest scopes
  (is (nil? (checklist/scope-parts :project)))
  (is (= [{:context :spec}] (checklist/scope-parts {:context :spec})))
  (is (= [{:context :knowledge} {:component :spec/parser}]
         (checklist/scope-parts [{:context :knowledge} {:component :spec/parser}])))
  (is (= ::checklist/malformed (checklist/scope-parts {:component :parser})))
  (is (= ::checklist/malformed (checklist/scope-parts [])))
  (testing "clauses belong to the whole project or a scope naming compliance"
    (is (checklist/clauses-in-scope? nil))
    (is (checklist/clauses-in-scope? [{:context :architecture} {:context :compliance}]))
    (is (not (checklist/clauses-in-scope? [{:context :architecture}])))
    (is (not (checklist/clauses-in-scope? [{:component :compliance/gap}])))))

(deftest items
  (let [{:keys [items planned]} (checklist/items scenarios nil)]
    (is (= [["spec-0012" "e2e"] ["spec-0014" "contract"]] (map #(vector (get % "id") (get % "level")) items)))
    (is (= ["id" "title" "context" "component" "level"] (vec (keys (first items)))))
    (is (= [{"id" "spec-0013" "title" "C"}] planned)))
  (testing "a scope keeps its own scenarios, planned ones included"
    (let [{:keys [items planned]} (checklist/items scenarios [{:component :spec/parser}])]
      (is (empty? items))
      (is (= ["spec-0013"] (map #(get % "id") planned))))))

(deftest what-stops-the-checklist
  (is (= ["scenario-unstamped"] (map :rule (checklist/scenario-findings [{:id nil :title "x" :file "f" :line 3}]))))
  (is (= ["scenario-no-level"] (map :rule (checklist/scenario-findings [{:id "spec-0001" :file "f" :line 3}]))))
  (is (empty? (checklist/scenario-findings scenarios))))

(deftest the-text
  (let [text (checklist/checklist-text "03" "spec-3" (:items (checklist/items scenarios nil))
                                       [{"id" "spec-0013" "title" "C"}])
        v (json/parse-string text)]
    (is (= "03" (get v "cycle")))
    (is (< (str/index-of text "\"cycle\"") (str/index-of text "\"cut\"") (str/index-of text "\"items\"")
           (str/index-of text "\"planned\"")))
    (is (= (checklist/clause-items [{:key "STD:5.1" :description "Plan" :clause-list "dhf/clauses/std.txt"}])
           [{"id" "STD:5.1" "title" "Plan" "context" "compliance" "component" "dhf/clauses/std.txt" "level" "clause"}]))))

(deftest cycles
  (let [root (str (fs/create-temp-dir {:prefix "rdm-spec-unit-"}))
        req {:root root :config {:cycles-dir "dhf/cycles/"}}]
    (try
      (is (= "dhf/cycles/03/cycle.edn" (checklist/cycle-file req "03" "cycle.edn")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no cycle name" (checklist/cycle-file req "../x" "cycle.edn")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no dhf/cycles/03/cycle.edn" (checklist/read-cycle req "03")))
      (fs/create-dirs (fs/file root "dhf/cycles/03"))
      (spit (str (fs/file root "dhf/cycles/03/cycle.edn")) "{:cut \"spec-3\"}")
      (is (= {:cut "spec-3" ::checklist/file "dhf/cycles/03/cycle.edn"} (checklist/read-cycle req "03")))
      (is (= :none (checklist/read-edn-file req "dhf/cycles/03/score.edn" :none)))
      (is (thrown? clojure.lang.ExceptionInfo (checklist/read-edn-file req "dhf/cycles/03/score.edn")))
      (finally (fs/delete-tree root)))))
