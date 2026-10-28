(ns steps.measurement-checklist
  "Steps of the component Checklist (07-measure, 10, 11, 12 and 13's checklist.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn checklist [ctx] (k/json-file (k/path (:dir ctx) "dhf/cycles/03/checklist.json")))
(defn items [ctx] (get (checklist ctx) "items"))

(defstep #"the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip"
  (fn [_]
    (let [ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios (vec (for [i (range 1 7)] {:id (format "spec-%04d" i) :title (str "Cart " i) :wip (= i 6)}))})
                                "shop/till.feature" (k/feature-text {:name "Till" :scenarios (vec (for [i (range 7 13)] {:id (format "spec-%04d" i) :title (str "Till " i) :level (if (odd? i) "contract" "e2e")}))})})]
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      ctx)))

(defstep #"dhf/cycles/03/checklist.json holds 11 items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [is (items ctx)]
      (k/expect! (= 11 (count is)) ctx (str (count is) " items"))
      (k/expect! (every? #(= #{"id" "title" "context" "component" "level"} (set (keys %))) is) ctx "an item lacks a key")
      (k/expect! (= {"id" "spec-0007" "title" "Till 7" "context" "shop" "component" "Till" "level" "contract"} (some #(when (= "spec-0007" (get % "id")) %) is)) ctx "spec-0007's item"))
    ctx))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx] (k/expect! (= [{"id" "spec-0006" "title" "Cart 6"}] (get (checklist ctx) "planned")) ctx "not planned") ctx))

(def lint-model
  {:system {:id :s :name "S" :description "d"} :people [{:id :p :name "P" :actor #{:human} :description "d"}]
   :containers [{:id :c :name "C" :kind :runs :technology "t" :description "d"
                 :contexts [{:id :architecture :name "architecture" :description "d"
                             :components [{:id :architecture/lint :name "Lint" :description "d" :spec "architecture/lint"}]}]}]
   :relationships [{:from :p :to :c :description "uses"}]
   :views [{:key "architecture-components" :level :component :context :architecture :description "d"}]})

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [_]
    (let [ctx (k/spec-project! {"architecture/lint.feature"
                                (k/feature-text {:name "Lint" :scenarios [{:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip true}
                                                                         {:id "spec-0012" :title "A view with no description fails the lint"}]})}
                               :model lint-model)]
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope :project})
      ctx)))

(defstep #"dhf/cycles/03/checklist.json holds:"
  (fn [ctx doc]
    (k/exits! ctx 0)
    (k/expect! (= (cheshire.core/parse-string doc) (checklist ctx)) ctx (str "it holds " (checklist ctx)))
    ctx))

(defn- in-order? [text ks] (let [is (map #(str/index-of text (str "\"" % "\"")) ks)] (and (every? some? is) (apply < is))))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [t (k/slurp-in (:dir ctx) "dhf/cycles/03/checklist.json")]
      (k/expect! (in-order? t ["cycle" "cut" "items" "planned"]) ctx "the top keys are out of order")
      (k/expect! (in-order? t ["id" "title" "context" "component" "level"]) ctx "an item's keys are out of order"))
    ctx))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/line-has! ctx (:unstamped ctx))
    (k/expect! (not (fs/exists? (k/path (:dir ctx) "dhf/cycles/03/checklist.json"))) ctx "a checklist was written")
    ctx))

;; ------------------------------------------------------------------ 11

(defstep #"a repository whose project is its folder wallet/, with wallet/dhf/cycles/03/cycle.edn naming the cut spec-3, and a dhf/cycles/03/ at the repository's root"
  (fn [_]
    (let [ctx (k/wallet-repo!)]
      (k/write! (:repo ctx) "dhf/cycles/03/cycle.edn" "{:cut \"spec-2\" :scope :project}")
      (assoc ctx :repo-before (k/tree-snapshot (:repo ctx))))))

(defstep #"it writes wallet/dhf/cycles/03/checklist.json, one item per stamped scenario of wallet/'s spec at spec-3"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= ["spec-0001" "spec-0002" "spec-0003"] (map #(get % "id") (items ctx))) ctx "not wallet/'s scenarios at spec-3")
    ctx))

(defstep #"nothing at the repository's root changes"
  (fn [ctx]
    (let [outside (fn [m] (into {} (remove #(str/starts-with? (key %) "wallet/") m)))]
      (k/expect! (= (outside (:repo-before ctx)) (outside (k/tree-snapshot (:repo ctx)))) ctx "something at the root changed"))
    ctx))

;; ------------------------------------------------------------------ 12

(defstep #"two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is \[\{:spec \"auth\"\}\]"
  (fn [_]
    (let [ctx (k/two-specs-cycle!)]
      (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope [{:spec "auth"}]})
      ctx)))

(defstep #"\"rdm-spec checklist 03\" is run, and verify's tests check reads the cycle's results"
  (fn [ctx]
    (let [c (k/rdm! ctx "checklist 03")
          v (k/rdm! ctx "verify ROOM 03")]
      (assoc c :verify-run v))))

(defstep #"the checklist holds one item per stamped scenario of auth and none of infra"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= ["auth-0001" "auth-0002"] (map #(get % "id") (items ctx))) ctx (str "it holds " (map #(get % "id") (items ctx)))) ctx))

(defstep #"verify requires a result for every scenario of auth, and none for infra's"
  (fn [ctx]
    (let [v (:verify-run ctx)]
      (k/exits! v 0)
      (k/expect! (= 2 (:scenarios (first (get-in v [:edn :verify :checks])))) v "not auth's two scenarios"))
    ctx))

;; ------------------------------------------------------------------ 13

(defn- slice-cycle! [cycle]
  (let [ctx (k/slices-project! {"12-several-specs/stamper.feature" nil})]
    (k/git! (:dir ctx) "init" "-q")
    (k/commit-all! (:dir ctx) "spec-3" "spec-3")
    (k/cycle-edn! (:dir ctx) "03" (merge {:cut "spec-3"} cycle))
    ctx))

(defstep #"a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser and :measurement/checklist; :spec/parser with 2 stamped scenarios in 12-several-specs and 3 in 02-write-the-spec; and dhf/cycles/03/cycle.edn whose :scope is \[\{:slice \"12-several-specs\"\}\]"
  (fn [_] (slice-cycle! {:scope [{:slice "12-several-specs"}]})))

(defstep #"it holds one item for each of the 5 scenarios of :spec/parser and each of :measurement/checklist's, wherever their slice, each item naming its slice and its component"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [is (items ctx)
          by (group-by #(get % "component") is)]
      (k/expect! (= 5 (count (by "Parser"))) ctx "not the parser's 5")
      (k/expect! (= 3 (count (by "Checklist"))) ctx "not the checklist's 3")
      (k/expect! (empty? (by "Stamper")) ctx "a stamper item")
      (k/expect! (every? #(get % "slice") is) ctx "an item names no slice"))
    ctx))

(defstep #"verify requires a result for each of them, as for any scope"
  (fn [ctx]
    ;; verify's scope is the checklist's: a slice scope requires the scenarios of the components it reaches
    (let [d (:dir ctx)
          room (k/path (:top ctx) "room")]
      (k/edit-config! d #(assoc % :implementation ["test"] :verify [(k/tests-check)]))
      (k/recut! ctx)
      (k/write! room "test/steps/all.clj" k/catch-all-steps)
      (let [v (k/rdm! ctx ["verify" room "03"])]
        (k/exits! v 0)
        (k/expect! (= 8 (:scenarios (first (get-in v [:edn :verify :checks])))) v "not the slice's 8 scenarios")))
    ctx))

(defstep #"a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser, with 2 of :spec/parser's stamped scenarios in 12-several-specs and 3 in 02-write-the-spec; and dhf/cycles/03/cycle.edn whose :slice is \"12-several-specs\""
  (fn [_] (slice-cycle! {:slice "12-several-specs"})))

(defstep #"the 2 items of 12-several-specs carry :role \"slice\" and the 3 of 02-write-the-spec carry :role \"regression\", each naming its slice"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [parser (filter #(= "Parser" (get % "component")) (items ctx))]
      (k/expect! (= #{["12-several-specs" "slice"]} (set (for [i parser :when (= "12-several-specs" (get i "slice"))] [(get i "slice") (get i "role")]))) ctx "12's items are not the slice's")
      (k/expect! (= 2 (count (filter #(= "12-several-specs" (get % "slice")) parser))) ctx "not 2")
      (k/expect! (= #{["02-write-the-spec" "regression"]} (set (for [i parser :when (= "02-write-the-spec" (get i "slice"))] [(get i "slice") (get i "role")]))) ctx "02's items are not regressions")
      (k/expect! (= 3 (count (filter #(= "02-write-the-spec" (get % "slice")) parser))) ctx "not 3"))
    ctx))

;; ------------------------------------------------------------------ 10: clauses

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std.txt at the cut holds \"STD:5.1 Development plan\""
  (fn [_]
    (let [ctx (k/regulated-project! :clauses "include base.txt\nSTD:5.1 Development plan\nSTD:4.1 Quality system, again\n")]
      (k/write! (:dir ctx) "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      (k/write! (:dir ctx) "dhf/clauses/std.txt" "STD:9.9 Not at the cut\n")
      (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope :project})
      ctx)))

(defstep #"the items of dhf/cycles/03/checklist.json end with:"
  (fn [ctx doc]
    (k/exits! ctx 0)
    (k/expect! (= (cheshire.core/parse-string doc) (last (items ctx))) ctx (str "the last item is " (last (items ctx))))
    ctx))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (let [levels (map #(get % "level") (items ctx))
          clauses (filter #(= "clause" (get % "level")) (items ctx))]
      (k/expect! (= levels (concat (remove #{"clause"} levels) (filter #{"clause"} levels))) ctx "a clause item before a scenario item")
      (k/expect! (= ["STD:4.1" "STD:5.1"] (map #(get % "id") clauses)) ctx (str "the clause items are " (map #(get % "id") clauses))))
    ctx))

(defstep #"cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses"
  (fn [_]
    (let [m (assoc k/shop-model :containers [(update (first (:containers k/shop-model)) :contexts conj
                                                    {:id :architecture :name "architecture" :description "d"
                                                     :components [{:id :architecture/lint :name "Lint" :description "d" :spec "architecture/lint"}]})]
                   :views (conj (:views k/shop-model) {:key "architecture-components" :level :component :context :architecture :description "d"}))
          ctx (k/regulated-project! :features {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})
                                               "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"}]})
                                               "architecture/lint.feature" (k/feature-text {:name "Lint" :scenarios [{:id "spec-0003"}]})})]
      (k/write! (:dir ctx) "dhf/spec/model.edn" (k/pp m))
      (k/git! (:dir ctx) "init" "-q")
      (k/commit-all! (:dir ctx) "spec-3" "spec-3")
      (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope {:context :architecture}})
      ctx)))

(defstep #"dhf/cycles/03/checklist.json holds no item at the level clause"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (seq (items ctx)) ctx "no item at all")
    (k/expect! (not-any? #(= "clause" (get % "level")) (items ctx)) ctx "a clause item")
    ctx))
