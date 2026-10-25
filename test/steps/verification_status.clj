(ns steps.verification-status
  "Step definitions for dhf/spec/verification/status.feature: `rdm-spec
  status` in a small project with its own spec, its Allure results written by
  hand as any test run would write them."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- scenarios-text
  "Scenario text for the ids, each {:id .. :tags [..]}."
  [scs]
  (str/join "\n" (map fx/scenario-text scs)))

(defn- status!
  "Checks the report gives `id` the status `want` (a keyword). Returns nil,
  so a step ending with it passes its context on unchanged."
  [ctx id want]
  (let [got (fx/status-of (fx/report ctx) id)]
    (s/check! (= want got) (str id " is " (pr-str got) ", not " want "\n" (s/show (:run ctx))))))

;; --- Planned work is a scenario tagged @wip ---------------------------------

(defstep #"a stamped scenario tagged @wip, with no result"
  (fn [ctx]
    ;; Another scenario's passing result, so a test run did write results.
    (-> (assoc ctx :id "spec-0001"
               :dir (fx/one-feature (scenarios-text [{:id "spec-0001" :tags ["@wip"]} {:id "spec-0002"}])))
        (fx/add-result! "spec-0002" "passed"))))

(defstep #"it lists the scenario as planned"
  (fn [ctx]
    (s/check! (= 0 (:exit (:run ctx))) (s/show (:run ctx)))
    (status! ctx (:id ctx) :planned)))

(defstep #"a stamped scenario tagged @wip, with results"
  (fn [ctx]
    (assoc ctx :id "spec-0001"
           :dir (fx/one-feature (scenarios-text [{:id "spec-0001" :tags ["@wip"]} {:id "spec-0002"}])))))

(defstep #"an Allure result for it with status skipped, and another with status passed"
  (fn [ctx]
    (-> ctx
        (fx/add-result! (:id ctx) "skipped")
        (fx/add-result! (:id ctx) "passed"))))

(defstep #"it lists the scenario as planned, not as verified, failed or unverified"
  (fn [ctx]
    (status! ctx (:id ctx) :planned)
    (let [rep (fx/report ctx)]
      (s/check! (= 1 (count (filter #(= (:id ctx) (:id %)) (:scenarios rep))))
                (str (:id ctx) " is listed more than once\n" (s/show (:run ctx))))
      (doseq [k [:verified :failed :unverified]]
        (s/check! (not (some #{(:id ctx)} (get-in rep [:by-status k])))
                  (str (:id ctx) " is listed as " (name k) "\n" (s/show (:run ctx)))))
      nil)))

(defstep #"three stamped scenarios: one passing, one failing, one with no result"
  (fn [ctx]
    (let [dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id "spec-0002"} {:id "spec-0003"}]))
          ctx (-> (assoc ctx :dir dir)
                  (fx/add-result! "spec-0001" "passed")
                  (fx/add-result! "spec-0002" "failed"))]
      (assoc ctx :spec-before (fx/spec-snapshot dir)))))

(defstep #"it lists one as verified, one as failed and one as unverified"
  (fn [ctx]
    (status! ctx "spec-0001" :verified)
    (status! ctx "spec-0002" :failed)
    (status! ctx "spec-0003" :unverified)))

;; Shared with dhf/spec/spec/stamper.feature, whose steps register it at run
;; time (steps.spec-support/ensure-shared-steps!) unless a step file defines it.
(when (empty? (runner/matching "no file in the spec changes"))
  (defstep #"no file in the spec changes"
    (fn [ctx]
      (s/check! (:spec-before ctx) "no snapshot of the spec was taken")
      (s/check! (= (:spec-before ctx) (fx/spec-snapshot (:dir ctx))) "a file under dhf/spec changed"))))

;; --- An outline is one requirement ------------------------------------------
;; "a stamped scenario outline with three example rows" is the runner's
;; (steps.kernel-runner): spec-0001, an outline over n = 1, 2, 3, as :id.

(defstep #"Allure results for two rows passed and one failed"
  (fn [ctx]
    (reduce (fn [ctx [n status]]
              (fx/add-result! ctx (:id ctx) status
                              {:name (str "An outline over <n> [n=" n "]")
                               :parameters [{:name "n" :value (str n)}]}))
            ctx
            [[1 "passed"] [2 "failed"] [3 "passed"]])))

(defstep #"the scenario is (verified|failed|unverified|planned)"
  (fn [ctx want]
    (status! ctx (:id ctx) (keyword want))))

;; --- Allure results are the oracle, whoever wrote them ------------------------

(defstep #"a stamped scenario @(spec-\d+)"
  (fn [ctx id]
    ;; A second scenario, so the report holds more than the one asked about.
    (assoc ctx :id id :dir (fx/one-feature (scenarios-text [{:id "spec-0001"} {:id id}])))))

(defstep #"an Allure result labelled spec=(\S+) with status (\S+)"
  (fn [ctx id status]
    (fx/add-result! ctx id status)))

(defstep #"two Allure results labelled spec=(\S+), one passed and one failed"
  (fn [ctx id]
    (-> ctx
        (fx/add-result! id "passed")
        (fx/add-result! id "failed"))))

(def ^:private ten (mapv #(format "spec-%04d" %) (range 1 11)))

(defstep #"ten stamped scenarios"
  (fn [ctx]
    (assoc ctx :dir (fx/one-feature (scenarios-text (map (fn [id] {:id id}) ten))))))

(defstep #"Allure results for nine of them"
  (fn [ctx]
    (reduce #(fx/add-result! %1 %2 "passed") ctx (take 9 ten))))

(defstep #"the report counts ten scenarios"
  (fn [ctx]
    (let [rep (fx/report ctx)]
      (s/check! (= 10 (get-in rep [:counts :scenarios])) (str "it counts " (pr-str (:counts rep)) "\n" (s/show (:run ctx))))
      (s/check! (= (set ten) (set (map :id (:scenarios rep)))) (str "it lists " (mapv :id (:scenarios rep))))
      nil)))

(defstep #"the tenth is unverified"
  (fn [ctx]
    (status! ctx (last ten) :unverified)
    (s/check! (= 1 (get-in (fx/report ctx) [:counts :unverified])) (s/show (:run ctx)))))
