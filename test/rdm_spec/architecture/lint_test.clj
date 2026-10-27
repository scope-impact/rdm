(ns rdm-spec.architecture.lint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.lint :as lint]))

(def model
  {:system {:id :demo :name "demo" :description "A demo"}
   :people [{:id :user :name "User" :description "Uses it" :actor #{:human}}]
   :external []
   :containers [{:id :app :name "app" :kind :runs :technology "x" :description "The program"
                 :contexts [{:id :shop :name "shop" :description "Shop"
                             :components [{:id :shop/till :name "Till" :description "Till" :spec "shop/till"}]}
                            {:id :stock :name "stock" :description "Stock"
                             :components [{:id :stock/shelf :name "Shelf" :description "Shelf" :spec "stock/shelf"}]}]}]
   :kernel [{:id :kernel/core :name "Core" :description "Core" :spec "kernel/core"}]
   :relationships [{:from :user :to :app :description "Uses"}]
   :views [{:key "context" :level :context :description "Users"}
           {:key "shop-components" :level :component :context :shop :description "Shop"}
           {:key "stock-components" :level :component :context :stock :description "Stock"}]})

(def features ["dhf/spec/shop/till.feature" "dhf/spec/stock/shelf.feature" "dhf/spec/kernel/core.feature"])

(defn run [m & {:keys [events config files]}]
  (lint/lint {:model m :file "dhf/spec/model.edn" :text (pr-str m) :events (or events {})
              :config (merge {:c4 {:view-element-limit 20 :description-max-chars 45}} config)
              :features (or files features) :spec-dir "dhf/spec"}))

(defn rules [r] (set (map :rule (:findings r))))

(deftest a-sound-model-passes
  (is (= {:findings [] :warnings []} (run model))))

(deftest the-c4-rules
  (is (= #{"c4-scope"} (rules (run (update-in model [:views 0] dissoc :description)))))
  (is (= #{"c4-element-described"} (rules (run (update-in model [:containers 0] dissoc :description)))))
  (is (= #{"c4-arrow-labelled"} (rules (run (update-in model [:relationships 0] dissoc :description)))))
  (is (= #{"c4-context-actors"} (rules (run (assoc model :people [] :relationships [])))))
  (is (= #{"c4-container-runs"} (rules (run (assoc-in model [:containers 0 :kind] :library)))))
  (let [r (run (update model :relationships conj {:from :shop/till :to :stock/shelf :description "P" :event :SpecCut}))]
    (is (= #{"c4-events-named"} (rules r)))
    (is (str/includes? (:message (first (:findings r))) "SpecCut")))
  (testing "an event published by another than its declared publisher"
    (is (= #{"c4-events-named"}
           (rules (run (update model :relationships conj {:from :stock/shelf :to :shop/till :description "P" :event :Sold})
                       :events {:Sold {:published-by :shop/till}})))))
  (let [r (run (assoc-in model [:people 0 :description] (apply str (repeat 60 "a"))))]
    (is (= #{"c4-description-length"} (rules r)))
    (is (str/includes? (:message (first (:findings r))) "60")))
  (testing "a view over the limit warns"
    (let [r (run model :config {:c4 {:view-element-limit 0 :description-max-chars 45}})]
      (is (empty? (:findings r)))
      (is (= #{"c4-view-size"} (set (map :rule (:warnings r))))))))

(deftest the-bounded-context-rules
  (is (= #{"a-component-view"} (rules (run (update model :views #(vec (take 2 %)))))))
  (is (= #{"a-scenario-owner"} (rules (run model :files (rest features)))))
  (is (= #{"a-scenario-owner"} (rules (run model :files (conj features "dhf/spec/shop/ghost.feature")))))
  (let [calls (update model :relationships conj {:from :shop/till :to :stock/shelf :description "Reads"})]
    (is (= #{"a-contract-only"} (rules (run calls))))
    (is (empty? (rules (run calls :config {:c4 {:contexts-relate-by [:events :calls]}}))))))

(deftest what-no-machine-judges
  (is (= 3 (count lint/for-a-person))))
