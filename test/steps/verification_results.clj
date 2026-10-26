(ns steps.verification-results
  "Step definitions for dhf/spec/verification/results.feature: `rdm-spec
  status` reading a small project's results (steps.verification-support).

  Context keys set here: :dir (the project), :id (the scenario a Then is
  about), :file (the result file a Then names), :unknown (the id no
  scenario carries)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- rd [ctx] (v/results-dir (:dir ctx)))

(defn- with-project
  "ctx with a project holding the stamped scenarios @spec-0001 and
  @spec-0012, unless an earlier Given made one."
  [ctx]
  (if (:dir ctx)
    ctx
    (v/one-feature! ctx [{:id "spec-0001" :title "One"} {:id "spec-0012" :title "Twelve"}])))

(defn- status-out [ctx]
  (let [r (kc/exits! ctx 0)]
    (:status (kc/stdout-edn r))))

;; ---------------------------------------------------------------------------
;; Allure results are the oracle, whoever wrote them

(defstep #"an Allure result labelled spec=spec-9999"
  (fn [ctx]
    (let [ctx (with-project ctx)
          f (v/result! (rd ctx) "spec-9999" "passed")]
      (v/result! (rd ctx) "spec-0001" "passed")
      (assoc ctx :unknown "spec-9999" :file f))))

(defstep #"no scenario in the spec is tagged @spec-9999"
  (fn [ctx]
    (let [texts (vals (kc/spec-files (:dir ctx)))]
      (s/check! (not-any? #(str/includes? % "@spec-9999") texts) "a scenario is tagged @spec-9999")
      ctx)))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (some #(= "spec-9999" (:spec %)) (:unknown-results out))
                (str "the unknown results are " (pr-str (:unknown-results out))))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (= {"spec-0001" :verified "spec-0012" :unverified} (v/statuses ctx))
                (str "the statuses are " (pr-str (v/statuses ctx))))
      (s/check! (= 2 (reduce + (vals (:counts out)))) (str "the counts are " (pr-str (:counts out))))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (with-project ctx)]
      (fs/create-dirs (rd ctx))
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (kc/refused-naming! ctx "no test run wrote results there") nil))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (with-project ctx)
          f (str (fs/file (rd ctx) "broken-result.json"))]
      (v/result! (rd ctx) "spec-0001" "passed")
      (s/write! (rd ctx) "broken-result.json" "{\"uuid\": \"x\",\n \"status\": \n")
      (assoc ctx :file f))))

(defn- unreadable-lines [ctx]
  (let [r (kc/the-run ctx)
        rel (str (fs/relativize (:dir ctx) (:file ctx)))]
    (filter #(and (str/includes? % (str (fs/file-name (:file ctx)))) (str/includes? % rel)) (s/err-lines r))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (unreadable-lines ctx)]
      (s/check! (#{0 1} (:exit r)) (str "status did not run\n" (s/show r)))
      (s/check! (= 1 (count ls)) (str "the error stream names the file in " (count ls) " lines\n" (s/show r)))
      (s/check! (str/includes? (first ls) "unreadable") (str "the line does not say unreadable: " (first ls)))
      (s/check! (= (count (s/err-lines r)) (count (str/split-lines (str/trim (:err r)))))
                (str "the error stream holds a broken line\n" (s/show r)))
      nil)))

(def ^:private javascript-result
  "A result as allure-js-commons writes it: camelCase keys, its own labels."
  (fn [id]
    {:uuid (str (random-uuid)) :historyId (str id "-js") :testCaseId "abc" :name (str "a js test of " id)
     :fullName (str "features/a.feature#" id) :status "passed" :stage "finished"
     :statusDetails {} :start 1700000000000 :stop 1700000000100
     :labels [{:name "language" :value "javascript"} {:name "framework" :value "cucumberjs"}
              {:name "host" :value "ci"} {:name "spec" :value id}]
     :links [] :parameters [] :attachments [] :steps [{:name "Given a step" :status "passed" :stage "finished"}]}))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (with-project ctx)]
      (doseq [id ["spec-0001" "spec-0012"]] (v/write-result! (rd ctx) (javascript-result id)))
      ctx)))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (status-out ctx)
    (s/check! (= {"spec-0001" :verified "spec-0012" :verified} (v/statuses ctx))
              (str "the statuses are " (pr-str (v/statuses ctx))))
    nil))

;; ---------------------------------------------------------------------------
;; A result with no spec label verifies nothing

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (let [ctx (with-project ctx)]
      (v/result! (rd ctx) nil "passed")
      (v/result! (rd ctx) "spec-0001" "passed")
      ctx)))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [out (status-out ctx)]
      (s/check! (empty? (:unknown-results out)) (str "the unknown results are " (pr-str (:unknown-results out))))
      nil)))

;; ---------------------------------------------------------------------------
;; What decides a status, and what is only kept

(defstep #"two passing Allure results labelled spec=spec-0012, one with attachments, links and extra labels, and one with none"
  (fn [ctx]
    (let [ctx (with-project ctx)
          full (-> (v/result-map "spec-0012" "passed")
                   (assoc :attachments [{:name "response" :source "x-attachment.txt" :type "text/plain"}]
                          :links [{:type "issue" :name "UN-001" :url "dhf/spec/needs.edn#UN-001"}])
                   (update :labels into [{:name "epic" :value "other"} {:name "owner" :value "someone"}]))]
      (v/write-result! (rd ctx) full)
      (v/write-result! (rd ctx) {:uuid (str (random-uuid)) :status "passed" :labels [{:name "spec" :value "spec-0012"}]})
      (assoc ctx :id "spec-0012"))))

(defstep #"spec-0012 is verified, and the other elements change nothing"
  (fn [ctx]
    (status-out ctx)
    (s/check! (= :verified (get (v/statuses ctx) "spec-0012")) (str "the statuses are " (pr-str (v/statuses ctx))))
    (s/check! (empty? (kc/finding-lines (kc/the-run ctx))) (str "status reported findings\n" (s/show (kc/the-run ctx))))
    nil))

(defstep #"an Allure result labelled spec=spec-0012 whose status is \"green\""
  (fn [ctx]
    (let [ctx (with-project ctx)
          f (v/write-result! (rd ctx) (v/result-map "spec-0012" "green"))]
      (v/result! (rd ctx) "spec-0001" "passed")
      (assoc ctx :file f))))

(defstep #"the report names its file as unreadable, saying the status must be passed, failed, broken or skipped"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (unreadable-lines ctx)]
      (s/check! (some #(str/includes? % "passed, failed, broken or skipped") ls)
                (str "no line names the file and the statuses\n" (s/show r)))
      (s/check! (not= :verified (get (v/statuses ctx) "spec-0012")) "the result counted")
      nil)))
