(ns steps.architecture-lint
  "Step definitions for dhf/spec/architecture/lint.feature: `rdm-spec check`
  on a small project (steps.architecture-support/small-project!) whose model
  is the support's base model changed as a step says, its views not
  generated (so the view-missing findings are expected beside the lint's).

  Context keys set here: :dir, :model (the model written), :names (what a
  finding of the rule a Then names must hold), :c4 (the configured limits)."
  (:require [babashka.fs :as fs]
            [clojure.edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- with-model!
  "ctx with a small project whose model is the base model changed by `f`,
  the configured :c4 limits kept, and the words a finding must name."
  [ctx f names]
  (let [m (f (or (:model ctx) a/base-model))
        dir (a/small-project! (dissoc ctx :dir) m)]
    (when-let [c4 (:c4 ctx)]
      (s/write! dir "dhf/harness/config.edn" (str (pr-str (assoc (kc/config-of dir) :c4 c4)) "\n")))
    (assoc ctx :dir dir :model m :names (vec names))))

(defn- finding-with! [ctx rule warning?]
  (let [r (kc/the-run ctx)
        ls (if warning? (filter kc/warning-line? (s/err-lines r)) (kc/finding-lines r))
        tag (str (when warning? "warning: ") rule ": ")]
    (when-not warning? (kc/exits! ctx 1))
    (s/check! (some (fn [l] (and (str/includes? l tag) (every? #(str/includes? l %) (:names ctx)))) ls)
              (str "no " (if warning? "warning" "finding") " of " rule " names " (pr-str (:names ctx)) "\n" (s/show r)))
    nil))

(defstep #"it fails rule (c4-[a-z-]+|a-[a-z-]+) and names (.+)"
  (fn [ctx rule _] (finding-with! ctx rule false)))

(defstep #"it warns under rule (c4-[a-z-]+) and names the view and both counts"
  (fn [ctx rule] (finding-with! ctx rule true)))

;; ---------------------------------------------------------------------------
;; The C4 checklist

(defn- drop-key [xs pred k] (mapv #(if (pred %) (dissoc % k) %) xs))

(defstep #"a view with no description"
  (fn [ctx]
    (with-model! ctx #(update % :views drop-key (fn [v] (= "containers" (:key v))) :description) ["\"containers\""])))

(defstep #"a container with no description"
  (fn [ctx]
    (with-model! ctx #(update % :containers drop-key (fn [c] (= :db (:id c))) :description) [":db"])))

(defstep #"a relationship with no description"
  (fn [ctx]
    (with-model! ctx #(update % :relationships drop-key (fn [r] (= [:app :mailer] [(:from r) (:to r)])) :description)
                 [":app" ":mailer"])))

(defstep #"a context view that includes no person"
  (fn [ctx]
    (with-model! ctx (fn [m] (-> m (assoc :people [])
                                 (update :relationships (fn [rs] (vec (remove #(= :bather (:from %)) rs))))))
                 ["\"context\""])))

(defstep #"a container declared with kind library"
  (fn [ctx]
    (with-model! ctx #(update % :containers (fn [cs] (mapv (fn [c] (if (= :db (:id c)) (assoc c :kind :library) c)) cs)))
                 [":db"])))

(defstep #"a relationship that publishes the event SpecCut"
  (fn [ctx]
    (with-model! ctx #(update % :relationships conj {:from :booking/slots :to :billing/invoice
                                                     :description "Publishes" :event :SpecCut})
                 [":booking/slots" ":billing/invoice" "SpecCut"])))

(defstep #"SpecCut is not in the event catalogue"
  (fn [ctx]
    (let [rel (or (:events (kc/config-of (:dir ctx))) "dhf/spec/events.edn")
          catalogue (clojure.edn/read-string (slurp (str (fs/file (:dir ctx) rel))))]
      (s/check! (not (contains? catalogue :SpecCut)) (str rel " declares SpecCut"))
      ctx)))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n] (assoc-in ctx [:c4 :view-element-limit] (parse-long n))))

(defstep #"a view showing (\d+) elements"
  (fn [ctx n]
    (let [k (parse-long n)
          extra (for [i (range (- k 3))] {:id (keyword (str "visitor-" i)) :name (str "Visitor " i)
                                          :description "Visits" :actor #{:human}})
          limit (str (get-in ctx [:c4 :view-element-limit] 20))]
      (with-model! ctx #(update % :people into extra) ["\"context\"" (str k) limit]))))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n] (assoc-in ctx [:c4 :description-max-chars] (parse-long n))))

(defstep #"a person whose description is (\d+) characters"
  (fn [ctx n]
    (let [k (parse-long n)
          desc (subs (apply str (repeat k "Books a sauna slot and pays. ")) 0 k)]
      (with-model! ctx #(update % :people (fn [ps] (mapv (fn [p] (assoc p :description desc)) ps)))
                   [":bather" (str k) (str (get-in ctx [:c4 :description-max-chars] 45))]))))

(defstep #"it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          _ (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
          qs (get-in (kc/stdout-edn r) [:lint :for-a-person])
          text (str/join "\n" qs)]
      (doseq [w ["audience" "coupling" "render" "looked at"]]
        (s/check! (str/includes? text w) (str "the questions for a person do not ask about " w ": " (pr-str qs))))
      nil)))

;; ---------------------------------------------------------------------------
;; Bounded contexts are modules, and the model owns the spec

(defstep #"a bounded context with components and no component view"
  (fn [ctx]
    (with-model! ctx #(update % :views (fn [vs] (vec (remove (fn [v] (= "billing-components" (:key v))) vs))))
                 [":billing"])))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (let [ctx (with-model! ctx identity [":billing/invoice" "dhf/spec/billing/invoice.feature"])]
      (fs/delete (fs/file (:dir ctx) "dhf/spec/billing/invoice.feature"))
      ctx)))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (with-model! ctx identity ["dhf/spec/booking/orphan.feature"])]
      (s/write! (:dir ctx) "dhf/spec/booking/orphan.feature" "Feature: Orphan\n  Nobody owns it.\n")
      ctx)))

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (with-model! ctx #(update % :relationships conj {:from :booking/slots :to :billing/invoice
                                                     :description "Asks for an invoice from"})
                 [":booking/slots" ":billing/invoice"])))
