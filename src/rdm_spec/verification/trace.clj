(ns rdm-spec.verification.trace
  "Trace (dhf/spec/verification/trace.feature): a need or a scenario, with
  everything linked to it, in both directions.

    rdm-spec trace UN-003     the need and every scenario tracing to it, by a
                              tag on the scenario, its rule or its feature
    rdm-spec trace spec-0012  the scenario, its needs, component, bounded
                              context and level

  With a results directory (given, or the configured one when it exists) each
  scenario carries its status, and a traced scenario the results behind it;
  without, the trace says no status was derived. Statuses come from Status."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

(defn subscriptions [req] (status/subscriptions req))

(def no-status "no status was derived because no results were given")

(defn- entry [sc] (select-keys sc [:id :name :component :context :level :file :line]))

(defn trace-need
  "Pure: the trace of the need `id`."
  [id need scs derived]
  (let [by-id (into {} (map (juxt :id identity) (:scenarios derived)))
        traced (filter #(some #{id} (:needs %)) scs)]
    (cond-> {:need id
             :text (:text need)
             :sources (:sources need)
             :declared? (some? need)
             :scenarios (vec (for [sc traced]
                               (cond-> (entry sc)
                                 (:derived? derived) (assoc :status (:status (by-id (:id sc)))))))}
      (empty? traced) (assoc :note (str "no scenario traces to " id))
      (not (:derived? derived)) (assoc :status-note no-status))))

(defn trace-scenario
  "Pure: the trace of the stamped scenario `sc`."
  [sc needs derived]
  (let [row (first (filter #(= (:id sc) (:id %)) (:scenarios derived)))]
    (merge (entry sc)
           {:needs (:needs sc)
            :need-texts (into (sorted-map) (for [n (:needs sc)] [n (get-in needs [n :text])]))
            :planned? (:planned? sc)}
           (if (:derived? derived)
             {:status (:status row) :results (:results row) :results-dir (:results derived)}
             {:status nil :note no-status}))))

(defn trace
  "The trace task: `rdm-spec trace ID [--results DIR]`."
  [req]
  (let [id (str/replace (str (first (:args req))) #"^@" "")
        scs (status/scenarios req)
        needs (status/declared-needs req)]
    (cond
      (re-matches status/need-re id)
      (if (or (contains? needs id) (some #(some #{id} (:needs %)) scs))
        (let [{:keys [report findings]} (status/derive! req)]
          {:result (trace-need id (get needs id) scs report)
           :findings (cond-> findings
                       (not (contains? needs id))
                       (conj (command/warning "trace-undeclared-need" (str id " is tagged but not declared in the needs"))))})
        (command/cannot-run! (str id " names no need: it is not declared and no scenario traces to it")))

      :else
      (if-let [sc (first (filter #(= id (:id %)) scs))]
        (let [{:keys [report findings]} (status/derive! req)]
          {:result (trace-scenario sc needs report) :findings findings})
        (command/cannot-run! (str id " names nothing: no scenario carries it and no need is declared as it"))))))
