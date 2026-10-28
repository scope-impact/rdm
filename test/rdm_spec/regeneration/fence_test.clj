(ns rdm-spec.regeneration.fence-test
  "One test per rule Fence enforces (06-regenerate/fence.feature, 11 and 13)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.regeneration.fence :as fence]))

(def cfg {:implementation ["src" "test"] :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site"]}
                                                       :keep ["dhf/spec" ".gitignore"]}
          :code-layout {:context ["src/rdm_spec/{context}/"] :component ["src/rdm_spec/{context}/{component}.clj"]}})

(deftest a-clean-room-is-built-only-from-a-cut
  (is (fence/cut-name? "spec-3"))
  (is (not (fence/cut-name? "HEAD"))))

(deftest a-clean-room-is-the-cut-minus-what-is-denied
  (is (= #{"src/a.clj" "dhf/cycles/01/score.edn" "site/x.html"}
         (set (filter #(fence/denied? cfg % nil) ["src/a.clj" "dhf/cycles/01/score.edn" "site/x.html" "dhf/spec/a.feature"])))))

(deftest every-committed-path-is-denied-or-kept
  (is (= ["dhf/scratch" "notes.md"] (fence/unclassified cfg ["notes.md" "dhf/scratch/a" "dhf/spec/x" "src/a" ".gitignore" "dhf/cycles/1"]))))

(deftest a-clean-room-can-rebuild-the-project-a-context-or-a-component
  (is (= ["src/rdm_spec/spec/parser.clj"] (fence/scope-prefixes cfg [[:component :spec/parser]])))
  (is (= ["src/rdm_spec/kernel/"] (fence/scope-prefixes cfg [[:context :kernel]]))))
