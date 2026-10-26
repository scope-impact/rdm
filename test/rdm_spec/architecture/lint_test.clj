(ns rdm-spec.architecture.lint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.lint :as lint]
            [rdm-spec.kernel.command :as command]))

(def model
  {:system {:id :sauna :name "Sauna" :description "Books sauna slots"}
   :people [{:id :bather :name "Bather" :description "Books a slot" :actor #{:human}}]
   :external [{:id :mailer :name "Mailer" :description "Sends mail"}]
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The booking program"
                 :contexts [{:id :booking :name "booking" :description "Books slots"
                             :components [{:id :booking/slots :name "Slots" :spec "booking/slots" :description "Holds the slots"}]}
                            {:id :billing :name "billing" :description "Bills"
                             :components [{:id :billing/invoice :name "Invoice" :spec "billing/invoice" :description "Writes invoices"}]}]}
                {:id :db :name "Database" :kind :stores :technology "files" :description "Holds the bookings"}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus" :description "The bus"}]
   :relationships [{:from :bather :to :app :description "Books slots with"}
                   {:from :booking/slots :to :billing/invoice :description "Publishes" :event :SlotBooked}
                   {:from :booking/slots :to :kernel/bus :description "Publishes on"}]
   :views [{:key "context" :level :context :description "Who uses it"}
           {:key "booking-components" :level :component :context :booking :description "Booking"}
           {:key "billing-components" :level :component :context :billing :description "Billing"}]})

(def catalogue {:SlotBooked {:published-by :booking/slots :carries [:slot]}})

(def features #{"dhf/spec/booking/slots.feature" "dhf/spec/billing/invoice.feature" "dhf/spec/kernel/bus.feature"})

(defn- rules [m & {:keys [cat files limits] :or {cat catalogue files features limits {}}}]
  (map :rule (lint/findings {:model m :text (pr-str m) :file "dhf/spec/model.edn" :catalogue cat}
                            "dhf/spec" files (merge {:view-element-limit 20 :description-max-chars 45} limits))))

(deftest a-clean-model-has-no-finding
  (is (empty? (rules model))))

(deftest the-c4-checklist
  (is (= ["c4-scope"] (rules (assoc-in model [:views 0 :description] " "))))
  (is (= ["c4-element-described"] (rules (update-in model [:containers 1] dissoc :description))))
  (is (= ["c4-arrow-labelled"] (rules (update-in model [:relationships 0] dissoc :description))))
  (is (= ["c4-context-actors"] (rules (-> model (assoc :people []) (update :relationships #(vec (rest %)))))))
  (is (= ["c4-container-runs"] (rules (assoc-in model [:containers 1 :kind] :library))))
  (is (= ["c4-events-named"] (rules (assoc-in model [:relationships 1 :event] :SlotCut))))
  (testing "an event published by another than its declared publisher"
    (is (= ["c4-events-named"] (rules model :cat {:SlotBooked {:published-by :billing/invoice}}))))
  (is (= ["c4-description-length"] (rules (assoc-in model [:people 0 :description] (apply str (repeat 46 "x"))))))
  (testing "a view over the limit warns"
    (let [fs* (lint/findings {:model model :text "" :file "m" :catalogue catalogue} "dhf/spec" features
                             {:view-element-limit 2 :description-max-chars 45})]
      (is (= #{"c4-view-size"} (set (map :rule fs*))))
      (is (every? command/warning? fs*))
      (is (str/includes? (:message (first fs*)) "3")))))

(deftest the-bounded-context-rules
  (is (= ["a-component-view"] (rules (update model :views #(vec (butlast %))))))
  (is (= ["a-scenario-owner"] (rules model :files (disj features "dhf/spec/billing/invoice.feature"))))
  (is (= ["a-scenario-owner"] (rules model :files (conj features "dhf/spec/booking/orphan.feature"))))
  (is (= ["a-contract-only"] (rules (update-in model [:relationships 1] dissoc :event))))
  (testing "a kernel component is shared, in no context"
    (is (empty? (rules model)))))

(deftest questions-for-a-person
  (is (= 3 (count lint/for-a-person)))
  (is (some #(str/includes? % "audience") lint/for-a-person)))
