(ns rdm-spec.measurement.checklist-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.checklist :as checklist]))

(def model
  {:containers [{:id :c :contexts [{:id :verification :name "verification"
                                    :components [{:id :verification/status :name "Status"}]}
                                   {:id :spec :name "spec"
                                    :components [{:id :spec/parser :name "Parser"}]}]}]
   :kernel [{:id :kernel/bus :name "Event bus"}]})

(deftest scopes-have-four-shapes
  (is (= [] (checklist/scope-units :project)))
  (is (= [[:context :verification]] (checklist/scope-units {:context :verification})))
  (is (= [[:component :spec/parser]] (checklist/scope-units {:component :spec/parser})))
  (is (= [[:context :knowledge] [:component :spec/parser]]
         (checklist/scope-units [{:context :knowledge} {:component :spec/parser}])))
  (testing "anything else is malformed"
    (doseq [s [nil :all {} [] {:context :a :component :b/c} {:component :parser} [{:context :a} :b] "project"]]
      (is (nil? (checklist/scope-units s)) (pr-str s)))))

(deftest a-scope-must-be-named-by-the-model
  (is (nil? (checklist/scope-refusal model :project)))
  (is (nil? (checklist/scope-refusal model {:context :kernel})))
  (is (nil? (checklist/scope-refusal model [{:context :verification} {:component :kernel/bus}])))
  (is (re-find #"no context archive" (checklist/scope-refusal model {:context :archive})))
  (is (re-find #"not a scope" (checklist/scope-refusal model {:thing 1}))))

(deftest a-scope-targets-its-items
  (let [t (checklist/target? model [{:context :verification} {:component :spec/parser}])]
    (is (t {:context "verification" :component "Status"}))
    (is (t {:context "spec" :component "Parser"}))
    (is (not (t {:context "spec" :component "Stamper"}))))
  (is ((checklist/target? model :project) {:context "x" :component "y"})))

(def scs
  [{:id "spec-0014" :title "B" :context :architecture :context-name "architecture" :component-name "Lint" :level :e2e}
   {:id "spec-0013" :title "W" :wip? true}
   {:id "spec-0012" :title "A" :context :architecture :context-name "architecture" :component-name "Lint" :level :contract}
   {:id nil :ids [] :title "Unstamped" :file "f.feature" :line 9}])

(deftest one-item-per-stamped-scenario
  (let [{:keys [items planned]} (checklist/derive-checklist scs)]
    (is (= ["spec-0012" "spec-0014"] (map :id items)))
    (is (= {:id "spec-0012" :title "A" :context "architecture" :component "Lint" :level "contract"} (first items)))
    (is (= [{:id "spec-0013" :title "W"}] planned))
    (is (= [:id :title :context :component :level] (keys (first items))))))

(deftest an-unstamped-scenario-stops-the-checklist
  (let [fs (checklist/spec-findings {:scenarios (map #(assoc % :ids (if (:id %) [(:id %)] [])) scs)})]
    (is (= [["f.feature" 9 "unstamped-scenario"]] (map (juxt :file :line :rule) fs)))))

(deftest the-json-keeps-its-key-order
  (let [text (checklist/checklist-json "03" "spec-3" (checklist/derive-checklist scs))]
    (is (= ["cycle" "cut" "items" "id" "title" "context" "component" "level"]
           (take 8 (map second (re-seq #"\"(\w+)\"\s*:" text)))))
    (is (= "spec-3" (:cut (json/parse-string text true))))))
