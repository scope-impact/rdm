(ns steps.measurement-checklist
  "Step definitions of dhf/spec/measurement/checklist.feature."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def checklist-file "dhf/cycles/03/checklist.json")
(defn- text [ctx] (let [f (fs/file (k/guard! (:dir ctx)) checklist-file)] (when (fs/exists? f) (slurp f))))
(defn checklist [ctx] (json/parse-string (or (text ctx) (fail! (str "no checklist: " (:err ctx))))))

(defstep #"the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip"
  (fn [ctx]
    (let [ctx (k/project! ctx {:features {k/core-feature
                                          (k/feature-text {:name "Core" :tags ["UN-001"]
                                                           :scenarios (for [i (range 1 13)]
                                                                        (cond-> (k/sc i (str "Scenario " i))
                                                                          (= i 7) (update :tags conj "wip")))})}
                               :extra {"dhf/spec/ids.edn" "{:issued 12}\n"}})]
      (k/tag-cut! ctx "spec-3"))))

(defstep #"dhf/cycles/03/checklist.json holds 11 items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [items (get (checklist ctx) "items")]
      (assert (= 11 (count items)) (pr-str items))
      (assert (every? #(= {"context" "kernel" "component" "Core" "level" "e2e"} (select-keys % ["context" "component" "level"])) items))
      (assert (= (str "Scenario " 1) (get (first items) "title")))
      (assert (not-any? #(= "spec-0007" (get % "id")) items)))
    ctx))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx] (assert (= [{"id" "spec-0007" "title" "Scenario 7"}] (get (checklist ctx) "planned"))) ctx))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx {:model-spec {:contexts [["architecture" ["lint"]]]}
                                  :features {"dhf/spec/architecture/lint.feature"
                                             (k/feature-text {:name "Lint" :tags ["UN-001"]
                                                              :scenarios [(k/sc 13 "A component that owns no feature file fails the lint" ["Given a step"])
                                                                          (update (k/sc 12 "A view with no description fails the lint") :tags (fn [t] t))]})}
                                  :extra {"dhf/spec/ids.edn" "{:issued 13}\n"}})
          f (fs/file (:dir ctx) "dhf/spec/architecture/lint.feature")]
      (spit f (str/replace (slurp f) "@spec-0013 @e2e" "@spec-0013 @e2e @wip"))
      (fs/delete (fs/file (:dir ctx) k/core-feature))
      (k/tag-cut! ctx "spec-3"))))

(defstep #"dhf/cycles/03/checklist.json holds:"
  (fn [ctx doc]
    (k/check-exit ctx 0)
    (assert (= (json/parse-string doc) (checklist ctx)) (text ctx))
    ctx))

(defn- in-order? [t ks] (apply < (map #(or (str/index-of t (str "\"" % "\"")) (fail! (str % " is missing: " t))) ks)))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [t (text ctx)]
      (assert (in-order? t ["cycle" "cut" "items" "planned"]) t)
      (assert (in-order? t ["id" "title" "context" "component" "level"]) t))
    ctx))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (k/check-exit ctx 1)
    (apply k/err-line-with ctx (:names ctx))
    (assert (nil? (text ctx)))
    ctx))

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std.txt at the cut holds \"STD:5.1 Development plan\""
  (fn [ctx] (-> (k/regulated! ctx) (k/verifiable!))))

(defstep #"the items of dhf/cycles/03/checklist.json end with:"
  (fn [ctx doc]
    (k/check-exit ctx 0)
    (assert (= (json/parse-string doc) (last (get (checklist ctx) "items"))) (text ctx))
    ctx))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (k/write-files! (:dir ctx) {"dhf/clauses/std.txt" "STD:5.1 Development plan\ninclude base.txt\nSTD:5.2 Requirements\n"
                                "dhf/clauses/base.txt" "STD:4.1 Quality system\nSTD:5.1 Development plan, again\n"})
    (k/tag-cut! ctx "spec-3")
    (let [r (k/rdm ctx ["checklist" "03"])]
      (k/check-exit r 0)
      (assert (= ["spec-0001" "STD:5.1" "STD:4.1" "STD:5.2"] (map #(get % "id") (get (checklist ctx) "items"))) (text ctx))
      (assert (= "dhf/clauses/base.txt" (get (nth (get (checklist ctx) "items") 2) "component"))))
    ctx))

(defstep #"cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses"
  (fn [ctx]
    (let [ctx (-> (k/regulated! ctx {:opts {:model-spec {:contexts [["architecture" ["lint"]]] :kernel ["core"]}
                                            :features {"dhf/spec/architecture/lint.feature"
                                                       (k/feature-text {:name "Lint" :tags ["UN-001"] :scenarios [(k/sc 2 "Lints")]})}}})
                  (k/verifiable! {:extra {"dhf/spec/ids.edn" "{:issued 2}\n"}}))]
      (-> ctx (k/cycle! "03" (merge {:cut "spec-3" :scope {:context :architecture}} k/roles)) (k/tag-cut! "spec-3")))))

(defstep #"dhf/cycles/03/checklist.json holds no item at the level clause"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [items (get (checklist ctx) "items")]
      (assert (= ["spec-0002"] (map #(get % "id") items)) (text ctx)))
    ctx))
