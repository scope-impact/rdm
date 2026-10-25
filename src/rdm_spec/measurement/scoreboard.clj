(ns rdm-spec.measurement.scoreboard
  "Scoreboard (dhf/spec/measurement/scoreboard.feature): `rdm-spec scoreboard
  BASELINE CYCLE` compares two cycles' scores honestly, for the spec owner.

  Over the items both cycles scored it counts what got better (failed in the
  baseline, passed in the cycle) and what got worse, never a net figure. A
  moved denominator is said out loud: the items only the cycle scored are
  counted separately as new, those only the baseline scored as dropped.

  When the cycle is scoped (its cycle.edn's :scope is not :project), its
  target is reported first, then the rest; where the cycle scored nothing
  outside its scope, it says the rest was not scored. Which item belongs to
  the scope is read from the cycles' checklist.json (each item's context and
  component), the scope's component ids matched through the model's names.

  A bootstrap cycle (:bootstrap true) is marked and nothing is compared
  against it, so it needs no score.

  The result:
    {:baseline \"01\" :cycle \"02\" :scope S
     :overall {:compared :better :worse :better-ids :worse-ids}  ; a project cycle
     :target {..same..} :rest {..same.. or :scored false}       ; a scoped cycle
     :denominator {:from :to :moved :compared :new :new-ids :dropped :dropped-ids}
     :notes [\"...\"]}
  or, against a bootstrap: {:baseline :cycle :bootstrap [cycle ...] :notes}.
  Each note is also a warning on the error stream.

  No cycle.edn, or a non-bootstrap cycle with no score.edn: cannot run (2)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Reading

(defn- cycle-dir [req cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  (fs/file (command/path req (command/config-value req :cycles-dir)) cycle))

(defn- read-edn! [req f what]
  (let [rel (command/rel req f)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": " what)))
    (let [v (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not EDN: " (ex-message e)))))]
      (when-not (map? v) (command/cannot-run! (str rel " is not a map")))
      v)))

(defn- read-checklist-items
  "{id {\"context\" .. \"component\" ..}} of the cycle's checklist.json, or {}."
  [dir]
  (let [f (fs/file dir "checklist.json")]
    (if-not (fs/regular-file? f)
      {}
      (let [c (try (json/parse-string (slurp (str f))) (catch Exception _ nil))]
        (into {} (for [i (when (map? c) (get c "items"))
                       :when (and (map? i) (string? (get i "id")))]
                   [(get i "id") i]))))))

(defn- read-model-names
  "{component-id component-name, context-id context-name} from the model, or {}."
  [req]
  (let [m (try (some-> (get-in req [:config :model]) (->> (command/path req)) str slurp edn/read-string)
               (catch Exception _ nil))]
    (if-not (map? m)
      {}
      (into {}
            (concat
             (for [c (:containers m) ctx (:contexts c)
                   x (cons ctx (:components ctx))
                   :when (:id x)]
               [(:id x) (:name x)])
             (for [k (:kernel m) :when (:id k)] [(:id k) (:name k)]))))))

(defn- read-cycle-data [req cycle]
  (let [dir (cycle-dir req cycle)
        c (read-edn! req (fs/file dir "cycle.edn") "the cycle is declared there, by hand")]
    {:cycle cycle :dir dir :decl c :bootstrap? (true? (:bootstrap c))}))

(defn- read-score [req {:keys [dir cycle]}]
  (let [s (read-edn! req (fs/file dir "score.edn") (str "cycle " cycle " is not scored (rdm-spec score " cycle ")"))]
    (into {} (for [i (:items s) :when (and (map? i) (string? (:id i)))]
               [(:id i) (:verdict i)]))))

;; ---------------------------------------------------------------------------
;; Comparing

(defn compare-verdicts
  "Better and worse over `ids`, from {id verdict} of the baseline and the cycle."
  [ids before after]
  (let [ids (sort ids)
        better (filterv #(and (not= :pass (before %)) (= :pass (after %))) ids)
        worse (filterv #(and (= :pass (before %)) (not= :pass (after %))) ids)]
    (array-map :compared (count ids) :better (count better) :worse (count worse)
               :better-ids better :worse-ids worse)))

(defn- matches-name? [want got]
  (and (string? want) (string? got) (= (str/lower-case want) (str/lower-case got))))

(defn item-in-scope?
  "True when the checklist item (its \"context\" and \"component\") is in
  `scope`; `names` maps the model's ids to their names."
  [scope names item]
  (let [ictx (get item "context")
        icomp (get item "component")
        parts (cond (= :project scope) nil (map? scope) [scope] :else scope)
        ctx-ok? (fn [k] (or (matches-name? (name k) ictx) (matches-name? (get names k) ictx)))]
    (or (= :project scope)
        (boolean
         (some (fn [p]
                 (cond
                   (contains? p :context) (ctx-ok? (:context p))
                   (contains? p :component)
                   (let [k (:component p)]
                     (and (ctx-ok? (keyword (namespace k)))
                          (or (matches-name? (name k) icomp) (matches-name? (get names k) icomp))))
                   :else false))
               parts)))))

(defn compare-cycles
  "The scoreboard of two non-bootstrap cycles: `before` and `after` are
  {id verdict}; `info` {id checklist-item}; `scope` the cycle's scope."
  [{:keys [baseline cycle scope before after info names]}]
  (let [common (filter after (keys before))
        new-ids (vec (sort (remove before (keys after))))
        dropped (vec (sort (remove after (keys before))))
        from (count before)
        to (count after)
        denominator (array-map :from from :to to :moved (not= from to) :compared (count common)
                               :new (count new-ids) :new-ids new-ids
                               :dropped (count dropped) :dropped-ids dropped)
        moved-note (when (not= from to)
                     (str "the denominator moved from " from " to " to ": only the " (count common)
                          " items both scored are compared"
                          (when (seq new-ids) (str "; " (count new-ids) " new item(s) counted separately"))
                          (when (seq dropped) (str "; " (count dropped) " item(s) no longer scored"))))]
    (if (= :project scope)
      {:result (array-map :baseline baseline :cycle cycle :scope scope
                          :overall (compare-verdicts common before after)
                          :denominator denominator
                          :notes (vec (remove nil? [moved-note])))}
      (let [in? #(item-in-scope? scope names (get info % {}))
            target-ids (filter in? common)
            rest-ids (remove in? common)
            after-rest (remove in? (keys after))
            rest-scored? (boolean (seq after-rest))
            rest-note (when-not rest-scored?
                        (str "the rest was not scored in cycle " cycle ": its scope is " (pr-str scope)))]
        {:result (array-map :baseline baseline :cycle cycle :scope scope
                            :target (compare-verdicts target-ids before after)
                            :rest (if rest-scored?
                                    (compare-verdicts rest-ids before after)
                                    (array-map :scored false :note rest-note))
                            :denominator denominator
                            :notes (vec (remove nil? [rest-note moved-note])))}))))

(defn scoreboard
  "The scoreboard task: `rdm-spec scoreboard BASELINE CYCLE`."
  [req]
  (let [[a b] (:args req)
        ca (read-cycle-data req a)
        cb (read-cycle-data req b)
        boots (filterv identity [(when (:bootstrap? ca) a) (when (:bootstrap? cb) b)])]
    (if (seq boots)
      (let [notes (mapv #(str "cycle " % " is a bootstrap cycle: it is never a baseline, and nothing is compared"
                              " against it")
                        boots)]
        {:result (array-map :baseline a :cycle b :bootstrap boots :notes notes)
         :findings (mapv #(command/warning "bootstrap" %) notes)})
      (let [scope (get-in cb [:decl :scope] :project)
            out (compare-cycles {:baseline a :cycle b :scope scope
                                 :before (read-score req ca) :after (read-score req cb)
                                 :info (merge (read-checklist-items (:dir ca)) (read-checklist-items (:dir cb)))
                                 :names (read-model-names req)})]
        (assoc out :findings (mapv #(command/warning "scoreboard" %) (get-in out [:result :notes])))))))
