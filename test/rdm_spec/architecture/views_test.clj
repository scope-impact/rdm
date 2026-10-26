(ns rdm-spec.architecture.views-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.architecture.views :as views]))

(def m (edn/read-string (slurp "dhf/spec/model.edn")))
(defn view [k] (first (filter #(= k (:key %)) (:views m))))

(deftest context-and-component-views-hold-their-elements
  (is (= (slurp "dhf/views/context.mmd") (views/mermaid m (view "context") "dhf/spec/model.edn")))
  (is (= (slurp "dhf/views/spec-components.mmd") (views/mermaid m (view "spec-components") "dhf/spec/model.edn")))
  (is (= (slurp "dhf/views/regeneration-cycle.mmd") (views/mermaid m (view "regeneration-cycle") "dhf/spec/model.edn"))))

(deftest container-view-holds-every-container-person-and-external
  (let [c (views/content m (view "containers"))]
    (is (= #{:rdm-spec :repository :engineer :reviewer :builder :grader :spec-owner :git-host :test-runner}
           (set (map :id (:elements c)))))
    (is (= 9 (views/element-count m (view "containers"))))))

(deftest fresh-means-the-first-line-records-the-hash
  (is (views/fresh? "%% model-sha256: abc; x\nC4Context" "abc"))
  (is (not (views/fresh? "%% model-sha256: abd; x\n" "abc"))))
