(ns rdm-spec.verification.verify-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.verify :as verify]))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:cannot-run (ex-data e)))))

(def tests {:kind :tests :command ["bb" "test.clj"]})

(deftest declared-checks
  (let [why (fn [checks & [plugins]] (refusal #(verify/checks {:implementation ["src" "test"] :verify checks :plugins plugins})))]
    (is (nil? (why [tests])))
    (is (= [{:kind :tests :name "tests" :command ["bb" "test.clj"]}] (verify/checks {:verify [tests]})))
    (is (str/includes? (why nil) "the configuration declares no :verify"))
    (is (str/includes? (why [{:kind :runs :name "a" :command ["x"]}]) "a project is verified by its tests first"))
    (is (str/includes? (why [tests {:kind :renders}]) ":renders is not a kind of check: the kinds are :tests, :serves, :runs and :plugin"))
    (is (str/includes? (why [{:kind :tests}]) "the tests check names no command"))
    (is (str/includes? (why [tests {:kind :serves :start ["x"] :port 1 :budget {:seconds 1 :polls 1} :probe ["sh" "src/p.sh"]}])
                       "the probe is in the implementation the builder writes"))
    (is (str/includes? (why [tests {:kind :plugin :name "judge"}]) "names no plugin answering :Check"))
    (is (nil? (why [tests {:kind :plugin :name "judge"}] [{:name "judge" :answers :Check :command ["x"]}])))))

(deftest the-mapping
  (is (= verify/default-mapping (verify/mapping {})))
  (is (= {:e2e ["t"]} (:evidence (verify/mapping {:allure {:evidence {:e2e ["t"]}}}))))
  (is (str/includes? (refusal #(verify/mapping {:allure {:epic :aggregate}})) ":aggregate is not an element of the spec"))
  (is (str/includes? (refusal #(verify/mapping {:allure {:colour :context}})) ":colour is not an element of an Allure result"))
  (is (str/includes? (refusal #(verify/mapping {:allure {:labels {"component" :component}}}))
                     "the label spec, from the scenario's id, is required")))

(def scenario
  {:id "spec-0003" :name "Three" :feature "Core" :rule "A rule" :context :kernel :component-name "Core"
   :level :e2e :needs ["UN-001"] :tags ["spec-0003" "e2e"] :feature-tags ["UN-001"] :rule-tags []
   :background [] :steps [{:keyword "Given" :text "a step"} {:keyword "When" :text "it runs"}]})

(def result
  {"name" "Three" "status" "passed"
   "steps" [{"name" "Given a step" "attachments" [{"name" "trace"}]} {"name" "When it runs"}]
   "labels" [{"name" "feature" "value" "Core"} {"name" "story" "value" "A rule"} {"name" "epic" "value" "kernel"}
             {"name" "spec" "value" "spec-0003"} {"name" "component" "value" "Core"}
             {"name" "tag" "value" "spec-0003"} {"name" "tag" "value" "e2e"} {"name" "tag" "value" "UN-001"}]
   "links" [{"type" "issue" "name" "UN-001" "url" "dhf/spec/needs.edn#UN-001"}]})

(defn rules [m r] (map (juxt :rule #(first (str/split (:message %) #":"))) (verify/mapping-findings m scenario r "R.json")))

(deftest a-result-held-to-the-mapping
  (let [m verify/default-mapping]
    (is (empty? (rules m result)))
    (let [[f & more] (verify/mapping-findings m scenario (assoc-in result ["steps" 1 "name"] "When it ran") "R.json")]
      (is (empty? more))
      (is (= "steps-differ-from-scenario" (:rule f)))
      (is (str/starts-with? (:message f) "spec-0003 ran the steps [\"Given a step\" \"When it ran\"]")))
    (is (= [["mapping-differs" "story"]] (rules m (update result "labels" #(remove (fn [l] (= "story" (get l "name"))) %)))))
    (is (= [["mapping-differs" "epic"]] (rules m (update result "labels" #(conj (vec (remove (fn [l] (= "epic" (get l "name"))) %)) {"name" "epic" "value" "x"})))))
    (is (= [["mapping-differs" "links"]] (rules m (assoc result "links" []))))
    (is (= [["mapping-differs" "name"]] (rules m (assoc result "name" "Other"))))
    (testing "evidence on the result or any of its steps"
      (is (empty? (rules (assoc m :evidence {:e2e ["trace"]}) result)))
      (is (= [["evidence-missing" "browser-trace"]] (rules (assoc m :evidence {:e2e ["browser-trace"]}) result))))
    (testing "the finding names the result's file"
      (is (every? #(= "R.json" (:file %)) (verify/mapping-findings m scenario (assoc result "links" []) "R.json"))))))

(deftest an-outline-rows-steps
  (is (= ["Given a is 1"]
         (verify/expected-steps {:background [] :steps [{:keyword "Given" :text "<name> is <n>"}]
                                 :examples [{:headings ["name" "n"]}]}
                                [{"name" "name" "value" "a"} {"name" "n" "value" "1"}]))))

(deftest margins
  (is (= 18 (verify/poll-margin 20 2)))
  (is (= 29.5 (verify/runs-margin 30 0.5)))
  (is (= 0.0 (verify/runs-margin 10 12.0))))

(deftest answering
  (testing "a port nobody holds is free, and no answer comes from it"
    (let [port (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s))]
      (is (verify/port-free? port))
      (is (not (verify/answers? port 200)))))
  (testing "a held port is not free, and a connection with no response is no answer"
    (with-open [s (java.net.ServerSocket. 0)]
      (is (not (verify/port-free? (.getLocalPort s))))
      (is (not (verify/answers? (.getLocalPort s) 300))))))
