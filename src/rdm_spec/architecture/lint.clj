(ns rdm-spec.architecture.lint
  "Lint (dhf/spec/architecture/lint.feature): holds the C4 model to the C4
  skill's quality checklist, where a machine can judge it, and to the
  bounded-context rules of ADR 0002. A stage of `rdm-spec check`.

  Rules (each finding is FILE:LINE: RULE: what, the file being the model's):
    c4-scope               a view with no description
    c4-element-described   an element (the system, a person, an external system,
                           a container, a bounded context, a component) with no description
    c4-arrow-labelled      a relationship with no description; names both ends
    c4-context-actors      a context view that includes no person
    c4-container-runs      a container whose :kind is not :runs or :stores
    c4-events-named        a relationship publishing an event the catalogue does not
                           declare, or from another publisher than the declared one
    c4-view-size           (a warning) a view drawing more elements than the
                           configured [:c4 :view-element-limit]
    c4-description-length  a person's or system's description longer than the
                           configured [:c4 :description-max-chars]
    a-component-view       a bounded context with components and no component view
    a-scenario-owner       a component with no feature file at its spec path, or a
                           feature file in the spec that no component names
    a-contract-only        a relationship between components of two bounded contexts
                           that names no event

  The model is read by the Model component; when it cannot be read whole,
  this stage reports why and lints nothing. The checklist items no machine
  can judge are listed in the result, for a person to answer.

  Subscribes to SpecParsed: the feature files its scenarios come from are
  feature files of the spec, beside those found under the spec directory."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.kernel.command :as command]))

(def for-a-person
  "The checklist items of the C4 skill no machine can judge."
  ["Does each view serve its audience: is it the view that audience needs, at the level it needs?"
   "Is the true coupling visible: does every real dependency appear as a relationship, and none that is not real?"
   "Was each render looked at by a person: are the labels readable and nothing drawn over anything else?"])

(defn subscriptions
  "Lint's subscriptions: SpecParsed, kept for the check stage."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec-parsed payload) nil)]])

(defn- blank? [s] (or (not (string? s)) (str/blank? s)))

(defn- limit [req k default]
  (let [v (get-in req [:config :c4 k])] (if (number? v) v default)))

(defn- spec-feature-files
  "Every feature file of the spec, relative to the root: those under the
  configured spec directory, and those SpecParsed names."
  [req spec-dir]
  (let [d (command/path req spec-dir)
        found (when (fs/directory? d)
                (for [f (fs/glob d "**.feature")] (command/rel req f)))
        parsed (some->> (:state req) deref ::spec-parsed :scenarios (keep :file))]
    (->> (concat found parsed) (map str) distinct sort vec)))

(defn lint-model
  "The lint's findings on the well-shaped model `m`, read from `file`.
  `opts`: :catalogue, :view-limit, :description-max, :spec-dir, :feature-files
  (every feature file of the spec), :exists? (fn of a path relative to the root)."
  [file m {:keys [catalogue view-limit description-max spec-dir feature-files exists?]}]
  (let [idx (model/element-index m)
        els (map #(get idx (:id %)) (model/elements m))
        at (fn [x] (model/line-of x))
        f (fn [x rule msg] (command/finding file (at x) rule msg))
        w (fn [x rule msg] (command/warning file (at x) rule msg))
        views (:views m)
        owners (model/owners m spec-dir)
        component-of (fn [id] (let [e (get idx id)] (when (= :component (:kind e)) e)))]
    (vec
     (concat
      ;; c4-scope
      (for [v views :when (blank? (:description v))]
        (f v "c4-scope" (str "the view " (pr-str (:key v)) " has no description: say what it shows and for whom")))
      ;; c4-element-described
      (for [e els :when (blank? (:description e))]
        (f (:entry e) "c4-element-described"
           (str "the " (model/describe (:kind e) (:entry e)) " has no description")))
      ;; c4-arrow-labelled
      (for [r (:relationships m) :when (blank? (:description r))]
        (f r "c4-arrow-labelled"
           (str "the relationship from " (pr-str (:from r)) " to " (pr-str (:to r)) " has no description: label the arrow")))
      ;; c4-context-actors
      (for [v views
            :when (= :context (:level v))
            :let [c (model/view-content m v)]
            :when (not-any? #(= :person (:kind %)) (:elements c))]
        (f v "c4-context-actors" (str "the context view " (pr-str (:key v)) " includes no person: who uses the system?")))
      ;; c4-container-runs
      (for [c (:containers m) :when (not (#{:runs :stores} (:kind c)))]
        (f c "c4-container-runs"
           (str "the container " (pr-str (:id c)) " has the kind " (pr-str (:kind c))
                ": a container runs (:runs) or stores data (:stores); a library is not a container")))
      ;; c4-events-named
      (for [r (:relationships m)
            :when (contains? r :event)
            :let [ev (:event r)
                  decl (get catalogue ev)]
            :when (or (nil? decl)
                      (and (contains? decl :published-by) (not= (:published-by decl) (:from r))))]
        (f r "c4-events-named"
           (if (nil? decl)
             (str "the " (model/describe-rel r) " publishes the event " (pr-str ev)
                  ", which the event catalogue does not declare")
             (str "the " (model/describe-rel r) " publishes the event " (pr-str ev)
                  ", which the event catalogue says " (pr-str (:published-by decl)) " publishes"))))
      ;; c4-view-size
      (for [v views
            :let [n (count (:elements (model/view-content m v)))]
            :when (> n view-limit)]
        (w v "c4-view-size" (str "the view " (pr-str (:key v)) " shows " n " elements, more than the configured "
                                 view-limit ": split it")))
      ;; c4-description-length
      (for [e els
            :when (#{:person :system :external} (:kind e))
            :let [d (:description e)]
            :when (and (string? d) (> (count d) description-max))]
        (f (:entry e) "c4-description-length"
           (str "the " (model/describe (:kind e) (:entry e)) "'s description is " (count d)
                " characters, more than the configured " description-max)))
      ;; a-component-view
      (for [e els
            :when (and (= :context (:kind e)) (seq (get-in e [:entry :components])))
            :when (not-any? #(and (= :component (:level %)) (= (:id e) (:context %))) views)]
        (f (:entry e) "a-component-view"
           (str "the bounded context " (pr-str (:id e)) " has components and no component view")))
      ;; a-scenario-owner: components
      (for [o owners
            :when (or (nil? (:file o)) (not (exists? (:file o))))]
        (command/finding file (:line o) "a-scenario-owner"
                         (if (:file o)
                           (str "the " (model/kind-name (:kind o)) " " (pr-str (:id o)) " owns no feature file: "
                                (:file o) " does not exist")
                           (str "the " (model/kind-name (:kind o)) " " (pr-str (:id o))
                                " names no :spec, so it owns no feature file"))))
      ;; a-scenario-owner: feature files
      (let [owned (set (keep :file owners))]
        (for [ff feature-files :when (not (owned ff))]
          (command/finding ff nil "a-scenario-owner"
                           (str "the feature file " ff " belongs to no component: no component of " file
                                " names it in its :spec"))))
      ;; a-contract-only
      (for [r (:relationships m)
            :let [a (component-of (:from r)) b (component-of (:to r))]
            :when (and a b (not= (:context a) (:context b)) (not (contains? r :event)))]
        (f r "a-contract-only"
           (str "the " (model/describe-rel r) " joins the bounded contexts " (pr-str (:context a)) " and "
                (pr-str (:context b)) " and names no event: contexts relate only through an event")))))))

(defn check
  "The lint stage of `rdm-spec check`: the model's reading, then the lint."
  [req]
  (let [{:keys [file model findings catalogue catalogue-findings]} (model/load! req)
        spec-dir (or (get-in req [:config :spec-dir]) "dhf/spec")
        lint (when model
               (lint-model file model
                           {:catalogue catalogue
                            :view-limit (limit req :view-element-limit 20)
                            :description-max (limit req :description-max-chars 45)
                            :spec-dir spec-dir
                            :feature-files (spec-feature-files req spec-dir)
                            :exists? #(fs/regular-file? (command/path req %))}))
        all (vec (concat findings catalogue-findings lint))
        errors (remove command/warning? all)]
    {:result {:lint {:passed? (empty? errors)
                     :model file
                     :for-a-person for-a-person}}
     :findings all}))
