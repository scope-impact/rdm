(ns rdm-spec.measurement.checklist
  "Checklist (dhf/spec/measurement/checklist.feature): `rdm-spec checklist
  CYCLE` derives the cycle's checklist, never written by hand: one item to
  grade per stamped scenario of the spec at the cycle's cut, and, under the
  regulated profile, one per clause of the clause lists at the cut.

  The spec arrives as SpecParsed, read at the cut the cycle's cycle.edn names
  (the parser's stage), and the clauses as ClausesIndexed (the gap stage, at
  the same cut). A cycle's scope (:project, {:context :c}, {:component
  :c/n}, or a vector of those) keeps the items of that scope and nothing
  else; clause items belong to the context compliance, so they are kept by
  :project or a scope naming that context.

  dhf/cycles/CYCLE/checklist.json, its keys in this order:

    {\"cycle\": \"03\", \"cut\": \"spec-3\",
     \"items\": [{\"id\" \"title\" \"context\" \"component\" \"level\"}],   ; scenarios by id, then clauses
     \"planned\": [{\"id\" \"title\"}]}                                    ; the @wip scenarios, by id

  A clause item: {\"id\" key, \"title\" description, \"context\" \"compliance\",
  \"component\" its clause list, \"level\" \"clause\"}.

  An unstamped scenario (no id, or more than one), or a line the parser
  refuses, stops the checklist: it is a finding (exit 1) and nothing is
  written, for a scenario the checklist cannot name would shrink the
  denominator unnoticed (loyly:harness/spec.feature@17d68d2).

  Also the measurement context's helpers for a cycle's files (cycle-file,
  read-edn-file, read-cycle, id-order), which the scorer and the scoreboard
  use."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; A cycle's files

(defn- cycles-dir [req]
  (str/replace (str (or (get-in req [:config :cycles-dir]) "dhf/cycles")) #"/+$" ""))

(defn cycle-file
  "The path of the cycle's file `f`, relative to the root:
  \"dhf/cycles/<cycle>/<f>\". Cannot run on a cycle name that is not one
  directory name."
  [req cycle f]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle's name, such as 03")))
  (str (cycles-dir req) "/" cycle "/" f))

(defn read-edn-file
  "The EDN value in the file `rel`: with a `default`, the default when there
  is no such file; without one, cannot run then. Cannot run when the file is
  not readable EDN."
  ([req rel]
   (let [v (read-edn-file req rel ::none)]
     (if (= ::none v) (command/cannot-run! (str "no " rel)) v)))
  ([req rel default]
   (let [f (command/path req rel)]
     (if-not (fs/regular-file? f)
       default
       (try (edn/read-string (slurp (str f)))
            (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))))))

(defn read-cycle
  "The cycle's cycle.edn, with ::file its path; cannot run when there is
  none or it is not a map."
  [req cycle]
  (let [rel (cycle-file req cycle "cycle.edn")
        c (read-edn-file req rel nil)]
    (cond
      (nil? c) (command/cannot-run! (str "no " rel ": a cycle is declared by hand in its cycle.edn first"))
      (not (map? c)) (command/cannot-run! (str rel " is not a map"))
      :else (assoc c ::file rel))))

(defn- id-key
  "Natural order: digit runs compare as numbers (spec-0012 before spec-0100)."
  [id]
  (vec (for [[_ digits other] (re-seq #"(\d+)|(\D+)" (str id))]
         (if digits [0 (parse-long digits) ""] [1 0 other]))))

(defn- compare-keys [a b]
  (loop [a (seq a) b (seq b)]
    (cond
      (and (nil? a) (nil? b)) 0
      (nil? a) -1
      (nil? b) 1
      :else (let [c (compare (first a) (first b))]
              (if (zero? c) (recur (next a) (next b)) c)))))

(defn id-order
  "Ids in their natural order (spec-0012 before spec-0100)."
  [ids]
  (vec (sort-by id-key compare-keys ids)))

;; ---------------------------------------------------------------------------
;; The events this component reads

(defn subscriptions
  "Keeps SpecParsed and ClausesIndexed in the run's state."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec-parsed payload) nil)]
   [:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]])

;; ---------------------------------------------------------------------------
;; Scope

(defn- scope-parts
  "The scope as a vector of {:context kw} / {:component kw}, or :project;
  cannot run on any other shape."
  [scope file]
  (let [bad #(command/cannot-run! (str file " has the scope " (pr-str scope)
                                       ": a scope is :project, {:context :c}, {:component :c/n} or a vector of those"))
        part? (fn [p] (and (map? p) (= 1 (count p))
                           (or (keyword? (:context p)) (keyword? (:component p)))))]
    (cond
      (or (nil? scope) (= :project scope)) :project
      (part? scope) [scope]
      (and (vector? scope) (seq scope) (every? part? scope)) scope
      :else (bad))))

(defn in-scope?
  "Pure: whether the item owned by `context` and `component` (the model's
  ids) is in `parts` (:project or [{:context ..} | {:component ..}])."
  [parts context component]
  (or (= :project parts)
      (boolean (some #(or (and (:context %) (= (:context %) context))
                          (and (:component %) (= (:component %) component)))
                     parts))))

;; ---------------------------------------------------------------------------
;; Deriving

(defn- level-name [l] (when l (str/replace (if (keyword? l) (name l) (str l)) #"^@" "")))

(defn- kw-name [x] (when x (if (keyword? x) (name x) (str x))))

(defn scenario-item
  "Pure: a stamped scenario as an item to grade."
  [sc]
  (array-map "id" (:id sc)
             "title" (:title sc)
             "context" (or (:context-name sc) (kw-name (:context sc)))
             "component" (or (:component-name sc) (kw-name (:component sc)))
             "level" (level-name (:level sc))))

(defn clause-item
  "Pure: a clause as an item to grade, at the level clause."
  [{:keys [key description clause-list]}]
  (array-map "id" key "title" description "context" "compliance" "component" clause-list "level" "clause"))

(defn derive-checklist
  "Pure: the checklist of `cycle` (its name), `cut`, the parsed `scenarios`
  and the `clauses` (ClausesIndexed's, or nil) in `parts`:
  {:checklist array-map :findings [..]}; with findings, no checklist."
  [cycle cut scenarios clauses parts refusals]
  (let [unstamped (for [sc scenarios :when (nil? (:id sc))]
                    (command/finding (:file sc) (:line sc) "scenario-unstamped"
                                     (str "the scenario " (pr-str (:title sc)) " at the cut " cut
                                          (if (seq (:ids sc))
                                            (str " carries " (count (:ids sc)) " ids")
                                            " carries no id")
                                          ": a scenario the checklist cannot name stops it, so nothing was written")))
        refused (for [{:keys [file line rule message]} refusals]
                  (command/finding file line rule (str message " (at the cut " cut "): nothing was written")))
        findings (vec (concat refused unstamped))]
    (if (seq findings)
      {:findings findings}
      (let [kept (filter #(in-scope? parts (:context %) (:component %)) scenarios)
            by-id #(sort-by (fn [sc] (id-key (:id sc))) compare-keys %)
            items (mapv scenario-item (by-id (remove :wip? kept)))
            planned (mapv #(array-map "id" (:id %) "title" (:title %)) (by-id (filter :wip? kept)))
            clause-items (when (in-scope? parts :compliance nil)
                           (mapv clause-item (:clauses clauses)))]
        {:checklist (array-map "cycle" cycle "cut" cut
                               "items" (into items clause-items)
                               "planned" planned)
         :findings []}))))

(defn checklist
  "The checklist stage: derives the cycle's checklist from the spec at its
  cut and writes it to checklist.json."
  [req]
  (let [cycle (first (:args req))
        c (read-cycle req cycle)
        cut (:cut c)
        _ (when-not (and (string? cut) (not (str/blank? cut)))
            (command/cannot-run! (str (::file c) " names no :cut")))
        parts (scope-parts (:scope c) (::file c))
        parsed (or (some-> (:state req) deref ::spec-parsed)
                   (command/cannot-run! (str "the cut " cut " that " (::file c)
                                             " names is not a commit of this repository: the checklist reads only a cut")))
        clauses (some-> (:state req) deref ::clauses)
        {:keys [checklist findings]} (derive-checklist cycle cut (:scenarios parsed) clauses parts (:refusals parsed))]
    (if (seq findings)
      {:result {:checklist {:cycle cycle :cut cut :written? false}} :findings findings}
      (let [rel (cycle-file req cycle "checklist.json")
            f (command/path req rel)
            items (get checklist "items")]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (str (json/generate-string checklist {:pretty true}) "\n"))
        {:result {:checklist {:cycle cycle :cut cut :file rel :written? true
                              :items (count items)
                              :clauses (count (filter #(= "clause" (get % "level")) items))
                              :planned (count (get checklist "planned"))}}
         :findings []}))))
