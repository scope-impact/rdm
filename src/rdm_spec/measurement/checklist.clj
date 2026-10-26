(ns rdm-spec.measurement.checklist
  "The checklist (dhf/spec/measurement/checklist.feature): derived from the
  spec at the cycle's cut, one item per stamped scenario of the cycle's scope
  (then, under the regulated profile, one per clause), planned scenarios
  listed apart. An unstamped scenario stops it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(defn- one-scope-problem [s]
  (cond
    (= :project s) nil
    (and (map? s) (= [:context] (keys s)) (keyword? (:context s))) nil
    (and (map? s) (= [:component] (keys s)) (keyword? (:component s)) (namespace (:component s))) nil
    :else (str "the scope " (pr-str s) " is not :project, {:context C}, {:component C/N} or a list of them")))

(defn scope-problem [scope]
  (if (vector? scope)
    (if (empty? scope) "the scope is an empty list" (first (keep one-scope-problem scope)))
    (one-scope-problem scope)))

(defn in-scope? [scope s]
  (cond
    (= :project scope) true
    (vector? scope) (some #(in-scope? % s) scope)
    (:context scope) (= (:context scope) (:context s))
    (:component scope) (= (:component scope) (:component s))))

(defn- covers-compliance? [scope]
  (or (= :project scope)
      (and (vector? scope) (some covers-compliance? scope))
      (= :compliance (:context scope))))

(defn checklist-json [cycle cut items planned]
  (json/generate-string
   (array-map "cycle" cycle "cut" cut
              "items" (mapv #(array-map "id" (:id %) "title" (:title %) "context" (:context %)
                                        "component" (:component %) "level" (:level %))
                            items)
              "planned" (mapv #(array-map "id" (:id %) "title" (:title %)) planned))
   {:pretty true}))

(defn read-cycle
  "The cycle's declaration, its cut resolved: {:cycle-dir :decl :cut}."
  [root config cycle]
  (let [dir (str (:cycles-dir config "dhf/cycles") "/" cycle)
        file (str dir "/cycle.edn")
        decl (or (try (cmd/read-edn (cmd/read-at root nil file)) (catch Exception _ nil))
                 (cmd/cannot-run! (str "the cycle " cycle " has no readable " file)))
        cut (or (:cut decl) (cmd/cannot-run! (str file " names no :cut")))]
    (when-not (= 0 (:exit (cmd/git root "rev-parse" "-q" "--verify" (str cut "^{commit}"))))
      (cmd/cannot-run! (str "the cut " cut " that " file " names does not exist")))
    {:cycle-dir dir :decl decl :cut cut}))

(defn prepare-stage
  "Reads the cycle and points the stages after it at the cut."
  [{:keys [root config args]}]
  (let [cycle (first args)
        {:keys [cycle-dir decl cut]} (read-cycle root config cycle)
        scope (get decl :scope :project)
        cut-config (or (try (cmd/read-edn (cmd/read-at root cut "dhf/harness/config.edn")) (catch Exception _ nil))
                       (cmd/cannot-run! (str "the cut " cut " holds no readable dhf/harness/config.edn")))]
    (when-let [why (scope-problem scope)] (cmd/cannot-run! why))
    {:ctx {:revision cut :config cut-config
           ::checklist {:cycle cycle :cycle-dir cycle-dir :cut cut :scope scope}}}))

(defn stage [{:keys [root] :as ctx}]
  (let [{:keys [cycle cycle-dir cut scope]} (::checklist ctx)
        {:keys [scenarios]} (bus/latest :SpecParsed)
        unstamped (filter #(empty? (:ids %)) scenarios)]
    (if (seq unstamped)
      {:findings (vec (for [s unstamped]
                        (cmd/finding (:file s) (:line s) "scenario-unstamped"
                                     (str "the cut " cut " holds the unstamped scenario \"" (:title s)
                                          "\": no checklist is derived while one is unnamed"))))}
      (let [mine (filter #(and (:id %) (in-scope? scope %)) scenarios)
            item (fn [s] {:id (:id s) :title (:title s) :context (:context-name s) :component (:component-name s)
                          :level (some-> (:level s) name)})
            items (sort-by :id (map item (remove :wip? mine)))
            planned (sort-by :id (map item (filter :wip? mine)))
            clauses (when (covers-compliance? scope) (:clauses (bus/latest :ClausesIndexed)))
            clause-items (for [c clauses] {:id (:key c) :title (:description c) :context "compliance"
                                           :component (:clause-list c) :level "clause"})
            file (str cycle-dir "/checklist.json")]
        (fs/create-dirs (fs/path root cycle-dir))
        (spit (fs/file root file) (str (checklist-json cycle cut (concat items clause-items) planned) "\n"))
        {:result {:checklist {:cycle cycle :cut cut :file file
                              :items (+ (count items) (count clause-items)) :planned (count planned)}}}))))
