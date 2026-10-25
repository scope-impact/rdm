(ns steps.architecture-views
  "Step definitions for dhf/spec/architecture/views.feature: a copy of the
  room's spec whose model a step trims, `rdm-spec views` (the shared When) to
  draw it, and each Then reading the outputs back from dhf/views/.

  What is checked is computed here from the model and the glossary, never
  asked of the implementation: the model hash is the SHA-256, as lowercase
  hex, of the model read as EDN and printed back with every map's keys sorted;
  the formats are the ones dhf/harness/stack.md names for dhf/views/<key>."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- views-dir [] (:views-dir (s/room-config)))

(defn- sha256-hex [^String text]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn model-hash
  "The glossary's model hash of the model file in `dir`."
  [dir]
  (let [v (edn/read-string (slurp (str (fs/file dir a/model-rel))))]
    (sha256-hex (binding [*print-namespace-maps* false]
                  (pr-str (walk/postwalk #(if (map? %) (into (sorted-map) %) %) v))))))

(defn formats
  "The extensions dhf/harness/stack.md names for a view's outputs."
  []
  (let [text (slurp (str (fs/file s/room-root "dhf/harness/stack.md")))
        exts (distinct (map second (re-seq #"dhf/views/<key>\.(\w+)" text)))]
    (s/check! (seq exts) "dhf/harness/stack.md names no format for dhf/views/<key>")
    (vec exts)))

(defn- output [dir k ext] (fs/file dir (views-dir) (str k "." ext)))

(defn- run-views! [ctx]
  (let [r (s/rdm-spec! (:dir ctx) "views")]
    (s/check! (= 0 (:exit r)) (str "rdm-spec views failed\n" (s/show r)))
    r))

;; --- Parsing a diagram source -------------------------------------------------

(defn- alias-of
  "How a diagram source names an element's id."
  [k]
  (-> (if (namespace k) (str (namespace k) "__" (name k)) (name k))
      (str/replace #"[^A-Za-z0-9_]" "_")))

(defn parse-source
  "A view's diagram source as {:elements {alias {:macro :inside?}} :rels
  [[from to label]] :boundaries n}."
  [text]
  (loop [[l & more :as ls] (str/split-lines text) depth 0 acc {:elements {} :rels [] :boundaries 0}]
    (if (empty? ls)
      acc
      (let [l (str/trim l)]
        (cond
          (re-find #"^\w*Boundary\(" l)
          (recur more (inc depth) (update acc :boundaries inc))

          (= "}" l) (recur more (dec depth) acc)

          (re-find #"^Rel\w*\(" l)
          (let [[_ from to label] (re-find #"^Rel\w*\(\s*(\w+)\s*,\s*(\w+)\s*,\s*\"([^\"]*)\"" l)]
            (recur more depth (update acc :rels conj [from to label])))

          (re-find #"^(Person|System|Container|Component)\w*\(" l)
          (let [[_ macro alias] (re-find #"^(\w+)\(\s*(\w+)" l)]
            (recur more depth (assoc-in acc [:elements alias] {:macro macro :inside? (pos? depth)})))

          :else (recur more depth acc))))))

;; --- What the model says a view holds ----------------------------------------

(defn- elements-of
  "{id {:type :container :context}} for every element of model `m`."
  [m]
  (merge
   {(get-in m [:system :id]) {:type :system}}
   (into {} (for [p (:people m)] [(:id p) {:type :person}]))
   (into {} (for [e (:external m)] [(:id e) {:type :external}]))
   (into {} (for [c (:containers m)] [(:id c) {:type :container}]))
   (into {} (for [c (:containers m) x (:contexts c)] [(:id x) {:type :context :container (:id c)}]))
   (into {} (for [c (:containers m) x (:contexts c) y (:components x)]
              [(:id y) {:type :component :container (:id c) :context (:id x)}]))
   (into {} (for [k (:kernel m)] [(:id k) {:type :kernel}]))))

(defn- kernel-container
  "The container the kernel is shared code of: the one that holds contexts."
  [m]
  (:id (or (first (filter :contexts (:containers m))) (first (filter #(= :runs (:kind %)) (:containers m))))))

(defn- lift
  "The element of the level `level` that `id` stands in or is."
  [m els level id]
  (let [{:keys [type container]} (get els id)]
    (case level
      :context (if (#{:person :external :system} type) id (get-in m [:system :id]))
      :container (case type
                   (:context :component) container
                   :kernel (kernel-container m)
                   id))))

(defn- lifted-rels [m level]
  (let [els (elements-of m)]
    (set (for [r (:relationships m)
               :let [f (lift m els level (:from r)) t (lift m els level (:to r))]
               :when (not= f t)]
           [(alias-of f) (alias-of t)]))))

(defn- the-view [ctx]
  (let [m (a/read-model (:dir ctx))
        v (first (filter #(= (:view-key ctx) (:key %)) (:views m)))
        src (output (:dir ctx) (:key v) "mmd")]
    (s/check! (fs/exists? src) (str "no diagram source " src))
    {:m m :v v :parsed (parse-source (slurp (str src)))}))

(defn- same! [what want got]
  (s/check! (= (set want) (set got))
            (str what " differ: missing " (pr-str (sort (remove (set got) want)))
                 ", extra " (pr-str (sort (remove (set want) got))))))

(defn- holds-context [ctx]
  (let [{:keys [m parsed]} (the-view ctx)
        want (map alias-of (concat [(get-in m [:system :id])] (map :id (:people m)) (map :id (:external m))))]
    (same! "the context view's elements" want (keys (:elements parsed)))
    (same! "the context view's relationships" (lifted-rels m :context) (map (comp vec (partial take 2)) (:rels parsed)))))

(defn- holds-container [ctx]
  (let [{:keys [m parsed]} (the-view ctx)
        containers (map (comp alias-of :id) (:containers m))
        want (concat containers (map (comp alias-of :id) (:people m)) (map (comp alias-of :id) (:external m)))]
    (same! "the container view's elements" want (keys (:elements parsed)))
    (same! "the container view's elements inside the system" containers
           (keep (fn [[k e]] (when (:inside? e) k)) (:elements parsed)))
    (same! "the container view's relationships" (lifted-rels m :container)
           (map (comp vec (partial take 2)) (:rels parsed)))))

(defn- holds-component [ctx]
  (let [{:keys [m v parsed]} (the-view ctx)
        els (elements-of m)
        inside (set (for [[id e] els :when (and (= :component (:type e)) (= (:context v) (:context e)))] id))
        rels (filter #(or (inside (:from %)) (inside (:to %))) (:relationships m))
        outside (set (remove inside (mapcat (juxt :from :to) rels)))
        drawn (:elements parsed)]
    (s/check! (= 1 (:boundaries parsed)) (str "the component view draws " (:boundaries parsed) " boundaries, not one"))
    (same! "the components inside the boundary" (map alias-of inside)
           (keep (fn [[k e]] (when (:inside? e) k)) drawn))
    (same! "the elements outside the boundary" (map alias-of outside)
           (keep (fn [[k e]] (when-not (:inside? e) k)) drawn))
    (doseq [id outside :let [e (get drawn (alias-of id))]]
      (s/check! (str/ends-with? (:macro e) "_Ext") (str id " is outside the context but drawn as " (:macro e))))
    (same! "the component view's relationships" (set (map #(mapv alias-of [(:from %) (:to %)]) rels))
           (map (comp vec (partial take 2)) (:rels parsed)))))

(defn- holds-dynamic [ctx]
  (let [{:keys [v parsed]} (the-view ctx)
        steps (:steps v)]
    (same! "the dynamic view's elements" (map alias-of (distinct (mapcat (partial take 2) steps)))
           (keys (:elements parsed)))
    (s/check! (= (mapv (fn [[f t label]] [(alias-of f) (alias-of t) label]) steps) (:rels parsed))
              (str "the dynamic view's steps are not its relationships, in order: " (pr-str (:rels parsed))))))

(def ^:private holds
  {"the system, every person and every external system, and the relationships between them" holds-context
   "every container, every person and every external system, and the relationships between them" holds-container
   "one context's components inside its boundary, and the elements outside it they relate to, drawn as external"
   holds-component
   "the elements its steps name, and its steps in order" holds-dynamic})

;; ---------------------------------------------------------------------------
;; The C4 model is one source and every view is generated from it

(defstep #"a model with a context view, a container view and two component views"
  (fn [ctx]
    (let [dir (a/dir-of ctx)
          m (a/read-model dir)
          pick (fn [level] (filter #(= level (:level %)) (:views m)))
          views (vec (concat (take 1 (pick :context)) (take 1 (pick :container)) (take 2 (pick :component))))]
      (s/check! (= 4 (count views)) "the room's model lacks a view of some level")
      ;; no earlier output, so everything read back was written by this run
      (fs/delete-tree (fs/file dir (views-dir)))
      (a/write-model! dir (assoc m :views views))
      (assoc ctx :dir dir :view-keys (mapv :key views)))))

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 0 (:exit r)) (s/show r))
      (doseq [k (:view-keys ctx) ext (formats) :let [f (output (:dir ctx) k ext)]]
        (s/check! (fs/regular-file? f) (str "no " ext " output for the view " k "\n" (s/show r)))
        (when (= "svg" ext)
          (s/check! (re-find #"<svg[\s>]" (slurp (str f))) (str f " is no SVG render")))
        (when (= "mmd" ext)
          (s/check! (re-find #"(?m)^C4\w+$" (slurp (str f))) (str f " is no C4 diagram source")))))))

(defstep #"each output's first line is a comment holding \"model-sha256: \" and the model's hash"
  (fn [ctx]
    (let [h (model-hash (:dir ctx))]
      (doseq [k (:view-keys ctx) ext (formats)
              :let [f (output (:dir ctx) k ext)
                    line (first (str/split-lines (slurp (str f))))]]
        (s/check! (case ext
                    "mmd" (str/starts-with? line "%%")
                    "svg" (and (str/starts-with? line "<!--") (str/ends-with? line "-->"))
                    true)
                  (str f "'s first line is no comment: " line))
        (s/check! (str/includes? line (str "model-sha256: " h))
                  (str f "'s first line does not hold the model hash " h ": " line))))))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (a/needs-check!)
    (let [dir (a/dir-of ctx)
          ctx (assoc ctx :dir dir)
          _ (run-views! ctx)
          k "containers"
          f (output dir k "svg")
          text (slurp (str f))
          h (model-hash dir)
          other (apply str (repeat 64 "0"))]
      (s/check! (str/includes? text h) (str f " does not record the model hash"))
      (spit (str f) (str/replace-first text h other))
      (assoc ctx :stale-view k))))

(defstep #"it fails and names the stale view"
  (fn [ctx]
    (let [r (:run ctx)
          k (:stale-view ctx)
          stale (a/rule-lines r "views-stale")]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (some #(str/includes? % (str "view \"" k "\"")) stale)
                (str "the view " k " is not named stale\n" (s/show r)))
      (s/check! (every? #(str/includes? % (str "view \"" k "\"")) stale)
                (str "a fresh view is named stale\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Each level of view holds what C4 says it holds

(defstep #"a model with a view of level (\w+)"
  (fn [ctx level]
    (let [dir (a/dir-of ctx)
          v (first (filter #(= (keyword level) (:level %)) (:views (a/read-model dir))))]
      (s/check! v (str "the room's model has no view of level " level))
      (fs/delete-tree (fs/file dir (views-dir)))
      (assoc ctx :dir dir :view-key (:key v)))))

(defstep #"the view holds (.+)"
  (fn [ctx what]
    (s/check! (= 0 (:exit (:run ctx))) (s/show (:run ctx)))
    (let [f (or (get holds what) (s/fail! (str "no check for " (pr-str what))))]
      (f ctx)
      nil)))
