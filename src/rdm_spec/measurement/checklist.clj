(ns rdm-spec.measurement.checklist
  "Checklist (dhf/spec/measurement/checklist.feature): `rdm-spec checklist
  CYCLE` derives the cycle's checklist from the spec at the cycle's cut,
  never by hand, into dhf/cycles/CYCLE/checklist.json, for a grader to grade.

  The cycle is declared by hand in dhf/cycles/CYCLE/cycle.edn: its :cut names
  the cut (a tag), its :scope what the cycle regenerates (absent: :project).
  The spec is read at that cut by the parser stage the task runs first; this
  stage reads it from the SpecParsed event (the parser publishes the spec read
  at the cut the cycle names), never from the working tree.

  A scope is :project, {:context :c}, {:component :c/n}, or a non-empty vector
  of contexts and components; the kernel is the context :kernel. A scoped
  cycle's checklist holds the items of its scope only.

  checklist.json, keys in this order, items and planned sorted by id:
    {\"cycle\": \"03\", \"cut\": \"spec-3\",
     \"items\":   [{\"id\" \"title\" \"context\" \"component\" \"level\"}],  ; to grade
     \"planned\": [{\"id\" \"title\"}]}                                  ; tagged @wip

  Refusals: no cycle.edn, unreadable, no :cut, a malformed scope, or a cut
  that does not exist: cannot run (exit 2). A spec at the cut the Gherkin
  subset refuses, or holding a scenario with no id (or several): exit 1,
  naming each file and line, and nothing is written."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The cycle

(defn cycle-dir
  "The directory of `cycle` under the configured :cycles-dir; a name that is
  not a plain directory name cannot run."
  [req cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  (fs/file (command/path req (command/config-value req :cycles-dir)) cycle))

(defn read-cycle
  "cycle.edn of the cycle directory `dir`: a map naming a :cut, or cannot run."
  [req dir]
  (let [f (fs/file dir "cycle.edn")
        rel (command/rel req f)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": the cycle is declared there, by hand")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str rel " is not a map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str rel " names no :cut")))
      c)))

;; ---------------------------------------------------------------------------
;; Scopes

(defn- part-problem
  "Why `part` is not one context or one component, or nil."
  [part]
  (cond
    (not (map? part)) (str (pr-str part) " is neither {:context C} nor {:component C/N}")
    (not= 1 (count part)) (str (pr-str part) " names more than one context or component")
    (contains? part :context)
    (let [c (:context part)]
      (when-not (and (keyword? c) (nil? (namespace c)))
        (str (pr-str part) ": a context is a plain keyword, such as :verification")))
    (contains? part :component)
    (let [c (:component part)]
      (when-not (and (keyword? c) (namespace c))
        (str (pr-str part) ": a component is a qualified keyword, context/component, such as :spec/parser")))
    :else (str (pr-str part) " is neither {:context C} nor {:component C/N}")))

(defn scope-problem
  "Why `scope` is not a scope (:project, one context or component, or a
  non-empty vector of them), or nil when it is one."
  [scope]
  (cond
    (= :project scope) nil
    (map? scope) (part-problem scope)
    (vector? scope) (if (empty? scope)
                      "an empty list scopes nothing"
                      (some part-problem scope))
    :else (str (pr-str scope) " is not :project, {:context C}, {:component C/N} or a list of them")))

(defn scope-parts
  "The contexts and components of a valid scope; nil for :project."
  [scope]
  (cond (= :project scope) nil
        (map? scope) [scope]
        :else (vec scope)))

(defn in-scope?
  "True when the scenario (with the model's :context and :component ids) is
  in `scope`."
  [scope {:keys [context component]}]
  (or (= :project scope)
      (boolean (some (fn [p] (if (contains? p :context)
                               (= (:context p) context)
                               (= (:component p) component)))
                     (scope-parts scope)))))

(defn cycle-scope
  "The cycle's scope (:project when cycle.edn declares none), or cannot run."
  [req dir c]
  (let [scope (get c :scope :project)]
    (when-let [why (scope-problem scope)]
      (command/cannot-run! (str (command/rel req (fs/file dir "cycle.edn")) ": the :scope " why)))
    scope))

;; ---------------------------------------------------------------------------
;; The checklist

(defn- name-of [x] (when (some? x) (if (keyword? x) (name x) (str x))))

(defn item
  "A checklist item of a scenario (SpecParsed shape)."
  [sc]
  (array-map "id" (:id sc)
             "title" (:title sc)
             "context" (or (:context-name sc) (name-of (:context sc)))
             "component" (or (:component-name sc) (name-of (:component sc)))
             "level" (name-of (:level sc))))

(defn derive-checklist
  "The checklist of `cycle` at `cut` over `scenarios` within `scope`:
  {:checklist ordered-map :findings [unstamped ...]}."
  [cycle cut scope scenarios]
  (let [unstamped (remove :id scenarios)
        stamped (filter #(and (:id %) (in-scope? scope %)) scenarios)]
    {:findings (vec (for [{:keys [file line title]} unstamped]
                      (command/finding file line "unstamped-scenario"
                                       (str "the scenario \"" title "\" at the cut " cut
                                            " carries no single id: the checklist cannot name it"))))
     :checklist (array-map "cycle" cycle
                           "cut" cut
                           "items" (vec (sort-by #(get % "id") (map item (remove :wip? stamped))))
                           "planned" (vec (sort-by #(get % "id")
                                                   (for [sc stamped :when (:wip? sc)]
                                                     (array-map "id" (:id sc) "title" (:title sc))))))}))

(defn checklist-text [m]
  (str (json/generate-string m {:pretty true}) "\n"))

(defn- spec-parsed
  "The SpecParsed payload published on this run's bus, or nil."
  [req]
  (some #(when (= :SpecParsed (:event %)) (:payload %))
        (reverse (some-> (:bus req) bus/events))))

(defn checklist
  "The checklist task's own stage: `rdm-spec checklist CYCLE`."
  [req]
  (let [cycle (first (:args req))
        dir (cycle-dir req cycle)
        c (read-cycle req dir)
        cut (:cut c)
        scope (cycle-scope req dir c)
        spec (spec-parsed req)
        _ (when-not (and spec (string? (:revision spec)))
            (command/cannot-run! (str "the cut " cut " does not exist: no tag " cut " in " (:root req))))
        refusals (mapv (fn [{:keys [file line rule message]}]
                         (command/finding file line rule (str "the spec at the cut " cut " is not readable: " message)))
                       (:refusals spec))
        {:keys [checklist findings]} (derive-checklist cycle cut scope (:scenarios spec))
        findings (into refusals findings)
        out (fs/file dir "checklist.json")]
    (if (seq findings)
      {:result {} :findings findings}
      (let [items (get checklist "items")
            planned (get checklist "planned")]
        (fs/create-dirs dir)
        (spit (str out) (checklist-text checklist))
        {:result (array-map :cycle cycle :cut cut :scope scope
                            :items (count items) :planned (count planned)
                            :written (command/rel req out))
         :findings (if (empty? items)
                     [(command/warning "empty-checklist" (str "the scope " (pr-str scope) " holds no item to grade at " cut))]
                     [])}))))
