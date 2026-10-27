(ns steps.spec-check
  "Step definitions of dhf/spec/spec/check.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn core!
  "The project whose core feature holds scenarios (feature-text maps), the ledger at issued."
  [ctx scenarios & {:keys [issued tags description]}]
  (k/project! ctx {:features {k/core-feature (k/feature-text {:name "Core" :tags (or tags ["UN-001"]) :description description
                                                              :scenarios scenarios})}
                   :extra {"dhf/spec/ids.edn" (str "{:issued " (or issued 1) "}\n")}}))

(defn tagged [n & tags] (assoc (k/sc n (str "Scenario " n)) :tags (vec tags)))

(defstep #"a scenario tagged both @spec-0007 and @spec-0008"
  (fn [ctx] (-> (core! ctx [(tagged 7 "spec-0007" "spec-0008" "e2e")] :issued 8) (k/names+ "spec-0007" "spec-0008"))))

(defstep #"two scenarios both tagged @spec-0007"
  (fn [ctx]
    (let [ctx (core! ctx [(tagged 7 "spec-0007" "e2e")] :issued 7)]
      (k/write-files! (:dir ctx) {"dhf/spec/kernel/other.feature"
                                  (k/feature-text {:name "Other" :tags ["UN-001"] :scenarios [(tagged 7 "spec-0007" "e2e")]})})
      (k/update-edn! ctx "dhf/spec/model.edn" update :kernel conj
                     {:id :kernel/other :name "Other" :description "The other component" :spec "kernel/other"})
      (k/names+ ctx k/core-feature "dhf/spec/kernel/other.feature" "spec-0007"))))

(defstep #"a scenario on line 30 of a feature file with no @spec- tag"
  (fn [ctx]
    (let [ctx (k/project! ctx)
          head (str "@UN-001\nFeature: Core\n" (str/join (repeat 26 "  # a comment\n")) "  @e2e\n")]
      (k/write-files! (:dir ctx) {k/core-feature (str head "  Scenario: Unstamped\n    Given a step\n")})
      (assert (= 30 (k/line-of (slurp (fs/file (:dir ctx) k/core-feature)) "Scenario: Unstamped")))
      ctx)))

(defstep #"it fails, names the file and line 30, and says to run \"rdm-spec stamp\""
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx (str k/core-feature ":30") "rdm-spec stamp")))

(defstep #"a scenario tagged @spec-0901, an id above every id the ledger has issued"
  (fn [ctx] (core! ctx [(tagged 901 "spec-0901" "e2e")] :issued 41)))

(defstep #"it fails, names spec-0901 and says the ledger never issued it"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0901" "never issued")))

(defstep #"a stamped scenario tagged @(e2e|contract)"
  (fn [ctx level] (core! ctx [(tagged 1 "spec-0001" level)])))

(defstep #"the scenario passes the level check"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? #(re-find #"level" %) (k/err-lines ctx)) (:err ctx))
    ctx))

(defstep #"a stamped scenario with no level tag"
  (fn [ctx] (-> (core! ctx [(tagged 1 "spec-0001")]) (k/names+ "spec-0001" "e2e" "contract"))))

(defstep #"a stamped scenario tagged both @e2e and @contract"
  (fn [ctx] (-> (core! ctx [(tagged 1 "spec-0001" "e2e" "contract")]) (k/names+ "spec-0001" "e2e" "contract"))))

(defstep #"a stamped scenario tagged @persona"
  (fn [ctx] (core! ctx [(tagged 1 "spec-0001" "e2e" "persona")])))

(defstep #"it fails, names the scenario's id and @persona, and lists the tags a scenario may carry"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0001" "@persona" "@e2e" "@contract" "@wip" "@UN-")))

(defstep #"a feature tagged @UN-003"
  (fn [ctx]
    (let [ctx (core! ctx [(tagged 1 "spec-0001" "e2e")] :tags ["UN-003"])]
      (k/update-edn! ctx "dhf/spec/needs.edn" assoc "UN-003" {:text "A third need" :sources ["demo:README.md@abc1234"]}))))

(defstep #"a scenario under it carries no need tag of its own"
  (fn [ctx]
    (assert (not (re-find #"(?m)^\s+@.*UN-" (slurp (fs/file (:dir ctx) k/core-feature)))))
    ctx))

(defstep #"no results directory exists"
  (fn [ctx] (assert (not (fs/exists? (fs/path (:dir ctx) "dhf/allure-results")))) ctx))

(defstep #"it lists the scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= ["spec-0001"] (map :id (get-in (k/edn-out ctx) [:trace :scenarios]))) (:out ctx))
    ctx))

(defstep #"a stamped scenario with no need tag on it, its rule or its feature"
  (fn [ctx] (-> (core! ctx [(tagged 1 "spec-0001" "e2e")] :tags []) (k/names+ "spec-0001"))))

(defstep #"the declared needs are UN-001 to UN-005"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/update-edn! ctx "dhf/spec/needs.edn"
                     (fn [_] (into {} (for [i (range 1 6)] [(format "UN-%03d" i) {:text "A need" :sources ["demo:README.md@abc1234"]}])))))))

(defstep #"a scenario tagged @UN-009"
  (fn [ctx] (-> (core! ctx [(tagged 1 "spec-0001" "e2e" "UN-009")]) (k/names+ "UN-009" "spec-0001"))))

(defstep #"the need UN-004 with no sources"
  (fn [ctx]
    (-> (k/project! ctx)
        (k/update-edn! "dhf/spec/needs.edn" assoc "UN-004" {:text "A need resting on nothing" :sources []})
        (k/names+ "UN-004"))))

(defstep #"a spec whose one stamped scenario is tagged @wip"
  (fn [ctx] (core! ctx [(tagged 1 "spec-0001" "e2e" "wip")])))

(defstep #"a feature file whose description says the parser is written in Cobalt"
  (fn [ctx]
    (-> (core! ctx [(tagged 1 "spec-0001" "e2e")] :description "The parser is written in Cobalt.")
        (k/names+ (str k/core-feature ":3") "Cobalt"))))
