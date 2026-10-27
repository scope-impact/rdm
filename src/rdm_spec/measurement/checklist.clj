(ns rdm-spec.measurement.checklist
  "The checklist (dhf/spec/measurement/checklist.feature): derived, never
  written by hand, from the spec at the cycle's cut: one item per stamped
  scenario of the cycle's scope, the planned ones listed apart, and, under the
  regulated profile, one item per clause after them. An unstamped scenario
  stops it rather than shrinking it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]))

(defn- scope-part? [x]
  (and (map? x) (= 1 (count x))
       (let [[k v] (first x)]
         (case k
           (:context :component) (keyword? v)
           :spec (string? v)
           false))))

(defn scope? [scope]
  (or (= :project scope) (scope-part? scope)
      (and (sequential? scope) (seq scope) (every? scope-part? scope))))

(defn- parts [scope] (if (sequential? scope) scope [scope]))

(defn in-scope? [scope s]
  (or (= :project scope)
      (some (fn [{:keys [context component spec]}]
              (or (and context (= context (:context s)))
                  (and component (= component (:component s)))
                  (and spec (= spec (:spec s)))))
            (parts scope))))

(defn clauses? [scope]
  (or (= :project scope) (some #(= :compliance (:context %)) (parts scope))))

(defn- spec-in-scope? [scope sc]
  (let [specs (keep :spec (parts scope))]
    (or (= :project scope) (empty? specs) (some #{(:name sc)} specs)
        (some #(or (:context %) (:component %)) (parts scope)))))

(defn read-cycle
  "A cycle's cycle.edn, and the configuration at its cut: {:cycle :cut :config :dir}."
  [root config id]
  (let [dir (str (:cycles-dir config "dhf/cycles") "/" id)
        f (str dir "/cycle.edn")
        c (or (try (cmd/read-edn (cmd/read-at root nil f)) (catch Exception _ nil))
              (cmd/cannot-run! (str "there is no readable " f ": a cycle is declared by hand in its cycle.edn")))
        tag (or (:cut c) (cmd/cannot-run! (str f " names no :cut")))]
    (when-not (cut/commit-of root (str "refs/tags/" tag))
      (cmd/cannot-run! (str "the cut " tag " that " f " names is no tag of this repository")))
    (when-not (scope? (:scope c :project))
      (cmd/cannot-run! (str "the scope " (pr-str (:scope c)) " in " f " is not a scope: it is :project, {:context C}, {:component C/N}, {:spec S} or a vector of them")))
    {:cycle c :cut tag :dir dir
     :config (or (try (cmd/read-edn (cmd/read-at root tag "dhf/harness/config.edn")) (catch Exception _ nil)) config)}))

(defn stage [{:keys [root config args]}]
  (let [id (first args)
        {:keys [cycle cut dir] cfg :config} (read-cycle root config id)
        scope (:scope cycle :project)
        specs (for [sc (cmd/spec-configs cfg) :when (spec-in-scope? scope sc)] (parser/parse! root cut sc))
        scenarios (mapcat :scenarios specs)
        unstamped (for [s scenarios :when (empty? (:ids s))]
                    (cmd/finding (:file s) (:line s) "scenario-unstamped"
                                 (str "the scenario \"" (:title s) "\" at the cut " cut " has no id: an unstamped scenario stops the checklist; run \"rdm-spec stamp\"")))]
    (if (seq unstamped)
      {:findings (vec unstamped)}
      (let [mine (sort-by :id (filter #(in-scope? scope %) (filter :id scenarios)))
            items (vec (for [s mine :when (not (:wip? s))]
                         (array-map "id" (:id s) "title" (:title s) "context" (some-> (:context s) name)
                                    "component" (:component-name s) "level" (some-> (:level s) name))))
            planned (vec (for [s mine :when (:wip? s)] (array-map "id" (:id s) "title" (:title s))))
            clause-items (when (and (:regulated cfg) (clauses? scope))
                           (let [{:keys [clauses]} ((requiring-resolve 'rdm-spec.compliance.gap/read-lists)
                                                    root cut (get-in cfg [:regulated :clause-lists]))]
                             (for [c clauses]
                               (array-map "id" (:key c) "title" (:description c) "context" "compliance"
                                          "component" (:clause-list c) "level" "clause"))))
            file (str dir "/checklist.json")]
        (fs/create-dirs (fs/path root dir))
        (spit (fs/file root file)
              (str (json/generate-string (array-map "cycle" id "cut" cut "items" (into items clause-items) "planned" planned)
                                         {:pretty true})
                   "\n"))
        {:result {:checklist {:cycle id :cut cut :file file :items (+ (count items) (count clause-items)) :planned (count planned)}}}))))
