(ns rdm-spec.regeneration.scopes
  "The Scopes component of the regeneration context
  (dhf/spec/regeneration/scopes.feature; dhf/spec/glossary.md, \"Scope\"):
  `rdm-spec scopes CUT CUT` lists the scopes a spec change touched between
  two cuts, so a cycle's scopes come from the spec, never from memory.

  A scope is a component, {:component id}:
    - a component whose own spec changed: its feature file or its design
      prose (<spec-dir>/<context>/<component>.feature or .md), the kernel's
      under kernel/;
    - for each key an event carries whose shape changed, or that was added
      or removed (for a key holding a collection of maps, each of their
      keys, read from the first {...} of its shape), the event's publisher
      and the subscribers :read-by names as reading it; a key it names no
      readers for is read by every subscriber the model's relationships
      name for the event, an empty list by none. :read-by itself is
      metadata: changing it reaches no one.
  Any other changed file of the spec (the glossary, the needs, the model,
  the ledger) is shared: no component owns it, so it is named as a warning
  (scope-shared-file) for a person to judge whose readers it reaches.

  Scopes are listed in the order one builder builds them: the kernel's
  first, in the model's order; then each publisher before its subscribers
  (the model's event relationships), ties broken by the order in which the
  model's relationships from a listed component first name each
  subscriber, then the model's order.

  Cannot run (exit 2): a CUT that is not a cut tag the repository holds.
  Result: {:scopes {:from :to :scopes [{:component id}] :shared [path]}}."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Pure

(defn nested-keys
  "The keys of the maps a shape like \"[{:id :title}] ...\" describes, or nil."
  [shape]
  (let [s (str shape)
        start (str/index-of s "{")]
    (when start
      (let [top (loop [i (inc start) depth 0 out (StringBuilder.)]
                  (if (>= i (count s))
                    (str out)
                    (let [c (.charAt s i)]
                      (cond
                        (and (zero? depth) (= c \})) (str out)
                        (#{\{ \[ \(} c) (recur (inc i) (inc depth) out)
                        (#{\} \] \)} c) (recur (inc i) (dec depth) (.append out " "))
                        (zero? depth) (recur (inc i) depth (.append out c))
                        :else (recur (inc i) depth out)))))]
        (set (map (comp keyword second) (re-seq #":([^\s,{}\[\]()]+)" top)))))))

(defn components
  "Every component of the model, in its order, the kernel's first: [id]."
  [m]
  (vec (concat (keep :id (:kernel m))
               (for [c (:containers m) x (:contexts c) comp (:components x) :when (:id comp)] (:id comp)))))

(defn owners
  "{spec-path component-id} of the model."
  [m]
  (into {} (concat (for [k (:kernel m) :when (:spec k)] [(:spec k) (:id k)])
                   (for [c (:containers m) x (:contexts c) comp (:components x) :when (:spec comp)]
                     [(:spec comp) (:id comp)]))))

(defn subscribers
  "The subscribers the model's relationships name for `event`, in their order."
  [m event]
  (vec (distinct (for [r (:relationships m) :when (= event (:event r))] (:to r)))))

(defn- readers [m event e path]
  (let [rb (get-in e (into [:read-by] path) ::none)]
    (cond
      (= ::none rb) (subscribers m event)
      (map? rb) (vec (distinct (apply concat (vals rb))))
      :else (vec rb))))

(defn event-scopes
  "Pure: the components a change of the catalogue `old` to `new` reaches, as
  a set: each changed key's publisher and readers."
  [m old new]
  (set
   (for [event (distinct (concat (keys old) (keys new)))
         :let [o (get old event) n (get new event)
               e (or n o)
               ks (distinct (concat (:carries o) (:carries n)))]
         k ks
         :let [in-o (some #{k} (:carries o)) in-n (some #{k} (:carries n))
               so (get-in o [:shape k]) sn (get-in n [:shape k])
               reached (cond
                         (not (and in-o in-n)) (readers m event e [k])
                         (= so sn) nil
                         :else (let [ko (nested-keys so) kn (nested-keys sn)]
                                 (if (and ko kn (not= ko kn))
                                   (vec (mapcat #(readers m event e [k %]) (set/difference (set/union ko kn) (set/intersection ko kn))))
                                   (readers m event e [k]))))]
         :when (some? reached)
         c (cons (:published-by e) reached)]
     c)))

(defn ordered
  "The components `cs` in the order a builder builds them."
  [m cs]
  (let [all (components m)
        kernel (set (keep :id (:kernel m)))
        events (filter :event (:relationships m))
        in? (set cs)
        rank (into {} (reverse (keep-indexed (fn [i r] (when (in? (:from r)) [(:to r) i])) events)))
        order (into {} (map-indexed (fn [i c] [c i]) all))
        key-of (fn [c] [(get rank c Long/MAX_VALUE) (get order c Long/MAX_VALUE)])
        others (set (remove kernel cs))
        edges (for [r events :when (and (others (:from r)) (others (:to r)) (not= (:from r) (:to r)))] [(:from r) (:to r)])]
    (vec (concat
          (filter (set cs) (filter kernel all))
          (loop [left others out []]
            (if (empty? left)
              out
              (let [free (filter (fn [c] (not-any? (fn [[a b]] (and (= b c) (left a))) edges)) left)
                    pick (first (sort-by key-of (if (seq free) free left)))]
                (recur (disj left pick) (conj out pick)))))))))

;; ---------------------------------------------------------------------------
;; The cuts

(defn- git [req & args]
  (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""} "git" args))

(defn- commit-of [req cut]
  (when (re-matches #"spec-\d+" (str cut))
    (let [r (git req "rev-parse" "-q" "--verify" (str "refs/tags/" cut "^{commit}"))]
      (when (zero? (:exit r)) (str/trim (:out r))))))

(defn- files-at [req commit dir]
  (let [r (git req "ls-tree" "-r" "-z" "--name-only" commit "--" (str dir "/"))]
    (into {} (for [f (remove str/blank? (str/split (:out r) #"\u0000"))]
               [f (:out (git req "show" (str commit ":" f)))]))))

(defn- edn-of [text] (try (let [v (edn/read-string text)] (when (map? v) v)) (catch Exception _ nil)))

(defn scopes
  "The scopes stage: `rdm-spec scopes CUT CUT`."
  [req]
  (let [[from to] (:args req)
        [a b] (for [c [from to]]
                (or (commit-of req c)
                    (command/cannot-run! (str c " is not a cut in " (:root req) ": scopes compares two cuts, tags spec-N"))))
        cfg (or (edn-of (:out (git req "show" (str b ":" command/config-file)))) (:config req))
        value #(command/config-value {:config cfg} %)
        spec-dir (str/replace (value :spec-dir) #"/+$" "")
        old (files-at req a spec-dir)
        new (files-at req b spec-dir)
        changed (sort (filter #(not= (get old %) (get new %)) (distinct (concat (keys old) (keys new)))))
        m (or (edn-of (get new (value :model))) (edn-of (get old (value :model))) {})
        own (merge (owners (or (edn-of (get old (value :model))) {})) (owners m))
        owner-of (fn [f] (when (str/starts-with? f (str spec-dir "/"))
                           (let [sp (str/replace (subs f (inc (count spec-dir))) #"\.(feature|md)$" "")]
                             (when (re-find #"\.(feature|md)$" f) (get own sp)))))
        events-rel (value :events)
        owned (keep owner-of changed)
        evs (when (some #{events-rel} changed)
              (event-scopes m (or (edn-of (get old events-rel)) {}) (or (edn-of (get new events-rel)) {})))
        shared (vec (remove #(or (owner-of %) (= events-rel %)) changed))
        cs (ordered m (set (concat owned evs)))]
    {:result {:scopes {:from from :to to :scopes (mapv (fn [c] {:component c}) cs) :shared shared}}
     :findings (vec (for [f shared]
                      (command/warning f "scope-shared-file"
                                       (str "changed between " from " and " to ", and no component owns it:"
                                            " a person must judge whose readers it reaches"))))}))
