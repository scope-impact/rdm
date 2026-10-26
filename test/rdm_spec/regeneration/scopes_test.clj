(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:id :app :contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}]}
                                     {:id :verification :components [{:id :verification/status :spec "verification/status"}
                                                                     {:id :verification/trace :spec "verification/trace"}]}
                                     {:id :site :components [{:id :site/pages :spec "site/pages"}]}]}]
   :kernel [{:id :kernel/command :spec "kernel/command"} {:id :kernel/bus :spec "kernel/bus"}]
   :relationships [{:from :spec/parser :to :verification/status :event :Parsed}
                   {:from :spec/parser :to :site/pages :event :Parsed}
                   {:from :verification/status :to :site/pages :event :Derived}]})

(def cat
  {:Parsed {:published-by :spec/parser :carries [:revision :scenarios]
            :read-by {:revision [:site/pages] :scenarios {:id [:verification/status] :title []}}
            :shape {:revision "a commit" :scenarios "[{:id :title}]; the scenarios"}}
   :Derived {:published-by :verification/status :carries [:statuses]
             :shape {:statuses "{id status}"}}})

(deftest a-changed-path-is-a-components-own-or-shared
  (is (= {:component :spec/parser} (scopes/owner model "dhf/spec" "dhf/spec/spec/parser.feature")))
  (is (= {:component :kernel/bus} (scopes/owner model "dhf/spec" "dhf/spec/kernel/bus.md")))
  (is (nil? (scopes/owner model "dhf/spec" "dhf/spec/glossary.md"))))

(deftest the-subkeys-of-a-shape
  (is (= [:id :title] (scopes/subkeys "[{:id :title}]; the scenarios")))
  (is (nil? (scopes/subkeys "a commit"))))

(deftest a-changed-key-reaches-its-publisher-and-readers
  (testing "readers named"
    (is (= #{:spec/parser :site/pages}
           (scopes/event-scopes model cat (assoc-in cat [:Parsed :shape :revision] "another")))))
  (testing "a gained subkey reaches only its readers"
    (is (= #{:spec/parser :verification/status}
           (scopes/event-scopes model cat (-> cat
                                              (assoc-in [:Parsed :shape :scenarios] "[{:id :title :n}]; the scenarios")
                                              (assoc-in [:Parsed :read-by :scenarios :n] [:verification/status]))))))
  (testing "an empty list reaches only the publisher"
    (is (= #{:spec/parser}
           (scopes/event-scopes model (assoc-in cat [:Parsed :read-by :revision] [])
                                (-> cat (assoc-in [:Parsed :read-by :revision] []) (assoc-in [:Parsed :shape :revision] "another"))))))
  (testing "a reworded collection of maps reaches the readers of every key of its maps"
    (is (= #{:spec/parser :verification/status}
           (scopes/event-scopes model cat (assoc-in cat [:Parsed :shape :scenarios] "[{:id :title}]; reworded")))))
  (testing "no :read-by: every subscriber the model names"
    (is (= #{:verification/status :site/pages}
           (scopes/event-scopes model cat (assoc-in cat [:Derived :shape :statuses] "another")))))
  (testing ":read-by is metadata"
    (is (= #{} (scopes/event-scopes model cat (assoc-in cat [:Parsed :read-by :revision] [:verification/status]))))
    (is (= #{} (scopes/event-scopes model cat (assoc-in cat [:Derived :read-by] {:statuses []}))))))

(deftest scopes-are-in-build-order
  (is (= [:kernel/bus :spec/parser :verification/status :site/pages]
         (scopes/build-order model #{:site/pages :verification/status :spec/parser :kernel/bus})))
  (is (= [:kernel/command :kernel/bus] (scopes/build-order model #{:kernel/bus :kernel/command}))))
