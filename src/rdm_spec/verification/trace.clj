(ns rdm-spec.verification.trace
  "Component Trace (verification context): a need with every scenario that
  traces to it, or a scenario with its needs, component and bounded context,
  each with its status and the results behind it when results are given."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.status :as status]))

(def no-results "no status was derived because no results were given")

(defn ctx-name [sc] (cond (= :kernel (:context sc)) "kernel" (:context sc) (name (:context sc))))

(defn results-findings [derived]
  (when derived
    (for [{:keys [file unreadable]} (:unreadable derived)]
      (project/finding file "result-unreadable" (str "the result is unreadable: " unreadable)))))

(defn trace-task [{:keys [cfg args opts]}]
  (let [id (first args)
        rd (project/results-dir cfg opts)
        with-results? (or (:explicit? rd) (fs/directory? (:abs rd)))
        derived (when with-results? (status/derive! cfg opts rd))
        read (or (:read derived) (let [r (parser/read-specs-once (project/root) cfg)] (parser/publish! :working-tree r) r))
        scenarios (status/scenarios-of read opts)
        needs (into {} (for [r read n (:needs r)] [(:id n) n]))
        st (fn [sc] (get (:statuses derived) (:id sc)))
        sc-map (fn [sc] (cond-> {:id (:id sc) :title (:title sc) :component (:component-name sc) :context (ctx-name sc) :level (:level sc)}
                          derived (assoc :status (st sc))))
        scenario (first (filter #(= id (:id %)) scenarios))
        need-id (when (re-matches #"UN-\d+" (str id)) id)
        tracing (when need-id (filter #(some #{need-id} (:needs %)) (status/stamped scenarios)))]
    (cond
      scenario
      (project/report
       {:trace (cond-> {:kind :scenario :id id :title (:title scenario) :needs (:needs scenario)
                        :component (:component-name scenario) :context (ctx-name scenario) :level (:level scenario)
                        :file (:file scenario) :line (:line scenario)}
                 derived (assoc :status (st scenario)
                                :results (vec (for [r (:results derived) :when (= id (:spec r))] {:file (:file r) :status (name (:status r))})))
                 (not derived) (assoc :says no-results))}
       (results-findings derived))
      (and need-id (or (contains? needs need-id) (seq tracing)))
      (project/report
       {:trace (cond-> {:kind :need :id need-id :text (get-in needs [need-id :text]) :scenarios (mapv sc-map tracing)}
                 (empty? tracing) (assoc :says (str "no scenario traces to " need-id))
                 (and (seq tracing) (not derived)) (assoc :says no-results))}
       (results-findings derived))
      :else (project/cannot-run! (str "trace names " id ", and no scenario is tagged @" id " and no need is " id)))))

