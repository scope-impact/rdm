(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.fence :as fence]))

(def model
  {:containers [{:contexts [{:id :spec :components [{:id :spec/parser :spec "spec/parser"}
                                                     {:id :spec/check-x :spec "spec/check-x"}]}
                            {:id :knowledge :components [{:id :knowledge/wiki :spec "knowledge/wiki"}]}]}]
   :kernel [{:id :kernel/bus :spec "kernel/bus"}]})

(def layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
               "test/steps/{context}_{component}.clj"]})

(deftest scopes
  (let [known (fence/model-scopes model)]
    (is (= #{"spec" "knowledge" "kernel"} (set (:contexts known))))
    (is (= [{:context "knowledge"} {:context "spec" :component "parser"} {:context "kernel" :component "bus"}]
           (fence/resolve-scopes known ["knowledge"] ["spec/parser" "kernel/bus"])))
    (is (= {:unknown ["archive" "spec/nothing"]} (fence/resolve-scopes known ["archive"] ["spec/nothing"])))))

(deftest what-a-room-keeps
  (let [files ["src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/check_x.clj" "src/rdm_spec/knowledge/wiki.clj"
               "test/steps/knowledge_wiki.clj" "test/steps/spec_parser.clj" "test.clj"]]
    (testing "the whole project keeps nothing"
      (is (empty? (fence/kept-implementation files [] []))))
    (testing "a component's - is written _"
      (is (= ["src/rdm_spec/spec/check_x.clj" "test/rdm_spec/spec/check_x_test.clj" "test/steps/spec_check_x.clj"]
             (fence/removed-prefixes layout [{:context "spec" :component "check-x"}]))))
    (testing "a context and a component"
      (let [scopes [{:context "knowledge"} {:context "spec" :component "parser"}]]
        (is (= ["src/rdm_spec/spec/check_x.clj" "test.clj"]
               (fence/kept-implementation files scopes (fence/removed-prefixes layout scopes))))))))

(deftest options
  (is (= ["knowledge" "publishing"] (fence/split-list "knowledge, publishing,")))
  (is (= [] (fence/split-list nil)))
  (is (fence/under? "dhf/wiki/a.md" "dhf/wiki/"))
  (is (not (fence/under? "dhf/wikis" "dhf/wiki"))))
