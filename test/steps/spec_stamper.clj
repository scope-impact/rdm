(ns steps.spec-stamper
  "Steps of the component Stamper (dhf/spec/02-write-the-spec/stamper.feature)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def feature "dhf/spec/demo/thing.feature")

(defn ledger [ctx] (:issued (edn/read-string (k/slurp-rel ctx "dhf/spec/ids.edn"))))

(defn add-unstamped! [ctx]
  (k/write! ctx feature (str (k/slurp-rel ctx feature) "\n  @e2e\n  Scenario: Not stamped yet\n    Given a thing\n")))

(defstep #"the ledger records (\d+) as the highest id issued"
  (fn [ctx n]
    (if (:runs ctx)
      (do (k/check! (= (parse-long n) (ledger ctx)) "the ledger records " (ledger ctx)) ctx)
      (let [ctx (if (:dir ctx) ctx (k/spec-project! ctx))]
        (k/write! ctx "dhf/spec/ids.edn" (str ";; the ledger\n{:issued " n "}\n"))))))

(defstep #"a scenario in the spec carries no id tag" (fn [ctx] (add-unstamped! (if (:dir ctx) ctx (k/spec-project! ctx)))))

(defstep #"the scenario is tagged @spec-0042"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (re-find #"@spec-0042 @e2e\n  Scenario: Not stamped yet" (k/slurp-rel ctx feature)) "the scenario is not tagged @spec-0042:\n" (k/slurp-rel ctx feature))
    ctx))

(defstep #"every scenario in the spec carries an id" (fn [ctx] (k/spec-project! ctx)))

(defstep #"the ledger does not change"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= 1 (ledger ctx)) "the ledger records " (ledger ctx)) ctx))

(defstep #"the scenario tagged @spec-0042 has been deleted"
  (fn [ctx] (k/write! ctx feature (k/feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "The thing works"}]))))

(defstep #"a new scenario carries no id tag" (fn [ctx] (add-unstamped! ctx)))

(defstep #"the new scenario is tagged @spec-0043"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (re-find #"@spec-0043 @e2e\n  Scenario: Not stamped yet" (k/slurp-rel ctx feature)) "the new scenario is not @spec-0043")
    ctx))

(def two-components
  (assoc k/kernel-model :kernel [{:id :kernel/thing :name "Thing" :spec "demo/thing" :description "Does the thing"}
                                 {:id :kernel/other :name "Other" :spec "demo/other" :description "Does another thing"}]))

(defstep #"a scenario tagged @spec-0007 in one component's feature file"
  (fn [ctx]
    (k/spec-project! ctx {:model two-components
                          :features {feature (k/feature-text "Thing" ["UN-001"] [{:id "spec-0007" :title "Seven"}])
                                     "dhf/spec/demo/other.feature" (k/feature-text "Other" ["UN-001"] [])}
                          :ledger 9})))

(defstep #"its title is reworded and it is moved to another component's feature file"
  (fn [ctx]
    (k/write! ctx feature (k/feature-text "Thing" ["UN-001"] []))
    (k/write! ctx "dhf/spec/demo/other.feature" (k/feature-text "Other" ["UN-001"] [{:id "spec-0007" :title "Seven, reworded"}]))))

(defstep #"it is still tagged @spec-0007"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (re-find #"@spec-0007 @e2e\n  Scenario: Seven, reworded" (k/slurp-rel ctx "dhf/spec/demo/other.feature")) "it is no longer @spec-0007")
    (k/check! (= 9 (ledger ctx)) "the ledger moved")
    ctx))

(defstep #"the project's configuration sets the id prefix to \"loyly\""
  (fn [ctx]
    (k/spec-project! ctx {:prefix "loyly"
                          :features {feature (k/feature-text "Thing" ["UN-001"] [{:id "loyly-0001" :title "One"}])}})))

(defstep #"the scenario is tagged with an id starting @loyly-"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (re-find #"@loyly-0002 @e2e\n  Scenario: Not stamped yet" (k/slurp-rel ctx feature)) "not tagged @loyly-")
    ctx))
