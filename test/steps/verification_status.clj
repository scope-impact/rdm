(ns steps.verification-status
  "Step definitions for dhf/spec/verification/results.feature and
  dhf/spec/verification/status.feature: `rdm-spec status` on a small project
  the scenario makes (steps.verification-support), its results in the
  project's configured results directory.

  The shared When (\"rdm-spec LINE\" is run), \"it exits N\" and \"no file in
  the spec changes\" are the kernel's; so is \"a stamped scenario outline
  with three example rows\" (kernel_runner.clj), which sets :dir and :id.

  Context keys: :dir, :id, :ids, :expected {id status}, :file, :unknown."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

;; ---------------------------------------------------------------------------
;; results.feature

(defstep #"an Allure result labelled spec=(spec-\d+)"
  (fn [ctx unknown]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "A known scenario"}])
        (v/project-results! [["spec-0001" "passed"] [unknown "passed"]])
        (assoc :unknown unknown :expected {"spec-0001" :verified}))))

(defstep #"no scenario in the spec is tagged @(spec-\d+)"
  (fn [ctx id]
    (doseq [f (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature")]
      (s/check! (not (str/includes? (slurp (str f)) (str "@" id))) (str f " holds @" id)))
    nil))

(defstep #"the report lists (spec-\d+) as an unknown result"
  (fn [ctx id]
    (let [out (v/out-of ctx)]
      (s/check! (some #(= id (:spec %)) (:unknown-results out))
                (str "the unknown results are " (pr-str (:unknown-results out)) "\n" (s/show (kc/the-run ctx))))
      nil)))

(defstep #"it counts toward no scenario"
  (fn [ctx]
    (let [st (v/statuses ctx)]
      (s/check! (= (:expected ctx) st) (str "the statuses are " (pr-str st) ", not " (pr-str (:expected ctx))))
      (s/check! (= (count st) (reduce + (vals (:counts (v/out-of ctx)))))
                (str "the counts are " (pr-str (:counts (v/out-of ctx)))))
      nil)))

(defstep #"the results directory exists and holds no Allure result"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [{:id "spec-0001" :title "A scenario"}])]
      (fs/create-dirs (v/results-dir (:dir ctx)))
      (s/write! (v/results-dir (:dir ctx)) "README.txt" "not a result\n")
      ctx)))

(defstep #"it exits 2 and says no test run wrote results there"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx "dhf/allure-results")]
      (s/check! (some #(str/includes? % "no test run wrote results there") (kc/cannot-run-lines r))
                (str "it does not say no test run wrote results there\n" (s/show r)))
      nil)))

(defstep #"a results directory holding one result file that is not valid JSON"
  (fn [ctx]
    (let [ctx (-> ctx
                  (v/one-feature! [{:id "spec-0001" :title "A scenario"}])
                  (v/project-results! [["spec-0001" "passed"]]))
          f (s/write! (v/results-dir (:dir ctx)) "0badcafe-result.json" "{\"name\": \"cut short")]
      (assoc ctx :file "dhf/allure-results/0badcafe-result.json"))))

(defstep #"the report names that file as unreadable, in one line on the error stream"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          ls (filter #(str/includes? % (:file ctx)) (s/err-lines r))]
      (s/check! (= 1 (count ls)) (str (count ls) " lines name " (:file ctx) "\n" (s/show r)))
      (s/check! (re-find #"(?i)unreadable|cannot be read|not valid JSON" (first ls))
                (str "the line does not say the file is unreadable: " (first ls)))
      nil)))

(defn- js-result
  "A result as a JavaScript test run (allure-js) writes it."
  [id status]
  (cond-> {:uuid (str (random-uuid))
           :historyId "b1946ac92492d2347c6235b4d2611184"
           :testCaseId "6f1ed002ab5595859014ebf0951522d9"
           :name (str "verifies " id)
           :fullName (str "spec/demo.test.js#verifies " id)
           :status status
           :stage "finished"
           :statusDetails (if (= "passed" status) {} {:message "expect(received).toBe(expected)" :trace "at Object.<anonymous>"})
           :start 1700000000000 :stop 1700000000042
           :steps [{:name "Given a step" :status status :stage "finished" :steps [] :attachments [] :parameters []}]
           :attachments [] :parameters []
           :links []
           :labels [{:name "language" :value "javascript"} {:name "framework" :value "jest"}
                    {:name "host" :value "ci"} {:name "thread" :value "1"}
                    {:name "spec" :value id} {:name "suite" :value "demo.test.js"}]}))

(defstep #"Allure results labelled with spec ids written by a JavaScript test run"
  (fn [ctx]
    (let [ctx (v/one-feature! ctx [{:id "spec-0001" :title "One"} {:id "spec-0002" :title "Two"}
                                   {:id "spec-0003" :title "Three"}])
          rd (v/results-dir (:dir ctx))]
      (v/write-result! rd (js-result "spec-0001" "passed"))
      (v/write-result! rd (js-result "spec-0002" "failed"))
      (s/write! rd "4a3c1e5d-container.json" "{\"uuid\":\"4a3c1e5d\",\"children\":[]}")
      (assoc ctx :expected {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified}))))

(defstep #"the scenarios they name are verified exactly as if any other stack had written them"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          st (v/statuses ctx)
          other (s/tmp-dir)
          _ (v/result! other "spec-0001" "passed")
          _ (v/result! other "spec-0002" "failed")
          r2 (kc/run! ctx (:dir ctx) ["status" "--results" other])
          st2 (into {} (map (juxt :id :status)) (:scenarios (kc/stdout-edn r2)))]
      (s/check! (= (:expected ctx) st) (str "the statuses are " (pr-str st) "\n" (s/show r)))
      (s/check! (= st st2) (str "another stack's results give " (pr-str st2) "\n" (s/show r2)))
      nil)))

(defstep #"an Allure result from a unit test, with no spec label"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "A scenario"} {:id "spec-0002" :title "Another"}])
        (v/project-results! [["spec-0001" "passed"] [nil "failed"]])
        (assoc :expected {"spec-0001" :verified "spec-0002" :unverified}))))

(defstep #"it is not listed as an unknown result"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          out (kc/stdout-edn r)]
      (s/check! (empty? (:unknown-results out)) (str "unknown results: " (pr-str (:unknown-results out))))
      (s/check! (not-any? #(re-find #"(?i)unknown" %) (s/err-lines r)) (str "a line names an unknown result\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; status.feature

(defstep #"a stamped scenario tagged @wip, with no result"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "Planned work" :wip? true} {:id "spec-0002" :title "Done work"}])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"it lists the scenario as planned"
  (fn [ctx] (kc/exits! ctx 0) (v/status-of! ctx (:id ctx) :planned)))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "Planned work" :wip? true}])
        (assoc :id "spec-0001"))))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (v/project-results! ctx [[(:id ctx) "skipped"] [(:id ctx) "passed"]])))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx]
    (kc/exits! ctx 0)
    (v/status-of! ctx (:id ctx) :planned)
    (let [c (:counts (v/out-of ctx))]
      (s/check! (= {:verified 0 :failed 0 :unverified 0 :planned 1} c) (str "the counts are " (pr-str c))))
    nil))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "Passing"} {:id "spec-0002" :title "Failing"}
                         {:id "spec-0003" :title "Without a result"}])
        (v/project-results! [["spec-0001" "passed"] ["spec-0002" "failed"]]))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [st (v/statuses ctx)]
      (s/check! (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified} st)
                (str "the statuses are " (pr-str st))))
    nil))

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (let [rd (v/results-dir (:dir ctx))]
      (doseq [[n st] [["1" "passed"] ["2" "passed"] ["3" "failed"]]]
        (v/write-result! rd (assoc (v/result-map (:id ctx) st)
                                   :name (str "An outline over " n " [n=" n "]")
                                   :parameters [{:name "n" :value n}])))
      ctx)))

(defstep #"the scenario is (verified|failed|unverified|planned)"
  (fn [ctx st] (kc/exits! ctx 0) (v/status-of! ctx (:id ctx) (keyword st))))

(defstep #"a stamped scenario @(spec-\d+)"
  (fn [ctx id]
    (-> ctx
        (v/one-feature! [{:id id :title "The scenario"}])
        (assoc :id id))))

(defstep #"an Allure result labelled spec=(spec-\d+) with status (\S+)"
  (fn [ctx id st] (v/project-results! ctx [[id st]])))

(defstep #"two Allure results labelled spec=(spec-\d+), one passed and one failed"
  (fn [ctx id] (v/project-results! ctx [[id "passed"] [id "failed"]])))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (let [ids (mapv #(format "spec-%04d" %) (range 1 11))]
      (-> ctx
          (v/one-feature! (vec (for [id ids] {:id id :title (str "Scenario " id)})))
          (assoc :ids ids)))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (v/project-results! ctx (for [id (butlast (:ids ctx))] [id "passed"]))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [out (v/out-of ctx)]
      (s/check! (= 10 (count (:scenarios out))) (str "the report lists " (count (:scenarios out)) " scenarios"))
      (s/check! (= 10 (reduce + (vals (:counts out)))) (str "the counts are " (pr-str (:counts out)))))
    nil))

(defstep #"the tenth is unverified"
  (fn [ctx]
    (v/status-of! ctx (last (:ids ctx)) :unverified)
    (s/check! (= 9 (count (filter #{:verified} (vals (v/statuses ctx))))) "not nine are verified")
    nil))
