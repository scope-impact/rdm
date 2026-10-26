(ns steps.verification-status
  "Step definitions for dhf/spec/verification/status.feature: `rdm-spec
  status` on a small project (steps.verification-support). The outline
  Given is the kernel's (steps.kernel-runner: :dir, :id, :rows).

  Context keys set here: :dir, :id (the scenario a Then is about), :ids,
  :results (a results directory outside the project), :unknown-file (the
  name of the result naming spec-0099)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- rd [ctx] (v/results-dir (:dir ctx)))

;; ---------------------------------------------------------------------------
;; Status's result has one shape

(def ^:private b-feature
  (str "Feature: B\n  A fixture.\n"
       "  @spec-0001 @e2e\n  Scenario: One\n    Given a step\n\n"
       "  @spec-0002 @e2e\n  Scenario: Two\n    Given a step\n\n"
       "  @spec-0003 @e2e @wip\n  Scenario: Three\n    Given a step\n"))

(defstep #"a project with the stamped scenarios @spec-0001 \"One\", @spec-0002 \"Two\" and @spec-0003 \"Three\", in dhf/spec/a/b\.feature at lines 4, 8 and 12, and @spec-0003 tagged @wip"
  (fn [ctx]
    (let [ctx (v/project! ctx {})]
      (s/write! (:dir ctx) "dhf/spec/a/b.feature" b-feature)
      ctx)))

(defstep #"a results directory DIR holding a passing result for @spec-0001 and a passing result labelled spec=spec-0099"
  (fn [ctx]
    (let [d (s/tmp-dir)]
      (v/result! d "spec-0001" "passed")
      (let [f (v/result! d "spec-0099" "passed")]
        (assoc ctx :results d :unknown-file (str (fs/file-name f)))))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:status .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          d (:results ctx)
          want {:status {:results d
                         :scenarios [{:id "spec-0001" :title "One" :file "dhf/spec/a/b.feature" :line 4 :status :verified}
                                     {:id "spec-0002" :title "Two" :file "dhf/spec/a/b.feature" :line 8 :status :unverified}
                                     {:id "spec-0003" :title "Three" :file "dhf/spec/a/b.feature" :line 12 :status :planned}]
                         :counts {:verified 1 :failed 0 :unverified 1 :planned 1}
                         :unknown-results [{:file (str d "/" (:unknown-file ctx)) :spec "spec-0099"}]}}]
      (s/check! (= want (kc/stdout-edn r)) (str "standard output is not exactly\n" (pr-str want) "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Planned work

(defstep #"a stamped scenario tagged @wip, with no result, and a passing result for another stamped scenario"
  (fn [ctx]
    (-> (v/one-feature! ctx [{:id "spec-0001" :title "Planned" :wip? true} {:id "spec-0002" :title "Done"}])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"it lists the scenario as planned"
  (fn [ctx] (kc/exits! ctx 0) (v/status-of! ctx (:id ctx) :planned)))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (-> (v/one-feature! ctx [{:id "spec-0001" :title "Planned" :wip? true} {:id "spec-0002" :title "Done"}])
        (v/project-results! [["spec-0002" "passed"]])
        (assoc :id "spec-0001"))))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx] (v/project-results! ctx [[(:id ctx) "skipped"] [(:id ctx) "passed"]])))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx] (kc/exits! ctx 0) (v/status-of! ctx (:id ctx) :planned)))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (-> (v/one-feature! ctx [{:id "spec-0001" :title "Passing"} {:id "spec-0002" :title "Failing"}
                             {:id "spec-0003" :title "Without a result"}])
        (v/project-results! [["spec-0001" "passed"] ["spec-0002" "failed"]]))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified} (v/statuses ctx))
              (str "the statuses are " (pr-str (v/statuses ctx))))
    nil))

;; ---------------------------------------------------------------------------
;; An outline is one requirement (the outline itself is the kernel's Given)

(defn- row-result [id values status]
  (assoc (v/result-map id status) :parameters (vec (for [[n x] values] {:name n :value x}))))

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (let [[a b c] (:rows ctx)]
      (doseq [[vs st] [[a "passed"] [b "passed"] [c "failed"]]]
        (v/write-result! (rd ctx) (row-result (:id ctx) vs st)))
      ctx)))

(defstep #"three passing Allure results labelled with its id, none of which names its row"
  (fn [ctx]
    (dotimes [_ 3] (v/write-result! (rd ctx) (v/result-map (:id ctx) "passed")))
    ctx))

(defstep #"Allure results for two rows, both passed, and none for the third"
  (fn [ctx]
    (let [[a b] (:rows ctx)]
      (doseq [vs [a b]] (v/write-result! (rd ctx) (row-result (:id ctx) vs "passed")))
      ctx)))

(defstep #"the scenario is (verified|failed|unverified)"
  (fn [ctx st] (kc/exits! ctx 0) (v/status-of! ctx (:id ctx) (keyword st))))

;; ---------------------------------------------------------------------------
;; Allure results are the oracle

(defstep #"a stamped scenario @spec-0007"
  (fn [ctx]
    (-> (v/one-feature! ctx [{:id "spec-0007" :title "Seven"}])
        (assoc :id "spec-0007"))))

(defstep #"an Allure result labelled spec=spec-0007 with status (\S+)"
  (fn [ctx st] (v/project-results! ctx [["spec-0007" st]])))

(defstep #"two Allure results labelled spec=spec-0007, one passed and one failed"
  (fn [ctx] (v/project-results! ctx [["spec-0007" "passed"] ["spec-0007" "failed"]])))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (let [scs (vec (for [i (range 1 11)] {:id (format "spec-%04d" i) :title (str "Number " i)}))]
      (assoc (v/one-feature! ctx scs) :ids (mapv :id scs)))))

(defstep #"Allure results for nine of them"
  (fn [ctx] (v/project-results! ctx (for [id (take 9 (:ids ctx))] [id "passed"]))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [out (:status (kc/stdout-edn (kc/the-run ctx)))]
      (s/check! (= 10 (count (:scenarios out)) (reduce + (vals (:counts out)))) (str "the report counts " (pr-str (:counts out))))
      nil)))

(defstep #"the tenth is unverified"
  (fn [ctx] (v/status-of! ctx (last (:ids ctx)) :unverified)))
