(ns steps.architecture-views
  "Step definitions for dhf/spec/architecture/views.feature: each Given makes
  a copy of the room's spec (its committed views removed) whose model holds
  the views the scenario names, `rdm-spec views` or `rdm-spec check` runs it
  (the shared When), and each Then reads what was written under the
  configured :views-dir. What a view holds is read off its diagram source and
  compared with what the model says it must hold, worked out here
  independently of the implementation."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

;; --- helpers -------------------------------------------------------------------

(defn- views-dir [dir] (fs/file dir (or (:views-dir (s/room-config)) "dhf/views")))

(defn- fresh-project!
  "A copy of the room's spec with no views generated yet."
  []
  (let [dir (s/copy-of-room!)]
    (fs/delete-tree (views-dir dir))
    dir))

(defn- with-views
  "A fresh project whose model holds only the views `(pick views)` returns."
  [ctx pick]
  (s/needs-built! 'rdm-spec.architecture.views)
  (let [dir (fresh-project!)
        m (a/read-model dir)
        vs (vec (pick (:views m)))]
    (a/write-model! dir (assoc m :views vs))
    (assoc ctx :dir dir :views vs)))

(defn- sha256-hex [^bytes bs]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") bs))))

(defn- model-bytes-hash [dir] (sha256-hex (fs/read-all-bytes (fs/file dir a/model-rel))))

(defn stack-formats
  "The diagram source's extension and the rendered formats dhf/harness/stack.md
  names, read off its row for the diagram source and render:
  {:source \"mmd\" :rendered [\"svg\"]}."
  []
  (let [stack (slurp (str (fs/file s/room-root "dhf/harness/stack.md")))
        row (first (filter #(str/includes? (str/lower-case %) "diagram source") (str/split-lines stack)))
        exts (vec (distinct (map second (re-seq #"dhf/views/<key>\.(\w+)" (or row "")))))]
    (s/check! (>= (count exts) 2) (str "dhf/harness/stack.md names no source and render format: " (pr-str row)))
    {:source (first exts) :rendered (vec (rest exts))}))

(defn- the-run [ctx]
  (or (:run ctx) (s/fail! "no command was run")))

;; --- reading a diagram source -------------------------------------------------------

(defn- alias-of [id]
  (str/replace (if (namespace id) (str (namespace id) "__" (name id)) (name id)) #"[^A-Za-z0-9_]" "_"))

(defn- parse-source
  "A diagram source read as {:inside #{alias} :outside #{alias} :external
  #{alias drawn as external} :boundaries [alias] :rels [[from to label]]}:
  an element is inside when it is declared within a boundary."
  [text]
  (reduce
   (fn [acc line]
     (let [l (str/trim line)]
       (cond
         (re-find #"^(\w*Boundary)\((\w+)" l)
         (-> acc (update :depth inc) (update :boundaries conj (second (re-find #"^\w*Boundary\((\w+)" l))))
         (= "}" l) (update acc :depth dec)
         (re-find #"^Rel\w*\(" l)
         (let [[_ f t label] (re-find #"^Rel\w*\((\w+),\s*(\w+),\s*\"([^\"]*)\"" l)]
           (update acc :rels conj [f t label]))
         (re-find #"^(Person|System|Container|ContainerDb|Component)(_Ext)?\((\w+)," l)
         (let [[_ _ ext al] (re-find #"^(Person|System|Container|ContainerDb|Component)(_Ext)?\((\w+)," l)]
           (cond-> (update acc (if (pos? (:depth acc)) :inside :outside) conj al)
             ext (update :external conj al)))
         :else acc)))
   {:depth 0 :inside #{} :outside #{} :external #{} :boundaries [] :rels []}
   (str/split-lines text)))

(defn- source-of [ctx v]
  (let [f (fs/file (views-dir (:dir ctx)) (str (:key v) "." (:source (stack-formats))))]
    (s/check! (fs/exists? f) (str "no diagram source for the view " (:key v) "\n" (s/show (the-run ctx))))
    (parse-source (slurp (str f)))))

;; --- what the model says a view holds ---------------------------------------------

(defn- parents
  "{id {:container id :context id}} for every context and component."
  [m]
  (into {}
        (for [c (:containers m)
              ctx (:contexts c)
              [id cx] (cons [(:id ctx) nil] (map (fn [k] [(:id k) (:id ctx)]) (:components ctx)))]
          [id {:container (:id c) :context cx}])))

(defn- lifted-pairs
  "The set of [from to] aliases the model's relationships join once each end
  is drawn as `(lift id)`; ends lifting to nil or to the same element left out."
  [m lift]
  (set (for [r (:relationships m)
             :let [f (lift (:from r)) t (lift (:to r))]
             :when (and f t (not= f t))]
         [(alias-of f) (alias-of t)])))

(defn- outer-ids [m] (set (concat (map :id (:people m)) (map :id (:external m)))))

(defn- check-holds! [ctx expect]
  (let [v (first (:views ctx))
        got (source-of ctx v)
        drawn (into (:inside got) (:outside got))
        show (str "\nthe view " (:key v) " holds " (pr-str (dissoc got :depth)))]
    (when-let [i (:inside expect)]
      (s/check! (= i (:inside got)) (str "inside its boundary it should hold exactly " (pr-str (sort i)) show)))
    (when-let [o (:outside expect)]
      (s/check! (= o (:outside got)) (str "outside a boundary it should hold exactly " (pr-str (sort o)) show)))
    (when-let [e (:external expect)]
      (s/check! (= e (:external got)) (str "it should draw exactly " (pr-str (sort e)) " as external" show)))
    (when-let [b (:boundaries expect)]
      (s/check! (= b (:boundaries got)) (str "its boundaries should be " (pr-str b) show)))
    (doseq [[f t] (:rels got)]
      (s/check! (and (drawn f) (drawn t)) (str "the relationship " f " -> " t " joins an element it does not draw" show)))
    (when-let [p (:pairs expect)]
      (s/check! (= p (set (map (fn [[f t]] [f t]) (:rels got))))
                (str "its relationships should join exactly " (pr-str (sort p)) show)))
    (when-let [r (:rels expect)]
      (s/check! (= r (:rels got)) (str "its relationships should be, in order, " (pr-str r) show)))
    ctx))

(defn- expect-context [m]
  (let [sys (get-in m [:system :id])
        outer (outer-ids m)
        lift (fn [id] (if (outer id) id sys))]
    ;; a context view draws no boundary: the system stands among the others
    {:inside #{}
     :outside (set (map alias-of (cons sys outer)))
     :external (set (map (comp alias-of :id) (:external m)))
     :pairs (lifted-pairs m lift)}))

(defn- expect-container [m]
  (let [outer (outer-ids m)
        cs (set (map :id (:containers m)))
        ps (parents m)
        runs (:id (or (first (filter #(= :runs (:kind %)) (:containers m))) (first (:containers m))))
        kernel (set (map :id (:kernel m)))
        lift (fn [id] (cond (or (outer id) (cs id)) id
                            (ps id) (:container (ps id))
                            (kernel id) runs))]
    {:inside (set (map alias-of cs))
     :outside (set (map alias-of outer))
     :external (set (map (comp alias-of :id) (:external m)))
     :boundaries [(str "boundary__" (alias-of (get-in m [:system :id])))]
     :pairs (lifted-pairs m lift)}))

(defn- expect-component [m v]
  (let [cx (:context v)
        ps (parents m)
        inside (set (for [[id {:keys [context]}] ps :when (= cx context)] id))
        rels (filter #(or (inside (:from %)) (inside (:to %))) (:relationships m))
        others (set (remove inside (mapcat (juxt :from :to) rels)))]
    {:inside (set (map alias-of inside))
     :outside (set (map alias-of others))
     :external (set (map alias-of others))
     :boundaries [(str "boundary__" (alias-of cx))]
     :pairs (set (map (fn [r] [(alias-of (:from r)) (alias-of (:to r))]) rels))}))

(defn- expect-dynamic [v]
  {:inside #{}
   :outside (set (map alias-of (mapcat (fn [[f t]] [f t]) (:steps v))))
   :rels (mapv (fn [[f t d]] [(alias-of f) (alias-of t) d]) (:steps v))})

;; --- Every view in the model is generated and rendered -------------------------------

(defstep #"a model with a context view, a container view and two component views"
  (fn [ctx]
    (with-views ctx (fn [vs]
                      (concat (take 1 (filter #(= :context (:level %)) vs))
                              (take 1 (filter #(= :container (:level %)) vs))
                              (take 2 (filter #(= :component (:level %)) vs)))))))

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (let [r (the-run ctx)
          {:keys [source rendered]} (stack-formats)
          dir (views-dir (:dir ctx))
          keys* (map :key (:views ctx))
          expected (set (for [k keys* ext (cons source rendered)] (str k "." ext)))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (= 4 (count keys*)) "the model does not hold four views")
      (s/check! (= expected (set (map (comp str fs/file-name) (fs/list-dir dir))))
                (str "dhf/views holds " (pr-str (sort (map (comp str fs/file-name) (fs/list-dir dir))))
                     ", not exactly " (pr-str (sort expected))))
      (doseq [k keys*]
        (let [src (slurp (str (fs/file dir (str k "." source))))]
          (s/check! (re-find #"(?m)^C4(Context|Container|Component|Dynamic)$" src)
                    (str "the source of " k " is no C4 diagram source")))
        (doseq [ext rendered
                :let [text (slurp (str (fs/file dir (str k "." ext))))]]
          (when (= "svg" ext)
            (s/check! (re-find #"<svg[\s>]" text) (str k ".svg is not an SVG"))
            (s/check! (str/includes? text "</svg>") (str k ".svg is not a whole SVG"))))))))

(defstep #"each output records the SHA-256 of model.edn's bytes, as lowercase hex, on its first line"
  (fn [ctx]
    (let [{:keys [source rendered]} (stack-formats)
          h (model-bytes-hash (:dir ctx))]
      (s/check! (re-matches #"[0-9a-f]{64}" h) "the model's hash is not lowercase hex")
      (doseq [v (:views ctx)
              ext (cons source rendered)
              :let [f (fs/file (views-dir (:dir ctx)) (str (:key v) "." ext))
                    first-line (first (str/split-lines (slurp (str f))))]]
        (s/check! (str/includes? first-line h)
                  (str (fs/file-name f) " does not record " h " on its first line: " first-line))))))

;; --- A view older than the model fails the check -------------------------------------
;; The same Given is in dhf/spec/publishing/site.feature (@spec-0099), whose
;; steps reuse this definition: :dir is the project, :stale-view the view.

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (s/needs-built! 'rdm-spec.architecture.views)
    (let [dir (fresh-project!)
          r (s/rdm-spec! dir "views")
          _ (s/check! (= 0 (:exit r)) (str "rdm-spec views failed\n" (s/show r)))
          {:keys [source rendered]} (stack-formats)
          older (sha256-hex (.getBytes "an older model" "UTF-8"))]
      (doseq [ext (cons source rendered)
              :let [f (str (fs/file (views-dir dir) (str "context." ext)))
                    text (slurp f)]]
        ;; every hash the output records replaced by an older model's
        (spit f (str/replace text #"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])" older)))
      (assoc ctx :dir dir :stale-view "context"))))

(defstep #"it fails and names the stale view"
  (fn [ctx]
    (let [r (the-run ctx)
          stale (a/rule-lines r "views-stale")]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (seq stale) (str "no view is named stale\n" (s/show r)))
      (s/check! (every? #(str/includes? % "view \"context\"") stale)
                (str "a view other than context is named stale\n" (s/show r)))
      (s/check! (some #(str/starts-with? % "dhf/views/context.") stale)
                (str "the stale view's file is not named\n" (s/show r))))))

;; --- A view of a level holds exactly its elements -----------------------------------

(defstep #"a model with a view of level (context|container|component|dynamic)"
  (fn [ctx level]
    (with-views ctx (fn [vs] (take 1 (filter #(= (keyword level) (:level %)) vs))))))

(defn- the-view-holds [ctx]
  (let [r (the-run ctx)]
    (s/check! (= 0 (:exit r)) (s/show r))
    (s/check! (= 1 (count (:views ctx))) "the model does not hold one view")
    (a/read-model (:dir ctx))))

(defstep #"the view holds the system, every person and every external system, and the relationships between them"
  (fn [ctx]
    (let [m (the-view-holds ctx)]
      (check-holds! ctx (expect-context m)))))

(defstep #"the view holds every container, every person and every external system, and the relationships between them"
  (fn [ctx]
    (let [m (the-view-holds ctx)]
      (check-holds! ctx (expect-container m)))))

(defstep #"the view holds one context's components inside its boundary, and the elements outside it they relate to, drawn as external"
  (fn [ctx]
    (let [m (the-view-holds ctx)]
      (check-holds! ctx (expect-component m (first (:views ctx)))))))

(defstep #"the view holds the elements its steps name, and its steps in order"
  (fn [ctx]
    (the-view-holds ctx)
    (check-holds! ctx (expect-dynamic (first (:views ctx))))))
