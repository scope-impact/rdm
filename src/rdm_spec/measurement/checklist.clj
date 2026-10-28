(ns rdm-spec.measurement.checklist
  "Derives one item per stamped scenario at the cycle's cut, never written by
  hand, for a grader to grade: the items of the cycle's scope, the planned
  ones apart, and under the regulated profile every clause
  (dhf/spec/07-measure/checklist.feature, 10, 11, 12 and 13)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]))

(defn checklist-path [cfg cycle] (str (:cycles-dir cfg) "/" cycle "/checklist.json"))

(defn role-of [sc slice] (if (= slice (:slice sc)) "slice" "regression"))

(defn in-scope
  "The scenarios of a scope: the project; contexts, components, specs and slices (each slice the components it reaches)."
  [scenarios scope slice-reach]
  (if (or (nil? scope) (= :project scope))
    (vec scenarios)
    (let [entries (if (sequential? scope) scope [scope])
          ok? (fn [sc]
                (some (fn [e]
                        (cond (:context e) (= (:context e) (:context sc))
                              (:component e) (= (:component e) (:component sc))
                              (:spec e) (= (:spec e) (:spec sc))
                              (:slice e) (some #{(:component sc)} (get slice-reach (:slice e)))))
                      entries))]
      (filterv ok? scenarios))))

(defn item [sc slice]
  (cond-> (array-map "id" (:id sc) "title" (:title sc) "context" (or (:context-name sc) (some-> (:context sc) name))
                     "component" (:component-name sc) "level" (some-> (:level sc) name))
    (:slice sc) (assoc "slice" (:slice sc))
    slice (assoc "role" (role-of sc slice))))

(defn derive-items
  "{:items [...] :planned [...]} of the scenarios in scope, sorted by id."
  [scenarios {:keys [slice]}]
  (let [stamped (sort-by :id (filter :id scenarios))]
    {:items (vec (for [sc stamped :when (not (:wip? sc))] (item sc slice)))
     :planned (vec (for [sc stamped :when (:wip? sc)] (array-map "id" (:id sc) "title" (:title sc))))}))

(defn unstamped-findings [scenarios]
  (vec (for [sc scenarios :when (not= 1 (count (:ids sc)))]
         (project/finding (:file sc) (:line sc) "scenario-unstamped"
                          (str "the scenario \"" (:title sc) "\" carries " (if (empty? (:ids sc)) "no id" "more than one id")
                               ": a cut holding an unstamped scenario has no checklist")))))

(defn clause-item [c]
  (array-map "id" (:key c) "title" (:description c) "context" "compliance" "component" (:declared c) "level" "clause"))

(defn read-cycle [root cfg cycle]
  (let [rel (str (:cycles-dir cfg) "/" cycle "/cycle.edn")
        f (fs/path root rel)]
    (when-not (fs/exists? f) (project/cannot-run! (str "no " rel ": a cycle is declared by hand, in its cycle.edn")))
    (let [c (try (edn/read-string (slurp (str f))) (catch Exception e (project/cannot-run! (str rel " cannot be read: " (project/one-line (ex-message e))))))]
      (when-not (and (map? c) (string? (:cut c))) (project/cannot-run! (str rel " names no :cut")))
      (when (and (contains? c :slice) (contains? c :scope))
        (project/cannot-run! (str rel " holds both :slice and :scope: a cycle names the slice it builds, or its scope, not both")))
      (cond-> c (:slice c) (assoc :scope [{:slice (:slice c)}])))))

(defn scope-problem [scope]
  (let [entry-ok? (fn [e] (and (map? e) (= 1 (count e))
                               (or (keyword? (:context e)) (and (keyword? (:component e)) (namespace (:component e)))
                                   (string? (:spec e)) (string? (:slice e)))))]
    (cond (nil? scope) nil
          (= :project scope) nil
          (entry-ok? scope) nil
          (and (vector? scope) (seq scope) (every? entry-ok? scope)) nil
          :else (str "the scope " (pr-str scope) " is not :project, {:context C}, {:component C/N}, {:spec S}, {:slice S}, or a vector of them"))))

(defn check-scope!
  "Refuses a scope naming what the cut does not hold."
  [scope model specs rel-files]
  (when-let [p (scope-problem scope)] (project/cannot-run! p))
  (let [ctxs (conj (set (map :id (mapcat :contexts (:containers model)))) :kernel)
        comps (set (map :id (parser/components model)))
        names (set (keep :name specs))
        slices (set (parser/slices-of rel-files))]
    (doseq [e (if (sequential? scope) scope (when (map? scope) [scope]))]
      (cond (and (:context e) (not (ctxs (:context e)))) (project/cannot-run! (str "the scope names the bounded context " (name (:context e)) ", which the cut's model does not hold"))
            (and (:component e) (not (comps (:component e)))) (project/cannot-run! (str "the scope names the component " (subs (str (:component e)) 1) ", which the cut's model does not hold"))
            (and (:spec e) (not (names (:spec e)))) (project/cannot-run! (str "the scope names the spec " (:spec e) ", which the cut does not declare"))
            (and (:slice e) (not (slices (:slice e)))) (project/cannot-run! (str "the cut holds no slice " (:slice e)))))))

(defn write-json [file m]
  (fs/create-dirs (fs/parent file))
  (spit (str file) (str (json/generate-string m {:pretty true}) "\n")))

(defn checklist [run]
  (let [root (:root run) wcfg (:cfg run)
        cycle (first (:args run))
        cyc (read-cycle root wcfg cycle)
        cut-name (:cut cyc)
        _ (when-not (cut/tag-commit root cut-name) (project/cannot-run! (str cut-name " is not a cut: the checklist is derived from a commit tagged spec-N")))]
    (cut/with-cut [dir root cut-name]
      (let [cfg (project/with-defaults (or (try (edn/read-string (slurp (str (fs/path dir project/config-path)))) (catch Exception _ nil))
                                           (project/cannot-run! (str "the cut " cut-name " holds no readable " project/config-path))))
            _ (parser/validate-specs! dir cfg)
            reading (parser/read-specs dir cfg)
            scenarios (vec (mapcat :scenarios (:specs reading)))
            unstamped (unstamped-findings scenarios)]
        (if (seq unstamped)
          {:findings unstamped :result {:checklist {:cycle cycle :cut cut-name :written false}}}
          (let [model (or (parser/read-model-lenient (fs/path dir (:model cfg))) {})
                rel-files (for [f (project/files-under dir (:spec-dir cfg))] (subs f (inc (count (:spec-dir cfg)))))
                scope (:scope cyc :project)
                _ (check-scope! scope model (:specs reading) rel-files)
                slices (keep #(when (map? %) (:slice %)) (if (sequential? scope) scope [scope]))
                reach (into {} (for [s slices] [s (parser/slice-reach model rel-files s)]))
                one-slice (when (and (= 1 (count slices)) (sequential? scope) (= 1 (count scope))) (first slices))
                {:keys [items planned]} (derive-items (in-scope scenarios scope reach) {:slice one-slice})
                clause? (and (contains? cfg :regulated)
                             (or (= :project scope) (some #(= :compliance (:context %)) (if (sequential? scope) scope [scope]))))
                clauses (when clause?
                          (:clauses ((requiring-resolve 'rdm-spec.compliance.gap/read-clauses) dir cfg)))
                all-items (vec (concat items (map clause-item clauses)))
                file (checklist-path wcfg cycle)]
            (write-json (fs/path root file) (array-map "cycle" cycle "cut" cut-name "items" all-items "planned" planned))
            {:result {:checklist (cond-> {:cycle cycle :cut cut-name :scope scope :items (count all-items) :planned (count planned) :file file}
                                   clause? (assoc :clauses (count clauses)))}}))))))
