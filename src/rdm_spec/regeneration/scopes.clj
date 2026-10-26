(ns rdm-spec.regeneration.scopes
  "Scopes (dhf/spec/regeneration/scopes.feature): what a spec change touched
  between two cuts. A component whose own feature file or design prose
  changed is a scope; an event whose contract (its publisher, what it carries
  and their shape) changed reaches its publishers and every subscriber the
  models name for it. Listed in the order one builder builds them: the kernel
  first, then each publisher before its subscribers, else the model's order."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.regeneration.cut :as cut]))

(defn contract [decl] (select-keys decl [:published-by :carries :shape]))

(defn changed-events
  "The events whose contract differs between two catalogues, added and removed ones included."
  [a b]
  (set (for [e (into (set (keys a)) (keys b))
             :when (not= (some-> (get a e) contract) (some-> (get b e) contract))]
         e)))

(defn- reach
  "Every node reachable from n over the edges."
  [edges n]
  (loop [seen #{} todo [n]]
    (if-let [x (first todo)]
      (let [nxt (for [[a b] edges :when (and (= a x) (not (seen b)))] b)]
        (recur (into seen nxt) (concat (rest todo) nxt)))
      seen)))

(defn order
  "Orders the items: an item after every other item it is reachable from over
  the edges (a publisher before its subscribers), ties in the order given by
  `model-order` (the kernel's components first)."
  [items model-order edges]
  (let [idx (fn [x] (let [i (.indexOf ^java.util.List (vec model-order) x)] (if (neg? i) Integer/MAX_VALUE i)))
        items (distinct items)
        before (into {} (for [x items] [x (set (filter #(and (not= % x) (contains? (reach edges %) x)
                                                               (not (contains? (reach edges x) %)))
                                                         items))]))]
    (loop [placed [] left (set items)]
      (if (empty? left)
        placed
        (let [ready (filter #(every? (set placed) (before %)) left)
              pick (first (sort-by idx (if (seq ready) ready left)))]
          (recur (conj placed pick) (disj left pick)))))))

(defn- read-edn-at [root rev path]
  (try (cmd/read-edn (cmd/read-at root rev path)) (catch Exception _ nil)))

(defn- component-order [m]
  (vec (concat (for [k (:kernel m)] (:id k))
               (for [c (:containers m) cx (:contexts c) k (:components cx)] (:id k)))))

(defn- spec-owners [m spec-dir]
  (into {} (for [k (concat (:kernel m) (for [c (:containers m) cx (:contexts c) k (:components cx)] k))
                 :when (:spec k)
                 ext [".feature" ".md"]]
             [(str spec-dir "/" (:spec k) ext) (:id k)])))

(defn stage [{:keys [root config args]}]
  (let [[a b] args
        rev (fn [x] (or (when (cut/commit-of root x) x)
                        (cmd/cannot-run! (str x " names no commit: scopes compares two cuts"))))
        a (rev a) b (rev b)
        cfg (or (read-edn-at root b "dhf/harness/config.edn") config)
        spec-dir (:spec-dir cfg "dhf/spec")
        events-path (:events cfg "dhf/spec/events.edn")
        model-path (:model cfg "dhf/spec/model.edn")
        ma (read-edn-at root a model-path)
        mb (read-edn-at root b model-path)
        owners (merge (spec-owners ma spec-dir) (spec-owners mb spec-dir))
        changed (remove str/blank? (str/split-lines (cmd/git! root "diff" "--name-only" a b "--" spec-dir)))
        own-scopes (keep owners changed)
        events (changed-events (or (read-edn-at root a events-path) {}) (or (read-edn-at root b events-path) {}))
        ea (or (read-edn-at root a events-path) {})
        eb (or (read-edn-at root b events-path) {})
        rels (concat (:relationships ma) (:relationships mb))
        event-scopes (for [e events
                           x (concat (keep #(get-in % [e :published-by]) [ea eb])
                                     (for [r rels :when (= e (:event r))] (:to r)))]
                       x)
        shared (remove #(or (owners %) (= % events-path)) changed)
        edges (distinct (for [r rels :when (:event r)] [(:from r) (:to r)]))
        model-order (distinct (concat (component-order mb) (component-order ma)))
        kernel? (fn [id] (= "kernel" (namespace id)))
        items (concat own-scopes event-scopes)
        ordered (order items (concat (filter kernel? model-order) (remove kernel? model-order)) edges)]
    {:result {:scopes {:from a :to b :scopes (mapv (fn [c] {:component c}) ordered) :shared (vec shared)}}
     :warnings (vec (for [f shared]
                      (cmd/finding f nil "shared-file"
                                   (str (last (str/split f #"/")) " changed between " a " and " b
                                        ", and no component owns it: it is a shared file whose readers a person must judge"))))}))
