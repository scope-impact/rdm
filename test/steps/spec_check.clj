(ns steps.spec-check
  "Steps of the component Spec check (dhf/spec/02-write-the-spec/check.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def feature "dhf/spec/demo/thing.feature")

(defn with-scenarios
  "A project whose one feature file (tagged NEED-TAGS) holds the raw scenario TEXTS."
  [ctx texts & [{:keys [ledger feature-tags]}]]
  (k/spec-project! ctx {:features {feature (str (str/join " " (or feature-tags ["@UN-001"])) "\nFeature: Thing\n" (str/join texts))}
                        :ledger (or ledger 10)}))

(defn sc [tags title] (str "\n  " tags "\n  Scenario: " title "\n    Given a thing\n"))

(defn findings [ctx] (remove #(re-find #"(^|: )warning: " %) (k/err-lines ctx)))

(defstep #"a scenario tagged both @spec-0007 and @spec-0008"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0007 @spec-0008 @e2e" "Two ids")])))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/demo/thing.feature:5" "spec-0007" "spec-0008") ctx))

(defstep #"two scenarios both tagged @spec-0007"
  (fn [ctx]
    (k/spec-project! ctx {:model (assoc k/kernel-model :kernel [{:id :kernel/thing :name "Thing" :spec "demo/thing" :description "One"}
                                                                {:id :kernel/other :name "Other" :spec "demo/other" :description "Two"}])
                          :features {feature (k/feature-text "Thing" ["UN-001"] [{:id "spec-0007" :title "One"}])
                                     "dhf/spec/demo/other.feature" (k/feature-text "Other" ["UN-001"] [{:id "spec-0007" :title "Two"}])}
                          :ledger 10})))

(defstep #"it fails and names both files and the id"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/err-line-has! ctx "spec-0007" "dhf/spec/demo/thing.feature" "dhf/spec/demo/other.feature")
    ctx))

(defstep #"a scenario on line 30 of a feature file with no @spec- tag"
  (fn [ctx]
    (with-scenarios ctx [(str/join (repeat 26 "  # padding\n")) "  @e2e\n  Scenario: Not stamped\n    Given a thing\n"])))

(defstep #"it fails, names the file and line 30, and says to run \"rdm-spec stamp\""
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/demo/thing.feature:30:" "rdm-spec stamp") ctx))

(defstep #"a scenario tagged @spec-0901, an id above every id the ledger has issued"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0901 @e2e" "Unissued")] {:ledger 10})))

(defstep #"it fails, names spec-0901 and says the ledger never issued it"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0901" "the ledger never issued it") ctx))

(defstep #"a stamped scenario tagged (@e2e|@contract)"
  (fn [ctx level] (with-scenarios ctx [(sc (str "@spec-0001 " level) "Levelled")])))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(str/includes? % "level") (findings ctx)) "a finding about the level: " (k/shows ctx))
    ctx))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0001" "No level")])))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0001" "@e2e" "@contract") ctx))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0001 @e2e @contract" "Two levels")])))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0001" "@e2e" "@contract") ctx))

(defstep #"a stamped scenario tagged @persona"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0001 @e2e @persona" "A persona")])))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0001" "@persona" "@e2e" "@contract" "@wip" "@UN-") ctx))

(defstep #"a feature tagged @UN-003"
  (fn [ctx] (with-scenarios ctx [] {:feature-tags ["@UN-003"]})))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx] (k/write! ctx feature (str (k/slurp-rel ctx feature) (sc "@spec-0001 @e2e" "Inherits its need")))))

(defstep #"no results directory exists"
  (fn [ctx] (k/delete! ctx "dhf/allure-results")))

(defstep #"it lists the scenario"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= ["spec-0001"] (map :id (get-in ctx [:result :trace :scenarios]))) "the trace lists " (pr-str (:result ctx)))
    ctx))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0001 @e2e" "No need")] {:feature-tags []})))

(defstep #"it fails and names the scenario's id"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0001") ctx))

(defstep #"the declared needs are UN-001 to UN-005"
  (fn [ctx]
    (k/spec-project! ctx {:needs (into {} (for [i (range 1 6)] [(format "UN-%03d" i) {:text "A need" :sources ["demo:README.md@abc1234"]}]))})))

(defstep #"a scenario tagged @UN-009"
  (fn [ctx] (k/write! ctx feature (str (k/slurp-rel ctx feature) (sc "@spec-0002 @e2e @UN-009" "An undeclared need")))
    (k/write! ctx "dhf/spec/ids.edn" "{:issued 2}\n")))

(defstep #"it fails and names UN-009 and the scenario's id"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "UN-009" "spec-0002") ctx))

(defstep #"the need UN-004 with no sources"
  (fn [ctx]
    (k/spec-project! ctx {:needs (assoc k/demo-needs "UN-004" {:text "A need resting on nothing" :sources []})})))

(defstep #"it fails and names UN-004"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "UN-004") ctx))

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx] (with-scenarios ctx [(sc "@spec-0001 @e2e @wip" "Planned")])))

(defstep #"a feature file whose description says the parser is written in Cobalt"
  (fn [ctx]
    (k/write! ctx feature "@UN-001\nFeature: Thing\n  The parser is written in Cobalt.\n\n  @spec-0001 @e2e\n  Scenario: The thing works\n    Given a thing\n")))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/demo/thing.feature:3:" "Cobalt") ctx))

;; ------------------------------------------------------------------ slices

(def plan-ok
  "# Slice 12: Several specs\n\n## Goal\n\nA project keeps several specs.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- specs\n\n**Out:**\n\n- the rest\n\n## Decisions\n\nNone yet.\n")

(defn slices-project! [ctx plan]
  (let [ctx (k/spec-project! ctx {:features {"dhf/spec/12-several-specs/thing.feature"
                                             (k/feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "The thing works"}])}})]
    (k/config! ctx assoc :spec-layout :slices)
    (if plan (k/write! ctx "dhf/spec/12-several-specs/plan.md" plan) ctx)))

(defstep #"a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/plan.md with the headings \"Goal\", \"References\", \"Scope\" and \"Decisions\", whose References name UN-001 and whose Scope has \"In\" and \"Out\""
  (fn [ctx] (slices-project! ctx plan-ok)))

(defstep #"it exits 0 with no finding about the slice"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(str/includes? % "12-several-specs") (k/err-lines ctx)) "a line names the slice: " (k/shows ctx))
    ctx))

(defstep #"a configuration with :spec-layout :slices, and a slice 12-several-specs whose (.+)"
  (fn [ctx plan]
    (slices-project! ctx (case plan
                           "folder holds no plan.md" nil
                           "plan.md has no \"Goal\" heading" (str/replace plan-ok "## Goal" "## Aim")
                           "plan.md's References name no user need" (str/replace plan-ok "- UN-001" "- the wiki")
                           "plan.md's References name UN-099, which no need is" (str/replace plan-ok "- UN-001" "- UN-099")))))

(defstep #"it exits 1 with the finding (slice-unplanned|slice-unfounded), naming dhf/spec/12-several-specs/plan.md"
  (fn [ctx rule]
    (k/exits! ctx 1)
    (k/err-line-has! ctx "dhf/spec/12-several-specs/plan.md" (str rule ": "))
    ctx))
