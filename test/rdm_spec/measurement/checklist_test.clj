(ns rdm-spec.measurement.checklist-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.checklist :as checklist]))

(def scs
  [{:id "spec-0014" :title "B" :level :contract :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint"}
   {:id "spec-0012" :title "A" :level :e2e :context :architecture :component :architecture/lint
    :context-name "architecture" :component-name "Lint"}
   {:id "spec-0013" :title "C" :wip? true :level :e2e :context :architecture :component :architecture/lint}
   {:id "spec-0020" :title "D" :level :e2e :context :spec :component :spec/parser
    :context-name "spec" :component-name "Parser"}])

(deftest scope-problem
  (testing "valid scopes"
    (doseq [s [:project {:context :verification} {:component :spec/parser}
               [{:context :knowledge} {:component :spec/parser}] {:context :kernel}]]
      (is (nil? (checklist/scope-problem s)) (pr-str s))))
  (testing "invalid scopes"
    (doseq [s [:everything {} [] {:context :a/b} {:component :parser} {:context :a :component :a/b}
               [{:context :a} :b] "spec"]]
      (is (string? (checklist/scope-problem s)) (pr-str s)))))

(deftest in-scope
  (is (checklist/in-scope? :project (first scs)))
  (is (checklist/in-scope? {:context :architecture} (first scs)))
  (is (not (checklist/in-scope? {:context :spec} (first scs))))
  (is (checklist/in-scope? {:component :spec/parser} (last scs)))
  (is (checklist/in-scope? [{:context :knowledge} {:component :spec/parser}] (last scs)))
  (is (not (checklist/in-scope? [{:context :knowledge} {:component :spec/parser}] (first scs)))))

(deftest derive-checklist
  (testing "items to grade sorted by id, planned apart, keys in order"
    (let [{:keys [checklist findings]} (checklist/derive-checklist "03" "spec-3" :project scs)]
      (is (empty? findings))
      (is (= ["cycle" "cut" "items" "planned"] (keys checklist)))
      (is (= ["spec-0012" "spec-0014" "spec-0020"] (map #(get % "id") (get checklist "items"))))
      (is (= ["id" "title" "context" "component" "level"] (keys (first (get checklist "items")))))
      (is (= {"id" "spec-0012" "title" "A" "context" "architecture" "component" "Lint" "level" "e2e"}
             (first (get checklist "items"))))
      (is (= [{"id" "spec-0013" "title" "C"}] (get checklist "planned")))))
  (testing "a scope keeps its items only"
    (let [{:keys [checklist]} (checklist/derive-checklist "03" "spec-3" {:component :spec/parser} scs)]
      (is (= ["spec-0020"] (map #(get % "id") (get checklist "items"))))
      (is (empty? (get checklist "planned")))))
  (testing "an unstamped scenario is a finding with its file and line"
    (let [{:keys [findings]} (checklist/derive-checklist "03" "spec-3" :project
                                                         (conj scs {:id nil :title "X" :file "f.feature" :line 9}))]
      (is (= [["f.feature" 9 "unstamped-scenario"]] (map (juxt :file :line :rule) findings))))))
