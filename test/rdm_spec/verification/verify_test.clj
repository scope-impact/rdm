(ns rdm-spec.verification.verify-test
  "Unit tests of Verify's pure parts: the declared checks, the Allure
  mapping, a result held to it, and a :runs check's answer. No fixture on
  disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.runner :as runner]
            [rdm-spec.verification.verify :as verify]))

(def impl ["src" "test" "test.clj" "bb.edn"])

(deftest the-declared-checks
  (is (nil? (verify/config-problem [{:kind :tests :command ["bb" "test.clj"]}] impl)))
  (is (str/includes? (verify/config-problem nil impl) "declares no :verify"))
  (is (str/includes? (verify/config-problem [{:kind :runs :name "a" :command ["x"] :budget {:seconds 1}}] impl)
                     "verified by its tests first"))
  (is (str/includes? (verify/config-problem [{:kind :tests}] impl) "names no command"))
  (is (str/includes? (verify/config-problem [{:kind :tests :command ["t"]} {:kind :renders}] impl)
                     ":renders is not a kind of check"))
  (is (str/includes? (verify/config-problem [{:kind :tests :command ["t"]}
                                             {:kind :serves :start ["a" "{port}"] :port 1 :budget {:seconds 1 :polls 1}
                                              :probe ["bb" "src/probe.clj" "{url}"]}]
                                            impl)
                     "the probe is in the implementation the builder writes"))
  (is (nil? (verify/config-problem [{:kind :tests :command ["t"]}
                                    {:kind :serves :start ["a" "{port}"] :port 1 :budget {:seconds 1 :polls 1}
                                     :probe ["bb" "dhf/harness/probe.clj" "{url}"]}]
                                   impl))))

(deftest the-mapping
  (is (nil? (verify/mapping-problem runner/default-mapping)))
  (is (str/includes? (verify/mapping-problem (assoc runner/default-mapping :epic :aggregate)) ":aggregate is not an element of the spec"))
  (is (str/includes? (verify/mapping-problem (assoc runner/default-mapping :colour :context)) ":colour is not an element of an Allure result"))
  (is (str/includes? (verify/mapping-problem (update runner/default-mapping :labels dissoc "spec"))
                     "the label spec, from the scenario's id, is required")))

(def pickle
  {:id "spec-0003" :name "Labelled" :feature "Lint" :rule "Every view" :context "architecture" :component "Lint"
   :component-id "architecture/lint" :tags ["@UN-001" "@spec-0003" "@e2e"] :needs ["UN-001"]
   :steps [{:keyword "Given" :text "a model"} {:keyword "Then" :text "it fails"}] :parameters []})

(def result
  {:name "Labelled" :status "passed"
   :steps [{:name "Given a model"} {:name "Then it fails"}]
   :labels [{:name "feature" :value "Lint"} {:name "story" :value "Every view"} {:name "epic" :value "architecture"}
            {:name "spec" :value "spec-0003"} {:name "component" :value "Lint"}
            {:name "tag" :value "UN-001"} {:name "tag" :value "spec-0003"} {:name "tag" :value "e2e"}]
   :links [{:type "issue" :name "UN-001" :url "dhf/spec/needs.edn#UN-001"}]
   :attachments []})

(defn- rules [mapping r]
  (mapv (fn [f] (str (:rule f) ": " (first (str/split (:message f) #" ")))) (verify/mapping-findings mapping pickle r {:file "r.json" :text ""})))

(deftest a-result-held-to-the-mapping
  (is (= [] (rules runner/default-mapping result)))
  (is (= ["mapping-differs: story:"] (rules runner/default-mapping (assoc-in result [:labels 1 :value] "Else"))))
  (is (= ["mapping-differs: epic:"] (rules runner/default-mapping (assoc-in result [:labels 2 :value] "other"))))
  (is (= ["steps-differ-from-scenario: spec-0003:"] (rules runner/default-mapping (update result :steps pop))))
  (is (= ["mapping-differs: links:"] (rules runner/default-mapping (assoc result :links []))))
  (is (= ["evidence-missing: browser-trace:"] (rules (assoc runner/default-mapping :evidence {:e2e ["browser-trace"]}) result)))
  (is (= [] (rules (assoc runner/default-mapping :evidence {:e2e ["browser-trace"]})
                   (assoc-in result [:steps 0 :attachments] [{:name "browser-trace"}]))))
  (testing "a mapping holds only what it names"
    (is (= [] (rules {:labels {"spec" :scenario-id}} (assoc result :steps [] :labels [{:name "spec" :value "spec-0003"}]))))))

(deftest a-commands-answer
  (is (nil? (verify/runs-problem {:exit 0 :stdout-starts "{:help"} {:exit 0 :out "{:help []}"} 30)))
  (is (str/includes? (verify/runs-problem {:exit 0} {:exit 3 :out ""} 30) "exited 3 where 0 was expected"))
  (is (str/includes? (verify/runs-problem {:exit 0 :stdout-starts "{:help"} {:exit 0 :out "usage\nmore"} 30) "\"usage\""))
  (is (str/includes? (verify/runs-problem {:exit 0} {:timed-out? true} 10) "the budget of 10 seconds")))
