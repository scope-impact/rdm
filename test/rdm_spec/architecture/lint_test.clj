(ns rdm-spec.architecture.lint-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.architecture.lint :as lint]))

(def m {:system {:id :s :name "S" :description "d"}
        :people [{:id :p :name "P" :description "d" :actor #{:human}}]
        :external []
        :containers [{:id :c :name "C" :description "d" :kind :runs :technology "t"
                      :contexts [{:id :x :name "x" :description "d" :components [{:id :x/a :name "A" :description "d" :spec "x/a"}]}
                                 {:id :y :name "y" :description "d" :components [{:id :y/b :name "B" :description "d" :spec "y/b"}]}]}]
        :relationships [{:from :p :to :s :description "uses"}]
        :views [{:key "ctx" :level :context :description "d"}
                {:key "x" :level :component :context :x :description "d"}
                {:key "y" :level :component :context :y :description "d"}]})

(def opts {:features #{"dhf/spec/x/a.feature" "dhf/spec/y/b.feature"} :spec-dir "dhf/spec" :events {}
           :limit 20 :max-chars 45 :relate-by [:events]})

(defn rules [model & {:as o}] (map :rule (lint/findings model (merge opts o))))

(deftest a-good-model-passes (is (empty? (rules m))))
(deftest view-description (is (= ["c4-scope"] (rules (assoc-in m [:views 0 :description] nil)))))
(deftest element-description (is (= ["c4-element-described"] (rules (assoc-in m [:containers 0 :description] nil)))))
(deftest arrow-label (is (= ["c4-arrow-labelled"] (rules (assoc-in m [:relationships 0 :description] "")))))
(deftest context-actors (is (= ["c4-context-actors"] (rules (assoc m :people [] :relationships [])))))
(deftest container-runs (is (= ["c4-container-runs"] (rules (assoc-in m [:containers 0 :kind] :library)))))
(deftest events-named (is (= ["c4-events-named"] (rules (update m :relationships conj {:from :x/a :to :y/b :description "Publishes" :event :SpecCut})))))
(deftest description-length (is (= ["c4-description-length"] (rules (assoc-in m [:people 0 :description] (apply str (repeat 60 "x")))))))
(deftest component-view (is (= ["a-component-view"] (rules (update m :views (comp vec butlast))))))
(deftest scenario-owner
  (is (= ["a-scenario-owner"] (rules m :features #{"dhf/spec/x/a.feature"})))
  (is (= ["a-scenario-owner"] (rules m :features #{"dhf/spec/x/a.feature" "dhf/spec/y/b.feature" "dhf/spec/z/c.feature"}))))
(deftest contract-only
  (is (= ["a-contract-only"] (rules (update m :relationships conj {:from :x/a :to :y/b :description "calls"}))))
  (is (empty? (rules (update m :relationships conj {:from :x/a :to :y/b :description "calls"}) :relate-by [:events :calls]))))
(deftest view-size-is-a-warning
  (is (= ["c4-view-size"] (map :rule (lint/warnings (update m :people into (for [i (range 25)] {:id (keyword (str "p" i)) :name "P" :description "d" :actor #{:human}})) opts)))))
