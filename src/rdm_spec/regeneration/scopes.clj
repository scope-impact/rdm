(ns rdm-spec.regeneration.scopes
  "The Scopes component of the regeneration context
  (dhf/spec/regeneration/scopes.feature): `rdm-spec scopes CUT CUT` lists
  the scopes a spec change touched between two cuts, read from the spec,
  never remembered.

  A changed file under the spec directory that a component's spec path
  names (<spec>.feature, or its design prose <spec>.md) makes that
  component a scope. A changed event catalogue (the configuration's
  :events) makes, for each event whose contract changed (what it carries
  and their shape, :carries and :shape; a description or a key the
  catalogue entry does not declare is no contract), or that was added or
  removed, its publisher and every subscriber the model's relationships
  name for it scopes. Any other changed path the clean room keeps is a
  shared file: a warning (shared-file-changed) names it for a person to
  judge, and the task exits 0. Denied paths (the implementation, the
  measurements, the generated output) are nobody's spec.

  Order, so one builder can build them one after another: the kernel
  first, each publisher before every component its events reach, and
  components with no order between them in the model's order (the kernel's
  components, then each container's contexts' components). A component
  only the earlier cut's model names comes last.

  Cannot run: a CUT that is not a cut (a spec-N tag of this repository).
  Result: {:scopes {:from :to :scopes [{:component id}] :shared [path]}}."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

(defn- components [m]
  (concat (for [c (:kernel m) :when (map? c)] c)
          (for [ct (:containers m) :when (map? ct) x (:contexts ct) :when (map? x)
                c (:components x) :when (map? c)]
            c)))

(defn model-order
  "Every component id of the model `m`, in the model's order."
  [m]
  (vec (keep :id (components m))))

(defn owners
  "A function of a path: the component whose spec path names it, or nil."
  [spec-dir m]
  (let [d (str (str/replace (str spec-dir) #"/+$" "") "/")
        by-spec (into {} (for [c (components m) :when (:spec c)] [(:spec c) (:id c)]))]
    (fn [path]
      (when (str/starts-with? path d)
        (when-let [[_ sp] (re-matches #"(.+)\.(?:feature|md)" (subs path (count d)))]
          (get by-spec sp))))))

(defn- contract [e] (when (map? e) (select-keys e [:carries :shape])))

(defn changed-events
  "The events of the catalogues `a` and `b` whose contract differs, or that
  only one holds."
  [a b]
  (let [a (if (map? a) a {}) b (if (map? b) b {})]
    (vec (for [k (distinct (concat (keys b) (keys a)))
               :when (or (not= (contains? a k) (contains? b k)) (not= (contract (get a k)) (contract (get b k))))]
           k))))

(defn- event-edges [m]
  (for [r (:relationships m) :when (and (map? r) (:event r))] [(:from r) (:to r)]))

(defn- reaches
  "{id #{ids it reaches}} over the edges."
  [edges]
  (let [succ (reduce (fn [acc [a b]] (update acc a (fnil conj #{}) b)) {} edges)]
    (into {} (for [n (keys succ)]
               [n (loop [seen #{} todo (vec (succ n))]
                    (if-let [x (peek todo)]
                      (if (seen x) (recur seen (pop todo)) (recur (conj seen x) (into (pop todo) (succ x))))
                      seen))]))))

(defn ordered
  "The component ids `ids` in the order a builder builds them, by the model `m`."
  [m ids]
  (let [rank (into {} (map-indexed (fn [i id] [id i]) (model-order m)))
        reach (reaches (event-edges m))
        before? (fn [a b] (contains? (get reach a #{}) b))
        key-of (fn [id] [(get rank id Long/MAX_VALUE) (str id)])]
    (loop [left (set ids) out []]
      (if (empty? left)
        out
        (let [ready (filter (fn [x] (not-any? #(and (not= % x) (before? % x) (not (before? x %))) left)) left)
              pick (first (sort-by key-of (if (seq ready) ready left)))]
          (recur (disj left pick) (conj out pick)))))))

(defn- under? [p l]
  (let [l (str/replace (str l) #"/+$" "")]
    (or (= p l) (str/starts-with? p (str l "/")))))

(defn scopes
  "The scopes stage: `rdm-spec scopes CUT CUT`."
  [req]
  (let [[a b] (:args req)
        _ (cut/repository! req)
        commit! (fn [t] (or (when (cut/cut-tag? t) (cut/commit-of req t))
                            (command/cannot-run! (str t " is not a cut of this repository: scopes compares two spec-N tags"))))
        ca (commit! a)
        cb (commit! b)
        cfg (or (cut/config-at req cb) (:config req))
        x {:config cfg}
        spec-dir (command/config-value x :spec-dir)
        model-rel (command/config-value x :model)
        events-rel (command/config-value x :events)
        ma (let [v (cut/edn-at req ca model-rel)] (if (map? v) v {}))
        mb (let [v (cut/edn-at req cb model-rel)] (if (map? v) v {}))
        own-b (owners spec-dir mb)
        own-a (owners spec-dir ma)
        changed (remove str/blank? (str/split-lines (cut/git! req {} (str "the cuts " a " and " b " cannot be compared")
                                                             "diff" "--name-only" "--no-renames" ca cb)))
        deny (get-in cfg [:clean-room :deny])
        denied (concat (when (:implementation deny) (:implementation cfg)) (:measurements deny) (:generated deny))
        by-file (for [p changed] (or (own-b p) (own-a p)))
        events (when (some #{events-rel} changed)
                 (changed-events (cut/edn-at req ca events-rel) (cut/edn-at req cb events-rel)))
        cat-a (when events (cut/edn-at req ca events-rel))
        cat-b (when events (cut/edn-at req cb events-rel))
        by-event (for [e events
                       c (cons (or (get-in cat-b [e :published-by]) (get-in cat-a [e :published-by]))
                               (for [r (concat (:relationships mb) (:relationships ma))
                                     :when (and (map? r) (= e (:event r)))]
                                 (:to r)))
                       :when c]
                   c)
        shared (vec (for [[p o] (map vector changed by-file)
                          :when (and (nil? o) (not= p events-rel) (not-any? #(under? p %) denied))]
                      p))
        ids (set (concat (remove nil? by-file) by-event))
        known (set (model-order mb))
        order (concat (ordered mb (filter known ids)) (sort-by str (remove known ids)))]
    {:result {:scopes {:from a :to b :scopes (mapv (fn [id] {:component id}) order) :shared shared}}
     :findings (vec (for [p shared]
                      (command/warning p "shared-file-changed"
                                       (str "changed between " a " and " b ", and no component owns it: a shared file,"
                                            " whose readers a person must judge"))))}))
