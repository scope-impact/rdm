(ns rdm-spec.verification.trace
  "Trace (dhf/spec/verification/trace.feature): a need or a scenario, with
  everything linked to it, in both directions.

  `rdm-spec trace ID [--results DIR]`, after the parser's stage:
    - ID a need (UN-n): every stamped scenario that traces to it, however the
      need is tagged (on the feature, the rule or the scenario), each with its
      id, title, component, context and level;
    - ID a scenario (<prefix>-NNNN, with or without @): its needs, its
      component and bounded context, its level and title.
  With results (--results, RESULTS_DIR, else the configured directory when it
  exists), each scenario carries its status, read from the Status component,
  and a scenario trace lists the results behind it, each with its status and
  file. Without results the trace says no status was derived because no
  results were given; a scenario tagged @wip still shows planned, as the
  glossary defines it (planned whatever the results).

  The result:
    {:trace {:kind :need | :scenario :id ..
             ;; a need
             :text .. :declared? bool :scenarios [entry] [:message ..]
             ;; a scenario
             :title :needs [..] :component :context :level :file :line
             :status .. :results [{:file :status :name}]
             ;; either
             :derived? bool :results-dir dir-or-nil [:no-status ..]}}

  Refusals (exit 2): an ID that names no scenario and no need, a results
  directory that does not exist (the Results reader names it)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

(def no-status "no status was derived because no results were given")

(defn subscriptions
  "The spec arrives as SpecParsed, kept as the Status component keeps it."
  [req]
  (status/subscriptions req))

(defn- need-id? [id] (boolean (re-matches status/need-re id)))

(defn- entry
  "Pure: a scenario as the trace lists it, with its status when one is known."
  [sc row derived?]
  (cond-> {:id (:id sc) :title (:name sc) :component (:component sc) :context (:context sc)
           :level (:level sc) :file (:file sc) :line (:line sc)}
    true (assoc :needs (:needs sc))
    derived? (assoc :status (:status row))
    (and (not derived?) (:planned? sc)) (assoc :status :planned)))

(defn trace-need
  "Pure: the trace of the need `id` over the stamped scenarios `scs`, with the
  needs declared and the report's rows by id (nil without results)."
  [id scs needs rows]
  (let [traced (filter #(some #{id} (:needs %)) scs)
        derived? (some? rows)
        entries (mapv #(entry % (get rows (:id %)) derived?) traced)]
    (cond-> {:kind :need :id id
             :text (get-in needs [id :text])
             :declared? (contains? needs id)
             :scenarios entries
             :derived? derived?}
      (empty? entries) (assoc :message (str "no scenario traces to " id))
      (not derived?) (assoc :no-status no-status))))

(defn trace-scenario
  "Pure: the trace of the stamped scenario `sc`, with its row of the report
  (nil without results)."
  [sc row derived?]
  (let [e (entry sc row derived?)]
    (cond-> (assoc e :kind :scenario :derived? derived?)
      derived? (assoc :results (vec (:results row)))
      (not derived?) (assoc :no-status no-status))))

(defn trace
  "The trace task's stage."
  [req]
  (let [raw (str (first (:args req)))
        id (str/replace raw #"^@" "")
        scs (status/scenarios req)
        needs (status/declared-needs req)
        sc (first (filter #(= id (:id %)) scs))
        need? (and (need-id? id) (or (contains? needs id) (some #(some #{id} (:needs %)) scs)))]
    (when-not (or sc need?)
      (command/cannot-run! (str "the id " id " names no stamped scenario and no need of the spec")))
    (let [{:keys [report findings]} (status/derive! req)
          derived? (boolean (:derived? report))
          rows (when derived? (into {} (map (juxt :id identity)) (:scenarios report)))
          t (if sc
              (trace-scenario sc (get rows id) derived?)
              (trace-need id scs needs rows))]
      {:result {:trace (assoc t :results-dir (when derived? (:results report)))}
       :findings findings})))
