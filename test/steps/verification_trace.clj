(ns steps.verification-trace
  "Step definitions for dhf/spec/verification/trace.feature: `rdm-spec
  trace ID` on a small project the scenario makes
  (steps.verification-support). The trace's result is read only as spec-0279
  states it: {:trace {:kind :need :id :text :scenarios [{:id :title
  :component :context :level :status}]}}; a scenario's trace is
  {:trace {:kind :scenario :id :title :needs :component :context :level
  :status :results}}.

  The shared When, \"it exits 2 and names WORD\" and \"a copy of rdm-spec's
  own project in which the configured results directory does not exist\" are
  the kernel's.

  Context keys: :dir, :results (a results directory outside the project, as
  the kernel's When reads it), :expected-ids, :need, :id."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-support :as v]))

(defn- the-trace [ctx]
  (let [r (kc/exits! ctx 0)
        t (:trace (kc/stdout-edn r))]
    (s/check! (map? t) (str "the result holds no :trace\n" (s/show r)))
    t))

;; ---------------------------------------------------------------------------
;; A need traces to its scenarios

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx]
    (-> ctx
        (v/project! {"demo/alpha" {:tags "@UN-003" :fname "Alpha"
                                   :scenarios [{:id "spec-0001" :title "Alpha one"}]
                                   :rules [{:name "Alpha's rule" :scenarios [{:id "spec-0002" :title "Alpha two" :level :contract}]}]}
                     "demo/beta" {:tags "@UN-001" :fname "Beta"
                                  :scenarios [{:id "spec-0003" :title "Beta outside the rule"}]
                                  :rules [{:name "A rule for UN-003" :tags "@UN-003"
                                           :scenarios [{:id "spec-0004" :title "Beta in the rule"}
                                                       {:id "spec-0005" :title "Beta also in the rule"}]}
                                          {:name "Another rule" :scenarios [{:id "spec-0006" :title "Beta elsewhere"}]}]}
                     "demo/gamma" {:tags "@UN-001" :fname "Gamma"
                                   :scenarios [{:id "spec-0007" :title "Gamma tagged" :tags "@UN-003"}
                                               {:id "spec-0008" :title "Gamma not tagged"}]}})
        (assoc :expected {"spec-0001" ["Alpha one" "Alpha" "e2e"]
                          "spec-0002" ["Alpha two" "Alpha" "contract"]
                          "spec-0004" ["Beta in the rule" "Beta" "e2e"]
                          "spec-0005" ["Beta also in the rule" "Beta" "e2e"]
                          "spec-0007" ["Gamma tagged" "Gamma" "e2e"]}))))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (let [t (the-trace ctx)
          ids (map :id (:scenarios t))]
      (s/check! (= (sort (keys (:expected ctx))) (sort ids)) (str "the trace lists " (pr-str ids)))
      (s/check! (= (count ids) (count (distinct ids))) (str "a scenario is listed twice: " (pr-str ids)))
      nil)))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (doseq [sc (:scenarios (the-trace ctx))
            :let [[title comp level] (get (:expected ctx) (:id sc))]]
      (s/check! (= [title comp level] [(:title sc) (str (:component sc)) (some-> (:level sc) name)])
                (str (:id sc) " is listed as " (pr-str sc))))
    nil))

(defstep #"the declared need (UN-\d+) and no scenario tracing to it"
  (fn [ctx need]
    (-> ctx
        (v/one-feature! [{:id "spec-0001" :title "A scenario of UN-001"}])
        (assoc :need need))))

(defstep #"the trace says no scenario traces to (UN-\d+)"
  (fn [ctx need]
    (let [r (kc/the-run ctx)
          t (the-trace ctx)]
      (s/check! (and (= need (:id t)) (= :need (:kind t)) (empty? (:scenarios t))) (str "the trace is " (pr-str t)))
      (s/check! (str/includes? (:out r) (str "no scenario traces to " need))
                (str "the trace does not say no scenario traces to " need "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A scenario traces to its need and its evidence

(def ^:private lint-feature
  {"architecture/lint" {:tags "@UN-005" :fname "Lint"
                        :scenarios [{:id "spec-0012" :title "A view with no description fails the lint"}]}})

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [ctx] (v/project! ctx lint-feature)))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (let [t (the-trace ctx)]
      (s/check! (= [:scenario "spec-0012" ["UN-005"] "Lint" "architecture" :e2e]
                   [(:kind t) (:id t) (mapv #(if (map? %) (:id %) %) (:needs t)) (:component t) (:context t) (:level t)])
                (str "the trace is " (pr-str t)))
      nil)))

(defstep #"an Allure results directory with two results labelled spec=spec-0012, one passed and one failed"
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx (v/project! ctx lint-feature))
          rd (s/tmp-dir)]
      (v/result! rd "spec-0012" "passed")
      (v/result! rd "spec-0012" "failed")
      (assoc ctx :results rd))))

(defstep #"the trace gives the status (verified|failed|unverified|planned)"
  (fn [ctx st]
    (let [t (the-trace ctx)]
      (s/check! (= (keyword st) (:status t)) (str "the trace gives " (pr-str (:status t))))
      nil)))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [t (the-trace ctx)
          rs (:results t)
          files (set (map #(str (fs/file-name %)) (fs/list-dir (:results ctx))))]
      (s/check! (= #{"passed" "failed"} (set (map (comp name :status) rs))) (str "the results are " (pr-str rs)))
      (s/check! (= files (set (map #(str (fs/file-name (str (:file %)))) rs))) (str "the results name " (pr-str rs)))
      nil)))

(defstep #"UN-003 tagged on one scenario, and a results directory with a passing result for it"
  (fn [ctx]
    (let [ctx (v/project! ctx {"demo/alpha" {:tags "@UN-001" :fname "Alpha"
                                             :scenarios [{:id "spec-0001" :title "Tagged" :tags "@UN-003"}
                                                         {:id "spec-0002" :title "Not tagged"}]}})
          rd (s/tmp-dir)]
      (v/result! rd "spec-0001" "passed")
      (assoc ctx :results rd))))

(defstep #"standard output is \{:trace \{:kind :need :id \"UN-003\" :text \.\.\. :scenarios \[\.\.\.\]\}\}"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          out (kc/stdout-edn r)
          t (:trace out)]
      (s/check! (= [:trace] (keys out)) (str "standard output holds " (pr-str (keys out))))
      (s/check! (= #{:kind :id :text :scenarios} (set (keys t))) (str "the trace holds " (pr-str (keys t))))
      (s/check! (= [:need "UN-003" "A user needs to know what is verified."] [(:kind t) (:id t) (:text t)])
                (str "the trace is " (pr-str t)))
      (s/check! (vector? (:scenarios t)) "the scenarios are not a vector")
      nil)))

(defstep #"each of its scenarios is a map with :id, :title, :component, :context, :level and :status"
  (fn [ctx]
    (let [scs (:scenarios (the-trace ctx))]
      (s/check! (= [{:id "spec-0001" :title "Tagged" :component "Alpha" :context "demo" :level :e2e :status :verified}]
                   scs)
                (str "the scenarios are " (pr-str scs)))
      nil)))

;; ---------------------------------------------------------------------------
;; The trace refuses what it cannot read

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [ctx] (v/project! ctx lint-feature)))

(defstep #"\"rdm-spec ([^\"]*)\" is run with a DIR that does not exist"
  (fn [ctx line]
    (let [ctx (if (:dir ctx) ctx (v/project! ctx lint-feature))
          missing (str (fs/file (s/tmp-dir) "no-such-results"))]
      (kc/run-line (assoc ctx :results missing :missing-results missing) line))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx] (kc/refused-naming! ctx (:missing-results ctx)) nil))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [t (the-trace ctx)]
      (s/check! (and (= "spec-0012" (:id t)) (seq (:needs t)) (:component t) (:level t))
                (str "the trace is " (pr-str t)))
      nil)))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          t (the-trace ctx)]
      (s/check! (nil? (:status t)) (str "the trace gives a status " (pr-str (:status t))))
      (s/check! (str/includes? (:out r) "no status was derived because no results were given")
                (str "it does not say no status was derived\n" (s/show r)))
      nil)))
