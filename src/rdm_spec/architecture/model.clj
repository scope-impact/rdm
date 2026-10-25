(ns rdm-spec.architecture.model
  "Model (dhf/spec/architecture/model.feature): reads the project's C4 model
  (the configured :model, dhf/spec/model.edn) and its event catalogue (the
  configured :events), the one source every view, every lint rule and every
  component's ownership of the spec is read from.

  The model is read whole or not at all: a file that is not EDN, a key its
  shape does not declare, an id used twice, or a reference to an element that
  does not exist refuses the whole model, with findings naming the file and,
  where it can, the line. A refused model is never linted or drawn.

  Its hash is taken over the model's value, never its text, so reformatting
  (comments, whitespace, key order) changes nothing, and any changed value
  changes it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [edamame.core :as edamame]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The model's shape: the keys each kind of entry may carry (model.edn's own
;; header comment). Required keys are the ones nothing can be drawn without;
;; a missing description is a lint finding (c4-element-described), not a
;; refusal.

(def shape
  {:model        {:allowed #{:system :people :external :containers :kernel :relationships :views}}
   :system       {:allowed #{:id :name :description} :required #{:id}}
   :person       {:allowed #{:id :name :description :actor} :required #{:id}}
   :external     {:allowed #{:id :name :description} :required #{:id}}
   :container    {:allowed #{:id :name :description :technology :kind :contexts} :required #{:id}}
   :context      {:allowed #{:id :name :description :components} :required #{:id}}
   :component    {:allowed #{:id :name :description :spec} :required #{:id}}
   :kernel       {:allowed #{:id :name :description :spec} :required #{:id}}
   :relationship {:allowed #{:from :to :description :event} :required #{:from :to}}
   :view         {:allowed #{:key :level :description :context :steps} :required #{:key :level}}})

(def levels #{:context :container :component :dynamic})

;; ---------------------------------------------------------------------------
;; Helpers

(defn line-of [x] (:line (meta x)))

(defn kw [k] (if (keyword? k) (str k) (pr-str k)))

(defn describe-element
  "How a finding names an element: `container :repository`."
  [{:keys [type id]}]
  (str (name type) " " (kw id)))

(defn describe-relationship [{:keys [from to]}]
  (str "relationship " (kw from) " -> " (kw to)))

(defn describe-view [v] (str "view " (pr-str (:key v))))

(defn blank? [s] (or (nil? s) (and (string? s) (str/blank? s))))

;; ---------------------------------------------------------------------------
;; Reading

(defn- parse-edn
  "[value nil] or [nil {:line n :message s}]. Maps and vectors carry their
  line as :line metadata."
  [text]
  (try
    (let [forms (edamame/parse-string-all text {:row-key :line :col-key :column})]
      (cond
        (empty? forms) [nil {:line nil :message "holds no EDN value"}]
        (> (count forms) 1) [nil {:line (line-of (second forms))
                                  :message "holds more than one EDN value"}]
        :else [(first forms) nil]))
    (catch Exception e
      (let [d (ex-data e)
            opened (:edamame/opened-delimiter-loc d)]
        [nil {:line (or (:row opened) (:line d) (:row d))
              :message (str "is not EDN: "
                            (if opened
                              (str "the " (:edamame/opened-delimiter d) " opened on line " (:row opened)
                                   " is never closed")
                              (ex-message e)))}]))))

(defn elements
  "Every element of the model, in model order:
  [{:type :system|:person|:external|:container|:context|:component|:kernel
    :id :el <the entry> :container <id> :context <id>}]."
  [m]
  (vec
   (concat
    (when (map? (:system m)) [{:type :system :id (:id (:system m)) :el (:system m)}])
    (for [p (:people m)] {:type :person :id (:id p) :el p})
    (for [e (:external m)] {:type :external :id (:id e) :el e})
    (for [c (:containers m)
          x (cons {:type :container :id (:id c) :el c}
                  (for [ctx (:contexts c)
                        y (cons {:type :context :id (:id ctx) :el ctx :container (:id c)}
                                (for [comp (:components ctx)]
                                  {:type :component :id (:id comp) :el comp
                                   :container (:id c) :context (:id ctx)}))]
                    y))]
      x)
    (for [k (:kernel m)] {:type :kernel :id (:id k) :el k}))))

(defn components
  "Every component and kernel element (the elements that own a feature file)."
  [m]
  (filterv #(#{:component :kernel} (:type %)) (elements m)))

(defn contexts [m] (filterv #(= :context (:type %)) (elements m)))

(defn- check-keys [file kind x where]
  (let [{:keys [allowed required]} (shape kind)]
    (if-not (map? x)
      [(command/finding file (line-of x) "model-shape"
                        (str where " is not a map: " (pr-str x)))]
      (concat
       (for [k (sort-by str (keys x)) :when (not (allowed k))]
         (command/finding file (line-of x) "model-shape"
                          (str where " carries the key " (kw k) ", which the model's shape does not declare"
                               " (a " (name kind) " may carry " (str/join " " (map kw (sort-by str allowed))) ")")))
       (for [k (sort-by str required) :when (not (contains? x k))]
         (command/finding file (line-of x) "model-shape"
                          (str where " has no " (kw k))))))))

(defn- where [kind x]
  (case kind
    :relationship (if (map? x) (describe-relationship x) "a relationship")
    :view (if (and (map? x) (:key x)) (describe-view x) "a view")
    (str (name kind) (when (and (map? x) (:id x)) (str " " (kw (:id x)))))))

(defn- shape-findings [file m]
  (if-not (map? m)
    [(command/finding file (line-of m) "model-shape" "the model is not a map")]
    (let [seqs-ok (for [k [:people :external :containers :kernel :relationships :views]
                        :let [v (get m k)]
                        :when (and (some? v) (not (sequential? v)))]
                    (command/finding file (line-of m) "model-shape" (str (kw k) " is not a vector")))]
      (vec
       (concat
        (check-keys file :model m "the model")
        seqs-ok
        (when (contains? m :system) (check-keys file :system (:system m) (where :system (:system m))))
        (when (empty? seqs-ok)
          (concat
           (mapcat #(check-keys file :person % (where :person %)) (:people m))
           (mapcat #(check-keys file :external % (where :external %)) (:external m))
           (mapcat #(check-keys file :kernel % (where :kernel %)) (:kernel m))
           (mapcat (fn [c]
                     (concat (check-keys file :container c (where :container c))
                             (when (map? c)
                               (mapcat (fn [ctx]
                                         (concat (check-keys file :context ctx (where :context ctx))
                                                 (when (map? ctx)
                                                   (mapcat #(check-keys file :component % (where :component %))
                                                           (:components ctx)))))
                                       (:contexts c)))))
                   (:containers m))
           (mapcat #(check-keys file :relationship % (where :relationship %)) (:relationships m))
           (mapcat (fn [v]
                     (concat (check-keys file :view v (where :view v))
                             (when (and (map? v) (contains? v :level) (not (levels (:level v))))
                               [(command/finding file (line-of v) "model-shape"
                                                 (str (describe-view v) " has the level " (kw (:level v))
                                                      "; a level is one of :context :container :component :dynamic"))])))
                   (:views m)))))))))

(defn- id-findings
  "An id used twice anywhere refuses the model. The one exception is the
  modular monolith's pun: the system and the one container that stands for it
  may share an id (rdm-spec's own model does), as long as no third element does."
  [file m]
  (let [els (elements m)
        by-id (group-by :id els)]
    (vec
     (concat
      (for [[id es] by-id
            :let [pun? (and (= 2 (count es)) (= #{:system :container} (set (map :type es))))]
            :when (and (> (count es) 1) (not pun?))]
        (command/finding file (line-of (:el (second es))) "model-id"
                         (str "the id " (kw id) " is used " (count es) " times: "
                              (str/join ", " (map describe-element es)))))
      (let [keys-seen (group-by :key (filter map? (:views m)))]
        (for [[k vs] keys-seen :when (> (count vs) 1)]
          (command/finding file (line-of (second vs)) "model-id"
                           (str "the view key " (pr-str k) " is used " (count vs) " times"))))))))

(defn- reference-findings [file m]
  (let [ids (set (map :id (elements m)))
        ctx-ids (set (map :id (contexts m)))
        missing (fn [id] (not (contains? ids id)))]
    (vec
     (concat
      (for [r (:relationships m)
            end [:from :to]
            :let [id (get r end)]
            :when (missing id)]
        (command/finding file (line-of r) "model-reference"
                         (str (describe-relationship r) " names " (kw id) ", and no element " (kw id) " exists")))
      (for [v (:views m)
            :when (= :component (:level v))
            :when (not (contains? ctx-ids (:context v)))]
        (command/finding file (line-of v) "model-reference"
                         (str (describe-view v) " is a component view of " (if (:context v) (kw (:context v)) "no context")
                              ", and no bounded context " (if (:context v) (kw (:context v)) "") " exists")))
      (for [v (:views m)
            :when (= :dynamic (:level v))
            [i st] (map-indexed vector (:steps v))]
        (if-not (and (sequential? st) (= 3 (count st)))
          (command/finding file (line-of v) "model-shape"
                           (str (describe-view v) " step " (inc i) " is not [from to description]"))
          (let [bad (filter missing (take 2 st))]
            (when (seq bad)
              (command/finding file (line-of v) "model-reference"
                               (str (describe-view v) " step " (inc i) " names "
                                    (str/join " and " (map kw bad)) ", and no such element exists"))))))))))

(defn- remove-nil [xs] (vec (remove nil? xs)))

(defn read-model-file
  "Reads the model file `f` (a File), naming it `rel` in findings:
  {:model m :findings [...]} with :model nil when it is refused."
  [f rel]
  (if-not (fs/exists? f)
    {:model nil :findings [(command/finding rel nil "model-edn" "the model does not exist")]}
    (let [[m err] (parse-edn (slurp f))]
      (if err
        {:model nil :findings [(command/finding rel (:line err) "model-edn" (str "the model " (:message err)))]}
        (let [fs1 (shape-findings rel m)
              fs2 (when (empty? fs1) (concat (id-findings rel m) (reference-findings rel m)))
              all (remove-nil (concat fs1 fs2))]
          {:model (when (empty? all) m) :findings all})))))

(defn read-catalogue-file
  "The event catalogue: {:catalogue {Event {...}} :findings [...]}. An absent
  catalogue is empty (a relationship naming an event then fails the lint)."
  [f rel]
  (if-not (fs/exists? f)
    {:catalogue {} :findings []}
    (let [[c err] (parse-edn (slurp f))]
      (cond
        err {:catalogue nil :findings [(command/finding rel (:line err) "model-edn"
                                                        (str "the event catalogue " (:message err)))]}
        (not (map? c)) {:catalogue nil :findings [(command/finding rel (line-of c) "model-shape"
                                                                   "the event catalogue is not a map")]}
        :else {:catalogue c :findings []}))))

;; ---------------------------------------------------------------------------
;; The hash: of the value, never the text

(defn- canonical
  "A string for `x` that depends only on its value: map entries and set
  members sorted, metadata and formatting ignored."
  [x]
  (cond
    (map? x) (str "{" (str/join "," (sort (map (fn [[k v]] (str (canonical k) " " (canonical v))) x))) "}")
    (set? x) (str "#{" (str/join "," (sort (map canonical x))) "}")
    (sequential? x) (str "[" (str/join "," (map canonical x)) "]")
    :else (pr-str x)))

(defn model-hash
  "The SHA-256, in hex, of the model's value."
  [m]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes ^String (canonical m) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

;; ---------------------------------------------------------------------------
;; From a request

(defn read-model
  "The project's model and catalogue for a request, read once per run (cached
  in the run's :state): {:model m-or-nil :catalogue c :hash h :file rel
  :events-file rel :findings [...]}."
  [req]
  (or (get @(:state req) ::read)
      (let [model-rel (command/config-value req :model)
            events-rel (or (get-in req [:config :events]) "dhf/spec/events.edn")
            {:keys [model findings]} (read-model-file (command/path req model-rel) model-rel)
            cat (read-catalogue-file (command/path req events-rel) events-rel)
            ok? (and model (:catalogue cat))
            r {:model (when ok? model)
               :catalogue (:catalogue cat)
               :hash (when ok? (model-hash model))
               :file model-rel
               :events-file events-rel
               :findings (vec (concat findings (:findings cat)))}]
        (swap! (:state req) assoc ::read r)
        r)))
