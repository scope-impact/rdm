(ns rdm-spec.regeneration.scopes-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.scopes :as scopes]))

(def model
  {:containers [{:id :rdm-spec
                 :contexts [{:id :architecture
                             :components [{:id :architecture/views :spec "architecture/views"}
                                          {:id :architecture/lint :spec "architecture/lint"}]}
                            {:id :regeneration
                             :components [{:id :regeneration/scopes :spec "regeneration/scopes"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(def config
  {:spec-dir "dhf/spec"
   :clean-room {:allow ["dhf/spec" "dhf/harness/config.edn" "dhf/wiki"]}})

(deftest owners-come-from-the-model
  (is (= {"architecture/views" :architecture/views
          "architecture/lint" :architecture/lint
          "regeneration/scopes" :regeneration/scopes
          "kernel/bus" :kernel/bus}
         (scopes/owners model))))

(deftest a-components-feature-and-prose-are-its-own
  (let [o (scopes/owners model)]
    (is (= :architecture/views (scopes/owner-of "dhf/spec" o "dhf/spec/architecture/views.feature")))
    (is (= :architecture/views (scopes/owner-of "dhf/spec" o "dhf/spec/architecture/views.md")))
    (is (= :kernel/bus (scopes/owner-of "dhf/spec" o "dhf/spec/kernel/bus.feature")))
    (testing "anything else under the spec directory is no component's"
      (is (nil? (scopes/owner-of "dhf/spec" o "dhf/spec/events.edn")))
      (is (nil? (scopes/owner-of "dhf/spec" o "dhf/spec/architecture/views.edn")))
      (is (nil? (scopes/owner-of "dhf/spec" o "dhf/spec/architecture/other.feature")))
      (is (nil? (scopes/owner-of "dhf/spec" o "dhf/spec/x/architecture/views.feature"))))
    (testing "a spec directory given with a trailing slash"
      (is (= :kernel/bus (scopes/owner-of "dhf/spec/" o "dhf/spec/kernel/bus.feature"))))))

(deftest changes-are-sorted-into-scopes-shared-and-ignored
  (let [c (scopes/classify config model
                           ["dhf/spec/kernel/bus.feature"
                            "dhf/spec/architecture/views.md"
                            "dhf/spec/architecture/views.feature"
                            "dhf/spec/glossary.md"
                            "dhf/spec/events.edn"
                            "dhf/wiki/pages/a.md"
                            "src/rdm_spec/kernel/bus.clj"
                            "dhf/cycles/04/score.edn"])]
    (is (= [{:component :architecture/views} {:component :kernel/bus}] (:scopes c)))
    (is (= ["dhf/spec/events.edn" "dhf/spec/glossary.md" "dhf/wiki/pages/a.md"] (:shared c)))
    (is (= ["dhf/cycles/04/score.edn" "src/rdm_spec/kernel/bus.clj"] (:ignored c)))))

(deftest no-change-lists-nothing
  (is (= {:scopes [] :shared [] :ignored []} (scopes/classify config model []))))

(deftest a-component-only-one-cuts-model-names-is-still-its-own
  (let [later (update-in model [:containers 0 :contexts 1 :components] conj
                         {:id :regeneration/fence :spec "regeneration/fence"})]
    (is (= [{:component :regeneration/fence}]
           (:scopes (scopes/classify config [later model] ["dhf/spec/regeneration/fence.feature"]))))))

(deftest a-shared-file-is-a-warning
  (let [w (scopes/shared-warning "spec-3" "spec-4" "dhf/spec/events.edn")]
    (is (= :warning (:severity w)))
    (is (= "dhf/spec/events.edn" (:file w)))
    (is (nil? (:line w)))
    (is (re-find #"no component owns" (:message w)))))
