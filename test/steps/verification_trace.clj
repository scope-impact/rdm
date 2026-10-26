(ns steps.verification-trace
  "Step definitions of dhf/spec/verification/trace.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.verification-results :as vr]))

(defn trace [ctx] (k/check-exit ctx 0) (:trace (k/edn-out ctx)))

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx]
    (let [ctx (k/project! ctx {:model-spec {:kernel ["core" "two" "three"]}
                               :needs {"UN-001" {:text "One" :sources ["demo:README.md@abc1234"]}
                                       "UN-003" {:text "Three" :sources ["demo:README.md@abc1234"]}}
                               :features {"dhf/spec/kernel/core.feature"
                                          (k/feature-text {:name "Core" :tags ["UN-003"] :scenarios [(k/sc 1 "A") (k/sc 2 "B")]})
                                          "dhf/spec/kernel/two.feature"
                                          (k/feature-text {:name "Two" :tags ["UN-001"] :scenarios [(k/sc 3 "C")]
                                                           :rules [{:name "R" :tags ["UN-003"] :scenarios [(k/sc 4 "D") (k/sc 5 "E")]}]})
                                          "dhf/spec/kernel/three.feature"
                                          (k/feature-text {:name "Three" :tags ["UN-001"]
                                                           :scenarios [(k/sc 6 "F") {:tags ["spec-0007" "contract" "UN-003"] :title "G" :steps ["Given a step"]}]})}})]
      ctx)))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (assert (= ["spec-0001" "spec-0002" "spec-0004" "spec-0005" "spec-0007"] (sort (map :id (:scenarios (trace ctx))))) (:out ctx))
    ctx))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (let [g (first (filter #(= "spec-0007" (:id %)) (:scenarios (trace ctx))))]
      (assert (= ["G" "Three" :contract] [(:title g) (:component g) (:level g)]) (pr-str g))
      ctx)))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [ctx] (k/project! ctx {:needs {"UN-001" {:text "One" :sources ["demo:README.md@abc1234"]}
                                     "UN-007" {:text "Seven" :sources ["demo:README.md@abc1234"]}}})))

(defstep #"the trace says no scenario traces to UN-007"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (empty? (:scenarios t)))
      (assert (str/includes? (:out ctx) "no scenario traces to UN-007") (:out ctx))
      ctx)))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [ctx]
    (let [ctx (k/project! ctx {:model-spec {:contexts [["architecture" ["lint"]]]}
                               :needs {"UN-005" {:text "Five" :sources ["demo:README.md@abc1234"]}}
                               :features {"dhf/spec/architecture/lint.feature"
                                          (k/feature-text {:name "Lint" :tags ["UN-005"] :scenarios [(k/sc 12 "Twelve")]})}})]
      (fs/delete-if-exists (fs/path (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature"))
      ctx)))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (= [:scenario ["UN-005"] "Lint" "architecture" :e2e] [(:kind t) (:needs t) (:component t) (:context t) (:level t)]) (pr-str t))
      ctx)))

(defstep #"an Allure results directory with two results labelled spec=spec-0012, one passed and one failed"
  (fn [ctx]
    (let [ctx (-> (vr/scenarios-project! ctx [(k/sc 12 "Twelve")]) (k/placeholder! "DIR"))
          d (get-in ctx [:ph "DIR"])]
      (assoc ctx ::files [(str d "/" (k/write-result! d {:id "spec-0012"}))
                          (str d "/" (k/write-result! d {:id "spec-0012" :status "failed"}))]))))

(defstep #"the trace gives the status failed"
  (fn [ctx] (assert (= :failed (:status (trace ctx))) (:out ctx)) ctx))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (assert (= (set (map vector (::files ctx) [:passed :failed]))
               (set (map (juxt :file :status) (:results (trace ctx)))))
            (:out ctx))
    ctx))

(defstep #"UN-003 tagged on one scenario, and a results directory with a passing result for it"
  (fn [ctx]
    (let [ctx (-> (k/project! ctx {:needs {"UN-001" {:text "One" :sources ["demo:README.md@abc1234"]}
                                           "UN-003" {:text "Three" :sources ["demo:README.md@abc1234"]}}
                                   :features {"dhf/spec/kernel/core.feature"
                                              (k/feature-text {:name "Core" :tags ["UN-001"]
                                                               :scenarios [{:tags ["spec-0001" "e2e" "UN-003"] :title "One" :steps ["Given a step"]}
                                                                           (k/sc 2 "Two")]})}})
                  (k/placeholder! "DIR"))]
      (k/write-result! (get-in ctx [:ph "DIR"]) {:id "spec-0001"})
      ctx)))

(defstep #"standard output is \{:trace \{:kind :need :id \"UN-003\" :text \.\.\. :scenarios \[\.\.\.\]\}\}"
  (fn [ctx]
    (let [o (k/edn-out ctx)]
      (assert (= [:trace] (keys o)))
      (assert (= {:kind :need :id "UN-003" :text "Three"} (select-keys (:trace o) [:kind :id :text])) (:out ctx))
      (assert (vector? (get-in o [:trace :scenarios])))
      ctx)))

(defstep #"each of its scenarios is a map with :id, :title, :component, :context, :level and :status"
  (fn [ctx]
    (assert (= [{:id "spec-0001" :title "One" :component "Core" :context "kernel" :level :e2e :status :verified}]
               (map #(select-keys % [:id :title :component :context :level :status]) (:scenarios (trace ctx))))
            (:out ctx))
    ctx))

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [ctx] (k/project! ctx)))

(defstep #"it exits 2 and names spec-0999"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "spec-0999")))

(defstep #"\"rdm-spec trace spec-0012 --results DIR\" is run with a DIR that does not exist"
  (fn [ctx]
    (let [ctx (-> (vr/scenarios-project! ctx [(k/sc 12 "Twelve")]) (k/placeholder! "DIR" false))]
      (k/rdm ctx ["trace" "spec-0012" "--results" (get-in ctx [:ph "DIR"])]))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx (get-in ctx [:ph "DIR"]))))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (= [["UN-005"] "Lint" :e2e] [(:needs t) (:component t) (:level t)]) (pr-str t))
      ctx)))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (assert (str/includes? (:out ctx) "no status was derived") (:out ctx))
    (assert (nil? (:status (trace ctx))))
    ctx))
