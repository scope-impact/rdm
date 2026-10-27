(ns rdm-spec.verification.verify-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.verification.verify :as v]))

(def config {:implementation ["src" "test" "test.clj"]})
(def tests {:kind :tests :command ["bb" "test.clj"]})
(def serves {:kind :serves :name "app" :start ["bb" "src/serve.clj" "{port}"] :port 8080
             :budget {:seconds 10 :polls 20} :probe ["sh" "dhf/harness/probe.sh" "{url}"]})

(defn- why [verify & {:as more}] (v/verify-problem (merge config {:verify verify} more)))

(deftest declarations
  (is (nil? (why [tests serves {:kind :runs :name "answers" :command ["bb" "x"] :expect {:exit 0} :budget {:seconds 30}}])))
  (is (str/includes? (why nil) "the configuration declares no :verify"))
  (is (str/includes? (why [serves]) "a project is verified by its tests first"))
  (is (str/includes? (why [serves tests]) "a project is verified by its tests first"))
  (is (str/includes? (why [{:kind :tests}]) "the tests check names no command"))
  (is (str/includes? (why [tests {:kind :renders}]) ":renders is not a kind of check: the kinds are :tests, :serves, :runs and :plugin"))
  (is (str/includes? (why [tests (assoc serves :probe ["sh" "src/probe.sh" "{url}"])]) "the probe is in the implementation the builder writes"))
  (is (str/includes? (why [tests {:kind :plugin :name "judge"}]) "the check names no plugin answering :Check"))
  (is (str/includes? (why [tests {:kind :plugin :name "judge"}] :plugins [{:name "judge" :subscribes [:SpecParsed] :command ["x"]}])
                     "the check names no plugin answering :Check"))
  (is (nil? (why [tests {:kind :plugin :name "judge"}] :plugins [{:name "judge" :answers :Check :command ["x"]}])))
  (testing "check names are their kind's when none is given, and declared once"
    (is (= ["tests" "serves" "answers"] (map v/check-name [tests (dissoc serves :name) {:kind :runs :name "answers"}])))
    (is (str/includes? (why [tests serves serves]) "declared once"))))

(def default v/default-mapping)

(deftest mappings
  (is (nil? (v/allure-problem nil)))
  (is (nil? (v/allure-problem default)))
  (is (str/includes? (v/allure-problem (assoc default :epic :aggregate)) ":aggregate is not an element of the spec"))
  (is (str/includes? (v/allure-problem (assoc default :colour :context)) ":colour is not an element of an Allure result"))
  (is (str/includes? (v/allure-problem (update default :labels dissoc "spec")) "the label spec, from the scenario's id, is required"))
  (is (str/includes? (v/allure-problem (assoc-in default [:labels "spec"] :component)) "the label spec, from the scenario's id, is required")))

(def scenario
  {:id "spec-0003" :title "Three" :feature "Core" :rule "Things happen" :context :kernel :context-name "kernel"
   :component :kernel/core :component-name "Core" :level :e2e :needs ["UN-001"]
   :tags ["spec-0003" "e2e"] :feature-tags ["UN-001"] :rule-tags []
   :background [] :steps [{:keyword "Given" :text "one"} {:keyword "When" :text "two"} {:keyword "Then" :text "three"}]
   :outline? false :examples []})

(def result
  {"name" "Three" "status" "passed"
   "steps" [{"name" "Given one"} {"name" "When two"} {"name" "Then three"}]
   "labels" [{"name" "feature" "value" "Core"} {"name" "story" "value" "Things happen"} {"name" "epic" "value" "kernel"}
             {"name" "tag" "value" "UN-001"} {"name" "tag" "value" "spec-0003"} {"name" "tag" "value" "e2e"}
             {"name" "spec" "value" "spec-0003"} {"name" "component" "value" "Core"}]
   "links" [{"type" "issue" "name" "UN-001" "url" "dhf/spec/needs.edn#UN-001"}]
   "parameters" [] "attachments" []})

(defn- rules [m sc j]
  (map #(str (:rule %) ": " (first (str/split (:message %) #":"))) (v/mapping-findings m sc j {:file "r.json" :text ""})))

(deftest holding-results
  (is (empty? (rules default scenario result)))
  (is (= ["steps-differ-from-scenario: spec-0003"] (rules default scenario (assoc-in result ["steps" 1 "name"] "When else"))))
  (is (= ["steps-differ-from-scenario: spec-0003"] (rules default scenario (update result "steps" (comp vec butlast)))))
  (is (= ["mapping-differs: story"] (rules default scenario (update result "labels" (fn [ls] (mapv #(if (= "story" (get % "name")) (assoc % "value" "X") %) ls))))))
  (is (= ["mapping-differs: epic"] (rules default scenario (update result "labels" (fn [ls] (mapv #(if (= "epic" (get % "name")) (assoc % "value" "shop") %) ls))))))
  (is (= ["mapping-differs: links"] (rules default scenario (assoc result "links" []))))
  (is (= ["mapping-differs: name"] (rules default scenario (assoc result "name" "Other"))))
  (is (= ["mapping-differs: labels"] (rules default scenario (update result "labels" (fn [ls] (vec (remove #(= "component" (get % "name")) ls)))))))
  (is (= ["evidence-missing: browser-trace"] (rules (assoc default :evidence {:e2e ["browser-trace"]}) scenario result)))
  (is (empty? (rules (assoc default :evidence {:e2e ["browser-trace"]}) scenario
                     (assoc-in result ["steps" 0 "attachments"] [{"name" "browser-trace" "source" "x" "type" "application/zip"}]))))
  (is (empty? (rules (assoc default :evidence {:contract ["browser-trace"]}) scenario result)))
  (testing "a scenario with no rule expects no story"
    (is (empty? (rules default (assoc scenario :rule nil)
                       (update result "labels" (fn [ls] (vec (remove #(= "story" (get % "name")) ls))))))))
  (testing "an outline's row is its parameters, its steps filled in from it"
    (let [sc (assoc scenario :outline? true :steps [{:keyword "Given" :text "<a> is <b>"}]
                    :examples [{:headings ["a" "b"] :rows [{:cells ["x" "1"]} {:cells ["y" "2"]}]}])
          j (assoc result "steps" [{"name" "Given y is 2"}] "parameters" [{"name" "a" "value" "y"} {"name" "b" "value" "2"}])]
      (is (empty? (rules default sc j)))
      (is (some #{"mapping-differs: parameter"} (rules default sc (assoc j "parameters" [{"name" "a" "value" "z"} {"name" "b" "value" "2"}]))))))
  (testing "a finding names the result's file and the line of the element"
    (let [[f] (v/mapping-findings default scenario (assoc result "links" []) {:file "r.json" :text "{\n  \"name\" : \"Three\",\n  \"links\" : [ ]\n}"})]
      (is (= "r.json" (:file f)))
      (is (= 3 (:line f))))))

(deftest runs
  (is (nil? (v/runs-difference {:exit 0 :stdout-starts "{:help"} {:exit 0 :out "{:help []}\n"})))
  (is (str/includes? (v/runs-difference {:exit 0} {:exit 3 :out ""}) "the exit 3 where 0 was expected"))
  (is (str/includes? (v/runs-difference {:exit 0 :stdout-starts "{:help"} {:exit 0 :out "usage\nmore\n"}) "\"usage\""))
  (is (not (str/includes? (v/runs-difference {:exit 0 :stdout-starts "{:help"} {:exit 0 :out "usage\nmore\n"}) "more")))
  (is (= 29.88 (v/margin 30 0.1234)))
  (is (= 0.0 (v/margin 1 2))))

(deftest filling
  (is (= ["bb" "serve" "8080" "--url=http://127.0.0.1:8080/"]
         (v/fill ["bb" "serve" "{port}" "--url={url}"] {"port" "8080" "url" "http://127.0.0.1:8080/"}))))

(deftest polling
  (testing "a port held by a listening socket is held; a free one is not"
    (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          port (.getLocalPort ss)]
      (try (is (v/port-held? port)) (finally (.close ss)))
      (is (not (v/port-held? port)))))
  (testing "an accepted connection with no response is no answer"
    (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          port (.getLocalPort ss)
          held (future (try (.accept ss) (catch Exception _ nil)))]
      (try (is (not (v/answers? port 300)))
           (finally (.close ss) (some-> (deref held 1000 nil) .close))))))

(deftest unstamped
  (is (= [["dhf/spec/a.feature" 7]]
         (map (juxt :file :line) (v/unstamped-findings [{:id "spec-0001" :file "dhf/spec/a.feature" :line 3}
                                                       {:id nil :ids [] :file "dhf/spec/a.feature" :line 7}])))))

(deftest seconds-rounded
  (is (= 1.23 (v/round2 1.2345)))
  (is (= 2.0 (v/round2 2))))
