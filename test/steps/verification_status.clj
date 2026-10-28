(ns steps.verification-status
  "Steps of the component Status (dhf/spec/05-verify-by-results/status.feature),
  and the helpers the verification context's steps share."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def results-dir "dhf/allure-results")

(defn scenarios-project!
  "A project whose one feature file holds the stamped scenarios IDS (numbers), tagged @wip where in WIP."
  [ctx ids & [{:keys [wip]}]]
  (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                   (k/feature-text "Thing" ["UN-001"] (for [i ids] {:id (format "spec-%04d" i) :title (str "Scenario " i)
                                                                                 :wip? (contains? (set wip) i)}))}
                        :ledger (reduce max 0 ids)}))

(defn result! [ctx id status & [m]] (k/result-json! ctx results-dir (merge {:spec id :status status} m)) ctx)

(defn status-of [ctx id]
  (some #(when (= id (:id %)) (:status %)) (get-in ctx [:result :status :scenarios])))

(defn status-is! [ctx id st]
  (k/check! (= st (status-of ctx id)) id " is " (status-of ctx id) ", not " st "; " (k/shows ctx))
  ctx)

(defstep #"a project with the stamped scenarios @spec-0001 \"One\", @spec-0002 \"Two\" and @spec-0003 \"Three\", in dhf/spec/a/b.feature at lines 4, 8 and 12, and @spec-0003 tagged @wip"
  (fn [ctx]
    (k/spec-project! ctx {:model (assoc k/kernel-model :kernel [{:id :kernel/b :name "B" :spec "a/b" :description "The b"}])
                          :features {"dhf/spec/a/b.feature"
                                     (str "@UN-001\nFeature: B\n  @spec-0001 @e2e\n  Scenario: One\n    Given x\n\n"
                                          "  @spec-0002 @e2e\n  Scenario: Two\n    Given x\n\n"
                                          "  @spec-0003 @e2e @wip\n  Scenario: Three\n    Given x\n")}})))

(defstep #"a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099"
  (fn [ctx]
    (let [d (str (:base ctx) "/dir")
          f (k/result-json! ctx d {:spec "spec-0099"})]
      (k/result-json! ctx d {:spec "spec-0001"})
      (-> ctx (assoc-in [:ph "DIR"] d) (assoc ::unknown-file (str d "/" f))))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:status \{:results DIR .*"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [d (get-in ctx [:ph "DIR"])
          expected {:status {:results d
                             :scenarios [{:id "spec-0001" :title "One" :file "dhf/spec/a/b.feature" :line 4 :status :verified}
                                         {:id "spec-0002" :title "Two" :file "dhf/spec/a/b.feature" :line 8 :status :unverified}
                                         {:id "spec-0003" :title "Three" :file "dhf/spec/a/b.feature" :line 12 :status :planned}]
                             :counts {:verified 1 :failed 0 :unverified 1 :planned 1}
                             :unknown-results [{:file (::unknown-file ctx) :spec "spec-0099"}]}}]
      (k/check! (= expected (:result ctx)) "status printed " (pr-str (:result ctx)) "\nexpected " (pr-str expected))
      ctx)))

(defstep #"a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario"
  (fn [ctx] (-> (scenarios-project! ctx [1 2] {:wip [1]}) (result! "spec-0002" "passed"))))

(defstep #"it lists the scenario as planned"
  (fn [ctx] (k/exits! ctx 0) (status-is! ctx "spec-0001" :planned)))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx] (scenarios-project! ctx [1] {:wip [1]})))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (-> ctx (result! "spec-0001" "skipped") (result! "spec-0001" "passed"))))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx] (k/exits! ctx 0) (status-is! ctx "spec-0001" :planned)))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx] (-> (scenarios-project! ctx [1 2 3]) (result! "spec-0001" "passed") (result! "spec-0002" "failed"))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (k/exits! ctx 0)
    (-> ctx (status-is! "spec-0001" :verified) (status-is! "spec-0002" :failed) (status-is! "spec-0003" :unverified))))

(defn row [n] {:parameters [{:name "n" :value (str n)}]})

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx] (-> ctx (result! "spec-0001" "passed" (row 1)) (result! "spec-0001" "passed" (row 2)) (result! "spec-0001" "failed" (row 3)))))

(defstep #"the scenario is (verified|failed|unverified)"
  (fn [ctx st] (k/exits! ctx 0) (status-is! ctx (or (::id ctx) "spec-0001") (keyword st))))

(defstep #"three passing Allure results labelled with its id, none of which names its row"
  (fn [ctx] (-> ctx (result! "spec-0001" "passed") (result! "spec-0001" "passed") (result! "spec-0001" "passed"))))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx] (-> ctx (result! "spec-0001" "passed" (row 1)) (result! "spec-0001" "passed" (row 2)))))

(defstep #"a stamped scenario @spec-0007"
  (fn [ctx] (assoc (scenarios-project! ctx [7]) ::id "spec-0007")))

(defstep #"an Allure result labelled spec=spec-0007 with status (passed|failed|broken|skipped|unknown)"
  (fn [ctx st] (result! ctx "spec-0007" st)))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx] (-> ctx (result! "spec-0007" "passed") (result! "spec-0007" "failed"))))

(defstep #"ten stamped scenarios"
  (fn [ctx] (scenarios-project! ctx (range 1 11))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (doseq [i (range 1 10)] (result! ctx (format "spec-%04d" i) "passed")) ctx))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [c (get-in ctx [:result :status :counts])]
      (k/check! (= 10 (count (get-in ctx [:result :status :scenarios]))) "the report lists " (count (get-in ctx [:result :status :scenarios])))
      (k/check! (= 10 (reduce + (vals c))) "the counts are " (pr-str c))
      ctx)))

(defstep #"the tenth is unverified"
  (fn [ctx] (status-is! ctx "spec-0010" :unverified)))
