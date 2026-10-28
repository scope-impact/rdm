(ns steps.regeneration-scopes
  "Steps of the component Scopes (06-regenerate/scopes.feature, 11 and 13)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def rdm-like-model
  "A model shaped like rdm-spec's own: kernel command and bus, and contexts whose components publish and subscribe."
  {:system {:id :s :name "S" :description "d"}
   :people [{:id :p :name "P" :actor #{:human} :description "d"}]
   :containers [{:id :c :name "C" :kind :runs :technology "t" :description "d"
                 :contexts [{:id :spec :name "spec" :description "d"
                             :components [{:id :spec/parser :name "Parser" :description "d" :spec "spec/parser"}]}
                            {:id :architecture :name "architecture" :description "d"
                             :components [{:id :architecture/lint :name "Lint" :description "d" :spec "architecture/lint"}
                                          {:id :architecture/views :name "Views" :description "d" :spec "architecture/views"}]}
                            {:id :verification :name "verification" :description "d"
                             :components [{:id :verification/status :name "Status" :description "d" :spec "verification/status"}]}
                            {:id :shop :name "shop" :description "d"
                             :components [{:id :shop/cart :name "Cart" :description "d" :spec "shop/cart"}
                                          {:id :shop/till :name "Till" :description "d" :spec "shop/till"}
                                          {:id :shop/ledger :name "Ledger" :description "d" :spec "shop/ledger"}
                                          {:id :shop/mail :name "Mail" :description "d" :spec "shop/mail"}]}]}]
   :kernel [{:id :kernel/command :name "Command" :description "d" :spec "kernel/command"}
            {:id :kernel/bus :name "Event bus" :description "d" :spec "kernel/bus"}]
   :relationships [{:from :p :to :c :description "uses"}
                   {:from :spec/parser :to :verification/status :description "Publishes" :event :SpecParsed}
                   {:from :spec/parser :to :architecture/lint :description "Publishes" :event :SpecParsed}
                   {:from :shop/cart :to :shop/mail :description "Publishes" :event :OrderPlaced}
                   {:from :shop/cart :to :shop/ledger :description "Publishes" :event :OrderPlaced}]
   :views (vec (for [c [:spec :architecture :verification :shop]] {:key (str (name c) "-components") :level :component :context c :description "d"}))})

(def catalogue
  {:SpecParsed {:description "Read" :published-by :spec/parser :carries [:scenarios] :shape {:scenarios "[...]"}}
   :OrderPlaced {:description "Placed" :published-by :shop/cart :carries [:order] :shape {:order "{:id}"} :read-by [:shop/mail]}})

(defn scopes-project!
  "A project shaped like rdm-spec's, committed and tagged spec-3."
  []
  (let [comps (for [c (concat (mapcat :components (mapcat :contexts (:containers rdm-like-model))) (:kernel rdm-like-model))] c)
        ctx (k/spec-project! (into {} (for [[i c] (map-indexed vector comps)]
                                        [(str (:spec c) ".feature") (k/feature-text {:name (:name c) :scenarios [{:id (format "spec-%04d" (inc i))}]})]))
                             :model rdm-like-model)]
    (k/write! (:dir ctx) "dhf/spec/events.edn" (k/pp catalogue))
    (k/git! (:dir ctx) "init" "-q")
    (k/commit-all! (:dir ctx) "spec-3" "spec-3")
    ctx))

(defn touch! [ctx rel] (k/write! (:dir ctx) rel (str (k/slurp-in (:dir ctx) rel) "\n# changed\n")))

(defn edit-events! [ctx f] (k/edit-edn! (:dir ctx) "dhf/spec/events.edn" f))

(defn cut-4! [ctx] (k/commit-all! (:dir ctx) "spec-4" "spec-4") ctx)

(defstep #"the cuts spec-3 and spec-4, between which (architecture/views.feature and kernel/bus.feature changed|only architecture/views.md, design prose that spec-3 did not have, was added|only dhf/spec/glossary.md changed|only the shape of one carried key of one event in dhf/spec/events.edn changed|only an event's :published-by changed from shop/cart to shop/till|only an event's :description changed|only an event lost a key its catalogue entry does not declare, as :read-by was|the kernel's command, the spec parser and the status component changed)"
  (fn [_ change]
    (let [ctx (scopes-project!)]
      (case change
        "architecture/views.feature and kernel/bus.feature changed" (do (touch! ctx "dhf/spec/architecture/views.feature") (touch! ctx "dhf/spec/kernel/bus.feature"))
        "only architecture/views.md, design prose that spec-3 did not have, was added" (k/write! (:dir ctx) "dhf/spec/architecture/views.md" "# Views\n\nProse.\n")
        "only dhf/spec/glossary.md changed" (touch! ctx "dhf/spec/glossary.md")
        "only the shape of one carried key of one event in dhf/spec/events.edn changed" (edit-events! ctx #(assoc-in % [:OrderPlaced :shape :order] "{:id :lines}"))
        "only an event's :published-by changed from shop/cart to shop/till" (edit-events! ctx #(assoc-in % [:OrderPlaced :published-by] :shop/till))
        "only an event's :description changed" (edit-events! ctx #(assoc-in % [:OrderPlaced :description] "Placed, at last"))
        "only an event lost a key its catalogue entry does not declare, as :read-by was" (edit-events! ctx #(update % :OrderPlaced dissoc :read-by))
        "the kernel's command, the spec parser and the status component changed"
        (do (touch! ctx "dhf/spec/verification/status.feature") (touch! ctx "dhf/spec/spec/parser.feature") (touch! ctx "dhf/spec/kernel/command.feature")))
      (cut-4! ctx))))

(defn listed [ctx] (get-in ctx [:edn :scopes :scopes]))

(defstep #"it lists the scopes \{:component :kernel/bus\}, then \{:component :architecture/views\}, and nothing else"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= [{:component :kernel/bus} {:component :architecture/views}] (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"it lists the scope \{:component :architecture/views\}"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= [{:component :architecture/views}] (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"it lists no scope, and names glossary.md as a changed shared file whose readers a person must judge"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (empty? (listed ctx)) ctx "it lists a scope")
    (k/line-has! ctx "warning" "glossary.md" "person")
    ctx))

(defstep #"it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the model's order"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= [{:component :shop/cart} {:component :shop/ledger} {:component :shop/mail}] (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"it lists as scopes shop/cart, shop/till and every subscriber the model's relationships name for that event, in the order the next rule gives"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= [{:component :shop/cart} {:component :shop/till} {:component :shop/ledger} {:component :shop/mail}] (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"it lists no scope"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (empty? (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"it lists kernel/command, then spec/parser, then verification/status"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= [{:component :kernel/command} {:component :spec/parser} {:component :verification/status}] (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"components with no order between them come in the model's order, the subscribers of one event among them \(cycles 18 and 19 read spec-0298's \"the order they name them\" two ways; spec-24 settles on the model's\)"
  (fn [ctx]
    ;; ledger and mail subscribe to OrderPlaced in the relationships' order mail, ledger; the model's order is ledger, mail
    (let [c (scopes-project!)]
      (edit-events! c #(assoc-in % [:OrderPlaced :shape :order] "{:id :lines}"))
      (cut-4! c)
      (let [r (k/rdm! c "scopes spec-3 spec-4")]
        (k/expect! (= [{:component :shop/cart} {:component :shop/ledger} {:component :shop/mail}] (listed r)) r "not in the model's order")))
    ctx))

;; ------------------------------------------------------------------ 11

(defstep #"a repository whose project is its folder wallet/, and the cuts spec-2 and spec-3, between which one of wallet/'s feature files and a feature file outside wallet/ changed"
  (fn [_]
    (let [ctx (k/wallet-repo!)]
      ;; a feature file outside wallet/, changed between the cuts
      (k/git! (:repo ctx) "checkout" "-q" "spec-2")
      (k/write! (:repo ctx) "dhf/spec/shop/cart.feature" "Feature: outside\n")
      (k/commit-all! (:repo ctx) "outside" "spec-2")
      (k/write! (:repo ctx) "dhf/spec/shop/cart.feature" "Feature: outside, changed\n")
      (k/write! (:dir ctx) "dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"} {:id "spec-0003" :title "More"}]}))
      (k/commit-all! (:repo ctx) "inside and outside" "spec-3")
      ctx)))

(defstep #"it lists the scope of wallet/'s changed feature file only, and names no file outside wallet/"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= [{:component :shop/till}] (listed ctx)) ctx (str "it lists " (listed ctx)))
    (k/expect! (not (str/includes? (str (:out ctx) (:err ctx)) "outside")) ctx "it names a file outside wallet/")
    ctx))

;; ------------------------------------------------------------------ 13

(defn slices-cuts! [f]
  (let [ctx (k/slices-project!)]
    (k/git! (:dir ctx) "init" "-q")
    (k/commit-all! (:dir ctx) "spec-3" "spec-3")
    (f ctx)
    (cut-4! ctx)))

(defstep #"a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which only dhf/spec/12-several-specs/plan.md changed"
  (fn [_] (slices-cuts! #(touch! % "dhf/spec/12-several-specs/plan.md"))))

(defstep #"it lists the scope \{:slice \"12-several-specs\" :components \[:spec/parser :spec/stamper :measurement/checklist\]\}, its components in the order a builder builds them"
  (fn [ctx] (k/exits! ctx 0)
    (k/expect! (= [{:slice "12-several-specs" :components [:spec/parser :spec/stamper :measurement/checklist]}] (listed ctx)) ctx (str "it lists " (listed ctx))) ctx))

(defstep #"a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which a feature file of 11-project-in-a-folder and one of 12-several-specs changed, both slices reaching :verification/verify"
  (fn [_]
    (let [m (update-in k/slices-model [:containers 0 :contexts] conj
                       {:id :verification :name "verification" :description "d" :components [{:id :verification/verify :name "Verify" :description "d" :spec "06-regenerate/verify"}]})
          m (update m :views conj {:key "verification-components" :level :component :context :verification :description "d"})
          ctx (k/slices-project!
               {"11-project-in-a-folder/plan.md" (str/replace k/plan-text "Slice 12" "Slice 11")
                "11-project-in-a-folder/decisions.md" "# D\n"
                "11-project-in-a-folder/verify.feature" (k/feature-text {:name "Verify" :scenarios [{:id "spec-0011"}]})
                "11-project-in-a-folder/checklist.feature" (k/feature-text {:name "Checklist" :scenarios [{:id "spec-0012"}]})
                "12-several-specs/verify.feature" (k/feature-text {:name "Verify" :scenarios [{:id "spec-0013"}]})}
               :model m)]
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      (touch! ctx "dhf/spec/11-project-in-a-folder/verify.feature")
      (touch! ctx "dhf/spec/12-several-specs/parser.feature")
      (cut-4! ctx))))

(defstep #"it lists both slices as scopes, slices in the order of their folders' names, and :verification/verify in the first only"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [l (listed ctx)]
      (k/expect! (= ["11-project-in-a-folder" "12-several-specs"] (map :slice l)) ctx (str "it lists " l))
      (k/expect! (some #{:verification/verify} (:components (first l))) ctx "verify is not in the first")
      (k/expect! (not (some #{:verification/verify} (:components (second l)))) ctx "verify is in the second")
      (k/expect! (not (some #{:measurement/checklist} (:components (second l)))) ctx "checklist is in both"))
    ctx))

(defstep #"a configuration with :spec-layout :slices; :verification/verify, whose spec path in the model is 06-regenerate/verify, with feature files 06-regenerate/verify.feature and 12-several-specs/verify.feature; and the cuts spec-3 and spec-4, between which only 12-several-specs/verify.feature changed"
  (fn [_]
    (let [m (update-in k/slices-model [:containers 0 :contexts] conj
                       {:id :verification :name "verification" :description "d" :components [{:id :verification/verify :name "Verify" :description "d" :spec "06-regenerate/verify"}]})
          m (update m :views conj {:key "verification-components" :level :component :context :verification :description "d"})
          ctx (k/slices-project!
               {"06-regenerate/plan.md" (str/replace k/plan-text "Slice 12" "Slice 06")
                "06-regenerate/decisions.md" "# D\n"
                "06-regenerate/verify.feature" (k/feature-text {:name "Verify" :scenarios [{:id "spec-0011"}]})
                "12-several-specs/verify.feature" (k/feature-text {:name "Verify" :scenarios [{:id "spec-0013"}]})}
               :model m)]
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      (touch! ctx "dhf/spec/12-several-specs/verify.feature")
      (cut-4! ctx))))

(defstep #"it lists the scope \{:slice \"12-several-specs\" :components \[\.\.\.\]\} whose components hold :verification/verify"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [l (listed ctx)]
      (k/expect! (and (= 1 (count l)) (= "12-several-specs" (:slice (first l))) (some #{:verification/verify} (:components (first l)))) ctx (str "it lists " l)))
    ctx))

(defstep #"a slice holding feature files for components the model's spec paths place elsewhere reaches every one of them"
  (fn [ctx]
    (k/expect! (= [:spec/parser :spec/stamper :measurement/checklist :verification/verify] (:components (first (listed ctx)))) ctx
               (str "it reaches " (:components (first (listed ctx)))))
    ctx))
