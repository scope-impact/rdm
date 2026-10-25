(ns steps.verification-results
  "Step definitions for dhf/spec/verification/results.feature: `rdm-spec
  status` in a small project with its own spec, reading Allure results written
  by hand as a test run in any stack would write them."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- project-with
  "A project whose one feature holds the stamped scenarios `ids`."
  [ids]
  (fx/one-feature (str/join "\n" (map #(fx/scenario-text {:id %}) ids))))

(defn- ensure-project
  "The context with a project (:dir) holding spec-0001 and spec-0002, unless
  it has one."
  [ctx]
  (if (:dir ctx) ctx (assoc ctx :dir (project-with ["spec-0001" "spec-0002"]))))

(defn- err-lines-naming [ctx needle]
  (filterv #(str/includes? % needle) (s/err-lines (:run ctx))))

;; --- Allure results are the oracle, whoever wrote them ------------------------

(defstep #"an Allure result labelled spec=(\S+)"
  (fn [ctx id]
    (fx/add-result! (ensure-project ctx) id "passed")))

(defstep #"no scenario in the spec is tagged @(\S+)"
  (fn [ctx id]
    (let [ctx (ensure-project ctx)
          ids (fx/scenario-ids (:dir ctx) (constantly true))]
      (s/check! (seq ids) "the project holds no stamped scenario")
      (s/check! (not (some #{id} ids)) (str "a scenario is tagged @" id))
      ctx)))

(defstep #"the report lists (\S+) as an unknown result"
  (fn [ctx id]
    (let [rep (fx/report ctx)
          files (set (map #(str (fs/file-name %)) (:result-files ctx)))
          unknown (filter #(= id (:id %)) (:unknown-results rep))]
      (s/check! (seq unknown) (str id " is not listed as an unknown result\n" (s/show (:run ctx))))
      (s/check! (every? #(contains? files (str (fs/file-name (:file %)))) unknown)
                (str "the unknown result names no file the scenario wrote: " (pr-str unknown)))
      (s/check! (seq (filter #(str/includes? % id) (err-lines-naming ctx "unknown-result")))
                (str "the error stream does not warn of " id "\n" (s/show (:run ctx))))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [rep (fx/report ctx)
          written (set (map #(str (fs/file-name %)) (:result-files ctx)))]
      (s/check! (seq (:scenarios rep)) (str "the report lists no scenario\n" (s/show (:run ctx))))
      (doseq [sc (:scenarios rep)]
        (s/check! (not-any? #(written (str (fs/file-name (:file %)))) (:results sc))
                  (str (:id sc) " counts the result\n" (s/show (:run ctx))))
        ;; The only results in the directory are the ones this scenario wrote.
        (s/check! (= :unverified (keyword (name (:status sc))))
                  (str (:id sc) " is " (:status sc) ", not unverified\n" (s/show (:run ctx)))))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [dir (project-with ["spec-0001"])
          results (fx/results-of dir)]
      (fs/create-dirs results)
      ;; What a run leaves beside its results is not a result.
      (spit (str (fs/file results "c0ffee-container.json")) "{\"children\":[]}")
      (spit (str (fs/file results "environment.properties")) "os=any\n")
      (assoc ctx :dir dir :results-path results))))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx]
    (let [r (:run ctx)
          lines (s/err-lines r)]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/blank? (:out r)) (str "it printed a result\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % "cannot-run:")
                            (str/includes? % "no test run wrote results")
                            (str/includes? % (str (fs/file-name (:results-path ctx)))))
                      lines)
                (str "it does not say no test run wrote results there\n" (s/show r))))))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [dir (project-with ["spec-0001"])
          f (s/write! (fx/results-of dir) "0badf00d-result.json" "{\"status\": \"failed\", \"labels\": [")]
      (assoc ctx :dir dir :bad-file f))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (:run ctx)
          base (str (fs/file-name (:bad-file ctx)))
          rep (fx/report ctx)
          lines (err-lines-naming ctx base)]
      (s/check! (not= 0 (:exit r)) (str "an unreadable result passed\n" (s/show r)))
      (s/check! (some #(str/includes? (str (:file %)) base) (:unreadable-files rep))
                (str "the report does not name " base " as unreadable\n" (s/show r)))
      (s/check! (= 1 (count lines)) (str (count lines) " lines name " base ", not one\n" (s/show r)))
      (s/check! (str/includes? (first lines) "unreadable") (str "the line does not say unreadable: " (first lines)))
      (s/check! (= :unverified (fx/status-of rep "spec-0001"))
                "the unreadable result counted toward a scenario"))))

;; --- The stack that wrote the results does not matter -------------------------

(def ^:private stack-ids ["spec-0001" "spec-0002" "spec-0003"])
(def ^:private stack-outcomes {"spec-0001" "passed" "spec-0002" "failed"})

(defn- js-result
  "A result as allure-js-commons writes it: its own label set, nested steps,
  parameters, attachments, a testCaseId, compact JSON."
  [spec status]
  (let [uuid (str (java.util.UUID/randomUUID))]
    {:uuid uuid
     :historyId (str (hash [spec "js"]))
     :testCaseId (str (hash spec))
     :name (str "verifies " spec)
     :fullName (str "test/spec.test.js#verifies " spec)
     :status status
     :statusDetails (if (= "passed" status) {} {:message "expected true to be false" :trace "AssertionError\n    at Context.<anonymous>"})
     :stage "finished"
     :start 1700000000000
     :stop 1700000000042
     :description ""
     :parameters []
     :attachments [{:name "log" :source (str uuid "-attachment.txt") :type "text/plain"}]
     :links []
     :labels [{:name "language" :value "javascript"}
              {:name "framework" :value "mocha"}
              {:name "host" :value "ci-runner"}
              {:name "thread" :value "1"}
              {:name "suite" :value "spec.test.js"}
              {:name "spec" :value spec}]
     :steps [{:name "Given a step" :status status :stage "finished" :start 1700000000001 :stop 1700000000040
              :parameters [] :attachments []
              :steps [{:name "an inner step" :status status :stage "finished" :steps [] :parameters [] :attachments []}]}]}))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [dir (project-with stack-ids)
          results (fx/results-of dir)]
      (fs/create-dirs results)
      (doseq [[spec status] stack-outcomes
              :let [r (js-result spec status)]]
        (spit (str (fs/file results (str (:uuid r) "-result.json"))) (json/generate-string r))
        (spit (str (fs/file results (str (:uuid r) "-attachment.txt"))) "log line\n"))
      (spit (str (fs/file results (str (java.util.UUID/randomUUID) "-container.json")))
            (json/generate-string {:uuid "c" :name "spec.test.js" :children [] :befores [] :afters []}))
      (assoc ctx :dir dir))))

(defn- statuses [rep]
  (into {} (map (juxt :id #(keyword (name (:status %)))) (:scenarios rep))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (let [r (:run ctx)
          rep (fx/report ctx)
          ;; The same spec and outcomes, written by the fixtures' own runner.
          other (reduce (fn [c [spec status]] (fx/add-result! c spec status))
                        {:dir (project-with stack-ids)}
                        stack-outcomes)
          other (kc/run-line other "status")
          other-rep (fx/report other)]
      (s/check! (= (:exit (:run other)) (:exit r))
                (str "it exits " (:exit r) ", another stack's results " (:exit (:run other)) "\n" (s/show r)))
      (s/check! (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified} (statuses rep))
                (str "the statuses are " (pr-str (statuses rep)) "\n" (s/show r)))
      (s/check! (= (statuses other-rep) (statuses rep))
                (str "another stack's results give " (pr-str (statuses other-rep))))
      (s/check! (= (:counts other-rep) (:counts rep)) (str "the counts differ: " (pr-str (:counts rep))))
      (s/check! (empty? (:unreadable-files rep)) (str "files were unreadable: " (pr-str (:unreadable-files rep))))
      (s/check! (empty? (:unknown-results rep)) (str "results were unknown: " (pr-str (:unknown-results rep))))
      nil)))

;; --- A result with no spec label verifies nothing ----------------------------

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (fx/add-result! (ensure-project ctx) nil "passed"
                    {:name "a unit test" :fullName "rdm-spec.demo-test/a-unit-test"})))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [rep (fx/report ctx)]
      (s/check! (empty? (:unknown-results rep)) (str "unknown results: " (pr-str (:unknown-results rep))))
      (s/check! (empty? (err-lines-naming ctx "unknown-result")) (str "it is warned of\n" (s/show (:run ctx))))
      nil)))
