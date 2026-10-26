(ns steps.verification-status
  "Step definitions for dhf/spec/verification/status.feature and
  dhf/spec/verification/results.feature (the Results reader is read through
  the status report): `rdm-spec status` on a small project the scenario
  makes (steps.verification-support), its results in the project's
  configured results directory.

  The status report, as rdm-spec.verification.status states it:
  {:results DIR :scenarios [{:id :title :file :line :status}] :counts {..}
  :unknown-results [{:file :spec}]}; each unknown result is also a warning
  (result-unknown), each unreadable file a finding (result-unreadable).

  The outline is the kernel runner's shared Given (\"a stamped scenario
  outline with three example rows\": :dir, :id, rows n = 1, 2, 3).

  Context keys set here: :dir, :id (the scenario a step is about), :ids,
  :expected ({id status}), :file (a result file), :unknown."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- scenario [n & {:as more}]
  (merge {:id (format "spec-%04d" n) :title (str "Scenario " n)} more))

(defn- results-dir [ctx] (v/results-dir (:dir ctx)))

(defn- result! [ctx id status] (v/result! (results-dir ctx) id status) ctx)

(defn- row-result!
  "A result of the outline's row n = `n`, as a runner writes one: named
  after the row, its parameters holding it."
  [ctx n status]
  (v/write-result! (results-dir ctx)
                   (assoc (v/result-map (:id ctx) status)
                          :name (str "An outline over " n " [n=" n "]")
                          :parameters [{:name "n" :value (str n)}]))
  ctx)

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a stamped scenario tagged @wip, with no result"
  (fn [ctx]
    (-> (v/one-feature! ctx [(scenario 1 :wip? true) (scenario 2)])
        (result! "spec-0002" "passed")
        (assoc :id "spec-0001"))))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (-> (v/one-feature! ctx [(scenario 1 :wip? true)])
        (assoc :id "spec-0001"))))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (-> ctx (result! (:id ctx) "skipped") (result! (:id ctx) "passed"))))

(defstep #"it lists the scenario as planned(?:, not as verified, failed or unverified)?"
  (fn [ctx]
    (kc/exits! ctx 0)
    (v/status-of! ctx (:id ctx) :planned)))

;; ---------------------------------------------------------------------------
;; Derived, never written

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (-> (v/one-feature! ctx [(scenario 1) (scenario 2) (scenario 3)])
        (result! "spec-0001" "passed")
        (result! "spec-0002" "failed")
        (assoc :expected {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified}))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [got (v/statuses ctx)]
      (s/check! (= (:expected ctx) got) (str "the statuses are " (pr-str got)))
      nil)))

;; ---------------------------------------------------------------------------
;; An outline is one requirement

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx] (-> ctx (row-result! 1 "passed") (row-result! 2 "passed") (row-result! 3 "failed"))))

(defstep #"three passing Allure results for it, none of which names its row"
  (fn [ctx]
    (dotimes [_ 3] (result! ctx (:id ctx) "passed"))
    ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx] (-> ctx (row-result! 1 "passed") (row-result! 2 "passed"))))

(defstep #"the scenario is (verified|failed|unverified|planned)"
  (fn [ctx st]
    (kc/exits! ctx 0)
    (v/status-of! ctx (:id ctx) (keyword st))))

;; ---------------------------------------------------------------------------
;; Allure results are the oracle, whoever wrote them

(defstep #"a stamped scenario @spec-0007"
  (fn [ctx] (assoc (v/one-feature! ctx [(scenario 7)]) :id "spec-0007")))

(defstep #"an Allure result labelled spec=spec-0007 with status (\S+)"
  (fn [ctx status] (result! ctx "spec-0007" status)))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx] (-> ctx (result! "spec-0007" "passed") (result! "spec-0007" "failed"))))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (let [scs (mapv scenario (range 1 11))]
      (assoc (v/one-feature! ctx scs) :ids (mapv :id scs)))))

(defstep #"Allure results for nine of them"
  (fn [ctx]
    (doseq [id (take 9 (:ids ctx))] (result! ctx id "passed"))
    ctx))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          out (kc/stdout-edn r)]
      (s/check! (= (set (:ids ctx)) (set (map :id (:scenarios out)))) (str "the report lists " (pr-str (map :id (:scenarios out)))))
      (s/check! (= 10 (reduce + 0 (vals (:counts out)))) (str "the report counts " (pr-str (:counts out))))
      nil)))

(defstep #"the tenth is unverified"
  (fn [ctx] (v/status-of! ctx (last (:ids ctx)) :unverified)))

;; ---------------------------------------------------------------------------
;; results.feature: the Results reader, read through the report

(defstep #"an Allure result labelled spec=spec-9999"
  (fn [ctx]
    (-> (v/one-feature! ctx [(scenario 1)])
        (result! "spec-9999" "passed")
        (assoc :unknown "spec-9999" :id "spec-0001"))))

(defstep #"no scenario in the spec is tagged @spec-9999"
  (fn [ctx]
    (s/check! (not-any? #(str/includes? (slurp (str %)) "@spec-9999")
                        (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature"))
              "a scenario is tagged @spec-9999")
    ctx))

(defstep #"the report lists spec-9999 as an unknown result"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          out (kc/stdout-edn r)]
      (s/check! (= ["spec-9999"] (map :spec (:unknown-results out))) (str "the unknown results are " (pr-str (:unknown-results out))))
      (s/check! (some #(and (kc/warning-line? %) (str/includes? % "spec-9999")) (s/err-lines r))
                (str "no warning names spec-9999\n" (s/show r)))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [got (v/statuses ctx)]
      (s/check! (= {(:id ctx) :unverified} got) (str "the statuses are " (pr-str got)))
      nil)))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (-> (v/one-feature! ctx [(scenario 1)])
        (result! nil "passed")
        (assoc :id "spec-0001"))))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          out (kc/stdout-edn r)]
      (s/check! (empty? (:unknown-results out)) (str "the unknown results are " (pr-str (:unknown-results out))))
      (s/check! (not-any? kc/warning-line? (s/err-lines r)) (str "a warning was given\n" (s/show r)))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [(scenario 1)])]
      (fs/create-dirs (results-dir ctx))
      (s/write! (results-dir ctx) "a-container.json" "{}")
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx] (kc/refused-naming! ctx "no test run wrote results there") nil))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [(scenario 1)])
          f (s/write! (results-dir ctx) "broken-result.json" "{\"status\": \"passed\", \"labels\": [")]
      (assoc ctx :file (str (fs/file-name f))))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (filter #(str/includes? % (:file ctx)) (s/err-lines r))]
      (s/check! (#{0 1} (:exit r)) (str "the status did not run\n" (s/show r)))
      (s/check! (= 1 (count ls)) (str (count ls) " lines name " (:file ctx) "\n" (s/show r)))
      (s/check! (str/includes? (first ls) "unreadable") (str "the line does not say unreadable: " (first ls)))
      nil)))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [(scenario 1) (scenario 2)])]
      (doseq [[id status] [["spec-0001" "passed"] ["spec-0002" "failed"]]]
        (v/write-result! (results-dir ctx)
                         {:uuid (str (random-uuid)) :historyId (str (random-uuid))
                          :name (str "jest: " id) :fullName (str "booking.test.js#" id)
                          :status status :stage "finished" :start 1 :stop 2
                          :labels [{:name "language" :value "javascript"} {:name "framework" :value "jest"}
                                   {:name "suite" :value "booking.test.js"} {:name "spec" :value id}]
                          :steps []}))
      (assoc ctx :expected {"spec-0001" :verified "spec-0002" :failed}))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [got (v/statuses ctx)]
      (s/check! (= (:expected ctx) got) (str "the statuses are " (pr-str got)))
      nil)))
