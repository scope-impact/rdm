(ns rdm-spec.architecture.lint-test
  "One test per rule the Lint enforces (04-model-the-architecture/lint.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.architecture.lint :as lint]))

(def base {:system {:id :s :name "S" :description "d"}
           :people [{:id :p :name "P" :description "d"}]
           :containers [{:id :c :name "C" :kind :runs :description "d"
                         :contexts [{:id :a :name "a" :description "d" :components [{:id :a/x :name "X" :description "d"}]}
                                    {:id :b :name "b" :description "d" :components [{:id :b/y :name "Y" :description "d"}]}]}]
           :relationships []
           :views [{:key "a" :level :component :context :a :description "d"} {:key "b" :level :component :context :b :description "d"}]})
(defn rules [m & [cfg]] (set (map :rule (lint/findings m {} (merge {:view-element-limit 20 :description-max-chars 45} cfg) "m.edn" ""))))

(deftest the-c4-model-passes-the-c4-lint
  (is (empty? (rules base)))
  (is ((rules (update base :views conj {:key "ctx" :level :context})) "c4-scope"))
  (is ((rules (update base :containers #(assoc-in % [0 :kind] :library))) "c4-container-runs"))
  (is ((rules (update base :relationships conj {:from :p :to :c})) "c4-arrow-labelled"))
  (is ((rules (update base :relationships conj {:from :a/x :to :b/y :description "x" :event :SpecCut})) "c4-events-named"))
  (is ((rules (assoc-in base [:people 0 :description] (apply str (repeat 60 "x")))) "c4-description-length"))
  (is (= 3 (count lint/for-a-person))))

(deftest bounded-contexts-are-modules-of-one-container
  (is ((rules (update base :views pop)) "a-component-view"))
  (is ((rules (update base :relationships conj {:from :a/x :to :b/y :description "calls"})) "a-contract-only"))
  (is (not ((rules (update base :relationships conj {:from :a/x :to :b/y :description "calls"}) {:contexts-relate-by [:events :calls]}) "a-contract-only"))))
