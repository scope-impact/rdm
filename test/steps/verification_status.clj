(ns steps.verification-status
  "Step definitions for dhf/spec/verification/status.feature, and for
  dhf/spec/verification/results.feature, whose every scenario is read through
  the status report (`rdm-spec status`).

  Projects come from steps.verification-support (`one-feature!`): one
  component, demo/alpha, its scenarios as a step gives them; results go into
  the project's configured results directory.

  Context keys set here: :dir, :id (the scenario a step is about),
  :expected ({id status}), :unknown (an unknown result's spec id), :file
  (a result file, as the report names it).
  The outline steps read :dir and :id from the kernel's shared step \"a
  stamped scenario outline with three example rows\" (spec-0001, an outline
  over n = 1, 2, 3, \"An outline over <n>\")."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as vs]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- spec-files
  "{rel text} of every file under the spec directory of the project `dir`
  (copied in: the kernel keeps its own private, README \"Step definitions are
  the scope's own\")."
  [dir]
  (let [d (fs/file dir (or (:spec-dir (kc/config-of dir)) "dhf/spec"))]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                           [(str (fs/relativize dir f)) (slurp (str f))]))
      {})))

(defn- report
  "The last status run's report: it exited 0 (status is a report)."
  [ctx]
  (vs/out-of (assoc ctx :run (kc/exits! ctx 0))))

(defn- row-result!
  "Writes a result for row n of the outline spec-0001, as the runner names it."
  [ctx n status]
  (vs/write-result! (vs/results-dir (:dir ctx))
                    (assoc (vs/result-map (:id ctx) status)
                           :name (str "An outline over " n " [n=" n "]")
                           :parameters [{:name "n" :value (str n)}])))

;; ---------------------------------------------------------------------------
;; Planned work is a scenario tagged @wip

(defstep #"a stamped scenario tagged @wip, with no result"
  (fn [ctx]
    (-> (vs/one-feature! ctx [{:id "spec-0001" :title "Planned work" :wip? true}
                              {:id "spec-0002" :title "Done work"}])
        (vs/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"it lists the scenario as planned"
  (fn [ctx]
    (report ctx)
    (vs/status-of! ctx (:id ctx) :planned)))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (-> (vs/one-feature! ctx [{:id "spec-0001" :title "Planned work" :wip? true}])
        (assoc :id "spec-0001"))))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx]
    (vs/project-results! ctx [[(:id ctx) "skipped"] [(:id ctx) "passed"]])))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx]
    (report ctx)
    (vs/status-of! ctx (:id ctx) :planned)))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (-> (vs/one-feature! ctx [{:id "spec-0001" :title "Passing"}
                              {:id "spec-0002" :title "Failing"}
                              {:id "spec-0003" :title "Without a result"}])
        (vs/project-results! [["spec-0001" "passed"] ["spec-0002" "failed"]])
        (assoc :expected {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified}))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (report ctx)
    (let [got (vs/statuses ctx)]
      (s/check! (= (:expected ctx) got) (str "the statuses are " (pr-str got) ", not " (pr-str (:expected ctx)))))
    nil))

;; ---------------------------------------------------------------------------
;; An outline is one requirement

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (row-result! ctx 1 "passed")
    (row-result! ctx 2 "passed")
    (row-result! ctx 3 "failed")
    ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx]
    (row-result! ctx 1 "passed")
    (row-result! ctx 2 "passed")
    ctx))

(defstep #"the scenario is (verified|failed|unverified|planned)"
  (fn [ctx st]
    (report ctx)
    (vs/status-of! ctx (:id ctx) (keyword st))))

;; ---------------------------------------------------------------------------
;; Allure results are the oracle, whoever wrote them

(defstep #"a stamped scenario @spec-0007"
  (fn [ctx]
    (-> (vs/one-feature! ctx [{:id "spec-0007" :title "The scenario"}])
        (assoc :id "spec-0007"))))

(defstep #"an Allure result labelled spec=spec-0007 with status (\S+)"
  (fn [ctx st]
    (vs/project-results! ctx [["spec-0007" st]])))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx]
    (vs/project-results! ctx [["spec-0007" "passed"] ["spec-0007" "failed"]])))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (let [ids (mapv #(format "spec-%04d" %) (range 1 11))]
      (-> (vs/one-feature! ctx (for [id ids] {:id id :title (str "Scenario " id)}))
          (assoc :ids ids)))))

(defstep #"Allure results for nine of them"
  (fn [ctx]
    (vs/project-results! ctx (for [id (take 9 (:ids ctx))] [id "passed"]))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (let [out (report ctx)
          scs (:scenarios out)]
      (s/check! (= 10 (count scs)) (str "the report lists " (count scs) " scenarios"))
      (s/check! (= (set (:ids ctx)) (set (map :id scs))) (str "the report lists " (pr-str (map :id scs))))
      (s/check! (= 10 (reduce + (vals (select-keys (:counts out) [:verified :failed :unverified :planned]))))
                (str "the report counts " (pr-str (:counts out)))))
    nil))

(defstep #"the tenth is unverified"
  (fn [ctx]
    (vs/status-of! ctx (last (:ids ctx)) :unverified)))

;; ---------------------------------------------------------------------------
;; results.feature, read through the status report

(defstep #"an Allure result labelled spec=(\S+)"
  (fn [ctx id]
    (-> (vs/one-feature! ctx [{:id "spec-0001" :title "A scenario"}])
        (vs/project-results! [[id "passed"]])
        (assoc :unknown id))))

(defstep #"no scenario in the spec is tagged @(\S+)"
  (fn [ctx id]
    (doseq [[path text] (spec-files (:dir ctx))]
      (s/check! (not (str/includes? text (str "@" id))) (str path " holds @" id)))
    nil))

(defstep #"the report lists (\S+) as an unknown result"
  (fn [ctx id]
    (let [out (report ctx)]
      (s/check! (some #(= id (:spec %)) (:unknown-results out))
                (str "the report's unknown results are " (pr-str (:unknown-results out)))))
    nil))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [out (report ctx)]
      (s/check! (every? #(= :unverified (:status %)) (:scenarios out))
                (str "a scenario counted the result: " (pr-str (:scenarios out))))
      (s/check! (= 1 (count (:scenarios out))) (str "the report lists " (pr-str (map :id (:scenarios out))))))
    nil))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [out (report ctx)]
      (s/check! (empty? (:unknown-results out)) (str "unknown results: " (pr-str (:unknown-results out))))
      (s/check! (not-any? #(str/includes? % "result-unknown") (s/err-lines (kc/the-run ctx)))
                (str "the error stream names an unknown result\n" (s/show (kc/the-run ctx)))))
    nil))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (vs/one-feature! ctx [{:id "spec-0001" :title "A scenario"}])]
      (s/write! (vs/results-dir (:dir ctx)) "a-container.json" "{}")
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx]
    (kc/refused-naming! ctx "no test run wrote results there")
    nil))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (vs/one-feature! ctx [{:id "spec-0001" :title "A scenario"}])
          rd (vs/results-dir (:dir ctx))
          rel (str (:results-dir (kc/config-of (:dir ctx))) "/broken-result.json")]
      (vs/result! rd "spec-0001" "passed")
      (s/write! rd "broken-result.json" "{\"name\": \"half a result")
      (assoc ctx :file rel))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (filter #(str/includes? % (:file ctx)) (s/err-lines r))]
      (s/check! (#{0 1} (:exit r)) (str "status could not run\n" (s/show r)))
      (s/check! (= 1 (count ls)) (str (count ls) " lines name " (:file ctx) "\n" (s/show r)))
      (s/check! (str/includes? (first ls) "unreadable") (str "the line does not say unreadable: " (first ls))))
    nil))

(defn- js-result
  "An Allure result as allure-js writes it."
  [id status]
  (cond-> {:uuid (str (random-uuid)) :historyId (str (random-uuid)) :testCaseId (str (random-uuid))
           :name (str "verifies " id) :fullName (str "test/alpha.spec.js#verifies " id)
           :status status :stage "finished" :start 1700000000000 :stop 1700000000123
           :steps [{:name "arrange" :status status :stage "finished" :steps [] :attachments [] :parameters []}]
           :attachments [] :parameters [] :links []
           :labels [{:name "language" :value "javascript"} {:name "framework" :value "vitest"}
                    {:name "host" :value "ci"} {:name "thread" :value "pid-1"}
                    {:name "spec" :value id} {:name "suite" :value "alpha"}]}
    (not= "passed" status) (assoc :statusDetails {:message "AssertionError: expected 1 to be 2" :trace "at alpha.spec.js:3"})))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (vs/one-feature! ctx [{:id "spec-0001" :title "One"} {:id "spec-0002" :title "Two"}])
          rd (vs/results-dir (:dir ctx))]
      (vs/write-result! rd (js-result "spec-0001" "passed"))
      (vs/write-result! rd (js-result "spec-0002" "failed"))
      (s/write! rd "0b1c-container.json" "{\"uuid\":\"0b1c\",\"children\":[]}")
      (s/write! rd "0b1c-attachment.txt" "a log")
      (assoc ctx :expected {"spec-0001" :verified "spec-0002" :failed}))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (report ctx)
    (let [got (vs/statuses ctx)]
      (s/check! (= (:expected ctx) got) (str "the statuses are " (pr-str got) ", not " (pr-str (:expected ctx)))))
    nil))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (let [ctx (vs/one-feature! ctx [{:id "spec-0001" :title "A scenario"}])]
      (vs/result! (vs/results-dir (:dir ctx)) nil "passed")
      ctx)))

