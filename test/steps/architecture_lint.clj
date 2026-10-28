(ns steps.architecture-lint
  "Steps of the component Lint (04-model-the-architecture/lint.feature)."
  (:require [clojure.string :as str]
            [steps.kernel-steps :as k]
            [steps.architecture-model :refer [with-model!]]
            [rdm-spec.kernel.runner :refer [defstep]]))

(defn- names [ctx & ns] (assoc ctx :names (vec ns)))

(defstep #"a view with no description"
  (fn [_] (names (with-model! #(update % :views conj {:key "bare" :level :container})) "bare")))

(defstep #"a container with no description"
  (fn [_] (names (with-model! #(update-in % [:containers 0] dissoc :description)) ":app")))

(defstep #"a relationship with no description"
  (fn [_] (names (with-model! #(update % :relationships conj {:from :buyer :to :shop/cart})) ":buyer" ":shop/cart")))

(defstep #"a context view that includes no person"
  (fn [_] (names (with-model! #(-> % (dissoc :people) (assoc :relationships []) (update :views conj {:key "world" :level :context :description "The world"}))) "world")))

(defstep #"a container declared with kind library"
  (fn [_] (names (with-model! #(update % :containers conj {:id :lib :name "Lib" :kind :library :technology "t" :description "Shared code"})) ":lib")))

(defstep #"a relationship that publishes the event SpecCut"
  (fn [_] (names (with-model! #(update % :relationships conj {:from :shop/cart :to :shop/till :description "Publishes" :event :SpecCut}))
                 ":shop/cart" ":shop/till" "SpecCut")))

(defstep #"SpecCut is not in the event catalogue"
  (fn [ctx] (k/write! (:dir ctx) "dhf/spec/events.edn" "{}") ctx))

(defstep #"the configured view limit is 20 elements"
  (fn [_] (let [ctx (with-model! identity)] (k/edit-config! (:dir ctx) #(assoc-in % [:c4 :view-element-limit] 20)) ctx)))

(defstep #"a view showing 23 elements"
  (fn [ctx]
    (k/edit-edn! (:dir ctx) "dhf/spec/model.edn"
                 #(-> % (update :people into (for [i (range 21)] {:id (keyword (str "p" i)) :name (str "P" i) :actor #{:human} :description "A person"}))
                      (update :views conj {:key "everyone" :level :context :description "Everyone"})))
    (k/fresh-views! (:dir ctx))
    (names ctx "everyone" "23" "20")))

(defstep #"it warns under rule c4-view-size and names the view and both counts"
  (fn [ctx] (k/exits! ctx 0) (k/line-has! ctx "warning: c4-view-size" "everyone" "23" "20") ctx))

(defstep #"the configured description limit is 45 characters"
  (fn [_] (let [ctx (with-model! identity)] (k/edit-config! (:dir ctx) #(assoc-in % [:c4 :description-max-chars] 45)) ctx)))

(defstep #"a person whose description is 60 characters"
  (fn [ctx]
    (k/edit-edn! (:dir ctx) "dhf/spec/model.edn" #(assoc-in % [:people 0 :description] (apply str (repeat 60 "x"))))
    (k/fresh-views! (:dir ctx))
    (names ctx ":buyer" "60" "45")))

(defstep #"its result's :lint :for-a-person is exactly \[\"Does each view serve its audience: can its reader answer the question the view is for\?\" \"Is the true coupling visible: does every relationship that matters appear, labelled with what passes\?\" \"Was each render looked at by a person, and did nothing overlap or hide a name\?\"\]"
  (fn [ctx]
    (k/expect! (= ["Does each view serve its audience: can its reader answer the question the view is for?"
                   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
                   "Was each render looked at by a person, and did nothing overlap or hide a name?"]
                  (get-in ctx [:edn :lint :for-a-person])) ctx "not the three questions")
    ctx))

(defstep #"a bounded context with components and no component view"
  (fn [_] (names (with-model! #(assoc % :views [])) ":shop")))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [_] (names (with-model! #(update-in % [:containers 0 :contexts 0 :components] conj {:id :shop/shelf :name "Shelf" :description "Shows" :spec "shop/shelf"}))
                 ":shop/shelf")))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [_] (let [ctx (with-model! identity)]
            (k/write! (:dir ctx) "dhf/spec/shop/stray.feature" (k/feature-text {:name "Stray" :scenarios [{:id "spec-0002"}]}))
            (names ctx "dhf/spec/shop/stray.feature"))))

(def two-contexts
  #(-> %
       (update-in [:containers 0 :contexts] conj {:id :pay :name "pay" :description "Paying"
                                                  :components [{:id :pay/card :name "Card" :description "Charges" :spec "shop/till"}]})
       (update :views conj {:key "pay-components" :level :component :context :pay :description "Paying"})
       (update :relationships conj {:from :shop/cart :to :pay/card :description "Asks to charge"})))

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [_] (names (with-model! two-contexts) ":shop/cart" ":pay/card")))

(defstep #"a configuration whose :c4 holds :contexts-relate-by \[:events :calls\], and a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [_] (let [ctx (with-model! two-contexts)]
            (k/edit-config! (:dir ctx) #(assoc-in % [:c4 :contexts-relate-by] [:events :calls]))
            ctx)))

(defstep #"it passes rule a-contract-only"
  (fn [ctx] (k/expect! (not (str/includes? (str (:err ctx)) "a-contract-only")) ctx "a-contract-only was found") ctx))

(defstep #"it fails rule ((?:c4|a)-[a-z-]+) and names (.+)"
  (fn [ctx rule _]
    (k/exits! ctx 1)
    (apply k/line-has! ctx rule (:names ctx))
    ctx))
