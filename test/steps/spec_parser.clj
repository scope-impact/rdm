(ns steps.spec-parser
  "Steps of the component Parser (02-write-the-spec/parser.feature, 12-several-specs/parser.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn cycle! [dir n m]
  (k/write! dir (str "dhf/cycles/" n "/cycle.edn")
            (pr-str (merge {:builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
                            :spec-owner {:name "Sami" :actor :human}}
                           m))))

(defn- scenarios [from to] (vec (for [i (range from (inc to))] {:id (format "spec-%04d" i) :title (str "Scenario " i)})))

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3 and the scope :project"
  (fn [_]
    (let [ctx (k/spec-project! {} :no-till true)]
      (cycle! (:dir ctx) "03" {:cut "spec-3" :scope :project})
      ctx)))

(defstep #"the spec at spec-3 holds 12 stamped scenarios and the working tree's spec holds 13"
  (fn [ctx]
    (let [d (:dir ctx)]
      (k/write! d "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :scenarios (scenarios 1 6)}))
      (k/write! d "dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :scenarios (scenarios 7 12)}))
      (k/commit-all! d "spec-3" "spec-3")
      (k/write! d "dhf/spec/shop/till.feature" (k/feature-text {:name "Till" :scenarios (scenarios 7 13)}))
      ctx)))

(defstep #"the checklist holds the 12 scenarios of spec-3"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [j (k/json-file (k/path (:dir ctx) "dhf/cycles/03/checklist.json"))]
      (k/expect! (= (map :id (scenarios 1 12)) (map #(get % "id") (get j "items"))) ctx "the checklist is not spec-3's 12 scenarios"))
    ctx))

(def every-construct
  "# A comment
@UN-001
Feature: Cart
  Every construct of the declared subset.

  Background:
    Given a cart

  Rule: Carts hold things

    @spec-0001 @e2e
    Scenario: A table and a doc string
      Given the items:
        | item  | count |
        | sauna | 1     |
      When the note is:
        \"\"\"
        a note
        \"\"\"
      Then it holds
      And it holds more
      But not less

    @spec-0002 @contract
    Scenario Outline: An outline <n>
      Given <n> things

      Examples:
        | n |
        | 1 |
        | 2 |
")

(defstep #"a stamped feature file using Feature, Background, Rule, Scenario, Scenario Outline, Examples, Given, When, Then, And, But, tags, comments, doc strings and data tables"
  (fn [_] (k/spec-project! {"shop/cart.feature" every-construct})))

(defstep #"a feature file containing a line starting with \"Scenario Template:\""
  (fn [_] (k/spec-project! {"shop/cart.feature" "@UN-001\nFeature: Cart\n\n  @spec-0001 @e2e\n  Scenario Template: A template\n    Given a thing\n"})))

(defstep #"it fails and names the file, the line number and the keyword"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/shop/cart.feature:5:" "Scenario Template") ctx))

(defstep #"a feature file with two Feature lines"
  (fn [_] (k/spec-project! {"shop/cart.feature" "@UN-001\nFeature: Cart\n\n  @spec-0001 @e2e\n  Scenario: One\n    Given a thing\n\nFeature: Again\n"})))

(defstep #"it fails and names the file and the line of the second Feature"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/spec/shop/cart.feature:8:") ctx))

;; ------------------------------------------------------------------ 12: several specs

(defstep #"a configuration whose :specs is \[\{:name \"auth\" :dir \"dhf/sdd/auth\" :id-prefix \"auth\"\} \{:name \"infra\" :dir \"dhf/sdd/infra\" :id-prefix \"infra\"\}\]"
  (fn [_]
    (k/two-specs! :auth-features {"auth/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "auth-0001"}]})}
                :infra-features {"infra/core.feature" (k/feature-text {:name "Core" :scenarios [{:id "infra-0001"}]})})))

(defstep #"each spec is read from its folder: its feature files, model.edn, events.edn, needs.edn, glossary.md, ids.edn, and its domain.edn where it keeps one"
  (fn [ctx]
    ;; Each spec's scenarios carry its own prefix and are owned in its own model,
    ;; so the check passes only when each folder is read on its own.
    (k/exits! ctx 0)
    (k/expect! (not (re-find #"auth-0001|infra-0001" (str (:err ctx)))) ctx "a finding names a spec's scenario")
    ctx))

(defstep #"a configuration with no :specs reads one spec, from :spec-dir and the paths it names, as before"
  (fn [ctx]
    (let [one (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})})
          r (k/rdm! one "check")]
      (k/exits! r 0))
    ctx))

(defstep #"a configuration whose :specs (two specs named \"auth\"|two specs with the id prefix \"auth\"|two specs in one folder|a spec whose folder does not exist)"
  (fn [_ holds]
    (let [ctx (k/two-specs!)]
      (k/edit-config! (:dir ctx)
                      #(assoc % :specs
                              (case holds
                                "two specs named \"auth\"" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "auth" :dir "dhf/sdd/infra" :id-prefix "infra"}]
                                "two specs with the id prefix \"auth\"" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/infra" :id-prefix "auth"}]
                                "two specs in one folder" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/auth" :id-prefix "infra"}]
                                "a spec whose folder does not exist" [{:name "auth" :dir "dhf/sdd/auth" :id-prefix "auth"} {:name "infra" :dir "dhf/sdd/nowhere" :id-prefix "infra"}])))
      ctx)))

(defstep #"it exits 2 and says (a spec's name is declared once|an id prefix is declared once, so no two scenarios share an id|a folder holds one spec|names the spec and its folder)"
  (fn [ctx why]
    (k/exits! ctx 2)
    (if (= why "names the spec and its folder")
      (k/err-has! ctx "infra" "dhf/sdd/nowhere")
      (k/err-has! ctx why))
    ctx))
