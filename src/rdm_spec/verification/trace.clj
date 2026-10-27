(ns rdm-spec.verification.trace
  "Trace (dhf/spec/verification/trace.feature): a need with every scenario
  that traces to it, however it is tagged, or a scenario with its needs, its
  component, its bounded context, its level and, with results, its status
  and the results behind it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.status :as status]))

(def no-status "no status was derived because no results were given")

(defn kind-of [id] (if (re-matches #"UN-\d+" id) :need :scenario))

(defn stage [{:keys [root config args] :as ctx}]
  (let [id (first args)
        specs (for [sc (cmd/spec-configs config)] (parser/read-spec root nil sc))
        scenarios (vec (for [sp specs s (:scenarios sp) :when (:id s)] s))
        needs (vec (mapcat :needs specs))
        named (cmd/results-dir ctx)
        derived (when (or (cmd/results-given? ctx) (fs/directory? (fs/path root named)))
                  (status/derive-statuses root config named))
        status-of (into {} (map (juxt :id :status) (:scenarios derived)))
        brief (fn [s] (cond-> {:id (:id s) :title (:title s) :component (:component-name s)
                               :context (some-> (:context s) name) :level (:level s)}
                        derived (assoc :status (status-of (:id s)))))]
    (when derived (status/publish! derived))
    (case (kind-of id)
      :need
      (let [n (or (first (filter #(= id (:id %)) needs))
                  (cmd/cannot-run! (str id " names nothing: no need " id " is declared and no scenario is tagged @" id)))
            ss (filterv #(some #{id} (:needs %)) scenarios)]
        {:result {:trace (cond-> {:kind :need :id id :text (:text n) :scenarios (mapv brief ss)}
                           (empty? ss) (assoc :note (str "no scenario traces to " id))
                           (and (seq ss) (not derived)) (assoc :note no-status))}})
      :scenario
      (let [s (or (first (filter #(= id (:id %)) scenarios))
                  (cmd/cannot-run! (str id " names nothing: no scenario is tagged @" id " and no need is " id)))]
        {:result {:trace (merge (brief s)
                                {:kind :scenario :needs (:needs s) :file (:file s) :line (:line s)}
                                (if derived
                                  {:results (vec (for [r (:results derived) :when (= id (:spec r))]
                                                   {:file (:file r) :status (:status r)}))}
                                  {:note no-status}))}}))))
