(ns steps.regeneration-scopes
  "Step definitions of dhf/spec/regeneration/scopes.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

;; verification comes before spec in the model, so the order of publisher and
;; subscriber is not the model's.
(def scopes-model
  (-> (k/model {:contexts [["verification" ["status"]] ["spec" ["parser"]] ["architecture" ["views"]]
                           ["shop" ["cart" "till"]] ["stock" ["shelf"]] ["books" ["ledger"]]]
                :kernel ["command" "bus"]})
      (update :relationships into [{:from :spec/parser :to :verification/status :description "Publishes" :event :SpecParsed}
                                   {:from :shop/till :to :books/ledger :description "Publishes" :event :Sold}
                                   {:from :shop/till :to :stock/shelf :description "Publishes" :event :Sold}])))

(def events
  {:SpecParsed {:description "The spec was read" :published-by :spec/parser :carries [:scenarios] :shape {:scenarios "[scenario]"}}
   :Sold {:description "An item was sold" :published-by :shop/till :carries [:item :price] :shape {:item "the item" :price "a number"}
          :read-by {:books/ledger [:price]}}})

(defn feature [path n] (k/feature-text {:name path :tags ["UN-001"] :scenarios [(k/sc n (str "About " path))]}))

(def comps ["verification/status" "spec/parser" "architecture/views" "shop/cart" "shop/till" "stock/shelf" "books/ledger"
            "kernel/command" "kernel/bus"])

(defn scopes-project! [ctx]
  (let [ctx (k/project! ctx {:features (into {} (map-indexed (fn [i c] [(str "dhf/spec/" c ".feature") (feature c (inc i))]) comps))
                             :extra {"dhf/spec/model.edn" (k/pr-edn scopes-model)
                                     "dhf/spec/events.edn" (k/pr-edn events)
                                     "dhf/spec/glossary.md" "# Glossary\n"}})]
    (fs/delete-if-exists (fs/path (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature"))
    (k/tag-cut! ctx "spec-3")))

(defn touch! [ctx path]
  (let [f (fs/file (k/guard! (:dir ctx)) path)]
    (spit f (str (slurp f) "\n# changed\n"))
    ctx))

(defn events! [ctx f] (k/update-edn! ctx "dhf/spec/events.edn" f))

(def changes
  {"architecture/views.feature and kernel/bus.feature changed"
   #(-> % (touch! "dhf/spec/architecture/views.feature") (touch! "dhf/spec/kernel/bus.feature"))
   "only architecture/views.md, design prose that spec-3 did not have, was added"
   #(k/write-files! (:dir %) {"dhf/spec/architecture/views.md" "# Views\n\nHow views are drawn.\n"})
   "only dhf/spec/glossary.md changed"
   #(touch! % "dhf/spec/glossary.md")
   "only the shape of one carried key of one event in dhf/spec/events.edn changed"
   #(events! % (fn [e] (assoc-in e [:Sold :shape :price] "a price in cents")))
   "only an event's :published-by changed from shop/cart to shop/till"
   identity
   "only an event's :description changed"
   #(events! % (fn [e] (assoc-in e [:Sold :description] "An item left the shop")))
   "only an event lost a key its catalogue entry does not declare, as :read-by was"
   #(events! % (fn [e] (update e :Sold dissoc :read-by)))
   "the kernel's command, the spec parser and the status component changed"
   #(-> % (touch! "dhf/spec/kernel/command.feature") (touch! "dhf/spec/verification/status.feature")
        (touch! "dhf/spec/spec/parser.feature"))})

(defstep #"the cuts spec-3 and spec-4, between which (.+)"
  (fn [ctx what]
    (let [change (or (changes what) (fail! (str "no such change: " what)))]
      (if (= what "only an event's :published-by changed from shop/cart to shop/till")
        ;; spec-3: shop/cart publishes Sold; spec-4: shop/till does
        (let [m3 (update scopes-model :relationships
                         (fn [rs] (mapv #(if (= :Sold (:event %)) (assoc % :from :shop/cart) %) rs)))
              ctx (k/project! ctx {:features (into {} (map-indexed (fn [i c] [(str "dhf/spec/" c ".feature") (feature c (inc i))]) comps))
                                   :extra {"dhf/spec/model.edn" (k/pr-edn m3)
                                           "dhf/spec/events.edn" (k/pr-edn (assoc-in events [:Sold :published-by] :shop/cart))}})]
          (fs/delete-if-exists (fs/path (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature"))
          (k/tag-cut! ctx "spec-3")
          (k/write-files! (:dir ctx) {"dhf/spec/model.edn" (k/pr-edn scopes-model) "dhf/spec/events.edn" (k/pr-edn events)})
          (k/tag-cut! ctx "spec-4"))
        (let [ctx (scopes-project! ctx)]
          (change ctx)
          (k/tag-cut! ctx "spec-4"))))))

(defn scopes [ctx]
  (k/check-exit ctx 0)
  (get-in (k/edn-out ctx) [:scopes :scopes]))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (assert (= [{:component :kernel/bus} {:component :architecture/views}] (scopes ctx)) (:out ctx)) ctx))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (assert (= [{:component :architecture/views}] (scopes ctx)) (:out ctx)) ctx))

(defstep #"it lists no scope, and names glossary.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (assert (= [] (scopes ctx)) (:out ctx))
    (k/err-line-with ctx "warning:" "glossary.md")
    ctx))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the model's order"
  (fn [ctx]
    (assert (= [{:component :shop/till} {:component :stock/shelf} {:component :books/ledger}] (scopes ctx)) (:out ctx))
    ctx))

(defstep #"it lists as scopes shop/cart, shop/till and every subscriber the model's relationships name for that event, in the order the next rule gives"
  (fn [ctx]
    (assert (= [{:component :shop/cart} {:component :shop/till} {:component :stock/shelf} {:component :books/ledger}] (scopes ctx)) (:out ctx))
    ctx))

(defstep #"it lists no scope"
  (fn [ctx] (assert (= [] (scopes ctx)) (:out ctx)) ctx))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx]
    (assert (= [{:component :kernel/command} {:component :spec/parser} {:component :verification/status}] (scopes ctx)) (:out ctx))
    ctx))

(defstep #"components with no order between them come in the model's order, the subscribers of one event among them \(cycles 18 and 19 read spec-0298's \"the order they name them\" two ways; spec-24 settles on the model's\)"
  (fn [ctx]
    ;; verification/status comes first in the model, yet after spec/parser, its
    ;; publisher; with no order between them (spec-0298) the model's order holds.
    (let [s (scopes ctx)]
      (assert (= :kernel/command (:component (first s))))
      (assert (< (.indexOf s {:component :spec/parser}) (.indexOf s {:component :verification/status}))))
    ctx))
