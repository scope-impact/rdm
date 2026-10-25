(ns rdm-spec.architecture.lint
  "The Lint component of the architecture context
  (dhf/spec/architecture/lint.feature): holds the model to the C4 skill's
  quality checklist, where a machine can judge it, and to the bounded-context
  rules. A stage of `rdm-spec check`, after the model is read
  (rdm-spec.architecture.model).

  Rules, each a stable name on the error stream:
    c4-scope               a view with no description
    c4-element-described   an element with no description
    c4-arrow-labelled      a relationship with no description
    c4-context-actors      a context view, and no person in the model
    c4-container-runs      a container that neither :runs nor :stores
    c4-events-named        a relationship publishing an event the catalogue does not
                           declare, or one it declares with another publisher
    c4-view-size           (warning) a view drawing more elements than :c4 :view-element-limit
    c4-description-length  a person's or system's description longer than :c4 :description-max-chars
    a-component-view       a bounded context with components and no component view
    a-scenario-owner       a component owning no feature file; a feature file no component owns
    a-contract-only        components of two bounded contexts related with no event

  The checklist items no machine can judge are listed in the result, for a
  person to answer:
  {:lint {:passed? bool :findings n :warnings n :for-a-person [question]}}."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as command]))

(def for-a-person
  "The C4 checklist items only a person can answer."
  ["Does each view serve its audience: can the reader it is drawn for answer their question from it?"
   "Is the true coupling visible: does every dependency that matters appear as a labelled relationship?"
   "Was each render looked at by a person, after the last change to the model?"])

(def default-view-limit 20)
(def default-description-max 45)

(defn- blank? [x] (str/blank? (str (or x ""))))

(defn lint
  "Every finding of the lint on the model `m` (read from `text` at `file`).
  `opts`: :catalogue (nil when unreadable) :catalogue-file :limit
  :description-max :spec-dir :feature-files (paths relative to the root)
  :exists? (a fn of a path relative to the root)."
  [m text file {:keys [catalogue catalogue-file limit description-max spec-dir feature-files exists?]}]
  (let [f (fn [line rule msg] (command/finding file line rule msg))
        w (fn [line rule msg] (command/warning file line rule msg))
        els (model/elements m)
        by-id (into {} (map (juxt :id identity)) els)
        comps (model/components m)
        comp-ctx (into {} (map (juxt :id :context)) comps)
        rels (filter map? (:relationships m))
        views (filter map? (:views m))]
    (vec
     (concat
      ;; c4-scope
      (for [v views :when (blank? (:description v))]
        (f (model/view-line text v) "c4-scope"
           (str "the view " (pr-str (:key v)) " has no description: say what it shows and for whom")))
      ;; c4-element-described
      (for [e els :when (blank? (get-in e [:el :description]))]
        (f (model/element-line text e) "c4-element-described" (str (model/describe e) " has no description")))
      ;; c4-arrow-labelled
      (for [r rels :when (blank? (:description r))]
        (f (model/rel-line text r) "c4-arrow-labelled"
           (str (model/rel-name r) " has no description: label every arrow with what it does")))
      ;; c4-context-actors
      (when (empty? (:people m))
        (for [v views :when (= :context (:level v))]
          (f (model/view-line text v) "c4-context-actors"
             (str "the context view " (pr-str (:key v)) " includes no person: a context view shows who uses the system"))))
      ;; c4-container-runs
      (for [e els :when (= :container (:kind e))
            :when (not (#{:runs :stores} (get-in e [:el :kind])))]
        (f (model/element-line text e) "c4-container-runs"
           (str (model/describe e) " is declared with kind " (pr-str (get-in e [:el :kind]))
                ": a container either runs (:runs) or stores data (:stores)")))
      ;; c4-events-named
      (for [r rels :when (:event r)
            :let [decl (when (map? catalogue) (get catalogue (:event r)))]
            :when (or (nil? decl) (and (:published-by decl) (not= (:published-by decl) (:from r))))]
        (f (model/rel-line text r) "c4-events-named"
           (if (nil? decl)
             (str (model/rel-name r) " publishes the event " (name (:event r)) ", which the event catalogue "
                  catalogue-file " does not declare")
             (str (model/rel-name r) " publishes the event " (name (:event r)) ", which the event catalogue "
                  catalogue-file " says " (pr-str (:published-by decl)) " publishes"))))
      ;; c4-view-size
      (for [v views
            :let [n (count (:elements (views/view-contents m v)))]
            :when (> n limit)]
        (w (model/view-line text v) "c4-view-size"
           (str "the view " (pr-str (:key v)) " shows " n " elements, more than the limit of " limit
                ": split it")))
      ;; c4-description-length
      (for [e els :when (#{:system :person :external} (:kind e))
            :let [d (str (get-in e [:el :description]))]
            :when (> (count d) description-max)]
        (f (model/element-line text e) "c4-description-length"
           (str (model/describe e) " has a description of " (count d) " characters, longer than the limit of "
                description-max ": the renderer draws its name over a longer one")))
      ;; a-component-view
      (for [x (model/contexts m)
            :when (seq (:components x))
            :when (not (some #(and (= :component (:level %)) (= (:id x) (:context %))) views))]
        (f (model/element-line text {:kind :context :id (:id x) :el x}) "a-component-view"
           (str "the bounded context " (pr-str (:id x)) " has components and no component view")))
      ;; a-scenario-owner
      (for [c (concat comps (filter map? (:kernel m)))
            :let [kind (if (:context c) :component :kernel)
                  e {:kind kind :id (:id c) :el c}
                  ff (when (string? (:spec c)) (model/feature-file spec-dir (:spec c)))]
            :when (or (nil? ff) (not (exists? ff)))]
        (f (model/element-line text e) "a-scenario-owner"
           (if ff
             (str (model/describe e) " owns no feature file: its spec path names " ff ", which does not exist")
             (str (model/describe e) " has no spec path (:spec \"<context>/<component>\"), so it owns no feature file"))))
      (let [owned (set (keep #(when (string? (:spec %)) (model/feature-file spec-dir (:spec %)))
                             (concat comps (filter map? (:kernel m)))))]
        (for [ff feature-files :when (not (owned ff))]
          (command/finding ff nil "a-scenario-owner"
                           (str "the feature file " ff " belongs to no component: no component of the model "
                                file " names it as its spec path"))))
      ;; a-contract-only
      (for [r rels
            :let [a (comp-ctx (:from r)) b (comp-ctx (:to r))]
            :when (and a b (not= a b) (nil? (:event r)))]
        (f (model/rel-line text r) "a-contract-only"
           (str (model/rel-name r) " joins the bounded contexts " (name a) " and " (name b)
                " without an event: contexts relate only through the events of the catalogue")))))))

(defn check
  "The lint's stage of `rdm-spec check`: reads the model (its refusals are
  reported here) and lints it."
  [req]
  (let [{:keys [model text file findings catalogue]} (model/load! req)
        c4 (get-in req [:config :c4])
        spec-dir (command/config-value req :spec-dir)
        feature-files (let [d (command/path req spec-dir)]
                        (if (fs/directory? d)
                          (vec (sort (map #(command/rel req %) (fs/glob d "**.feature"))))
                          []))
        lint-findings (if model
                        (lint model text file
                              {:catalogue (:catalogue catalogue) :catalogue-file (:file catalogue)
                               :limit (or (:view-element-limit c4) default-view-limit)
                               :description-max (or (:description-max-chars c4) default-description-max)
                               :spec-dir spec-dir
                               :feature-files feature-files
                               :exists? #(fs/regular-file? (command/path req %))})
                        [])
        all (vec (concat findings lint-findings))]
    {:result {:lint {:passed? (not-any? #(not (command/warning? %)) all)
                     :findings (count (remove command/warning? all))
                     :warnings (count (filter command/warning? all))
                     :for-a-person for-a-person}}
     :findings all}))
