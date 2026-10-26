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
                               :features {"dhf/spec/a/b.feature" b-feature}})]
      (fs/delete-if-exists (fs/path (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature"))
      ctx)))

(defstep #"a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099"
  (fn [ctx]
    (let [ctx (k/placeholder! ctx "DIR")
          d (get-in ctx [:ph "DIR"])]
      (k/write-result! d {:id "spec-0001"})
      (assoc ctx ::unknown (str d "/" (k/write-result! d {:id "spec-0099"}))))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:status \{:results DIR :scenarios \[.*\]\}\}, F the result's file as DIR names it \(DIR/name\), as trace and release name a result, DIR as given"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [d (get-in ctx [:ph "DIR"])
          want {:status {:results d
                         :scenarios [{:id "spec-0001" :title "One" :file "dhf/spec/a/b.feature" :line 4 :status :verified}
                                     {:id "spec-0002" :title "Two" :file "dhf/spec/a/b.feature" :line 8 :status :unverified}
                                     {:id "spec-0003" :title "Three" :file "dhf/spec/a/b.feature" :line 12 :status :planned}]
                         :counts {:verified 1 :failed 0 :unverified 1 :planned 1}
                         :unknown-results [{:file (::unknown ctx) :spec "spec-0099"}]}}]
      (assert (= want (k/edn-out ctx)) (str (:out ctx) "\n" (pr-str want)))
      ctx)))

(defn wip-project! [ctx]
  (vr/scenarios-project! ctx [{:tags ["spec-0001" "e2e" "wip"] :title "Planned" :steps ["Given a step"]} (k/sc 2 "Other")]))

(defstep #"a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario"
  (fn [ctx] (-> (wip-project! ctx) (vr/result! {:id "spec-0002"}))))

(defstep #"it lists the scenario as planned"
  (fn [ctx] (k/check-exit ctx 0) (assert (= :planned (vr/status-of ctx "spec-0001"))) ctx))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx] (wip-project! ctx)))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (-> ctx (vr/result! {:id "spec-0001" :status "skipped"}) (vr/result! {:id "spec-0001"}))))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx] (k/check-exit ctx 0) (assert (= :planned (vr/status-of ctx "spec-0001"))) ctx))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (let [ctx (vr/scenarios-project! ctx [(k/sc 1 "Passing") (k/sc 2 "Failing") (k/sc 3 "Unrun")])]
      (-> ctx (vr/result! {:id "spec-0001"}) (vr/result! {:id "spec-0002" :status "failed"})
          (assoc :spec-before (k/spec-snapshot ctx))))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [:verified :failed :unverified] (map #(vr/status-of ctx %) ["spec-0001" "spec-0002" "spec-0003"])))
    ctx))

(defn row-result! [ctx [name n] status]
  (vr/result! ctx {:id "spec-0001" :status status :params [["name" name] ["n" n]]}))

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx] (-> ctx (row-result! ["a" "1"] "passed") (row-result! ["b" "2"] "passed") (row-result! ["c" "3"] "failed")
                (assoc ::id "spec-0001"))))

(defstep #"three passing Allure results labelled with its id, none of which names its row"
  (fn [ctx] (-> ctx (vr/result! {:id "spec-0001"}) (vr/result! {:id "spec-0001"}) (vr/result! {:id "spec-0001"})
                (assoc ::id "spec-0001"))))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx] (-> ctx (row-result! ["a" "1"] "passed") (row-result! ["b" "2"] "passed") (assoc ::id "spec-0001"))))

(defstep #"the scenario is (failed|verified|unverified)"
  (fn [ctx status]
    (k/check-exit ctx 0)
    (let [id (or (::id ctx) (::vr-id ctx) "spec-0007")]
      (assert (= (keyword status) (vr/status-of ctx id)) (str id " is " (vr/status-of ctx id)))
      ctx)))

(defstep #"a stamped scenario @spec-0007"
  (fn [ctx] (-> (vr/scenarios-project! ctx [(k/sc 7 "Seven")]) (assoc ::id "spec-0007"))))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx] (-> ctx (vr/result! {:id "spec-0007"}) (vr/result! {:id "spec-0007" :status "failed"}))))

(defstep #"ten stamped scenarios"
  (fn [ctx] (vr/scenarios-project! ctx (for [i (range 1 11)] (k/sc i (str "Scenario " i))))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (reduce (fn [c i] (vr/result! c {:id (format "spec-%04d" i)})) ctx (range 1 10))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= 10 (count (get-in (k/edn-out ctx) [:status :scenarios]))))
    (assert (= {:verified 9 :failed 0 :unverified 1 :planned 0} (get-in (k/edn-out ctx) [:status :counts])))
    ctx))

(defstep #"the tenth is unverified"
  (fn [ctx] (assert (= :unverified (vr/status-of ctx "spec-0010"))) ctx))
