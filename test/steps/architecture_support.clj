(ns steps.architecture-support
  "Fixtures for the architecture context's step definitions
  (architecture_model, architecture_lint, architecture_views): a copy of the
  room whose model a step edits, or a small project `rdm-spec init` wrote
  with a model of the step's own; the model hash, as the glossary defines
  it; and a reading of a generated view's diagram source."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn model-rel [dir] (or (:model (kc/config-of dir)) "dhf/spec/model.edn"))

(defn views-rel [dir] (or (:views-dir (kc/config-of dir)) "dhf/views"))

(defn read-model [dir] (edn/read-string (slurp (str (fs/file dir (model-rel dir))))))

(defn write-model! [dir m] (s/write! dir (model-rel dir) (str (pr-str m) "\n")))

(defn update-model! [dir f] (write-model! dir (f (read-model dir))))

(defn room-copy [ctx] (or (:dir ctx) (s/copy-of-room!)))

(defn small-project!
  "A project `rdm-spec init` wrote, its model `m`, a feature file for each
  component whose spec path is given, its views not generated."
  [ctx m]
  (let [{:keys [dir]} (kc/project-set-up! ctx)]
    (write-model! dir m)
    (doseq [c (concat (for [x (:containers m) y (:contexts x) c (:components y)] c) (:kernel m))
            :when (:spec c)]
      (s/write! dir (str "dhf/spec/" (:spec c) ".feature")
                (str "Feature: " (:name c) "\n  " (:description c) "\n")))
    dir))

(defn views! [ctx dir]
  (let [r (kc/run! ctx dir ["views"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------
;; A small model

(defn component [ctx-id cname desc]
  (let [slug (str/lower-case cname)]
    {:id (keyword (name ctx-id) slug) :name cname :spec (str (name ctx-id) "/" slug) :description desc}))

(def base-model
  "A system with one person, one external system, one container with two
  bounded contexts (booking: Slots, Rules; billing: Invoice) and a store."
  {:system {:id :sauna :name "Sauna" :description "Books sauna slots"}
   :people [{:id :bather :name "Bather" :description "Books a slot" :actor #{:human}}]
   :external [{:id :mailer :name "Mailer" :description "Sends mail"}]
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The booking program"
                 :contexts [{:id :booking :name "booking" :description "Books slots"
                             :components [(component :booking "Slots" "Holds the slots")
                                          (component :booking "Rules" "Says who may book")]}
                            {:id :billing :name "billing" :description "Bills for slots"
                             :components [(component :billing "Invoice" "Writes invoices")]}]}
                {:id :db :name "Database" :kind :stores :technology "files" :description "Holds the bookings"}]
   :kernel []
   :relationships [{:from :bather :to :app :description "Books slots with"}
                   {:from :app :to :mailer :description "Sends confirmations through"}
                   {:from :app :to :db :description "Keeps bookings in"}
                   {:from :booking/rules :to :booking/slots :description "Guards"}
                   {:from :booking/slots :to :db :description "Writes slots to"}
                   {:from :billing/invoice :to :db :description "Reads bookings from"}]
   :views [{:key "context" :level :context :description "The sauna and who uses it"}
           {:key "containers" :level :container :description "The program and its store"}
           {:key "booking-components" :level :component :context :booking :description "Booking"}
           {:key "billing-components" :level :component :context :billing :description "Billing"}]})

;; ---------------------------------------------------------------------------
;; The model hash (dhf/spec/glossary.md, "Model hash")

(defn- canon [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (canon k) " " (canon v)))
                                              (sort-by #(canon (key %)) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map canon x))) "}")
        (vector? x) (str "[" (str/join " " (map canon x)) "]")
        (seq? x) (str "(" (str/join " " (map canon x)) ")")
        :else (binding [*print-namespace-maps* false] (pr-str x))))

(defn model-hash [m]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes ^String (canon m) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

;; ---------------------------------------------------------------------------
;; Reading a view's diagram source

(defn view-file [dir k ext] (fs/file dir (views-rel dir) (str k "." ext)))

(defn view-source [dir k]
  (let [f (view-file dir k "mmd")]
    (s/check! (fs/exists? f) (str "no diagram source " f))
    (slurp (str f))))

(defn mermaid-id
  "The diagram id of a model id."
  [k]
  (-> (if (keyword? k) (subs (str k) 1) (str k)) (str/replace "/" "__") (str/replace #"[^A-Za-z0-9_]" "_")))

(defn view-elements
  "{diagram-id kind} of every element a view's source draws (boundaries aside)."
  [text]
  (into {} (for [[_ kind id] (re-seq #"(?m)^\s*([A-Za-z_]+)\(([A-Za-z0-9_]+)," text)
                 :when (not (re-find #"(?i)boundary|^Rel" kind))]
             [id kind])))

(defn view-rels
  "[[from to label]] of a view's source, in order."
  [text]
  (vec (for [[_ a b l] (re-seq #"(?m)^\s*Rel\(([A-Za-z0-9_]+),\s*([A-Za-z0-9_]+),\s*\"([^\"]*)\"" text)]
         [a b l])))

(defn boundary-body
  "The lines inside the first boundary of a view's source."
  [text]
  (let [ls (str/split-lines text)
        start (first (keep-indexed (fn [i l] (when (re-find #"Boundary\(" l) i)) ls))]
    (when start
      (str/join "\n" (take-while #(not (re-matches #"\s*\}\s*" %)) (drop (inc start) ls))))))
