(ns steps.verification-trace
  "Steps of the component Trace (dhf/spec/05-verify-by-results/trace.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.verification-status :as s]))

(def three-features
  {"dhf/spec/demo/one.feature" (str "@UN-003\nFeature: One\n" (k/scenario-text {:id "spec-0001" :title "Under the feature"})
                                    (k/scenario-text {:id "spec-0002" :title "Also under the feature"}))
   "dhf/spec/demo/two.feature" (str "@UN-001\nFeature: Two\n\n  @UN-003\n  Rule: The rule\n" (k/scenario-text {:id "spec-0003" :title "Under the rule"})
                                    "\n  Rule: Another rule\n" (k/scenario-text {:id "spec-0004" :title "Not under it"}))
   "dhf/spec/demo/three.feature" (str "@UN-001\nFeature: Three\n" (k/scenario-text {:id "spec-0005" :title "Tagged itself" :tags ["@UN-003"]})
                                      (k/scenario-text {:id "spec-0006" :title "Not tagged"}))})

(def three-model
  (assoc k/kernel-model :kernel (vec (for [n ["one" "two" "three"]]
                                       {:id (keyword "kernel" n) :name (str/capitalize n) :spec (str "demo/" n) :description "A part"}))))

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx] (k/spec-project! ctx {:model three-model :features three-features})))

(defn trace [ctx] (get-in ctx [:result :trace]))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= ["spec-0001" "spec-0002" "spec-0003" "spec-0005"] (sort (map :id (:scenarios (trace ctx))))) "the trace lists " (pr-str (map :id (:scenarios (trace ctx)))))
    ctx))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (k/check! (= {:id "spec-0003" :title "Under the rule" :component "Two" :level "e2e"}
                 (select-keys (first (filter #(= "spec-0003" (:id %)) (:scenarios (trace ctx)))) [:id :title :component :level]))
              "the trace's scenario is " (pr-str (:scenarios (trace ctx))))
    ctx))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [ctx] (k/spec-project! ctx {:needs (assoc k/demo-needs "UN-007" {:text "A need nothing traces to" :sources ["demo:README.md@abc1234"]})})))

(defstep #"the trace says no scenario traces to UN-007"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (empty? (:scenarios (trace ctx))) "the trace lists scenarios")
    (k/check! (str/includes? (str (:says (trace ctx))) "no scenario traces to UN-007") "the trace says " (pr-str (trace ctx)))
    ctx))

(def lint-model (k/context-model [[:architecture [[:lint "Lint"]]]]))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [ctx]
    (k/spec-project! ctx {:model lint-model
                          :needs (assoc k/demo-needs "UN-005" {:text "A model" :sources ["demo:README.md@abc1234"]})
                          :features {"dhf/spec/architecture/lint.feature"
                                     (k/feature-text "Lint" ["UN-005"] [{:id "spec-0012" :title "Lint names the view" :level "contract"}])}})))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (trace ctx)]
      (k/check! (= {:needs ["UN-005"] :component "Lint" :context "architecture" :level "contract"} (select-keys t [:needs :component :context :level]))
                "the trace is " (pr-str t))
      ctx)))

(defstep #"an Allure results directory with two results labelled spec=spec-0012, one passed and one failed"
  (fn [ctx]
    (let [ctx (s/scenarios-project! ctx [12])
          d (str (:base ctx) "/dir")]
      (k/result-json! ctx d {:spec "spec-0012" :status "passed"})
      (k/result-json! ctx d {:spec "spec-0012" :status "failed"})
      (assoc-in ctx [:ph "DIR"] d))))

(defstep #"\"rdm-spec trace spec-0012 --results DIR\" is run with that results directory as DIR"
  (fn [ctx] (k/run-rdm ctx "trace spec-0012 --results DIR")))

(defstep #"the trace gives the status failed"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= :failed (:status (trace ctx))) "the trace gives " (pr-str (trace ctx))) ctx))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [rs (:results (trace ctx))]
      (k/check! (= #{"passed" "failed"} (set (map :status rs))) "the results are " (pr-str rs))
      (k/check! (every? #(str/ends-with? (str (:file %)) "-result.json") rs) "the results do not name their files")
      ctx)))

(defstep #"UN-003 tagged on one scenario, and a results directory with a passing result for it"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx {:needs (assoc k/demo-needs "UN-003" {:text "A team sees each scenario's status." :sources ["demo:README.md@abc1234"]})
                                    :features {"dhf/spec/demo/thing.feature"
                                               (k/feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "Traced" :tags ["@UN-003"]}
                                                                                   {:id "spec-0002" :title "Not traced"}])}})
          d (str (:base ctx) "/dir")]
      (k/result-json! ctx d {:spec "spec-0001"})
      (assoc-in ctx [:ph "DIR"] d))))

(defstep #"standard output is \{:trace \{:kind :need :id \"UN-003\" :text \.\.\. :scenarios \[\.\.\.\]\}\}"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (:result ctx)]
      (k/check! (= #{:trace} (set (keys t))) "standard output is " (pr-str t))
      (k/check! (= {:kind :need :id "UN-003" :text "A team sees each scenario's status."} (select-keys (:trace t) [:kind :id :text])) "the trace is " (pr-str t))
      (k/check! (= #{:kind :id :text :scenarios} (set (keys (:trace t)))) "the trace holds " (pr-str (keys (:trace t))))
      ctx)))

(defstep #"each of its scenarios is a map with :id, :title, :component, :context, :level and :status"
  (fn [ctx]
    (k/check! (= [{:id "spec-0001" :title "Traced" :component "Thing" :context "kernel" :level "e2e" :status :verified}]
                 (:scenarios (trace ctx)))
              "the scenarios are " (pr-str (:scenarios (trace ctx))))
    ctx))

(defstep #"no scenario tagged @spec-0999 and no need UN-999" (fn [ctx] (k/spec-project! ctx)))

(defstep #"it exits 2 and names spec-0999"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "spec-0999") ctx))

(defstep #"\"rdm-spec trace spec-0012 --results DIR\" is run with a DIR that does not exist"
  (fn [ctx] (k/run-rdm ctx "trace spec-0012 --results DIR")))

(defstep #"it exits 2 and names the directory"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx (str (:base ctx) "/dir")) ctx))

(defstep #"\"rdm-spec trace spec-0012\" is run with no --results"
  (fn [ctx] (k/run-rdm ctx "trace spec-0012")))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= {:needs ["UN-005"] :component "Lint" :level "e2e"} (select-keys (trace ctx) [:needs :component :level])) "the trace is " (pr-str (trace ctx)))
    ctx))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (k/check! (str/includes? (str (:says (trace ctx))) "no status was derived because no results were given") "the trace says " (pr-str (trace ctx)))
    ctx))
