(ns rdm-spec.regeneration.fence-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.regeneration.fence :as fence]))

(def model
  {:containers [{:id :c :contexts [{:id :spec :components [{:id :spec/parser} {:id :spec/stamper}]}
                                   {:id :knowledge :components [{:id :knowledge/wiki}]}]}]
   :kernel [{:id :kernel/bus} {:id :kernel/command}]})

(def scopes (fence/model-scopes model))

(def layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
               "test/steps/{context}_{component}.clj"]})

(defn- cannot-run [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (when (:rdm-spec.kernel.command/cannot-run (ex-data e)) (ex-message e)))))

(deftest model-scopes
  (is (= ["spec" "knowledge" "kernel"] (:contexts scopes)))
  (is (= ["bus" "command"] (get-in scopes [:components "kernel"]))))

(deftest scope
  (is (= {:project true} (fence/scope nil {})))
  (is (= {:contexts ["knowledge" "spec"] :components [["spec" "parser"] ["kernel" "bus"]]}
         (fence/scope scopes {:context "knowledge, spec,," :component "spec/parser,kernel/bus"})))
  (testing "the kernel is a context"
    (is (= ["kernel"] (:contexts (fence/scope scopes {:context "kernel"})))))
  (testing "a scope the model does not name"
    (let [m (cannot-run #(fence/scope scopes {:context "archive"}))]
      (is (re-find #"archive" m))
      (is (re-find #"spec, knowledge" m))
      (is (re-find #"kernel" m)))
    (is (re-find #"spec/nope" (cannot-run #(fence/scope scopes {:component "spec/nope"}))))
    (is (re-find #"parser" (cannot-run #(fence/scope scopes {:component "parser"}))))
    (is (cannot-run #(fence/scope scopes {:context ","})))))

(deftest scope-prefixes
  (is (= ["src/rdm_spec/knowledge/" "test/rdm_spec/knowledge/" "test/steps/knowledge_"
          "src/rdm_spec/spec/parser.clj" "test/rdm_spec/spec/parser_test.clj" "test/steps/spec_parser.clj"]
         (fence/scope-prefixes layout {:contexts ["knowledge"] :components [["spec" "parser"]]})))
  (testing "- is written _"
    (is (= ["src/rdm_spec/a_b/c_d.clj"]
           (fence/scope-prefixes {:component ["src/rdm_spec/{context}/{component}.clj"]}
                                 {:components [["a-b" "c-d"]]})))))

(deftest describe-scope
  (is (= "the project" (fence/describe-scope {:project true})))
  (is (= "the contexts a, b and the component c/d"
         (fence/describe-scope {:contexts ["a" "b"] :components [["c" "d"]]}))))

(def entries
  (mapv #(hash-map :mode "100644" :type "blob" :hash (str "h-" %) :path %)
        ["dhf/spec/a.feature" "dhf/specs-not-allowed.md" "README.md" "test.clj"
         "src/rdm_spec/spec/parser.clj" "src/rdm_spec/spec/stamper.clj" "src/rdm_spec/knowledge/wiki.clj"]))

(deftest choose
  (let [cfg {:allow ["dhf/spec"] :implementation ["src" "test.clj"]}]
    (testing "the project scope keeps no implementation"
      (let [c (fence/choose entries cfg [] true)]
        (is (= ["dhf/spec/a.feature"] (map :path (:kept c))))
        (is (= 4 (:removed c)))))
    (testing "a scope keeps every implementation file no prefix removes"
      (let [c (fence/choose entries cfg ["src/rdm_spec/spec/parser.clj" "src/rdm_spec/knowledge/"] false)]
        (is (= ["dhf/spec/a.feature" "src/rdm_spec/spec/stamper.clj" "test.clj"] (map :path (:kept c))))
        (is (= 2 (:removed c)))))))

(deftest missing-allowed
  (is (= ["dhf/wiki"] (fence/missing-allowed ["dhf/spec" "dhf/wiki" "adr.md"]
                                             ["dhf/spec/x" "dhf/wikis/y" "adr.md"]))))
