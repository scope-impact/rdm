(ns steps.regeneration-scopes
  "Steps of the component Scopes (dhf/spec/06-regenerate/scopes.feature)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def model
  (-> (k/context-model [[:spec [[:parser "Parser"] [:stamper "Stamper"]]]
                        [:architecture [[:views "Views"]]]
                        [:verification [[:status "Status"] [:verify "Verify"]]]
                        [:shop [[:cart "Cart"] [:till "Till"]]]
                        [:measurement [[:checklist "Checklist"]]]
                        [:stock [[:shelf "Shelf"]]]])
      (assoc :kernel [{:id :kernel/command :name "Command" :spec "kernel/command" :description "The command"}
                      {:id :kernel/bus :name "Event bus" :spec "kernel/bus" :description "Delivers events"}])
      (update :relationships into [{:from :spec/parser :to :verification/status :description "Publishes" :event :SpecParsed}
                                   {:from :spec/parser :to :measurement/checklist :description "Publishes" :event :SpecParsed}
                                   {:from :shop/cart :to :stock/shelf :description "Publishes" :event :OrderPlaced}
                                   {:from :shop/cart :to :measurement/checklist :description "Publishes" :event :OrderPlaced}])))

(def events
  {:SpecParsed {:description "The spec was read" :published-by :spec/parser :carries [:scenarios] :shape {:scenarios "[scenario]"}}
   :OrderPlaced {:description "An order was placed" :published-by :shop/cart :carries [:order] :shape {:order "the order"}}})

(defn features [m]
  (into {} (map-indexed (fn [i c] [(str "dhf/spec/" (:spec c) ".feature")
                                   (k/feature-text (:name c) ["UN-001"] [{:id (format "spec-%04d" (inc i)) :title (str (:name c) " works")}])])
                        (concat (for [c (:containers m) ctx (:contexts c) comp (:components ctx)] comp) (:kernel m)))))

(defn cut3!
  "A project at spec-3 of the scopes model; F changes it into spec-4."
  [ctx f]
  (let [ctx (k/spec-project! ctx {:model model :features (features model)})]
    (k/write-edn! ctx "dhf/spec/events.edn" events)
    (k/commit-tag! ctx "spec-3")
    (f ctx)
    (k/commit-tag! ctx "spec-4")))

(defn touch! [ctx rel] (k/write! ctx rel (str (k/slurp-rel ctx rel) "\n# changed\n")))

(defn scopes [ctx] (get-in ctx [:result :scopes :scopes]))

(defn scopes-are! [ctx expected]
  (k/exits! ctx 0)
  (k/check! (= expected (scopes ctx)) "the scopes are " (pr-str (scopes ctx)) "; " (k/shows ctx))
  ctx)

(defstep #"the cuts spec-3 and spec-4, between which architecture/views.feature and kernel/bus.feature changed"
  (fn [ctx] (cut3! ctx #(do (touch! % "dhf/spec/architecture/views.feature") (touch! % "dhf/spec/kernel/bus.feature")))))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (scopes-are! ctx [{:component :kernel/bus} {:component :architecture/views}])))

(defstep #"the cuts spec-3 and spec-4, between which only architecture/views.md, design prose that spec-3 did not have, was added"
  (fn [ctx] (cut3! ctx #(k/write! % "dhf/spec/architecture/views.md" "# Views\n\nHow views are drawn.\n"))))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (scopes-are! ctx [{:component :architecture/views}])))

(defstep #"the cuts spec-3 and spec-4, between which only dhf/spec/glossary.md changed"
  (fn [ctx] (cut3! ctx #(touch! % "dhf/spec/glossary.md"))))

(defstep #"it lists no scope, and names glossary.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (scopes-are! ctx [])
    (k/err-line-has! ctx "dhf/spec/glossary.md" "warning: ")
    ctx))

(defn events! [ctx f] (k/write-edn! ctx "dhf/spec/events.edn" (f (edn/read-string (k/slurp-rel ctx "dhf/spec/events.edn")))))

(defstep #"the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events.edn changed"
  (fn [ctx] (cut3! ctx #(events! % (fn [e] (assoc-in e [:OrderPlaced :shape :order] "the order, with its lines"))))))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the model's order"
  (fn [ctx] (scopes-are! ctx [{:component :shop/cart} {:component :measurement/checklist} {:component :stock/shelf}])))

(defstep #"the cuts spec-3 and spec-4, between which only an event's :published-by changed from shop/cart to shop/till"
  (fn [ctx] (cut3! ctx #(events! % (fn [e] (assoc-in e [:OrderPlaced :published-by] :shop/till))))))

(defstep #"it lists as scopes shop/cart, shop/till and every subscriber the model's relationships name for that event, in the order the next rule gives"
  (fn [ctx] (scopes-are! ctx [{:component :shop/cart} {:component :shop/till} {:component :measurement/checklist} {:component :stock/shelf}])))

(defstep #"the cuts spec-3 and spec-4, between which only (an event's :description changed|an event lost a key its catalogue entry does not declare, as :read-by was)"
  (fn [ctx change]
    (let [ctx (k/spec-project! ctx {:model model :features (features model)})]
      (k/write-edn! ctx "dhf/spec/events.edn" (assoc-in events [:OrderPlaced :read-by] {:order [:shop/till]}))
      (k/commit-tag! ctx "spec-3")
      (if (str/starts-with? change "an event's :description")
        (events! ctx (fn [e] (assoc-in e [:OrderPlaced :description] "An order was placed, and paid")))
        (events! ctx (fn [e] (update e :OrderPlaced dissoc :read-by))))
      (k/commit-tag! ctx "spec-4"))))

(defstep #"it lists no scope" (fn [ctx] (scopes-are! ctx [])))

(defstep #"the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed"
  (fn [ctx] (cut3! ctx #(doseq [f ["dhf/spec/verification/status.feature" "dhf/spec/spec/parser.feature" "dhf/spec/kernel/command.feature"]] (touch! % f)))))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx] (scopes-are! ctx [{:component :kernel/command} {:component :spec/parser} {:component :verification/status}])))

(defstep #"components with no order between them come in the model's order, the subscribers of one event among them .*"
  (fn [ctx]
    (let [c (cut3! (dissoc ctx :dir :base) #(doseq [f ["dhf/spec/stock/shelf.feature" "dhf/spec/architecture/views.feature" "dhf/spec/spec/stamper.feature"]] (touch! % f)))
          r (k/run-rdm c "scopes spec-3 spec-4")]
      (scopes-are! r [{:component :spec/stamper} {:component :architecture/views} {:component :stock/shelf}])
      ctx)))

;; ------------------------------------------------------------------ slices

(def plan "# Slice\n\n## Goal\n\nA goal.\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n- a\n\n**Out:**\n\n- b\n\n## Decisions\n\nNone.\n")

(defn slices-cut3!
  "A slices-layout project at spec-3: SLICES {slice [component-name ...]}; F changes it into spec-4."
  [ctx slices f]
  (let [comps (into {} (for [c (concat (for [c (:containers model) ctx (:contexts c) comp (:components ctx)] comp) (:kernel model))]
                         [(name (:id c)) c]))
        feats (into {} (for [[s names] slices n names]
                         [(str "dhf/spec/" s "/" n ".feature") (k/feature-text n ["UN-001"] [])]))
        placed (set (mapcat val slices))
        rest-feats (into {} (for [[n _] comps :when (not (placed n))] [(str "dhf/spec/00-rest/" n ".feature") (k/feature-text n ["UN-001"] [])]))
        ctx (k/spec-project! ctx {:model model :features (merge feats rest-feats) :ledger 0})]
    (k/config! ctx assoc :spec-layout :slices)
    (doseq [s (concat (keys slices) ["00-rest"])] (k/write! ctx (str "dhf/spec/" s "/plan.md") plan))
    (k/write-edn! ctx "dhf/spec/events.edn" events)
    (k/commit-tag! ctx "spec-3")
    (f ctx)
    (k/commit-tag! ctx "spec-4")))

(defstep #"a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which only dhf/spec/12-several-specs/plan.md changed"
  (fn [ctx] (slices-cut3! ctx {"12-several-specs" ["parser" "stamper" "checklist"]} #(touch! % "dhf/spec/12-several-specs/plan.md"))))

(defstep #"it lists the scope \{:slice \"12-several-specs\" :components \[:spec/parser :spec/stamper :measurement/checklist\]\}, its components in the order a builder builds them"
  (fn [ctx] (scopes-are! ctx [{:slice "12-several-specs" :components [:spec/parser :spec/stamper :measurement/checklist]}])))

(defstep #"a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which a feature file of 11-project-in-a-folder and one of 12-several-specs changed, both slices reaching :verification/verify"
  (fn [ctx]
    (slices-cut3! ctx {"11-project-in-a-folder" ["command" "verify"] "12-several-specs" ["parser" "verify"]}
                  #(do (touch! % "dhf/spec/11-project-in-a-folder/command.feature") (touch! % "dhf/spec/12-several-specs/parser.feature")))))

(defstep #"it lists both slices as scopes, slices in the order of their folders' names, and :verification/verify in the first only"
  (fn [ctx] (scopes-are! ctx [{:slice "11-project-in-a-folder" :components [:kernel/command :verification/verify]}
                              {:slice "12-several-specs" :components [:spec/parser]}])))
