(ns rdm-spec.architecture.lint
  "The Lint component of the architecture context
  (dhf/spec/architecture/lint.feature): holds the model to the C4 skill's
  quality checklist, where a machine can judge it, and to the
  bounded-context rules. A stage of `rdm-spec check`; it reads the model
  once per run with the Model component (whose refusals it reports when it
  is the first to ask; a refused model is not linted).

  Rules, each a stable name on the error stream, at the model's line:
    c4-scope               a view with no description
    c4-element-described   an element (system, person, external system,
                           container, bounded context, component, kernel
                           component) with no description
    c4-arrow-labelled      a relationship with no description, naming both ends
    c4-context-actors      a context view while the model has no person
    c4-container-runs      a container whose :kind is neither :runs nor :stores
    c4-events-named        a relationship publishing an event the event
                           catalogue does not declare, or declares from
                           another publisher
    c4-view-size           (a warning) a view drawing more elements than the
                           configured :c4 :view-element-limit (default 20)
    c4-description-length  a person's or a system's description longer than
                           the configured :c4 :description-max-chars (default 45)
    a-component-view       a bounded context with components and no component view
    a-scenario-owner       a component (or kernel component) with no feature
                           file at its spec path, or a feature file under the
                           spec directory no component's spec path names
    a-contract-only        a relationship between components of two bounded
                           contexts naming no event, unless the configuration's
                           :c4 :contexts-relate-by holds :calls (default [:events])

  Its result lists the checklist items no machine can judge, for a person:
  {:lint {:for-a-person [question]}}."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as command]))

(def for-a-person
  "The C4 checklist's items a person answers, after each check."
  ["Does each view serve its audience, at the level it is drawn for?"
   "Is the true coupling visible: does every relationship that matters appear?"
   "Was each render looked at, its labels readable and nothing drawn over another?"])

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn findings
  "Pure: every lint finding of `in` {:model :text :file :catalogue (a map or
  nil) :spec-dir :features [rel] :c4 {..}}."
  [{:keys [model text file catalogue spec-dir features c4]}]
  (let [m model
        f (fn [line rule msg] (command/finding file line rule msg))
        els (model/elements m)
        by-id (into {} (map (juxt :id identity)) els)
        limit (or (:view-element-limit c4) 20)
        max-chars (or (:description-max-chars c4) 45)
        relate-by (set (or (:contexts-relate-by c4) [:events]))
        rels (filter map? (:relationships m))
        views* (filter map? (:views m))
        spec-dir (strip-slash spec-dir)
        owned (into {} (for [e els :when (and (#{:component :kernel} (:kind e)) (string? (get-in e [:el :spec])))]
                         [(model/feature-file spec-dir (get-in e [:el :spec])) e]))
        feats (set features)
        blank? (fn [s] (or (not (string? s)) (str/blank? s)))]
    (vec
     (concat
      (for [v views* :when (blank? (:description v))]
        (f (model/view-line text v) "c4-scope"
           (str "the view " (pr-str (:key v)) " has no description: say whom it is for and what it shows")))
      (for [e els :when (blank? (get-in e [:el :description]))]
        (f (model/element-line text e) "c4-element-described" (str (model/describe e) " has no description")))
      (for [r rels :when (blank? (:description r))]
        (f (model/rel-line text r) "c4-arrow-labelled"
           (str (model/rel-name r) " has no description: label the arrow from " (pr-str (:from r))
                " to " (pr-str (:to r)))))
      (when (empty? (filter #(= :person (:kind %)) els))
        (for [v views* :when (= :context (:level v))]
          (f (model/view-line text v) "c4-context-actors"
             (str "the context view " (pr-str (:key v)) " includes no person: the model has none"))))
      (for [e els :when (and (= :container (:kind e)) (not (#{:runs :stores} (get-in e [:el :kind]))))]
        (f (model/element-line text e) "c4-container-runs"
           (str (model/describe e) " is of the kind " (pr-str (get-in e [:el :kind]))
                ": a container runs (:runs) or stores data (:stores)")))
      (for [r rels :when (:event r)
            :let [decl (get catalogue (:event r))]
            :when (or (nil? decl) (not= (:published-by decl) (:from r)))]
        (f (model/rel-line text r) "c4-events-named"
           (if decl
             (str (model/rel-name r) " publishes " (name (:event r)) ", which the event catalogue declares as published by "
                  (pr-str (:published-by decl)))
             (str (model/rel-name r) " publishes " (name (:event r)) ", which the event catalogue does not declare"))))
      (for [v views*
            :let [n (try (count (:elements (views/view-contents m v))) (catch Exception _ 0))]
            :when (> n limit)]
        (command/warning file (model/view-line text v) "c4-view-size"
                         (str "the view " (pr-str (:key v)) " draws " n " elements, over the configured limit of " limit)))
      (for [e els :when (#{:system :person :external} (:kind e))
            :let [d (get-in e [:el :description])]
            :when (and (string? d) (> (count d) max-chars))]
        (f (model/element-line text e) "c4-description-length"
           (str (model/describe e) " has a description of " (count d)
                " characters, over the configured limit of " max-chars)))
      (let [viewed (set (keep #(when (= :component (:level %)) (:context %)) views*))]
        (for [e els :when (and (= :context (:kind e)) (seq (get-in e [:el :components])) (not (viewed (:id e))))]
          (f (model/element-line text e) "a-component-view"
             (str (model/describe e) " has components and no component view"))))
      (for [[ff e] (sort-by key owned) :when (not (feats ff))]
        (f (model/element-line text e) "a-scenario-owner"
           (str (model/describe e) " owns no feature file: there is no " ff)))
      (for [ff (sort feats) :when (not (contains? owned ff))]
        (command/finding ff "a-scenario-owner"
                         (str "the feature file " ff " belongs to no component: no spec path of the model names it")))
      (when-not (relate-by :calls)
        (for [r rels
              :let [a (get by-id (:from r)) b (get by-id (:to r))]
              :when (and (= :component (:kind a)) (= :component (:kind b))
                         (not= (:context a) (:context b)) (nil? (:event r)))]
          (f (model/rel-line text r) "a-contract-only"
             (str (model/rel-name r) " joins the bounded contexts " (pr-str (:context a)) " and " (pr-str (:context b))
                  " naming no event: contexts relate only through events"))))))))

(defn- feature-files [req]
  (let [sd (strip-slash (command/config-value req :spec-dir))
        d (command/path req sd)]
    (if (fs/directory? d)
      (vec (sort (for [f (fs/glob d "**.feature") :when (fs/regular-file? f)] (command/rel req f))))
      [])))

(defn lint
  "The lint stage of `rdm-spec check`."
  [req]
  (let [{:keys [model text file catalogue] refusals :findings} (model/load! req)]
    (if-not model
      {:result {:lint {:for-a-person for-a-person}} :findings refusals}
      {:result {:lint {:for-a-person for-a-person}}
       :findings (vec (concat refusals
                              (findings {:model model :text text :file file
                                         :catalogue (or (:catalogue catalogue) {})
                                         :spec-dir (command/config-value req :spec-dir)
                                         :features (feature-files req)
                                         :c4 (command/config-value req :c4)})))})))
