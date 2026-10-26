(ns rdm-spec.regeneration.scopes
  "Component Scopes of the regeneration context
  (dhf/spec/regeneration/scopes.feature; dhf/spec/glossary.md, \"Scope\",
  \"Read-by\"): `rdm-spec scopes CUT CUT` lists what a spec change touched
  between two cuts, so a cycle's scopes come from the spec.

  Each path changed between the cuts (git's list, as the later cut's
  configuration classifies it) is
    - a component's own when it is its feature file or its design prose,
      <spec-dir>/<context>/<component>.feature or .md (the kernel's
      included): that component is a scope;
    - the event catalogue (:events): each event's carried keys are compared
      between the cuts (whether it is carried, and its :shape); a key that
      changed, was added or was removed reaches the event's publisher and
      the subscribers that read it. For a key holding a collection of maps
      ([{:a :b}] at the start of its shape), the keys of its maps that were
      added or removed are compared, and when none was, every key of its
      maps counts as changed. Who reads a key is its :read-by entry at the
      cut that has the key (the later one, unless it was removed): no entry
      is every subscriber the model's relationships name for the event; a
      list is those, an empty one none. :read-by itself is metadata:
      writing it, changing it or adding it reaches no component;
    - shared, when it is another kept path (:clean-room :keep): no
      component owns it, so it is named as a warning (scope-shared) for a
      person to judge whose readers must be rebuilt;
    - nothing, otherwise (implementation, measurements, generated output):
      a component whose only change is upstream code is not rebuilt.

  Scopes are listed in the order one builder builds them: the kernel first,
  then each publisher before its subscribers (the model's event
  relationships), otherwise in the model's order.

  Cannot run (exit 2): not a git repository, a CUT it does not hold.

  Result: {:scopes {:from CUT :to CUT :scopes [{:component id}] :shared [path]}}."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; The model (pure)

(defn- components
  "Every component of the model in its order, the kernel's first: [{:id :spec}]."
  [m]
  (vec (concat (filter map? (:kernel m))
               (for [c (:containers m) x (:contexts c) comp (:components x)] comp))))

(defn owner
  "The scope owning the spec path `rel` under `spec-dir`: {:component id} or nil."
  [m spec-dir rel]
  (let [d (str (str/replace (str spec-dir) #"/+$" "") "/")]
    (when (str/starts-with? rel d)
      (when-let [[_ sp] (re-matches #"(.+)\.(?:feature|md)" (subs rel (count d)))]
        (some #(when (= sp (:spec %)) {:component (:id %)}) (components m))))))

(defn- subscribers [m event]
  (set (for [r (:relationships m) :when (and (map? r) (= event (:event r)))] (:to r))))

(defn build-order
  "The component ids `ids` in the order a builder builds them."
  [m ids]
  (let [index (into {} (map-indexed (fn [i c] [(:id c) i]) (components m)))
        rank #(get index % Integer/MAX_VALUE)
        ids (set ids)
        edges (set (for [r (:relationships m)
                         :when (and (map? r) (:event r) (ids (:from r)) (ids (:to r)) (not= (:from r) (:to r)))]
                     [(:from r) (:to r)]))]
    (loop [left ids out []]
      (if (empty? left)
        out
        (let [free (remove (fn [id] (some (fn [[a b]] (and (= b id) (left a))) edges)) left)
              pick (first (sort-by (juxt rank str) (if (seq free) free left)))]
          (recur (disj left pick) (conj out pick)))))))

;; ---------------------------------------------------------------------------
;; Event keys (pure)

(defn subkeys
  "The keys of the maps a shape holding a collection of maps names
  (\"[{:a :b [{:c}]}] ...\" gives [:a :b]), or nil for another shape."
  [shape]
  (let [s (str/triml (str shape))]
    (when (str/starts-with? s "[{")
      (loop [i 2 depth 1 acc (StringBuilder.)]
        (if (or (>= i (count s)) (zero? depth))
          (mapv keyword (map #(subs % 1) (re-seq #":[A-Za-z0-9?!*+_-]+" (str acc))))
          (let [c (.charAt s i)
                depth' (cond (#{\[ \{} c) (inc depth) (#{\] \}} c) (dec depth) :else depth)]
            (when (and (= 1 depth) (= 1 depth')) (.append acc c))
            (recur (inc i) depth' acc)))))))

(defn- carried? [e k] (boolean (some #{k} (:carries e))))

(defn- readers
  "Who reads `subs` (nil: the key itself) of the key `k` of `entry`."
  [all entry k subs]
  (let [rb (:read-by entry)]
    (if-not (and (map? rb) (contains? rb k))
      all
      (let [v (get rb k)]
        (cond
          (map? v) (if (seq subs)
                     (apply set/union #{} (for [s subs] (if (contains? v s) (set (get v s)) all)))
                     (apply set/union #{} (map set (vals v))))
          (sequential? v) (set v)
          :else all)))))

(defn- key-change
  "The readers a change of the key `k` of `event` reaches, or nil when it did not change."
  [all o n k]
  (let [in-o (carried? o k) in-n (carried? n k)
        sh-o (get-in o [:shape k]) sh-n (get-in n [:shape k])]
    (when (or (not= in-o in-n) (and in-o in-n (not= sh-o sh-n)))
      (let [entry (if in-n n o)
            so (subkeys sh-o) sn (subkeys sh-n)
            subs (cond
                   (not (and in-o in-n)) nil
                   (and so sn (not= (set so) (set sn))) (set/union (set/difference (set so) (set sn))
                                                                   (set/difference (set sn) (set so)))
                   (or so sn) (set (concat so sn))
                   :else nil)]
        (readers all entry k subs)))))

(defn event-scopes
  "The components a change from the catalogue `old` to `new` reaches."
  [m old new]
  (apply set/union #{}
         (for [event (distinct (concat (keys old) (keys new)))
               :let [o (get old event) n (get new event)
                     all (subscribers m event)
                     publishers (set (keep :published-by [o n]))
                     moved? (and o n (not= (:published-by o) (:published-by n)))
                     ks (distinct (concat (:carries o) (:carries n)))
                     reached (for [k ks :let [r (if moved? (readers all (if (carried? n k) n o) k nil) (key-change all o n k))]
                                   :when r]
                               r)]
               :when (or moved? (seq reached))]
           (apply set/union publishers reached))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- under? [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn- edn-or-empty [req rev path]
  (let [v (when path (cut/edn-at req rev path))] (if (map? v) v {})))

(defn scopes
  "The scopes stage: `rdm-spec scopes CUT CUT`."
  [req]
  (cut/repository! req)
  (let [[a b] (:args req)
        _ (doseq [c [a b]]
            (when-not (cut/commit-of req c)
              (command/cannot-run! (str "the cut " c " is not in " (:root req) ": scopes compares two cuts"))))
        cfg (merge {:config (or (cut/config-at req b) (:config req))})
        spec-dir (command/config-value cfg :spec-dir)
        events (command/config-value cfg :events)
        keep-paths (get-in cfg [:config :clean-room :keep])
        model-b (edn-or-empty req b (command/config-value cfg :model))
        model-a (edn-or-empty req a (command/config-value cfg :model))
        changed (remove str/blank? (str/split-lines (cut/git! req {} (str "git cannot compare " a " and " b)
                                                              "diff" "--name-only" "--no-renames" a b "--")))
        classified (for [p changed]
                     (cond
                       (= p events) [:events p]
                       (or (owner model-b spec-dir p) (owner model-a spec-dir p))
                       [:own (:component (or (owner model-b spec-dir p) (owner model-a spec-dir p)))]
                       (some #(under? p %) keep-paths) [:shared p]
                       :else nil))
        own (set (for [[k v] classified :when (= :own k)] v))
        evs (if (some #(= :events (first %)) classified)
              (event-scopes model-b (edn-or-empty req a events) (edn-or-empty req b events))
              #{})
        shared (vec (sort (for [[k v] classified :when (= :shared k)] v)))
        ids (build-order model-b (set/union own evs))]
    {:result {:scopes {:from a :to b :scopes (mapv (fn [id] {:component id}) ids) :shared shared}}
     :findings (vec (for [p shared]
                      (command/warning p "scope-shared"
                                       (str "changed between " a " and " b ", and no component owns it:"
                                            " a person must judge whose readers to rebuild"))))}))
