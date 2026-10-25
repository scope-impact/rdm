(ns rdm-spec.architecture.lint-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.lint :as lint]))

(def m
  {:system {:id :s :name "S" :description "A system"}
   :people [{:id :p :name "P" :description "A person" :actor #{:human}}]
   :external []
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The program"
                 :contexts [{:id :a :name "a" :description "A"
                             :components [{:id :a/one :name "One" :spec "a/one" :description "One"}]}
                            {:id :b :name "b" :description "B"
                             :components [{:id :b/two :name "Two" :spec "b/two" :description "Two"}]}]}]
   :relationships [{:from :p :to :app :description "Uses"}
                   {:from :a/one :to :b/two :description "Publishes" :event :Done}]
   :views [{:key "context" :level :context :description "Context"}
           {:key "a-components" :level :component :context :a :description "A"}
           {:key "b-components" :level :component :context :b :description "B"}]})

(defn- rules [model & {:as opts}]
  (set (map :rule (lint/lint model "" "model.edn"
                             (merge {:catalogue {:Done {:published-by :a/one}} :catalogue-file "events.edn"
                                     :limit 20 :description-max 45 :spec-dir "dhf/spec"
                                     :feature-files ["dhf/spec/a/one.feature" "dhf/spec/b/two.feature"]
                                     :exists? #{"dhf/spec/a/one.feature" "dhf/spec/b/two.feature"}}
                                    opts)))))

(deftest a-clean-model-has-no-finding
  (is (empty? (rules m))))

(deftest the-c4-checklist
  (is (contains? (rules (update-in m [:views 0] dissoc :description)) "c4-scope"))
  (is (contains? (rules (update-in m [:containers 0] dissoc :description)) "c4-element-described"))
  (is (contains? (rules (update-in m [:relationships 0] dissoc :description)) "c4-arrow-labelled"))
  (is (contains? (rules (assoc m :people [] :relationships [])) "c4-context-actors"))
  (is (contains? (rules (assoc-in m [:containers 0 :kind] :library)) "c4-container-runs"))
  (is (contains? (rules m :catalogue {}) "c4-events-named"))
  (is (contains? (rules m :catalogue {:Done {:published-by :b/two}}) "c4-events-named") "from another publisher")
  (is (contains? (rules m :limit 1) "c4-view-size"))
  (is (contains? (rules (assoc-in m [:people 0 :description] (apply str (repeat 46 "x")))) "c4-description-length")))

(deftest the-context-rules
  (is (contains? (rules (update m :views pop)) "a-component-view"))
  (is (contains? (rules m :exists? #{"dhf/spec/a/one.feature"}) "a-scenario-owner"))
  (is (contains? (rules m :feature-files ["dhf/spec/a/one.feature" "dhf/spec/b/two.feature" "dhf/spec/x.feature"])
                 "a-scenario-owner"))
  (is (contains? (rules (update-in m [:relationships 1] dissoc :event)) "a-contract-only"))
  (testing "the view size is a warning"
    (is (every? #(= :warning (:severity %))
                (filter #(= "c4-view-size" (:rule %))
                        (lint/lint m "" "model.edn" {:catalogue {:Done {:published-by :a/one}} :limit 1
                                                     :description-max 45 :spec-dir "dhf/spec"
                                                     :feature-files [] :exists? (constantly true)}))))))
