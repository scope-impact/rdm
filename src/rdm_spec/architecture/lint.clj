(ns rdm-spec.architecture.lint
  "The Lint component of the architecture context
  (dhf/spec/architecture/lint.feature): holds the model to the C4 skill's
  quality checklist where a machine can judge it, and to the bounded-context
  rules. A stage of `rdm-spec check`; a model the Model component refuses is
  not linted (its refusals are relayed once, by whichever stage reads the
  model first).

  Rules, each a stable name on the error stream, at the model's line naming
  the element:
    c4-scope               a view with no description
    c4-element-described   an element with no description
    c4-arrow-labelled      a relationship with no description (names both ends)
    c4-context-actors      a context view that includes no person
    c4-container-runs      a container whose :kind is neither :runs nor :stores
    c4-events-named        a relationship publishing an event the catalogue
                           does not declare, or from another publisher than
                           its declared one
    c4-view-size           (warning) a view showing more elements than
                           :c4 :view-element-limit (default 20)
    c4-description-length  a person's or a system's description longer than
                           :c4 :description-max-chars (default 45)
    a-component-view       a bounded context with components and no component view
    a-scenario-owner       a component with no feature file at its spec path,
                           or a feature file no component's spec path names
    a-contract-only        a relationship between components of two bounded
                           contexts naming no event, unless :c4
                           :contexts-relate-by holds :calls

  Its result: {:lint {:findings n :for-a-person [question]}}, the checklist
  items no machine can judge (spec-0010)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as command]))

(def for-a-person
  "The C4 checklist's items no machine can judge."
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defn- blank? [s] (or (nil? s) (and (string? s) (str/blank? s))))

(defn- q [s] (str "\"" s "\""))

(defn findings
  "Pure: every finding of the lint. `in`: {:model :text :file :catalogue
  {event {:published-by}} :feature-files [rel] :spec-dir :config}."
  [{:keys [model text file catalogue feature-files spec-dir config]}]
  (let [m model
        c4 (merge (command/config-value {} :c4) (:c4 config))
        limit (:view-element-limit c4)
        max-chars (:description-max-chars c4)
        relate-by (set (or (:contexts-relate-by c4) [:events]))
        els (model/elements m)
        by-id (into {} (map (juxt :id identity)) els)
        at (fn [line rule msg] (command/finding file line rule msg))
        rels (filter map? (:relationships m))
        vs (filter map? (:views m))
        spec-dir (str/replace (str spec-dir) #"/+$" "")
        components (filter #(#{:component :kernel} (:kind %)) els)
        owned (set (keep #(get-in % [:el :spec]) components))]
    (vec
     (concat
      ;; the C4 checklist
      (for [v vs :when (blank? (:description v))]
        (at (model/view-line text v) "c4-scope"
            (str "the view " (q (:key v)) " has no description: say what question it answers, for whom")))
      (for [e els :when (blank? (get-in e [:el :description]))]
        (at (model/element-line text e) "c4-element-described" (str (model/describe e) " has no description")))
      (for [r rels :when (blank? (:description r))]
        (at (model/rel-line text r) "c4-arrow-labelled"
            (str "the relationship " (pr-str (:from r)) " -> " (pr-str (:to r)) " has no description: label what passes")))
      (for [v vs :when (= :context (:level v))
            :let [c (views/view-contents m v)]
            :when (not-any? #(= :person (:kind %)) (:elements c))]
        (at (model/view-line text v) "c4-context-actors"
            (str "the context view " (q (:key v)) " includes no person: who uses the system?")))
      (for [e els :when (= :container (:kind e))
            :let [k (get-in e [:el :kind])]
            :when (not (#{:runs :stores} k))]
        (at (model/element-line text e) "c4-container-runs"
            (str (model/describe e) " is of the kind " (pr-str k) ": a container runs (:runs) or stores data (:stores)")))
      (for [r rels :when (:event r)
            :let [decl (get catalogue (:event r))]
            :when (or (nil? decl) (not= (:published-by decl) (:from r)))]
        (at (model/rel-line text r) "c4-events-named"
            (if decl
              (str (model/rel-name r) " publishes " (name (:event r)) ", which the event catalogue declares published by "
                   (pr-str (:published-by decl)))
              (str (model/rel-name r) " publishes " (name (:event r)) ", which the event catalogue does not declare"))))
      (for [v vs
            :let [n (count (:elements (views/view-contents m v)))]
            :when (and limit (> n limit))]
        (command/warning file (model/view-line text v) "c4-view-size"
                         (str "the view " (q (:key v)) " shows " n " elements, more than the configured limit of " limit
                              ": split it")))
      (for [e els :when (#{:person :system :external} (:kind e))
            :let [d (get-in e [:el :description])]
            :when (and (string? d) max-chars (> (count d) max-chars))]
        (at (model/element-line text e) "c4-description-length"
            (str (model/describe e) " has a description of " (count d) " characters, more than the configured "
                 max-chars ": the renderer draws the name over a longer one")))
      ;; the bounded-context rules
      (for [e els :when (and (= :context (:kind e)) (seq (get-in e [:el :components])))
            :when (not-any? #(and (= :component (:level %)) (= (:id e) (:context %))) vs)]
        (at (model/element-line text e) "a-component-view"
            (str (model/describe e) " has components and no component view")))
      (for [e components
            :let [sp (get-in e [:el :spec])
                  f (when sp (str spec-dir "/" sp ".feature"))]
            :when (or (nil? f) (not (some #{f} feature-files)))]
        (at (model/element-line text e) "a-scenario-owner"
            (if f
              (str (model/describe e) " owns no feature file: there is no " f)
              (str (model/describe e) " names no :spec, so it owns no feature file"))))
      (for [f feature-files
            :let [sp (when (str/starts-with? f (str spec-dir "/"))
                       (str/replace (subs f (inc (count spec-dir))) #"\.feature$" ""))]
            :when (not (owned sp))]
        (command/finding f "a-scenario-owner" (str "the feature file " f " belongs to no component of the model")))
      (when-not (:calls relate-by)
        (for [r rels
              :let [a (get by-id (:from r)) b (get by-id (:to r))]
              :when (and (= :component (:kind a)) (= :component (:kind b))
                         (not= (:context a) (:context b)) (not (:event r)))]
          (at (model/rel-line text r) "a-contract-only"
              (str (model/rel-name r) " joins the bounded contexts " (pr-str (:context a)) " and " (pr-str (:context b))
                   " and names no event: contexts relate only through events"))))))))

(defn lint
  "The lint's stage of `rdm-spec check`."
  [req]
  (let [{m :model :keys [text file catalogue] refusals :findings} (model/load! req)
        spec-dir (str/replace (command/config-value req :spec-dir) #"/+$" "")
        d (command/path req spec-dir)
        feature-files (if (fs/directory? d)
                        (vec (sort (for [f (fs/glob d "**.feature") :when (fs/regular-file? f)] (command/rel req f))))
                        [])
        fs* (if m
              (findings {:model m :text text :file file :catalogue (or (:catalogue catalogue) {})
                         :feature-files feature-files :spec-dir spec-dir :config (:config req)})
              [])]
    {:result {:lint {:findings (count (remove command/warning? fs*)) :for-a-person for-a-person}}
     :findings (vec (concat refusals fs*))}))
