(ns rdm-spec.verification.trace
  "The Trace component of the verification context
  (dhf/spec/verification/trace.feature): a need or a scenario, with
  everything linked to it, in both directions. It reads the scenarios from
  SpecParsed and, with results, the statuses from the Status component.

  The stage `trace` (task trace ID [--results DIR]). Its result (spec-0279):

    need:     {:trace {:kind :need :id \"UN-003\" :text \"...\"
                       :scenarios [{:id :title :component :context :level :status}]}}
    scenario: {:trace {:kind :scenario :id :title :needs [{:id :text}]
                       :component :context :level :file :line
                       :status :results [{:file :status}]}}

  :component and :context are the model's names. Without results there is
  no :status (a need's scenarios carry :status nil) and :note says no
  status was derived because no results were given; a need nothing traces
  to says so in :note too. An ID that names no stamped scenario and no
  need, and a results directory that does not exist, cannot run (exit 2)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(def no-status "no status was derived because no results were given")

(defn subscriptions
  "Keeps SpecParsed in the run's state, as the Status component reads it."
  [req]
  (status/subscriptions req))

(defn- need-ids [spec]
  (set (concat (map :id (:needs spec)) (mapcat :needs (:scenarios spec)))))

(defn- note [& parts] (when-let [ps (seq (remove nil? parts))] (str/join "; " ps)))

(defn trace-need
  "The trace of the need `id`."
  [spec id statuses]
  (let [need (first (filter #(= id (:id %)) (:needs spec)))
        scs (filter #(some #{id} (:needs %)) (status/stamped (:scenarios spec)))
        n (note (when (empty? scs) (str "no scenario traces to " id))
                (when-not statuses no-status))]
    (cond-> {:kind :need :id id :text (:text need)
             :scenarios (vec (for [sc scs]
                               {:id (:id sc) :title (:title sc) :component (:component-name sc)
                                :context (:context-name sc) :level (:level sc)
                                :status (get statuses (:id sc))}))}
      n (assoc :note n))))

(defn trace-scenario
  "The trace of the stamped scenario `sc`."
  [spec sc derived]
  (let [needs (into {} (map (juxt :id :text)) (:needs spec))]
    (cond-> {:kind :scenario :id (:id sc) :title (:title sc)
             :needs (vec (for [n (:needs sc)] {:id n :text (get needs n)}))
             :component (:component-name sc) :context (:context-name sc)
             :level (:level sc) :file (:file sc) :line (:line sc)}
      derived (assoc :status (get-in derived [:statuses (:id sc)])
                     :results (vec (for [r (get-in derived [:by-id (:id sc)])]
                                     {:file (:file r) :status (keyword (:status r))})))
      (not derived) (assoc :note no-status))))

(defn trace
  "The trace stage."
  [req]
  (let [spec (status/spec-of req)
        id (str (first (:args req)))
        sc (first (filter #(= id (:id %)) (status/stamped (:scenarios spec))))
        need? (contains? (need-ids spec) id)
        _ (when-not (or sc need?)
            (command/cannot-run! (str id " names no stamped scenario and no need of the spec")))
        {:keys [read derived]} (when-not (str/blank? (:results req))
                                 (status/derive-from! req (:results req)))]
    {:result {:trace (if sc
                       (trace-scenario spec sc derived)
                       (trace-need spec id (:statuses derived)))}
     :findings (if read (results/unreadable-findings read) [])}))
