(ns steps.architecture-lint
  "Step definitions of dhf/spec/architecture/lint.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.architecture-model :as am]))

(def two-contexts
  (k/model {:contexts [["shop" ["till"]] ["stock" ["shelf"]]]
            :views [{:key "context" :level :context :description "The demo and its users"}]}))

(defn lint-project!
  "A project of the model m (two contexts and a context view unless given),
  its views not generated."
  ([ctx f] (lint-project! ctx f {}))
  ([ctx f opts] (am/arch-project! ctx (f two-contexts) (merge {:no-views true} opts))))

(defstep #"a view with no description"
  (fn [ctx] (-> (lint-project! ctx #(update-in % [:views 0] dissoc :description)) (k/names+ "c4-scope" "context"))))

(defstep #"a container with no description"
  (fn [ctx] (-> (lint-project! ctx #(update-in % [:containers 0] dissoc :description))
                (k/names+ "c4-element-described" ":app"))))

(defstep #"a relationship with no description"
  (fn [ctx] (-> (lint-project! ctx #(update-in % [:relationships 0] dissoc :description))
                (k/names+ "c4-arrow-labelled" ":user" ":app"))))

(defstep #"a context view that includes no person"
  (fn [ctx] (-> (lint-project! ctx #(-> % (assoc :people []) (assoc :relationships [])))
                (k/names+ "c4-context-actors" "context"))))

(defstep #"a container declared with kind library"
  (fn [ctx] (-> (lint-project! ctx #(assoc-in % [:containers 0 :kind] :library))
                (k/names+ "c4-container-runs" ":app"))))

(defstep #"a relationship that publishes the event SpecCut"
  (fn [ctx] (-> (lint-project! ctx #(update % :relationships conj
                                            {:from :shop/till :to :stock/shelf :description "Publishes" :event :SpecCut}))
                (k/names+ "c4-events-named" ":shop/till -> :stock/shelf" "SpecCut"))))

(defstep #"SpecCut is not in the event catalogue"
  (fn [ctx]
    (assert (not (str/includes? (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/events.edn")) "SpecCut")))
    ctx))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n] (k/project! ctx {:config {:c4 {:view-element-limit (parse-long n) :description-max-chars 45}}})))

(defstep #"a view showing 23 elements"
  (fn [ctx]
    (let [comps (mapv #(str "part" %) (range 1 24))
          m (k/model {:contexts [["shop" comps]] :kernel ["core"]})]
      (-> (am/arch-project! ctx m)
          (k/names+ "c4-view-size" "shop-components" "23" "20")))))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n] (k/project! ctx {:config {:c4 {:view-element-limit 20 :description-max-chars (parse-long n)}}})))

(defstep #"a person whose description is 60 characters"
  (fn [ctx]
    (let [d (apply str (take 60 (cycle "A person who uses the demo every day. ")))]
      (assert (= 60 (count d)))
      (-> (lint-project! ctx #(assoc-in % [:people 0 :description] d))
          (k/names+ "c4-description-length" ":user" "60" "45")))))

(defstep #"it warns under rule (\S+) and names .+"
  (fn [ctx rule]
    (k/check-exit ctx 0)
    (assert (= rule (first (:names ctx))))
    (apply k/err-line-with ctx "warning: " (:names ctx))))

(defstep #"its result's :lint :for-a-person is exactly (\[.+\])"
  (fn [ctx items]
    (k/check-exit ctx 0)
    (let [want (clojure.edn/read-string items)
          got (get-in (k/edn-out ctx) [:lint :for-a-person])]
      (assert (= want got) (pr-str got))
      ctx)))

(defstep #"a bounded context with components and no component view"
  (fn [ctx]
    (-> (lint-project! ctx #(update % :views (fn [vs] (vec (remove (fn [v] (= :stock (:context v))) vs)))))
        (k/names+ "a-component-view" ":stock"))))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (let [ctx (lint-project! ctx identity)]
      (fs/delete (fs/path (:dir ctx) "dhf/spec/stock/shelf.feature"))
      (k/names+ ctx "a-scenario-owner" ":stock/shelf" "dhf/spec/stock/shelf.feature"))))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (lint-project! ctx identity)]
      (k/write-files! (:dir ctx) {"dhf/spec/stock/ghost.feature" (am/feature-for "stock/ghost" 9)})
      (k/names+ ctx "a-scenario-owner" "dhf/spec/stock/ghost.feature"))))

(def call {:from :shop/till :to :stock/shelf :description "Reads the shelf through"})

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (-> (lint-project! ctx #(update % :relationships conj call))
        (k/names+ "a-contract-only" ":shop/till -> :stock/shelf"))))

(defstep #"a configuration whose :c4 holds :contexts-relate-by \[:events :calls\], and a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (lint-project! ctx #(update % :relationships conj call)
                   {:no-views false :config {:c4 {:view-element-limit 20 :description-max-chars 45
                                                  :contexts-relate-by [:events :calls]}}})))

(defstep #"it passes rule (\S+)"
  (fn [ctx rule]
    (k/check-exit ctx 0)
    (assert (not-any? #(str/includes? % rule) (k/err-lines ctx)) (:err ctx))
    ctx))
