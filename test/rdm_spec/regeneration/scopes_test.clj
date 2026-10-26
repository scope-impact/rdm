(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"} {:id :spec/check :spec "spec/check"}]}
                            {:id :verification :components [{:id :verification/status :spec "verification/status"}]}
                            {:id :publishing :components [{:id :publishing/site :spec "publishing/site"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"}]
   :relationships [{:from :spec/parser :to :publishing/site :event :E}
                   {:from :spec/parser :to :verification/status :event :E}
                   {:from :verification/status :to :publishing/site :event :F}
                   {:from :spec/check :to :spec/parser :description "within a context"}]})

(deftest nested-keys-are-read-from-the-shape
  (is (= #{:id :title} (scopes/nested-keys "[{:id :title}]; :id is the id")))
  (is (nil? (scopes/nested-keys "the commit read"))))

(deftest a-changed-key-reaches-its-readers
  (let [old {:E {:published-by :spec/parser :carries [:a :b] :shape {:a "x" :b "[{:id}]"}
                 :read-by {:a [:publishing/site] :b {:id []}}}}]
    (testing "a shape change reaches the readers :read-by names"
      (is (= #{:spec/parser :publishing/site}
             (scopes/event-scopes model old (assoc-in old [:E :shape :a] "y")))))
    (testing "an added nested key with no readers named reaches every subscriber"
      (is (= #{:spec/parser :publishing/site :verification/status}
             (scopes/event-scopes model old (assoc-in old [:E :shape :b] "[{:id :new}]")))))
    (testing "an empty list reaches only the publisher"
      (is (= #{:spec/parser} (scopes/event-scopes model old (assoc-in old [:E :shape :b] "[{:id}]; reworded")))))
    (testing ":read-by alone reaches no one"
      (is (= #{} (scopes/event-scopes model old (assoc-in old [:E :read-by :a] [])))))))

(deftest scopes-are-ordered-kernel-first-then-publishers-before-subscribers
  (is (= [:kernel/bus :spec/parser :verification/status :publishing/site]
         (scopes/ordered model #{:publishing/site :verification/status :kernel/bus :spec/parser}))))
