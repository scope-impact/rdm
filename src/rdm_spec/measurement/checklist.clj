(ns rdm-spec.measurement.checklist
  "Checklist (dhf/spec/measurement/checklist.feature): `rdm-spec checklist
  CYCLE` derives the cycle's checklist from the spec at the cycle's cut,
  never by hand, into dhf/cycles/CYCLE/checklist.json.

  The task's stages are the parser's `parse` (which reads the spec at the cut
  dhf/cycles/CYCLE/cycle.edn names and publishes SpecParsed), then
  `checklist`, which reads only that event's payload: the measurement context
  meets the spec context on the bus, nowhere else.

  A cycle (dhf/cycles/NN/cycle.edn, written by hand):
    {:cut \"spec-3\" :scope SCOPE
     :builder {:name :actor} :grader {:name :actor} :spec-owner {:name :actor}
     [:bootstrap true]}
  where SCOPE is :project (also when absent), {:context :kw}, {:component
  :ctx/kw}, or a non-empty vector of contexts and components. The kernel is
  the context :kernel.

  checklist.json, keys in this order, items and planned sorted by id:
    {\"cycle\": \"03\", \"cut\": \"spec-3\",
     \"items\": [{\"id\" \"title\" \"context\" \"component\" \"level\"}],
     \"planned\": [{\"id\" \"title\"}]}
  One item per stamped scenario of the scope not tagged @wip (an outline is
  one item); a @wip one is listed as planned. A cut holding a scenario with
  no id, or anything the parser refused, has no checklist: exit 1, naming
  the file and line, and nothing is written."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Cycles: shared by the measurement context's components

(def default-cycles-dir "dhf/cycles")

(defn cycle-name!
  "`cycle`, or cannot run when it is not a plain directory name."
  [cycle]
  (when-not (and (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  cycle)

(defn cycles-dir
  "The configured cycles directory, relative to the root."
  [req]
  (str/replace (str (or (get-in req [:config :cycles-dir]) default-cycles-dir)) #"/+$" ""))

(defn cycle-file
  "The path, relative to the root, of `file` in cycle `cycle`."
  [req cycle file]
  (str (cycles-dir req) "/" (cycle-name! cycle) "/" file))

(defn read-edn-file
  "The EDN value in `rel` (relative to the root), or cannot run naming it
  when it is missing (unless `missing` is given, returned then) or
  unreadable."
  ([req rel] (read-edn-file req rel ::cannot-run))
  ([req rel missing]
   (let [f (command/path req rel)]
     (if-not (fs/regular-file? f)
       (if (= ::cannot-run missing)
         (command/cannot-run! (str "no " rel))
         missing)
       (try (edn/read-string (slurp (str f)))
            (catch Exception e
              (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))))))

(defn read-cycle
  "cycle.edn of cycle `cycle`: a map naming a :cut, else cannot run."
  [req cycle]
  (let [rel (cycle-file req cycle "cycle.edn")
        f (command/path req rel)
        _ (when-not (fs/regular-file? f)
            (command/cannot-run! (str "no " rel ": a cycle is declared there, by hand, before it is ratified")))
        c (read-edn-file req rel)]
    (when-not (map? c)
      (command/cannot-run! (str rel " is not a map")))
    (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
      (command/cannot-run! (str rel " names no :cut")))
    (assoc c ::file rel ::cycle cycle)))

(defn- scope-part? [x]
  (and (map? x) (= 1 (count x))
       (or (keyword? (:context x)) (qualified-keyword? (:component x)))))

(defn scope-of
  "The cycle's scope (:project when it names none), or cannot run when it is
  not a scope."
  [cycle]
  (let [s (get cycle :scope :project)]
    (if (or (= :project s) (scope-part? s) (and (vector? s) (seq s) (every? scope-part? s)))
      s
      (command/cannot-run!
       (str (::file cycle) ": the scope " (pr-str s) " is not :project, {:context C}, {:component C/N}"
            " or a vector of contexts and components")))))

(defn- scope-parts [scope]
  (cond (= :project scope) nil
        (map? scope) [scope]
        :else scope))

(defn in-scope?
  "True when the scenario (with the model's :context and :component ids)
  belongs to `scope`."
  [scope scenario]
  (or (= :project scope)
      (boolean (some (fn [{:keys [context component]}]
                       (if context
                         (= context (:context scenario))
                         (= component (:component scenario))))
                     (scope-parts scope)))))

;; ---------------------------------------------------------------------------
;; The checklist

(defn subscriptions
  "Keeps the run's SpecParsed payload for the checklist stage."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec-parsed payload) nil)]])

(defn id-order
  "Sorts ids by length, then text: spec-0999 before spec-10000."
  [ids-or-maps]
  (sort-by (fn [x] (let [id (str (if (map? x) (:id x) x))] [(count id) id])) ids-or-maps))

(defn- kw-name [k] (cond (keyword? k) (name k) (nil? k) nil :else (str k)))

(defn item
  "A checklist item, its keys in the published order."
  [sc]
  (array-map "id" (:id sc)
             "title" (:title sc)
             "context" (or (:context-name sc) (kw-name (:context sc)))
             "component" (or (:component-name sc) (kw-name (:component sc)))
             "level" (kw-name (:level sc))))

(defn planned-item [sc] (array-map "id" (:id sc) "title" (:title sc)))

(defn unstamped-findings
  "A finding for every scenario carrying no single id, and every refusal of
  the parser: the checklist cannot name them, so it has none."
  [payload]
  (vec (concat
        (for [{:keys [file line rule message]} (:refusals payload)]
          (command/finding file line rule message))
        (for [sc (:scenarios payload) :when (nil? (:id sc))]
          (command/finding (:file sc) (:line sc) "unstamped-scenario"
                           (str "the scenario \"" (:title sc) "\" "
                                (if (seq (:ids sc))
                                  (str "carries " (count (:ids sc)) " ids, not one")
                                  "carries no id")
                                "; a checklist that left it out would shrink the denominator unnoticed"))))))

(defn derive-checklist
  "The checklist of `cycle-name` at `cut` over `scope`, from SpecParsed's
  scenarios."
  [cycle-name cut scope scenarios]
  (let [scs (filter #(in-scope? scope %) scenarios)]
    (array-map "cycle" cycle-name
               "cut" cut
               "items" (mapv item (id-order (remove :wip? scs)))
               "planned" (mapv planned-item (id-order (filter :wip? scs))))))

(defn checklist-text [checklist]
  (str (json/generate-string checklist {:pretty true}) "\n"))

(defn- empty-scope-findings [cycle scope scenarios]
  (vec (for [part (scope-parts scope)
             :when (not-any? #(in-scope? part %) scenarios)]
         (command/finding (::file cycle) nil "scope-owns-nothing"
                          (str "the scope names " (pr-str part) ", which owns no scenario at the cut " (:cut cycle))))))

(defn checklist
  "The checklist stage."
  [req]
  (let [cycle-name (first (:args req))
        cycle (read-cycle req cycle-name)
        scope (scope-of cycle)
        payload (::spec-parsed @(:state req))
        _ (when-not payload
            (command/cannot-run! (str "the cut " (:cut cycle) " that " (::file cycle)
                                      " names is not a tag or commit of this repository")))
        scenarios (:scenarios payload)
        findings (into (unstamped-findings payload) (empty-scope-findings cycle scope scenarios))]
    (if (seq findings)
      {:result {} :findings findings}
      (let [cl (derive-checklist cycle-name (:cut cycle) scope scenarios)
            rel (cycle-file req cycle-name "checklist.json")
            f (command/path req rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (checklist-text cl))
        {:result {:checklist {:cycle cycle-name :cut (:cut cycle) :file rel
                              :items (count (get cl "items")) :planned (count (get cl "planned"))}}
         :findings []}))))
