(ns steps.architecture-views
  "Step definitions for dhf/spec/architecture/views.feature: `rdm-spec views`
  on a small project with a model of its own (steps.architecture-support),
  read back from the diagram sources it writes; the stale view is the
  kernel's shared step.

  Context keys set here: :dir the project; :model the model written; :level
  the level of the view an outline row draws; :view its key."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defstep #"a model with a context view, a container view and two component views"
  (fn [ctx]
    (assoc ctx :dir (a/small-project! ctx a/base-model) :model a/base-model)))

(defn- stack-formats
  "The diagram formats dhf/harness/stack.md names for views: the extensions
  of the dhf/views/<key>.EXT paths it gives."
  []
  (let [text (slurp (str (fs/file s/room-root "dhf/harness/stack.md")))]
    (vec (distinct (map second (re-seq #"dhf/views/<key>\.([a-z]+)" text))))))

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          formats (stack-formats)]
      (s/check! (= #{"mmd" "svg"} (set formats)) (str "the stack names the formats " formats))
      (doseq [v (:views (:model ctx)) ext formats
              :let [f (a/view-file (:dir ctx) (:key v) ext)]]
        (s/check! (and (fs/exists? f) (pos? (fs/size f))) (str "no " f "\n" (s/show r))))
      (s/check! (str/includes? (slurp (str (a/view-file (:dir ctx) "context" "svg"))) "<svg")
                "the rendered context view is no SVG")
      nil)))

(defstep #"each output's first line is a comment holding \"model-sha256: \" and the model's hash"
  (fn [ctx]
    (let [h (a/model-hash (:model ctx))]
      (doseq [v (:views (:model ctx))
              [ext comment-re] [["mmd" #"^%%.*"] ["svg" #"^<!--.*-->$"]]
              :let [first-line (first (str/split-lines (slurp (str (a/view-file (:dir ctx) (:key v) ext)))))]]
        (s/check! (re-matches comment-re first-line) (str (:key v) "." ext "'s first line is no comment: " first-line))
        (s/check! (str/includes? first-line (str "model-sha256: " h))
                  (str (:key v) "." ext "'s first line does not hold the model's hash " h ": " first-line)))
      nil)))

(defstep #"it fails and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\"")))
                      (kc/finding-lines r))
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Each level of view holds what C4 says it holds

(def ^:private level-model
  "The base model, with a kernel component, a second person who uses only
  the store's owner, an event between the contexts and a dynamic view."
  (-> a/base-model
      (update :people conj {:id :owner :name "Owner" :description "Owns the sauna" :actor #{:human}})
      (assoc :kernel [{:id :kernel/cli :name "CLI" :spec "kernel/cli" :description "The command"}])
      (update :relationships into [{:from :owner :to :db :description "Audits"}
                                   {:from :booking/slots :to :billing/invoice :description "Asks for an invoice from"}
                                   {:from :booking/rules :to :mailer :description "Mails refusals through"}])
      (update :views conj {:key "booking-flow" :level :dynamic :description "A slot is booked"
                           :steps [[:bather :app "1. Asks for a slot"]
                                   [:booking/slots :db "2. Writes the slot"]
                                   [:booking/slots :billing/invoice "3. Asks for the invoice"]]})))

(def ^:private view-of {"context" "context" "container" "containers" "component" "booking-components"
                        "dynamic" "booking-flow"})

(defstep #"a model with a view of level (\S+)"
  (fn [ctx level]
    (s/check! (view-of level) (str "no fixture view of level " level))
    (assoc ctx :dir (a/small-project! ctx level-model) :model level-model :level level :view (view-of level))))

(defn- ids [xs] (set (map (comp a/mermaid-id :id) xs)))

(defn- check-elements! [ctx text want]
  (let [got (set (keys (a/view-elements text)))]
    (s/check! (= want got) (str "the view draws " (sort got) ", not " (sort want) "\n" text))))

(defn- source [ctx] (kc/exits! ctx 0) (a/view-source (:dir ctx) (:view ctx)))

(defstep #"the view holds the system, every person and every external system, and the relationships between them"
  (fn [ctx]
    (let [m (:model ctx) text (source ctx)
          sys (a/mermaid-id (get-in m [:system :id]))]
      (check-elements! ctx text (conj (into (ids (:people m)) (ids (:external m))) sys))
      (let [rels (set (map (juxt first second) (a/view-rels text)))]
        (s/check! (contains? rels ["bather" sys]) (str "no relationship from the bather to the system\n" text))
        (s/check! (contains? rels [sys "mailer"]) (str "no relationship from the system to the mailer\n" text))
        (s/check! (contains? rels ["owner" sys]) (str "no relationship from the owner to the system\n" text)))
      nil)))

(defstep #"the view holds every container, every person and every external system, and the relationships between them"
  (fn [ctx]
    (let [m (:model ctx) text (source ctx)]
      (check-elements! ctx text (into (ids (:containers m)) (concat (ids (:people m)) (ids (:external m)))))
      (let [rels (set (map (juxt first second) (a/view-rels text)))]
        (doseq [pair [["bather" "app"] ["app" "mailer"] ["app" "db"] ["owner" "db"]]]
          (s/check! (contains? rels pair) (str "no relationship " pair "\n" text)))
        (s/check! (every? (fn [[x y]] (not= x y)) rels) (str "a relationship joins a container to itself\n" text)))
      nil)))

(defstep #"the view holds one context's components inside its boundary, and the elements outside it they relate to, drawn as external"
  (fn [ctx]
    (let [text (source ctx)
          els (a/view-elements text)
          inside (set (keys (a/view-elements (or (a/boundary-body text) ""))))]
      (s/check! (= #{"booking__slots" "booking__rules"} inside)
                (str "the boundary holds " (sort inside) ", not booking's two components\n" text))
      (check-elements! ctx text #{"booking__slots" "booking__rules" "db" "billing__invoice" "mailer"})
      (doseq [outside ["db" "billing__invoice" "mailer"]]
        (s/check! (str/ends-with? (get els outside) "_Ext") (str outside " is not drawn as external\n" text)))
      nil)))

(defstep #"the view holds the elements its steps name, and its steps in order"
  (fn [ctx]
    (let [text (source ctx)]
      (check-elements! ctx text #{"bather" "app" "booking__slots" "db" "billing__invoice"})
      (s/check! (= ["1. Asks for a slot" "2. Writes the slot" "3. Asks for the invoice"] (map #(nth % 2) (a/view-rels text)))
                (str "the steps are not drawn in order\n" text))
      nil)))
