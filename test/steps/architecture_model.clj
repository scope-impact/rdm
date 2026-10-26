(ns steps.architecture-model
  "Step definitions of dhf/spec/architecture/model.feature, and the
  architecture context's helpers."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn feature-for [comp-spec n]
  (k/feature-text {:name (str/capitalize (last (str/split comp-spec #"/"))) :tags ["UN-001"]
                   :scenarios [(k/sc n (str "Scenario " n))]}))

(defn arch-project!
  "A project whose model is `m` (a map), one feature per component, views
  generated unless :no-views."
  ([ctx m] (arch-project! ctx m {}))
  ([ctx m {:keys [no-views extra config]}]
   (let [comps (concat (for [c (:containers m) cx (:contexts c) k (:components cx) :when (:spec k)] (:spec k))
                       (for [k (:kernel m) :when (:spec k)] (:spec k)))
         ctx (k/project! ctx {:features (into {} (map-indexed (fn [i s] [(str "dhf/spec/" s ".feature") (feature-for s (inc i))]) comps))
                              :config config
                              :extra (merge {"dhf/spec/model.edn" (k/pr-edn m)} extra)})]
     (when-not (some #(= "kernel/core" %) comps)
       (fs/delete-if-exists (fs/path (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature")))
     (if no-views ctx (k/views! ctx)))))

(def full-model
  "Two contexts, a kernel component, and a view of every level."
  (-> (k/model {:contexts [["shop" ["till" "cart"]] ["stock" ["shelf"]]] :kernel ["core"]
                :views [{:key "context" :level :context :description "The demo and its users"}
                        {:key "containers" :level :container :description "The program"}]})
      (update :relationships conj {:from :shop/till :to :stock/shelf :description "Publishes" :event :ItemSold})
      (assoc :external [{:id :bank :name "Bank" :description "Takes payments"}])
      (update :relationships conj {:from :shop/till :to :bank :description "Charges through"})))

(def full-events {:ItemSold {:description "An item was sold" :published-by :shop/till :carries [:item] :shape {:item "the item"}}})

(defn full-project! [ctx]
  (arch-project! ctx full-model {:extra {"dhf/spec/events.edn" (k/pr-edn full-events)}}))

(defn model-text! [ctx text]
  (k/write-files! (:dir ctx) {"dhf/spec/model.edn" text})
  ctx)

(defstep #"a model file with an unclosed map on line 12"
  (fn [ctx]
    (let [ctx (k/project! ctx)
          lines (concat [";; a model" "{:system {:id :demo :name \"demo\" :description \"A demo\"}"]
                        (repeat 9 ";; filler")
                        [" :people [{:id :user :name \"User\" :description \"Uses it\""
                         " :views []}"])]
      (model-text! ctx (str (str/join "\n" lines) "\n")))))

(defstep #"it exits 1 and names the file and line 12"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "dhf/spec/model.edn:12")))

(defstep #"a model file with a \"\}\" on line 7 that closes nothing"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (model-text! ctx (str "{:system {:id :demo :name \"demo\" :description \"A demo\"}\n"
                            " :people []\n :external []\n :containers []\n :relationships []\n :views []}\n}\n")))))

(defstep #"it exits 1 and names the file, line 7 and the \"\}\""
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "dhf/spec/model.edn:7" "}")))

(defstep #"a container carrying the key :owner, which the model's shape does not declare"
  (fn [ctx]
    (arch-project! ctx (assoc-in (k/model {:contexts [["shop" ["till"]]]}) [:containers 0 :owner] "Sami") {:no-views true})))

(defstep #"it exits 1 and names the container and the key"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx ":app" ":owner")))

(defstep #"a container and a component both with the id :alpha"
  (fn [ctx]
    (arch-project! ctx (-> (k/model {:contexts [["shop" ["till"]]]})
                           (assoc-in [:containers 0 :id] :alpha)
                           (assoc-in [:containers 0 :contexts 0 :components 0 :id] :alpha))
                   {:no-views true})))

(defstep #"it exits 1 and names the id and both elements"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx ":alpha" "container" "component")))

(defstep #"a relationship from :spec to :archive, and no element :archive"
  (fn [ctx]
    (arch-project! ctx (-> (k/model {:contexts [["spec" ["parser"]]]})
                           (update :relationships conj {:from :spec :to :archive :description "Stores in"}))
                   {:no-views true})))

(defstep #"it exits 1 and names the relationship and :archive"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx ":spec -> :archive" ":archive")))

(defstep #"the component :spec/parser with the spec path \"spec/parser\""
  (fn [ctx] (arch-project! ctx (k/model {:contexts [["spec" ["parser"]]]}))))

(defstep #"the component owns the feature file dhf/spec/spec/parser.feature, and \"rdm-spec check\" passes only when that file exists"
  (fn [ctx]
    (k/check-exit ctx 0)
    (fs/delete (fs/path (k/guard! (:dir ctx)) "dhf/spec/spec/parser.feature"))
    (let [r (k/rdm ctx ["check"])]
      (k/check-exit r 1)
      (k/err-line-with r ":spec/parser" "dhf/spec/spec/parser.feature")
      ctx)))

(defstep #"views generated from the model"
  (fn [ctx] (full-project! ctx)))

(defstep #"the model's comments and whitespace are changed and nothing else"
  (fn [ctx]
    (let [f (fs/file (k/guard! (:dir ctx)) "dhf/spec/model.edn")
          text (slurp f)]
      (spit f (str ";; a comment added\n\n" (str/replace text "\n" "\n\n  ") "\n;; and another\n"))
      ctx)))

(defstep #"it reports no stale view"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (str/includes? (:err ctx) "stale")) (:err ctx))
    ctx))

(defstep #"one element's description is changed"
  (fn [ctx]
    (-> (k/update-edn! ctx "dhf/spec/model.edn" assoc-in [:system :description] "Another description")
        (k/names+ "dhf/views/context." "dhf/views/containers." "dhf/views/shop-components." "dhf/views/stock-components."))))

