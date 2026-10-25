(ns steps.architecture-lint
  "Step definitions for dhf/spec/architecture/lint.feature: each Given edits
  the model of a copy of the room's spec, `rdm-spec check` runs it (the shared
  When), and each Then reads the finding off the error stream."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

;; --- The C4 model passes the C4 lint ----------------------------------------

(defstep #"a view with no description"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (update m :views a/update-where #(= "context" (:key %)) #(dissoc % :description))))
        (assoc :expect ["view \"context\""]))))

(defstep #"a container with no description"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (update m :containers a/update-where #(= :repository (:id %)) #(dissoc % :description))))
        (assoc :expect [":repository"]))))

(defstep #"a relationship with no description"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (update m :relationships a/update-where
                                          #(and (= :architecture/lint (:from %)) (= :architecture/model (:to %)))
                                          #(dissoc % :description))))
        (assoc :expect [":architecture/lint" ":architecture/model"]))))

(defstep #"a context view that includes no person"
  (fn [ctx]
    (-> (a/edit-model
         ctx
         (fn [m]
           (let [people (set (map :id (:people m)))]
             (-> m
                 (assoc :people [])
                 (update :relationships #(vec (remove (fn [r] (or (people (:from r)) (people (:to r)))) %)))
                 (update :views (fn [vs] (mapv (fn [v] (if (:steps v)
                                                         (update v :steps #(vec (remove (fn [[f t]] (or (people f) (people t))) %)))
                                                         v))
                                               vs)))))))
        (assoc :expect ["view \"context\""]))))

(defstep #"a container declared with kind library"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (update m :containers a/update-where #(= :repository (:id %)) #(assoc % :kind :library))))
        (assoc :expect [":repository"]))))

(defstep #"a relationship that publishes the event (\w+)"
  (fn [ctx event]
    (-> (a/edit-model ctx (fn [m] (update m :relationships conj
                                          {:from :regeneration/cut :to :spec/check
                                           :description "Publishes" :event (keyword event)})))
        (assoc :expect [":regeneration/cut" ":spec/check" event]))))

(defstep #"(\w+) is not in the event catalogue"
  (fn [ctx event]
    (let [cat (edn/read-string (slurp (str (fs/file (:dir ctx) a/events-rel))))]
      (s/check! (not (contains? cat (keyword event))) (str event " is in the catalogue")))))

(defn- set-c4! [dir k v]
  (let [f (str (fs/file dir "dhf/harness/config.edn"))]
    (spit f (pr-str (assoc-in (edn/read-string (slurp f)) [:c4 k] v)))))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n]
    (a/needs-check!)
    (let [dir (a/dir-of ctx)]
      (set-c4! dir :view-element-limit (parse-long n))
      (assoc ctx :dir dir :limit n))))

(defstep #"a view showing (\d+) elements"
  (fn [ctx n]
    ;; The container view draws every container, person and external system:
    ;; add external systems until it draws n.
    (let [n (parse-long n)]
      (-> (a/edit-model
           ctx
           (fn [m]
             (let [now (+ (count (:containers m)) (count (:people m)) (count (:external m)))]
               (update m :external into
                       (for [i (range (- n now))]
                         {:id (keyword (str "extra-" i)) :name (str "Extra " i) :description "An extra system"})))))
          (assoc :expect ["view \"containers\"" (str n) (:limit ctx)])))))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n]
    (a/needs-check!)
    (let [dir (a/dir-of ctx)]
      (set-c4! dir :description-max-chars (parse-long n))
      (assoc ctx :dir dir :limit n))))

(defstep #"a person whose description is (\d+) characters"
  (fn [ctx n]
    (let [d (subs (apply str (repeat 10 "Writes the spec and its architecture, ")) 0 (parse-long n))]
      (-> (a/edit-model ctx (fn [m] (update m :people a/update-where #(= :engineer (:id %)) #(assoc % :description d))))
          (assoc :expect [":engineer" n (:limit ctx)])))))

(defstep #"it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at"
  (fn [ctx]
    (a/needs-check!)
    (let [r (:run ctx)
          out (a/stdout-edn r)
          listed (:c4-for-a-person out)
          text (str/lower-case (str/join " " (:questions listed)))]
      (s/check! (map? listed) (str "the result lists nothing for a person under :c4-for-a-person\n" (s/show r)))
      (s/check! (= 3 (count (:questions listed))) (str "expected three questions: " (pr-str listed)))
      (doseq [w ["audience" "coupling" "looked at"]]
        (s/check! (str/includes? text w) (str "no question about " w ": " (pr-str listed))))
      (s/check! (= (mapv :key (:views (a/read-model (:dir ctx)))) (:views listed))
                (str "the list does not name every view: " (pr-str listed))))))

;; --- Bounded contexts are modules of one container --------------------------

(defstep #"a bounded context with components and no component view"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (update m :views #(vec (remove (fn [v] (= "architecture-components" (:key v))) %)))))
        (assoc :expect [":architecture"]))))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (a/update-context m :architecture
                                                    #(update % :components conj
                                                             {:id :architecture/render :name "Render"
                                                              :spec "architecture/render"
                                                              :description "Renders each view"}))))
        (assoc :expect [":architecture/render" "dhf/spec/architecture/render.feature"]))))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (a/needs-check!)
    (let [dir (a/dir-of ctx)]
      (s/write! dir "dhf/spec/architecture/render.feature"
                (str "@UN-005\nFeature: Render\n  Renders each view.\n\n"
                     "  Scenario: A view is rendered\n    Given a view\n    When it is rendered\n    Then it is drawn\n"))
      (assoc ctx :dir dir :expect ["dhf/spec/architecture/render.feature"]))))

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (-> (a/edit-model ctx (fn [m] (update m :relationships conj
                                          {:from :architecture/lint :to :spec/check :description "Reads findings from"})))
        (assoc :expect [":architecture/lint -> :spec/check"]))))

;; --- Then --------------------------------------------------------------------

(defstep #"it fails rule (\S+) and names (?:the view|the container|both ends|the relationship and the event|the person and both lengths|the context|the component|the file|the relationship)"
  (fn [ctx rule]
    (a/check-names! (:run ctx) 1 rule (:expect ctx))))

(defstep #"it warns under rule (\S+) and names the view and both counts"
  (fn [ctx rule]
    (let [r (:run ctx)
          lines (a/rule-lines r rule)]
      (s/check! (some (fn [l] (and (str/includes? l "warning: ") (every? #(str/includes? l %) (:expect ctx)))) lines)
                (str "no warning under " rule " names " (pr-str (:expect ctx)) "\n" (s/show r)))
      ;; a warning alone never fails the check
      (s/check! (not-any? #(not (str/includes? % "warning: ")) lines) (s/show r)))))
