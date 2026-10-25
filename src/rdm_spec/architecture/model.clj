(ns rdm-spec.architecture.model
  "Model (dhf/spec/architecture/model.feature): reads the C4 model (the
  configured :model, dhf/spec/model.edn) and the event catalogue (the
  configured :events): the one source every view, every lint rule and every
  component's ownership of the spec is read from.

  The model is read whole or not at all. Reading it finds, each with its file
  and line (exit 1 through the task that reads it):

    model-edn           the file is not one EDN value: an unclosed bracket (named
                        at the line it opens), a closing bracket that closes
                        nothing (named at its own line), anything else the
                        reader refuses
    model-shape         a key the model's shape does not declare, a value of the
                        wrong kind, a required key missing
    model-duplicate-id  an id used by two elements anywhere in the model (or a
                        view key used twice)
    model-unknown-id    a relationship, a dynamic view's step or a component
                        view naming an element that does not exist
    model-catalogue     the configured event catalogue is missing or not an EDN map

  The shape (the model file's own header comment):
    :system        {:id :name :description}
    :people        [{:id :name :description :actor #{:human :agent}}]
    :external      [{:id :name :description}]
    :containers    [{:id :name :description :technology :kind
                     :contexts [{:id :name :description
                                 :components [{:id :name :description :spec}]}]}]
    :kernel        [{:id :name :description :spec}]
    :relationships [{:from :to :description :event}]
    :views         [{:key :level :description :context :steps}]

  Model hash (glossary): the SHA-256, lowercase hex, of the model read as EDN
  and printed back with every map's keys and every set's elements sorted by
  their printed form, so reformatting the file keeps it.

  What a view holds (dhf/spec/architecture/views.feature), as data
  (`view-content`): its elements, each drawn inside or outside a boundary,
  and its relationships, so the lint can count what the views draw."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The model hash

(defn- printed
  "`x` printed as EDN, namespaced maps written out, no metadata."
  [x]
  (binding [*print-namespace-maps* false *print-meta* false *print-length* nil *print-level* nil]
    (pr-str x)))

(defn- by-printed [a b] (compare (printed a) (printed b)))

(defn canonical
  "`x` with every map's keys and every set's elements sorted by their printed form."
  [x]
  (cond
    (map? x) (into (sorted-map-by by-printed) (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    (set? x) (into (sorted-set-by by-printed) (map canonical) x)
    (vector? x) (mapv canonical x)
    (seq? x) (apply list (map canonical x))
    :else x))

(defn- hex [^bytes d] (apply str (map #(format "%02x" (bit-and % 0xff)) d)))

(defn model-hash
  "The model hash of the model value `m` (glossary: Model hash)."
  [m]
  (hex (.digest (java.security.MessageDigest/getInstance "SHA-256")
                (.getBytes ^String (printed (canonical m)) "UTF-8"))))

(defn recorded-hash
  "The model hash the first line of a view's output records, or nil."
  [text]
  (some->> (first (str/split-lines (str text)))
           (re-find #"model-sha256:\s*([0-9a-f]{64})(?![0-9a-f])")
           second))

;; ---------------------------------------------------------------------------
;; Reading the text: where a bracket goes wrong

(def ^:private closer-of {\( \) \[ \] \{ \}})
(def ^:private opener-of {\) \( \] \[ \} \{})

(defn brackets
  "Every bracket of the EDN text, outside strings, comments and character
  literals: [{:ch \\{ :line n :open? bool :shown \"#{\"}]."
  [text]
  (let [n (count text)]
    (loop [i 0 line 1 acc []]
      (if (>= i n)
        acc
        (let [c (.charAt ^String text i)]
          (cond
            (= c \newline) (recur (inc i) (inc line) acc)
            (= c \;) (let [j (or (str/index-of text "\n" i) n)] (recur j line acc))
            (= c \") (let [[j line] (loop [j (inc i) line line]
                                      (cond (>= j n) [j line]
                                            (= \\ (.charAt ^String text j)) (recur (+ j 2) (if (and (< (inc j) n) (= \newline (.charAt ^String text (inc j)))) (inc line) line))
                                            (= \" (.charAt ^String text j)) [(inc j) line]
                                            (= \newline (.charAt ^String text j)) (recur (inc j) (inc line))
                                            :else (recur (inc j) line)))]
                       (recur j line acc))
            (= c \\) (recur (min n (+ i 2)) line acc)
            (and (= c \#) (< (inc i) n) (= \{ (.charAt ^String text (inc i))))
            (recur (+ i 2) line (conj acc {:ch \{ :line line :open? true :shown "#{"}))
            (contains? closer-of c) (recur (inc i) line (conj acc {:ch c :line line :open? true :shown (str c)}))
            (contains? opener-of c) (recur (inc i) line (conj acc {:ch c :line line :open? false :shown (str c)}))
            :else (recur (inc i) line acc)))))))

(defn- greedy-errors
  "How many bracket errors the rest of the text holds, read greedily: a
  mismatch pops the opener, a stray closer is skipped, every opener left at
  the end counts."
  [events stack]
  (loop [[e & more :as es] events stack stack errs 0]
    (if (empty? es)
      (+ errs (count stack))
      (if (:open? e)
        (recur more (conj stack e) errs)
        (cond (empty? stack) (recur more stack (inc errs))
              (= (:ch e) (closer-of (:ch (peek stack)))) (recur more (pop stack) errs)
              :else (recur more (pop stack) (inc errs)))))))

(defn bracket-problem
  "The first bracket problem of the EDN text, or nil:
  {:kind :unclosed :line n :shown \"{\"} for an opener never closed (at the
  line it opens) or {:kind :stray :line n :shown \"}\"} for a closer that
  closes nothing (at its own line). On a closer that does not match the open
  bracket, the reading that leaves the rest of the text whole wins: the open
  bracket was never closed, or the closer is one too many."
  [text]
  (loop [[e & more :as es] (brackets text) stack []]
    (if (empty? es)
      (when (seq stack) (let [o (peek stack)] {:kind :unclosed :line (:line o) :shown (:shown o)}))
      (if (:open? e)
        (recur more (conj stack e))
        (cond
          (empty? stack) {:kind :stray :line (:line e) :shown (:shown e)}
          (= (:ch e) (closer-of (:ch (peek stack)))) (recur more (pop stack))
          :else (let [unclosed (greedy-errors es (pop stack))
                      stray (greedy-errors more stack)
                      o (peek stack)]
                  (if (<= unclosed stray)
                    {:kind :unclosed :line (:line o) :shown (:shown o)}
                    {:kind :stray :line (:line e) :shown (:shown e)})))))))

(defn- read-all
  "Every EDN form of `text`: {:forms [..]} or {:error msg :line n}."
  [text]
  (let [rdr (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))]
    (try (loop [acc []]
           (let [x (edn/read {:eof ::eof} rdr)]
             (if (= ::eof x) {:forms acc} (recur (conj acc x)))))
         (catch Exception e {:error (ex-message e) :line (.getLineNumber rdr)}))))

(defn parse-text
  "Reads the model text: {:model m} (the value, with :row metadata on its
  maps and vectors where the reader can give it) or {:findings [..]}, each
  finding at `file` and its line."
  [file text]
  (let [{:keys [forms error line]} (read-all text)
        problem (when (or error (not= 1 (count forms))) (bracket-problem text))]
    (cond
      problem
      {:findings [(command/finding file (:line problem) "model-edn"
                                   (case (:kind problem)
                                     :unclosed (str "the model is not EDN: the \"" (:shown problem) "\" opened on line "
                                                    (:line problem) " is never closed")
                                     :stray (str "the model is not EDN: the \"" (:shown problem) "\" on line "
                                                 (:line problem) " closes nothing")))]}
      error
      {:findings [(command/finding file line "model-edn" (str "the model is not EDN: " error))]}
      (empty? forms)
      {:findings [(command/finding file nil "model-edn" "the model is empty: it holds no EDN value")]}
      (< 1 (count forms))
      {:findings [(command/finding file nil "model-edn" (str "the model holds " (count forms) " EDN values, not one"))]}
      :else
      (let [m (first forms)
            located (try (edamame/parse-string text {:location? (fn [_] true)})
                         (catch Exception _ nil))]
        {:model (if (= located m) located m)}))))

;; ---------------------------------------------------------------------------
;; The shape

(def top-keys [:system :people :external :containers :kernel :relationships :views])

(def shape
  "The keys each kind of entry may carry."
  {:system #{:id :name :description}
   :person #{:id :name :description :actor}
   :external #{:id :name :description}
   :container #{:id :name :description :technology :kind :contexts}
   :context #{:id :name :description :components}
   :component #{:id :name :description :spec}
   :kernel #{:id :name :description :spec}
   :relationship #{:from :to :description :event}
   :view #{:key :level :description :context :steps}})

(def levels #{:context :container :component :dynamic})

(defn line-of [x] (:row (meta x)))

(defn kind-name [kind]
  (case kind :person "person" :external "external system" :container "container" :context "bounded context"
        :component "component" :kernel "kernel component" :system "system" :relationship "relationship"
        :view "view" (name kind)))

(defn describe-rel [r] (str "relationship " (pr-str (:from r)) " -> " (pr-str (:to r))))

(defn describe
  "How a finding names an entry of the model."
  [kind x]
  (case kind
    :relationship (describe-rel x)
    :view (str "view " (pr-str (:key x)))
    (str (kind-name kind) " " (if (contains? x :id) (pr-str (:id x)) (pr-str (:name x))))))

(defn- entries
  "Every entry of the model whose shape is checked: [[kind entry] ...], in the
  model's order (contexts and components only when their parents are maps)."
  [m]
  (let [maps (fn [k] (let [v (get m k)] (if (sequential? v) (filter map? v) [])))]
    (concat
     (when (map? (:system m)) [[:system (:system m)]])
     (for [p (maps :people)] [:person p])
     (for [e (maps :external)] [:external e])
     (mapcat (fn [c]
               (cons [:container c]
                     (when (sequential? (:contexts c))
                       (mapcat (fn [ctx]
                                 (cons [:context ctx]
                                       (when (sequential? (:components ctx))
                                         (for [comp (:components ctx) :when (map? comp)] [:component comp]))))
                               (filter map? (:contexts c))))))
             (maps :containers))
     (for [k (maps :kernel)] [:kernel k])
     (for [r (maps :relationships)] [:relationship r])
     (for [v (maps :views)] [:view v]))))

(defn- shape-findings [file m]
  (let [f (fn [x msg] (command/finding file (or (line-of x) (line-of m)) "model-shape" msg))
        undeclared (fn [x msg] (assoc (f x msg) ::undeclared-key true))]
    (if-not (map? m)
      [(command/finding file 1 "model-shape" "the model is not a map")]
      (concat
       (for [k (keys m) :when (not (some #{k} top-keys))]
         (undeclared m (str "the model carries the key " (pr-str k) ", which the model's shape does not declare; it declares "
                   (str/join ", " (map pr-str top-keys)))))
       (when-not (map? (:system m)) [(f m "the model has no :system map")])
       (for [k [:people :external :containers :kernel :relationships :views]
             :let [v (get m k)]
             :when (and (some? v) (not (and (sequential? v) (every? map? v))))]
         (f m (str "the model's " (pr-str k) " is not a vector of maps")))
       (for [c (when (sequential? (:containers m)) (filter map? (:containers m)))
             [k label] [[:contexts "bounded contexts"]]
             :let [v (get c k)]
             :when (and (some? v) (not (and (sequential? v) (every? map? v))))]
         (f c (str "the " (describe :container c) "'s " label " (" (pr-str k) ") are not a vector of maps")))
       (for [c (when (sequential? (:containers m)) (filter map? (:containers m)))
             ctx (when (sequential? (:contexts c)) (filter map? (:contexts c)))
             :let [v (:components ctx)]
             :when (and (some? v) (not (and (sequential? v) (every? map? v))))]
         (f ctx (str "the " (describe :context ctx) "'s :components are not a vector of maps")))
       (mapcat
        (fn [[kind x]]
          (concat
           (for [k (keys x) :when (not (contains? (shape kind) k))]
             (undeclared x (str "the " (describe kind x) " carries the key " (pr-str k)
                       ", which the model's shape does not declare; a " (kind-name kind) " may carry "
                       (str/join ", " (map pr-str (sort-by name (shape kind)))))))
           (case kind
             :relationship
             (for [k [:from :to] :when (not (keyword? (get x k)))]
               (f x (str "the " (describe kind x) " has no " (pr-str k) " id")))
             :view
             (concat
              (when-not (and (string? (:key x)) (not (str/blank? (:key x))))
                [(f x (str "the " (describe kind x) " has no :key string"))])
              (when-not (levels (:level x))
                [(f x (str "the " (describe kind x) " has the level " (pr-str (:level x)) ", not one of "
                           (str/join ", " (map pr-str (sort-by name levels)))))])
              (when (and (= :component (:level x)) (not (keyword? (:context x))))
                [(f x (str "the component " (describe kind x) " names no bounded context (:context)"))])
              (when (and (= :dynamic (:level x))
                         (not (and (sequential? (:steps x))
                                   (every? #(and (sequential? %) (= 3 (count %)) (keyword? (first %))
                                                 (keyword? (second %)) (string? (nth % 2)))
                                           (:steps x)))))
                [(f x (str "the dynamic " (describe kind x) " needs :steps, a vector of [from to \"description\"]"))]))
             ;; elements
             (concat
              (when-not (keyword? (:id x))
                [(f x (str "a " (kind-name kind) " " (pr-str (:name x)) " has no keyword :id"))])
              (when-not (string? (:name x))
                [(f x (str "the " (describe kind x) " has no :name string"))])
              (when (and (contains? x :description) (not (string? (:description x))))
                [(f x (str "the " (describe kind x) "'s :description is not a string"))])
              (when (and (= kind :person) (contains? x :actor)
                         (not (and (set? (:actor x)) (every? #{:human :agent} (:actor x)))))
                [(f x (str "the " (describe kind x) "'s :actor is not a set of :human and :agent"))])
              (when (and (#{:component :kernel} kind) (contains? x :spec) (not (string? (:spec x))))
                [(f x (str "the " (describe kind x) "'s :spec is not a string"))])))))
        (entries m))))))

;; ---------------------------------------------------------------------------
;; Elements

(defn elements
  "Every element of a well-shaped model, in the model's order:
  [{:id :kind :name :description :entry :container :context :line}]; a
  component's :container and :context are its parents' ids, a context's
  :container its container's."
  [m]
  (vec
   (concat
    (when (map? (:system m)) [{:id (get-in m [:system :id]) :kind :system :entry (:system m)}])
    (for [p (:people m)] {:id (:id p) :kind :person :entry p})
    (for [e (:external m)] {:id (:id e) :kind :external :entry e})
    (mapcat (fn [c]
              (cons {:id (:id c) :kind :container :entry c}
                    (mapcat (fn [ctx]
                              (cons {:id (:id ctx) :kind :context :entry ctx :container (:id c)}
                                    (for [comp (:components ctx)]
                                      {:id (:id comp) :kind :component :entry comp
                                       :container (:id c) :context (:id ctx)})))
                            (:contexts c))))
            (:containers m))
    (for [k (:kernel m)] {:id (:id k) :kind :kernel :entry k}))))

(defn- complete [el]
  (let [e (:entry el)]
    (assoc el :name (:name e) :description (:description e) :line (line-of e))))

(defn element-index
  "{id element} of every element of `m` (the first, when an id is used twice)."
  [m]
  (reduce (fn [acc el] (if (contains? acc (:id el)) acc (assoc acc (:id el) (complete el))))
          {} (elements m)))

(defn- duplicate-findings [file m]
  (let [els (map complete (elements m))
        by-id (group-by :id (filter #(keyword? (:id %)) els))
        views (filter map? (:views m))]
    (concat
     (for [[id es] by-id :when (< 1 (count es))
           :let [[a b] es]]
       (command/finding file (or (:line b) (:line a)) "model-duplicate-id"
                        (str "the id " (pr-str id) " is used twice: by the " (kind-name (:kind a)) " "
                             (pr-str (:name a)) (when (:line a) (str " (line " (:line a) ")"))
                             " and by the " (kind-name (:kind b)) " " (pr-str (:name b))
                             (when (:line b) (str " (line " (:line b) ")"))
                             (when (< 2 (count es)) (str ", and " (- (count es) 2) " more")))))
     (for [[k vs] (group-by :key views) :when (and k (< 1 (count vs)))]
       (command/finding file (line-of (second vs)) "model-duplicate-id"
                        (str "the view key " (pr-str k) " is used by " (count vs) " views"))))))

(defn- unknown-findings [file m]
  (let [idx (element-index m)
        contexts (set (keep #(when (= :context (:kind %)) (:id %)) (vals idx)))
        missing (fn [id] (not (contains? idx id)))]
    (concat
     (for [r (:relationships m)
           end [:from :to]
           :let [id (get r end)]
           :when (missing id)]
       (command/finding file (line-of r) "model-unknown-id"
                        (str "the " (describe-rel r) " names " (pr-str id) ", and no element of the model has that id")))
     (for [v (:views m)
           :when (= :dynamic (:level v))
           [i [from to _]] (map-indexed vector (:steps v))
           id [from to]
           :when (missing id)]
       (command/finding file (line-of v) "model-unknown-id"
                        (str "step " (inc i) " of the view " (pr-str (:key v)) " names " (pr-str id)
                             ", and no element of the model has that id")))
     (for [v (:views m)
           :when (and (= :component (:level v)) (not (contexts (:context v))))]
       (command/finding file (line-of v) "model-unknown-id"
                        (str "the view " (pr-str (:key v)) " shows the bounded context " (pr-str (:context v))
                             ", and the model has no bounded context with that id"))))))

(defn check-model
  "The findings of a model value `m` read from `file`: its shape, its ids and
  the elements its relationships and views name."
  [file m]
  (let [shape-fs (vec (shape-findings file m))
        clean (mapv #(dissoc % ::undeclared-key) shape-fs)]
    ;; a key the shape does not declare leaves the rest readable; any other
    ;; shape finding stops the reading there
    (if (every? ::undeclared-key shape-fs)
      (vec (concat clean (duplicate-findings file m) (unknown-findings file m)))
      clean)))

;; ---------------------------------------------------------------------------
;; Ownership of the spec

(defn feature-path
  "The feature file a component's :spec names, under `spec-dir`."
  [spec-dir spec]
  (str spec-dir "/" spec ".feature"))

(defn owners
  "Every component and kernel component of `m` with the feature file it
  owns: [{:id :kind :spec :file}] (:file nil when it names no :spec)."
  [m spec-dir]
  (vec (for [el (elements m)
             :when (#{:component :kernel} (:kind el))
             :let [spec (get-in el [:entry :spec])]]
         {:id (:id el) :kind (:kind el) :spec spec :line (line-of (:entry el))
          :file (when (and (string? spec) (not (str/blank? spec))) (feature-path spec-dir spec))})))

;; ---------------------------------------------------------------------------
;; What a view holds

(defn- runs-container [m]
  (first (filter #(= :runs (:kind %)) (:containers m))))

(defn- rel-label [r]
  (let [d (str (:description r))
        ev (some-> (:event r) name)]
    (if (and ev (not (str/includes? d ev))) (str/trim (str d " " ev)) d)))

(defn- lifted-rels
  "The model's relationships with each end lifted by `lift` (nil drops the
  relationship), self-relationships dropped, one per pair: a relationship
  between the elements themselves before one lifted onto them."
  [m lift]
  (let [rs (for [r (:relationships m)
                 :let [f (lift (:from r)) t (lift (:to r))]
                 :when (and f t (not= f t))]
             {:from f :to t :label (rel-label r)
              :direct? (and (= f (:from r)) (= t (:to r)))})
        firsts (reduce (fn [acc r]
                         (let [k [(:from r) (:to r)] seen (get acc k)]
                           (if (or (nil? seen) (and (:direct? r) (not (:direct? seen))))
                             (assoc acc k r) acc)))
                       {} rs)
        order (distinct (map (juxt :from :to) rs))]
    (mapv #(dissoc (get firsts %) :direct?) order)))

(defn view-content
  "What the view `v` of model `m` holds:
  {:level :title :boundary {:id :name :kind :system|:context} or nil
   :elements [{:id :kind :name :description :technology :stores? :inside? :external?}]
   :relationships [{:from :to :label}]}.
    context    the system, every person and every external system; relationships
               lifted onto the system
    container  every container (inside the system's boundary), every person and
               every external system; relationships lifted onto the containers
    component  the context's components inside its boundary, and every element
               outside it they relate to, drawn as external, in the model's order
    dynamic    the elements the steps name, in order of first naming, and the
               steps in order"
  [m v]
  (let [idx (element-index m)
        sys (get-in m [:system :id])
        el (fn [id & {:as extra}]
             (let [e (get idx id)]
               (merge {:id id :kind (:kind e) :name (or (:name e) (name id)) :description (:description e)
                       :technology (if (= :kernel (:kind e)) "kernel" (get-in e [:entry :technology]))
                       :stores? (= :stores (get-in e [:entry :kind]))}
                      extra)))
        people (map :id (filter map? (:people m)))
        externals (map :id (filter map? (:external m)))
        order (into {} (map-indexed (fn [i e] [(:id e) i]) (elements m)))
        base {:level (:level v) :key (:key v) :title (:description v)}]
    (case (:level v)
      :context
      (let [lift (fn [id] (case (:kind (get idx id))
                            (:person :external) id
                            (:system :container :context :component :kernel) sys
                            nil))]
        (assoc base
               :boundary nil
               :elements (vec (concat [(el sys :inside? true)]
                                      (map #(el % :inside? false) people)
                                      (map #(el % :inside? false :external? true) externals)))
               :relationships (lifted-rels m lift)))

      :container
      (let [runs (:id (runs-container m))
            lift (fn [id] (let [e (get idx id)]
                            (case (:kind e)
                              (:person :external :container) id
                              (:context :component) (:container e)
                              :kernel runs
                              nil)))]
        (assoc base
               :boundary {:id sys :name (or (get-in m [:system :name]) (name sys)) :kind :system}
               :elements (vec (concat (map #(el (:id %) :inside? true) (filter map? (:containers m)))
                                      (map #(el % :inside? false) people)
                                      (map #(el % :inside? false :external? true) externals)))
               :relationships (lifted-rels m lift)))

      :component
      (let [ctx (get idx (:context v))
            inside (vec (for [e (elements m) :when (and (= :component (:kind e)) (= (:context v) (:context e)))] (:id e)))
            in? (set inside)
            rels (vec (for [r (:relationships m)
                            :when (and (or (in? (:from r)) (in? (:to r)))
                                       (not= (:from r) (:to r)))]
                        {:from (:from r) :to (:to r) :label (rel-label r)}))
            outside (sort-by #(get order % Long/MAX_VALUE)
                             (distinct (for [r rels end [:from :to] :let [id (get r end)] :when (not (in? id))] id)))]
        (assoc base
               :boundary {:id (:context v) :name (or (:name ctx) (some-> (:context v) name)) :kind :context}
               :elements (vec (concat (map #(el % :inside? true) inside)
                                      (map #(el % :inside? false :external? true) outside)))
               :relationships rels))

      :dynamic
      (let [steps (:steps v)
            ids (distinct (mapcat (fn [[f t]] [f t]) steps))]
        (assoc base
               :boundary nil
               :elements (mapv #(el % :inside? false) ids)
               :relationships (vec (for [[f t d] steps] {:from f :to t :label d}))))
      (assoc base :elements [] :relationships []))))

;; ---------------------------------------------------------------------------
;; The event catalogue

(defn read-catalogue
  "The configured event catalogue: {:catalogue map-or-nil :findings [..]}.
  No :events configured is no catalogue, so no event is declared."
  [req]
  (let [rel (get-in req [:config :events])]
    (if-not rel
      {:catalogue nil :findings []}
      (let [f (command/path req rel)]
        (if-not (fs/exists? f)
          {:catalogue nil
           :findings [(command/finding rel nil "model-catalogue" (str "no event catalogue " rel ", which the configuration names"))]}
          (let [{:keys [forms error line]} (read-all (slurp f))]
            (cond
              error {:catalogue nil
                     :findings [(command/finding rel line "model-catalogue" (str "the event catalogue is not EDN: " error))]}
              (not (and (= 1 (count forms)) (map? (first forms))))
              {:catalogue nil
               :findings [(command/finding rel nil "model-catalogue" "the event catalogue is not one EDN map")]}
              :else {:catalogue (first forms) :findings []})))))))

;; ---------------------------------------------------------------------------
;; Loading, once per run

(defn read-model-file
  "Reads the model at `rel` (relative to the request's root):
  {:file rel :exists? bool :model m-or-nil :hash h-or-nil :findings [..]}.
  :model is nil unless the model was read whole and its shape holds; :hash
  is set whenever the file holds one EDN value."
  [req rel]
  (let [f (command/path req rel)]
    (if-not (fs/exists? f)
      {:file rel :exists? false :model nil :hash nil
       :findings [(command/finding rel nil "model-edn" (str "no model " rel ", which the configuration names"))]}
      (let [{:keys [model findings]} (parse-text rel (slurp f))]
        (if (seq findings)
          {:file rel :exists? true :model nil :hash nil :findings findings}
          (let [fs* (check-model rel model)]
            {:file rel :exists? true :hash (model-hash model)
             :model (when (empty? fs*) model) :value model :findings fs*}))))))

(defn load!
  "The run's model and catalogue, read once per request (cached in its
  :state): read-model-file's map with :catalogue and :catalogue-findings."
  [req]
  (or (some-> (:state req) deref ::loaded)
      (let [rel (command/config-value req :model)
            r (read-model-file req rel)
            c (read-catalogue req)
            loaded (assoc r :catalogue (:catalogue c) :catalogue-findings (:findings c))]
        (when-let [s (:state req)] (swap! s assoc ::loaded loaded))
        loaded)))
