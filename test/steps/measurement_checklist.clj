(ns steps.measurement-checklist
  "Steps of the component Checklist (dhf/spec/07-measure/checklist.feature)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep doc-string]]
            [steps.kernel-shared :as k]))

(defn checklist [ctx] (some-> (k/slurp-rel ctx "dhf/cycles/03/checklist.json") json/parse-string))

(defstep #"the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip"
  (fn [ctx]
    (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                     (k/feature-text "Thing" ["UN-001"] (for [i (range 1 13)] {:id (format "spec-%04d" i) :title (str "Scenario " i)
                                                                                           :wip? (= i 12)}))}})))

(defstep #"dhf/cycles/03/checklist.json holds 11 items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [items (get (checklist ctx) "items")]
      (k/check! (= 11 (count items)) "the checklist holds " (count items) " items")
      (k/check! (= {"id" "spec-0001" "title" "Scenario 1" "context" "kernel" "component" "Thing" "level" "e2e"} (first items))
                "an item is " (pr-str (first items)))
      ctx)))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx] (k/check! (= [{"id" "spec-0012" "title" "Scenario 12"}] (get (checklist ctx) "planned")) "planned: " (pr-str (get (checklist ctx) "planned"))) ctx))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx {:model (k/context-model [[:architecture [[:lint "Lint"]]]])
                                    :features {"dhf/spec/architecture/lint.feature"
                                               (k/feature-text "Lint" ["UN-001"] [{:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}
                                                                                  {:id "spec-0012" :title "A view with no description fails the lint"}])}})]
      (k/cut-project! ctx))))

(defstep #"dhf/cycles/03/checklist.json holds:"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (json/parse-string (doc-string)) (checklist ctx)) "checklist.json holds " (k/slurp-rel ctx "dhf/cycles/03/checklist.json"))
    ctx))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [t (k/slurp-rel ctx "dhf/cycles/03/checklist.json")
          pos #(str/index-of t (str "\"" % "\""))]
      (k/check! (apply < (map pos ["cycle" "cut" "items" "planned"])) "the keys are out of order:\n" t)
      (k/check! (apply < (map pos ["id" "title" "context" "component" "level"])) "an item's keys are out of order:\n" t)
      ctx)))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/err-line-has! ctx "dhf/spec/demo/thing.feature:5")
    (k/check! (nil? (checklist ctx)) "a checklist was written")
    ctx))

(def regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})

(defn regulated-cut! [ctx scope]
  (let [ctx (k/spec-project! ctx {:model (k/context-model [[:architecture [[:lint "Lint"]]]])
                                  :features {"dhf/spec/architecture/lint.feature"
                                             (k/feature-text "Lint" ["UN-001"] [{:id "spec-0001" :title "One"} {:id "spec-0002" :title "Two"}])}})]
    (k/config! ctx assoc :regulated regulated)
    (k/write! ctx "dhf/clauses/std.txt" "include base.txt\nSTD:5.1 Development plan\nSTD:4.1 Quality system, again\n")
    (k/write! ctx "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
    (k/write! ctx "dhf/documents/plan.md" "---\nid: PLAN-001\nrevision: 1\ntitle: Plan\n---\n\nWe plan [[STD:5.1]] in a system [[STD:4.1]].\n")
    (k/cut-project! ctx)
    (k/write-edn! ctx "dhf/cycles/03/cycle.edn" {:cut "spec-3" :scope scope :builder {:name "b" :actor :agent}
                                                 :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}})
    ctx))

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std.txt at the cut holds \"STD:5.1 Development plan\""
  (fn [ctx] (regulated-cut! ctx :project)))

(defstep #"the items of dhf/cycles/03/checklist.json end with:"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (json/parse-string (doc-string)) (last (get (checklist ctx) "items"))) "the items end with " (pr-str (last (get (checklist ctx) "items"))))
    ctx))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (k/check! (= ["spec-0001" "spec-0002" "STD:4.1" "STD:5.1"] (map #(get % "id") (get (checklist ctx) "items")))
              "the items are " (pr-str (map #(get % "id") (get (checklist ctx) "items"))))
    ctx))

(defstep #"cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses"
  (fn [ctx] (regulated-cut! ctx {:context :architecture})))

(defstep #"dhf/cycles/03/checklist.json holds no item at the level clause"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(= "clause" (get % "level")) (get (checklist ctx) "items")) "a clause item is listed")
    ctx))

(def plan "# Slice\n\n## Goal\n\nA goal.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- a\n\n**Out:**\n\n- b\n\n## Decisions\n\nNone.\n")

(defstep #"a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser and :measurement/checklist; :spec/parser with 2 stamped scenarios in 12-several-specs and 3 in 02-write-the-spec; and dhf/cycles/03/cycle.edn whose :scope is \[\{:slice \"12-several-specs\"\}\]"
  (fn [ctx]
    (let [sc (fn [ids] (for [i ids] {:id (format "spec-%04d" i) :title (str "Scenario " i)}))
          ctx (k/verify-project! ctx {:model (k/context-model [[:spec [[:parser "Parser"] [:stamper "Stamper"]]] [:measurement [[:checklist "Checklist"]]]])
                                      :features {"dhf/spec/12-several-specs/parser.feature" (k/feature-text "Parser" ["UN-001"] (sc [1 2]))
                                                 "dhf/spec/12-several-specs/checklist.feature" (k/feature-text "Checklist" ["UN-001"] (sc [3]))
                                                 "dhf/spec/02-write-the-spec/parser.feature" (k/feature-text "Parser" ["UN-001"] (sc [4 5 6]))
                                                 "dhf/spec/02-write-the-spec/stamper.feature" (k/feature-text "Stamper" ["UN-001"] (sc [7]))
                                                 "dhf/spec/02-write-the-spec/checklist.feature" (k/feature-text "Checklist" ["UN-001"] (sc [8]))}
                                      :files {"dhf/spec/12-several-specs/plan.md" plan "dhf/spec/02-write-the-spec/plan.md" plan}
                                      :cfg-fn #(assoc % :spec-layout :slices)})]
      (k/write-edn! ctx "dhf/cycles/03/cycle.edn" {:cut "spec-3" :scope [{:slice "12-several-specs"}] :builder {:name "b" :actor :agent}
                                                   :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}})
      ctx)))

(defstep #"it holds one item for each of the 5 scenarios of :spec/parser and each of :measurement/checklist's, wherever their slice, each item naming its slice and its component"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [items (get (checklist ctx) "items")]
      (k/check! (= ["spec-0001" "spec-0002" "spec-0003" "spec-0004" "spec-0005" "spec-0006" "spec-0008"] (map #(get % "id") items))
                "the checklist holds " (pr-str (map #(get % "id") items)))
      (k/check! (= {"id" "spec-0004" "title" "Scenario 4" "context" "spec" "component" "Parser" "level" "e2e" "slice" "02-write-the-spec"}
                   (nth items 3))
                "an item is " (pr-str (nth items 3)))
      ctx)))

(defstep #"verify requires a result for each of them, as for any scope"
  (fn [ctx]
    (let [r (k/run-rdm ctx "verify ROOM 03")]
      (k/exits! r 0)
      (k/check! (= 8 (get-in r [:result :verify :checks 0 :scenarios])) "verify required " (pr-str (get-in r [:result :verify :checks 0])))
      ctx)))
