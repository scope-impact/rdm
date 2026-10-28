(ns rdm-spec.regeneration.scopes
  "Lists the scopes a spec change touched between two cuts, in the order a
  builder builds them: the kernel first, then each publisher before its
  subscribers, else the model's order (dhf/spec/06-regenerate/scopes.feature);
  within the project's folder only (11); a touched slice whole (13)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]))

(defn owner-of
  "The component a changed spec file (relative to the spec folder) belongs to, under the contexts layout."
  [_layout model rel]
  (let [base (str/replace rel #"\.(feature|md)$" "")]
    (when (re-find #"\.(feature|md)$" rel)
      (some #(when (= base (:spec %)) (:id %)) (parser/components model)))))

(defn slice-of [rel]
  (let [parts (str/split rel #"/")]
    (when (and (> (count parts) 1) (parser/slice-folder? (first parts))) (first parts))))

(defn in-folder
  "The paths under the project's folder pre, relative to it."
  [pre paths]
  (vec (for [p paths :when (str/starts-with? p pre)] (subs p (count pre)))))

(defn contract [e] (select-keys e [:published-by :carries :shape]))

(defn changed-events
  "The events whose contract (publisher, carried keys and their shape) changed, or that were added or removed."
  [from to]
  (set (for [k (distinct (concat (keys from) (keys to)))
             :when (not= (some-> (get from k) contract) (some-> (get to k) contract))]
         k)))

(defn model-index [model]
  (into {} (map-indexed (fn [i c] [(:id c) i]) (parser/components model))))

(defn build-order
  "The components ids, the kernel's first, then publishers before subscribers, ties in the model's order."
  [model ids]
  (let [idx (model-index model)
        kernel (set (map :id (:kernel model)))
        order-key (fn [id] [(if (kernel id) 0 1) (get idx id 100000) (str id)])
        ids (set ids)
        edges (set (for [r (:relationships model)
                         :when (and (:event r) (ids (:from r)) (ids (:to r)) (not= (:from r) (:to r))
                                    (not (kernel (:from r))) (not (kernel (:to r))))]
                     [(:from r) (:to r)]))]
    (loop [left ids acc []]
      (if (empty? left)
        acc
        (let [ready (filter (fn [id] (or (kernel id) (not-any? (fn [[a b]] (and (= b id) (left a))) edges))) left)
              pick (first (sort-by order-key (if (seq ready) ready left)))]
          (recur (disj left pick) (conj acc pick)))))))

(defn- read-at [root rev path] (cut/read-edn-at root rev path))

(defn scopes [run]
  (let [root (:root run)
        [from to] (:args run)]
    (doseq [c [from to]]
      (when-not (cut/tag-commit root c) (project/cannot-run! (str c " is not a cut: scopes compares two cuts, commits tagged spec-N"))))
    (let [pre (cut/prefix root)
          diff (project/git root "diff" "--name-only" from to "--" ".")
          _ (when-not (zero? (:exit diff)) (project/cannot-run! (str "git cannot compare " from " and " to ": " (project/one-line (:err diff)))))
          full (remove str/blank? (str/split-lines (:out diff)))
          changed (if (str/blank? pre) (vec full) (in-folder pre full))
          cfg (project/with-defaults (or (read-at root to project/config-path) (:cfg run)))
          sd (:spec-dir cfg)
          layout (:spec-layout cfg :contexts)
          model-to (or (read-at root to (:model cfg)) {})
          model-from (or (read-at root from (:model cfg)) {})
          events-path (:events cfg)
          rels-of (fn [p] (when (str/starts-with? p (str sd "/")) (subs p (inc (count sd)))))
          ev-changed (when (some #{events-path} changed)
                       (changed-events (or (read-at root from events-path) {}) (or (read-at root to events-path) {})))
          ev-from (or (read-at root from events-path) {})
          ev-to (or (read-at root to events-path) {})
          event-comps (set (for [e ev-changed
                                 id (concat (keep :published-by [(get ev-from e) (get ev-to e)])
                                            (for [m [model-to model-from] r (:relationships m) :when (= e (:event r))] (:to r)))]
                             id))
          spec-files (for [p changed :let [r (rels-of p)] :when (and r (not= p events-path))] [p r])
          comp-hits (when-not (= :slices layout)
                      (for [[p r] spec-files :let [o (or (owner-of layout model-to r) (owner-of layout model-from r))] :when o] [p o]))
          slices (when (= :slices layout) (sort (distinct (keep (fn [[_ r]] (slice-of r)) spec-files))))
          owned (set (concat (map first comp-hits)
                             (when (= :slices layout) (for [[p r] spec-files :when (slice-of r)] p))
                             (when (seq ev-changed) [events-path])))
          impl (fn [p] (some #(or (= p %) (str/starts-with? p (str % "/"))) (:implementation cfg)))
          deny (get-in cfg [:clean-room :deny])
          denied (fn [p] (some #(or (= p %) (str/starts-with? p (str % "/"))) (concat (:measurements deny) (:generated deny))))
          ;; an events.edn whose contracts did not change reaches no component, and is no shared file either
          shared (vec (for [p changed :when (and (not (owned p)) (not (impl p)) (not (denied p)) (not= p events-path))] p))
          rel-files-to (let [es (cut/ls-tree root to)]
                         (for [e es :let [r (rels-of (:path e))] :when r] r))
          slice-scopes (loop [ss slices seen #{} acc []]
                         (if (empty? ss)
                           acc
                           (let [s (first ss)
                                 comps (remove seen (build-order model-to (parser/slice-reach model-to rel-files-to s)))]
                             (recur (rest ss) (into seen comps) (conj acc {:slice s :components (vec comps)})))))
          in-slices (set (mapcat :components slice-scopes))
          comp-ids (remove in-slices (distinct (concat (map second comp-hits) event-comps)))
          comp-scopes (for [id (build-order (merge-with (fn [a b] (if (sequential? a) (vec (distinct (concat a b))) a)) model-to model-from) comp-ids)]
                        {:component id})]
      {:warnings (vec (for [p shared]
                        (project/warning p nil "scope-shared-file"
                                         (str p " changed and no component owns it: a shared file whose readers a person must judge"))))
       :result {:scopes {:from from :to to :scopes (vec (concat slice-scopes comp-scopes)) :shared shared}}})))
