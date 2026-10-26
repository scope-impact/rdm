(ns rdm-spec.regeneration.scopes
  "Component Scopes of the regeneration context
  (dhf/spec/regeneration/scopes.feature): `rdm-spec scopes CUT CUT` lists
  the scopes a spec change touched between two cuts, read from the spec and
  never remembered:

    - a component whose own spec changed: its feature file or its design
      prose (<spec-dir>/<context>/<component>.feature or .md);
    - for each event of the catalogue whose declaration changed (anything
      but its :description), the component the catalogue names as its
      publisher and every component the model draws subscribing to it (a
      relationship publishing the event), at either cut.

  A component whose only change is upstream code is not rebuilt: a change
  to an implementation path, a measurement or generated output is no scope.
  Any other changed file no component owns is a shared file, named in a
  warning for a person to judge (scope-shared-file); the task exits 0.

  The scopes are listed in the order one builder builds them: the kernel
  first, then each publisher before its subscribers, else the model's order.

  Result: {:scopes [{:component id}] :shared [path] :cuts [CUT CUT]}.
  Cannot run (exit 2): a revision that is not a cut in the project."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.regeneration.fence :as fence]))

;; ---------------------------------------------------------------------------
;; Pure

(defn- components
  "Every component of the model `m`, the kernel's first: [{:id :spec}]."
  [m]
  (vec (concat (filter map? (:kernel m))
               (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)
                     comp (:components x) :when (map? comp)]
                 comp))))

(defn owner
  "The id of the component whose own spec the path `rel` is (its feature
  file or its design prose), or nil."
  [m spec-dir rel]
  (let [prefix (str (str/replace (str spec-dir) #"/+$" "") "/")]
    (when (str/starts-with? rel prefix)
      (when-let [[_ sp] (re-matches #"(.+)\.(?:feature|md)" (subs rel (count prefix)))]
        (:id (first (filter #(= sp (:spec %)) (components m))))))))

(defn- subscribers [m event]
  (for [r (:relationships m) :when (and (map? r) (= event (:event r)))] (:to r)))

(defn event-components
  "The components a change of the catalogue from `old` to `new` reaches:
  the publisher and the subscribers (in the models `m-old` and `m-new`) of
  every event whose declaration changed, :description aside."
  [old new m-old m-new]
  (let [strip #(when (map? %) (dissoc % :description))]
    (set (for [e (distinct (concat (keys old) (keys new)))
               :when (not= (strip (get old e)) (strip (get new e)))
               c (concat (keep #(get-in % [e :published-by]) [old new])
                         (subscribers m-old e) (subscribers m-new e))
               :when c]
           c))))

(defn build-order
  "The components `ids` in the order a builder builds them: the kernel
  first, each publisher before its subscribers, else the model's order."
  [m ids]
  (let [order (into {} (map-indexed (fn [i c] [(:id c) i]) (components m)))
        rank #(get order % Integer/MAX_VALUE)
        ids (set ids)
        edges (set (for [r (:relationships m)
                         :when (and (map? r) (:event r) (ids (:from r)) (ids (:to r)) (not= (:from r) (:to r)))]
                     [(:from r) (:to r)]))]
    (loop [left ids out []]
      (if (empty? left)
        out
        (let [ready (filter (fn [c] (not-any? (fn [[a b]] (and (= b c) (left a))) edges)) left)
              pick (first (sort-by (juxt rank str) (if (seq ready) ready left)))]
          (recur (disj left pick) (conj out pick)))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- commit-of-cut! [req rev]
  (when-not (cut/cut-tag? rev)
    (command/cannot-run! (str rev " is not a cut: scopes are read between two cuts, spec-N")))
  (or (cut/commit-of req (str "refs/tags/" rev))
      (command/cannot-run! (str "there is no cut " rev " in " (:root req)))))

(defn scopes
  "The scopes stage: `rdm-spec scopes CUT CUT`."
  [req]
  (cut/repository! req)
  (let [[a b] (:args req)
        ca (commit-of-cut! req a)
        cb (commit-of-cut! req b)
        config (or (cut/config-at req cb) (:config req))
        cfg {:config config}
        spec-dir (command/config-value cfg :spec-dir)
        model-rel (command/config-value cfg :model)
        events-rel (command/config-value cfg :events)
        m-old (or (cut/edn-at req ca model-rel) {})
        m-new (or (cut/edn-at req cb model-rel) {})
        changed (vec (remove str/blank?
                             (str/split (cut/git! req {} (str "the cuts " a " and " b " cannot be compared")
                                                  "diff" "--name-only" "--no-renames" "-z" ca cb)
                                        #"\u0000")))
        upstream? (fence/denier config [])
        event-comps (when (some #{events-rel} changed)
                      (event-components (or (cut/edn-at req ca events-rel) {})
                                        (or (cut/edn-at req cb events-rel) {}) m-old m-new))
        owned (keep #(or (owner m-new spec-dir %) (owner m-old spec-dir %)) changed)
        shared (vec (for [p changed
                          :when (not (or (owner m-new spec-dir p) (owner m-old spec-dir p) (upstream? p)
                                         (and (= events-rel p) (seq event-comps))))]
                      p))
        ids (set (concat owned event-comps))
        merged (update m-new :relationships #(vec (concat % (:relationships m-old))))
        ordered (build-order (if (seq (components m-new)) merged m-old) ids)]
    {:result {:scopes (mapv (fn [id] {:component id}) ordered)
              :shared shared
              :cuts [a b]}
     :findings (vec (for [p shared]
                      (command/warning p "scope-shared-file"
                                       (str "changed between " a " and " b ", and no component owns it:"
                                            " a person must judge which scopes read it"))))}))
