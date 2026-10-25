(ns rdm-spec.measurement.checklist
  "The Checklist component (dhf/spec/measurement/checklist.feature): `rdm-spec
  checklist CYCLE` derives dhf/cycles/CYCLE/checklist.json from the spec at
  the cycle's cut, never by hand: one item per stamped scenario of the
  cycle's scope not tagged @wip, the @wip ones listed apart as planned, and,
  under the regulated profile, one item per clause after them.

  The spec arrives as SpecParsed and the clauses as ClausesIndexed (the
  parser and the gap analysis run as earlier stages of the task, at the
  cut; this namespace's `subscriptions` keep their payloads).

  checklist.json, its keys in this order (spec-0210):

    {\"cycle\" \"03\", \"cut\" \"spec-3\",
     \"items\":   [{\"id\" \"title\" \"context\" \"component\" \"level\"}]  ; by id, clauses after
     \"planned\": [{\"id\" \"title\"}]}                                  ; by id

  context and component are the model's :name; a clause item's context is
  \"compliance\", its component the clause list it is written in, its level
  \"clause\".

  A cycle's scope (cycle.edn :scope, :project when absent): :project,
  {:context :c}, {:component :c/n}, or a vector of contexts and components.
  Clause items belong to a cycle scoped to the project or naming the context
  compliance.

  Refusals, writing nothing: no cycle.edn, one that is not a map naming a
  :cut, a scope of another shape, or a cut the repository does not hold
  cannot run (exit 2). A scenario the cut holds with no id stops the
  checklist rather than shrinking it (checklist-unstamped, at its file and
  line), as does any other refusal of the parser (relayed unchanged), and a
  cut or scope with nothing to grade (checklist-empty): exit 1.

  Result: {:checklist {:cycle :cut :file :items n :planned n}}."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps SpecParsed's and ClausesIndexed's payloads in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]
   [:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]])

;; ---------------------------------------------------------------------------
;; The cycle

(defn cycle-rel
  "dhf/cycles/<cycle>/<file>, under the configured cycles directory."
  [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn valid-cycle-name? [cycle]
  (boolean (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))))

(defn- read-cycle
  "The cycle's cycle.edn, read; cannot run when it is missing or unreadable."
  [req cycle]
  (when-not (valid-cycle-name? cycle)
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle's name, such as 03")))
  (let [rel (cycle-rel req cycle "cycle.edn")
        f (command/path req rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": a cycle is declared by hand in its cycle.edn before its checklist")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str rel " is not an EDN map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str rel " names no :cut")))
      c)))

;; ---------------------------------------------------------------------------
;; Scopes

(defn- scope-part? [x]
  (and (map? x) (= 1 (count x))
       (or (and (contains? x :context) (keyword? (:context x)) (nil? (namespace (:context x))))
           (and (contains? x :component) (keyword? (:component x)) (some? (namespace (:component x)))))))

(defn scope-parts
  "Pure: the parts of a scope, [{:context k} | {:component k}], or nil for
  the whole project; :invalid when it has another shape."
  [scope]
  (cond
    (or (nil? scope) (= :project scope)) nil
    (scope-part? scope) [scope]
    (and (vector? scope) (seq scope) (every? scope-part? scope)) scope
    :else :invalid))

(defn in-scope?
  "Pure: whether the scenario (:context and :component, the model's ids)
  belongs to the scope's parts (nil: the project)."
  [parts {:keys [context component]}]
  (or (nil? parts)
      (boolean (some (fn [p] (if (:context p) (= (:context p) context) (= (:component p) component))) parts))))

(defn clauses-in-scope?
  "Pure: whether a cycle with these scope parts carries clause items."
  [parts]
  (or (nil? parts) (boolean (some #(= :compliance (:context %)) parts))))

;; ---------------------------------------------------------------------------
;; The checklist, as data

(defn- nm [x] (cond (nil? x) nil (keyword? x) (name x) :else (str x)))

(defn scenario-item
  "Pure: a parsed scenario as an item to grade."
  [{:keys [id title context-name component-name level]}]
  (array-map "id" id "title" title "context" (nm context-name) "component" (nm component-name) "level" (nm level)))

(defn clause-item
  "Pure: a clause (ClausesIndexed) as an item to grade."
  [{:keys [key description clause-list]}]
  (array-map "id" key "title" description "context" "compliance" "component" clause-list "level" "clause"))

(defn derive-checklist
  "Pure: the checklist of `cycle` at `cut` from the scenarios (SpecParsed's)
  and the clauses (ClausesIndexed's, or nil), for the scope's parts."
  [cycle cut scenarios clauses parts]
  (let [stamped (filter #(and (:id %) (in-scope? parts %)) scenarios)
        by-id #(sort-by (fn [x] (get x "id")) %)
        items (by-id (map scenario-item (remove :wip? stamped)))
        planned (by-id (map #(array-map "id" (:id %) "title" (:title %)) (filter :wip? stamped)))
        clause-items (when (and clauses (clauses-in-scope? parts))
                       (->> clauses
                            (reduce (fn [[seen acc] c]
                                      (if (seen (:key c)) [seen acc] [(conj seen (:key c)) (conj acc (clause-item c))]))
                                    [#{} []])
                            second))]
    (array-map "cycle" cycle "cut" cut "items" (vec (concat items clause-items)) "planned" (vec planned))))

(defn unstamped-findings
  "Pure: a finding for each scenario with no id (stamped scenarios only are
  items), at its file and line."
  [scenarios]
  (vec (for [{:keys [id file line title ids]} scenarios
             :when (nil? id)]
         (if (empty? ids)
           (command/finding file line "checklist-unstamped"
                            (str "the scenario \"" title "\" carries no id: an unstamped scenario stops the checklist"
                                 " rather than shrinking it (rdm-spec stamp)"))
           (command/finding file line "checklist-ids"
                            (str "the scenario \"" title "\" carries " (count ids) " ids, not one: the checklist"
                                 " cannot name it as one item"))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn checklist
  "The checklist stage: derives and writes dhf/cycles/CYCLE/checklist.json."
  [req]
  (let [cycle (first (:args req))
        c (read-cycle req cycle)
        parts (scope-parts (:scope c))
        _ (when (= :invalid parts)
            (command/cannot-run! (str (cycle-rel req cycle "cycle.edn") " holds the scope " (pr-str (:scope c))
                                      ", not :project, {:context C}, {:component C/N} or a vector of them")))
        st (some-> (:state req) deref)
        spec (or (::spec st)
                 (command/cannot-run! (str "the cut " (:cut c) " that " (cycle-rel req cycle "cycle.edn")
                                           " names is not a commit of this repository, so its spec cannot be read")))
        scenarios (:scenarios spec)
        refusals (vec (concat (for [{:keys [file line rule message]} (:refusals spec)]
                                (command/finding file line rule message))
                              (unstamped-findings scenarios)))
        cl (derive-checklist cycle (:cut c) scenarios (:clauses (::clauses st)) parts)
        rel (cycle-rel req cycle "checklist.json")]
    (cond
      (seq refusals)
      {:result {} :findings (vec (distinct refusals))}

      (empty? (get cl "items"))
      {:result {}
       :findings [(command/finding (cycle-rel req cycle "cycle.edn") "checklist-empty"
                                   (str "the cut " (:cut c) (when parts (str " within the scope " (pr-str (:scope c))))
                                        " holds no stamped scenario to grade: an empty checklist would judge nothing"))]}

      :else
      (let [f (command/path req rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (str (json/generate-string cl {:pretty true}) "\n"))
        {:result {:checklist {:cycle cycle :cut (:cut c) :file rel
                              :items (count (get cl "items")) :planned (count (get cl "planned"))}}
         :findings []}))))
