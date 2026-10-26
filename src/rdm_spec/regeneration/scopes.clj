(ns rdm-spec.regeneration.scopes
  "Component Scopes of the regeneration context
  (dhf/spec/regeneration/scopes.feature; dhf/spec/glossary.md, \"Scope\"):
  `rdm-spec scopes CUT CUT` lists what a spec change touched between two
  cuts, read from the spec, never remembered.

  Between the cuts A and B (git's changed paths):
    - a component's own files, <spec-dir>/<context>/<component>.feature and
      its design prose <component>.md, make it a scope, {:component id};
    - the event catalogue is compared event by event (dhf/spec/events.edn,
      :read-by): a carried key whose shape changed, or that was added or
      removed, reaches the event's publisher and the subscribers :read-by
      names for that key, and no other; for a key read per sub-key
      (SpecParsed's :scenarios) a sub-key added or removed reaches only its
      readers, and a changed shape with the same sub-keys reaches every
      reader of the key, since which sub-key changed cannot be told. A
      change to :read-by or a description alone reaches no one. An event
      added or removed reaches its publisher and every reader; a publisher
      changed, both publishers and every reader;
    - any other changed path the configuration keeps (:clean-room :keep) is
      a shared file: a warning (shared-file-changed) for a person to judge
      whose code reads it, the task still passing. A denied path (the
      implementation, the measurements) is upstream code: never a scope.

  Scopes are listed in the order one builder builds them: the kernel first,
  then each publisher before its subscribers (every event's :read-by, and
  the model's relationships that publish an event), else in the model's
  order.

  Cannot run (exit 2): a CUT that is not a cut (spec-N) or that the
  repository does not hold.

  Result: {:scopes {:from A :to B :scopes [{:component id}] :shared [path]}}."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Owners

(defn owners
  "{spec-path component-id} of every component of the model `m`, the
  kernel's included."
  [m]
  (into {}
        (concat
         (for [c (:containers m) x (:contexts c) comp (:components x) :when (string? (:spec comp))]
           [(:spec comp) (:id comp)])
         (for [comp (:kernel m) :when (string? (:spec comp))]
           [(:spec comp) (:id comp)]))))

(defn owner-of
  "The component owning `rel`, a feature file or design prose under
  `spec-dir`, or nil."
  [owner-map spec-dir rel]
  (let [prefix (str (str/replace (str spec-dir) #"/+$" "") "/")]
    (when (str/starts-with? rel prefix)
      (when-let [[_ sp] (re-matches #"(.+)\.(?:feature|md)" (subs rel (count prefix)))]
        (get owner-map sp)))))

;; ---------------------------------------------------------------------------
;; Events

(defn- readers
  "Every subscriber a read-by entry names (per sub-key too)."
  [rb]
  (set (if (map? rb) (mapcat val rb) rb)))

(defn- all-readers [ev] (set (mapcat (comp readers val) (:read-by ev))))

(defn- key-scopes
  "The subscribers a change of the carried key `k` between the
  declarations `a` and `b` of one event reaches, or nil when it did not
  change."
  [a b k]
  (let [carried? (fn [ev] (or (some #{k} (:carries ev)) (contains? (:shape ev) k)))
        rb-a (get-in a [:read-by k])
        rb-b (get-in b [:read-by k])]
    (cond
      (and (carried? b) (not (carried? a))) (readers rb-b)
      (and (carried? a) (not (carried? b))) (readers rb-a)
      (or (map? rb-a) (map? rb-b))
      (let [ma (if (map? rb-a) rb-a {}) mb (if (map? rb-b) rb-b {})
            added (remove (set (keys ma)) (keys mb))
            removed (remove (set (keys mb)) (keys ma))]
        (cond
          (or (seq added) (seq removed)) (set (concat (mapcat mb added) (mapcat ma removed)))
          (not= (get-in a [:shape k]) (get-in b [:shape k])) (into (readers rb-a) (readers rb-b))))
      (not= (get-in a [:shape k]) (get-in b [:shape k])) (into (readers rb-a) (readers rb-b)))))

(defn event-scopes
  "Every component a change of the catalogue `a` into `b` reaches."
  [a b]
  (let [a (if (map? a) a {}) b (if (map? b) b {})]
    (set
     (for [e (distinct (concat (keys a) (keys b)))
           :let [ea (get a e) eb (get b e)]
           c (cond
               (and eb (not ea)) (conj (all-readers eb) (:published-by eb))
               (and ea (not eb)) (conj (all-readers ea) (:published-by ea))
               (not= (:published-by ea) (:published-by eb))
               (concat [(:published-by ea) (:published-by eb)] (all-readers ea) (all-readers eb))
               :else (let [ks (distinct (concat (:carries ea) (:carries eb) (keys (:shape ea)) (keys (:shape eb))))
                           reached (keep #(key-scopes ea eb %) ks)]
                       (when (seq reached) (conj (apply concat reached) (:published-by eb)))))
           :when c]
       c))))

;; ---------------------------------------------------------------------------
;; Build order

(defn- model-order [m]
  (vec (concat (keep :id (:kernel m))
               (for [c (:containers m) x (:contexts c) comp (:components x) :when (:id comp)] (:id comp)))))

(defn build-order
  "The components `ids` in the order a builder builds them: the kernel
  first, each publisher before its subscribers, else the model's order."
  [m catalogue ids]
  (let [order (model-order m)
        index (into {} (map-indexed (fn [i id] [id i]) order))
        rank #(get index % (count order))
        edges (set (concat
                    (for [[_ ev] (when (map? catalogue) catalogue) r (all-readers ev)] [(:published-by ev) r])
                    (for [r (:relationships m) :when (:event r)] [(:from r) (:to r)])))]
    (loop [left (set ids) out []]
      (if (empty? left)
        out
        (let [free (filter (fn [id] (not-any? #(and (left %) (not= % id) (edges [% id])) left)) left)
              pick (first (sort-by (juxt rank str) (if (seq free) free left)))]
          (recur (disj left pick) (conj out pick)))))))

;; ---------------------------------------------------------------------------
;; git

(defn- git [req & args]
  (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""} "git" args)
       (catch Exception e (command/cannot-run! (str "git cannot be run (" (ex-message e) "): rdm-spec scopes needs git")))))

(defn- commit-of [req cut]
  (when-not (re-matches #"spec-\d+" (str cut))
    (command/cannot-run! (str cut " is not a cut: scopes reads two cuts, commits tagged spec-N")))
  (let [r (git req "rev-parse" "--verify" "-q" (str "refs/tags/" cut "^{commit}"))]
    (if (zero? (:exit r))
      (str/trim (:out r))
      (command/cannot-run! (str "there is no cut " cut " in " (:root req) ": scopes reads two cuts")))))

(defn- show [req commit rel]
  (let [r (git req "show" (str commit ":" rel))]
    (when (zero? (:exit r)) (:out r))))

(defn- edn-at [req commit rel]
  (when-let [t (show req commit rel)]
    (try (edn/read-string t) (catch Exception _ nil))))

(defn- changed-paths [req a b]
  (let [r (git req "diff" "--name-only" "-z" "--no-renames" a b)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git cannot tell what changed between the cuts: " (str/trim (:err r)))))
    (vec (sort (remove str/blank? (str/split (:out r) #"\u0000"))))))

(defn- under? [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

;; ---------------------------------------------------------------------------
;; The stage

(defn scopes
  "The scopes stage: `rdm-spec scopes A B`."
  [req]
  (let [[from to] (:args req)
        a (commit-of req from)
        b (commit-of req to)
        config (let [c (edn-at req b command/config-file)] (if (map? c) c (:config req)))
        cfg {:config config}
        spec-dir (str/replace (str (command/config-value cfg :spec-dir)) #"/+$" "")
        events-rel (command/config-value cfg :events)
        model-rel (command/config-value cfg :model)
        model-a (edn-at req a model-rel)
        model-b (edn-at req b model-rel)
        owner-map (merge (owners (if (map? model-a) model-a {})) (owners (if (map? model-b) model-b {})))
        keep-paths (get-in config [:clean-room :keep] [])
        catalogue-b (edn-at req b events-rel)
        changed (changed-paths req a b)
        found (for [rel changed]
                (cond
                  (= rel events-rel) {:components (event-scopes (edn-at req a events-rel) catalogue-b)}
                  (owner-of owner-map spec-dir rel) {:components [(owner-of owner-map spec-dir rel)]}
                  (some #(under? rel %) keep-paths) {:shared rel}
                  :else nil))
        ids (set (remove nil? (mapcat :components found)))
        shared (vec (keep :shared found))
        ordered (build-order (if (map? model-b) model-b {}) catalogue-b ids)]
    {:result {:scopes {:from from :to to
                       :scopes (mapv (fn [id] {:component id}) ordered)
                       :shared shared}}
     :findings (vec (for [rel shared]
                      (command/warning rel "shared-file-changed"
                                       (str "changed between " from " and " to ", and no component owns it:"
                                            " a person must judge whose code reads it, and whether that is a scope"))))}))
