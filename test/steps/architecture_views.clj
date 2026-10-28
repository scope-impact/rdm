(ns steps.architecture-views
  "Steps of the component Views (dhf/spec/04-model-the-architecture/views.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.architecture-model :as m]))

(def full-model
  (-> (k/context-model [[:shop [[:cart "Cart"] [:till "Till"]]] [:stock [[:shelf "Shelf"]]]])
      (assoc :external [{:id :bank :name "Bank" :description "Takes payments"}])
      (update :containers conj {:id :db :name "Database" :kind :stores :technology "files" :description "Keeps the orders"})
      (update :relationships into [{:from :shop/till :to :bank :description "Charges"}
                                   {:from :shop/cart :to :db :description "Writes orders to"}
                                   {:from :shop/cart :to :stock/shelf :description "Publishes" :event :OrderPlaced}
                                   {:from :user :to :bank :description "Pays with"}])
      (update :views conj {:key "containers" :level :container :description "The program and its store"}
              {:key "checkout" :level :dynamic :description "One checkout"
               :steps [[:user :app "1. Shops"] [:shop/till :bank "2. Charges"]]})))

(defn full-project! [ctx]
  (let [ctx (k/spec-project! ctx {:model full-model :features (m/features-for full-model)})]
    (k/write-edn! ctx "dhf/spec/events.edn" {:OrderPlaced {:description "An order" :published-by :shop/cart :carries [:order] :shape {:order "it"}}})
    ctx))

(defn hash-of [ctx]
  (second (re-find #"model-sha256: ([0-9a-f]{64})" (k/slurp-rel ctx "dhf/views/context.mmd"))))

(defstep #"a model with a context view, a container view and two component views"
  (fn [ctx] (let [ctx (full-project! ctx)] (fs/delete-tree (k/path ctx "dhf/views")) ctx)))

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [v ["context" "containers" "shop-components" "stock-components" "checkout"] ext [".mmd" ".svg"]]
      (k/check! (fs/exists? (k/path ctx (str "dhf/views/" v ext))) "no dhf/views/" v ext))
    (k/check! (str/includes? (k/slurp-rel ctx "dhf/views/context.svg") "<svg") "the render is not an SVG")
    ctx))

(defstep #"each output's first line is a comment holding \"model-sha256: \" and the model's hash"
  (fn [ctx]
    (let [h (hash-of ctx)]
      (k/check! (and h (re-matches #"[0-9a-f]{64}" h)) "no hash")
      (doseq [f (fs/list-dir (k/path ctx "dhf/views"))]
        (let [first-line (first (str/split-lines (slurp (str f))))]
          (k/check! (and (str/includes? first-line (str "model-sha256: " h))
                         (or (str/starts-with? first-line "%%") (str/starts-with? first-line "<!--")))
                    (fs/file-name f) "'s first line is " first-line)))
      ctx)))

(defstep #"it fails and names the stale view"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/views/context" "stale") ctx))

(defstep #"a model with a view of level (context|container|component|dynamic)"
  (fn [ctx level] (assoc (full-project! ctx) ::level level)))

(defn ids-in [text]
  (set (for [[_ id] (re-seq #"(?m)^\s*(?:Person|Person_Ext|System|System_Ext|Container|ContainerDb|Container_Ext|ContainerDb_Ext|Component|Component_Ext|System_Boundary|Boundary)\(([A-Za-z0-9_]+)," text)
             :when (not (str/starts-with? id "boundary__"))]
         id)))

(defn rels-in [text] (set (for [[_ a b] (re-seq #"(?m)^\s*Rel\(([A-Za-z0-9_]+), ([A-Za-z0-9_]+)," text)] [a b])))

(defstep #"the view holds (.+)"
  (fn [ctx _]
    (k/exits! ctx 0)
    (let [[file ids rels] (case (::level ctx)
                            "context" ["context" #{"demo_system" "user" "bank"} #{["user" "demo_system"] ["demo_system" "bank"] ["user" "bank"]}]
                            "container" ["containers" #{"app" "db" "user" "bank"} #{["user" "app"] ["app" "bank"] ["app" "db"] ["user" "bank"]}]
                            "component" ["shop-components" #{"shop__cart" "shop__till" "bank" "db" "stock__shelf"}
                                         #{["shop__till" "bank"] ["shop__cart" "db"] ["shop__cart" "stock__shelf"]}]
                            "dynamic" ["checkout" #{"user" "app" "shop__till" "bank"} #{["user" "app"] ["shop__till" "bank"]}])
          text (k/slurp-rel ctx (str "dhf/views/" file ".mmd"))]
      (k/check! (= ids (ids-in text)) "the view holds " (pr-str (ids-in text)) ":\n" text)
      (k/check! (= rels (rels-in text)) "the view relates " (pr-str (rels-in text)) ":\n" text)
      (when (= "component" (::level ctx))
        (k/check! (re-find #"Boundary\(boundary__shop" text) "the context is not drawn as a boundary")
        (k/check! (re-find #"Component_Ext\(stock__shelf" text) "the outside component is not drawn as external"))
      (when (= "dynamic" (::level ctx))
        (k/check! (< (str/index-of text "1. Shops") (str/index-of text "2. Charges")) "the steps are out of order"))
      ctx)))
