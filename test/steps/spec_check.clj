(ns steps.spec-check
  "Steps of the component Spec check (02-write-the-spec/check.feature,
  12-several-specs/check.feature, 13-slice-development/check.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [steps.spec-parser :as sp]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn cart [& scenarios] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios (vec scenarios)})}))

(defstep #"a scenario tagged both @spec-0007 and @spec-0008"
  (fn [_] (cart {:id "spec-0007" :tags ["spec-0008"] :title "Twice"})))

(defstep #"it fails and names the scenario and both ids"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/shop/cart.feature:" "Twice" "spec-0007" "spec-0008") ctx))

(defstep #"two scenarios both tagged @spec-0007"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0007"}]})
                            "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0007"}]})})))

(defstep #"it fails and names both files and the id"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0007" "dhf/spec/shop/cart.feature" "dhf/spec/shop/till.feature") ctx))

(defstep #"a scenario on line 30 of a feature file with no @spec- tag"
  (fn [_]
    (let [head (str "@UN-001\nFeature: Cart\n" (apply str (repeat 25 "  # a comment\n")) "\n")
          text (str head "  @e2e\n  Scenario: Unstamped\n    Given a thing\n")]
      (k/spec-project! {"shop/cart.feature" text}))))

(defstep #"it fails, names the file and line 30, and says to run \"rdm-spec stamp\""
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/shop/cart.feature:30:" "rdm-spec stamp") ctx))

(defstep #"a scenario tagged @spec-0901, an id above every id the ledger has issued"
  (fn [_] (cart {:id "spec-0901"})))

(defstep #"it fails, names spec-0901 and says the ledger never issued it"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0901" "ledger never issued") ctx))

(defstep #"a stamped scenario tagged (@e2e|@contract)"
  (fn [_ level] (cart {:id "spec-0001" :level (subs level 1)})))

(defstep #"the scenario passes the level check"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (not (re-find #"level" (str (:err ctx)))) ctx "a level finding") ctx))

(defstep #"a stamped scenario with no level tag" (fn [_] (cart {:id "spec-0001" :level nil})))

(defstep #"it fails and names the scenario's id and the two levels"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0001" "@e2e" "@contract") ctx))

(defstep #"a stamped scenario tagged both @e2e and @contract" (fn [_] (cart {:id "spec-0001" :level "e2e" :tags ["contract"]})))

(defstep #"it fails and names the scenario's id and both levels"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0001" "@e2e" "@contract") ctx))

(defstep #"a stamped scenario tagged @persona" (fn [_] (cart {:id "spec-0001" :tags ["persona"]})))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0001" "@persona" "@e2e" "@contract" "@UN-" "@wip") ctx))

(defstep #"a feature tagged @UN-003"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :tags ["UN-003"] :scenarios [{:id "spec-0001" :title "Inherits"}]})})))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx] (k/expect! (not (str/includes? (k/slurp-in (:dir ctx) "dhf/spec/shop/cart.feature") "@spec-0001 @e2e @UN")) ctx "it has its own") ctx))

(defstep #"no results directory exists"
  (fn [ctx] (k/expect! (not (fs/exists? (k/path (:dir ctx) "dhf/allure-results"))) ctx "a results directory exists") ctx))

(defstep #"it lists the scenario"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (some #(= "spec-0001" (:id %)) (get-in ctx [:edn :trace :scenarios])) ctx "the trace does not list spec-0001")
    ctx))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :tags [] :scenarios [{:id "spec-0001"}]})})))

(defstep #"it fails and names the scenario's id"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/shop/cart.feature:" "spec-0001") ctx))

(defstep #"the declared needs are UN-001 to UN-005"
  (fn [_] (k/spec-project! {} :needs (into {} (for [i (range 1 6)] [(format "UN-%03d" i) {:text "t" :sources ["loyly:README.md@17d68d2"]}])))))

(defstep #"a scenario tagged @UN-009"
  (fn [ctx] (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :tags [] :scenarios [{:id "spec-0001" :tags ["UN-009"]}]})) ctx))

(defstep #"it fails and names UN-009 and the scenario's id"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "UN-009" "spec-0001") ctx))

(defstep #"the need UN-004 with no sources"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}
                           :needs (assoc k/default-needs "UN-004" {:text "A need resting on nothing." :sources []}))))

(defstep #"it fails and names UN-004"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/needs.edn:" "UN-004") ctx))

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001" :wip true}]})
                            "shop/till.feature" (k/feature-text {:name "Till" :scenarios []})})))

(defstep #"a feature file whose description says the parser is written in Cobalt"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/spec/shop/cart.feature"
              "@UN-001\nFeature: Cart\n  The parser is written in Cobalt.\n\n  @spec-0001 @e2e\n  Scenario: One\n    Given a thing\n")
    ctx))

(defstep #"it fails and names the file, the line and the word"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/shop/cart.feature:3:" "Cobalt") ctx))

;; ------------------------------------------------------------------ 12

(defstep #"two specs, auth and infra, where infra's feature file names a need its needs.edn does not hold, and auth's names UN-001, which only auth's needs.edn holds"
  (fn [_]
    (k/two-specs! :auth-needs {"UN-001" {:text "t" :sources ["loyly:README.md@17d68d2"]}}
                   :infra-needs {"UN-002" {:text "t" :sources ["loyly:README.md@17d68d2"]}}
                   :auth-features {"auth/core.feature" (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [{:id "auth-0001"}]})}
                   :infra-features {"infra/core.feature" (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [{:id "infra-0001"}]})})))

(defstep #"it exits 1 with one finding, naming infra's feature file and the need, and none about auth"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [fs* (k/finding-lines ctx)]
      (k/expect! (= 1 (count fs*)) ctx "not exactly one finding")
      (k/expect! (re-find #"^dhf/sdd/infra/infra/core\.feature:\d+: .*UN-001" (first fs*)) ctx "the finding is not infra's need"))
    ctx))

(defstep #"a need, a component and a context resolve within their own spec only"
  (fn [ctx] (k/expect! (not-any? #(str/includes? % "dhf/sdd/auth") (k/finding-lines ctx)) ctx "a finding about auth") ctx))

;; ------------------------------------------------------------------ 13: slices

(def slices-config-re "a configuration with :spec-layout :slices")

(defstep #"a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/plan.md with the headings \"Goal\", \"References\", \"Scope\" and \"Decisions\", whose References name UN-001 and whose Scope has \"In\" and \"Out\""
  (fn [_] (k/slices-project!)))

(defstep #"it exits 0 with no finding about the slice"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (not (str/includes? (str (:err ctx)) "12-several-specs/plan.md")) ctx "a finding about the slice") ctx))

(defstep #"a configuration with :spec-layout :slices, and a slice 12-several-specs whose (folder holds no plan.md|plan.md has no \"Goal\" heading|plan.md's References name no user need|plan.md's References name UN-099, which no need is)"
  (fn [_ plan]
    (k/slices-project! {"12-several-specs/plan.md"
                      (case plan
                        "folder holds no plan.md" nil
                        "plan.md has no \"Goal\" heading" (str/replace k/plan-text "## Goal" "## Aim")
                        "plan.md's References name no user need" (str/replace k/plan-text "- UN-001" "- the README")
                        "plan.md's References name UN-099, which no need is" (str/replace k/plan-text "- UN-001" "- UN-099"))})))

(defstep #"it exits 1 with the finding (slice-unplanned|slice-unfounded), naming dhf/spec/12-several-specs/plan.md"
  (fn [ctx rule] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/12-several-specs/plan.md" rule) ctx))

(defstep #"a configuration with :spec-layout :slices, and dhf/spec/12-several-specs/plan.md whose line under its title is (.+)"
  (fn [_ line]
    (k/slices-project! {"12-several-specs/plan.md"
                      (if (= line "no status line")
                        (str/replace k/plan-text "status: building\n\n" "")
                        (str/replace k/plan-text "status: building" (subs line 1 (dec (count line)))))})))

(defstep #"it (exits 1 with the finding slice-status, naming the plan and the four statuses|exits 1 with the finding slice-status, naming the plan)"
  (fn [ctx answer]
    (k/exits! ctx 1)
    (k/line-has! ctx "dhf/spec/12-several-specs/plan.md" "slice-status")
    (when (str/includes? answer "four")
      (k/line-has! ctx "slice-status" "planned" "ready" "building" "done"))
    ctx))

(defstep #"a configuration with :spec-layout :slices, and a slice whose plan says \"status: done\" and one of whose scenarios is tagged @wip"
  (fn [_]
    (k/slices-project! {"12-several-specs/plan.md" (str/replace k/plan-text "status: building" "status: done")
                      "12-several-specs/checklist.feature" (k/feature-text {:name "Checklist" :scenarios [{:id "spec-0010" :wip true}]})})))

(defstep #"it exits 1 with the finding slice-done-planned, naming the plan and the planned scenario"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/12-several-specs/plan.md" "slice-done-planned" "spec-0010") ctx))

(defstep #"a configuration with :spec-layout :slices, and a slice with a plan and no decisions.md"
  (fn [_] (k/slices-project! {"12-several-specs/decisions.md" nil})))

(defstep #"it exits 1 with the finding slice-undecided, naming the slice's folder"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/12-several-specs" "slice-undecided") ctx))
