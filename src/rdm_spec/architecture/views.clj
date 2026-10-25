(ns rdm-spec.architecture.views
  "The Views component of the architecture context
  (dhf/spec/architecture/views.feature): generates every view of the model
  as diagram source and renders it, and checks that the views are fresh.

  `views` (the task `rdm-spec views`) writes, for each view of the model,
  <views-dir>/<key>.mmd (Mermaid C4) and <key>.svg (rendered by mmdr), each
  starting with a comment holding `model-sha256: <hash>`, and removes the
  generated views of keys the model no longer has. `check` (a stage of
  `rdm-spec check`, and publish's gate) finds every view fresh: both outputs
  record the current model hash.

  What each level draws:
    context    the system, every person and every external system; a
               relationship of anything inside the system is drawn as the system's
    container  every container inside the system's boundary, every person and
               every external system; a component's relationship is its container's
    component  one context's components inside its boundary, and every element a
               relationship joins to them, drawn as external
    dynamic    the elements its steps name, and its steps in order

  Rules: view-missing (a view of the model with no output), view-stale (an
  output recording another model hash), view-render-failed (the renderer
  failed on a view)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.kernel.command :as command]))

(def renderer "mmdr")

;; ---------------------------------------------------------------------------
;; What a view holds

(defn diagram-id
  "The diagram id of a model id."
  [k]
  (-> (if (keyword? k) (subs (str k) 1) (str k)) (str/replace "/" "__") (str/replace #"[^A-Za-z0-9_]" "_")))

(defn- label [{:keys [description event]}]
  (str/trim (str description (when event (str " " (name event))))))

(defn- lift-rels
  "The relationships with each end lifted by `lift` (nil: dropped), deduped
  by ends, self-relationships dropped: [[from to label]]."
  [rels lift]
  (let [out (for [r rels
                  :let [a (lift (:from r)) b (lift (:to r))]
                  :when (and a b (not= a b))]
              [a b (label r)])]
    (vec (vals (reduce (fn [acc [a b l]] (if (contains? acc [a b]) acc (assoc acc [a b] [a b l])))
                       (array-map) out)))))

(defn- ordered-distinct [xs]
  (reduce (fn [acc x] (if (some #{x} acc) acc (conj acc x))) [] xs))

(defn view-contents
  "What the view `v` of the model `m` draws:
  {:boundary {:id :name :kind} or nil, :inside [element], :elements [element]
   :rels [[from-id to-id label]]}, an element {:id :kind :el :ext?}."
  [m v]
  (let [els (model/elements m)
        by-id (into {} (map (juxt :id identity)) els)
        sys-id (get-in m [:system :id])
        people (filter #(= :person (:kind %)) els)
        externals (filter #(= :external (:kind %)) els)
        containers (filter #(= :container (:kind %)) els)
        runs (or (:id (first (filter #(= :runs (get-in % [:el :kind])) containers))) (:id (first containers)))
        rels (filter map? (:relationships m))]
    (case (:level v)
      :context
      (let [shown (set (map :id (concat people externals)))
            lift (fn [id] (cond (contains? shown id) id
                                (= id sys-id) sys-id
                                (contains? by-id id) sys-id))
            elements (concat [(get by-id sys-id)] people externals)]
        {:boundary nil :inside [] :elements (vec (remove nil? elements)) :rels (lift-rels rels lift)})

      :container
      (let [shown (set (map :id (concat people externals containers)))
            lift (fn [id]
                   (let [e (get by-id id)]
                     (cond (contains? shown id) id
                           (#{:context :component} (:kind e)) (:container e)
                           (= :kernel (:kind e)) runs)))]
        {:boundary {:id sys-id :name (get-in m [:system :name]) :kind :system}
         :inside (vec containers)
         :elements (vec (concat containers people externals))
         :rels (lift-rels rels lift)})

      :component
      (let [x (get by-id (:context v))
            inside (vec (filter #(and (= :component (:kind %)) (= (:context v) (:context %))) els))
            in? (set (map :id inside))
            rs (filter #(or (in? (:from %)) (in? (:to %))) rels)
            outside (->> rs
                         (mapcat (fn [r] [(:from r) (:to r)]))
                         (remove in?)
                         ordered-distinct
                         (keep by-id)
                         (map #(assoc % :ext? true)))]
        {:boundary {:id (:context v) :name (get-in x [:el :name] (some-> (:context v) name)) :kind :context}
         :inside inside
         :elements (vec (concat inside outside))
         :rels (vec (for [r rs] [(:from r) (:to r) (label r)]))})

      :dynamic
      (let [steps (filter sequential? (:steps v))
            ids (ordered-distinct (mapcat #(take 2 %) steps))]
        {:boundary nil :inside []
         :elements (vec (keep by-id ids))
         :rels (vec (for [[a b l] steps] [a b (str l)]))})

      {:boundary nil :inside [] :elements [] :rels []})))

;; ---------------------------------------------------------------------------
;; Mermaid C4

(defn- q [s] (str "\"" (str/replace (str s) "\"" "'") "\""))

(defn- element-line [{:keys [id kind el ext?]}]
  (let [f (case kind
            :system (if ext? "System_Ext" "System")
            :person (if ext? "Person_Ext" "Person")
            :external "System_Ext"
            :container (str (if (= :stores (:kind el)) "ContainerDb" "Container") (when ext? "_Ext"))
            (:component :kernel) (str "Component" (when ext? "_Ext"))
            :context (str "Container" (when ext? "_Ext"))
            "System_Ext")
        tech? (#{:container :component :kernel :context} kind)]
    (str f "(" (diagram-id id) ", " (q (:name el))
         (when tech? (str ", " (q (or (:technology el) ""))))
         ", " (q (:description el)) ")")))

(def ^:private diagram-kind
  {:context "C4Context" :container "C4Container" :component "C4Component" :dynamic "C4Dynamic"})

(defn header [hash model-rel k]
  (str "model-sha256: " hash "; generated by rdm-spec views from " model-rel ", view " (q k) "; do not edit"))

(defn mermaid
  "The Mermaid C4 source of the view `v`, its first line the model hash."
  [m v hash model-rel]
  (let [{:keys [boundary inside elements rels]} (view-contents m v)
        in-ids (set (map :id inside))
        outside (remove #(in-ids (:id %)) elements)]
    (str (str/join "\n"
                   (concat
                    [(str "%% " (header hash model-rel (:key v)))
                     (diagram-kind (:level v) "C4Context")
                     (str "title " (str/replace (str (:description v)) "\n" " "))]
                    (when boundary
                      (concat [(str (if (= :system (:kind boundary))
                                      (str "System_Boundary(boundary__" (diagram-id (:id boundary)) ", " (q (:name boundary)) ")")
                                      (str "Boundary(boundary__" (diagram-id (:id boundary)) ", " (q (:name boundary))
                                           ", \"bounded context\")"))
                                    " {")]
                              (map #(str "  " (element-line %)) inside)
                              ["}"]))
                    (map element-line outside)
                    (for [[a b l] rels] (str "Rel(" (diagram-id a) ", " (diagram-id b) ", " (q l) ")"))))
         "\n")))

;; ---------------------------------------------------------------------------
;; Freshness

(def hash-re #"model-sha256: ([0-9a-f]{64})")

(defn recorded-hash
  "The model hash the first line of the file `f` records, or nil."
  [f]
  (when (fs/regular-file? f)
    (with-open [r (clojure.java.io/reader (str f))]
      (some->> (first (line-seq r)) (re-find hash-re) second))))

(defn- view-files [views-dir k] [(str views-dir "/" k ".mmd") (str views-dir "/" k ".svg")])

(defn freshness-findings
  "A finding for each view of `m` whose outputs are missing or record
  another hash than `hash`."
  [root views-dir m hash]
  (vec (for [v (filter map? (:views m))
             :let [k (:key v)
                   files (view-files views-dir k)
                   recs (map #(recorded-hash (command/path root %)) files)
                   missing (keep (fn [[f r]] (when (and (nil? r) (not (fs/exists? (command/path root f)))) f))
                                 (map vector files recs))]
             :when (or (seq missing) (some #(not= hash %) recs))]
         (if (seq missing)
           (command/finding (first missing) "view-missing"
                            (str "the view " (q k) " has not been generated (" (str/join ", " missing)
                                 " missing): run \"rdm-spec views\""))
           (let [stale (first (filter #(not= hash %) recs))]
             (command/finding (first files) 1 "view-stale"
                              (str "the view " (q k) " is stale: it records model-sha256 " (or stale "nothing")
                                   ", the model's is " hash ": run \"rdm-spec views\"")))))))

(defn check
  "The views' freshness stage (of check, and publish's gate)."
  [req]
  (let [{:keys [model findings]} (model/load! req)
        views-dir (str/replace (command/config-value req :views-dir) #"/+$" "")]
    (if-not model
      {:result {:views {:fresh? false}} :findings findings}
      (let [h (model/model-hash model)
            fs* (freshness-findings (:root req) views-dir model h)]
        {:result {:views {:fresh? (empty? fs*) :model-sha256 h}}
         :findings fs*}))))

;; ---------------------------------------------------------------------------
;; Generating

(defn- render!
  "Renders `mmd-text` to SVG with the renderer: {:svg text} or {:error why}."
  [mmd-text]
  (let [dir (fs/create-temp-dir {:prefix "rdm-spec-views-"})]
    (try
      (let [in (fs/file dir "view.mmd") out (fs/file dir "view.svg")]
        (spit (str in) mmd-text)
        (let [r (p/shell {:out :string :err :string :continue true :in ""}
                         renderer "-i" (str in) "-o" (str out))]
          (if (and (zero? (:exit r)) (fs/exists? out))
            {:svg (slurp (str out))}
            {:error (str/trim (str (:err r) (:out r)))})))
      (finally (fs/delete-tree dir)))))

(defn views
  "The views task: generates and renders every view of the model."
  [req]
  (let [{:keys [model findings]} (model/load! req)]
    (if-not model
      {:result {:views {:written []}} :findings findings}
      (do
        (when-not (fs/which renderer)
          (command/cannot-run! (str "the view renderer " renderer " is not installed, so no view was rendered"
                                    " (dhf/harness/stack.md names it)")))
        (let [h (model/model-hash model)
              model-rel (command/config-value req :model)
              views-dir (str/replace (command/config-value req :views-dir) #"/+$" "")
              dir (command/path req views-dir)
              vs (filter map? (:views model))
              keys* (set (map :key vs))
              _ (fs/create-dirs dir)
              results (for [v vs
                            :let [k (:key v)
                                  src (mermaid model v h model-rel)
                                  [mmd-f svg-f] (view-files views-dir k)
                                  _ (spit (str (command/path req mmd-f)) src)
                                  {:keys [svg error]} (render! src)]]
                        (if error
                          {:finding (command/finding mmd-f "view-render-failed"
                                                     (str "the renderer " renderer " failed on the view " (q k) ": " error))}
                          (do (spit (str (command/path req svg-f))
                                    (str "<!-- " (header h model-rel k) " -->\n"
                                         (str/replace svg #"^\s*<\?xml[^>]*\?>\s*" "")))
                              {:written [mmd-f svg-f]})))
              results (vec results)]
          ;; generated views of keys the model no longer has
          (doseq [f (concat (fs/glob dir "*.mmd") (fs/glob dir "*.svg"))
                  :let [k (str/replace (str (fs/file-name f)) #"\.(mmd|svg)$" "")]
                  :when (and (not (keys* k)) (recorded-hash f))]
            (fs/delete f))
          {:result {:views {:written (vec (mapcat :written results)) :model-sha256 h}}
           :findings (vec (keep :finding results))})))))
