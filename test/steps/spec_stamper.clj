(ns steps.spec-stamper
  "Steps of the component Stamper (02-write-the-spec/stamper.feature, 12-several-specs/stamper.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [steps.spec-parser :as sp]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn ledger [ctx] (:issued (edn/read-string (k/slurp-in (:dir ctx) "dhf/spec/ids.edn"))))

(defn tags-above
  "The tags on the line above the scenario titled title in rel."
  [ctx rel title]
  (let [lines (str/split-lines (str (k/slurp-in (:dir ctx) rel)))
        i (first (keep-indexed (fn [i l] (when (str/includes? l (str "Scenario: " title)) i)) lines))]
    (when (and i (pos? i)) (set (re-seq #"@\S+" (nth lines (dec i)))))))

(defstep #"the ledger records (41|42) as the highest id issued"
  (fn [ctx n]
    (if (:runs ctx)
      (do (k/expect! (= (parse-long n) (ledger ctx)) ctx (str "the ledger records " (ledger ctx))) ctx)
    (let [ctx (if (:dir ctx) ctx
                  (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id (str "spec-00" n) :title "The last"}]})}
                                   :issued (parse-long n)))]
      (k/write! (:dir ctx) "dhf/spec/ids.edn" (str ";; the ledger\n{:issued " n "}\n"))
      ctx))))

(defstep #"the project's configuration sets the id prefix to \"loyly\""
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "loyly-0001"}]})} :prefix "loyly")))

(defstep #"a (?:scenario in the spec|new scenario) carries no id tag"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/spec/shop/till.feature"
              (k/feature-text {:name "Till" :scenarios [{:id nil :title "Unstamped one"}]}))
    (assoc ctx :unstamped ["dhf/spec/shop/till.feature" "Unstamped one"])))

(defstep #"(?:the|the new) scenario is tagged @spec-(\d+)"
  (fn [ctx n]
    (k/exits! ctx 0)
    (let [[rel title] (:unstamped ctx)]
      (k/expect! (contains? (tags-above ctx rel title) (str "@spec-" n)) ctx (str "the scenario is tagged " (tags-above ctx rel title))))
    ctx))

(defstep #"every scenario in the spec carries an id"
  (fn [_] (let [ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id "spec-0002"}]})})]
            (k/keep-spec ctx))))

(defstep #"the ledger does not change"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= 99 (ledger ctx)) ctx "the ledger changed") ctx))

(defstep #"the scenario tagged @spec-0042 has been deleted"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]}))
    ctx))

(defstep #"a scenario tagged @spec-0007 in one component's feature file"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0007" :title "Seven"} {:id "spec-0001"}]})})))

(defstep #"its title is reworded and it is moved to another component's feature file"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]}))
    (k/write! (:dir ctx) "dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0098"} {:id "spec-0007" :title "Seven, reworded"}]}))
    ctx))

(defstep #"it is still tagged @spec-0007"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (contains? (tags-above ctx "dhf/spec/shop/till.feature" "Seven, reworded") "@spec-0007") ctx "the id changed")
    ctx))

(defstep #"the scenario is tagged with an id starting @loyly-"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [[rel title] (:unstamped ctx)]
      (k/expect! (some #(str/starts-with? % "@loyly-") (tags-above ctx rel title)) ctx "no @loyly- id"))
    ctx))

;; ------------------------------------------------------------------ 12

(defstep #"two specs, auth with 3 stamped scenarios and infra with none, each with one unstamped scenario"
  (fn [_]
    (k/two-specs! :auth-issued 3 :infra-issued 0
                   :auth-features {"auth/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "auth-0001"} {:id "auth-0002"} {:id "auth-0003"} {:title "New in auth"}]})}
                   :infra-features {"infra/core.feature" (k/feature-text {:name "Core" :scenarios [{:title "New in infra"}]})})))

(defstep #"auth's is stamped @auth-0004 from dhf/sdd/auth/ids.edn, and infra's @infra-0001 from dhf/sdd/infra/ids.edn"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (contains? (tags-above ctx "dhf/sdd/auth/auth/core.feature" "New in auth") "@auth-0004") ctx "auth's is not @auth-0004")
    (k/expect! (contains? (tags-above ctx "dhf/sdd/infra/infra/core.feature" "New in infra") "@infra-0001") ctx "infra's is not @infra-0001")
    (k/expect! (= 4 (:issued (edn/read-string (k/slurp-in (:dir ctx) "dhf/sdd/auth/ids.edn")))) ctx "auth's ledger is not 4")
    (k/expect! (= 1 (:issued (edn/read-string (k/slurp-in (:dir ctx) "dhf/sdd/infra/ids.edn")))) ctx "infra's ledger is not 1")
    ctx))
