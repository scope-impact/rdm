(ns steps.architecture-views
  "Step definitions of dhf/spec/architecture/views.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]
            [steps.architecture-model :as am]))

;; The model hash, as the glossary defines it (copied in: the test's own reading).
(defn canonical [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[a b]] (str (canonical a) " " (canonical b))) (sort-by (comp canonical key) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map canonical x))) "}")
        (vector? x) (str "[" (str/join " " (map canonical x)) "]")
        :else (pr-str x)))

(defn model-hash [m]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (canonical m) "UTF-8"))]
    (apply str (map #(format "%02x" %) d))))

(defstep #"a model with a context view, a container view and two component views"
  (fn [ctx] (am/arch-project! ctx am/full-model {:no-views true :extra {"dhf/spec/events.edn" (k/pr-edn am/full-events)}})))

(def view-keys ["context" "containers" "shop-components" "stock-components"])

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [v view-keys ext ["mmd" "svg"]]
      (assert (fs/exists? (fs/path (k/guard! (:dir ctx)) "dhf/views" (str v "." ext))) (str v "." ext)))
    (assert (str/includes? (slurp (fs/file (k/guard! (:dir ctx)) "dhf/views/context.svg")) "<svg"))
    ctx))

(defstep #"each output's first line is a comment holding \"model-sha256: \" and the model's hash"
  (fn [ctx]
    (let [h (model-hash (edn/read-string (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/model.edn"))))]
      (doseq [v view-keys [ext open] [["mmd" "%%"] ["svg" "<!--"]]]
        (let [l (first (str/split-lines (slurp (fs/file (k/guard! (:dir ctx)) "dhf/views" (str v "." ext)))))]
          (assert (str/starts-with? l open) l)
          (assert (str/includes? l (str "model-sha256: " h)) l)))
      ctx)))

(defstep #"a model with a view of level (\w+)"
  (fn [ctx level]
    (let [m (cond-> am/full-model
              (= level "dynamic") (update :views conj {:key "flow" :level :dynamic :description "A sale"
                                                       :steps [[:user :shop/till "1. Buys"] [:shop/till :stock/shelf "2. Reserves"]]}))]
      (am/arch-project! ctx m {:no-views true :extra {"dhf/spec/events.edn" (k/pr-edn am/full-events)}}))))

(defn view-text [ctx key] (slurp (fs/file (k/guard! (:dir ctx)) "dhf/views" (str key ".mmd"))))

(defn holds [text & lines]
  (doseq [l lines] (assert (str/includes? text l) (str "the view does not hold " l ":\n" text))))

(defn lacks [text & lines]
  (doseq [l lines] (assert (not (str/includes? text l)) (str "the view holds " l ":\n" text))))

(defstep #"the view holds (.+)"
  (fn [ctx what]
    (k/check-exit ctx 0)
    (cond
      (str/starts-with? what "the system,")
      (let [t (view-text ctx "context")]
        (holds t "System(demo," "Person(user," "System_Ext(bank," "Rel(user, demo," "Rel(demo, bank,")
        (lacks t "Container(" "Component("))
      (str/starts-with? what "every container,")
      (let [t (view-text ctx "containers")]
        (holds t "Container(app," "Person(user," "System_Ext(bank," "Rel(app, bank,")
        (lacks t "Component("))
      (str/starts-with? what "one context's components")
      (let [t (view-text ctx "shop-components")]
        (holds t "Boundary(boundary__shop" "Component(shop__till," "Component(shop__cart,"
               "Component_Ext(stock__shelf," "System_Ext(bank," "Rel(shop__till, stock__shelf, \"Publishes ItemSold\")")
        (lacks t "Component(stock__shelf,"))
      (str/starts-with? what "the elements its steps name")
      (let [t (view-text ctx "flow")]
        (holds t "C4Dynamic" "Person(user," "Component(shop__till," "Component(stock__shelf,")
        (lacks t "bank" "shop__cart")
        (assert (< (str/index-of t "1. Buys") (str/index-of t "2. Reserves"))))
      :else (fail! (str "unknown level description: " what)))
    ctx))
