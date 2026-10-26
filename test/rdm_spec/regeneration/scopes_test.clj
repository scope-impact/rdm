(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"} {:id :spec/check :spec "spec/check"}]}
                            {:id :verification :components [{:id :verification/status :spec "verification/status"}]}
                            {:id :publishing :components [{:id :publishing/site :spec "publishing/site"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"} {:id :kernel/command :spec "kernel/command"}]
   :relationships [{:from :spec/parser :to :verification/status :event :SpecParsed}
                   {:from :spec/parser :to :publishing/site :event :SpecParsed}
                   {:from :verification/status :to :publishing/site :event :VerificationDerived}
                   {:from :spec/check :to :spec/parser :description "Reads with"}]})

(deftest a-components-own-spec-is-its-feature-file-and-its-prose
  (is (= :spec/parser (scopes/owner model "dhf/spec" "dhf/spec/spec/parser.feature")))
  (is (= :spec/parser (scopes/owner model "dhf/spec" "dhf/spec/spec/parser.md")))
  (is (= :kernel/bus (scopes/owner model "dhf/spec" "dhf/spec/kernel/bus.feature")))
  (is (nil? (scopes/owner model "dhf/spec" "dhf/spec/glossary.md")))
  (is (nil? (scopes/owner model "dhf/spec" "dhf/spec/spec/other.feature"))))

(deftest a-changed-event-reaches-its-publisher-and-subscribers
  (let [old {:SpecParsed {:published-by :spec/parser :carries [:a] :shape {:a "x"} :description "d"}
             :VerificationDerived {:published-by :verification/status :carries [:b]}}]
    (is (= #{} (scopes/event-components old (assoc-in old [:SpecParsed :description] "e") model model)))
    (is (= #{:spec/parser :verification/status :publishing/site}
           (scopes/event-components old (assoc-in old [:SpecParsed :shape :a] "y") model model)))
    (is (= #{:verification/status :publishing/site}
           (scopes/event-components old (assoc-in old [:VerificationDerived :carries] [:b :c]) model model)))))

(deftest scopes-are-in-the-order-a-builder-builds-them
  (is (= [:kernel/command :spec/parser :verification/status :publishing/site]
         (scopes/build-order model #{:publishing/site :verification/status :spec/parser :kernel/command})))
  (testing "a publisher before its subscriber, whatever the model's order"
    (let [m (update model :relationships conj {:from :publishing/site :to :spec/check :event :X})]
      (is (= [:spec/parser :publishing/site :spec/check]
             (scopes/build-order m #{:spec/check :publishing/site :spec/parser}))))))
