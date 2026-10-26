(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:id :app :contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}
                                                             {:id :spec/check :spec "spec/check"}]}
                                     {:id :verification :components [{:id :verification/status :spec "verification/status"}]}
                                     {:id :publishing :components [{:id :publishing/site :spec "publishing/site"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"} {:id :kernel/command :spec "kernel/command"}]
   :relationships [{:from :spec/parser :to :publishing/site :event :SpecParsed}
                   {:from :spec/parser :to :verification/status :event :SpecParsed}
                   {:from :verification/status :to :publishing/site :event :VerificationDerived}]})

(def catalogue
  {:SpecParsed {:description "d" :published-by :spec/parser :carries [:a] :shape {:a "x"}}
   :VerificationDerived {:description "d" :published-by :verification/status :carries [:b] :shape {:b "y"}}})

(deftest a-spec-file-belongs-to-its-component
  (is (= :spec/parser (scopes/owner-of model "dhf/spec" "dhf/spec/spec/parser.feature")))
  (is (= :spec/parser (scopes/owner-of model "dhf/spec" "dhf/spec/spec/parser.md")))
  (is (= :kernel/bus (scopes/owner-of model "dhf/spec" "dhf/spec/kernel/bus.feature")))
  (is (nil? (scopes/owner-of model "dhf/spec" "dhf/spec/glossary.md"))))

(deftest a-changed-event-reaches-its-publisher-and-subscribers
  (is (= [] (scopes/changed-events catalogue catalogue)))
  (is (= [] (scopes/changed-events catalogue (assoc-in catalogue [:SpecParsed :description] "new"))))
  (is (= [:SpecParsed] (scopes/changed-events catalogue (assoc-in catalogue [:SpecParsed :shape :a] "z"))))
  (is (= [:New] (scopes/changed-events catalogue (assoc catalogue :New {:published-by :spec/check}))))
  (is (= [:SpecParsed] (scopes/changed-events catalogue (dissoc catalogue :SpecParsed))))
  (is (= [:spec/parser :publishing/site :verification/status]
         (scopes/event-scopes model catalogue :SpecParsed))))

(deftest scopes-are-ordered-kernel-first-then-publishers-before-subscribers
  (is (= [:kernel/command :spec/parser :publishing/site]
         (scopes/ordered model [:publishing/site :spec/parser :kernel/command])))
  (testing "a subscriber listed before its publisher waits for it"
    (is (= [:spec/parser :verification/status :publishing/site]
           (scopes/ordered model [:publishing/site :verification/status :spec/parser]))))
  (is (= [:kernel/bus :kernel/command] (scopes/ordered model [:kernel/command :kernel/bus]))))
