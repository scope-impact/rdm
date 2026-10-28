(ns steps.architecture-lint
  "Steps of the component Lint (dhf/spec/04-model-the-architecture/lint.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]
            [steps.architecture-model :as m]))

(defn linted!
  "A project of the demo model changed by F, its views redrawn."
  [ctx f]
  (let [ctx (m/model-project! ctx m/demo)]
    (m/model! ctx f)
    (k/run-rdm ctx "views")
    ctx))

(defn rule! [ctx rule & names] (k/exits! ctx 1) (apply k/err-line-has! ctx (str rule ": ") names) ctx)

(defstep #"a view with no description"
  (fn [ctx] (linted! ctx #(update-in % [:views 0] dissoc :description))))

(defstep #"it fails rule c4-scope and names the view"
  (fn [ctx] (rule! ctx "c4-scope" "context")))

(defstep #"a container with no description"
  (fn [ctx] (linted! ctx #(update-in % [:containers 0] dissoc :description))))

(defstep #"it fails rule c4-element-described and names the container"
  (fn [ctx] (rule! ctx "c4-element-described" ":app")))

(defstep #"a relationship with no description"
  (fn [ctx] (linted! ctx #(update-in % [:relationships 0] dissoc :description))))

(defstep #"it fails rule c4-arrow-labelled and names both ends"
  (fn [ctx] (rule! ctx "c4-arrow-labelled" ":user" ":app")))

(defstep #"a context view that includes no person"
  (fn [ctx] (linted! ctx #(-> % (assoc :people []) (assoc :relationships [])))))

(defstep #"it fails rule c4-context-actors and names the view"
  (fn [ctx] (rule! ctx "c4-context-actors" "context")))

(defstep #"a container declared with kind library"
  (fn [ctx] (linted! ctx #(assoc-in % [:containers 0 :kind] :library))))

(defstep #"it fails rule c4-container-runs and names the container"
  (fn [ctx] (rule! ctx "c4-container-runs" ":app")))

(defstep #"a relationship that publishes the event SpecCut"
  (fn [ctx]
    (let [ctx (linted! ctx (fn [mo] (-> mo
                              (update-in [:containers 0 :contexts 0 :components] conj {:id :demo/other :name "Other" :spec "demo/other" :description "Another"})
                              (update :relationships conj {:from :demo/thing :to :demo/other :description "Publishes" :event :SpecCut}))))]
      (k/write! ctx "dhf/spec/demo/other.feature" (k/feature-text "Other" ["UN-001"] [{:id "spec-0002" :title "Other works"}]))
      (k/write! ctx "dhf/spec/ids.edn" "{:issued 2}\n"))))

(defstep #"SpecCut is not in the event catalogue"
  (fn [ctx] (k/write-edn! ctx "dhf/spec/events.edn" {})))

(defstep #"it fails rule c4-events-named and names the relationship and the event"
  (fn [ctx] (rule! ctx "c4-events-named" ":demo/thing" ":demo/other" "SpecCut")))

(defstep #"the configured view limit is 20 elements"
  (fn [ctx] (let [ctx (m/model-project! ctx m/demo)] (k/config! ctx assoc-in [:c4 :view-element-limit] 20) ctx)))

(defstep #"a view showing 23 elements"
  (fn [ctx]
    (m/model! ctx update :people into (for [i (range 21)] {:id (keyword (str "p" i)) :name (str "P" i) :description "A person"}))
    (k/run-rdm ctx "views")
    ctx))

(defstep #"it warns under rule c4-view-size and names the view and both counts"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/err-line-has! ctx "warning: c4-view-size: " "context" "23" "20")
    ctx))

(defstep #"the configured description limit is 45 characters"
  (fn [ctx] (let [ctx (m/model-project! ctx m/demo)] (k/config! ctx assoc-in [:c4 :description-max-chars] 45) ctx)))

(defstep #"a person whose description is 60 characters"
  (fn [ctx] (m/model! ctx assoc-in [:people 0 :description] (apply str (repeat 60 "x"))) (k/run-rdm ctx "views") ctx))

(defstep #"it fails rule c4-description-length and names the person and both lengths"
  (fn [ctx] (rule! ctx "c4-description-length" ":user" "60" "45")))

(defstep #"its result's :lint :for-a-person is exactly \[\"Does each view serve its audience: can its reader answer the question the view is for\?\" \"Is the true coupling visible: does every relationship that matters appear, labelled with what passes\?\" \"Was each render looked at by a person, and did nothing overlap or hide a name\?\"\]"
  (fn [ctx]
    (k/check! (= ["Does each view serve its audience: can its reader answer the question the view is for?"
                  "Is the true coupling visible: does every relationship that matters appear, labelled with what passes?"
                  "Was each render looked at by a person, and did nothing overlap or hide a name?"]
                 (get-in ctx [:result :lint :for-a-person]))
              "the questions are " (pr-str (get-in ctx [:result :lint])) "; " (k/shows ctx))
    ctx))

(defstep #"a bounded context with components and no component view"
  (fn [ctx] (linted! ctx #(update % :views (fn [vs] (vec (remove (fn [v] (= :component (:level v))) vs)))))))

(defstep #"it fails rule a-component-view and names the context"
  (fn [ctx] (rule! ctx "a-component-view" ":demo")))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (linted! ctx #(update-in % [:containers 0 :contexts 0 :components] conj {:id :demo/ghost :name "Ghost" :spec "demo/ghost" :description "Has no file"}))))

(defstep #"it fails rule a-scenario-owner and names the component"
  (fn [ctx] (rule! ctx "a-scenario-owner" ":demo/ghost")))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (m/model-project! ctx m/demo)]
      (k/write! ctx "dhf/spec/demo/stray.feature" (k/feature-text "Stray" ["UN-001"] [{:id "spec-0002" :title "Stray"}]))
      (k/write! ctx "dhf/spec/ids.edn" "{:issued 2}\n"))))

(defstep #"it fails rule a-scenario-owner and names the file"
  (fn [ctx] (rule! ctx "a-scenario-owner" "dhf/spec/demo/stray.feature")))

(def two-contexts (k/context-model [[:demo [[:thing "Thing"]]] [:other [[:part "Part"]]]]))

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (let [ctx (m/model-project! ctx two-contexts)]
      (m/model! ctx update :relationships conj {:from :demo/thing :to :other/part :description "Calls"})
      (k/run-rdm ctx "views")
      ctx)))

(defstep #"it fails rule a-contract-only and names the relationship"
  (fn [ctx] (rule! ctx "a-contract-only" ":demo/thing" ":other/part")))

(defstep #"a configuration whose :c4 holds :contexts-relate-by \[:events :calls\], and a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (let [ctx (m/model-project! ctx two-contexts)]
      (m/model! ctx update :relationships conj {:from :demo/thing :to :other/part :description "Calls"})
      (k/config! ctx assoc-in [:c4 :contexts-relate-by] [:events :calls])
      (k/run-rdm ctx "views")
      ctx)))

(defstep #"it passes rule a-contract-only"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(str/includes? % "a-contract-only") (k/err-lines ctx)) "a-contract-only fails: " (k/shows ctx))
    ctx))
