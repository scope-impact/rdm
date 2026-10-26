(ns steps.verification-results
  "Step definitions for dhf/spec/verification/results.feature: `rdm-spec
  status` on a small project (steps.verification-support) whose configured
  results directory holds the results a step writes.

  Context keys set here: :dir, :expected ({id status} of the stamped
  scenarios), :unknown (an id no scenario carries), :file (a result file's
  name)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(def ^:private two [{:id "spec-0012" :title "Twelve"} {:id "spec-0014" :title "Fourteen"}])

(defn- rd [ctx] (v/results-dir (:dir ctx)))

(defn- out [ctx] (:status (kc/stdout-edn (kc/the-run ctx))))

(defstep #"an Allure result labelled spec=spec-9999"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)]
      (v/project-results! ctx [["spec-9999" "passed"] ["spec-0012" "passed"]])
      (assoc ctx :unknown "spec-9999" :expected {"spec-0012" :verified "spec-0014" :unverified}))))

(defstep #"no scenario in the spec is tagged @spec-9999"
  (fn [ctx]
    (s/check! (not-any? #(str/includes? % "@spec-9999") (vals (kc/spec-files (:dir ctx)))) "a scenario carries @spec-9999")
    ctx))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (some #(= "spec-9999" (:spec %)) (:unknown-results (out ctx)))
                (str "spec-9999 is not an unknown result\n" (s/show r)))
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "spec-9999")) (s/err-lines r))
                (str "no warning names spec-9999\n" (s/show r)))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [st (v/statuses ctx)]
      (s/check! (= (:expected ctx) st) (str "the statuses are " (pr-str st) ", not " (pr-str (:expected ctx))))
      (s/check! (= (count (:expected ctx)) (reduce + (vals (:counts (out ctx))))) (str "the counts are " (:counts (out ctx))))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)]
      (fs/create-dirs (rd ctx))
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (kc/refused-naming! ctx "no test run wrote results there") nil))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)
          f (s/write! (rd ctx) "broken-result.json" "{\"uuid\": \"x\", \"status\": ")]
      (v/project-results! ctx [["spec-0012" "passed"]])
      (assoc ctx :file (str (fs/file-name f))))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (filter #(str/includes? % (:file ctx)) (s/err-lines r))]
      (s/check! (#{0 1} (:exit r)) (str "the status did not run\n" (s/show r)))
      (s/check! (= 1 (count ls)) (str (count ls) " lines name " (:file ctx) "\n" (s/show r)))
      (s/check! (str/includes? (first ls) "unreadable") (str "the line does not say unreadable: " (first ls)))
      nil)))

(defn- js-result
  "A result as allure-js writes it: its own uuid, history, labels and steps."
  [id]
  {:uuid (str (random-uuid)) :historyId (str (random-uuid)) :testCaseId (str (random-uuid))
   :name (str "the scenario " id) :fullName (str "features/lint.feature#the scenario " id)
   :status "passed" :statusDetails {} :stage "finished" :start 1700000000000 :stop 1700000000100
   :steps [{:name "Given a model" :status "passed" :stage "finished" :steps [] :attachments [] :parameters []}]
   :attachments [] :parameters [] :links []
   :labels [{:name "language" :value "javascript"} {:name "framework" :value "cucumberjs"}
            {:name "host" :value "ci"} {:name "spec" :value id}]})

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)]
      (doseq [id ["spec-0012" "spec-0014"]] (v/write-result! (rd ctx) (js-result id)))
      ctx)))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= {"spec-0012" :verified "spec-0014" :verified} (v/statuses ctx)) (str "the statuses are " (v/statuses ctx)))
    nil))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)]
      (v/project-results! ctx [[nil "passed"] ["spec-0012" "passed"]])
      (assoc ctx :expected {"spec-0012" :verified "spec-0014" :unverified}))))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (s/check! (empty? (:unknown-results (out ctx))) (str "an unknown result is listed\n" (s/show r)))
      (s/check! (not-any? kc/warning-line? (s/err-lines r)) (str "a warning is printed\n" (s/show r)))
      nil)))

(defstep #"two passing Allure results labelled spec=spec-0012, one with attachments, links and extra labels, and one with none"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)
          full (-> (v/result-map "spec-0012" "passed")
                   (assoc :attachments [{:name "log" :type "text/plain" :source "x-attachment.txt"}]
                          :links [{:type "issue" :name "UN-001" :url "https://example.invalid/UN-001"}]
                          :parameters [{:name "browser" :value "none"}])
                   (update :labels into [{:name "owner" :value "someone"} {:name "severity" :value "critical"}]))]
      (v/write-result! (rd ctx) full)
      (v/write-result! (rd ctx) (v/result-map "spec-0012" "passed"))
      (s/write! (rd ctx) "x-attachment.txt" "a log\n")
      ctx)))

(defstep #"spec-0012 is verified, and the other elements change nothing"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (v/status-of! ctx "spec-0012" :verified)
      (s/check! (empty? (kc/finding-lines r)) (str "a finding is printed\n" (s/show r)))
      nil)))

(defstep #"an Allure result labelled spec=spec-0012 whose status is \"green\""
  (fn [ctx]
    (let [ctx (v/one-feature! ctx two)
          f (v/write-result! (rd ctx) (assoc (v/result-map "spec-0012" "passed") :status "green"))]
      (v/project-results! ctx [["spec-0014" "passed"]])
      (assoc ctx :file (str (fs/file-name f))))))

(defstep #"the report names its file as unreadable, saying the status must be passed, failed, broken or skipped"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          l (first (filter #(str/includes? % (:file ctx)) (s/err-lines r)))]
      (s/check! (#{0 1} (:exit r)) (str "the status did not run\n" (s/show r)))
      (s/check! (and l (str/includes? l "unreadable") (str/includes? l "passed, failed, broken or skipped"))
                (str "no line names the file as unreadable with the statuses\n" (s/show r)))
      (s/check! (not= :verified (get (v/statuses ctx) "spec-0012")) "the result counted")
      nil)))
