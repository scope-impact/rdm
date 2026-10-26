(ns rdm-spec.regeneration.scopes-test
  "Unit tests of Scopes' pure parts: who owns a changed file, which events
  changed their contract, and the order a builder builds in. No fixture."
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:id :app :contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}]}
                                     {:id :architecture :components [{:id :architecture/lint :spec "architecture/lint"}
                                                                      {:id :architecture/views :spec "architecture/views"}]}
                                     {:id :verification :components [{:id :verification/status :spec "verification/status"}]}]}]
   :kernel [{:id :kernel/command :spec "kernel/command"} {:id :kernel/bus :spec "kernel/bus"}]
   :relationships [{:from :spec/parser :to :verification/status :event :SpecParsed}
                   {:from :spec/parser :to :architecture/lint :event :SpecParsed}]})

(deftest a-components-own-files
  (let [own (scopes/owners "dhf/spec" model)]
    (is (= :spec/parser (own "dhf/spec/spec/parser.feature")))
    (is (= :architecture/views (own "dhf/spec/architecture/views.md")))
    (is (nil? (own "dhf/spec/glossary.md")))))

(deftest an-events-contract-is-what-it-carries
  (let [a {:E {:description "x" :published-by :spec/parser :carries [:k] :shape {:k "a"} :read-by {:x [:k]}}
           :F {:carries [:j] :shape {:j "b"}}}]
    (is (= [] (scopes/changed-events a (assoc-in a [:E :description] "y"))))
    (is (= [] (scopes/changed-events a (update a :E dissoc :read-by))))
    (is (= [:E] (scopes/changed-events a (assoc-in a [:E :shape :k] "c"))))
    (is (= [:E] (scopes/changed-events a (assoc-in a [:E :carries] [:k :l]))))
    (testing "an event added or removed"
      (is (= [:F] (scopes/changed-events a (dissoc a :F))))
      (is (= [:G] (scopes/changed-events a (assoc a :G {:carries []})))))))

(deftest the-order-a-builder-builds-in
  (testing "the kernel first, each publisher before its subscribers"
    (is (= [:kernel/command :spec/parser :verification/status]
           (scopes/ordered model #{:verification/status :spec/parser :kernel/command}))))
  (testing "a subscriber the model names first among several comes after its publisher"
    (is (= [:spec/parser :architecture/lint :verification/status]
           (scopes/ordered model #{:verification/status :architecture/lint :spec/parser}))))
  (testing "no order between them: the model's"
    (is (= [:kernel/command :kernel/bus :architecture/views] (scopes/ordered model #{:architecture/views :kernel/bus :kernel/command})))))
