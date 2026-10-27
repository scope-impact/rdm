(ns rdm-spec.measurement.checklist-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.checklist :as checklist]))

(deftest a-scope-selects-scenarios
  (let [s {:id "spec-0001" :context :spec :component :spec/parser :spec nil}]
    (is (checklist/in-scope? :project s))
    (is (checklist/in-scope? {:context :spec} s))
    (is (not (checklist/in-scope? {:context :verification} s)))
    (is (checklist/in-scope? [{:context :knowledge} {:component :spec/parser}] s))
    (is (checklist/in-scope? {:spec "auth"} (assoc s :spec "auth")))
    (is (not (checklist/in-scope? {:spec "auth"} (assoc s :spec "infra"))))))

(deftest a-scope-is-one-of-its-shapes
  (is (checklist/scope? :project))
  (is (checklist/scope? [{:context :knowledge} {:component :spec/parser}]))
  (is (not (checklist/scope? {:colour :red}))))

(deftest clause-items-come-with-the-project-or-the-compliance-context
  (is (checklist/clauses? :project))
  (is (checklist/clauses? [{:context :compliance}]))
  (is (not (checklist/clauses? {:context :architecture}))))
