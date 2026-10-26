(ns rdm-spec.regeneration.fence-test
  "Unit tests of Fence's pure parts: classifying committed paths, the
  prefixes a scope removes, and which scopes the model names. No fixture."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.fence :as fence]))

(def ^:private cfg
  {:implementation ["src" "test" "test.clj"]
   :code-layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
                 :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"]}
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site"]}
                :keep ["dhf/spec" ".gitignore" "README.md"]}})

(deftest every-top-level-path-and-every-path-under-dhf-is-classified
  (is (= ["dhf/scratch" "notes.md"]
         (fence/unclassified cfg ["README.md" "src/a.clj" "test.clj" "notes.md" "dhf/spec/x.feature"
                                  "dhf/cycles/01/score.edn" "dhf/scratch/a.md" "site/index.html" ".gitignore"]))))

(deftest a-scope-removes-its-code-layouts-prefixes
  (is (= ["src/rdm_spec/spec/" "test/rdm_spec/spec/" "test/steps/spec_"]
         (fence/prefixes cfg {:contexts ["spec"] :components []})))
  (is (= ["src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj"]
         (fence/prefixes cfg {:contexts [] :components [["spec" "parser"]]})))
  (testing "a component's - is written _"
    (is (= ["src/rdm_spec/knowledge/the_wiki.clj" "test/rdm_spec/knowledge/the_wiki_test.clj"]
           (fence/prefixes cfg {:contexts [] :components [["knowledge" "the-wiki"]]})))))

(def ^:private model
  {:containers [{:contexts [{:id :spec :components [{:id :spec/parser}]}]}]
   :kernel [{:id :kernel/bus}]})

(deftest the-model-names-the-scopes
  (is (= #{"spec" "kernel"} (fence/context-names model)))
  (is (= #{"spec/parser" "kernel/bus"} (fence/component-names model))))

(deftest the-scopes-of-the-command-line
  (is (= {:contexts ["knowledge" "publishing"] :components [["spec" "parser"]]}
         (fence/scopes-of {:context "knowledge,publishing" :component "spec/parser"})))
  (is (= {:contexts [] :components []} (fence/scopes-of {}))))
