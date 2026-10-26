(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature: `rdm-spec
  checklist 03` in a cycle project (steps.measurement-cycle) whose spec at
  the cut spec-3 holds the lint's feature file with the scenarios a step
  gives. The kernel's shared Givens (dhf/cycles/03/cycle.edn names the cut
  spec-3; the spec at cut spec-3 holds a scenario with no id) build on it.

  Context keys set here: :dir, :scenarios (those the lint's feature file
  holds at the cut), :clauses (the clause keys of the cut's lists, in
  order)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def ^:private checklist-rel (mc/cycle-path "03" "checklist.json"))

(defn- checklist-text [ctx]
  (let [r (kc/the-run ctx)
        f (fs/file (:dir ctx) checklist-rel)]
    (s/check! (fs/exists? f) (str "no " checklist-rel "\n" (s/show r)))
    (slurp (str f))))

(defn- checklist [ctx] (json/parse-string (checklist-text ctx) true))

(defn- cut-project! [ctx scenarios]
  (let [dir (mc/project! {mc/lint-feature scenarios})]
    (mc/cut! dir "spec-3")
    (assoc ctx :dir dir :scenarios scenarios)))

;; ---------------------------------------------------------------------------
;; Derived from the cycle's cut

(defstep #"the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip"
  (fn [ctx]
    (cut-project! ctx (update (mc/numbered 1 12) 4 assoc :wip? true))))

(defstep #"dhf/cycles/03/checklist\.json holds 11 items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [{:keys [items]} (checklist ctx)
          want (sort (map :id (remove :wip? (:scenarios ctx))))
          titles (into {} (map (juxt :id :title)) (:scenarios ctx))]
      (s/check! (= want (map :id items)) (str "the items are " (pr-str (map :id items))))
      (doseq [it items]
        (s/check! (= {:id (:id it) :title (titles (:id it)) :context "architecture" :component "Lint" :level "e2e"} it)
                  (str "the item is " (pr-str it))))
      nil)))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [planned items]} (checklist ctx)
          wip (first (filter :wip? (:scenarios ctx)))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] planned) (str "planned is " (pr-str planned)))
      (s/check! (not-any? #(= (:id wip) (:id %)) items) "the planned scenario is an item to grade")
      nil)))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (let [ctx (cut-project! ctx [{:id "spec-0012" :title "A view with no description fails the lint"}
                                 {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])]
      (mc/write-cycle! (:dir ctx) "03" {})
      ctx)))

(defstep #"dhf/cycles/03/checklist\.json holds:"
  (fn [ctx doc]
    (kc/exits! ctx 0)
    (let [got (checklist ctx)
          want (json/parse-string doc true)]
      (s/check! (= want got) (str "checklist.json holds\n" (checklist-text ctx)))
      nil)))

(defn- positions [text ks]
  (map #(str/index-of text (str "\"" % "\"")) ks))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (checklist-text ctx)
          {:keys [items planned]} (checklist ctx)
          top (positions text ["cycle" "cut" "items" "planned"])]
      (s/check! (and (every? some? top) (apply < top)) (str "the keys are out of order:\n" text))
      (let [item-text (subs text (str/index-of text "\"items\""))
            ks (positions item-text ["id" "title" "context" "component" "level"])]
        (s/check! (and (every? some? ks) (apply < ks)) (str "an item's keys are out of order:\n" text)))
      (s/check! (= (map :id items) (sort (map :id items))) "the items are not sorted by id")
      (s/check! (= (map :id planned) (sort (map :id planned))) "planned is not sorted by id")
      nil)))

;; ---------------------------------------------------------------------------
;; An unstamped scenario stops the checklist (the Given is the kernel's)

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          {:keys [file line]} (:unstamped ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (kc/finding-lines r))
                (str "no finding names " file ":" line "\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file (:dir ctx) checklist-rel))) (str checklist-rel " was written"))
      nil)))

;; ---------------------------------------------------------------------------
;; Under the regulated profile

(defn- regulated-cut! [ctx scope clause-lines]
  (let [dir (mc/project! {mc/lint-feature [{:id "spec-0012" :title "A view with no description fails the lint"}
                                           {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}]})
        cfg (kc/config-of dir)]
    (s/write! dir "dhf/harness/config.edn"
              (str (pr-str (assoc cfg :regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})) "\n"))
    (s/write! dir "dhf/clauses/std.txt" (str (str/join "\n" clause-lines) "\n"))
    (s/write! dir "dhf/documents/plan.md"
              "---\nid: PLAN-001\nrevision: 1\ntitle: Development plan\n---\n\n# Development plan\nWe plan [[STD:5.1]].\n")
    (mc/cut! dir "spec-3")
    (mc/write-cycle! dir "03" {:scope scope})
    (assoc ctx :dir dir :clauses (keep #(second (re-find #"^(\S+:\S*)\s" %)) clause-lines))))

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std\.txt at the cut holds \"STD:5\.1 Development plan\""
  (fn [ctx] (regulated-cut! ctx :project ["STD:5.1 Development plan"])))

(defstep #"the items of dhf/cycles/03/checklist\.json end with:"
  (fn [ctx doc]
    (kc/exits! ctx 0)
    (let [items (:items (checklist ctx))]
      (s/check! (= (json/parse-string doc true) (last items)) (str "the last item is " (pr-str (last items))))
      nil)))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (let [items (:items (checklist ctx))
          levels (map :level items)
          clause-ids (map :id (filter #(= "clause" (:level %)) items))]
      (s/check! (= levels (concat (remove #{"clause"} levels) (filter #{"clause"} levels)))
                (str "a scenario item comes after a clause item: " (pr-str (map :id items))))
      (s/check! (= (:clauses ctx) clause-ids) (str "the clause items are " (pr-str clause-ids)))
      nil)))

(defstep #"cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses"
  (fn [ctx] (regulated-cut! ctx {:context :architecture} ["STD:5.1 Development plan" "STD:5.2 Design"])))

(defstep #"dhf/cycles/03/checklist\.json holds no item at the level clause"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [items (:items (checklist ctx))]
      (s/check! (seq items) "the checklist holds no item")
      (s/check! (not-any? #(= "clause" (:level %)) items) (str "the items are " (pr-str items)))
      nil)))
