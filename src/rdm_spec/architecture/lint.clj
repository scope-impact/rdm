(ns rdm-spec.architecture.lint
  "The lint (dhf/spec/architecture/lint.feature): holds the model to the C4
  skill's checklist where a machine can judge it, and to the bounded-context
  rules: a component view per context, every component owning its feature
  file and every feature file owned, and contexts relating only through an
  event unless the project's :c4 :contexts-relate-by lets them call. The
  checklist items no machine can judge are printed for a person."
  (:require [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as cmd]))

(def for-a-person
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defn- blank? [s] (or (not (string? s)) (str/blank? s)))

(defn lint
  "The lint's findings and warnings about a model.
  {:model :file :text :events {name decl} :config :features [path] :spec-dir}"
  [{:keys [model file text events config features spec-dir]}]
  (let [c4 (:c4 config)
        limit (:view-element-limit c4 20)
        max-chars (:description-max-chars c4 45)
        relate-by (set (:contexts-relate-by c4 [:events]))
        at (fn [needle] (model/line-of text needle))
        f (fn [needle rule msg] (cmd/finding file (at needle) rule msg))
        els (model/elements model)
        by-id (into {} (map (juxt :id identity) els))
        label (fn [{:keys [kind id]}] (str "the " (model/kind-name kind) " " id))
        rel-name (fn [r] (str (:from r) " -> " (:to r)))
        comps (model/components model)
        spec-of (fn [c] (str spec-dir "/" (get-in c [:el :spec]) ".feature"))
        owned (set (map spec-of (filter #(get-in % [:el :spec]) comps)))
        findings
        (concat
         ;; c4-scope: every view says what it is for
         (for [v (:views model) :when (blank? (:description v))]
           (f (str "\"" (:key v) "\"") "c4-scope"
              (str "the view " (:key v) " has no description: a view says what it is for and for whom")))
         ;; c4-element-described
         (for [e els :when (blank? (get-in e [:el :description]))]
           (f (str (:id e)) "c4-element-described" (str (label e) " has no description")))
         ;; c4-arrow-labelled
         (for [r (:relationships model) :when (blank? (:description r))]
           (f (str (:from r)) "c4-arrow-labelled"
              (str "the relationship " (rel-name r) " has no description: every arrow says what passes")))
         ;; c4-context-actors
         (for [v (:views model)
               :when (= :context (:level v))
               :when (not-any? #(= :person (:kind %)) (:elements (views/content model v)))]
           (f (str "\"" (:key v) "\"") "c4-context-actors"
              (str "the context view " (:key v) " includes no person: a context view shows who uses the system")))
         ;; c4-container-runs
         (for [c (:containers model) :when (not (#{:runs :stores} (:kind c)))]
           (f (str (:id c)) "c4-container-runs"
              (str "the container " (:id c) " is declared with the kind " (pr-str (:kind c))
                   ": a container runs (:runs) or stores data (:stores)")))
         ;; c4-events-named
         (for [r (:relationships model)
               :let [e (:event r)]
               :when e
               :let [decl (get events e)]
               :when (or (nil? decl) (and (:published-by decl) (not= (:published-by decl) (:from r))))]
           (f (str e) "c4-events-named"
              (if decl
                (str "the relationship " (rel-name r) " publishes " (name e) ", whose declared publisher is "
                     (:published-by decl))
                (str "the relationship " (rel-name r) " publishes " (name e)
                     ", which the event catalogue does not declare"))))
         ;; c4-description-length: people and systems
         (for [e els
               :when (#{:person :system :external} (:kind e))
               :let [d (get-in e [:el :description])]
               :when (and (string? d) (> (count d) max-chars))]
           (f (str (:id e)) "c4-description-length"
              (str (label e) " has a description of " (count d) " characters, over the limit of " max-chars
                   ": the render draws the name over a longer one")))
         ;; a-component-view
         (for [e els
               :when (= :context (:kind e))
               :when (seq (get-in e [:el :components]))
               :when (not-any? #(and (= :component (:level %)) (= (:id e) (:context %))) (:views model))]
           (f (str (:id e)) "a-component-view"
              (str "the bounded context " (:id e) " has components and no component view")))
         ;; a-scenario-owner: every component owns its feature file ...
         (for [c comps
               :let [s (get-in c [:el :spec])]
               :when (or (blank? s) (not (some #{(spec-of c)} features)))]
           (f (str (:id c)) "a-scenario-owner"
              (if (blank? s)
                (str (label c) " names no feature file: every component owns one")
                (str (label c) " owns no feature file: " (spec-of c) " does not exist"))))
         ;; ... and every feature file has an owner
         (for [p features :when (not (owned p))]
           (cmd/finding p nil "a-scenario-owner"
                        (str p " belongs to no component: no component of the model names it")))
         ;; a-contract-only
         (when-not (relate-by :calls)
           (for [r (:relationships model)
                 :let [a (by-id (:from r)) b (by-id (:to r))]
                 :when (and (= :component (:kind a)) (= :component (:kind b))
                            (not= (:context a) (:context b)) (nil? (:event r)))]
             (f (str (:from r)) "a-contract-only"
                (str "the relationship " (rel-name r) " joins two bounded contexts and names no event"
                     ": contexts relate only through an event")))))
        warnings
        (for [v (:views model)
              :let [n (views/element-count model v)]
              :when (> n limit)]
          (cmd/finding file (at (str "\"" (:key v) "\"")) "c4-view-size"
                       (str "the view " (:key v) " shows " n " elements, more than the limit of " limit)))]
    {:findings (vec findings) :warnings (vec warnings)}))

(defn stage
  "The lint's stage: the model as it reads, or nothing when it cannot be read
  (the model's own stage says why)."
  [{:keys [root config revision]}]
  (let [{:keys [model file text findings]} (model/read-model root revision config)
        {:keys [events]} (model/read-events root revision config)
        spec-dir (:spec-dir config "dhf/spec")]
    (if (or (nil? model) (seq findings))
      {:result {:lint {:for-a-person for-a-person}}}
      (merge (lint {:model model :file file :text text :events events :config config :spec-dir spec-dir
                    :features (filter #(str/ends-with? % ".feature") (cmd/files-at root revision spec-dir))})
             {:result {:lint {:for-a-person for-a-person}}}))))
