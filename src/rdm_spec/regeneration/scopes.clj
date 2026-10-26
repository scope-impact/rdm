(ns rdm-spec.regeneration.scopes
  "The Scopes component of the regeneration context
  (dhf/spec/regeneration/scopes.feature; glossary, \"Scope\"): `rdm-spec
  scopes A B` lists what a spec change touched between two cuts, so a
  cycle's scopes come from the spec, never from memory.

  A scope is {:component id}, the model's id (the kernel's components under
  :kernel/...). Between the cuts A and B (each a tag or commit the project
  holds; anything else cannot run), read at B's configuration:
    - a changed, added or removed file of the spec directory that a
      component's spec path names, <spec>.feature or its design prose
      <spec>.md, makes that component a scope (the model at B names it, else
      the model at A);
    - an event of the event catalogue that was added or removed, or whose
      entry changed in anything but its :description, makes its publisher
      and then every subscriber the model's relationships name for it (in
      their order) scopes; a change to an event's description alone reaches
      no component (spec-0340);
    - any other changed file of the spec directory (the glossary, the
      README, the model, the needs) is a shared file no component owns: a
      warning (scope-shared-file) names it for a person to judge its
      readers, and the task exits 0. The ledger, written only by the
      stamper, and the event catalogue, read above, are not shared files.
  Scopes are listed in the order one builder builds them: the kernel's
  first, in the model's order, then each publisher before the components
  its events reach, else in the order they were found (changed files in
  the model's order, then each changed event's publisher and subscribers).

  Result: {:scopes {:from A :to B :scopes [{:component id}] :shared [path]}}."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

;; ---------------------------------------------------------------------------
;; Pure

(defn- components [m]
  (concat (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x) comp (:components x) :when (map? comp)] comp)
          (filter map? (:kernel m))))

(defn owner-of
  "The id of the component whose spec path names the spec file `rel`
  (<spec-dir>/<spec>.feature or .md) in the model `m`, or nil."
  [m spec-dir rel]
  (let [sd (str (strip-slash spec-dir) "/")]
    (when (str/starts-with? rel sd)
      (let [sp (str/replace (subs rel (count sd)) #"\.(feature|md)$" "")]
        (when (re-find #"\.(feature|md)$" rel)
          (some #(when (= sp (:spec %)) (:id %)) (components m)))))))

(defn changed-events
  "The events of the catalogues `a` and `b` that were added, removed, or
  changed in anything but their :description, sorted."
  [a b]
  (let [a (if (map? a) a {}) b (if (map? b) b {})
        strip #(if (map? %) (dissoc % :description) %)]
    (vec (sort-by str (for [e (distinct (concat (keys a) (keys b)))
                            :when (not= (strip (get a e)) (strip (get b e)))]
                        e)))))

(defn event-scopes
  "The publisher of `event` (by the catalogue `cat`), then every subscriber
  the model's relationships name for it, in their order."
  [m cat event]
  (vec (distinct (remove nil? (cons (get-in cat [event :published-by])
                                    (for [r (:relationships m) :when (and (map? r) (= event (:event r)))] (:to r)))))))

(defn ordered
  "The scopes `ids` (in the order found) in the order a builder builds them:
  the kernel's first, in the model's order, then each publisher before the
  components its events reach."
  [m ids]
  (let [ids (vec (distinct ids))
        kernel-order (map :id (filter map? (:kernel m)))
        kernel (filter (set ids) kernel-order)
        kernel-rest (filter #(and (= "kernel" (namespace %)) (not (some #{%} kernel))) ids)
        others (vec (remove #(= "kernel" (namespace %)) ids))
        in? (set others)
        pubs (fn [id] (set (for [r (:relationships m) :when (and (map? r) (:event r) (= id (:to r)) (in? (:from r))
                                                               (not= id (:from r)))]
                             (:from r))))]
    (vec (concat kernel kernel-rest
                 (loop [left others done [] seen #{}]
                   (if (empty? left)
                     done
                     (let [nxt (or (first (filter #(every? seen (pubs %)) left)) (first left))]
                       (recur (vec (remove #{nxt} left)) (conj done nxt) (conj seen nxt)))))))))

;; ---------------------------------------------------------------------------
;; Reading the cuts

(defn- git [req & args]
  (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""} "git" (map str args)))

(defn- commit! [req rev]
  (let [r (git req "rev-parse" "--verify" "-q" (str rev "^{commit}"))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str rev " names no commit in " (:root req) ": scopes are listed between two cuts")))
    (str/trim (:out r))))

(defn- edn-at [req commit rel]
  (let [r (git req "show" (str commit ":" rel))]
    (when (zero? (:exit r))
      (try (edn/read-string (:out r))
           (catch Exception e (command/cannot-run! (str rel " at " commit " is not readable EDN: " (ex-message e))))))))

(defn scopes
  "The scopes stage: `rdm-spec scopes A B`."
  [req]
  (let [[a b] (:args req)
        ca (commit! req a)
        cb (commit! req b)
        cfg {:config (merge (:config req) (let [c (edn-at req cb command/config-file)] (when (map? c) c)))}
        spec-dir (strip-slash (command/config-value cfg :spec-dir))
        events-rel (command/config-value cfg :events)
        ledger-rel (command/config-value cfg :ledger)
        model-rel (command/config-value cfg :model)
        ma (or (edn-at req ca model-rel) {})
        mb (or (edn-at req cb model-rel) {})
        diff (git req "diff" "--name-only" "--no-renames" ca cb "--" spec-dir)
        _ (when-not (zero? (:exit diff)) (command/cannot-run! (str "git cannot compare " a " and " b ": " (str/trim (:err diff)))))
        changed (vec (sort (remove str/blank? (str/split-lines (:out diff)))))
        owned (for [rel changed :let [o (or (owner-of mb spec-dir rel) (owner-of ma spec-dir rel))] :when o] o)
        model-order (vec (map :id (components mb)))
        owned (sort-by #(let [i (.indexOf ^java.util.List model-order %)] (if (neg? i) Integer/MAX_VALUE i)) (distinct owned))
        cat-a (edn-at req ca events-rel)
        cat-b (edn-at req cb events-rel)
        evs (changed-events cat-a cat-b)
        ev-scopes (mapcat (fn [e] (event-scopes (if (seq (:relationships mb)) mb ma)
                                                (if (contains? cat-b e) cat-b cat-a) e))
                          evs)
        shared (vec (for [rel changed
                          :when (not (or (owner-of mb spec-dir rel) (owner-of ma spec-dir rel)
                                         (= rel events-rel) (= rel ledger-rel)))]
                      rel))
        found (ordered mb (concat owned ev-scopes))]
    {:result {:scopes {:from a :to b :scopes (mapv (fn [id] {:component id}) found) :shared shared}}
     :findings (vec (for [rel shared]
                      (command/warning rel "scope-shared-file"
                                       (str "changed between " a " and " b ", and no component owns it: a person judges its readers"))))}))
