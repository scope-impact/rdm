(ns steps.architecture-lint
  "Step definitions of dhf/spec/architecture/lint.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.architecture-model :as am]))

(def base (k/model {:contexts [["shop" ["till"]] ["stock" ["shelf"]]]
                    :views [{:key "context" :level :context :description "The demo and its users"}]}))

(defn lint-project! [ctx m & [opts]]
  (am/arch-project! ctx m (merge {:no-views true} opts)))

(defstep #"a view with no description"
  (fn [ctx] (-> (lint-project! ctx (assoc-in base [:views 0 :description] nil)) (k/names+ "context"))))

(defstep #"a container with no description"
  (fn [ctx] (-> (lint-project! ctx (assoc-in base [:containers 0 :description] nil)) (k/names+ ":app"))))

(defstep #"a relationship with no description"
  (fn [ctx] (-> (lint-project! ctx (update base :relationships conj {:from :shop/till :to :user}))
                (k/names+ ":shop/till" ":user"))))

(defstep #"a context view that includes no person"
  (fn [ctx] (-> (lint-project! ctx (assoc base :people [] :relationships [])) (k/names+ "context"))))

(defstep #"a container declared with kind library"
  (fn [ctx] (-> (lint-project! ctx (assoc-in base [:containers 0 :kind] :library)) (k/names+ ":app"))))

(defstep #"a relationship that publishes the event SpecCut"
  (fn [ctx] (-> (lint-project! ctx (update base :relationships conj {:from :shop/till :to :stock/shelf :description "Publishes" :event :SpecCut}))
                (k/names+ ":shop/till -> :stock/shelf" "SpecCut"))))

(defstep #"SpecCut is not in the event catalogue"
  (fn [ctx]
    (assert (not (str/includes? (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/events.edn")) "SpecCut")))
    ctx))

(defstep #"the configured view limit is 20 elements"
  (fn [ctx] (assoc ctx ::limit 20)))

(defstep #"a view showing 23 elements"
  (fn [ctx]
    (lint-project! ctx (update base :people into (for [i (range 21)] {:id (keyword (str "p" i)) :name (str "P" i)
                                                                        :description "A person" :actor #{:human}}))
                   {:config {:c4 {:view-element-limit 20 :description-max-chars 45}}})))

(defstep #"it warns under rule c4-view-size and names the view and both counts"
  (fn [ctx]
    (k/err-line-with ctx "warning: c4-view-size" "context" "23" "20")))

(defstep #"the configured description limit is 45 characters"
  (fn [ctx] ctx))

(defstep #"a person whose description is 60 characters"
  (fn [ctx]
    (-> (lint-project! ctx (assoc-in base [:people 0 :description] (apply str (repeat 60 "x")))
                       {:config {:c4 {:view-element-limit 20 :description-max-chars 45}}})
        (k/names+ ":user" "60" "45"))))

(defstep #"its result's :lint :for-a-person is exactly \[\"Does each view serve its audience: can its reader answer the question the view is for\?\" \"Is the true coupling visible: does every relationship that matters appear, labelled with what passes\?\" \"Was each render looked at by a person, and did nothing overlap or hide a name\?\"\]"
  (fn [ctx]
    (assert (= ["Does each view serve its audience: can its reader answer the question the view is for?"
                "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
                "Was each render looked at by a person, and did nothing overlap or hide a name?"]
               (get-in (k/edn-out ctx) [:lint :for-a-person]))
            (:out ctx))
    ctx))

(defstep #"a bounded context with components and no component view"
  (fn [ctx] (-> (lint-project! ctx (update base :views (fn [vs] (vec (remove #(= :stock (:context %)) vs)))))
                (k/names+ ":stock"))))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (let [ctx (lint-project! ctx base)]
      (fs/delete (fs/path (k/guard! (:dir ctx)) "dhf/spec/stock/shelf.feature"))
      (k/names+ ctx ":stock/shelf"))))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (lint-project! ctx base)]
      (k/write-files! (:dir ctx) {"dhf/spec/stray/orphan.feature" (am/feature-for "stray/orphan" 9)})
      (k/names+ ctx "dhf/spec/stray/orphan.feature"))))

(def crossing {:from :shop/till :to :stock/shelf :description "Reserves stock with"})

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx] (-> (lint-project! ctx (update base :relationships conj crossing))
                (k/names+ ":shop/till -> :stock/shelf"))))

(defstep #"a configuration whose :c4 holds :contexts-relate-by \[:events :calls\], and a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (lint-project! ctx (update base :relationships conj crossing)
                   {:config {:c4 {:view-element-limit 20 :description-max-chars 45 :contexts-relate-by [:events :calls]}}
                    :no-views false})))

(defstep #"it passes rule a-contract-only"
  (fn [ctx]
    (assert (#{0 1} (:exit ctx)) (:err ctx))
    (assert (not-any? #{"a-contract-only"} (k/finding-rules ctx)) (:err ctx))
    (k/check-exit ctx 0)))
