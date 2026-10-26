(ns rdm-spec.measurement.checklist-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.measurement.checklist :as c]))

(def s1 {:id "spec-0002" :context :architecture :component :architecture/lint})
(def s2 {:id "spec-0001" :context :kernel :component :kernel/bus})

(deftest scopes
  (is (c/in-scope? :project s1))
  (is (c/in-scope? {:context :architecture} s1))
  (is (not (c/in-scope? {:context :architecture} s2)))
  (is (c/in-scope? {:component :kernel/bus} s2))
  (is (c/in-scope? [{:context :verification} {:component :kernel/bus}] s2))
  (is (nil? (c/scope-problem [{:context :knowledge} {:component :spec/parser}])))
  (is (c/scope-problem {:area :x})))

(deftest json-key-order
  (is (re-find #"(?s)\"cycle\".*\"cut\".*\"items\".*\"planned\"" (c/checklist-json "03" "spec-3" [] []))))
