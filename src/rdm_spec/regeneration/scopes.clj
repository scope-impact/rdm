(ns rdm-spec.regeneration.scopes
  "Component Scopes (regeneration context): lists what a spec change touched
  between two cuts, so a cycle's scopes come from the spec, never from
  memory: the components whose own spec files changed (every slice touched,
  under the slices layout, with every component it reaches), and for each
  event whose contract changed, its publishers and every subscriber. Listed
  in the order one builder builds them: the kernel first, then each
  publisher before its subscribers, else the model's order."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(defn show
  "The text of the project's file P at REV, or nil."
  [rev p]
  (let [r (project/git (project/root) "show" (str rev ":./" p))]
    (when (= 0 (:exit r)) (:out r))))

(defn edn-at [rev p] (when-let [t (show rev p)] (let [v (:value (project/read-edn-text t))] v)))

(defn config-at [rev]
  (when-let [c (edn-at rev project/config-file)] (when (map? c) (project/with-defaults c))))

(defn contract [e] (when (map? e) (select-keys e [:published-by :carries :shape])))

(defn comp-index [model] (into {} (map-indexed (fn [i c] [(:id c) i]) (project/components model))))

(defn build-order
  "Every component of MODEL in the order a builder builds them: the kernel
  first, then the rest with each event's publisher before its subscribers,
  ties in the model's order."
  [model]
  (let [comps (project/components model)
        kernel (filterv #(= :kernel (:context %)) comps)
        rest-c (filterv #(not= :kernel (:context %)) comps)
        ids (set (map :id rest-c))
        idx (comp-index model)
        edges (distinct (for [r (:relationships model)
                              :when (and (map? r) (:event r) (ids (:from r)) (ids (:to r)) (not= (:from r) (:to r)))]
                          [(:from r) (:to r)]))]
    (loop [order [] remaining (set ids)]
      (if (empty? remaining)
        (into (mapv :id kernel) order)
        (let [blocked (set (for [[a b] edges :when (and (remaining a) (remaining b))] b))
              ready (sort-by idx (remove blocked remaining))
              nxt (or (first ready) (first (sort-by idx remaining)))]
          (recur (conj order nxt) (disj remaining nxt)))))))

(defn order-ids
  "IDS in the build order of the models (B first, then A for components B no longer holds)."
  [ids model-b model-a]
  (let [ob (build-order model-b)
        oa (build-order model-a)
        pos (merge (into {} (map-indexed (fn [i id] [id (+ 100000 i)]) oa))
                   (into {} (map-indexed (fn [i id] [id i]) ob)))]
    (vec (sort-by #(get pos % 1000000) (distinct ids)))))

(defn changed-files [a b]
  (vec (remove str/blank? (str/split (project/git! (project/root) "diff" "--name-only" "-z" "--relative" a b "--" ".") #"\u0000"))))

(defn scopes-task [{:keys [args]}]
  (let [[a b] args]
    (doseq [r [a b]]
      (when-not (project/resolve-commit r)
        (project/cannot-run! (str r " names no commit of this repository: scopes reads two cuts"))))
    (let [cfg-b (or (config-at b) (project/cannot-run! (str "the cut " b " holds no readable " project/config-file)))
          cfg-a (or (config-at a) cfg-b)
          spec-b (first (project/specs cfg-b))
          spec-a (first (project/specs cfg-a))
          model-b (let [m (edn-at b (:model spec-b))] (if (map? m) m {}))
          model-a (let [m (edn-at a (:model spec-a))] (if (map? m) m {}))
          events-a (let [e (edn-at a (:events spec-a))] (if (map? e) e {}))
          events-b (let [e (edn-at b (:events spec-b))] (if (map? e) e {}))
          files (changed-files a b)
          specs-b (project/specs cfg-b)
          events-files (set (concat (map :events specs-b) (map :events (project/specs cfg-a))))
          spec-of (fn [f] (first (filter #(str/starts-with? f (str (:dir %) "/")) specs-b)))
          denied? (fn [f] (some #(and (string? %) (or (= f (str/replace % #"/+$" "")) (str/starts-with? f (str (str/replace % #"/+$" "") "/"))))
                                (concat (:implementation cfg-b)
                                        (get-in cfg-b [:clean-room :deny :measurements])
                                        (get-in cfg-b [:clean-room :deny :generated]))))
          slices? (project/slices? cfg-b)
          classified (for [f files]
                       (let [sp (spec-of f)]
                         (cond
                           (events-files f) {:events true}
                           (and sp slices? (project/slice-of (:dir sp) f)) {:slice (project/slice-of (:dir sp) f)}
                           (and sp (or (project/owner cfg-b sp model-b f) (project/owner cfg-a spec-a model-a f)))
                           {:component (:id (or (project/owner cfg-b sp model-b f) (project/owner cfg-a spec-a model-a f)))}
                           (denied? f) {:ignored true}
                           :else {:shared f})))
          event-comps (for [e (distinct (concat (keys events-a) (keys events-b)))
                            :when (not= (contract (get events-a e)) (contract (get events-b e)))
                            id (distinct (concat (keep #(get-in % [e :published-by]) [events-a events-b])
                                                 (for [m [model-b model-a] r (:relationships m) :when (and (map? r) (= e (:event r)))] (:to r))))
                            :when (keyword? id)]
                        id)
          slices (sort (distinct (keep :slice classified)))
          slice-comps (fn [s] (distinct (for [[cfg model rev] [[cfg-b model-b b] [cfg-a model-a a]]
                                              sp (project/specs cfg)
                                              f (str/split-lines (str (:out (project/git (project/toplevel) "ls-tree" "-r" "--name-only" (project/tree-ish rev)))))
                                              :when (and (str/ends-with? f ".feature") (= s (project/slice-of (:dir sp) f)))
                                              :let [o (project/owner cfg sp model f)] :when o]
                                          (:id o))))
          slice-scopes (loop [ss slices seen #{} out []]
                         (if (empty? ss)
                           out
                           (let [cs (remove seen (order-ids (slice-comps (first ss)) model-b model-a))]
                             (recur (rest ss) (into seen cs) (conj out {:slice (first ss) :components (vec cs)})))))
          in-slices (set (mapcat :components slice-scopes))
          comp-scopes (for [id (order-ids (concat (keep :component classified) event-comps) model-b model-a)
                            :when (not (in-slices id))]
                        {:component id})
          shared (vec (distinct (keep :shared classified)))]
      (project/report {:scopes {:from a :to b :scopes (vec (concat slice-scopes comp-scopes)) :shared shared}}
                      []
                      (for [f shared]
                        (project/warning f "shared-file-changed"
                                         (str f " changed and no component owns it: a person must judge whose readers it reaches")))))))
