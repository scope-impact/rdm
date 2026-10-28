(ns rdm-spec.architecture.model-test
  "One test per rule the Model enforces (04-model-the-architecture/model.feature, 13-slice-development/model.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.architecture.model :as model]))

(deftest the-model-is-read-whole-or-not-at-all
  (is (= 3 (:line (first (:findings (model/read-text "m.edn" "{:a 1\n :b 2\n :c {:d 1\n"))))))
  (is (= 3 (:line (first (:findings (model/read-text "m.edn" "{:a 1}\n\n}\n"))))))
  (is (= ["model-key-unknown"] (map :rule (model/shape-findings "m.edn" "" {:containers [{:id :c :name "c" :description "d" :kind :runs :owner "x"}]}))))
  (is (= ["model-id-duplicate"] (map :rule (model/shape-findings "m.edn" "" {:containers [{:id :alpha :name "a" :description "d" :kind :runs :contexts [{:id :x :name "x" :description "d" :components [{:id :alpha :name "a" :description "d"}]}]}]}))))
  (is (= ["model-element-unknown"] (map :rule (model/shape-findings "m.edn" "" {:system {:id :s :name "s" :description "d"} :relationships [{:from :s :to :archive :description "x"}]})))))

(deftest a-components-feature-file-is-found-from-the-model
  (is (= ["dhf/spec/spec/parser.feature"] (model/owned-files {:layout :contexts :dir "dhf/spec"} {:id :spec/parser :spec "spec/parser"} []))))

(deftest views-go-stale-on-content-never-formatting
  (is (= (model/model-hash (clojure.edn/read-string "{:a 1 :b #{2 1}}")) (model/model-hash (clojure.edn/read-string "{:b #{1 2}\n ;; c\n :a 1}")))))

(deftest under-the-slices-layout-the-spec-is-grouped-by-slice-then-component
  (is (= #{"slice-component-unknown"}
         (set (map :rule (model/slice-findings {:dir "dhf/spec"} {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser}]}]}]}
                                               ["dhf/spec/01-a/parsr.feature" "dhf/spec/01-a/parser.feature"])))))
  (is (= #{"slice-missing"} (set (map :rule (model/slice-findings {:dir "dhf/spec"} {:kernel [{:id :kernel/bus}]} ["dhf/spec/bus.feature" "dhf/spec/01-a/bus.feature"]))))))
