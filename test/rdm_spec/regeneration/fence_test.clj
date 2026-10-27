(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.fence :as fence]))

(def config
  {:implementation ["src" "test" "test.clj"]
   :code-layout {:context ["src/rdm_spec/{context}/" "test/steps/{context}_"]
                 :component ["src/rdm_spec/{context}/{component}.clj"]}
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site"]}
                :keep ["dhf/spec" "README.md"]}})

(deftest a-scope-removes-the-paths-its-layout-places
  (is (= ["src/rdm_spec/spec/" "test/steps/spec_"] (fence/scope-prefixes config {:context ["spec"]})))
  (is (= ["src/rdm_spec/kernel/command_line.clj"] (fence/scope-prefixes config {:component ["kernel/command-line"]})))
  (is (= ["src" "test" "test.clj"] (fence/scope-prefixes config {}))))

(deftest a-path-is-under-a-prefix
  (is (fence/under? "src" "src/a.clj"))
  (is (fence/under? "src/rdm_spec/spec/" "src/rdm_spec/spec/parser.clj"))
  (is (fence/under? "test/steps/spec_" "test/steps/spec_parser.clj"))
  (is (not (fence/under? "src" "srcx/a.clj"))))

(deftest every-path-is-denied-or-kept
  (is (= ["notes.md" "dhf/scratch"]
         (fence/unclassified config ["src/a.clj" "notes.md" "README.md" "dhf/spec/x.feature" "dhf/cycles/01/a" "dhf/scratch/x" "site/i.html"]))))
