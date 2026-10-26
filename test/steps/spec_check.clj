(ns steps.spec-check
  "Step definitions of dhf/spec/spec/check.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def core "dhf/spec/kernel/core.feature")

(defn with-scenarios
  "A project whose core feature (tagged @UN-001 unless feature-tags say
  otherwise) holds the given scenarios."
  ([ctx scenarios] (with-scenarios ctx scenarios ["UN-001"]))
  ([ctx scenarios feature-tags]
   (k/project! ctx {:features {core (k/feature-text {:name "Core" :tags feature-tags :scenarios scenarios})}})))

(defstep #"a scenario tagged both @spec-0007 and @spec-0008"
  (fn [ctx]
    (-> (with-scenarios ctx [{:tags ["spec-0007" "spec-0008" "e2e"] :title "Two ids" :steps ["Given a step"]}])
        (k/names+ "spec-0007" "spec-0008" "Two ids"))))

(defstep #"two scenarios both tagged @spec-0007"
  (fn [ctx]
    (-> (k/project! ctx {:model-spec {:kernel ["core" "other"]}
                         :features {core (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 7 "One")]})
                                    "dhf/spec/kernel/other.feature"
                                    (k/feature-text {:name "Other" :tags ["UN-001"] :scenarios [(k/sc 7 "Two")]})}})
        (k/names+ "spec-0007" core "dhf/spec/kernel/other.feature"))))

(defstep #"a scenario on line 30 of a feature file with no @spec- tag"
  (fn [ctx]
    (let [filler (apply str (repeat 25 "  # filler\n"))]
      (k/project! ctx {:features {core (str "@UN-001\nFeature: Core\n\n" filler "  @e2e\n  Scenario: Unstamped\n    Given a step\n")}}))))

(defstep #"it fails, names the file and line 30, and says to run \"rdm-spec stamp\""
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-line-with ctx (str core ":30") "rdm-spec stamp")))

(defstep #"a scenario tagged @spec-0901, an id above every id the ledger has issued"
  (fn [ctx] (with-scenarios ctx [(k/sc 901 "Too high")])))

(defstep #"it fails, names spec-0901 and says the ledger never issued it"
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-line-with ctx "spec-0901" "never issued")))

(defstep #"a stamped scenario tagged (@e2e|@contract)"
  (fn [ctx level]
    (with-scenarios ctx [{:tags ["spec-0001" (subs level 1)] :title "Levelled" :steps ["Given a step"]}])))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(str/includes? % "level") (k/finding-rules ctx)))
    ctx))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx]
    (-> (with-scenarios ctx [{:tags ["spec-0001"] :title "No level" :steps ["Given a step"]}])
        (k/names+ "spec-0001" "@e2e" "@contract"))))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx]
    (-> (with-scenarios ctx [{:tags ["spec-0001" "e2e" "contract"] :title "Both" :steps ["Given a step"]}])
        (k/names+ "spec-0001" "@e2e" "@contract"))))

(defstep #"a stamped scenario tagged @persona"
  (fn [ctx]
    (with-scenarios ctx [{:tags ["spec-0001" "e2e" "persona"] :title "Persona" :steps ["Given a step"]}])))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-line-with ctx "spec-0001" "@persona" "@e2e" "@contract" "@wip" "@UN-")))

(defstep #"a feature tagged @UN-003"
  (fn [ctx]
    (k/project! ctx {:needs {"UN-003" {:text "Need three" :sources ["demo:README.md@abc1234"]}}
                     :features {core (k/feature-text {:name "Core" :tags ["UN-003"] :scenarios [(k/sc 1 "Inherits")]})}})))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (assert (not (str/includes? (k/scenario-text (k/sc 1 "Inherits")) "UN-")))
    ctx))

(defstep #"no results directory exists"
  (fn [ctx]
    (assert (not (fs/exists? (fs/path (k/guard! (:dir ctx)) "dhf/allure-results"))))
    ctx))

(defstep #"it lists the scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (:trace (k/edn-out ctx))]
      (assert (= ["spec-0001"] (map :id (:scenarios t))) (pr-str t))
      ctx)))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx]
    (-> (with-scenarios ctx [(k/sc 1 "No need")] [])
        (k/names+ "spec-0001"))))

(defstep #"the declared needs are UN-001 to UN-005"
  (fn [ctx]
    (k/project! ctx {:needs (into {} (for [i (range 1 6)]
                                       [(format "UN-%03d" i) {:text (str "Need " i) :sources ["demo:README.md@abc1234"]}]))})))

(defstep #"a scenario tagged @UN-009"
  (fn [ctx]
    (k/write-files! (:dir ctx) {core (k/feature-text {:name "Core" :tags ["UN-001"]
                                                      :scenarios [{:tags ["spec-0002" "e2e" "UN-009"] :title "Nine" :steps ["Given a step"]}]})})
    (k/names+ ctx "UN-009" "spec-0002")))

(defstep #"the need UN-004 with no sources"
  (fn [ctx]
    (-> (k/project! ctx {:needs {"UN-001" {:text "One" :sources ["demo:README.md@abc1234"]}
                                 "UN-004" {:text "Four" :sources []}}})
        (k/names+ "UN-004"))))

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx]
    (with-scenarios ctx [{:tags ["spec-0001" "e2e" "wip"] :title "Planned" :steps ["Given a step"]}])))

(defstep #"a feature file whose description says the parser is written in Cobalt"
  (fn [ctx]
    (k/write-files! (:dir ctx) {core (str "@UN-001\nFeature: Core\n  The parser is written in Cobalt.\n\n"
                                          (k/scenario-text (k/sc 1 "One")))})
    (k/names+ ctx (str core ":3"))))

