(ns rdm-spec.regeneration.scopes-test
  "Unit tests of Scopes' pure parts: the keys of a shape, the components an
  event change reaches, and the order scopes are built in. No fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def ^:private model
  {:containers [{:contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}]}
                            {:id :verification :components [{:id :verification/status :spec "verification/status"}]}
                            {:id :publishing :components [{:id :publishing/site :spec "publishing/site"}]}]}]
   :kernel [{:id :kernel/command :spec "kernel/command"}]
   :relationships [{:from :spec/parser :to :verification/status :event :E}
                   {:from :spec/parser :to :publishing/site :event :E}
                   {:from :verification/status :to :publishing/site :event :F}]})

(def ^:private before
  {:E {:published-by :spec/parser :carries [:a :rows :list]
       :read-by {:a [:verification/status] :rows {:id [:publishing/site]} :list [:publishing/site]}
       :shape {:a "a string" :rows "[{:id :title}]; the rows" :list "[{:id :text}]"}}})

(defn- after [f] (f before))

(deftest the-keys-of-a-shape-of-maps
  (is (= #{:id :title} (scopes/inner-keys "[{:id :title}]; the rows")))
  (is (nil? (scopes/inner-keys "a string"))))

(deftest a-changed-key-reaches-its-publisher-and-readers
  (is (= #{:spec/parser :verification/status}
         (scopes/event-scopes before (after #(assoc-in % [:E :shape :a] "another string")) model)))
  (testing "an inner key added reaches the readers :read-by names for it, or every subscriber"
    (is (= #{:spec/parser :verification/status :publishing/site}
           (scopes/event-scopes before (after #(assoc-in % [:E :shape :rows] "[{:id :title :x}]; the rows")) model)))
    (is (= #{:spec/parser :publishing/site}
           (scopes/event-scopes before (after #(-> % (assoc-in [:E :shape :rows] "[{:id :title :x}]; the rows")
                                                   (assoc-in [:E :read-by :rows :x] [:publishing/site])))
                                model))))
  (testing "a flat list reaches its readers whatever inner key changed"
    (is (= #{:spec/parser :publishing/site}
           (scopes/event-scopes before (after #(assoc-in % [:E :shape :list] "[{:id :text :x}]")) model))))
  (testing "a change to :read-by alone reaches no one"
    (is (empty? (scopes/event-scopes before (after #(assoc-in % [:E :read-by :a] [])) model)))
    (is (empty? (scopes/event-scopes (update before :E dissoc :read-by) before model))))
  (testing "an empty reader list reaches only the publisher"
    (is (= #{:spec/parser}
           (scopes/event-scopes (assoc-in before [:E :read-by :a] [])
                                (after #(-> % (assoc-in [:E :read-by :a] []) (assoc-in [:E :shape :a] "x")))
                                model)))))

(deftest the-kernel-first-then-publishers-before-subscribers
  (is (= [:kernel/command :spec/parser :verification/status :publishing/site]
         (scopes/build-order #{:publishing/site :verification/status :kernel/command :spec/parser} model))))

(deftest the-keys-of-maps-nested-in-a-shape-are-not-its-own
  (is (= #{:id :steps :context :x} (scopes/inner-keys "[{:id :steps [{:keyword :text}] :context :x}]; more"))))
