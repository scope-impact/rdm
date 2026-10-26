(ns rdm-spec.regeneration.scopes-test
  "Unit tests of Scopes' pure parts. They make no fixture on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:id :app :contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}]}
                                     {:id :verification :components [{:id :verification/status :spec "verification/status"}
                                                                      {:id :verification/trace :spec "verification/trace"}]}
                                     {:id :publishing :components [{:id :publishing/site :spec "publishing/site"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(deftest a-components-own-files
  (let [owners (scopes/owners model)]
    (is (= :spec/parser (scopes/owner-of owners "dhf/spec" "dhf/spec/spec/parser.feature")))
    (is (= :spec/parser (scopes/owner-of owners "dhf/spec" "dhf/spec/spec/parser.md")))
    (is (= :kernel/bus (scopes/owner-of owners "dhf/spec" "dhf/spec/kernel/bus.feature")))
    (is (nil? (scopes/owner-of owners "dhf/spec" "dhf/spec/glossary.md")))
    (is (nil? (scopes/owner-of owners "dhf/spec" "dhf/spec/spec/nobody.feature")))))

(def catalogue
  {:SpecParsed {:published-by :spec/parser
                :carries [:revision :scenarios :needs]
                :read-by {:revision [:verification/status]
                          :needs [:publishing/site]
                          :scenarios {:id [:verification/status :publishing/site] :title [:publishing/site]}}
                :shape {:revision "r" :scenarios "s" :needs "n"}}})

(deftest a-changed-key-reaches-its-readers
  (testing "a shape changed"
    (is (= #{:spec/parser :publishing/site}
           (scopes/event-scopes catalogue (assoc-in catalogue [:SpecParsed :shape :needs] "n2")))))
  (testing "a key added to the scenarios reaches its readers only"
    (is (= #{:spec/parser :verification/status}
           (scopes/event-scopes catalogue (-> catalogue
                                              (assoc-in [:SpecParsed :shape :scenarios] "s2")
                                              (assoc-in [:SpecParsed :read-by :scenarios :new] [:verification/status]))))))
  (testing "a carried key added"
    (is (= #{:spec/parser :verification/status}
           (scopes/event-scopes catalogue (-> catalogue
                                              (update-in [:SpecParsed :carries] conj :more)
                                              (assoc-in [:SpecParsed :shape :more] "m")
                                              (assoc-in [:SpecParsed :read-by :more] [:verification/status]))))))
  (testing "the readers changed alone reach nothing"
    (is (= #{} (scopes/event-scopes catalogue (assoc-in catalogue [:SpecParsed :read-by :needs] [])))))
  (testing "a description changed reaches nothing"
    (is (= #{} (scopes/event-scopes catalogue (assoc-in catalogue [:SpecParsed :description] "d")))))
  (testing "an event added reaches its publisher and every reader"
    (is (= #{:verification/status :publishing/site}
           (scopes/event-scopes {} {:VerificationDerived {:published-by :verification/status
                                                          :read-by {:statuses [:publishing/site]}}})))))

(deftest scopes-are-listed-in-build-order
  (is (= [:kernel/bus :spec/parser :verification/status :publishing/site]
         (scopes/build-order model catalogue #{:publishing/site :verification/status :spec/parser :kernel/bus})))
  (testing "a subscriber listed earlier in the model comes after its publisher"
    (let [m (update-in model [:containers 0 :contexts] (comp vec reverse))]
      (is (= [:spec/parser :publishing/site]
             (scopes/build-order m catalogue #{:publishing/site :spec/parser}))))))
