(ns rdm-spec.architecture.lint-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.lint :as lint]))

(def model
  {:system {:id :sauna :name "Sauna" :description "Books sauna slots"}
   :people [{:id :bather :name "Bather" :description "Books a slot" :actor #{:human}}]
   :external [{:id :mailer :name "Mailer" :description "Sends mail"}]
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The program"
                 :contexts [{:id :booking :name "booking" :description "Books slots"
                             :components [{:id :booking/slots :name "Slots" :spec "booking/slots" :description "Slots"}]}
                            {:id :billing :name "billing" :description "Bills"
                             :components [{:id :billing/invoice :name "Invoice" :spec "billing/invoice" :description "Invoices"}]}]}
                {:id :db :name "Database" :kind :stores :technology "files" :description "Holds bookings"}]
   :kernel []
   :relationships [{:from :bather :to :app :description "Books with"}
                   {:from :booking/slots :to :db :description "Writes to"}]
   :views [{:key "context" :level :context :description "The sauna"}
           {:key "booking-components" :level :component :context :booking :description "Booking"}
           {:key "billing-components" :level :component :context :billing :description "Billing"}]})

(def features ["dhf/spec/booking/slots.feature" "dhf/spec/billing/invoice.feature"])

(defn- rules [m & {:keys [catalogue c4 feats] :or {catalogue {} c4 {} feats features}}]
  (mapv :rule (lint/findings {:model m :text (pr-str m) :file "dhf/spec/model.edn" :catalogue catalogue
                              :spec-dir "dhf/spec" :features feats :c4 c4})))

(deftest a-sound-model-passes
  (is (= [] (rules model))))

(deftest each-c4-rule
  (is (= ["c4-scope"] (rules (update-in model [:views 0] dissoc :description))))
  (is (= ["c4-element-described"] (rules (update-in model [:containers 1] dissoc :description))))
  (is (= ["c4-arrow-labelled"] (rules (update-in model [:relationships 1] dissoc :description))))
  (is (= ["c4-context-actors"] (rules (-> model (assoc :people []) (update :relationships subvec 1)))))
  (is (= ["c4-container-runs"] (rules (assoc-in model [:containers 1 :kind] :library))))
  (testing "an event the catalogue does not declare, or from another publisher"
    (let [m (update model :relationships conj {:from :booking/slots :to :billing/invoice :description "Publishes" :event :Booked})]
      (is (= ["c4-events-named"] (rules m)))
      (is (= [] (rules m :catalogue {:Booked {:published-by :booking/slots}})))
      (is (= ["c4-events-named"] (rules m :catalogue {:Booked {:published-by :billing/invoice}})))))
  (testing "the view limit warns, the description limit fails"
    (let [fs* (lint/findings {:model model :text "" :file "m" :catalogue {} :spec-dir "dhf/spec" :features features
                              :c4 {:view-element-limit 2}})]
      (is (= ["c4-view-size"] (distinct (map :rule fs*))))
      (is (every? #(= :warning (:severity %)) fs*))
      (is (some #(str/includes? (:message %) "\"context\"") fs*)))
    (is (= ["c4-description-length"] (distinct (rules model :c4 {:description-max-chars 10}))))))

(deftest each-context-rule
  (is (= ["a-component-view"] (rules (update model :views subvec 0 2))))
  (is (= ["a-scenario-owner"] (rules model :feats (take 1 features))))
  (is (= ["a-scenario-owner"] (rules model :feats (conj features "dhf/spec/old/x.feature"))))
  (let [m (update model :relationships conj {:from :booking/slots :to :billing/invoice :description "Asks"})]
    (is (= ["a-contract-only"] (rules m)))
    (is (= [] (rules m :c4 {:contexts-relate-by [:events :calls]})))
    (is (= ["a-contract-only"] (rules m :c4 {:contexts-relate-by [:events]})))))

(deftest the-questions-no-machine-can-judge
  (is (= 3 (count lint/for-a-person))))
