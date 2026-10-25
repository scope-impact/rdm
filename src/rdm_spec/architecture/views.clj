(ns rdm-spec.architecture.views
  "Views (dhf/spec/architecture/views.feature): generates every view of the
  one model (dhf/spec/model.edn) as diagram source, renders it beside the
  source, and, as a stage of `rdm-spec check`, fails a view older than the
  model.

  What each level of view holds (@spec-0198):
    context    the system, every person and every external system, and the
               relationships between them; an end inside the system (a
               container, a context, a component, kernel code) is drawn as
               the system
    container  every container inside the system's boundary, every person
               and every external system, and the relationships between
               them; an end inside a container is drawn as its container
               (kernel code as the first container that runs)
    component  one context's components inside its boundary, and the
               elements outside it they relate to, drawn as external; the
               relationships with an end inside the boundary
    dynamic    the elements its steps name, and its steps in order

  The hash (@spec-0017): the first line of every output (the source and each
  render) records the SHA-256 of model.edn's bytes, lowercase hex, as
  `model-sha256: HEX`. The same line also records the hash of the model's
  value (`model-value-sha256: HEX`, rdm-spec.architecture.model/model-hash),
  so that reformatting the model, which changes its bytes and not its value,
  leaves the views fresh (@spec-0077). A view is stale when neither recorded
  hash is the current model's (@spec-0018); its outputs are named under the
  rule views-stale, a missing output under views-missing."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.architecture.model :as model]
            [rdm-spec.kernel.command :as command]))

(def renderer "mmdr")
(def source-ext "mmd")
(def render-exts
  "The formats every view is rendered to (dhf/harness/stack.md)."
  ["svg"])

;; ---------------------------------------------------------------------------
;; What a view holds

(defn- by-id [m] (into {} (map (juxt :id identity)) (model/elements m)))

(defn- first-runs-container [m]
  (or (first (filter #(= :runs (:kind %)) (:containers m))) (first (:containers m))))

(defn- dedupe-rels
  "Relationships with self-loops dropped and duplicates (same ends and label)
  kept once, in model order."
  [rels]
  (let [seen (volatile! #{})]
    (vec (for [r rels
               :when (not= (:from r) (:to r))
               :let [k [(:from r) (:to r) (:description r) (:event r)]]
               :when (not (@seen k))]
           (do (vswap! seen conj k) r)))))

(defn- lifted-rels
  "The model's relationships with each end replaced by `(lift id)`; a
  relationship with an end that lifts to nil is left out."
  [m lift]
  (let [all (for [r (:relationships m)
                  :let [f (lift (:from r)) t (lift (:to r))]
                  :when (and f t)]
              (assoc r :from f :to t ::direct? (and (= f (:from r)) (= t (:to r)))))
        direct-pairs (set (for [r all :when (::direct? r)] [(:from r) (:to r)]))
        ;; a pair joined directly keeps its own arrows; a pair joined only by
        ;; lifted relationships is drawn once, with the first of them
        kept (loop [rs all seen #{} acc []]
               (if-let [[r & more] (seq rs)]
                 (let [pair [(:from r) (:to r)]]
                   (cond (::direct? r) (recur more seen (conj acc r))
                         (or (direct-pairs pair) (seen pair)) (recur more seen acc)
                         :else (recur more (conj seen pair) (conj acc r))))
                 acc))]
    (mapv #(dissoc % ::direct?) (dedupe-rels kept))))

(defn- context-content [m]
  (let [els (by-id m)
        sys (first (filter #(= :system (:type %)) (model/elements m)))
        outside (filterv #(#{:person :external} (:type %)) (model/elements m))
        outside-ids (set (map :id outside))
        lift (fn [id]
               (let [e (get els id)]
                 (cond (outside-ids id) id
                       (nil? e) nil
                       (#{:system :container :context :component :kernel} (:type e)) (:id sys))))]
    {:boundary nil
     :inside (if sys [sys] [])
     :outside outside
     :rels (if sys (lifted-rels m lift) [])}))

(defn- container-content [m]
  (let [els (model/elements m)
        sys (first (filter #(= :system (:type %)) els))
        containers (filterv #(= :container (:type %)) els)
        outside (filterv #(#{:person :external} (:type %)) els)
        runs (:id (first-runs-container m))
        lift (fn [id]
               (let [e (first (filter #(= id (:id %)) (remove #(= :system (:type %)) els)))]
                 (case (:type e)
                   (:person :external :container) id
                   (:context :component) (:container e)
                   :kernel runs
                   nil)))]
    {:boundary sys
     :inside containers
     :outside outside
     :rels (lifted-rels m lift)}))

(defn- component-content [m v]
  (let [els (model/elements m)
        ctx (first (filter #(and (= :context (:type %)) (= (:context v) (:id %))) els))
        inside (filterv #(and (= :component (:type %)) (= (:context v) (:context %))) els)
        inside-ids (set (map :id inside))
        rels (dedupe-rels (filter #(or (inside-ids (:from %)) (inside-ids (:to %))) (:relationships m)))
        other-ids (set (remove inside-ids (mapcat (juxt :from :to) rels)))
        ;; the pun: an id shared by the system and its container is drawn as the container
        outside (filterv #(and (other-ids (:id %))
                               (not (and (= :system (:type %))
                                         (some (fn [e] (and (= :container (:type e)) (= (:id e) (:id %)))) els))))
                         els)]
    {:boundary ctx
     :inside inside
     :outside outside
     :rels rels}))

(defn- dynamic-content [m v]
  (let [els (model/elements m)
        pick (fn [id] (or (first (filter #(and (= id (:id %)) (not= :system (:type %))) els))
                          (first (filter #(= id (:id %)) els))))
        ids (distinct (mapcat (fn [[f t]] [f t]) (:steps v)))]
    {:boundary nil
     :inside (vec (keep pick ids))
     :outside []
     :rels (vec (for [[f t d] (:steps v)] {:from f :to t :description d}))}))

(defn view-content
  "What view `v` of model `m` holds: {:boundary element-or-nil :inside
  [element] :outside [element] :rels [{:from :to :description}]}; elements
  as rdm-spec.architecture.model/elements gives them. The boundary (the
  system of a container view, the context of a component view) is drawn
  around :inside and is not counted among its elements."
  [m v]
  (case (:level v)
    :context (context-content m)
    :container (container-content m)
    :component (component-content m v)
    :dynamic (dynamic-content m v)
    {:boundary nil :inside [] :outside [] :rels []}))

(defn element-count
  "How many elements view `v` draws (its boundary not counted)."
  [m v]
  (let [{:keys [inside outside]} (view-content m v)]
    (+ (count inside) (count outside))))

;; ---------------------------------------------------------------------------
;; Diagram source

(defn alias-of
  "A diagram alias for an id: `:architecture/views` is architecture__views."
  [id]
  (let [s (if (keyword? id)
            (str (when (namespace id) (str (namespace id) "__")) (name id))
            (str id))]
    (str/replace s #"[^A-Za-z0-9_]" "_")))

(defn- q [s] (str "\"" (str/replace (str (or s "")) #"[\"\r\n]" (fn [c] (if (= c "\"") "'" " "))) "\""))

(defn- element-line [{:keys [type id el]} external?]
  (let [a (alias-of id)
        x (if external? "_Ext" "")]
    (case type
      :person (str "Person" x "(" a ", " (q (:name el)) ", " (q (:description el)) ")")
      (:system :external) (str "System" (if (= :external type) "_Ext" x) "(" a ", " (q (:name el)) ", "
                               (q (:description el)) ")")
      :container (str (if (= :stores (:kind el)) "ContainerDb" "Container") x
                      "(" a ", " (q (:name el)) ", " (q (:technology el)) ", " (q (:description el)) ")")
      :context (str "Container" x "(" a ", " (q (:name el)) ", \"bounded context\", " (q (:description el)) ")")
      (:component :kernel) (str "Component" x "(" a ", " (q (:name el)) ", "
                                (q (if (= :kernel type) "kernel" "")) ", " (q (:description el)) ")"))))

(defn- rel-line [{:keys [from to description event]}]
  (str "Rel(" (alias-of from) ", " (alias-of to) ", "
       (q (str description (when event (str " " (name event))))) ")"))

(def ^:private diagram-kind
  {:context "C4Context" :container "C4Container" :component "C4Component" :dynamic "C4Dynamic"})

(defn hash-line-text
  "What the first line of every output records: the SHA-256 of the model's
  bytes and of its value, as lowercase hex."
  [bytes-hash value-hash]
  (str "model-sha256: " bytes-hash " model-value-sha256: " value-hash))

(defn source
  "The diagram source of view `v`, its first line recording the hashes."
  [m v {:keys [bytes-hash value-hash model-file]}]
  (let [{:keys [boundary inside outside rels]} (view-content m v)
        level (:level v)
        ext? (= :component level)
        inner (map #(element-line % false) inside)
        block (cond
                (and boundary (= :container level))
                (concat [(str "System_Boundary(boundary__" (alias-of (:id boundary)) ", "
                              (q (get-in boundary [:el :name])) ") {")]
                        (map #(str "  " %) inner) ["}"])
                (and boundary (= :component level))
                (concat [(str "Boundary(boundary__" (alias-of (:id boundary)) ", "
                              (q (get-in boundary [:el :name])) ", \"bounded context\") {")]
                        (map #(str "  " %) inner) ["}"])
                :else inner)]
    (str (str/join "\n"
                   (concat [(str "%% " (hash-line-text bytes-hash value-hash)
                                 "; generated by rdm-spec views from " model-file ", view " (pr-str (:key v))
                                 "; do not edit")
                            (diagram-kind level)
                            (str "title " (str/replace (str (:description v)) #"[\r\n]" " "))]
                           block
                           (map #(element-line % ext?) outside)
                           (map rel-line rels)))
         "\n")))

;; ---------------------------------------------------------------------------
;; Hashes

(defn- hex [^bytes d] (apply str (map #(format "%02x" (bit-and % 0xff)) d)))

(defn bytes-hash
  "The SHA-256 of `bs` (a byte array), lowercase hex."
  [^bytes bs]
  (hex (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)))

(defn first-line [text] (first (str/split-lines (str text))))

(defn recorded-hashes
  "The hashes the first line of an output records: {:bytes hex :value hex},
  either nil when absent."
  [text]
  (let [l (or (first-line text) "")]
    {:bytes (some->> (re-find #"model-sha256:\s*([0-9a-f]{64})" l) second)
     :value (some->> (re-find #"model-value-sha256:\s*([0-9a-f]{64})" l) second)}))

(defn fresh?
  "True when the output `text` records the current model's bytes or value hash."
  [text {:keys [bytes-hash value-hash]}]
  (let [{:keys [bytes value]} (recorded-hashes text)]
    (boolean (or (and bytes (= bytes bytes-hash)) (and value (= value value-hash))))))

;; ---------------------------------------------------------------------------
;; Rendering

(defn- render!
  "Renders `src-file` to `out-file`; nil, or the renderer's complaint."
  [src-file out-file ext]
  (let [r (try (p/shell {:out :string :err :string :continue true}
                        renderer "-i" (str src-file) "-o" (str out-file) "-e" ext)
               (catch java.io.IOException e
                 (command/cannot-run! (str "the diagram renderer " renderer " is not installed: " (ex-message e)))))]
    (when (or (not (zero? (:exit r))) (not (fs/exists? out-file)))
      (str/trim (str (:err r) " " (:out r))))))

(defn- stamp-render
  "The rendered output with the hash line put first."
  [text hash-text k]
  (let [text (str/replace-first (str text) #"^\s*<\?xml[^>]*\?>\s*" "")]
    (str "<!-- " hash-text "; generated by rdm-spec views, view " (pr-str k) "; do not edit -->\n" text)))

(defn- generated? [f]
  (try (str/includes? (or (first-line (slurp (str f))) "") "generated by rdm-spec views")
       (catch Exception _ false)))

;; ---------------------------------------------------------------------------
;; The tasks

(defn- views-dir [req]
  (str/replace (or (get-in req [:config :views-dir]) "dhf/views") #"/+$" ""))

(defn- hashes [req read]
  (let [f (command/path req (:file read))]
    {:bytes-hash (bytes-hash (fs/read-all-bytes f))
     :value-hash (:hash read)
     :model-file (:file read)}))

(defn views
  "The views task: every view of the model generated to <views-dir>/<key>.mmd
  and rendered beside it. A refused model writes nothing. Generated outputs of
  views no longer in the model are removed."
  [req]
  (let [{:keys [model findings] :as read} (model/read-model req)]
    (if-not model
      {:result {:model-read false :views []} :findings findings}
      (do
        (when-not (fs/which renderer)
          (command/cannot-run! (str "the diagram renderer " renderer " is not on PATH: no view was rendered")))
        (let [h (hashes req read)
              dir (views-dir req)
              ddir (command/path req dir)
              _ (fs/create-dirs ddir)
              hash-text (hash-line-text (:bytes-hash h) (:value-hash h))
              out (vec
                   (for [v (:views model)
                         :let [k (:key v)
                               src (fs/file ddir (str k "." source-ext))
                               _ (spit src (source model v h))
                               rendered (for [ext render-exts
                                              :let [o (fs/file ddir (str k "." ext))
                                                    tmp (fs/file ddir (str "." k "." ext ".tmp"))
                                                    _ (fs/delete-if-exists tmp)
                                                    err (render! src tmp ext)]]
                                          (if err
                                            (do (fs/delete-if-exists tmp)
                                                {:finding (command/finding (command/rel req src) nil "views-render"
                                                                           (str (model/describe-view v) " could not be rendered to "
                                                                                ext ": " err))})
                                            (do (spit o (stamp-render (slurp tmp) hash-text k))
                                                (fs/delete tmp)
                                                {:file (command/rel req o)})))]]
                     {:key k :level (:level v)
                      :source (command/rel req src)
                      :rendered (vec (keep :file rendered))
                      :findings (vec (keep :finding rendered))}))
              keys* (set (map :key (:views model)))
              removed (vec (for [f (sort (map str (fs/list-dir ddir)))
                                 :let [n (fs/file-name f)
                                       [_ k ext] (re-matches #"(.+)\.([^.]+)" n)]
                                 :when (and k ((set (cons source-ext render-exts)) ext)
                                            (not (keys* k)) (generated? f))]
                             (do (fs/delete f) (command/rel req f))))]
          {:result {:model-read true
                    :model-sha256 (:bytes-hash h)
                    :model-value-sha256 (:value-hash h)
                    :views (mapv #(dissoc % :findings) out)
                    :removed removed}
           :findings (vec (mapcat :findings out))})))))

(defn stale-findings
  "A finding for every view of the model whose outputs are missing
  (views-missing) or record no hash of the current model (views-stale)."
  [req model h]
  (let [dir (views-dir req)]
    (vec
     (for [v (:views model)
           ext (cons source-ext render-exts)
           :let [f (command/path req (str dir "/" (:key v) "." ext))
                 rel (command/rel req f)]
           :let [x (cond
                     (not (fs/exists? f))
                     (command/finding rel nil "views-missing"
                                      (str (model/describe-view v) " has no " ext " output; run rdm-spec views"))
                     (not (fresh? (slurp (str f)) h))
                     (let [{:keys [bytes]} (recorded-hashes (slurp (str f)))]
                       (command/finding rel 1 "views-stale"
                                        (str (model/describe-view v) " is older than the model: it records "
                                             (or bytes "no model hash") ", the model's is " (:bytes-hash h)
                                             "; run rdm-spec views"))))]
           :when x]
       x))))

(defn check
  "The views' stage of `rdm-spec check`: every view's outputs exist and record
  the current model's hash. A refused model is the lint's to report."
  [req]
  (let [{:keys [model] :as read} (model/read-model req)]
    (when model
      (let [fs* (stale-findings req model (hashes req read))]
        {:result {:views-fresh (empty? fs*)}
         :findings fs*}))))
