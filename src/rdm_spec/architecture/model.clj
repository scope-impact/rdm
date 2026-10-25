(ns rdm-spec.architecture.model
  "The Model component of the architecture context
  (dhf/spec/architecture/model.feature): reads model.edn and the event
  catalogue, the one source every view, every lint rule and every
  component's ownership of the spec is read from.

  The model is read whole or not at all: a file that is not EDN (named by
  the line of the bracket at fault), a key its shape does not declare, an
  id used twice, a relationship or view naming no element are refusals, and
  a refused model is neither linted nor drawn.

  Rules, each a stable name on the error stream:
    model-unreadable      missing, not EDN, or not one map
    model-shape           an entry of the wrong kind (a vector where one is declared...)
    model-key-undeclared  a key the model's shape does not declare
    model-id-duplicate    an id two elements carry
    model-unknown-element a relationship, view or step naming no element

  `load!` reads the model once per run and hands its refusals to the first
  stage that asks, so one run names each once."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The shape (dhf/spec/model.edn's header)

(def shape
  "The keys each kind of entry may carry."
  {:model #{:system :people :external :containers :kernel :relationships :views}
   :system #{:id :name :description}
   :person #{:id :name :description :actor}
   :external #{:id :name :description}
   :container #{:id :name :description :technology :kind :contexts}
   :context #{:id :name :description :components}
   :component #{:id :name :description :spec}
   :kernel #{:id :name :description :spec}
   :relationship #{:from :to :description :event}
   :view #{:key :level :description :context :steps}})

(def kind-names
  {:system "the system" :person "the person" :external "the external system" :container "the container"
   :context "the bounded context" :component "the component" :kernel "the kernel component"})

;; ---------------------------------------------------------------------------
;; Brackets: where a file that is not EDN goes wrong

(def ^:private pairs {\( \) \[ \] \{ \}})
(def ^:private closers (set (vals pairs)))

(defn bracket-error
  "The first bracket fault of `text`: {:line :message}, or nil. A closer
  that closes nothing names its own line; an opener never closed names the
  line it opened on."
  [text]
  (let [n (count text)]
    (loop [i 0 line 1 stack []]
      (if (>= i n)
        (when-let [[ch l] (peek stack)]
          {:line l :message (str "the \"" ch "\" opened on line " l " is never closed")})
        (let [c (.charAt ^String text i)]
          (cond
            (= c \newline) (recur (inc i) (inc line) stack)
            (= c \;) (let [j (or (str/index-of text "\n" i) n)] (recur j line stack))
            (= c \\) (recur (+ i 2) line stack)
            (= c \") (let [[j l] (loop [j (inc i) l line]
                                   (cond (>= j n) [j l]
                                         (= \\ (.charAt ^String text j)) (recur (+ j 2) l)
                                         (= \" (.charAt ^String text j)) [(inc j) l]
                                         (= \newline (.charAt ^String text j)) (recur (inc j) (inc l))
                                         :else (recur (inc j) l)))]
                       (if (>= j n)
                         (if (= \" (.charAt ^String text (dec n)))
                           (recur j l stack)
                           {:line line :message (str "the string opened on line " line " is never closed")})
                         (recur j l stack)))
            (contains? pairs c) (recur (inc i) line (conj stack [c line]))
            (contains? closers c)
            (let [[top] (peek stack)]
              (cond
                (and top (= c (pairs top))) (recur (inc i) line (pop stack))
                :else
                (let [opener ({\) \( \] \[ \} \{} c)
                      k (last (keep-indexed (fn [idx [o]] (when (= o opener) idx)) stack))]
                  (if (nil? k)
                    {:line line :message (str "a \"" c "\" that closes nothing")}
                    (let [[o l] (nth stack (inc k))]
                      {:line l :message (str "the \"" o "\" opened on line " l " is never closed (the \"" c
                                             "\" on line " line " closes an earlier \"" opener "\")")})))))
            :else (recur (inc i) line stack)))))))

(defn- read-forms
  "Every form of `text`: {:forms [..]} or {:error {:line :message}}."
  [text]
  (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. text))]
    (try
      (loop [acc []]
        (let [x (edn/read {:eof ::eof} r)]
          (if (= ::eof x) {:forms acc} (recur (conj acc x)))))
      (catch Exception e
        {:error {:line (.getLineNumber r) :message (str "not readable EDN: " (ex-message e))}}))))

;; ---------------------------------------------------------------------------
;; Elements

(defn elements
  "Every element of the model with an id: [{:id :name :kind :el ...}],
  :context the id of a component's context, :container a context's or
  component's container."
  [m]
  (vec
   (concat
    (when (map? (:system m)) [{:kind :system :id (get-in m [:system :id]) :el (:system m)}])
    (for [p (:people m) :when (map? p)] {:kind :person :id (:id p) :el p})
    (for [e (:external m) :when (map? e)] {:kind :external :id (:id e) :el e})
    (for [c (:containers m) :when (map? c)] {:kind :container :id (:id c) :el c})
    (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)]
      {:kind :context :id (:id x) :el x :container (:id c)})
    (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x) comp (:components x) :when (map? comp)]
      {:kind :component :id (:id comp) :el comp :context (:id x) :container (:id c)})
    (for [k (:kernel m) :when (map? k)] {:kind :kernel :id (:id k) :el k}))))

(defn describe
  "An element as a finding names it: `the container :repository (\"Repository\")`."
  [{:keys [kind id el]}]
  (str (kind-names kind) " " (pr-str id) (when (:name el) (str " (" (pr-str (:name el)) ")"))))

(defn rel-name
  "A relationship as a finding names it."
  [{:keys [from to description event]}]
  (str "the relationship " (pr-str from) " -> " (pr-str to)
       (when description (str " (" (pr-str description) ")"))
       (when event (str " publishing " (name event)))))

(defn line-of
  "The line of `text` holding every one of `parts`, else the first holding
  the first of them, or nil."
  [text parts]
  (let [ls (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (str text)))
        hit (fn [ps] (some (fn [[n l]] (when (and (not (str/starts-with? (str/triml l) ";"))
                                                 (every? #(str/includes? l %) ps))
                                        n))
                           ls))]
    (when (seq parts) (or (hit parts) (hit [(first parts)])))))

(defn element-line [text {:keys [kind id el]}]
  (line-of text (if (= :view kind) [(str ":key " (pr-str (:key el)))] [(str ":id " (pr-str id))])))

(defn rel-line [text {:keys [from to]}]
  (line-of text [(str ":from " (pr-str from)) (str ":to " (pr-str to))]))

(defn view-line [text v] (line-of text [(str ":key " (pr-str (:key v)))]))

;; ---------------------------------------------------------------------------
;; The refusals

(defn- vec-of? [x] (or (nil? x) (sequential? x)))

(defn shape-findings [file text m]
  (let [f (fn [line rule msg] (command/finding file line rule msg))
        undeclared (fn [what entry kind line]
                     (for [k (sort-by str (keys entry)) :when (not (contains? (shape kind) k))]
                       (f line "model-key-undeclared"
                          (str what " carries the key " (pr-str k) ", which the model's shape does not declare"
                               " (it declares " (str/join " " (map pr-str (sort-by str (shape kind)))) ")"))))]
    (vec
     (concat
      (for [k (sort-by str (keys m)) :when (not (contains? (shape :model) k))]
        (f (line-of text [(pr-str k)]) "model-key-undeclared"
           (str "the model carries the key " (pr-str k) ", which its shape does not declare")))
      (for [k [:people :external :containers :kernel :relationships :views]
            :when (not (vec-of? (get m k)))]
        (f (line-of text [(pr-str k)]) "model-shape" (str "the model's " (pr-str k) " is not a vector")))
      (when (and (some? (:system m)) (not (map? (:system m))))
        [(f (line-of text [":system"]) "model-shape" "the model's :system is not a map")])
      (apply concat
             (for [e (elements m)]
               (undeclared (describe e) (:el e) (:kind e) (element-line text e))))
      (for [x (concat (:people m) (:external m) (:containers m) (:kernel m) (:relationships m) (:views m))
            :when (not (map? x))]
        (f nil "model-shape" (str "the model holds " (pr-str x) " where an entry, a map, belongs")))
      (apply concat
             (for [r (:relationships m) :when (map? r)]
               (undeclared (rel-name r) r :relationship (rel-line text r))))
      (apply concat
             (for [v (:views m) :when (map? v)]
               (undeclared (str "the view " (pr-str (:key v))) v :view (view-line text v))))))))

(defn id-findings [file text m]
  (let [els (filter :id (elements m))]
    (vec (for [[id es] (sort-by (comp str key) (group-by :id els))
               :when (> (count es) 1)]
           (command/finding file (element-line text (second es)) "model-id-duplicate"
                            (str "the id " (pr-str id) " is used by " (str/join " and " (map describe es))
                                 ": an id is used once in the model"))))))

(defn reference-findings [file text m]
  (let [ids (set (keep :id (elements m)))
        ctx-ids (set (keep #(when (= :context (:kind %)) (:id %)) (elements m)))]
    (vec
     (concat
      (for [r (:relationships m) :when (map? r)
            end [(:from r) (:to r)]
            :when (not (contains? ids end))]
        (command/finding file (rel-line text r) "model-unknown-element"
                         (str (rel-name r) " names " (pr-str end) ", which is no element of the model")))
      (for [v (:views m) :when (map? v)
            :when (and (:context v) (not (contains? ctx-ids (:context v))))]
        (command/finding file (view-line text v) "model-unknown-element"
                         (str "the view " (pr-str (:key v)) " draws the context " (pr-str (:context v))
                              ", which is no bounded context of the model")))
      (for [v (:views m) :when (map? v)
            st (:steps v)
            end (take 2 (if (sequential? st) st []))
            :when (not (contains? ids end))]
        (command/finding file (view-line text v) "model-unknown-element"
                         (str "the view " (pr-str (:key v)) " has a step naming " (pr-str end)
                              ", which is no element of the model")))))))

(defn read-model
  "The model at `rel` under `root`: {:model m :text :file :findings []}; a
  refused model has :model nil and its findings."
  [root rel]
  (let [f (command/path root rel)
        refuse (fn [line msg] {:model nil :file rel :findings [(command/finding rel line "model-unreadable" msg)]})]
    (if-not (fs/regular-file? f)
      (refuse nil (str "the model " rel " does not exist"))
      (let [text (slurp (str f))]
        (if-let [{:keys [line message]} (bracket-error text)]
          (refuse line (str "the model " rel " is not EDN: " message))
          (let [{:keys [forms error]} (read-forms text)]
            (cond
              error (refuse (:line error) (str "the model " rel " is " (:message error)))
              (not= 1 (count forms)) (refuse nil (str "the model " rel " holds " (count forms) " forms, not one map"))
              (not (map? (first forms))) (refuse nil (str "the model " rel " is not a map"))
              :else
              (let [m (first forms)
                    fs* (vec (concat (shape-findings rel text m) (id-findings rel text m) (reference-findings rel text m)))]
                (if (seq fs*)
                  {:model nil :file rel :text text :findings fs*}
                  {:model m :file rel :text text :findings []})))))))))

(defn read-catalogue
  "The event catalogue at `rel`: {:catalogue map-or-nil :file rel :error why-or-nil}."
  [root rel]
  (let [f (command/path root rel)]
    (if-not (fs/regular-file? f)
      {:catalogue nil :file rel :error (str "the event catalogue " rel " does not exist")}
      (let [v (try (edn/read-string (slurp (str f))) (catch Exception e {::error (ex-message e)}))]
        (cond
          (and (map? v) (::error v)) {:catalogue nil :file rel :error (str "the event catalogue " rel " is not readable EDN: " (::error v))}
          (not (map? v)) {:catalogue nil :file rel :error (str "the event catalogue " rel " is not a map of events")}
          :else {:catalogue v :file rel :error nil})))))

(defn load!
  "The model of the request's project, read once per run:
  {:model :text :file :catalogue :findings}. The refusals come with the
  first call of the run and never again."
  [req]
  (let [state (:state req)
        cached (some-> state deref ::model)]
    (if cached
      (assoc cached :findings [])
      (let [rel (command/config-value req :model)
            out (merge (read-model (:root req) rel)
                       {:catalogue (read-catalogue (:root req) (command/config-value req :events))})]
        (when state (swap! state assoc ::model out))
        out))))

;; ---------------------------------------------------------------------------
;; The model hash (dhf/spec/glossary.md, "Model hash")

(defn canonical
  "The model printed back as EDN prints it, every map's entries and every
  set's elements sorted by their printed form, no namespace map syntax."
  [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (canonical k) " " (canonical v)))
                                              (sort-by #(canonical (key %)) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map canonical x))) "}")
        (vector? x) (str "[" (str/join " " (map canonical x)) "]")
        (seq? x) (str "(" (str/join " " (map canonical x)) ")")
        :else (binding [*print-namespace-maps* false] (pr-str x))))

(defn model-hash
  "The model hash: SHA-256, lowercase hex, of the canonical model."
  [m]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String (canonical m) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

;; ---------------------------------------------------------------------------
;; Reading the model

(defn contexts
  "Every bounded context: [{:id :name :description :components :container}]."
  [m]
  (vec (for [c (:containers m) x (:contexts c)] (assoc x :container (:id c)))))

(defn components
  "Every component of every context: [{... :context ctx-id}]."
  [m]
  (vec (for [x (contexts m) comp (:components x)] (assoc comp :context (:id x)))))

(defn feature-file
  "The feature file a component's spec path names: <spec-dir>/<spec>.feature."
  [spec-dir spec]
  (str (str/replace (str spec-dir) #"/+$" "") "/" spec ".feature"))
