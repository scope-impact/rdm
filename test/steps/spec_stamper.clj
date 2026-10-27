(ns steps.spec-stamper
  "Step definitions of dhf/spec/spec/stamper.feature."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn- text [ctx p] (slurp (fs/file (k/guard! (:dir ctx)) p)))
(defn- issued [ctx] (:issued (edn/read-string (text ctx "dhf/spec/ids.edn"))))

(defn tagged-above?
  "Whether the tag sits on the tag line right above the scenario titled title."
  [text tag title]
  (boolean (re-find (re-pattern (str "@" (java.util.regex.Pattern/quote tag) "(?:\\s[^\\n]*)?\\n\\s*Scenario: "
                                     (java.util.regex.Pattern/quote title) "\\n"))
                    text)))

(defstep #"the ledger records (\d+) as the highest id issued"
  (fn [ctx n]
    (if (:exit ctx)
      (do (k/check-exit ctx 0)
          (assert (= (parse-long n) (issued ctx)) (text ctx "dhf/spec/ids.edn"))
          ctx)
      (let [ctx (k/project! ctx)]
        (k/write-files! (:dir ctx) {"dhf/spec/ids.edn" (str ";; the ledger\n{:issued " n "}\n")})
        ctx))))

(defn- add-unstamped! [ctx title]
  (let [ctx (k/project! ctx)
        f (fs/file (:dir ctx) k/core-feature)]
    (spit f (str (slurp f) "\n  @e2e\n  Scenario: " title "\n    Given a step\n"))
    ctx))

(defstep #"a scenario in the spec carries no id tag"
  (fn [ctx] (add-unstamped! ctx "Unstamped")))

(defstep #"the scenario is tagged @(spec-\d+)"
  (fn [ctx id]
    (k/check-exit ctx 0)
    (assert (tagged-above? (text ctx k/core-feature) id "Unstamped") (text ctx k/core-feature))
    ctx))

(defstep #"every scenario in the spec carries an id"
  (fn [ctx] (let [ctx (k/project! ctx)] (assoc ctx ::issued (issued ctx)))))

(defstep #"the ledger does not change"
  (fn [ctx] (k/check-exit ctx 0) (assert (= (::issued ctx) (issued ctx))) ctx))

(defstep #"the scenario tagged @spec-0042 has been deleted"
  (fn [ctx]
    (assert (= 42 (issued ctx)))
    (assert (not (str/includes? (text ctx k/core-feature) "spec-0042")))
    ctx))

(defstep #"a new scenario carries no id tag"
  (fn [ctx] (add-unstamped! ctx "New")))

(defstep #"the new scenario is tagged @spec-0043"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (tagged-above? (text ctx k/core-feature) "spec-0043" "New") (text ctx k/core-feature))
    ctx))

(defstep #"a scenario tagged @spec-0007 in one component's feature file"
  (fn [ctx]
    (k/project! ctx {:model-spec {:contexts [["shop" ["till" "cart"]]] :kernel ["core"]}
                     :features {"dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :tags ["UN-001"] :scenarios [(k/sc 7 "Takes a payment")]})
                                "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :tags ["UN-001"] :scenarios [(k/sc 8 "Holds items")]})}
                     :extra {"dhf/spec/ids.edn" "{:issued 8}\n"}})))

(defstep #"its title is reworded and it is moved to another component's feature file"
  (fn [ctx]
    (k/write-files! (:dir ctx) {"dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :tags ["UN-001"]})
                                "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :tags ["UN-001"]
                                                                              :scenarios [(k/sc 8 "Holds items") (k/sc 7 "Takes a card payment")]})})
    ctx))

(defstep #"it is still tagged @spec-0007"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [t (text ctx "dhf/spec/shop/cart.feature")]
      (assert (tagged-above? t "spec-0007" "Takes a card payment") t)
      (assert (= 8 (issued ctx)))
      (assert (not (str/includes? t "spec-0009")) t))
    ctx))

(defstep #"the project's configuration sets the id prefix to \"loyly\""
  (fn [ctx]
    (k/project! ctx {:config {:id-prefix "loyly"}
                     :features {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                :scenarios [(assoc (k/sc 1 "A scenario") :tags ["loyly-0001" "e2e"])]})}})))

(defstep #"the scenario is tagged with an id starting @loyly-"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (re-find #"@loyly-\d{4,}[^\n]*\n\s*Scenario: Unstamped" (text ctx k/core-feature)) (text ctx k/core-feature))
    ctx))
