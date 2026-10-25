(ns steps.architecture-views
  "Step definitions for dhf/spec/architecture/views.feature, and the step
  `a rendered view whose recorded hash differs from the model's`, which
  dhf/spec/publishing/site.feature shares (it sets :dir and :stale-view).

  The views' scenarios run `rdm-spec views` on a small model of their own (a
  shop: two people, a bank, an app of two bounded contexts, a database and
  a kernel component), and read the diagram source back to see exactly
  which elements and relationships each view holds."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The fixture

(def shop
  {:system {:id :shop :name "Shop" :description "Sells things"}
   :people [{:id :buyer :name "Buyer" :description "Buys things" :actor #{:human :agent}}
            {:id :clerk :name "Clerk" :description "Keeps the books" :actor #{:human}}]
   :external [{:id :bank :name "Bank" :description "Moves the money"}]
   :containers [{:id :app :name "App" :kind :runs :technology "a program" :description "The shop's program"
                 :contexts [{:id :orders :name "orders" :description "Takes orders"
                             :components [{:id :orders/cart :name "Cart" :spec "orders/cart" :description "Holds the cart"}
                                          {:id :orders/pay :name "Pay" :spec "orders/pay" :description "Takes payment"}]}
                            {:id :stock :name "stock" :description "Counts the stock"
                             :components [{:id :stock/count :name "Count" :spec "stock/count" :description "Counts items"}]}]}
                {:id :db :name "Database" :kind :stores :technology "files" :description "Holds the counts"}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus" :description "Carries the events"}]
   :relationships [{:from :buyer :to :app :description "Buys through"}
                   {:from :clerk :to :bank :description "Reconciles with"}
                   {:from :app :to :bank :description "Charges through"}
                   {:from :orders/cart :to :orders/pay :description "Hands the cart to"}
                   {:from :orders/pay :to :stock/count :description "Publishes" :event :Paid}
                   {:from :stock/count :to :db :description "Writes counts to"}
                   {:from :orders/pay :to :kernel/bus :description "Publishes on"}
                   {:from :bank :to :db :description "Reports to"}]
   :views [{:key "context" :level :context :description "The shop and who uses it"}
           {:key "containers" :level :container :description "The program and its store"}
           {:key "orders-components" :level :component :context :orders :description "Taking orders"}
           {:key "stock-components" :level :component :context :stock :description "Counting stock"}
           {:key "checkout" :level :dynamic :description "One checkout"
            :steps [[:buyer :app "1. Opens a cart"]
                    [:orders/cart :orders/pay "2. Pays"]
                    [:orders/pay :stock/count "3. Counts down the stock"]]}]})

(def shop-events {:Paid {:description "An order was paid" :published-by :orders/pay :carries []}})

(def expected
  "What each level's view of the shop holds: its elements (diagram id ->
  how it is drawn, :in when inside the boundary) and its relationships."
  {"context" {:key "context"
              :elements {"shop" ["System" :out] "buyer" ["Person" :out] "clerk" ["Person" :out]
                         "bank" ["System_Ext" :out]}
              :rels #{["buyer" "shop"] ["clerk" "bank"] ["shop" "bank"] ["bank" "shop"]}}
   "container" {:key "containers"
                :elements {"app" ["Container" :in] "db" ["ContainerDb" :in] "buyer" ["Person" :out]
                           "clerk" ["Person" :out] "bank" ["System_Ext" :out]}
                :rels #{["buyer" "app"] ["clerk" "bank"] ["app" "bank"] ["app" "db"] ["bank" "db"]}}
   "component" {:key "orders-components"
                :elements {"orders__cart" ["Component" :in] "orders__pay" ["Component" :in]
                           "stock__count" ["Component_Ext" :out] "kernel__bus" ["Component_Ext" :out]}
                :rels #{["orders__cart" "orders__pay"] ["orders__pay" "stock__count"] ["orders__pay" "kernel__bus"]}}
   "dynamic" {:key "checkout"
              :elements {"buyer" ["Person" :out] "app" ["Container" :out] "orders__cart" ["Component" :out]
                         "orders__pay" ["Component" :out] "stock__count" ["Component" :out]}
              :rels [["buyer" "app" "1. Opens a cart"] ["orders__cart" "orders__pay" "2. Pays"]
                     ["orders__pay" "stock__count" "3. Counts down the stock"]]}})

(defn- shop! []
  (a/needs-architecture!)
  (let [dir (s/project!)]
    (a/write-model! dir shop)
    (s/write! dir a/events-rel (pr-str shop-events))
    dir))

(defn- views-dir [dir] (fs/file dir (or (:views-dir (s/room-config)) "dhf/views")))

(defn- formats
  "The output formats dhf/harness/stack.md names for a view (dhf/views/<key>.EXT)."
  []
  (let [text (slurp (str (fs/file s/room-root "dhf/harness/stack.md")))
        exts (distinct (map second (re-seq #"dhf/views/<key>\.(\w+)" text)))]
    (s/check! (seq exts) "dhf/harness/stack.md names no output format of a view")
    (vec exts)))

;; --- reading a diagram source back -------------------------------------------

(def element-re
  #"^(\s*)(Person|Person_Ext|System|System_Ext|Container|ContainerDb|Container_Ext|ContainerDb_Ext|Component|Component_Ext)\((\w+),.*")

(defn parse-diagram
  "{:elements {id [macro :in|:out]} :rels [[from to label]]} of a diagram source."
  [text]
  (loop [[l & more] (str/split-lines text) depth 0 els {} rels []]
    (if (nil? l)
      {:elements els :rels rels}
      (cond
        (re-find #"Boundary\(.*\{\s*$" l) (recur more (inc depth) els rels)
        (= "}" (str/trim l)) (recur more (dec depth) els rels)
        :else
        (if-let [[_ _ macro id] (re-matches element-re l)]
          (recur more depth (assoc els id [macro (if (pos? depth) :in :out)]) rels)
          (if-let [[_ from to label] (re-matches #"^\s*Rel\((\w+), (\w+), \"(.*)\"\)\s*$" l)]
            (recur more depth els (conj rels [from to label]))
            (recur more depth els rels)))))))

;; ---------------------------------------------------------------------------
;; The C4 model is one source and every view is generated from it

(defstep #"a model with a context view, a container view and two component views"
  (fn [ctx]
    (let [levels (frequencies (map :level (:views shop)))]
      (s/check! (and (= 1 (levels :context)) (= 1 (levels :container)) (= 2 (levels :component)))
                "the fixture holds other views")
      (assoc ctx :dir (shop!)))))

(defstep #"each view's diagram source is written to dhf/views/<key> and rendered beside it, in the formats dhf/harness/stack.md names"
  (fn [ctx]
    (let [r (a/the-run ctx)
          vd (views-dir (:dir ctx))
          exts (formats)]
      (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (some #{"svg"} exts) (str "no rendering among the formats " exts))
      (doseq [{k :key} (:views shop) ext exts
              :let [f (fs/file vd (str k "." ext))]]
        (s/check! (fs/exists? f) (str "no " f "\n" (s/show r)))
        (when (= "svg" ext)
          (s/check! (str/includes? (slurp (str f)) "<svg") (str f " is not a rendered SVG")))))))

(defstep #"each output's first line is a comment holding \"model-sha256: \" and the model's hash"
  (fn [ctx]
    (let [vd (views-dir (:dir ctx))
          h (a/hash-of (a/read-model (:dir ctx)))]
      (doseq [{k :key} (:views shop) ext (formats)
              :let [f (fs/file vd (str k "." ext))
                    line (first (str/split-lines (slurp (str f))))]]
        (s/check! (str/includes? line (str "model-sha256: " h)) (str f "'s first line does not record " h ": " line))
        (s/check! (case ext
                    "mmd" (str/starts-with? line "%%")
                    "svg" (and (str/starts-with? line "<!--") (str/ends-with? (str/trim line) "-->"))
                    true)
                  (str f "'s first line is not a comment: " line))))))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          _ (when (s/built? 'rdm-spec.architecture.views) (a/views! dir))
          k "containers"
          other (apply str (repeat 64 "0"))
          vd (views-dir dir)]
      (doseq [ext ["mmd" "svg"]
              :let [f (str (fs/file vd (str k "." ext)))
                    [first-line & more] (str/split-lines (slurp f))
                    changed (str/replace first-line #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " other))]]
        (s/check! (not= first-line changed) (str f " records no model hash: " first-line))
        (spit f (str (str/join "\n" (cons changed more)) "\n")))
      (assoc ctx :dir dir :stale-view k))))

(defstep #"it fails and names the stale view"
  (fn [ctx]
    (let [r (a/the-run ctx)
          k (:stale-view ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (a/line-with r (pr-str k) "stale") (str "no finding names the view " k " as stale\n" (s/show r)))
      (s/check! (every? #(str/includes? % (pr-str k)) (filter #(str/includes? % "stale") (a/finding-lines r)))
                (str "other views than " k " are named stale\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Each level of view holds what C4 says it holds

(defstep #"a model with a view of level (\w+)"
  (fn [ctx level]
    (let [e (or (expected level) (s/fail! (str "no level " level)))]
      (s/check! (some #(and (= (:key e) (:key %)) (= (keyword level) (:level %))) (:views shop))
                (str "the fixture has no view of level " level))
      (assoc ctx :dir (shop!) :level level))))

(defstep #"the view holds (.+)"
  (fn [ctx _what]
    (let [r (a/the-run ctx)
          e (expected (:level ctx))
          f (fs/file (views-dir (:dir ctx)) (str (:key e) ".mmd"))
          _ (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
          d (parse-diagram (slurp (str f)))
          rels (map (fn [[a b _]] [a b]) (:rels d))]
      (s/check! (= (:elements e) (:elements d))
                (str "the " (:level ctx) " view holds " (pr-str (:elements d)) ", not " (pr-str (:elements e))))
      (if (= "dynamic" (:level ctx))
        (s/check! (= (:rels e) (:rels d)) (str "the steps are " (pr-str (:rels d)) ", not " (pr-str (:rels e))))
        (do (s/check! (= (count rels) (count (set rels))) (str "a relationship is drawn twice: " (pr-str rels)))
            (s/check! (= (:rels e) (set rels))
                      (str "the " (:level ctx) " view relates " (pr-str (set rels)) ", not " (pr-str (:rels e)))))))))
