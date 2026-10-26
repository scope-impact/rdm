(ns rdm-spec.architecture.model
  "The model (dhf/spec/architecture/model.feature): reads model.edn whole or
  not at all, holds it to its declared shape, and reads the event catalogue.
  Its hash is of its content, never its formatting."
  (:require [clojure.string :as str]
            [edamame.core :as e]
            [rdm-spec.kernel.command :as cmd]))

;; ---------------------------------------------------------------------------
;; The model hash: the SHA-256 of the model printed back canonically

(defn canonical
  "The model printed as EDN prints it, every map's entries and every set's
  elements sorted by their printed form."
  [x]
  (cond
    (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (canonical k) " " (canonical v)))
                                          (sort-by (comp canonical key) x))) "}")
    (set? x) (str "#{" (str/join " " (sort (map canonical x))) "}")
    (vector? x) (str "[" (str/join " " (map canonical x)) "]")
    (seq? x) (str "(" (str/join " " (map canonical x)) ")")
    :else (pr-str x)))

(defn sha256-hex [^String s]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defn model-hash [model] (sha256-hex (canonical model)))

;; ---------------------------------------------------------------------------
;; Reading

(defn read-text
  "Reads an EDN file's text whole: {:value v :findings [..]}, a finding naming
  the line where the text stops being EDN."
  [file text]
  (if (nil? text)
    {:findings [(cmd/finding file nil "model-unreadable" "the file is missing")]}
    (try
      (let [forms (e/parse-string-all text {:all true})]
        (cond
          (not= 1 (count forms))
          {:findings [(cmd/finding file nil "model-unreadable" (str "the file holds " (count forms) " forms, not one map"))]}
          (not (map? (first forms)))
          {:findings [(cmd/finding file nil "model-unreadable" "the file is not one map")]}
          :else {:value (first forms) :findings []}))
      (catch clojure.lang.ExceptionInfo ex
        (let [d (ex-data ex)
              opened (get-in d [:edamame/opened-delimiter-loc :row])
              line (or (when (and opened (seq (:edamame/expected-delimiter d))) opened) (:row d))
              msg (ex-message ex)]
          {:findings [(cmd/finding file line "model-unreadable"
                                   (cond
                                     (str/starts-with? msg "Unmatched delimiter")
                                     (str "\"" (str/replace msg #"^Unmatched delimiter: " "") "\" closes nothing: " msg)
                                     (seq (:edamame/expected-delimiter d))
                                     (str "the \"" (:edamame/opened-delimiter d) "\" on line " opened " is never closed: " msg)
                                     :else (str "not EDN: " msg)))]})))))

(def shape
  {:top #{:system :people :external :containers :kernel :relationships :views}
   :system #{:id :name :description}
   :person #{:id :name :description :actor}
   :external #{:id :name :description}
   :container #{:id :name :description :technology :kind :contexts}
   :context #{:id :name :description :components}
   :component #{:id :name :description :spec}
   :kernel #{:id :name :description :spec}
   :relationship #{:from :to :description :event}
   :view #{:key :level :description :context :steps}})

(defn kind-name [k]
  (case k :system "system" :person "person" :external "external system" :container "container"
          :context "bounded context" :component "component" :kernel "kernel component" (name k)))

(defn elements
  "Every element of the model in the model's order: {:id :kind :el :container :context}."
  [model]
  (vec
   (concat
    (when-let [s (:system model)] [{:id (:id s) :kind :system :el s}])
    (for [p (:people model)] {:id (:id p) :kind :person :el p})
    (for [x (:external model)] {:id (:id x) :kind :external :el x})
    (mapcat (fn [c]
              (concat [{:id (:id c) :kind :container :el c}]
                      (mapcat (fn [cx]
                                (concat [{:id (:id cx) :kind :context :el cx :container (:id c)}]
                                        (for [k (:components cx)]
                                          {:id (:id k) :kind :component :el k :container (:id c) :context (:id cx)})))
                              (:contexts c))))
            (:containers model))
    (for [k (:kernel model)] {:id (:id k) :kind :kernel :el k}))))

(defn components
  "Every component of the model, its contexts' in order, then the kernel's."
  [model]
  (filter #(#{:component :kernel} (:kind %)) (elements model)))

(defn line-of [text s]
  (when (and text s)
    (when-let [i (str/index-of text s)]
      (inc (count (filter #{\newline} (subs text 0 i)))))))

(defn check-model
  "The findings about the model's shape, its ids and its relationships."
  [file model text]
  (let [f (fn [needle rule msg] (cmd/finding file (line-of text needle) rule msg))
        undeclared (fn [kind el label]
                     (for [k (keys el) :when (not (contains? (shape kind) k))]
                       (f (str k) "model-key-undeclared"
                          (str label " carries the key " k ", which the model's shape does not declare"))))
        els (elements model)
        label (fn [{:keys [kind id]}] (str "the " (kind-name kind) " " id))
        ids (group-by :id els)
        known (set (map :id els))
        context-ids (set (map :id (filter #(= :context (:kind %)) els)))]
    (vec
     (concat
      (undeclared :top model "the model")
      (mapcat (fn [x] (when (map? (:el x)) (undeclared (:kind x) (:el x) (label x)))) els)
      (map-indexed (fn [i r] (undeclared :relationship r (str "the relationship " (:from r) " -> " (:to r)))) [])
      (mapcat (fn [r] (undeclared :relationship r (str "the relationship " (:from r) " -> " (:to r)))) (:relationships model))
      (mapcat (fn [v] (undeclared :view v (str "the view " (:key v)))) (:views model))
      (for [[id xs] ids :when (> (count xs) 1)]
        (f (str id) "model-id-duplicate"
           (str "the id " id " is used " (count xs) " times: by " (str/join " and by " (map label xs)))))
      (for [r (:relationships model) end [(:from r) (:to r)] :when (not (known end))]
        (f (str end) "model-element-unknown"
           (str "the relationship " (:from r) " -> " (:to r) " names " end ", which is no element of the model")))
      (for [v (:views model) :when (and (:context v) (not (context-ids (:context v))))]
        (f (str (:context v)) "model-element-unknown"
           (str "the view " (:key v) " names the bounded context " (:context v) ", which the model does not hold")))
      (for [v (:views model) [a b] (:steps v) end [a b] :when (not (known end))]
        (f (str end) "model-element-unknown"
           (str "the view " (:key v) " has a step naming " end ", which is no element of the model")))))))

(defn read-model
  "Reads the model at a revision: {:model :hash :findings :text :file}."
  [root rev config]
  (let [file (:model config "dhf/spec/model.edn")
        text (cmd/read-at root rev file)
        {:keys [value findings]} (read-text file text)]
    (if value
      {:file file :text text :model value :hash (model-hash value)
       :findings (into findings (check-model file value text))}
      {:file file :text text :findings findings})))

(defn read-events
  "Reads the event catalogue: {:events {name decl} :findings}."
  [root rev config]
  (let [file (:events config "dhf/spec/events.edn")
        text (cmd/read-at root rev file)
        {:keys [value findings]} (read-text file text)]
    {:file file :text text :events (or value {})
     :findings (mapv #(assoc % :rule "events-unreadable") findings)}))

(defn stage
  "The model's stage: the model and the catalogue are read whole or not at all."
  [{:keys [root config revision]}]
  (let [m (read-model root revision config)
        ev (read-events root revision config)]
    {:findings (concat (:findings m) (:findings ev))}))
