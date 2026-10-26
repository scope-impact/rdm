(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.fence :as fence]))

(def config {:implementation ["src" "test" "test.clj"]
             :code-layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
                           :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
                                       "test/steps/{context}_{component}.clj"]}
             :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site"]}
                          :keep [".gitignore" "dhf/spec"]}})

(deftest scope-prefixes
  (is (= ["src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj"]
         (fence/prefixes config {:component "spec/parser"})))
  (is (= ["src/rdm_spec/my_ctx/" "test/rdm_spec/my_ctx/" "test/steps/my_ctx_"] (fence/prefixes config {:context "my-ctx"}))))

(deftest what-is-denied
  (let [d (fence/denied-fn config nil)]
    (is (d "src/a.clj")) (is (d "test.clj")) (is (d "dhf/cycles/01/score.edn")) (is (d "site/index.html"))
    (is (not (d "dhf/spec/x.feature"))))
  (let [d (fence/denied-fn config [{:context "spec"}])]
    (is (d "src/rdm_spec/spec/check.clj")) (is (not (d "src/rdm_spec/kernel/bus.clj")))))

(deftest classification
  (is (= ["notes.md" "dhf/scratch"] (fence/unclassified config ["dhf" ".gitignore" "notes.md" "src"] ["dhf/spec" "dhf/scratch" "dhf/cycles"]))))
