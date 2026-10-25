(ns rdm-spec.measurement.scoreboard
  "Component Scoreboard of the bounded context measurement
  (dhf/spec/measurement/scoreboard.feature): `rdm-spec scoreboard BASELINE CYCLE`.

  Compares two scored cycles honestly, for the spec owner: over the items
  both scored only, it counts what got better (fail in the baseline, pass in
  the cycle) and what got worse, never a net. Items only the later cycle
  scored are counted apart as new, those only the baseline scored as
  dropped, and a moved denominator is said on the error stream. When the
  later cycle regenerated a scope narrower than the project, its scope's items
  are reported first (:parts, :target) and every other item apart (:rest).

  A bootstrap cycle (:bootstrap true in its cycle.edn) is never a baseline:
  the scoreboard marks it and compares nothing against it.

  Result: {:baseline :cycle :scope :denominator {:from :to :moved?}
           :compared :better :worse :better-ids :worse-ids :new :new-ids
           :dropped :dropped-ids [:parts [{:part :target ...} {:part :rest ...}]]}
  or, with a bootstrap cycle, {:baseline :cycle :bootstrap [cycle ...]}.

  Cannot run (exit 2): a cycle with no cycle.edn, or one that is not a
  bootstrap with no readable score.edn."
  (:require [clojure.set :as set]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]))

(defn read-score
  "Cycle `cycle`'s score.edn: a map with :items; cannot run without one."
  [req cycle]
  (let [f (checklist/cycle-file req cycle "score.edn")
        s (checklist/read-edn-file req f)]
    (when (nil? s)
      (command/cannot-run! (str "no " (command/rel req f) ": cycle " cycle " is not scored (rdm-spec score " cycle
                                " --verdicts FILE)")))
    (when-not (and (map? s) (sequential? (:items s)))
      (command/cannot-run! (str (command/rel req f) " holds no :items")))
    s))

(defn verdicts
  "{id verdict} of a score."
  [score]
  (into {} (keep #(when (map? %) [(:id %) (:verdict %)]) (:items score))))

(defn compare-verdicts
  "Better and worse over the ids both `a` and `b` ({id verdict}) scored,
  restricted to the ids `keep?` accepts."
  [a b keep?]
  (let [shared (sort (filter keep? (set/intersection (set (keys a)) (set (keys b)))))
        better (filterv #(and (not= :pass (a %)) (= :pass (b %))) shared)
        worse (filterv #(and (= :pass (a %)) (not= :pass (b %))) shared)]
    {:compared (count shared) :better (count better) :worse (count worse)
     :better-ids better :worse-ids worse}))

(defn board
  "The comparison of the baseline's verdicts `a` with the cycle's `b`;
  `target?` (nil: no scope) tells the scope's items from the rest."
  [a b target?]
  (let [new-ids (vec (sort (remove (set (keys a)) (keys b))))
        dropped (vec (sort (remove (set (keys b)) (keys a))))
        from (count a) to (count b)]
    (cond-> (merge {:denominator {:from from :to to :moved? (not= from to)}}
                   (compare-verdicts a b (constantly true))
                   {:new (count new-ids) :new-ids new-ids :dropped (count dropped) :dropped-ids dropped})
      target? (assoc :parts [(assoc (compare-verdicts a b target?) :part :target)
                             (assoc (compare-verdicts a b (complement target?)) :part :rest)]))))

(defn- bootstrap? [c] (true? (:bootstrap c)))

(defn- owners-of
  "{id {:context :component}} from the checklists of the cycles, later first."
  [req cycles]
  (into {} (for [cy (reverse cycles)
                 :let [cl (checklist/read-checklist-json req cy)]
                 it (concat (:items cl) (:planned cl))
                 :when (map? it)]
             [(:id it) (select-keys it [:context :component])])))

(defn scoreboard
  "The scoreboard task: `rdm-spec scoreboard BASELINE CYCLE`."
  [req]
  (let [[a b] (:args req)
        ca (checklist/read-cycle req a)
        cb (checklist/read-cycle req b)
        boot (vec (keep (fn [[cy c]] (when (bootstrap? c) cy)) [[a ca] [b cb]]))]
    (if (seq boot)
      {:result {:baseline a :cycle b :bootstrap boot}
       :findings (vec (for [cy boot]
                        (command/warning (command/rel req (checklist/cycle-file req cy "cycle.edn")) nil "bootstrap"
                                         (str "cycle " cy " is a bootstrap cycle, reviewed by a person only and never"
                                              " a baseline: no better or worse is counted against it"))))}
      (let [sa (read-score req a)
            sb (read-score req b)
            scope (or (:scope sb) (:scope cb) :project)
            units (checklist/scope-units scope)
            target? (when (seq units)
                      (let [pred (checklist/target? (checklist/model-at req (:cut cb)) scope)
                            owners (owners-of req [a b])]
                        (fn [id] (pred (get owners id {})))))
            va (verdicts sa)
            vb (verdicts sb)
            r (board va vb target?)
            {:keys [from to moved?]} (:denominator r)]
        {:result (merge {:baseline a :cycle b :scope scope} r
                        (when target? {:parts (mapv #(cond-> % (= :target (:part %)) (assoc :scope scope)) (:parts r))}))
         :findings (cond-> []
                     moved?
                     (conj (command/warning "denominator-moved"
                                            (str "the denominator moved from " from " to " to ": only the "
                                                 (:compared r) " items both cycles scored are compared; "
                                                 (:new r) " new and " (:dropped r) " dropped items are counted apart"))))}))))
