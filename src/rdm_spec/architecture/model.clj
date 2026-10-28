(ns rdm-spec.architecture.model
  "Component Model (architecture context): reads model.edn whole or not at
  all, holds it to its declared shape, and reads the event catalogue. The
  model's hash is computed here, once: views record it and are stale when it
  moves, which a change of content does and a reformat does not."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

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

(defn line-of [text s]
  (when (and text s)
    (some (fn [[i l]] (when (str/includes? l s) (inc i))) (map-indexed vector (str/split-lines text)))))

(defn element-line [text id] (or (line-of text (str ":id " id)) (line-of text (str id)) 1))

(defn elements
  "Every element of the model with its kind, in the model's order: [{:id :kind :el :context}]."
  [m]
  (vec (concat
        (when (map? (:system m)) [{:id (get-in m [:system :id]) :kind :system :el (:system m)}])
        (for [p (:people m) :when (map? p)] {:id (:id p) :kind :person :el p})
        (for [e (:external m) :when (map? e)] {:id (:id e) :kind :external :el e})
        (for [c (:containers m) :when (map? c)] {:id (:id c) :kind :container :el c})
        (for [c (:containers m) :when (map? c) ctx (:contexts c) :when (map? ctx)] {:id (:id ctx) :kind :context :el ctx :container (:id c)})
        (for [c (:containers m) :when (map? c) ctx (:contexts c) :when (map? ctx) comp (:components ctx) :when (map? comp)]
          {:id (:id comp) :kind :component :el comp :context (:id ctx) :container (:id c)})
        (for [k (:kernel m) :when (map? k)] {:id (:id k) :kind :kernel :el k :context :kernel}))))

(defn kind-name [k] (case k :system "system" :person "person" :external "external system" :container "container"
                          :context "bounded context" :component "component" :kernel "kernel component" (name k)))

(defn shape-findings [file text m]
  (let [f (fn [id msg] (project/finding file (element-line text id) "model-key-unknown" msg))
        check (fn [kind el]
                (for [k (keys el) :when (not (contains? (shape kind) k))]
                  (f (:id el) (str "the " (kind-name kind) " " (:id el) " carries the key " k ", which the model's shape does not declare"))))]
    (concat
     (for [k (keys m) :when (not (contains? (:top shape) k))]
       (project/finding file (or (line-of text (str k)) 1) "model-key-unknown" (str "the model carries the key " k ", which its shape does not declare")))
     (when (map? (:system m)) (check :system (:system m)))
     (mapcat #(check (:kind %) (:el %)) (rest (elements m)))
     (for [r (:relationships m) :when (map? r) k (keys r) :when (not (contains? (:relationship shape) k))]
       (project/finding file (or (line-of text (str ":from " (:from r))) 1) "model-key-unknown"
                        (str "the relationship " (:from r) " -> " (:to r) " carries the key " k ", which the model's shape does not declare")))
     (for [v (:views m) :when (map? v) k (keys v) :when (not (contains? (:view shape) k))]
       (project/finding file (or (line-of text (pr-str (:key v))) 1) "model-key-unknown"
                        (str "the view " (:key v) " carries the key " k ", which the model's shape does not declare"))))))

(defn id-findings [file text m]
  (let [els (elements m)]
    (concat
     (for [[id es] (group-by :id els) :when (and id (> (count es) 1))]
       (project/finding file (element-line text id) "model-id-duplicate"
                        (str "the id " id " is used " (count es) " times: by "
                             (str/join " and by " (map #(str "the " (kind-name (:kind %)) " " (or (get-in % [:el :name]) (:id %))) es)))))
     (for [e els :when (not (keyword? (:id e)))]
       (project/finding file 1 "model-id-missing" (str "a " (kind-name (:kind e)) " has no keyword :id")))
     (let [ids (set (map :id els))]
       (for [r (:relationships m) :when (map? r)
             end [:from :to] :let [id (get r end)] :when (not (contains? ids id))]
         (project/finding file (or (line-of text (str ":from " (:from r))) 1) "model-relationship-unknown"
                          (str "the relationship " (:from r) " -> " (:to r) " names " id ", which is no element of the model")))))))

(defn read-model
  "Reads the model of SPEC at TREE: {:model m :text :findings}; :model nil
  when it cannot be read whole."
  [tree spec]
  (let [file (:model spec)
        text (project/read-text tree file)]
    (if (nil? text)
      {:model nil :findings [(project/finding file "model-unreadable" "the model is missing")]}
      (let [r (project/read-edn-text text)]
        (cond
          (:error r) {:model nil :text text
                      :findings [(project/finding file (get-in r [:error :line]) "model-unreadable"
                                                  (str "the model is not EDN: " (get-in r [:error :message])))]}
          (not (map? (:value r))) {:model nil :text text :findings [(project/finding file 1 "model-unreadable" "the model is not a map")]}
          :else (let [m (:value r)
                      fs (vec (concat (shape-findings file text m) (id-findings file text m)))]
                  {:model m :text text :findings fs}))))))

(defn read-model-once [tree spec]
  (project/cached [::model tree (:model spec)] #(read-model tree spec)))

(defn read-catalogue
  "The event catalogue of SPEC at TREE: {:events {name m} :findings}."
  [tree spec]
  (let [file (:events spec)
        r (project/read-edn-file tree file)]
    (cond
      (:missing r) {:events {} :findings []}
      (:error r) {:events {} :findings [(project/finding file (get-in r [:error :line]) "events-unreadable"
                                                         (str "the event catalogue is not EDN: " (get-in r [:error :message])))]}
      (not (map? (:value r))) {:events {} :findings [(project/finding file "events-unreadable" "the event catalogue is not a map")]}
      :else {:events (:value r) :findings []})))

;; ------------------------------------------------------------------ the hash

(defn- canonical [x]
  (cond
    (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (canonical k) " " (canonical v)))
                                           (sort-by (fn [[k _]] (pr-str k)) x))) "}")
    (set? x) (str "#{" (str/join " " (map canonical (sort-by pr-str x))) "}")
    (vector? x) (str "[" (str/join " " (map canonical x)) "]")
    (seq? x) (str "(" (str/join " " (map canonical x)) ")")
    :else (pr-str x)))

(defn model-hash
  "The SHA-256, lowercase hex, of the model printed back with every map's
  entries and every set's elements sorted by their printed form."
  [m]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md (.getBytes ^String (canonical m) "UTF-8"))))))
