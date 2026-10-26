(ns rdm-spec.measurement.checklist
  "The Checklist component (dhf/spec/measurement/checklist.feature):
  `rdm-spec checklist CYCLE` derives the cycle's checklist from the spec at
  the cycle's cut, never written by hand, into
  dhf/cycles/CYCLE/checklist.json: one item per stamped scenario of the
  cycle's scope, for a grader to grade. The spec is its only author.

  The cut is cycle.edn's :cut (`revision`, which the command reads the spec
  at); the scope its :scope: :project (also when absent), {:context id},
  {:component id}, or a vector of contexts and components (the kernel a
  context, :kernel). It reads the scenarios from SpecParsed and, under the
  regulated profile, the clauses from ClausesIndexed.

  checklist.json, its keys in this order (spec-0210):

    {\"cycle\" \"03\" \"cut\" \"spec-3\"
     \"items\" [{\"id\" \"title\" \"context\" \"component\" \"level\"}]
     \"planned\" [{\"id\" \"title\"}]}

  items: every stamped scenario of the scope not tagged @wip, sorted by id,
  context and component as the model names them; then, in a cycle scoped
  to the whole project or naming the context compliance, every clause of
  the cut's clause lists, in their order with includes in place, each key
  once, at the level clause, its component the clause list it is written
  in. planned: every scenario of the scope tagged @wip, sorted by id.

  A scenario with no id (checklist-unstamped) or with two (checklist-ids),
  a spec the parser refuses, and a spec with no stamped scenario
  (checklist-empty) stop the checklist: exit 1, nothing written, never a
  smaller checklist. Cannot run (exit 2): no cycle.edn, no :cut, a cut the
  repository does not hold, a scope of another shape.

  Result: {:checklist {:cycle :cut :file :items n :planned n}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The cycle

(defn- cycle-rel [req cycle file]
  (str (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "") "/" cycle "/" file))

(defn read-cycle
  "The cycle.edn of `cycle`; the task cannot run without it, or without a :cut."
  [req cycle]
  (let [rel (cycle-rel req cycle "cycle.edn")
        f (command/path req rel)
        _ (when-not (fs/regular-file? f)
            (command/cannot-run! (str "there is no " rel ": a cycle is declared in its cycle.edn before its checklist")))
        c (try (edn/read-string (slurp (str f)))
               (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
    (when-not (and (map? c) (string? (:cut c)) (not (str/blank? (:cut c))))
      (command/cannot-run! (str rel " names no :cut: the checklist is derived from the spec at a cut")))
    c))

(defn revision
  "The commit the checklist task reads the spec at: the cycle's cut."
  [req]
  (let [c (read-cycle req (first (:args req)))
        r (p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""}
                   "git" "rev-parse" "--verify" "-q" (str (:cut c) "^{commit}"))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the cut " (:cut c) " that cycle " (first (:args req)) " names is not in "
                                (:root req) ": the checklist reads the spec at the cut")))
    (str/trim (:out r))))

;; ---------------------------------------------------------------------------
;; Pure

(defn- one-scope? [s]
  (and (map? s) (= 1 (count s))
       (or (keyword? (:context s)) (and (keyword? (:component s)) (namespace (:component s))))))

(defn check-scope!
  "The scope `s`, checked; the task cannot run on another shape."
  [s]
  (when-not (or (= :project s) (one-scope? s) (and (vector? s) (seq s) (every? one-scope? s)))
    (command/cannot-run! (str "the scope " (pr-str s) " is not :project, {:context id}, {:component id}"
                              " or a vector of them")))
  s)

(defn in-scope?
  "Whether the scenario `sc` lies in the scope `s`."
  [s sc]
  (cond
    (= :project s) true
    (vector? s) (boolean (some #(in-scope? % sc) s))
    (contains? s :context) (= (:context s) (:context sc))
    (contains? s :component) (= (:component s) (:component sc))
    :else false))

(defn- names-compliance? [s]
  (or (= :project s)
      (= :compliance (:context s))
      (and (vector? s) (some names-compliance? s))))

(defn checklist-of
  "Pure: {:items :planned} of the scope `s` over the stamped `scenarios`,
  and the `clauses` (nil without the regulated profile)."
  [s scenarios clauses]
  (let [mine (sort-by :id (filter #(in-scope? s %) scenarios))]
    {:items (vec (concat
                  (for [sc mine :when (not (:wip? sc))]
                    (array-map "id" (:id sc) "title" (:title sc) "context" (:context-name sc)
                               "component" (:component-name sc) "level" (some-> (:level sc) name)))
                  (when (names-compliance? s)
                    (for [c clauses]
                      (array-map "id" (:key c) "title" (:description c) "context" "compliance"
                                 "component" (:clause-list c) "level" "clause")))))
     :planned (vec (for [sc mine :when (:wip? sc)] (array-map "id" (:id sc) "title" (:title sc))))}))

(defn stops
  "Pure: the findings that stop the checklist: the parser's `refusals`, a
  scenario with no id or with two, a spec with no stamped scenario."
  [scenarios refusals]
  (vec (concat
        (for [{:keys [file line rule message]} refusals] (command/finding file line rule message))
        (for [sc scenarios :when (empty? (:ids sc))]
          (command/finding (:file sc) (:line sc) "checklist-unstamped"
                           (str "the scenario \"" (:title sc) "\" carries no id, so the checklist cannot name it:"
                                " an unstamped scenario stops the checklist rather than shrinking it")))
        (for [sc scenarios :when (< 1 (count (:ids sc)))]
          (command/finding (:file sc) (:line sc) "checklist-ids"
                           (str "the scenario \"" (:title sc) "\" carries " (count (:ids sc)) " ids, "
                                (str/join " and " (:ids sc)) ": an item has one id, so the checklist stops")))
        (when (and (empty? refusals) (empty? (filter :id scenarios)))
          [(command/finding "checklist-empty"
                            "the spec at the cut holds no stamped scenario: an empty spec stops the checklist")]))))

;; ---------------------------------------------------------------------------
;; The stage

(defn subscriptions
  "Keeps SpecParsed and, under the regulated profile, ClausesIndexed."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec payload) nil)]
   [:ClausesIndexed (fn [payload] (swap! (:state req) assoc ::clauses payload) nil)]])

(defn checklist
  "The checklist stage: `rdm-spec checklist CYCLE`."
  [req]
  (let [cycle (first (:args req))
        c (read-cycle req cycle)
        scope (check-scope! (if (contains? c :scope) (:scope c) :project))
        spec (or (some-> (:state req) deref ::spec)
                 (command/cannot-run! "no SpecParsed reached the checklist: the spec at the cut was not read"))
        clauses (when (contains? (:config req) :regulated)
                  (or (:clauses (some-> (:state req) deref ::clauses))
                      (command/cannot-run! "the configuration holds :regulated, but the clauses at the cut were not read")))
        fs* (stops (:scenarios spec) (:refusals spec))]
    (if (seq fs*)
      {:result {} :findings fs*}
      (let [{:keys [items planned]} (checklist-of scope (filter :id (:scenarios spec)) clauses)
            rel (cycle-rel req cycle "checklist.json")
            f (command/path req rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (str (json/generate-string (array-map "cycle" cycle "cut" (:cut c) "items" items "planned" planned)
                                                 {:pretty true})
                           "\n"))
        {:result {:checklist {:cycle cycle :cut (:cut c) :file rel :items (count items) :planned (count planned)}}
         :findings []}))))
