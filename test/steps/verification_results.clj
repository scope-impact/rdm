(ns steps.verification-results
  "Step definitions of dhf/spec/verification/results.feature, and the
  verification context's helpers."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn results-dir [ctx] (str (fs/path (k/guard! (:dir ctx)) "dhf/allure-results")))

(defn scenarios-project!
  "A project whose core feature holds the given scenarios (k/sc maps)."
  [ctx scenarios]
  (k/project! ctx {:features {"dhf/spec/kernel/core.feature"
                              (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios scenarios})}}))

(defn status-of [ctx id]
  (let [s (first (filter #(= id (:id %)) (get-in (k/edn-out ctx) [:status :scenarios])))]
    (when-not s (fail! (str "no status for " id ": " (:out ctx) (:err ctx))))
    (:status s)))

(defn result! [ctx m] (k/write-result! (results-dir ctx) m) ctx)

;; One step for every "an Allure result labelled spec=ID ..." of the context.
(defstep #"an Allure result labelled spec=(spec-\d+)(?: with status (\w+)| whose status is \"(\w+)\")?"
  (fn [ctx id status bad]
    (let [n (parse-long (subs id 5))
          ctx (scenarios-project! ctx [(k/sc (if (= id "spec-9999") 1 n) "A scenario")])
          f (k/write-result! (results-dir ctx) {:id id :status (or status bad "passed")})]
      (assoc ctx ::id id ::file f))))

(defstep #"no scenario in the spec is tagged @spec-9999"
  (fn [ctx] (assert (not (str/includes? (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature")) "spec-9999"))) ctx))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= ["spec-9999"] (map :spec (get-in (k/edn-out ctx) [:status :unknown-results]))) (:out ctx))
    ctx))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [st (k/edn-out ctx)]
      (assert (every? #(= :unverified (:status %)) (get-in st [:status :scenarios])) (:out ctx))
      ctx)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx] (let [ctx (k/project! ctx)] (fs/create-dirs (results-dir ctx)) ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "no test run wrote results")))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/write-files! (results-dir ctx) {"broken-result.json" "{\"name\": \"x\",\n \"status\": "})
      (assoc ctx ::file "broken-result.json"))))

(defn unreadable-line [ctx]
  (let [ls (filter #(str/includes? % (::file ctx)) (k/err-lines ctx))]
    (assert (= 1 (count ls)) (str "the file is named on " (count ls) " lines: " (:err ctx)))
    (first ls)))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (assert (str/includes? (unreadable-line ctx) "unreadable") (:err ctx))
    ctx))

(def js-result
  {"uuid" "0b1f2c4e" "historyId" "abc" "name" "A scenario" "fullName" "features/core.feature:A scenario"
   "status" "passed" "stage" "finished" "start" 1700000000000 "stop" 1700000000100
   "labels" [{"name" "language" "value" "javascript"} {"name" "framework" "value" "cucumberjs"}
             {"name" "host" "value" "ci"} {"name" "thread" "value" "1"} {"name" "spec" "value" "spec-0001"}]
   "steps" [{"name" "Given a step" "status" "passed" "stage" "finished" "steps" [] "attachments" [] "parameters" []}]
   "attachments" [] "parameters" [] "links" []})

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (scenarios-project! ctx [(k/sc 1 "A scenario") (k/sc 2 "Another")])]
      (k/write-files! (results-dir ctx) {"0b1f2c4e-result.json" (json/generate-string js-result)
                                         "1c2d-result.json" (json/generate-string (assoc js-result "uuid" "1c2d" "status" "failed"
                                                                                         "labels" [{"name" "spec" "value" "spec-0002"}]))})
      ctx)))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :verified (status-of ctx "spec-0001")))
    (assert (= :failed (status-of ctx "spec-0002")))
    ctx))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (let [ctx (scenarios-project! ctx [(k/sc 1 "A scenario")])]
      (k/write-files! (results-dir ctx) {"u-result.json" (json/generate-string {"name" "parses" "status" "passed"
                                                                                "labels" [{"name" "suite" "value" "unit"}]})})
      ctx)))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (assert (empty? (get-in (k/edn-out ctx) [:status :unknown-results])) (:out ctx))
    ctx))

(defstep #"two passing Allure results labelled spec=spec-0012, one with attachments, links and extra labels, and one with none"
  (fn [ctx]
    (let [ctx (scenarios-project! ctx [(k/sc 12 "Twelve")])]
      (k/write-files! (results-dir ctx)
                      {"a-result.json" (json/generate-string {"name" "Twelve" "status" "passed"
                                                              "labels" [{"name" "spec" "value" "spec-0012"} {"name" "owner" "value" "x"}
                                                                        {"name" "severity" "value" "critical"}]
                                                              "links" [{"type" "issue" "name" "UN-001" "url" "x"}]
                                                              "attachments" [{"name" "log" "source" "a-attachment.txt" "type" "text/plain"}]})
                       "b-result.json" (json/generate-string {"status" "passed" "labels" [{"name" "spec" "value" "spec-0012"}]})})
      ctx)))

(defstep #"spec-0012 is verified, and the other elements change nothing"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :verified (status-of ctx "spec-0012")))
    (assert (empty? (get-in (k/edn-out ctx) [:status :unknown-results])))
    ctx))

(defstep #"the report names its file as unreadable, saying the status must be passed, failed, broken or skipped"
  (fn [ctx]
    (let [l (unreadable-line ctx)]
      (assert (str/includes? l "passed, failed, broken or skipped") l)
      ctx)))
