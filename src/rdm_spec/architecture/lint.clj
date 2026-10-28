(ns rdm-spec.architecture.lint
  "Component Lint (architecture context): holds the model to the C4 skill's
  quality checklist where a machine can judge it, and to the bounded-context
  rules: every context drawn, every component owning its feature files,
  contexts relating through events. Under the slices layout, a component's
  feature files are found by its name, in every slice."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.architecture.views :as views]))

(def for-a-person
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defn- blank? [s] (or (nil? s) (and (string? s) (str/blank? s))))

(defn view-elements
  "How many elements a view draws."
  [m v]
  (let [idx (views/index m)]
    (case (:level v)
      :context (+ 1 (count (:people m)) (count (:external m)))
      :container (+ (count (:containers m)) (count (:people m)) (count (:external m)))
      :component (let [ctx (:el (idx (:context v)))
                       inside (set (map :id (:components ctx)))
                       outside (distinct (for [r (:relationships m) :when (map? r)
                                               :when (or (inside (:from r)) (inside (:to r)))
                                               end [(:from r) (:to r)] :when (not (inside end))] end))]
                   (+ (count inside) (count outside)))
      :dynamic (count (distinct (mapcat (fn [[a b]] [a b]) (:steps v))))
      0)))

(defn c4-findings [cfg spec text m catalogue]
  (let [file (:model spec)
        idx (views/index m)
        el-line #(model/element-line text %)
        rel-line #(or (model/line-of text (str ":from " (:from %) " :to " (:to %))) (model/line-of text (str ":from " (:from %))) 1)
        view-line #(or (model/line-of text (pr-str (:key %))) 1)
        limit (get-in cfg [:c4 :view-element-limit] 20)
        max-chars (get-in cfg [:c4 :description-max-chars] 45)
        f project/finding]
    (concat
     (for [v (:views m) :when (and (map? v) (blank? (:description v)))]
       (f file (view-line v) "c4-scope" (str "the view " (:key v) " has no description: a view says what question it answers")))
     (for [{:keys [id kind el]} (model/elements m) :when (and (not= kind :context) (blank? (:description el)))]
       (f file (el-line id) "c4-element-described" (str "the " (model/kind-name kind) " " id " has no description")))
     (for [r (:relationships m) :when (and (map? r) (blank? (:description r)))]
       (f file (rel-line r) "c4-arrow-labelled" (str "the relationship " (:from r) " -> " (:to r) " has no description: every arrow says what passes")))
     (for [v (:views m) :when (and (map? v) (= :context (:level v)) (empty? (:people m)))]
       (f file (view-line v) "c4-context-actors" (str "the context view " (:key v) " includes no person: a context view shows who uses the system")))
     (for [c (:containers m) :when (and (map? c) (not (#{:runs :stores} (:kind c))))]
       (f file (el-line (:id c)) "c4-container-runs" (str "the container " (:id c) " is of kind " (pr-str (:kind c)) ": a container runs or stores data (:runs or :stores)")))
     (for [r (:relationships m) :when (and (map? r) (:event r) (not (contains? catalogue (:event r))))]
       (f file (rel-line r) "c4-events-named" (str "the relationship " (:from r) " -> " (:to r) " publishes " (name (:event r)) ", which the event catalogue " (:events spec) " does not declare")))
     (for [p (concat (when (map? (:system m)) [(:system m)]) (:people m) (:external m))
           :when (and (map? p) (string? (:description p)) (> (count (:description p)) max-chars))]
       (f file (el-line (:id p)) "c4-description-length"
          (str "the " (model/kind-name (:kind (idx (:id p)))) " " (:id p) "'s description is " (count (:description p))
               " characters, more than the limit of " max-chars)))
     (for [v (:views m) :when (and (map? v) (= :component (:level v)) (not (= :context (:kind (idx (:context v))))))]
       (f file (view-line v) "c4-view-context" (str "the component view " (:key v) " names " (:context v) ", which is no bounded context")))
     (for [v (:views m) :when (and (map? v) (= :dynamic (:level v))) [a b] (:steps v) end [a b] :when (not (idx end))]
       (f file (view-line v) "c4-view-element" (str "the dynamic view " (:key v) " names " end ", which is no element of the model"))))))

(defn view-size-warnings [cfg spec text m]
  (let [limit (get-in cfg [:c4 :view-element-limit] 20)]
    (for [v (:views m) :when (map? v)
          :let [n (view-elements m v)] :when (> n limit)]
      (project/warning (:model spec) (or (model/line-of text (pr-str (:key v))) 1) "c4-view-size"
                       (str "the view " (:key v) " shows " n " elements, more than the limit of " limit)))))

(defn context-findings [cfg spec text m]
  (let [idx (views/index m)
        ctx-of (fn [id] (let [e (idx id)] (when (= :component (:kind e)) (:context e))))
        drawn (set (for [v (:views m) :when (and (map? v) (= :component (:level v)))] (:context v)))
        relate-by (set (get-in cfg [:c4 :contexts-relate-by] [:events]))]
    (concat
     (for [c (:containers m) :when (map? c) ctx (:contexts c) :when (and (map? ctx) (seq (:components ctx)) (not (drawn (:id ctx))))]
       (project/finding (:model spec) (model/element-line text (:id ctx)) "a-component-view"
                        (str "the bounded context " (:id ctx) " has components and no component view")))
     (when-not (relate-by :calls)
       (for [r (:relationships m) :when (map? r)
             :let [a (ctx-of (:from r)) b (ctx-of (:to r))]
             :when (and a b (not= a b) (nil? (:event r)))]
         (project/finding (:model spec) (or (model/line-of text (str ":from " (:from r) " :to " (:to r))) 1) "a-contract-only"
                          (str "the relationship " (:from r) " -> " (:to r) " joins the bounded contexts " a " and " b
                               " and names no event: contexts relate only through events")))))))

(defn feature-files [tree spec]
  (let [d (project/abs tree (:dir spec))]
    (if (fs/directory? d) (vec (sort (map #(project/rel tree %) (fs/glob d "**.feature")))) [])))

(defn ownership-findings
  "Every component owns a feature file and every feature file has an owner."
  [tree cfg spec m]
  (let [comps (filter #(keyword? (:id %)) (project/components m))
        files (feature-files tree spec)
        sd (:dir spec)]
    (if (project/slices? cfg)
      (let [by-name (group-by #(project/id-name (:id %)) comps)
            in-slice (filter #(project/slice-of sd %) files)
            named (set (map #(fs/file-name (str/replace % #"\.feature$" "")) in-slice))]
        (concat
         (for [f files :when (not (project/slice-of sd f))]
           (project/finding f "slice-missing" "a feature file outside every slice: under the slices layout each sits in <NN-slice>/"))
         (for [f in-slice :let [n (fs/file-name (str/replace f #"\.feature$" ""))] :when (not (by-name n))]
           (project/finding f "slice-component-unknown" (str "no component of the model is named " n)))
         (for [[n cs] by-name :when (> (count cs) 1)]
           (project/finding (:model spec) "component-name-shared"
                            (str "the components " (str/join " and " (map :id cs)) " share the name " n
                                 ": a slice's feature file is found by its component's name")))
         (for [c comps :when (not (named (project/id-name (:id c))))]
           (project/finding (:model spec) "component-unspecified"
                            (str "the component " (:id c) " has no feature file named " (project/id-name (:id c)) ".feature in any slice")))))
      (concat
       (for [c comps
             :let [f (str sd "/" (:spec c) ".feature")]
             :when (or (not (string? (:spec c))) (not (fs/regular-file? (project/abs tree f))))]
         (project/finding (:model spec) "a-scenario-owner"
                          (str "the component " (:id c) " owns no feature file: there is no " f)))
       (for [f files :when (not (project/owner cfg spec m f))]
         (project/finding f "a-scenario-owner" (str "the feature file " f " belongs to no component of the model")))))))

(defn lint
  "The model's findings and warnings for SPEC at TREE."
  [tree cfg spec]
  (let [{:keys [model text findings]} (model/read-model-once tree spec)
        cat (model/read-catalogue tree spec)]
    (if-not model
      {:findings (vec (concat findings (:findings cat))) :warnings []}
      {:findings (vec (concat findings (:findings cat)
                              (c4-findings cfg spec text model (:events cat))
                              (context-findings cfg spec text model)
                              (ownership-findings tree cfg spec model)))
       :warnings (vec (view-size-warnings cfg spec text model))})))

(defn check-stage [{:keys [cfg]}]
  (let [rs (for [spec (project/specs cfg)] (lint (project/root) cfg spec))
        fs (vec (mapcat :findings rs))]
    {:result {:lint {:findings (count fs) :for-a-person for-a-person}}
     :findings fs
     :warnings (vec (mapcat :warnings rs))}))
