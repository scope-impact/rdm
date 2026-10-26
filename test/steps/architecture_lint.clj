(ns steps.architecture-lint
  "Step definitions for dhf/spec/architecture/lint.feature: `rdm-spec check`
  on a small project rdm-spec set up, its model the base model of
  steps.architecture-support with the one change a Given makes.

  Context keys set here: :dir the project; :model the model written;
  :named the words the finding (or warning) a Then looks for must hold."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(defn- project-with
  "ctx with :dir, a small project whose model is the base model changed by
  `f`, and :named `named`."
  ([ctx f named] (project-with ctx f named identity))
  ([ctx f named config-f]
   (let [m (f a/base-model)
         dir (a/small-project! (dissoc ctx :dir) m)]
     (s/write! dir "dhf/harness/config.edn" (str (pr-str (config-f (kc/config-of dir))) "\n"))
     (assoc ctx :dir dir :model m :named named))))

(defn- update-config [ctx f]
  (s/write! (:dir ctx) "dhf/harness/config.edn" (str (pr-str (f (kc/config-of (:dir ctx)))) "\n"))
  ctx)

(defn- line-with [lines rule named]
  (first (filter (fn [l] (and (str/includes? l (str ": " rule ": ")) (every? #(str/includes? l %) named))) lines)))

;; ---------------------------------------------------------------------------
;; The C4 checklist

(defstep #"a view with no description"
  (fn [ctx]
    (project-with ctx (fn [m] (update m :views (fn [vs] (mapv #(if (= "containers" (:key %)) (dissoc % :description) %) vs))))
                  ["\"containers\""])))

(defstep #"a container with no description"
  (fn [ctx]
    (project-with ctx (fn [m] (update m :containers (fn [cs] (mapv #(if (= :db (:id %)) (dissoc % :description) %) cs))))
                  [":db"])))

(defstep #"a relationship with no description"
  (fn [ctx]
    (project-with ctx (fn [m] (update m :relationships (fn [rs] (mapv #(if (= :booking/rules (:from %)) (dissoc % :description) %) rs))))
                  [":booking/rules" ":booking/slots"])))

(defstep #"a context view that includes no person"
  (fn [ctx]
    (project-with ctx (fn [m] (-> m (assoc :people [])
                                  (update :relationships (fn [rs] (vec (remove #(= :bather (:from %)) rs))))))
                  ["\"context\""])))

(defstep #"a container declared with kind (\S+)"
  (fn [ctx kind]
    (project-with ctx (fn [m] (update m :containers (fn [cs] (mapv #(if (= :db (:id %)) (assoc % :kind (keyword kind)) %) cs))))
                  [":db" kind])))

(defstep #"a relationship that publishes the event (\S+)"
  (fn [ctx ev]
    (project-with ctx (fn [m] (update m :relationships conj
                                      {:from :booking/slots :to :billing/invoice :description "Publishes" :event (keyword ev)}))
                  [":booking/slots" ":billing/invoice" ev])))

(defstep #"(\S+) is not in the event catalogue"
  (fn [ctx ev]
    (let [rel (or (:events (kc/config-of (:dir ctx))) "dhf/spec/events.edn")]
      (s/write! (:dir ctx) rel "{}\n")
      (s/check! (not (str/includes? (slurp (str (fs/file (:dir ctx) rel))) ev)) (str ev " is in the catalogue"))
      ctx)))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n] (assoc ctx :view-limit (parse-long n))))

(defstep #"a view showing (\d+) elements"
  (fn [ctx n]
    (let [n (parse-long n)
          ;; the context view shows the system, its one person and every external system
          extra (- n 2)
          ctx (project-with ctx (fn [m] (assoc m :external (vec (for [i (range extra)]
                                                                  {:id (keyword (str "ext-" i)) :name (str "External " i)
                                                                   :description "Another system"}))
                                               :relationships (vec (remove #(= :mailer (:to %)) (:relationships m)))))
                            ["\"context\"" (str n) (str (:view-limit ctx))])]
      (update-config ctx #(assoc-in % [:c4 :view-element-limit] (:view-limit ctx))))))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n] (assoc ctx :description-limit (parse-long n))))

(defstep #"a person whose description is (\d+) characters"
  (fn [ctx n]
    (let [n (parse-long n)
          d (subs (apply str (repeat 10 "Books a sauna slot every week. ")) 0 n)
          ctx (project-with ctx (fn [m] (assoc-in m [:people 0 :description] d))
                            [":bather" (str n) (str (:description-limit ctx))])]
      (update-config ctx #(assoc-in % [:c4 :description-max-chars] (:description-limit ctx))))))

(defstep #"it fails rule ((?:c4|a)-[a-z-]+) and names (?:.+)"
  (fn [ctx rule]
    (let [r (kc/exits! ctx 1)]
      (s/check! (line-with (kc/finding-lines r) rule (:named ctx))
                (str "no " rule " finding names " (str/join ", " (:named ctx)) "\n" (s/show r)))
      nil)))

(defstep #"it warns under rule (\S+) and names (?:.+)"
  (fn [ctx rule]
    (let [r (kc/the-run ctx)
          ws (filter kc/warning-line? (s/err-lines r))]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (line-with ws rule (:named ctx))
                (str "no " rule " warning names " (str/join ", " (:named ctx)) "\n" (s/show r)))
      (s/check! (not (line-with (kc/finding-lines r) rule [])) (str rule " is a finding, not a warning\n" (s/show r)))
      nil)))

(defstep #"it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          qs (get-in (kc/stdout-edn r) [:lint :for-a-person])]
      (s/check! (#{0 1} (:exit r)) (str "the check did not run\n" (s/show r)))
      (s/check! (= 3 (count qs)) (str "the lint lists " (pr-str qs) "\n" (s/show r)))
      (doseq [w ["audience" "coupling" "render"]]
        (s/check! (some #(str/includes? % w) qs) (str "no question asks about the " w ": " (pr-str qs))))
      nil)))

;; ---------------------------------------------------------------------------
;; Bounded contexts

(defstep #"a bounded context with components and no component view"
  (fn [ctx]
    (project-with ctx (fn [m] (update m :views (fn [vs] (vec (remove #(= "billing-components" (:key %)) vs)))))
                  [":billing"])))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (let [ctx (project-with ctx identity [":booking/rules" "dhf/spec/booking/rules.feature"])]
      (fs/delete (fs/file (:dir ctx) "dhf/spec/booking/rules.feature"))
      ctx)))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (project-with ctx identity ["dhf/spec/archive/old.feature"])]
      (s/write! (:dir ctx) "dhf/spec/archive/old.feature" "Feature: Old\n  Nobody owns this.\n")
      ctx)))

(def ^:private cross-call {:from :booking/slots :to :billing/invoice :description "Asks for an invoice from"})

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (project-with ctx (fn [m] (update m :relationships conj cross-call)) [":booking/slots" ":billing/invoice"])))

(defstep #"a configuration whose :c4 holds :contexts-relate-by \[:events :calls\], and a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (let [ctx (project-with ctx (fn [m] (update m :relationships conj cross-call)) [":booking/slots" ":billing/invoice"]
                            #(assoc-in % [:c4 :contexts-relate-by] [:events :calls]))]
      (a/views! ctx (:dir ctx))
      ctx)))

(defstep #"it passes rule (\S+)"
  (fn [ctx rule]
    (let [r (kc/exits! ctx 0)]
      (s/check! (not (some #(str/includes? % (str ": " rule ": ")) (s/err-lines r)))
                (str "the run names " rule "\n" (s/show r)))
      nil)))
