(ns rdm-spec.architecture.lint
  "Holds the model to the C4 skill's checklist and to the bounded-context
  rules (dhf/spec/04-model-the-architecture/lint.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.kernel.project :as project]))

(def for-a-person
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defn findings
  "The C4 checklist and the bounded-context rules, over a model read whole."
  [m catalogue c4 file text]
  (let [els (model/elements m)
        by-id (into {} (map (juxt :id identity) els))
        line-of (fn [x] (or (project/line-of text (pr-str x)) 1))
        f (fn [line rule msg] (project/finding file line rule msg))
        ctx-of (fn [id] (let [el (by-id id)] (when (= :component (:el el)) (:context el))))
        relate-by (set (:contexts-relate-by c4 [:events]))
        limit (:view-element-limit c4 20)
        max-chars (:description-max-chars c4 45)]
    (vec
     (concat
      (for [v (:views m) :when (str/blank? (str (:description v)))]
        (f (line-of (:key v)) "c4-scope" (str "the view " (:key v) " has no description: a view says what it is for")))
      (for [el els :when (str/blank? (str (:description el)))]
        (f (line-of (:id el)) "c4-element-described" (str (model/describe el) " has no description")))
      (for [r (:relationships m) :when (str/blank? (str (:description r)))]
        (f (line-of (:to r)) "c4-arrow-labelled" (str "the relationship " (pr-str (:from r)) " -> " (pr-str (:to r)) " has no label")))
      (for [v (:views m) :when (and (= :context (:level v)) (empty? (:people m)))]
        (f (line-of (:key v)) "c4-context-actors" (str "the context view " (:key v) " includes no person")))
      (for [c (:containers m) :when (not (#{:runs :stores} (:kind c)))]
        (f (line-of (:id c)) "c4-container-runs" (str "the container " (pr-str (:id c)) " is of kind " (pr-str (:kind c)) ": a container runs or stores")))
      (for [r (:relationships m) :when (and (:event r) (not (contains? catalogue (:event r))))]
        (f (line-of (:event r)) "c4-events-named" (str "the relationship " (pr-str (:from r)) " -> " (pr-str (:to r)) " publishes the event "
                                                       (name (:event r)) ", which the event catalogue does not declare")))
      (for [v (:views m)
            :let [n (count (:elements (model/view-elements m v)))]
            :when (> n limit)]
        (project/warning file (line-of (:key v)) "c4-view-size" (str "the view " (:key v) " shows " n " elements, more than the limit of " limit)))
      (for [el els
            :when (and (#{:person :external :system} (:el el)) (> (count (str (:description el))) max-chars))]
        (f (line-of (:id el)) "c4-description-length"
           (str (model/describe el) "'s description is " (count (:description el)) " characters, longer than the limit of " max-chars)))
      (for [el els
            :when (and (= :context (:el el)) (seq (:components el))
                       (not (some #(and (= :component (:level %)) (= (:id el) (:context %))) (:views m))))]
        (f (line-of (:id el)) "a-component-view" (str "the bounded context " (pr-str (:id el)) " has components and no component view")))
      (for [r (:relationships m)
            :let [a (ctx-of (:from r)) b (ctx-of (:to r))]
            :when (and a b (not= a b) (not= :kernel a) (not= :kernel b) (nil? (:event r)) (not (relate-by :calls)))]
        (f (line-of (:to r)) "a-contract-only" (str "the relationship " (pr-str (:from r)) " -> " (pr-str (:to r))
                                                    " joins two bounded contexts and names no event: contexts relate only through events")))))))

(defn ownership-findings
  "Under the contexts layout: a component that owns no feature file, and a feature file no component owns."
  [decl m feature-files text]
  (let [comps (filter #(= :component (:el %)) (model/elements m))
        owned (set (map #(str (:dir decl) "/" (:spec %) ".feature") (filter :spec comps)))]
    (vec (concat
          (for [c comps
                :let [f (when (:spec c) (str (:dir decl) "/" (:spec c) ".feature"))]
                :when (not (and f (some #{f} feature-files)))]
            (project/finding (:model decl) (or (project/line-of text (pr-str (:id c))) 1) "a-scenario-owner"
                             (str "the component " (pr-str (:id c)) " owns no feature file"
                                  (if f (str ": " f " does not exist") ": it has no spec path"))))
          (for [f feature-files :when (not (owned f))]
            (project/finding f "a-scenario-owner" "the feature file belongs to no component of the model"))))))

(defn- read-catalogue [root file]
  (let [p (fs/path root file)]
    (if-not (fs/exists? p)
      [{} nil]
      (try (let [c (edn/read-string (slurp (str p)))]
             (if (map? c) [c nil] [{} (project/finding file 1 "events-unreadable" "the event catalogue is not a map")]))
           (catch Exception e [{} (project/finding file 1 "events-unreadable" (ex-message e))])))))

(defn lint
  "check's stage: reads each spec's model and holds it to the lint."
  [run]
  (let [root (:root run) cfg (:cfg run)
        decls ((requiring-resolve 'rdm-spec.spec.parser/spec-decls) cfg)
        per (for [d decls]
              (let [{m :model text :text read-findings :findings} (model/read-file root (:model d))
                    [cat cat-finding] (read-catalogue root (:events d))
                    features (filter #(str/ends-with? % ".feature") (project/files-under root (:dir d)))]
                (if-not m
                  {:findings (vec read-findings)}
                  {:model m
                   :findings (vec (concat (model/shape-findings (:model d) text m)
                                          (when cat-finding [cat-finding])
                                          (findings m cat (:c4 cfg) (:model d) text)
                                          (if (= :slices (:layout d))
                                            (model/slice-findings d m features)
                                            (ownership-findings d m features text))))})))
        all (vec (mapcat :findings per))]
    {:findings all
     :models (mapv :model per)
     :result {:lint {:for-a-person for-a-person
                     :findings (count (remove :warning? all))
                     :warnings (count (filter :warning? all))}}}))
