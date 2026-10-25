(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.fence :as fence]))

(def layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
               "test/steps/{context}_{component}.clj"]})

(def model
  {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser} {:id :spec/check}]}
                                   {:id :verification :components [{:id :verification/status}]}]}
                {:id :repo}]
   :kernel [{:id :kernel/command}]})

(defn- refused? [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e (boolean (:rdm-spec.kernel.command/cannot-run (ex-data e))))))

(deftest parsing-the-scope
  (is (= {:kind :project} (fence/parse-scope {})))
  (is (= {:kind :context :context "spec"} (fence/parse-scope {:context "spec"})))
  (is (= {:kind :component :context "spec" :component "parser"} (fence/parse-scope {:component "spec/parser"})))
  (testing "refused"
    (is (refused? #(fence/parse-scope {:context "spec" :component "spec/parser"})))
    (is (refused? #(fence/parse-scope {:component "parser"})))
    (is (refused? #(fence/parse-scope {:component "spec/"})))
    (is (refused? #(fence/parse-scope {:component "a/b/c"})))))

(deftest the-model-names-the-scope
  (is (= ["spec" "verification"] (fence/model-contexts model)))
  (is (= ["spec/parser" "spec/check" "verification/status" "kernel/command"] (fence/model-components model)))
  (is (nil? (fence/scope-refusal model {:kind :project})))
  (is (nil? (fence/scope-refusal model {:kind :context :context "spec"})))
  (is (nil? (fence/scope-refusal model {:kind :context :context "kernel"})))
  (is (nil? (fence/scope-refusal model {:kind :component :context "spec" :component "parser"})))
  (is (nil? (fence/scope-refusal model {:kind :component :context "kernel" :component "command"})))
  (let [why (fence/scope-refusal model {:kind :context :context "archive"})]
    (is (re-find #"archive" why))
    (is (re-find #"spec, verification" why)))
  (is (re-find #"spec/lexer" (fence/scope-refusal model {:kind :component :context "spec" :component "lexer"}))))

(deftest prefixes-from-the-layout
  (is (= [] (fence/scope-prefixes layout {:kind :project})))
  (is (= ["src/rdm_spec/verification/" "test/rdm_spec/verification/" "test/steps/verification_"]
         (fence/scope-prefixes layout {:kind :context :context "verification"})))
  (testing "- is written _"
    (is (= ["src/rdm_spec/a_b/c_d.clj" "test/rdm_spec/a_b/c_d_test.clj" "test/steps/a_b_c_d.clj"]
           (fence/scope-prefixes layout {:kind :component :context "a-b" :component "c-d"}))))
  (is (refused? #(fence/scope-prefixes {} {:kind :context :context "spec"}))))

(def entries
  (mapv #(hash-map :mode "100644" :type "blob" :sha (str "sha-" %) :path %)
        ["README.md" "dhf/spec/a.feature" "dhf/specs-other/x" "dhf/decisions/adr-0002.md" "dhf/decisions/adr-0001.md"
         "src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/parser_helpers/x.clj" "src/rdm_spec/verification/status.clj"
         "test/steps/verification_status.clj" "test.clj" "test.cljx" "dhf/cycles/01/score.edn"]))

(def allow ["dhf/spec" "dhf/decisions/adr-0002.md"])
(def implementation ["src" "test" "test.clj"])

(defn- paths [scope]
  (set (map :path (fence/select-entries entries {:allow allow :implementation implementation :scope scope
                                                 :prefixes (fence/scope-prefixes layout scope)}))))

(deftest selecting
  (testing "the project scope: the allowed paths only, never a sibling sharing a prefix"
    (is (= #{"dhf/spec/a.feature" "dhf/decisions/adr-0002.md"} (paths {:kind :project}))))
  (testing "a context scope keeps the rest of the implementation"
    (is (= #{"dhf/spec/a.feature" "dhf/decisions/adr-0002.md" "src/rdm_spec/spec/parser.clj"
             "src/rdm_spec/spec/parser_helpers/x.clj" "test.clj"}
           (paths {:kind :context :context "verification"}))))
  (testing "a component scope removes only its files"
    (is (= #{"dhf/spec/a.feature" "dhf/decisions/adr-0002.md" "src/rdm_spec/spec/parser_helpers/x.clj"
             "src/rdm_spec/verification/status.clj" "test/steps/verification_status.clj" "test.clj"}
           (paths {:kind :component :context "spec" :component "parser"})))))

(deftest missing-allowed-paths
  (is (= [] (fence/missing-allowed entries allow)))
  (is (= ["dhf/wiki"] (fence/missing-allowed entries ["dhf/spec" "dhf/wiki"])))
  (is (= ["dhf/spe"] (fence/missing-allowed entries ["dhf/spe"]))))

(deftest reading-git-output
  (is (= [{:mode "100755" :type "blob" :sha "abc" :path "a b/c.sh"}
          {:mode "100644" :type "blob" :sha "def" :path "x"}]
         (fence/parse-ls-tree "100755 blob abc\ta b/c.sh\u0000100644 blob def\tx\u0000")))
  (let [out (.getBytes "aaa blob 3\nab\n\nbbb blob 0\n\n" "UTF-8")
        m (fence/parse-batch out)]
    (is (= "ab\n" (String. ^bytes (get m "aaa") "UTF-8")))
    (is (= 0 (alength ^bytes (get m "bbb"))))))
