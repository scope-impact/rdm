(ns rdm-spec.regeneration.scopes
  "Scopes (dhf/spec/regeneration/scopes.feature): `rdm-spec scopes CUT CUT`
  lists the scopes a spec change touched between two cuts, in the order one
  builder builds them.

  A scope is {:component ID}, the model's id (the kernel's under kernel/):

  - a component whose own spec changed: its feature file or its design
    prose (<context>/<component>.feature or .md), owned by either cut's
    model;
  - for each event key whose shape changed, or that was added or removed,
    in the catalogue (the configuration's :events): the event's publisher
    and the subscribers that read the key (Read-by: a flat list names
    them; for a key holding maps, :read-by's map names them per inner key,
    the inner keys read from the shape's [{:k ..}]; a key or inner key it
    names no readers for is read by every subscriber the model's
    relationships name for the event; an empty list by none). :read-by is
    metadata: changing it reaches no one.

  Any other changed path that is kept (the model, the glossary, the wiki
  ...) is a shared file, named in a warning (changed-shared-file) for a
  person to judge; a denied path (the implementation, the measurements) is
  never a scope.

  Order: the kernel's components first, in the model's order, then every
  publisher before its subscribers (the model's event relationships), ties
  in the model's order.

  Result {:scopes {:from CUT :to CUT :scopes [{:component ID}] :shared
  [path]}}. Cannot run: a cut names no commit."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; The model

(defn component-order
  "Every component id of the model `m`, the kernel's first, in order."
  [m]
  (vec (concat (keep :id (:kernel m))
               (for [c (:containers m) :when (map? c) x (:contexts c) comp (:components x) :when (:id comp)]
                 (:id comp)))))

(defn owners
  "{spec-path component-id} of the model `m`."
  [m]
  (into {} (concat (for [c (:containers m) :when (map? c) x (:contexts c) comp (:components x)
                         :when (string? (:spec comp))]
                     [(:spec comp) (:id comp)])
                   (for [comp (:kernel m) :when (string? (:spec comp))] [(:spec comp) (:id comp)]))))

(defn subscribers
  "The subscribers of the event `e`, in the order the model's relationships name them."
  [m e]
  (vec (distinct (for [r (:relationships m) :when (= e (:event r))] (:to r)))))

(defn build-order
  "The components `ids` in the order a builder builds them."
  [ids m]
  (let [order (component-order m)
        rank (fn [id] (let [i (.indexOf ^java.util.List order id)] (if (neg? i) Integer/MAX_VALUE i)))
        ids (set ids)
        kernel (filter #(= "kernel" (namespace %)) (sort-by rank ids))
        rest* (set (remove (set kernel) ids))
        edges (set (for [r (:relationships m) :when (and (:event r) (rest* (:from r)) (rest* (:to r)) (not= (:from r) (:to r)))]
                     [(:from r) (:to r)]))]
    (loop [done (vec kernel) left rest*]
      (if (empty? left)
        done
        (let [ready (filter (fn [id] (not-any? (fn [[a b]] (and (= b id) (left a))) edges)) left)
              pick (first (sort-by (juxt rank str) (if (seq ready) ready left)))]
          (recur (conj done pick) (disj left pick)))))))

;; ---------------------------------------------------------------------------
;; The catalogue

(defn inner-keys
  "The keys of the maps a shape `s` of the form [{:k ..}] describes (the
  map's own keys, not those of maps nested in it), or nil."
  [s]
  (let [s (str s)]
    (when-let [m (re-find #"^\s*\[\{" s)]
      (loop [i (count m) depth 1 cur (StringBuilder.) parts []]
        (if (or (>= i (count s)) (zero? depth))
          (set (map keyword (map #(subs % 1) (mapcat #(re-seq #":[A-Za-z0-9_*+!?<>=/-]+" %) (conj parts (str cur))))))
          (let [c (.charAt s i)]
            (cond
              (#{\{ \[ \(} c) (recur (inc i) (inc depth) (if (= 1 depth) (StringBuilder.) cur)
                                     (if (= 1 depth) (conj parts (str cur)) parts))
              (#{\} \] \)} c) (recur (inc i) (dec depth) cur parts)
              (= 1 depth) (recur (inc i) depth (.append cur c) parts)
              :else (recur (inc i) depth cur parts))))))))

(defn- readers
  "The components that read the key `k` of an event whose subscribers are
  `subs`, `read-by` its :read-by, where the inner keys `changed` changed
  (nil: the whole key)."
  [read-by k subs changed]
  (if-not (contains? read-by k)
    subs
    (let [rb (get read-by k)]
      (cond
        (sequential? rb) (vec rb)
        (map? rb) (vec (distinct (mapcat (fn [ik] (if (contains? rb ik) (get rb ik) subs))
                                         (or (seq changed) (keys rb)))))
        :else subs))))

(defn event-scopes
  "The components an event change between the catalogues `before` and
  `after` reaches, the model `m` naming the subscribers."
  [before after m]
  (set
   (for [e (distinct (concat (keys before) (keys after)))
         :let [b (get before e) a (get after e)
               publisher (or (:published-by a) (:published-by b))
               subs (subscribers m e)
               read-by (or (:read-by a) (:read-by b) {})]
         k (distinct (concat (:carries b) (:carries a) (keys (:shape b)) (keys (:shape a))))
         :let [in-b (some #{k} (:carries b)) in-a (some #{k} (:carries a))
               sb (get-in b [:shape k]) sa (get-in a [:shape k])]
         :when (or (not= (boolean in-b) (boolean in-a)) (not= sb sa))
         :let [ib (inner-keys sb) ia (inner-keys sa)
               changed (when (and ib ia) (seq (set/union (set/difference ib ia) (set/difference ia ib))))
               changed (or changed (when (or ib ia) (seq (set/union (or ib #{}) (or ia #{})))))]
         id (cons publisher (readers read-by k subs changed))
         :when id]
     id)))

;; ---------------------------------------------------------------------------
;; The stage

(defn- files-at
  "{path blob} of every file of `commit`."
  [req commit]
  (let [out (cut/git! req {} (str "the commit " commit " cannot be read") "ls-tree" "-r" "-z" "--full-tree" commit)]
    (into {} (for [entry (str/split out #"\u0000") :when (not (str/blank? entry))
                   :let [[meta p] (str/split entry #"\t" 2)]]
               [p (nth (str/split meta #" ") 2)]))))

(defn- edn-of [req commit rel]
  (let [v (cut/edn-at req commit rel)] (if (map? v) v {})))

(defn- under? [p l]
  (let [l (str/replace (str l) #"/+$" "")] (or (= p l) (str/starts-with? p (str l "/")))))

(defn scopes
  "The scopes stage."
  [req]
  (let [[from to] (:args req)
        _ (cut/repository! req)
        c-from (or (cut/commit-of req from) (command/cannot-run! (str from " names no commit of this repository")))
        c-to (or (cut/commit-of req to) (command/cannot-run! (str to " names no commit of this repository")))
        cfg (or (cut/config-at req c-to) (cut/config-at req c-from) (:config req))
        cv #(command/config-value {:config cfg} %)
        spec-dir (str/replace (cv :spec-dir) #"/+$" "")
        m-from (edn-of req c-from (cv :model))
        m-to (edn-of req c-to (cv :model))
        own (merge (owners m-from) (owners m-to))
        f-from (files-at req c-from)
        f-to (files-at req c-to)
        changed (sort (filter #(not= (get f-from %) (get f-to %)) (distinct (concat (keys f-from) (keys f-to)))))
        deny (get-in cfg [:clean-room :deny])
        denied? (fn [p] (some #(under? p %) (concat (when (:implementation deny) (:implementation cfg))
                                                   (:measurements deny) (:generated deny))))
        owner-of (fn [p]
                   (when (str/starts-with? p (str spec-dir "/"))
                     (get own (str/replace (subs p (inc (count spec-dir))) #"\.(feature|md)$" ""))))
        events-rel (cv :events)
        owned (keep owner-of changed)
        shared (vec (remove #(or (owner-of %) (= events-rel %) (denied? %)) changed))
        from-events (when (some #{events-rel} changed)
                      (event-scopes (edn-of req c-from events-rel) (edn-of req c-to events-rel) m-to))
        ids (set (concat owned from-events))]
    {:result {:scopes {:from from :to to
                       :scopes (mapv (fn [id] {:component id}) (build-order ids m-to))
                       :shared shared}}
     :findings (vec (for [p shared]
                      (command/warning p "changed-shared-file"
                                       (str "changed between " from " and " to " and owned by no component: "
                                            "a person must judge its readers"))))}))
