(ns rdm-spec.architecture.lint
  "Lint (dhf/spec/architecture/lint.feature): the model's stage of
  `rdm-spec check`. Reads the model whole (rdm-spec.architecture.model; a
  refused model is reported and nothing else is linted), then holds it to

  the C4 skill's quality checklist, where a machine can judge it:
    c4-scope               every view has a description
    c4-element-described   every element has a description
    c4-arrow-labelled      every relationship and dynamic step has a description
    c4-context-actors      every context view shows at least one person
    c4-container-runs      every container :runs or :stores
    c4-events-named        a relationship's event is in the catalogue, from its publisher
    c4-view-size           a view draws no more than :c4 :view-element-limit (a warning)
    c4-description-length  a person's or system's description fits :c4 :description-max-chars

  and the bounded-context rules:
    a-component-view       a context with components has a component view
    a-scenario-owner       every component owns its feature file, every feature file a component
    a-contract-only        components of different contexts relate only through an event

  The checklist items no machine can judge are listed in the result, under
  :c4-for-a-person, for a person to answer."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as command]))

(def for-a-person
  "The C4 checklist items only a person can judge."
  ["Does each view serve its audience: is it the view that reader needs, at the right level?"
   "Is the true coupling visible: does each view show the dependencies that really exist, and none that do not?"
   "Was each render looked at by a person: are the labels legible and nothing drawn over anything else?"])

(defn subscriptions
  "Keeps the spec as the parser read it (SpecParsed): the feature files its
  scenarios came from count as feature files of the spec."
  [req]
  [[:SpecParsed (fn [payload] (swap! (:state req) assoc ::spec-parsed payload) nil)]])

;; ---------------------------------------------------------------------------
;; The C4 checklist

(defn- f [ctx line rule msg] (command/finding (:file ctx) line rule msg))

(defn- c4-scope [ctx m]
  (for [v (:views m) :when (model/blank? (:description v))]
    (f ctx (model/line-of v) "c4-scope"
       (str (model/describe-view v) " has no description: say what it shows and for whom"))))

(defn- c4-element-described [ctx m]
  (for [e (model/elements m) :when (model/blank? (get-in e [:el :description]))]
    (f ctx (model/line-of (:el e)) "c4-element-described"
       (str (model/describe-element e) " has no description"))))

(defn- c4-arrow-labelled [ctx m]
  (concat
   (for [r (:relationships m) :when (model/blank? (:description r))]
     (f ctx (model/line-of r) "c4-arrow-labelled"
        (str (model/describe-relationship r) " has no description: label the arrow from "
             (model/kw (:from r)) " to " (model/kw (:to r)))))
   (for [v (:views m) :when (= :dynamic (:level v))
         [i [from to label]] (map-indexed vector (:steps v))
         :when (model/blank? label)]
     (f ctx (model/line-of v) "c4-arrow-labelled"
        (str (model/describe-view v) " step " (inc i) " from " (model/kw from) " to " (model/kw to)
             " has no description")))))

(defn- c4-context-actors [ctx m]
  (for [v (:views m) :when (= :context (:level v))
        :let [{:keys [inside outside]} (views/view-content m v)]
        :when (not-any? #(= :person (:type %)) (concat inside outside))]
    (f ctx (model/line-of v) "c4-context-actors"
       (str (model/describe-view v) " is a context view that includes no person: who uses the system?"))))

(defn- c4-container-runs [ctx m]
  (for [c (:containers m) :when (not (#{:runs :stores} (:kind c)))]
    (f ctx (model/line-of c) "c4-container-runs"
       (str "container " (model/kw (:id c)) " neither runs nor stores (its :kind is "
            (if (contains? c :kind) (model/kw (:kind c)) "missing")
            "): a container is :runs or :stores; a library is a component"))))

(defn- c4-events-named [ctx m catalogue events-file]
  (for [r (:relationships m) :when (contains? r :event)
        :let [ev (:event r)
              decl (get catalogue ev)]
        :when (or (nil? decl) (not= (:from r) (:published-by decl)))]
    (f ctx (model/line-of r) "c4-events-named"
       (if (nil? decl)
         (str (model/describe-relationship r) " publishes the event " (model/kw ev)
              ", which the event catalogue " events-file " does not declare")
         (str (model/describe-relationship r) " publishes the event " (model/kw ev)
              ", which " events-file " says is published by " (model/kw (:published-by decl))
              ", not " (model/kw (:from r)))))))

(defn- c4-view-size [ctx m limit]
  (when limit
    (for [v (:views m)
          :let [n (views/element-count m v)]
          :when (> n limit)]
      (command/warning (:file ctx) (model/line-of v) "c4-view-size"
                       (str (model/describe-view v) " shows " n " elements, more than the configured limit of "
                            limit ": split it")))))

(defn- c4-description-length [ctx m limit]
  (when limit
    (for [e (model/elements m)
          :when (#{:person :system :external} (:type e))
          :let [d (get-in e [:el :description])]
          :when (and (string? d) (> (count d) limit))]
      (f ctx (model/line-of (:el e)) "c4-description-length"
         (str (model/describe-element e) " has a description of " (count d)
              " characters, longer than the configured limit of " limit)))))

;; ---------------------------------------------------------------------------
;; The bounded-context rules

(defn- a-component-view [ctx m]
  (let [viewed (set (keep #(when (= :component (:level %)) (:context %)) (:views m)))]
    (for [c (model/contexts m)
          :when (seq (get-in c [:el :components]))
          :when (not (viewed (:id c)))]
      (f ctx (model/line-of (:el c)) "a-component-view"
         (str "bounded context " (model/kw (:id c)) " has components and no component view")))))

(defn- spec-feature-files
  "Every feature file of the spec, relative to the root: found by looking, and
  every file the parser read scenarios from."
  [req spec-dir]
  (let [dir (command/path req spec-dir)
        found (when (fs/directory? dir) (map #(command/rel req %) (fs/glob dir "**.feature")))
        parsed (for [sc (:scenarios (get @(:state req) ::spec-parsed))
                     :let [file (:file sc)]
                     :when (string? file)
                     :when (str/ends-with? file ".feature")
                     :when (fs/exists? (command/path req file))]
                 (command/rel req (command/path req file)))]
    (set (concat found parsed))))

(defn- a-scenario-owner [ctx req m]
  (let [spec-dir (str/replace (command/config-value req :spec-dir) #"/+$" "")
        owners (model/components m)
        path-of (fn [spec] (str spec-dir "/" spec ".feature"))
        owned (set (keep #(when-let [s (get-in % [:el :spec])] (path-of s)) owners))
        files (spec-feature-files req spec-dir)]
    (concat
     (for [c owners
           :let [s (get-in c [:el :spec])]
           :when (or (model/blank? s) (not (fs/exists? (command/path req (path-of s)))))]
       (f ctx (model/line-of (:el c)) "a-scenario-owner"
          (if (model/blank? s)
            (str (model/describe-element c) " owns no feature file: it has no :spec path")
            (str (model/describe-element c) " owns no feature file: its :spec \"" s "\" names "
                 (path-of s) ", which does not exist"))))
     (for [file (sort files) :when (not (owned file))]
       (command/finding file nil "a-scenario-owner"
                        (str "the feature file belongs to no component: no component of the model "
                             (:file ctx) " has the :spec \""
                             (str/replace (subs file (min (count file) (inc (count spec-dir)))) #"\.feature$" "")
                             "\""))))))

(defn- a-contract-only [ctx m]
  (let [ctx-of (into {} (for [e (model/elements m) :when (= :component (:type e))] [(:id e) (:context e)]))]
    (for [r (:relationships m)
          :let [a (ctx-of (:from r)) b (ctx-of (:to r))]
          :when (and a b (not= a b) (not (contains? r :event)))]
      (f ctx (model/line-of r) "a-contract-only"
         (str (model/describe-relationship r) " joins the bounded contexts " (model/kw a) " and " (model/kw b)
              " and names no event: contexts relate only through an event of the catalogue")))))

;; ---------------------------------------------------------------------------
;; The stage

(defn lint
  "Every lint finding for a model read whole."
  [req {:keys [model catalogue file events-file]}]
  (let [ctx {:file file}
        c4 (get-in req [:config :c4])]
    (vec
     (concat
      (c4-scope ctx model)
      (c4-element-described ctx model)
      (c4-arrow-labelled ctx model)
      (c4-context-actors ctx model)
      (c4-container-runs ctx model)
      (c4-events-named ctx model catalogue events-file)
      (c4-view-size ctx model (:view-element-limit c4))
      (c4-description-length ctx model (:description-max-chars c4))
      (a-component-view ctx model)
      (a-scenario-owner ctx req model)
      (a-contract-only ctx model)))))

(defn check
  "The lint's stage of `rdm-spec check`: the model read whole, then linted."
  [req]
  (let [{:keys [model findings] :as read} (model/read-model req)]
    (if-not model
      {:result {:model-read false} :findings findings}
      (let [fs* (lint req read)]
        {:result {:model-read true
                  :lint-passed (not-any? #(= :error (:severity %)) fs*)
                  :c4-for-a-person {:views (mapv :key (:views model)) :questions for-a-person}}
         :findings fs*}))))
