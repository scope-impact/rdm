(ns steps.verification-status
  "Step definitions of dhf/spec/verification/status.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.verification-results :as vr]))

(def b-feature
  "@UN-001
Feature: B
  @spec-0001 @e2e
  Scenario: One
    Given a step

  @spec-0002 @e2e
  Scenario: Two
    Given a step

  @spec-0003 @e2e @wip
  Scenario: Three
    Given a step
")

(defstep #"a project with the stamped scenarios @spec-0001 \"One\", @spec-0002 \"Two\" and @spec-0003 \"Three\", in dhf/spec/a/b.feature at lines 4, 8 and 12, and @spec-0003 tagged @wip"
  (fn [ctx]
    (let [ctx (k/project! ctx {:model-spec {:contexts [["a" ["b"]]]}
                               :features {"dhf/spec/a/b.feature" b-feature}
                               :extra {"dhf/spec/ids.edn" "{:issued 3}\n"}})]
      (fs/delete (fs/path (:dir ctx) k/core-feature))
      ctx)))

(defstep #"a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099"
  (fn [ctx]
    (let [ctx (k/placeholder! ctx "DIR")
          d (get-in ctx [:ph "DIR"])]
      (k/write-result! d {:id "spec-0001"})
      (assoc ctx ::unknown (k/write-result! d {:id "spec-0099"})))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:status .+"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [d (get-in ctx [:ph "DIR"])
          want {:status {:results d
                         :scenarios [{:id "spec-0001" :title "One" :file "dhf/spec/a/b.feature" :line 4 :status :verified}
                                     {:id "spec-0002" :title "Two" :file "dhf/spec/a/b.feature" :line 8 :status :unverified}
                                     {:id "spec-0003" :title "Three" :file "dhf/spec/a/b.feature" :line 12 :status :planned}]
                         :counts {:verified 1 :failed 0 :unverified 1 :planned 1}
                         :unknown-results [{:file (str d "/" (::unknown ctx)) :spec "spec-0099"}]}}]
      (when-not (= want (k/edn-out ctx)) (fail! (str "standard output is " (:out ctx) " not " (pr-str want))))
      ctx)))

(defn statuses! [ctx scenarios results]
  (let [ctx (vr/scenarios-project! ctx scenarios)]
    (k/write-files! (:dir ctx) {"dhf/spec/ids.edn" (str "{:issued " (count scenarios) "}\n")})
    (doseq [r results] (k/write-result! (vr/results-dir ctx) r))
    ctx))

(defn wip [n title] (update (k/sc n title) :tags conj "wip"))

(defstep #"a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario"
  (fn [ctx] (statuses! ctx [(wip 1 "Planned") (k/sc 2 "Done")] [{:id "spec-0002"}])))

(defstep #"it lists the scenario as planned(?:, not as verified, failed or unverified)?"
  (fn [ctx] (k/check-exit ctx 0) (assert (= :planned (vr/status-of ctx "spec-0001"))) ctx))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx] (statuses! ctx [(wip 1 "Planned")] [])))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (doseq [s ["skipped" "passed"]] (k/write-result! (vr/results-dir ctx) {:id "spec-0001" :status s})) ctx))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx] (statuses! ctx [(k/sc 1 "Passing") (k/sc 2 "Failing") (k/sc 3 "Untested")]
                       [{:id "spec-0001"} {:id "spec-0002" :status "failed"}])))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [:verified :failed :unverified] (map #(vr/status-of ctx %) ["spec-0001" "spec-0002" "spec-0003"])))
    ctx))

(defn row [n] [{"name" "n" "value" (str n)}])

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (doseq [[n s] [[1 "passed"] [2 "passed"] [3 "failed"]]]
      (k/write-result! (vr/results-dir ctx) {:id "spec-0001" :status s :parameters (row n)}))
    ctx))

(defstep #"three passing Allure results labelled with its id, none of which names its row"
  (fn [ctx] (dotimes [_ 3] (k/write-result! (vr/results-dir ctx) {:id "spec-0001"})) ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx]
    (doseq [n [1 2]] (k/write-result! (vr/results-dir ctx) {:id "spec-0001" :parameters (row n)}))
    ctx))

(defstep #"the scenario is (verified|failed|unverified|planned)"
  (fn [ctx status]
    (k/check-exit ctx 0)
    (let [id (or (some #(when (str/includes? (slurp (fs/file (:dir ctx) k/core-feature)) %) %) ["spec-0007" "spec-0001"]))]
      (assert (= (keyword status) (vr/status-of ctx id)) (:out ctx)))
    ctx))

(defstep #"a stamped scenario @spec-0007"
  (fn [ctx] (vr/scenarios-project! ctx [(k/sc 7 "Seven")])))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx]
    (let [ctx (vr/scenarios-project! ctx [(k/sc 7 "Seven")])]
      (doseq [s ["passed" "failed"]] (k/write-result! (vr/results-dir ctx) {:id "spec-0007" :status s}))
      ctx)))

(defstep #"ten stamped scenarios"
  (fn [ctx] (statuses! ctx (for [i (range 1 11)] (k/sc i (str "Scenario " i))) [])))

(defstep #"Allure results for nine of them"
  (fn [ctx] (doseq [i (range 1 10)] (k/write-result! (vr/results-dir ctx) {:id (format "spec-%04d" i)})) ctx))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= 10 (count (get-in (k/edn-out ctx) [:status :scenarios]))))
    (assert (= {:verified 9 :failed 0 :unverified 1 :planned 0} (get-in (k/edn-out ctx) [:status :counts])))
    ctx))

(defstep #"the tenth is unverified"
  (fn [ctx] (assert (= :unverified (vr/status-of ctx "spec-0010"))) ctx))
