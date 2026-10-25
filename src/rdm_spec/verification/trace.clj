(ns rdm-spec.verification.trace
  "Trace (dhf/spec/verification/trace.feature): a need or a scenario, with
  everything linked to it, in both directions. `rdm-spec trace ID [--results
  DIR]`, ID a need (UN-nnn) or a scenario id (<prefix>-NNNN, an @ allowed).

  The spec arrives as SpecParsed (the parse stage before this one); statuses
  come from the Status component, from the results directory the task reads
  (--results, RESULTS_DIR, else the configured one when it exists). With none,
  no status is derived and the trace says so.

  Its result, the contract other contexts read (spec-0279):

    need      {:trace {:kind :need :id \"UN-003\" :text \"..\" :sources [..]
                       :scenarios [{:id :title :component :context :level :status}]
                       :derived? bool :results-dir dir-or-nil :note \"..\"?}}
    scenario  {:trace {:kind :scenario :id :title :file :line :component :context
                       :level :planned? :needs [{:id :text}] :status
                       :results [{:file :status :name}] :derived? bool
                       :results-dir dir-or-nil :note \"..\"?}}

  :status is :verified, :failed, :unverified or :planned, or nil when no
  status was derived (no results were given). An id that names nothing, and
  a results directory that does not exist, cannot be traced (exit 2)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

(def no-status-note "no status was derived because no results were given")

(defn subscriptions
  "Status's subscriptions: the spec, as SpecParsed carries it."
  [req]
  (status/subscriptions req))

;; ---------------------------------------------------------------------------
;; Pure

(defn id-kind
  "What the id `id` names the kind of: :need, :scenario, or nil."
  [prefix id]
  (cond
    (re-matches #"UN-\d+" id) :need
    (re-matches (re-pattern (str (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}")) id) :scenario
    :else nil))

(defn scenario-row
  "A scenario as a need's trace lists it."
  [sc status]
  {:id (:id sc) :title (:name sc) :component (:component sc) :context (:context sc)
   :level (:level sc) :status status})

(defn need-trace
  "The trace of the need `id`, from the stamped scenarios `scs` (as Status
  names them), the declared needs {id {:text :sources}} and the statuses
  {id status} (nil: none derived)."
  [id scs declared statuses]
  (let [rows (vec (for [sc scs :when (some #{id} (:needs sc))]
                    (scenario-row sc (when statuses (get statuses (:id sc))))))
        n (get declared id)]
    (cond-> {:kind :need :id id :text (:text n) :sources (vec (:sources n)) :scenarios rows
             :derived? (some? statuses)}
      (empty? rows) (assoc :note (str "no scenario traces to " id))
      (and (seq rows) (nil? statuses)) (assoc :note no-status-note))))

(defn scenario-trace
  "The trace of the scenario `sc` (as Status names it), with its needs'
  texts and, when derived, its status and results (`row`, a Status report
  row, nil when no status was derived)."
  [sc declared row]
  (cond-> {:kind :scenario :id (:id sc) :title (:name sc) :file (:file sc) :line (:line sc)
           :component (:component sc) :context (:context sc) :level (:level sc)
           :planned? (:planned? sc)
           :needs (mapv (fn [n] {:id n :text (get-in declared [n :text])}) (:needs sc))
           :status (:status row)
           :results (vec (:results row))
           :derived? (some? row)}
    (nil? row) (assoc :note no-status-note)))

;; ---------------------------------------------------------------------------
;; The stage

(defn trace
  "The trace task's stage: see the namespace doc."
  [req]
  (let [raw (str (first (:args req)))
        id (str/replace raw #"^@" "")
        prefix (or (get-in req [:config :id-prefix]) "spec")
        kind (id-kind prefix id)
        scs (status/scenarios req)
        declared (status/declared-needs req)
        known? (case kind
                 :need (or (contains? declared id) (some #(some #{id} (:needs %)) scs))
                 :scenario (some #(= id (:id %)) scs)
                 nil)]
    (when-not known?
      (command/cannot-run! (str raw " names nothing to trace: no stamped scenario carries it and no need is declared"
                                " with it; trace a need (UN-nnn) or a scenario id (" prefix "-NNNN)")))
    (let [{:keys [report findings]} (status/derive! req)
          derived? (:derived? report)
          rows (into {} (map (juxt :id identity)) (:scenarios report))
          t (case kind
              :need (assoc (need-trace id scs declared (when derived? (into {} (map (juxt :id :status)) (:scenarios report))))
                           :results-dir (:results report))
              :scenario (assoc (scenario-trace (some #(when (= id (:id %)) %) scs) declared
                                               (when derived? (get rows id)))
                               :results-dir (:results report)))]
      {:result {:trace t} :findings findings})))
