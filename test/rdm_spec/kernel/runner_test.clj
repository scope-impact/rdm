(ns rdm-spec.kernel.runner-test
  "One test per rule the Gherkin runner enforces (dhf/spec/05-verify-by-results/runner.feature)."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [rdm-spec.kernel.runner :as runner]))

(defn- tmp [] (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))}))

(defn- scenario [steps & {:as more}]
  (merge {:id "spec-0001" :ids ["spec-0001"] :title "S" :name "S" :keyword "Scenario" :file "dhf/spec/a/b.feature" :line 3
          :feature "B" :rule "R" :tags ["UN-001" "spec-0001" "e2e"] :needs ["UN-001"] :level :e2e
          :context :a :component :a/b :context-name "a" :component-name "B"
          :steps (mapv (fn [[k t]] {:keyword k :text t}) steps)}
         more))

(defn- run [defs scenarios]
  (let [dir (tmp)]
    (try
      (let [summary (runner/run {:scenarios scenarios :results-dir (str dir) :steps defs :needs "dhf/spec/needs.edn"})
            results (for [f (fs/glob dir "*-result.json")] (json/parse-string (slurp (str f))))]
        {:summary summary :results (sort-by #(get % "historyId") results)})
      (finally (fs/delete-tree dir)))))

(defn- def* [re f] {:re re :fn f :where (str re)})

(deftest a-step-is-a-pattern-and-a-function-and-its-return-is-the-next-context
  (let [seen (atom nil)
        defs [(def* #"the spec holds (\d+) features" (fn [ctx n] (assoc ctx :n n)))
              (def* #"it is seen" (fn [ctx] (reset! seen ctx) :not-a-map))
              (def* #"still seen" (fn [ctx] (reset! seen ctx) ctx))]
        {:keys [results]} (run defs [(scenario [["Given" "the spec holds 20 features"] ["Then" "it is seen"] ["And" "still seen"]])])]
    (is (= {:n "20"} @seen))
    (is (= "passed" (get (first results) "status")))))

(deftest a-step-two-definitions-match-is-broken
  (let [defs [(def* #"a spec" identity) (def* #"a .*" identity)]
        {:keys [results]} (run defs [(scenario [["Given" "a spec"]])])]
    (is (= "broken" (get (first results) "status")))))

(deftest every-scenario-run-leaves-one-result-labelled-with-what-it-verifies
  (let [{:keys [results]} (run [(def* #"x" identity)] [(scenario [["Given" "x"]])])
        labels (into {} (map (juxt #(get % "name") #(get % "value")) (get (first results) "labels")))]
    (is (= 1 (count results)))
    (is (= "spec-0001" (labels "spec")))
    (is (= "B" (labels "component")))
    (is (= "a" (labels "epic")))))

(deftest a-planned-scenario-is-not-run
  (let [ran (atom false)
        {:keys [results]} (run [(def* #"x" (fn [c] (reset! ran true) c))] [(scenario [["Given" "x"]] :wip? true)])]
    (is (false? @ran))
    (is (= "skipped" (get (first results) "status")))))

(deftest failed-is-a-then-that-did-not-hold-and-broken-anything-else
  (let [defs [(def* #"holds not" (fn [_] (runner/fail! "no such view")))
              (def* #"throws" (fn [_] (throw (ex-info "boom" {}))))]
        r (fn [k t] (get (first (:results (run defs [(scenario [[k t]])]))) "status"))]
    (is (= "failed" (r "Then" "holds not")))
    (is (= "broken" (r "Given" "holds not")))
    (is (= "broken" (r "Then" "throws")))))

(deftest a-scenarios-tag-labels-are-its-features-its-rules-and-its-own
  (let [{:keys [results]} (run [(def* #"x" identity)] [(scenario [["Given" "x"]] :tags ["UN-001" "UN-002" "spec-0003" "e2e"])])]
    (is (= ["UN-001" "UN-002" "spec-0003" "e2e"]
           (for [l (get (first results) "labels") :when (= "tag" (get l "name"))] (get l "value"))))))

(deftest an-outline-writes-one-result-per-row
  (let [{:keys [results]} (run [(def* #"v (.*)" (fn [c _] c))]
                               [(scenario [["Given" "v <x>"]] :keyword "Scenario Outline" :outline? true
                                          :rows [{:values {"x" "1"} :order ["x"]} {:values {"x" "2"} :order ["x"]}])])]
    (is (= ["spec-0001|1" "spec-0001|2"] (map #(get % "historyId") results)))))

(deftest the-runner-discovers-and-refuses-to-find-nothing
  (is (thrown? clojure.lang.ExceptionInfo (runner/refuse-empty! []))))
