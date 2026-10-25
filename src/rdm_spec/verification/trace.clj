(ns rdm-spec.verification.trace
  "Trace (dhf/spec/verification/trace.feature): a need or a scenario, with
  everything linked to it, in both directions. `rdm-spec trace ID [--results
  DIR]`, ID a scenario id (spec-NNNN, the configured :id-prefix) or a need
  (UN-n); a leading @ is accepted.

  The spec arrives as SpecParsed (subscriptions); statuses come from Status
  (rdm-spec.verification.status/derive!), only when a results directory was
  given (or the configured one exists).

  The result (standard output):

    {:trace \"UN-003\" | \"spec-0012\"
     :kind :need | :scenario
     :need {:id :text :sources :declared?}       ; a need's trace only
     :scenarios [{:id :title :file :line :component :context :level
                  :needs [UN-n] :planned?
                  :status :verified|:failed|:unverified|:planned   ; with results
                  :results [{:file :status :name}]}]              ; with results
     :needs [{:id :text :sources :declared?}]    ; a scenario's trace only
     :results \"dir\" | nil
     :derived? bool
     :note \"no scenario traces to UN-007\" | \"no status was derived because no results were given\" ...}

  A need's scenarios are every scenario that traces to it: tagged with it
  on the scenario, its rule or its feature (the parser's :needs). A
  scenario with no single id is listed with :id nil and never has a status.

  Cannot run (exit 2): an ID that is neither a scenario id nor a need, a
  scenario id no scenario carries, a need neither declared nor tagged, and,
  through Status, a results directory that does not exist or holds no result."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.status :as status]))

;; ---------------------------------------------------------------------------
;; The spec, as SpecParsed carries it

(defn subscriptions
  "Keeps SpecParsed's payload in the run's state, for the trace and for
  Status (whose own subscription is not wired by a task it is no stage of)."
  [req]
  (concat (status/subscriptions req)
          [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec-parsed payload) nil)]]))

(defn- spec-parsed [req]
  (or (some-> (:state req) deref ::spec-parsed)
      (command/cannot-run! "the spec was not read: no SpecParsed event reached the verification context")))

;; ---------------------------------------------------------------------------
;; Pure: what an id names and what traces to it

(def no-results-note "no status was derived because no results were given")

(defn id-kind
  "Pure: [:scenario \"spec-0012\"], [:need \"UN-003\"] or nil, for the ID as
  given (a leading @ is dropped)."
  [prefix id]
  (let [id (str/replace (str id) #"^@" "")
        prefix (or prefix "spec")]
    (cond
      (re-matches (re-pattern (str (java.util.regex.Pattern/quote prefix) "-\\d+")) id) [:scenario id]
      (re-matches status/need-re id) [:need id]
      :else nil)))

(defn entry
  "Pure: a parsed scenario as the trace lists it."
  [sc]
  (-> (status/scenario-entry sc)
      (assoc :title (or (:title sc) (:name sc)))
      (select-keys [:id :title :file :line :component :context :level :needs :planned?])))

(defn carries?
  "Pure: true when the parsed scenario carries the id `id` (its single id,
  or one of several)."
  [id sc]
  (boolean (or (= id (:id sc)) (some #{id} (:ids sc)))))

(defn traces-to?
  "Pure: true when the parsed scenario traces to the need (on it, its rule
  or its feature)."
  [need sc]
  (boolean (some #{need} (:needs sc))))

(defn need-of
  "Pure: the need `id` as declared ({:id :text :sources :declared? true}),
  or {:id :declared? false}."
  [declared id]
  (if-let [n (get declared id)]
    (assoc (select-keys (merge {:id id} n) [:id :text :sources]) :declared? true)
    {:id id :declared? false}))

(defn with-statuses
  "Pure: the entries with each one's status and results from Status's
  report; with no derived report, the entries unchanged."
  [entries report]
  (if-not (:derived? report)
    entries
    (let [by-id (into {} (map (juxt :id identity)) (:scenarios report))]
      (vec (for [e entries
                 :let [row (get by-id (:id e))]]
             (if row
               (assoc e :status (:status row) :results (:results row))
               e))))))

;; ---------------------------------------------------------------------------
;; The task

(defn- trace-need [req need]
  (let [parsed (spec-parsed req)
        declared (status/declared-needs req)
        scs (filter #(traces-to? need %) (:scenarios parsed))]
    (when (and (empty? scs) (not (contains? declared need)))
      (command/cannot-run! (str need " names nothing: no need " need " is declared in the needs file"
                                " and no scenario is tagged @" need)))
    {:kind :need
     :need (need-of declared need)
     :scenarios (mapv entry scs)
     :empty-note (when (empty? scs) (str "no scenario traces to " need))}))

(defn- trace-scenario [req id]
  (let [parsed (spec-parsed req)
        declared (status/declared-needs req)
        scs (filter #(carries? id %) (:scenarios parsed))]
    (when (empty? scs)
      (command/cannot-run! (str id " names nothing: no scenario is tagged @" id)))
    {:kind :scenario
     :scenarios (mapv entry scs)
     :needs (vec (for [n (distinct (mapcat :needs scs))] (need-of declared n)))}))

(defn trace
  "The trace task: `rdm-spec trace ID [--results DIR]`."
  [req]
  (let [given (first (:args req))
        [kind id] (or (id-kind (get-in req [:config :id-prefix]) given)
                      (command/cannot-run! (str given " is neither a scenario id ("
                                                (or (get-in req [:config :id-prefix]) "spec")
                                                "-NNNN) nor a need (UN-n)")))
        t (if (= :need kind) (trace-need req id) (trace-scenario req id))
        {:keys [report findings]} (status/derive! req)
        derived? (boolean (:derived? report))
        note (str/join "; " (remove nil? [(:empty-note t) (when-not derived? no-results-note)]))]
    {:result (cond-> (-> (dissoc t :empty-note)
                         (assoc :trace id
                                :scenarios (with-statuses (:scenarios t) report)
                                :results (:results report)
                                :derived? derived?))
               (not (str/blank? note)) (assoc :note note))
     :findings (vec findings)}))
