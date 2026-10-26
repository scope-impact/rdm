(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.fence :as fence]))

(def config
  {:implementation ["src" "test" "test.clj" "bb.edn"]
   :code-layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
                 :component ["src/rdm_spec/{context}/{component}.clj"
                             "test/rdm_spec/{context}/{component}_test.clj"
                             "test/steps/{context}_{component}.clj"]}
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site" "site-src"]}
                :keep [".gitignore" "README.md" "dhf/spec" "dhf/harness" "dhf/wiki"]}})

(def model
  {:containers [{:contexts [{:id :spec :components [{:id :spec/parser} {:id :spec/check}]}
                            {:id :knowledge :components [{:id :knowledge/sources}]}]}]
   :kernel [{:id :kernel/bus} {:id :kernel/command}]})

(deftest a-scope-names-the-paths-it-removes
  (is (= ["src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj"]
         (fence/scope-prefixes config {:components [["spec" "parser"]]})))
  (is (= ["src/rdm_spec/knowledge/" "test/rdm_spec/knowledge/" "test/steps/knowledge_"]
         (fence/scope-prefixes config {:contexts ["knowledge"]})))
  (testing "a - in a name is written _"
    (is (= ["src/rdm_spec/a_b/c_d.clj" "test/rdm_spec/a_b/c_d_test.clj" "test/steps/a_b_c_d.clj"]
           (fence/scope-prefixes config {:components [["a-b" "c-d"]]})))))

(deftest a-scope-is-one-the-model-names
  (is (= {:contexts ["spec" "kernel"] :components [["knowledge" "sources"]]}
         (fence/scopes-of model {:context "spec,kernel" :component "knowledge/sources"})))
  (is (= {:contexts [] :components []} (fence/scopes-of model {})))
  (doseq [opts [{:context "archive"} {:component "spec/nothing"} {:component "parser"}]]
    (is (command/cannot-run? (try (fence/scopes-of model opts) nil (catch Exception e e))) (pr-str opts))))

(deftest what-is-denied
  (let [denied? (fence/denier config [])]
    (is (denied? "src/rdm_spec/spec/parser.clj"))
    (is (denied? "test.clj"))
    (is (denied? "dhf/cycles/01/score.edn"))
    (is (denied? "site/index.html"))
    (is (not (denied? "dhf/spec/model.edn")))
    (is (not (denied? "dhf/cyclesx/a"))))
  (testing "a scoped regeneration keeps the code outside its scope"
    (let [denied? (fence/denier config (fence/scope-prefixes config {:contexts ["spec"]}))]
      (is (denied? "src/rdm_spec/spec/parser.clj"))
      (is (not (denied? "src/rdm_spec/kernel/bus.clj"))))))

(deftest every-committed-path-is-denied-or-kept
  (is (= [] (fence/unclassified config ["README.md" "dhf/spec/a.feature" "src/x.clj" "dhf/cycles/01/score.edn"])))
  (is (= ["notes.md" "dhf/scratch"]
         (fence/unclassified config ["notes.md" "dhf/scratch/a.md" "dhf/scratch/b.md" "dhf/spec/x"])))
  (testing "a path under dhf/ is classified on its own, dhf itself never"
    (is (= ["dhf/raw"] (fence/unclassified config ["dhf/raw/a.md"])))))

(deftest a-kept-file-never-carries-a-removed-implementation-file
  (let [removed {"src/a.clj" "b1" "src/e.clj" fence/empty-blob}
        kept {"dhf/wiki/copy.md" "b1" "dhf/spec/x" "b2" "dhf/spec/empty" fence/empty-blob}]
    (is (= [["dhf/wiki/copy.md" "src/a.clj"]] (fence/copies removed kept)))))
