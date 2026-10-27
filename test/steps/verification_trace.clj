(ns steps.verification-trace
  "Step definitions of dhf/spec/verification/trace.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn trace [ctx] (k/check-exit ctx 0) (:trace (k/edn-out ctx)))

(def three-features
  {:model-spec {:contexts [["shop" ["till" "cart" "door"]]]}
   :features {"dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :tags ["UN-003"] :scenarios [(k/sc 1 "Takes") (k/sc 2 "Gives")]})
              "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :tags ["UN-001"] :scenarios [(k/sc 3 "Holds")]
                                                            :rules [{:name "Carts" :tags ["UN-003"] :scenarios [(k/sc 4 "Fills")]}]})
              "dhf/spec/shop/door.feature" (k/feature-text {:name "Door" :tags ["UN-001"]
                                                            :scenarios [(k/sc 5 "Opens") (update (k/sc 6 "Closes") :tags conj "UN-003")]})}
   :extra {"dhf/spec/ids.edn" "{:issued 6}\n"
           "dhf/spec/needs.edn" (k/pr-edn {"UN-001" {:text "One" :sources ["demo:README.md@abc1234"]}
                                           "UN-003" {:text "Three" :sources ["demo:README.md@abc1234"]}
                                           "UN-007" {:text "Seven" :sources ["demo:README.md@abc1234"]}})}})

(defn three! [ctx]
  (let [ctx (k/project! ctx three-features)]
    (fs/delete (fs/path (:dir ctx) k/core-feature))
    ctx))

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx] (three! ctx)))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (assert (= ["spec-0001" "spec-0002" "spec-0004" "spec-0006"] (sort (map :id (:scenarios (trace ctx))))) (:out ctx))
    ctx))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (let [s (first (filter #(= "spec-0004" (:id %)) (:scenarios (trace ctx))))]
      (assert (= {:id "spec-0004" :title "Fills" :component "Cart" :level :e2e} (select-keys s [:id :title :component :level])) (pr-str s)))
    ctx))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [ctx] (three! ctx)))

(defstep #"the trace says no scenario traces to UN-007"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (empty? (:scenarios t)))
      (assert (str/includes? (str (:out ctx)) "no scenario traces to UN-007") (:out ctx)))
    ctx))

(defn lint! [ctx need]
  (let [ctx (k/project! ctx {:model-spec {:contexts [["architecture" ["lint"]]] :kernel ["core"]}
                             :features {"dhf/spec/architecture/lint.feature"
                                        (k/feature-text {:name "Lint" :tags [need] :scenarios [(k/sc 12 "Names the view")]})}
                             :extra {"dhf/spec/ids.edn" "{:issued 12}\n"}})]
    (k/update-edn! ctx "dhf/spec/needs.edn" assoc need {:text "A need" :sources ["demo:README.md@abc1234"]})))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [ctx] (lint! ctx "UN-005")))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (= {:kind :scenario :id "spec-0012" :needs ["UN-005"] :component "Lint" :context "architecture" :level :e2e}
                 (select-keys t [:kind :id :needs :component :context :level])) (pr-str t)))
    ctx))

(defstep #"an Allure results directory with two results labelled spec=spec-0012, one passed and one failed"
  (fn [ctx]
    (let [ctx (-> (lint! ctx "UN-005") (k/placeholder! "DIR"))
          d (get-in ctx [:ph "DIR"])]
      (assoc ctx ::files [(k/write-result! d {:id "spec-0012"}) (k/write-result! d {:id "spec-0012" :status "failed"})]))))

(defstep #"the trace gives the status failed"
  (fn [ctx] (assert (= :failed (:status (trace ctx))) (:out ctx)) ctx))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [d (get-in ctx [:ph "DIR"])]
      (assert (= #{{:file (str d "/" (first (::files ctx))) :status "passed"} {:file (str d "/" (second (::files ctx))) :status "failed"}}
                 (set (:results (trace ctx)))) (:out ctx)))
    ctx))

(defstep #"UN-003 tagged on one scenario, and a results directory with a passing result for it"
  (fn [ctx]
    (let [ctx (-> (three! ctx) (k/placeholder! "DIR"))]
      (k/write-files! (:dir ctx) {"dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :tags ["UN-001"] :scenarios [(k/sc 1 "Takes") (k/sc 2 "Gives")]})
                                  "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :tags ["UN-001"] :scenarios [(k/sc 3 "Holds") (k/sc 4 "Fills")]})})
      (k/write-result! (get-in ctx [:ph "DIR"]) {:id "spec-0006"})
      ctx)))

(defstep #"standard output is \{:trace \{:kind :need :id \"UN-003\" :text \.\.\. :scenarios \[\.\.\.\]\}\}"
  (fn [ctx]
    (let [out (k/edn-out ctx) t (:trace out)]
      (assert (= #{:trace} (set (keys out))))
      (assert (= {:kind :need :id "UN-003" :text "Three"} (select-keys t [:kind :id :text])) (pr-str t))
      (assert (= ["spec-0006"] (map :id (:scenarios t))) (pr-str t))
      ctx)))

(defstep #"each of its scenarios is a map with :id, :title, :component, :context, :level and :status"
  (fn [ctx]
    (assert (= [{:id "spec-0006" :title "Closes" :component "Door" :context "shop" :level :e2e :status :verified}]
               (:scenarios (trace ctx))) (:out ctx))
    ctx))

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [ctx] (k/project! ctx)))

(defstep #"it exits 2 and names spec-0999"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "spec-0999")))

(defstep #"\"rdm-spec trace spec-0012 --results DIR\" is run with a DIR that does not exist"
  (fn [ctx]
    (let [ctx (lint! ctx "UN-005")]
      (k/rdm-line ctx "rdm-spec trace spec-0012 --results DIR"))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" (get-in ctx [:ph "DIR"]))))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (= "spec-0012" (:id t)))
      (assert (seq (:needs t)) (pr-str t))
      (assert (string? (:component t)) (pr-str t))
      (assert (#{:e2e :contract} (:level t)) (pr-str t)))
    ctx))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (let [t (trace ctx)]
      (assert (nil? (:status t)))
      (assert (str/includes? (:out ctx) "no status was derived because no results were given") (:out ctx)))
    ctx))
