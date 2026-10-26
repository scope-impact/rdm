(ns rdm-spec.architecture.lint
  "The Lint component of the architecture context
  (dhf/spec/architecture/lint.feature): holds the C4 model to the C4 skill's
  quality checklist, where a machine can judge it
  (agent-skills:skills/engineering/c4-diagrams/reference/c4-guide.md), and
  to the bounded-context rules of ADR 0002. A stage of `rdm-spec check`.

  The rules, each a stable name on the error stream, at the model's line:
    c4-scope               a view with no description (its scope and audience)
    c4-element-described   an element with no description
    c4-arrow-labelled      a relationship with no description
    c4-context-actors      a context view that includes no person
    c4-container-runs      a container whose kind is neither :runs nor :stores
    c4-events-named        a relationship publishing an event the catalogue does
                           not declare, or from another than its declared publisher
    c4-view-size           (a warning) a view showing more elements than the
                           configured :c4 :view-element-limit
    c4-description-length  a person's or system's description longer than the
                           configured :c4 :description-max-chars
    a-component-view       a bounded context with components and no component view
    a-scenario-owner       a component (or kernel component) with no feature file
                           at its spec path, or a feature file no component names
    a-contract-only        a relationship from a component of one bounded context
                           to a component of another that names no event

  What no machine can judge is listed for a person to answer, in the
  result: {:lint {:for-a-person [question]}}."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as command]))

(def default-limits {:view-element-limit 20 :description-max-chars 45})

(def for-a-person
  "The checklist items no machine can judge."
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defn- blank? [s] (str/blank? (str s)))

(defn findings
  "Pure: every finding of the lint on the loaded model `loaded` ({:model
  :text :file :catalogue}), the feature files `features` (paths relative to
  the project) under `spec-dir`, and the configured `limits`."
  [{m :model text :text file :file catalogue :catalogue} spec-dir features limits]
  (let [f (fn [line rule msg] (command/finding file line rule msg))
        els (model/elements m)
        by-id (into {} (map (juxt :id identity)) els)
        el-line #(model/element-line text %)
        rels (filter map? (:relationships m))
        vs (filter map? (:views m))
        spec-dir (str/replace (str spec-dir) #"/+$" "")
        limit (or (:view-element-limit limits) (:view-element-limit default-limits))
        max-chars (or (:description-max-chars limits) (:description-max-chars default-limits))
        owned (set (for [e els :when (#{:component :kernel} (:kind e)) :when (string? (get-in e [:el :spec]))]
                     (model/feature-file spec-dir (get-in e [:el :spec]))))]
    (vec
     (concat
      (for [v vs :when (blank? (:description v))]
        (f (model/view-line text v) "c4-scope"
           (str "the view " (pr-str (:key v)) " has no description: a view says its scope and its audience")))
      (for [e els :when (blank? (get-in e [:el :description]))]
        (f (el-line e) "c4-element-described" (str (model/describe e) " has no description: every element says what it is")))
      (for [r rels :when (blank? (:description r))]
        (f (model/rel-line text r) "c4-arrow-labelled"
           (str (model/rel-name r) " has no description: every arrow says what it does, from "
                (pr-str (:from r)) " to " (pr-str (:to r)))))
      (when (empty? (filter #(= :person (:kind %)) els))
        (for [v vs :when (= :context (:level v))]
          (f (model/view-line text v) "c4-context-actors"
             (str "the context view " (pr-str (:key v)) " includes no person: a system context shows who uses the system"))))
      (for [e els :when (and (= :container (:kind e)) (not (#{:runs :stores} (get-in e [:el :kind]))))]
        (f (el-line e) "c4-container-runs"
           (str (model/describe e) " is of kind " (pr-str (get-in e [:el :kind]))
                ": a container runs (:runs) or stores data (:stores)")))
      (for [r rels :when (:event r)
            :let [decl (get catalogue (:event r))]
            :when (or (nil? decl) (and (:published-by decl) (not= (:published-by decl) (:from r))))]
        (f (model/rel-line text r) "c4-events-named"
           (if (nil? decl)
             (str (model/rel-name r) " publishes " (name (:event r)) ", which the event catalogue does not declare")
             (str (model/rel-name r) " publishes " (name (:event r)) ", which the event catalogue says "
                  (pr-str (:published-by decl)) " publishes"))))
      (for [v vs
            :let [n (count (:elements (views/view-contents m v)))]
            :when (> n limit)]
        (command/warning file (model/view-line text v) "c4-view-size"
                         (str "the view " (pr-str (:key v)) " shows " n " elements, more than the configured limit of " limit
                              ": split it, or draw less")))
      (for [e els :when (#{:person :system :external} (:kind e))
            :let [d (str (get-in e [:el :description]))]
            :when (> (count d) max-chars)]
        (f (el-line e) "c4-description-length"
           (str (model/describe e) " has a description of " (count d) " characters, more than the configured limit of "
                max-chars ": the render draws its name over a longer one")))
      (let [with-view (set (for [v vs :when (= :component (:level v))] (:context v)))]
        (for [e els :when (and (= :context (:kind e)) (seq (get-in e [:el :components])) (not (with-view (:id e))))]
          (f (el-line e) "a-component-view"
             (str (model/describe e) " has components and no component view: each bounded context is drawn"
                  " as a boundary around its components"))))
      (for [e els :when (#{:component :kernel} (:kind e))
            :let [sp (get-in e [:el :spec])
                  ff (when (string? sp) (model/feature-file spec-dir sp))]
            :when (or (nil? ff) (not (contains? (set features) ff)))]
        (f (el-line e) "a-scenario-owner"
           (if ff
             (str (model/describe e) " owns no feature file: there is no " ff)
             (str (model/describe e) " names no spec path: every component owns its feature file"))))
      (for [ff (sort features) :when (not (owned ff))]
        (command/finding ff "a-scenario-owner"
                         (str "no component of the model names this feature file: every scenario belongs to a component")))
      (for [r rels
            :let [a (by-id (:from r)) b (by-id (:to r))]
            :when (and (= :component (:kind a)) (= :component (:kind b))
                       (not= (:context a) (:context b)) (nil? (:event r)))]
        (f (model/rel-line text r) "a-contract-only"
           (str (model/rel-name r) " joins two bounded contexts and names no event: contexts relate only through"
                " the events of the catalogue")))))))

(defn subscriptions
  "Keeps the feature files SpecParsed read scenarios from."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) update ::files (fnil into #{}) (keep :file (:scenarios payload))) nil)]])

(defn lint
  "The lint stage of `rdm-spec check`."
  [req]
  (let [{m :model :as loaded} (model/load! req)]
    (if-not m
      {:result {:lint {:for-a-person for-a-person}} :findings (:findings loaded)}
      (let [spec-dir (command/config-value req :spec-dir)
            d (command/path req spec-dir)
            on-disk (if (fs/directory? d)
                      (for [f (fs/glob d "**.feature") :when (fs/regular-file? f)] (command/rel req f))
                      [])
            features (set (concat on-disk (some-> (:state req) deref ::files)))
            catalogue (:catalogue (:catalogue loaded))
            fs* (findings (assoc loaded :catalogue (if (map? catalogue) catalogue {}))
                          spec-dir features (merge default-limits (command/config-value req :c4)))]
        {:result {:lint {:for-a-person for-a-person}}
         :findings (vec (concat (:findings loaded) fs*))}))))
