(ns steps.measurement-checklist
  "Step definitions of dhf/spec/measurement/checklist.feature, and the
  measurement context's helpers."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn checklist-file [ctx] (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03/checklist.json"))

(defn checklist [ctx]
  (k/check-exit ctx 0)
  (let [f (checklist-file ctx)]
    (when-not (fs/exists? f) (fail! (str "no checklist.json: " (:err ctx))))
    (json/parse-string (slurp f))))

(defstep #"the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip"
  (fn [ctx]
    (let [ctx (k/project! ctx {:features {"dhf/spec/kernel/core.feature"
                                          (k/feature-text {:name "Core" :tags ["UN-001"]
                                                           :scenarios (for [i (range 1 13)]
                                                                        (cond-> (k/sc i (str "Scenario " i))
                                                                          (= i 7) (update :tags conj "wip")))})}})]
      (k/tag-cut! ctx "spec-3"))))

(defstep #"dhf/cycles/03/checklist.json holds 11 items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx]
    (let [items (get (checklist ctx) "items")]
      (assert (= 11 (count items)) (count items))
      (assert (every? #(= #{"id" "title" "context" "component" "level"} (set (keys %))) items))
      (assert (= {"id" "spec-0001" "title" "Scenario 1" "context" "kernel" "component" "Core" "level" "e2e"} (first items)) (pr-str (first items)))
      ctx)))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (assert (= [{"id" "spec-0007" "title" "Scenario 7"}] (get (checklist ctx) "planned")))
    ctx))

(def lint-feature
  "@UN-001
Feature: Lint

  @spec-0013 @e2e @wip
  Scenario: A component that owns no feature file fails the lint
    Given a step

  @spec-0012 @e2e
  Scenario: A view with no description fails the lint
    Given a step
")

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (let [ctx (k/project! ctx {:model-spec {:contexts [["architecture" ["lint"]]]}
                               :features {"dhf/spec/architecture/lint.feature" lint-feature}})]
      (fs/delete (fs/path (:dir ctx) "dhf/spec/kernel/core.feature"))
      (k/cycle-ready! ctx))))

(defstep #"dhf/cycles/03/checklist.json holds:"
  (fn [ctx doc]
    (assert (= (json/parse-string doc) (checklist ctx)) (slurp (checklist-file ctx)))
    ctx))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [t (slurp (checklist-file ctx))
          at #(str/index-of t (str "\"" % "\""))]
      (assert (< (at "cycle") (at "cut") (at "items") (at "planned")) t)
      (assert (< (at "id") (at "title") (at "context") (at "component") (at "level")) t)
      ctx)))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (k/check-exit ctx 1)
    (apply k/err-line-with ctx (:names ctx))
    (assert (not (fs/exists? (checklist-file ctx))))
    ctx))

(defn regulated-cycle! [ctx scope clauses]
  (let [ctx (k/regulated! ctx {:clauses clauses})]
    (k/cycle! ctx "03" (merge {:cut "spec-3" :scope scope} k/roles))
    (k/tag-cut! ctx "spec-3")))

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std.txt at the cut holds \"STD:5.1 Development plan\""
  (fn [ctx] (regulated-cycle! ctx :project "STD:5.1 Development plan\n")))

(defstep #"the items of dhf/cycles/03/checklist.json end with:"
  (fn [ctx doc]
    (assert (= (json/parse-string doc) (last (get (checklist ctx) "items"))) (slurp (checklist-file ctx)))
    ctx))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (k/write-files! (:dir ctx) {"dhf/clauses/std.txt" "STD:5.1 Development plan\ninclude base.txt\nSTD:5.2 Analysis\nSTD:5.1 Again\n"
                                "dhf/clauses/base.txt" "STD:4.1 Quality system\n"
                                "dhf/documents/plan.md" (k/doc-text {:id "P" :revision "1" :title "P"} "[[STD:5.1]] [[STD:5.2]] [[STD:4.1]]\n")})
    (k/tag-cut! ctx "spec-3")
    (let [r (k/rdm ctx ["checklist" "03"])
          items (get (checklist r) "items")]
      (assert (= ["spec-0001" "STD:5.1" "STD:4.1" "STD:5.2"] (map #(get % "id") items)) (pr-str items))
      (assert (= ["e2e" "clause" "clause" "clause"] (map #(get % "level") items)))
      ctx)))

(defstep #"cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses"
  (fn [ctx]
    (let [ctx (k/regulated! ctx {:opts {:model-spec {:contexts [["architecture" ["lint"]]] :kernel ["core"]}
                                        :features {"dhf/spec/architecture/lint.feature" lint-feature}}})]
      (k/cycle! ctx "03" (merge {:cut "spec-3" :scope {:context :architecture}} k/roles))
      (k/tag-cut! ctx "spec-3"))))

(defstep #"dhf/cycles/03/checklist.json holds no item at the level clause"
  (fn [ctx]
    (let [items (get (checklist ctx) "items")]
      (assert (seq items))
      (assert (not-any? #(= "clause" (get % "level")) items) (pr-str items))
      ctx)))
