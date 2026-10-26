(ns rdm-spec.architecture.lint-test
  "Unit tests of the Lint's pure rules over a model held in memory. No
  fixture on disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.architecture.lint :as lint]))

(def model
  {:system {:id :sauna :name "Sauna" :description "Books sauna slots"}
   :people [{:id :bather :name "Bather" :description "Books a slot" :actor #{:human}}]
   :external []
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The program"
                 :contexts [{:id :booking :name "booking" :description "Books"
                             :components [{:id :booking/slots :name "Slots" :spec "booking/slots" :description "Slots"}]}
                            {:id :billing :name "billing" :description "Bills"
                             :components [{:id :billing/invoice :name "Invoice" :spec "billing/invoice" :description "Invoices"}]}]}]
   :kernel [{:id :kernel/cli :name "CLI" :spec "kernel/cli" :description "The command"}]
   :relationships [{:from :bather :to :app :description "Uses"}
                   {:from :booking/slots :to :billing/invoice :description "Publishes" :event :SlotBooked}]
   :views [{:key "context" :level :context :description "Who uses it"}
           {:key "booking-components" :level :component :context :booking :description "Booking"}
           {:key "billing-components" :level :component :context :billing :description "Billing"}]})

(def catalogue {:SlotBooked {:published-by :booking/slots :carries [:slot]}})

(def features ["dhf/spec/booking/slots.feature" "dhf/spec/billing/invoice.feature" "dhf/spec/kernel/cli.feature"])

(defn- rules [m & {:keys [cat files config] :or {cat catalogue files features config {}}}]
  (mapv :rule (lint/findings {:model m :text (pr-str m) :file "dhf/spec/model.edn" :catalogue cat
                              :feature-files files :spec-dir "dhf/spec" :config config})))

(deftest a-good-model-passes
  (is (= [] (rules model))))

(deftest the-c4-rules
  (is (= ["c4-scope"] (rules (update-in model [:views 0] dissoc :description))))
  (is (= ["c4-element-described"] (rules (update-in model [:people 0] dissoc :description))))
  (is (= ["c4-arrow-labelled"] (rules (update-in model [:relationships 0] dissoc :description))))
  (is (= ["c4-context-actors"] (rules (-> model (assoc :people []) (update :relationships subvec 1)))))
  (is (= ["c4-container-runs"] (rules (assoc-in model [:containers 0 :kind] :library))))
  (is (= ["c4-events-named"] (rules model :cat {})))
  (testing "an event from another publisher than its declared one"
    (is (= ["c4-events-named"] (rules model :cat {:SlotBooked {:published-by :billing/invoice :carries []}}))))
  (is (= ["c4-description-length"] (rules (assoc-in model [:system :description] (apply str (repeat 46 "x"))))))
  (is (= [] (rules (assoc-in model [:system :description] (apply str (repeat 46 "x")))
                   :config {:c4 {:description-max-chars 50}}))))

(deftest a-view-over-the-limit-warns
  (let [m (update model :people into (for [i (range 25)] {:id (keyword (str "p" i)) :name "P" :description "A person"}))
        fs* (lint/findings {:model m :text "" :file "m" :catalogue catalogue :feature-files features :spec-dir "dhf/spec"
                            :config {:c4 {:view-element-limit 20}}})]
    (is (= ["c4-view-size"] (map :rule fs*)))
    (is (= :warning (:severity (first fs*))))
    (is (str/includes? (:message (first fs*)) "27"))))

(deftest the-bounded-context-rules
  (is (= ["a-component-view"] (rules (update model :views pop))))
  (is (= ["a-scenario-owner"] (rules model :files (rest features))))
  (is (= ["a-scenario-owner"] (rules model :files (conj features "dhf/spec/billing/refunds.feature"))))
  (let [m (update model :relationships conj {:from :billing/invoice :to :booking/slots :description "Reads"})]
    (is (= ["a-contract-only"] (rules m)))
    (is (= [] (rules m :config {:c4 {:contexts-relate-by [:events :calls]}}))))
  (testing "the kernel is in no context"
    (is (= [] (rules (update model :relationships conj {:from :booking/slots :to :kernel/cli :description "Runs"}))))))

(deftest the-questions-for-a-person
  (is (= 3 (count lint/for-a-person))))
