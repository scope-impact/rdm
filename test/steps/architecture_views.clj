(ns steps.architecture-views
  "Steps of the component Views (04-model-the-architecture/views.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [steps.architecture-model :as am]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defstep #"a model with a context view, a container view and two component views"
  (fn [_] (am/views-project!)))

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [v ["context" "containers" "shop-components" "pay-components"] ext [".mmd" ".svg"]]
      (k/expect! (fs/exists? (k/path (:dir ctx) (str "dhf/views/" v ext))) ctx (str "no dhf/views/" v ext)))
    (k/expect! (str/includes? (k/slurp-in (:dir ctx) "dhf/views/context.svg") "<svg") ctx "the render is not an SVG")
    ctx))

(defstep #"each output's first line is a comment holding \"model-sha256: \" and the model's hash"
  (fn [ctx]
    (let [h (k/model-hash (:dir ctx) "dhf/spec/model.edn")]
      (doseq [v ["context" "containers" "shop-components" "pay-components"]]
        (let [mmd (first (str/split-lines (k/slurp-in (:dir ctx) (str "dhf/views/" v ".mmd"))))
              svg (first (str/split-lines (k/slurp-in (:dir ctx) (str "dhf/views/" v ".svg"))))]
          (k/expect! (and (str/starts-with? mmd "%%") (str/includes? mmd (str "model-sha256: " h))) ctx (str v ".mmd's first line"))
          (k/expect! (and (str/starts-with? svg "<!--") (str/includes? svg (str "model-sha256: " h))) ctx (str v ".svg's first line")))))
    ctx))

(defstep #"it fails and names the stale view"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/views/shop-components" "stale") ctx))

(defstep #"a model with a view of level (context|container|component|dynamic)"
  (fn [_ level] (assoc (am/views-project!) :level level)))

(defn- ids-in [src]
  (set (map second (re-seq #"(?m)^\s*(?:Person|Person_Ext|System|System_Ext|Container|ContainerDb|Container_Ext|ContainerDb_Ext|Component|Component_Ext)\((\w+)," src))))

(defn- rels-in [src] (vec (map rest (re-seq #"(?m)^Rel\((\w+), (\w+), \"([^\"]*)\"\)" src))))

(defstep #"the view holds (.+)"
  (fn [ctx _]
    (k/exits! ctx 0)
    (let [d (:dir ctx)
          src (fn [k] (k/slurp-in d (str "dhf/views/" k ".mmd")))]
      (case (:level ctx)
        "context" (let [s (src "context")]
                    (k/expect! (= #{"shop_system" "buyer" "bank"} (ids-in s)) ctx (str "the context view holds " (ids-in s)))
                    (k/expect! (= [["buyer" "shop_system" "Buys with"] ["shop_system" "bank" "Charges through"]] (rels-in s)) ctx (str "its relationships are " (rels-in s))))
        "container" (let [s (src "containers")]
                      (k/expect! (= #{"app" "db" "buyer" "bank"} (ids-in s)) ctx (str "the container view holds " (ids-in s)))
                      (k/expect! (= #{["buyer" "app" "Buys with"] ["app" "db" "Writes orders to"] ["app" "bank" "Charges through"]} (set (rels-in s))) ctx (str "its relationships are " (rels-in s))))
        "component" (let [s (src "shop-components")]
                      (k/expect! (str/includes? s "Boundary(boundary__shop") ctx "no boundary")
                      (k/expect! (= #{"shop__cart" "shop__till" "db"} (ids-in s)) ctx (str "the component view holds " (ids-in s)))
                      (k/expect! (str/includes? s "ContainerDb_Ext(db") ctx "the store is not drawn as external"))
        "dynamic" (let [s (src "flow")]
                    (k/expect! (= #{"buyer" "app" "shop__till" "db"} (ids-in s)) ctx (str "the dynamic view holds " (ids-in s)))
                    (k/expect! (= [["buyer" "app" "1. Buys"] ["shop__till" "db" "2. Writes the order"]] (rels-in s)) ctx "its steps are not in order"))))
    ctx))
