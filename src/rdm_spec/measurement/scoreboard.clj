(ns rdm-spec.measurement.scoreboard
  "Component Scoreboard of the bounded context measurement
  (dhf/spec/measurement/scoreboard.feature): `rdm-spec scoreboard BASELINE
  CYCLE` compares two scored cycles, for the spec owner, honestly:

  - only over the items both scored; what got better and what got worse are
    each counted, never netted;
  - a moved denominator is said out loud (a warning on the error stream and
    :denominator in the result), and items only one cycle scored are counted
    apart (:new, :dropped);
  - when the later cycle regenerated one bounded context or one component, its
    scope's items are reported first (:target), then every other item (:rest);
  - a bootstrap cycle is marked, and nothing is compared against it.

  The result is printed as EDN on stdout, keys in that order."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.checklist :as checklist]
            [rdm-spec.measurement.scorer :as scorer]))

(defn- verdicts [score] (into {} (map (juxt :id :verdict) (:items score))))

(defn compare-items
  "Better and worse over the ids in `ids`, from verdicts `a` to verdicts `b`."
  [ids a b]
  (let [better (sort (filter #(and (= :fail (a %)) (= :pass (b %))) ids))
        worse (sort (filter #(and (= :pass (a %)) (= :fail (b %))) ids))]
    (array-map :items (count ids)
               :better (count better) :worse (count worse)
               :better-ids (vec better) :worse-ids (vec worse))))

(defn- read-checklist-or-nil [req cycle]
  (let [f (checklist/cycle-file req cycle "checklist.json")]
    (when (fs/exists? f)
      (try (json/parse-string (slurp (str f)) true) (catch Exception _ nil)))))

(defn in-scope-fn
  "A predicate on an id: whether the item belongs to `scope`, from the
  checklists' context and component, and the model at the cut (to name a
  component by its id)."
  [req scope cut checklist-items]
  (let [by-id (into {} (map (juxt :id identity) checklist-items))
        model (or (checklist/model-at req cut)
                  (some->> (get-in req [:config :model]) (command/path req) str slurp edn/read-string))
        ctx-name (fn [ctx-id]
                   (or (first (for [c (:containers model) ctx (:contexts c) :when (= ctx-id (:id ctx))]
                                (or (:name ctx) (name (:id ctx)))))
                       (name ctx-id)))
        comp-of (fn [comp-id]
                  (first (for [c (:containers model) ctx (:contexts c) comp (:components ctx)
                               :when (= comp-id (:id comp))]
                           [(or (:name ctx) (name (:id ctx))) (:name comp)])))]
    (cond
      (:context scope)
      (let [n (ctx-name (:context scope))]
        #(= n (:context (by-id %))))
      (:component scope)
      (let [[ctx comp] (or (comp-of (:component scope))
                           [(namespace (:component scope)) (name (:component scope))])]
        #(let [i (by-id %)] (and (= ctx (:context i)) (= comp (:component i)))))
      :else (constantly true))))

(defn scoreboard
  "The scoreboard task: `rdm-spec scoreboard CYCLE CYCLE`, the baseline first."
  [req]
  (let [[a b] (:args req)
        ca (checklist/read-cycle req a)
        cb (checklist/read-cycle req b)
        bootstrap (vec (for [[n c] [[a ca] [b cb]] :when (true? (:bootstrap c))] n))]
    (if (seq bootstrap)
      {:result (array-map :baseline a :cycle b :bootstrap bootstrap
                          :compared nil
                          :reason (str "a bootstrap cycle is reviewed by a person only and is never a baseline;"
                                       " nothing is compared against it"))
       :findings (vec (for [n bootstrap]
                        (command/warning (command/rel req (checklist/cycle-file req n "cycle.edn")) nil "bootstrap-cycle"
                                         (str "cycle " n " is a bootstrap cycle: no better or worse count is reported against it"))))}
      (let [sa (scorer/read-score req a)
            sb (scorer/read-score req b)
            va (verdicts sa)
            vb (verdicts sb)
            shared (filter va (map :id (:items sb)))
            new-ids (vec (sort (remove va (keys vb))))
            dropped (vec (sort (remove vb (keys va))))
            scope (or (:scope sb) (:scope cb) :project)
            moved? (not= (count va) (count vb))
            denominator (array-map :from (count va) :to (count vb) :moved moved?)
            findings (cond-> []
                       moved? (conj (command/warning "denominator-moved"
                                                     (str "the denominator moved from " (count va) " to " (count vb)
                                                          "; only the " (count shared) " items both cycles scored are compared, "
                                                          (count new-ids) " new and " (count dropped) " dropped items are counted apart"))))
            apart (array-map :new (array-map :items (count new-ids) :ids new-ids
                                             :passed (count (filter #(= :pass (vb %)) new-ids)))
                             :dropped (array-map :items (count dropped) :ids dropped))]
        (if (= :project scope)
          {:result (apply array-map
                          (concat [:baseline a :cycle b :scope :project :denominator denominator
                                   :shared (compare-items shared va vb)]
                                  (mapcat identity apart)))
           :findings findings}
          (let [cl-items (concat (:items (read-checklist-or-nil req b)) (:items (read-checklist-or-nil req a)))
                _ (when (empty? cl-items)
                    (command/cannot-run! (str "cycle " b " regenerated " (pr-str scope)
                                              ", but neither cycle has a checklist.json to tell its items from the rest")))
                in? (in-scope-fn req scope (or (:cut sb) (:cut cb)) cl-items)
                target (filter in? shared)
                others (remove in? shared)]
            {:result (apply array-map
                            (concat [:baseline a :cycle b :scope scope :denominator denominator
                                     :target (assoc (compare-items target va vb) :scope scope)
                                     :rest (compare-items others va vb)]
                                    (mapcat identity apart)))
             :findings findings}))))))
