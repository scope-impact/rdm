(ns rdm-spec.architecture.lint
  "The lint (dhf/spec/architecture/lint.feature): the C4 skill's quality
  checklist where a machine can judge it, and the bounded-context rules: a
  component view per context, the model owning every feature file, and
  contexts relating only through events unless the project says otherwise."
  (:require [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]
            [rdm-spec.kernel.command :as cmd]))

(def for-a-person
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defn- f [{:keys [file text]} needle rule msg]
  (cmd/finding file (model/line-of text needle) rule msg))

(defn findings
  "The lint's findings. opts: :features (set of feature files) :spec-dir
  :events (catalogue) :max-chars :relate-by :file :text."
  [m {:keys [features spec-dir events max-chars relate-by] :as opts}]
  (let [els (model/elements m)
        idx (into {} (map (juxt :id identity) els))
        label (fn [x] (str "the " (model/kind-name (:kind x)) " " (:id x)))
        rel-name (fn [r] (str "the relationship " (:from r) " -> " (:to r)))
        comps (model/components m)
        owned (set (for [c comps :when (get-in c [:el :spec])] (str spec-dir "/" (get-in c [:el :spec]) ".feature")))
        component-views (set (keep #(when (= :component (:level %)) (:context %)) (:views m)))]
    (vec
     (concat
      (for [v (:views m) :when (str/blank? (:description v))]
        (f opts (str "\"" (:key v) "\"") "c4-scope" (str "the view " (:key v) " has no description: say what question it answers")))
      (for [x els :when (str/blank? (get-in x [:el :description]))]
        (f opts (str (:id x)) "c4-element-described" (str (label x) " has no description")))
      (for [r (:relationships m) :when (str/blank? (:description r))]
        (f opts (str (:from r)) "c4-arrow-labelled" (str (rel-name r) " is not labelled: say what passes from " (:from r) " to " (:to r))))
      (for [v (:views m) :when (and (= :context (:level v)) (empty? (:people m)))]
        (f opts (str "\"" (:key v) "\"") "c4-context-actors" (str "the context view " (:key v) " includes no person")))
      (for [c (:containers m) :when (not (#{:runs :stores} (:kind c)))]
        (f opts (str (:id c)) "c4-container-runs"
           (str "the container " (:id c) " is of the kind " (pr-str (:kind c)) ": a container either runs (:runs) or stores (:stores)")))
      (for [r (:relationships m) :when (:event r)
            :let [decl (get events (:event r))]
            :when (or (nil? decl) (not= (:from r) (:published-by decl)))]
        (f opts (str (:event r)) "c4-events-named"
           (if decl
             (str (rel-name r) " publishes the event " (name (:event r)) ", whose publisher is " (:published-by decl) ", not " (:from r))
             (str (rel-name r) " publishes the event " (name (:event r)) ", which the event catalogue does not declare"))))
      (for [x els :when (#{:person :system :external} (:kind x))
            :let [d (str (get-in x [:el :description]))]
            :when (> (count d) max-chars)]
        (f opts (str (:id x)) "c4-description-length"
           (str (label x) " has a description of " (count d) " characters, above the limit of " max-chars)))
      (for [cn (:containers m) cx (:contexts cn)
            :when (and (seq (:components cx)) (not (component-views (:id cx))))]
        (f opts (str (:id cx)) "a-component-view" (str "the bounded context " (:id cx) " has components and no component view")))
      (for [c comps :let [p (str spec-dir "/" (get-in c [:el :spec]) ".feature")]
            :when (not (contains? features p))]
        (f opts (str (:id c)) "a-scenario-owner" (str (label c) " owns no feature file: " p " does not exist")))
      (for [p (sort features) :when (not (owned p))]
        (cmd/finding p nil "a-scenario-owner" (str "the feature file " p " belongs to no component of the model")))
      (when-not (some #{:calls} relate-by)
        (for [r (:relationships m)
              :let [a (idx (:from r)) b (idx (:to r))]
              :when (and a b (= :component (:kind a)) (= :component (:kind b))
                         (not= (:context a) (:context b)) (nil? (:event r)))]
          (f opts (str (:from r)) "a-contract-only"
             (str (rel-name r) " crosses from the bounded context " (:context a) " to " (:context b)
                  " naming no event: contexts relate only through events"))))))))

(defn warnings [m {:keys [limit] :as opts}]
  (vec
   (for [v (:views m)
         :let [n (views/element-count m v)]
         :when (> n limit)]
     (f opts (str "\"" (:key v) "\"") "c4-view-size"
        (str "the view " (:key v) " shows " n " elements, above the configured limit of " limit)))))

(defn stage [{:keys [root config revision]}]
  (let [{:keys [model file text]} (model/read-model root revision config)]
    (if-not model
      {:result {:lint {:for-a-person for-a-person}}}
      (let [spec-dir (:spec-dir config "dhf/spec")
            opts {:file file :text text :spec-dir spec-dir
                  :features (set (filter #(str/ends-with? % ".feature") (cmd/files-at root revision spec-dir)))
                  :events (:events (model/read-events root revision config))
                  :limit (get-in config [:c4 :view-element-limit] 20)
                  :max-chars (get-in config [:c4 :description-max-chars] 45)
                  :relate-by (get-in config [:c4 :contexts-relate-by] [:events])}]
        {:findings (findings model opts)
         :warnings (warnings model opts)
         :result {:lint {:for-a-person for-a-person}}}))))
