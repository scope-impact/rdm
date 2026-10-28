(ns steps.verification-results
  "Steps of the component Results reader (dhf/spec/05-verify-by-results/results.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.verification-status :as s]))

(defstep #"an Allure result labelled spec=spec-9999"
  (fn [ctx] (-> (s/scenarios-project! ctx [1]) (s/result! "spec-0001" "passed") (s/result! "spec-9999" "passed"))))

(defstep #"no scenario in the spec is tagged @spec-9999" (fn [ctx] ctx))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= ["spec-9999"] (map :spec (get-in ctx [:result :status :unknown-results]))) "the unknown results are " (pr-str (get-in ctx [:result :status :unknown-results])))
    ctx))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (k/check! (= {:verified 1 :failed 0 :unverified 0 :planned 0} (get-in ctx [:result :status :counts])) "the counts are " (pr-str (get-in ctx [:result :status :counts])))
    ctx))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx] (let [ctx (s/scenarios-project! ctx [1])] (k/write! ctx "dhf/allure-results/notes.txt" "no results\n"))))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no test run wrote results there") ctx))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (-> (s/scenarios-project! ctx [1]) (s/result! "spec-0001" "passed"))]
      (k/write! ctx "dhf/allure-results/broken-result.json" "{\"name\": \"half\nwritten"))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [ls (filter #(str/includes? % "broken-result.json") (k/err-lines ctx))]
      (k/check! (= 1 (count ls)) "the lines naming the file are " (pr-str ls) "; " (k/shows ctx))
      (k/check! (str/includes? (first ls) "unreadable") "the line does not say unreadable: " (first ls))
      ctx)))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (s/scenarios-project! ctx [1 2])]
      (doseq [id ["spec-0001" "spec-0002"]]
        (k/result-json! ctx s/results-dir
                        {:spec id :name (str "it does " id) :fullName (str "tests/" id ".spec.js#it does " id)
                         :historyId (str "a1b2" id) :testCaseId (str "c3d4" id) :status "passed"
                         :statusDetails {:known false :muted false :flaky false}
                         :labels [{:name "language" :value "javascript"} {:name "framework" :value "mocha"}
                                  {:name "host" :value "ci"} {:name "spec" :value id}]
                         :start 1700000000000 :stop 1700000000123}))
      ctx)))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx] (k/exits! ctx 0) (s/status-is! ctx "spec-0001" :verified) (s/status-is! ctx "spec-0002" :verified)))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx] (-> (s/scenarios-project! ctx [1]) (s/result! "spec-0001" "passed") (s/result! nil "failed"))))

(defstep #"it is not listed as an unknown result"
  (fn [ctx] (k/check! (empty? (get-in ctx [:result :status :unknown-results])) "it is listed as unknown") ctx))

(defstep #"two passing Allure results labelled spec=spec-0012, one with attachments, links and extra labels, and one with none"
  (fn [ctx]
    (let [ctx (s/scenarios-project! ctx [12])]
      (k/result-json! ctx s/results-dir {:spec "spec-0012" :attachments [{:name "trace" :source "x.txt" :type "text/plain"}]
                                          :links [{:type "issue" :name "UN-001" :url "x#UN-001"}]
                                          :labels [{:name "spec" :value "spec-0012"} {:name "owner" :value "me"} {:name "severity" :value "critical"}]})
      (k/result-json! ctx s/results-dir {:spec "spec-0012"})
      ctx)))

(defstep #"spec-0012 is verified, and the other elements change nothing"
  (fn [ctx] (k/exits! ctx 0) (s/status-is! ctx "spec-0012" :verified)))

(defstep #"an Allure result labelled spec=spec-0012 whose status is \"green\""
  (fn [ctx]
    (let [ctx (s/scenarios-project! ctx [12])]
      (k/write! ctx "dhf/allure-results/green-result.json" "{\"name\": \"x\", \"status\": \"green\", \"labels\": [{\"name\": \"spec\", \"value\": \"spec-0012\"}]}"))))

(defstep #"the report names its file as unreadable, saying the status must be passed, failed, broken or skipped"
  (fn [ctx] (k/err-line-has! ctx "green-result.json" "unreadable" "passed, failed, broken or skipped") ctx))
