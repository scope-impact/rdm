(ns steps.architecture-lint
  "Step definitions for dhf/spec/architecture/lint.feature: `rdm-spec
  check` on a small project `rdm-spec init` wrote, its model the
  architecture context's base model (steps.architecture-support) changed
  as a step says, with a feature file for each component.

  A finding is read from the error stream as `FILE:LINE: RULE: what`; a
  step checks the line of its rule names what the scenario says.

  Context keys set here: :dir (the project), :model (the model written),
  :c4 (the configuration's :c4 a step set)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- with-model
  "ctx with a small project whose model is the base model changed by `f`."
  [ctx f]
  (let [m (f (or (:model ctx) a/base-model))
        dir (a/small-project! ctx m)]
    (assoc ctx :dir dir :model m)))

(defn- update-where [xs pred f] (mapv #(if (pred %) (f %) %) xs))

(defn- set-c4! [ctx k v]
  (let [ctx (kc/project-set-up! ctx)
        dir (:dir ctx)
        cfg (kc/config-of dir)]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str (assoc-in cfg [:c4 k] v)) "\n"))
    ctx))

(defn- rule-lines [r rule lines-of]
  (filter #(str/includes? % (str ": " rule ": ")) (lines-of r)))

(defn- names! [ctx rule names & {:keys [warning?]}]
  (let [r (kc/the-run ctx)
        ls (rule-lines r rule (if warning? #(filter kc/warning-line? (s/err-lines %)) kc/finding-lines))]
    (s/check! (some (fn [l] (every? #(str/includes? l %) names)) ls)
              (str "no " (if warning? "warning" "finding") " of " rule " names " (str/join ", " names) "\n" (s/show r)))
    nil))

(defn- fails! [ctx rule & names]
  (kc/exits! ctx 1)
  (names! ctx rule names))

;; ---------------------------------------------------------------------------
;; The C4 lint

(defstep #"a view with no description"
  (fn [ctx] (with-model ctx #(update % :views update-where (fn [v] (= "context" (:key v))) (fn [v] (dissoc v :description))))))

(defstep #"it fails rule c4-scope and names the view"
  (fn [ctx] (fails! ctx "c4-scope" "\"context\"")))

(defstep #"a container with no description"
  (fn [ctx] (with-model ctx #(update % :containers update-where (fn [c] (= :db (:id c))) (fn [c] (dissoc c :description))))))

(defstep #"it fails rule c4-element-described and names the container"
  (fn [ctx] (fails! ctx "c4-element-described" ":db")))

(defstep #"a relationship with no description"
  (fn [ctx] (with-model ctx #(update % :relationships update-where (fn [r] (= [:app :mailer] [(:from r) (:to r)]))
                                     (fn [r] (dissoc r :description))))))

(defstep #"it fails rule c4-arrow-labelled and names both ends"
  (fn [ctx] (fails! ctx "c4-arrow-labelled" ":app" ":mailer")))

(defstep #"a context view that includes no person"
  (fn [ctx] (with-model ctx #(-> % (assoc :people [])
                                 (update :relationships (fn [rs] (vec (remove (fn [r] (= :bather (:from r))) rs))))))))

(defstep #"it fails rule c4-context-actors and names the view"
  (fn [ctx] (fails! ctx "c4-context-actors" "\"context\"")))

(defstep #"a container declared with kind library"
  (fn [ctx] (with-model ctx #(update % :containers update-where (fn [c] (= :db (:id c))) (fn [c] (assoc c :kind :library))))))

(defstep #"it fails rule c4-container-runs and names the container"
  (fn [ctx] (fails! ctx "c4-container-runs" ":db")))

(defstep #"a relationship that publishes the event SpecCut"
  (fn [ctx] (with-model ctx #(update % :relationships conj {:from :booking/slots :to :billing/invoice
                                                            :description "Publishes" :event :SpecCut}))))

(defstep #"SpecCut is not in the event catalogue"
  (fn [ctx]
    (let [dir (:dir ctx)]
      (s/write! dir (or (:events (kc/config-of dir)) "dhf/spec/events.edn") "{}\n")
      ctx)))

(defstep #"it fails rule c4-events-named and names the relationship and the event"
  (fn [ctx] (fails! ctx "c4-events-named" ":booking/slots" ":billing/invoice" "SpecCut")))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n] (assoc (set-c4! ctx :view-element-limit (parse-long n)) :c4 {:view-element-limit (parse-long n)})))

(defstep #"a view showing 23 elements"
  (fn [ctx]
    ;; the context view: the system, the external system and 21 people
    (with-model ctx #(update % :people into (for [i (range 2 22)]
                                              {:id (keyword (str "person-" i)) :name (str "Person " i)
                                               :description "Books a slot" :actor #{:human}})))))

(defstep #"it warns under rule c4-view-size and names the view and both counts"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % ": c4-view-size: ") (kc/finding-lines r)) "the view size is a finding, not a warning")
      (names! ctx "c4-view-size" ["\"context\"" "23" "20"] :warning? true))))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n] (set-c4! ctx :description-max-chars (parse-long n))))

(defstep #"a person whose description is 60 characters"
  (fn [ctx]
    (let [d (apply str "Books a slot and pays for it at the desk" (repeat 20 "."))]
      (s/check! (= 60 (count d)) "the description is not 60 characters")
      (with-model ctx #(update % :people update-where (fn [p] (= :bather (:id p))) (fn [p] (assoc p :description d)))))))

(defstep #"it fails rule c4-description-length and names the person and both lengths"
  (fn [ctx] (fails! ctx "c4-description-length" ":bather" "60" "45")))

(def for-a-person
  ["Does each view serve its audience: can its reader answer the question the view is for?"
   "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
   "Was each render looked at by a person, and did nothing overlap or hide a name?"])

(defstep #"its result's :lint :for-a-person is exactly \[.*\]"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (= for-a-person (get-in (kc/stdout-edn r) [:lint :for-a-person]))
                (str "the result's :lint is " (pr-str (:lint (kc/stdout-edn r)))))
      nil)))

;; ---------------------------------------------------------------------------
;; Bounded contexts are modules of one container, and the model owns the spec

(defstep #"a bounded context with components and no component view"
  (fn [ctx] (with-model ctx #(update % :views (fn [vs] (vec (remove (fn [v] (= :billing (:context v))) vs)))))))

(defstep #"it fails rule a-component-view and names the context"
  (fn [ctx] (fails! ctx "a-component-view" ":billing")))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (let [ctx (with-model ctx identity)]
      (fs/delete (fs/file (:dir ctx) "dhf/spec/billing/invoice.feature"))
      ctx)))

(defstep #"it fails rule a-scenario-owner and names the component"
  (fn [ctx] (fails! ctx "a-scenario-owner" ":billing/invoice" "dhf/spec/billing/invoice.feature")))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (with-model ctx identity)]
      (s/write! (:dir ctx) "dhf/spec/billing/refunds.feature" "Feature: Refunds\n  Nobody owns it.\n")
      ctx)))

(defstep #"it fails rule a-scenario-owner and names the file"
  (fn [ctx] (fails! ctx "a-scenario-owner" "dhf/spec/billing/refunds.feature")))

(def ^:private cross {:from :billing/invoice :to :booking/slots :description "Reads slots from"})

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx] (with-model ctx #(update % :relationships conj cross))))

(defstep #"it fails rule a-contract-only and names the relationship"
  (fn [ctx] (fails! ctx "a-contract-only" ":billing/invoice" ":booking/slots")))

(defstep #"a configuration whose :c4 holds :contexts-relate-by \[:events :calls\], and a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (-> ctx
        (set-c4! :contexts-relate-by [:events :calls])
        (with-model #(update % :relationships conj cross)))))

(defstep #"it passes rule a-contract-only"
  (fn [ctx]
    (let [r (kc/the-run ctx)]
      (s/check! (and (#{0 1} (:exit r)) (:lint (kc/stdout-edn r))) (str "the lint did not run\n" (s/show r)))
      (s/check! (not-any? #(str/includes? % "a-contract-only") (s/err-lines r))
                (str "a-contract-only is named\n" (s/show r)))
      nil)))
