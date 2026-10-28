(ns rdm-spec.measurement.checklist
  "Component Checklist (measurement context): derives the cycle's checklist
  from the spec at its cut, never by hand: one item per stamped scenario of
  the cycle's scope, planned scenarios apart, and under the regulated
  profile one item per clause after them. An unstamped scenario stops it."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]))

(defn read-cycle [cfg cycle]
  (let [f (str (:cycles-dir cfg) "/" cycle "/cycle.edn")
        r (project/read-edn-file (project/root) f)]
    (cond
      (:missing r) (project/cannot-run! (str "no " f ": a cycle is declared by hand before its checklist is derived"))
      (:error r) (project/cannot-run! (str f ":" (get-in r [:error :line]) ": the cycle is not readable EDN: " (get-in r [:error :message])))
      (not (map? (:value r))) (project/cannot-run! (str f " is not a map"))
      :else (:value r))))

(defn ctx-name [sc] (cond (= :kernel (:context sc)) "kernel" (:context sc) (name (:context sc))))

(defn scope-pred
  "Whether a scenario is in the cycle's SCOPE; refuses a scope it cannot read."
  [scope cfg tree model read]
  (let [entries (cond (= :project scope) [:project] (nil? scope) [:project] (sequential? scope) scope :else [scope])
        contexts (set (concat [:kernel] (map :id (project/contexts model))))
        comps (set (map :id (project/components model)))
        preds (for [e entries]
                (cond
                  (= :project e) (constantly true)
                  (and (map? e) (:context e)) (do (when-not (contexts (:context e))
                                                    (project/cannot-run! (str "the scope names the context " (:context e) ", which the model at the cut does not hold")))
                                                  #(= (:context e) (:context %)))
                  (and (map? e) (:component e)) (do (when-not (comps (:component e))
                                                      (project/cannot-run! (str "the scope names the component " (:component e) ", which the model at the cut does not hold")))
                                                    #(= (:component e) (:component %)))
                  (and (map? e) (:spec e)) (do (when-not (some #(= (:spec e) (get-in % [:spec :name])) read)
                                                 (project/cannot-run! (str "the scope names the spec " (:spec e) ", which the configuration does not declare")))
                                               #(= (:spec e) (:spec %)))
                  (and (map? e) (:slice e))
                  (let [s (:slice e)
                        reached (set (for [r read sc (:scenarios r) :when (= s (:slice sc))] (:component sc)))
                        held? (some (fn [sp] (babashka.fs/directory? (project/abs tree (str (:dir sp) "/" s)))) (project/specs cfg))]
                    (when-not (and (project/slices? cfg) held?)
                      (project/cannot-run! (str "the scope names the slice " s ", which the cut does not hold")))
                    #(contains? reached (:component %)))
                  :else (project/cannot-run! (str "the scope " (pr-str e) " is not :project, {:context C}, {:component C/N}, {:spec S} or {:slice S}"))))]
    (fn [sc] (some #(% sc) preds))))

(defn compliance? [scope]
  (let [entries (cond (or (nil? scope) (= :project scope)) [:project] (sequential? scope) scope :else [scope])]
    (some #(or (= :project %) (and (map? %) (= :compliance (:context %)))) entries)))

(defn checklist-text [m]
  (json/generate-string m {:pretty true}))

(defn checklist-task [{:keys [cfg args]}]
  (let [cycle (first args)
        cyc (read-cycle cfg cycle)
        cut (:cut cyc)]
    (when-not (and (string? cut) (project/resolve-commit cut))
      (project/cannot-run! (str "the cycle " cycle " names the cut " (pr-str cut) ", which this repository does not hold")))
    (project/with-revision cut
      (fn [tree]
        (let [cut-cfg (project/config tree)
              _ (project/validate-specs! tree cut-cfg)
              read (parser/read-specs tree cut-cfg)
              _ (parser/publish! cut read)
              scenarios (vec (for [r read sc (:scenarios r)] sc))
              unstamped (for [sc scenarios :when (empty? (:ids sc))]
                          (project/finding (:file sc) (:line sc) "scenario-unstamped"
                                           (str "the scenario \"" (:title sc) "\" carries no id at the cut " cut ": the checklist stops rather than shrink")))]
          (if (seq unstamped)
            (project/report {} unstamped)
            (let [model (or (project/read-model tree (first (project/specs cut-cfg))) {})
                  in? (scope-pred (:scope cyc) cut-cfg tree model read)
                  slices? (project/slices? cut-cfg)
                  stamped (sort-by :id (filter #(and (:id %) (in? %)) scenarios))
                  items (vec (for [sc stamped :when (not (:wip? sc))]
                               (cond-> (array-map "id" (:id sc) "title" (:title sc) "context" (ctx-name sc)
                                                  "component" (:component-name sc) "level" (:level sc))
                                 slices? (assoc "slice" (:slice sc)))))
                  planned (vec (for [sc stamped :when (:wip? sc)] (array-map "id" (:id sc) "title" (:title sc))))
                  clauses (when (and (:regulated cut-cfg) (compliance? (:scope cyc)))
                            (:clauses ((requiring-resolve 'rdm-spec.compliance.gap/index!) cut-cfg tree cut)))
                  clause-items (vec (for [c clauses]
                                      (array-map "id" (:key c) "title" (:description c) "context" "compliance"
                                                 "component" (:declared c) "level" "clause")))
                  out (str (:cycles-dir cfg) "/" cycle "/checklist.json")
                  m (array-map "cycle" cycle "cut" cut "items" (into items clause-items) "planned" planned)]
              (babashka.fs/create-dirs (babashka.fs/parent (project/abs out)))
              (spit (project/abs out) (str (checklist-text m) "\n"))
              (project/report {:checklist {:cycle cycle :cut cut :items (count items) :clauses (count clause-items)
                                           :planned (count planned) :file out}}))))))))
