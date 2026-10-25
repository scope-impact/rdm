(ns rdm-spec.measurement.checklist
  "Checklist (dhf/spec/measurement/checklist.feature): `rdm-spec checklist
  CYCLE` derives the cycle's checklist from the spec at the cycle's cut into
  dhf/cycles/CYCLE/checklist.json, for a grader to grade. Never written by
  hand: the spec is its only author.

  It reads the spec through the SpecParsed event (the parser, run first,
  reads it at the cut cycle.edn names) and, under the regulated profile, the
  clauses through ClausesIndexed (the gap stage, run before it).

  In order:
    cannot run (exit 2): a cycle name that is no directory name, no
      cycle.edn or one that is not an EDN map, no :cut in it, a cut the
      repository does not hold, a :scope of another shape than :project,
      {:context :c}, {:component :c/n} or a vector of those.
    findings (exit 1), writing nothing: a scenario carrying no single id
      (scenario-unstamped, at its file and line), a stamped scenario with no
      single level (scenario-no-level), the parser's refusals (relayed), and
      a checklist with nothing in it (checklist-empty): an unstamped scenario
      or an empty spec stops the checklist rather than shrinking it.

  checklist.json, keys in this order:
    {\"cycle\" \"03\" \"cut\" \"spec-3\"
     \"items\"   [{\"id\" \"title\" \"context\" \"component\" \"level\"}]  ; by id
     \"planned\" [{\"id\" \"title\"}]}                                  ; @wip, by id
  Only the scenarios of the cycle's scope are listed. Under the regulated
  profile, when the scope is the whole project or names the context
  compliance, every clause follows the scenario items, in the clause lists'
  order with includes in place, each key once:
    {\"id\" key \"title\" description \"context\" \"compliance\"
     \"component\" clause-list-file \"level\" \"clause\"}

  Cycle helpers the scorer and the scoreboard share: `cycle-file`,
  `read-cycle` (the map carries ::file), `read-edn-file`, `id-order`."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Cycles

(defn cycles-dir
  "The configured cycles directory, without a trailing /."
  [req]
  (str/replace (str (or (get-in req [:config :cycles-dir]) "dhf/cycles")) #"/+$" ""))

(defn- cycle-name! [cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is no cycle name: a cycle is a directory name such as 03")))
  cycle)

(defn cycle-file
  "The project path of the file `f` of cycle `cycle`: dhf/cycles/CYCLE/f."
  [req cycle f]
  (str (cycles-dir req) "/" (cycle-name! cycle) "/" f))

(defn read-edn-file
  "The EDN value of the project file `rel`. When it does not exist: `default`
  (3 arguments), else cannot run. One that is not readable EDN cannot run."
  ([req rel]
   (let [v (read-edn-file req rel ::missing)]
     (when (= ::missing v) (command/cannot-run! (str "no " rel)))
     v))
  ([req rel default]
   (let [f (command/path req rel)]
     (if-not (fs/regular-file? f)
       default
       (try (edn/read-string (slurp (str f)))
            (catch Exception e
              (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))))))

(defn read-cycle
  "The cycle's cycle.edn as a map, carrying ::file (its path), or cannot run."
  [req cycle]
  (let [rel (cycle-file req cycle "cycle.edn")
        c (read-edn-file req rel nil)]
    (cond
      (nil? c) (command/cannot-run! (str "no " rel ": a cycle is declared by hand in its cycle.edn before it is measured"))
      (not (map? c)) (command/cannot-run! (str rel " is not an EDN map"))
      :else (assoc c ::file rel))))

;; ---------------------------------------------------------------------------
;; Ids

(defn- id-key [id]
  (let [s (str id)
        [_ n] (re-matches #"[A-Za-z][A-Za-z0-9_]*-(\d+)" s)]
    (if n
      [0 (str/replace s #"-\d+$" "") (parse-long n) s]
      [1 s 0 s])))

(defn id-order
  "Pure: the ids sorted: scenario ids (prefix-NNNN) by prefix and number,
  then any other (a clause key) by its text."
  [ids]
  (vec (sort-by id-key (distinct (seq ids)))))

;; ---------------------------------------------------------------------------
;; Scope

(defn scope-parts
  "Pure: the scope as [{:context kw} | {:component kw}], nil for the whole
  project, or ::malformed."
  [scope]
  (let [part? (fn [p] (and (map? p) (= 1 (count p))
                           (or (keyword? (:context p)) (qualified-keyword? (:component p)))))]
    (cond
      (or (nil? scope) (= :project scope)) nil
      (part? scope) [scope]
      (and (vector? scope) (seq scope) (every? part? scope)) scope
      :else ::malformed)))

(defn in-scope?
  "Pure: whether the scenario (its :context and :component, the model's ids)
  lies in the scope parts (nil: the whole project)."
  [parts {:keys [context component]}]
  (or (nil? parts)
      (boolean (some #(or (and (:context %) (= (:context %) context))
                          (and (:component %) (= (:component %) component)))
                     parts))))

(defn clauses-in-scope?
  "Pure: clause items belong to a cycle scoped to the whole project or naming
  the context compliance."
  [parts]
  (or (nil? parts) (boolean (some #(= :compliance (:context %)) parts))))

;; ---------------------------------------------------------------------------
;; Items

(defn- level-name [l] (if (keyword? l) (name l) (str l)))

(defn scenario-findings
  "Pure: what stops the checklist in the scenarios: one without a single id,
  one stamped with no single level."
  [scenarios]
  (vec (concat
        (for [sc scenarios :when (nil? (:id sc))]
          (command/finding (:file sc) (:line sc) "scenario-unstamped"
                           (str "the scenario " (pr-str (:title sc))
                                " carries no single id: an unstamped scenario stops the checklist rather than shrinking it;"
                                " stamp it first")))
        (for [sc scenarios :when (and (:id sc) (nil? (:level sc)))]
          (command/finding (:file sc) (:line sc) "scenario-no-level"
                           (str (:id sc) " carries no single level, so it cannot be an item to grade"))))))

(defn items
  "Pure: {:items [..] :planned [..]} of the stamped scenarios in scope,
  each sorted by id, keys in the published order."
  [scenarios parts]
  (let [scs (filter #(and (:id %) (in-scope? parts %)) scenarios)
        by-id (fn [xs] (sort-by #(id-key (get % "id")) xs))]
    {:items (vec (by-id (for [sc scs :when (not (:wip? sc))]
                          (array-map "id" (:id sc)
                                     "title" (:title sc)
                                     "context" (or (:context-name sc) (some-> (:context sc) name))
                                     "component" (or (:component-name sc) (some-> (:component sc) name))
                                     "level" (level-name (:level sc))))))
     :planned (vec (by-id (for [sc scs :when (:wip? sc)]
                            (array-map "id" (:id sc) "title" (:title sc)))))}))

(defn clause-items
  "Pure: a checklist item per clause, in the order given."
  [clauses]
  (vec (for [c clauses]
         (array-map "id" (:key c)
                    "title" (:description c)
                    "context" "compliance"
                    "component" (:clause-list c)
                    "level" "clause"))))

(defn checklist-text
  "Pure: checklist.json's text, keys in the published order."
  [cycle cut items planned]
  (str (json/generate-string (array-map "cycle" cycle "cut" cut "items" items "planned" planned)
                             {:pretty true})
       "\n"))

;; ---------------------------------------------------------------------------
;; The stage

(defn subscriptions
  "Keeps SpecParsed and ClausesIndexed in the run's state."
  [req]
  (for [e [:SpecParsed :ClausesIndexed]]
    [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)]))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

(defn- commit-of [root rev]
  (let [r (p/shell {:dir (str root) :out :string :err :string :continue true :in ""}
                   "git" "rev-parse" "--verify" "--quiet" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn checklist
  "The checklist stage."
  [req]
  (let [cycle-name (first (:args req))
        cycle (read-cycle req cycle-name)
        cut (:cut cycle)
        _ (when-not (and (string? cut) (not (str/blank? cut)))
            (command/cannot-run! (str (::file cycle) " names no :cut: a cycle reads one cut")))
        _ (when-not (commit-of (:root req) cut)
            (command/cannot-run! (str "the cut " cut " that " (::file cycle) " names is no commit of this repository")))
        parts (scope-parts (:scope cycle))
        _ (when (= ::malformed parts)
            (command/cannot-run! (str (::file cycle) " has the scope " (pr-str (:scope cycle))
                                      ": a scope is :project, {:context :c}, {:component :c/n} or a vector of those")))
        parsed (or (event req :SpecParsed)
                   (command/cannot-run! (str "the spec at the cut " cut " could not be read")))
        scenarios (:scenarios parsed)
        refusals (for [{:keys [file line rule message]} (:refusals parsed)]
                   (command/finding file line rule message))
        stops (vec (concat refusals (scenario-findings scenarios)))
        rel (cycle-file req cycle-name "checklist.json")]
    (if (seq stops)
      {:result {} :findings stops}
      (let [{:keys [items planned]} (items scenarios parts)
            clauses (when (and (contains? (:config req) :regulated) (clauses-in-scope? parts))
                      (clause-items (:clauses (event req :ClausesIndexed))))
            all (into items clauses)]
        (if (and (empty? all) (empty? planned))
          {:result {}
           :findings [(command/finding (::file cycle) nil "checklist-empty"
                                       (str "the spec at the cut " cut " holds no stamped scenario in the scope "
                                            (pr-str (or (:scope cycle) :project))
                                            ": an empty spec stops the checklist rather than producing an empty one"))]}
          (let [f (command/path req rel)]
            (fs/create-dirs (fs/parent f))
            (spit (str f) (checklist-text cycle-name cut all planned))
            {:result {:checklist {:cycle cycle-name :cut cut :file rel
                                  :items (count all) :clauses (count clauses) :planned (count planned)}}
             :findings []}))))))
