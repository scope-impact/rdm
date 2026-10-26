(ns rdm-spec.verification.trace
  "Trace (dhf/spec/verification/trace.feature): a need with every scenario
  that traces to it, or a scenario with its needs, component, context, level
  and the results behind its status."
  (:require [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def no-status "no status was derived: no results were given")

(defn- line [s statuses]
  {:id (:id s) :title (:title s) :component (:component-name s) :context (:context-name s)
   :level (:level s) :status (get statuses (:id s))})

(defn stage [{:keys [args no-results verification]}]
  (let [id (first args)
        {:keys [scenarios needs]} (bus/latest :SpecParsed)
        statuses (:statuses verification)
        need (first (filter #(= id (:id %)) needs))
        sc (first (filter #(= id (:id %)) scenarios))
        used-need? (some #(some #{id} (:needs %)) scenarios)]
    (cond
      (or need used-need?)
      (let [ss (vec (for [s scenarios :when (and (:id s) (some #{id} (:needs s)))] (line s statuses)))]
        {:result {:trace (cond-> {:kind :need :id id :text (:text need) :scenarios ss}
                           (empty? ss) (assoc :note (str "no scenario traces to " id))
                           no-results (assoc :note (if (empty? ss) (str "no scenario traces to " id "; " no-status) no-status)))}})
      sc
      {:result {:trace (cond-> (merge (line sc statuses)
                                      {:kind :scenario :needs (:needs sc) :file (:file sc) :line (:line sc)
                                       :results (vec (for [r (:results verification) :when (= id (:spec r))]
                                                       {:file (:file r) :status (keyword (:status r))}))})
                         no-results (assoc :note no-status))}}
      :else
      (cmd/cannot-run! (str id " names no scenario and no need of the spec")))))
