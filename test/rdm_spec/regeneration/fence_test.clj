(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.fence :as fence]))

(def model
  {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser} {:id :spec/check}]}
                                   {:id :verification :components [{:id :verification/status}]}]}]
   :kernel [{:id :kernel/bus}]})

(def layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
               "test/steps/{context}_{component}.clj"]})

(deftest scopes-are-the-models
  (is (= :project (fence/scope-of model {})))
  (is (= [{:context :verification} {:context :kernel} {:component :spec/parser} {:component :kernel/bus}]
         (fence/scope-of model {:context "verification,kernel" :component "spec/parser,kernel/bus"})))
  (testing "an unknown scope cannot run, naming the contexts and kernel"
    (let [e (try (fence/scope-of model {:context "archive"}) nil (catch Exception e e))]
      (is (re-find #"archive" (ex-message e)))
      (is (re-find #"spec, verification, and kernel" (ex-message e))))
    (is (thrown? Exception (fence/scope-of model {:component "spec/nothing"})))))

(deftest a-scope-removes-its-layout
  (is (= ["src/rdm_spec/verification/" "test/rdm_spec/verification/" "test/steps/verification_"]
         (fence/removed-prefixes layout [{:context :verification}])))
  (is (= ["src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj"]
         (fence/removed-prefixes layout [{:component :spec/parser}]))))

(deftest what-a-clean-room-keeps
  (let [rules {:allow ["dhf/spec" "dhf/harness/config.edn"] :implementation ["src" "test" "test.clj"]}
        scoped (assoc rules :scope [{:context :verification}]
                      :prefixes (fence/removed-prefixes layout [{:context :verification}]))]
    (is (fence/kept? (assoc rules :scope :project) "dhf/spec/a.feature"))
    (is (not (fence/kept? (assoc rules :scope :project) "dhf/spec-other/a")))
    (is (not (fence/kept? (assoc rules :scope :project) "src/rdm_spec/spec/parser.clj")))
    (is (not (fence/kept? (assoc rules :scope :project) "dhf/harness/oracle/x")))
    (is (fence/kept? scoped "src/rdm_spec/spec/parser.clj"))
    (is (fence/kept? scoped "test.clj"))
    (is (not (fence/kept? scoped "src/rdm_spec/verification/status.clj")))
    (is (not (fence/kept? scoped "test/steps/verification_status.clj")))
    (is (not (fence/kept? scoped "README.md")))))
