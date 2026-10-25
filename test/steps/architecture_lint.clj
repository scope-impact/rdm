(ns steps.architecture-lint
  "Step definitions for dhf/spec/architecture/lint.feature. Each scenario
  changes the model of a copy of the room (its views generated first) and
  runs `rdm-spec check` through the shared When (kernel_command)."
  (:require [babashka.fs :as fs]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.architecture-support :as a]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The C4 model passes the C4 lint

(defstep #"a view with no description"
  (fn [ctx]
    (-> (a/change-model! ctx (fn [m] (a/update-in-seq m [:views] #(= "containers" (:key %)) #(dissoc % :description))))
        (assoc :view "containers"))))

(defstep #"it fails rule (\S+) and names the view"
  (fn [ctx rule] (a/fails-rule! ctx rule (pr-str (:view ctx)))))

(defstep #"a container with no description"
  (fn [ctx]
    (-> (a/change-model! ctx #(a/update-element % :repository (fn [c] (dissoc c :description))))
        (assoc :element :repository))))

(defstep #"it fails rule (\S+) and names the container"
  (fn [ctx rule] (a/fails-rule! ctx rule (pr-str (:element ctx)))))

(defstep #"a relationship with no description"
  (fn [ctx]
    (-> (a/change-model! ctx (fn [m] (a/update-in-seq m [:relationships]
                                                     #(and (= :reviewer (:from %)) (= :git-host (:to %)))
                                                     #(dissoc % :description))))
        (assoc :rel [:reviewer :git-host]))))

(defstep #"it fails rule (\S+) and names both ends"
  (fn [ctx rule] (apply a/fails-rule! ctx rule (map pr-str (:rel ctx)))))

(defstep #"a context view that includes no person"
  (fn [ctx]
    (-> (a/change-model!
         ctx
         (fn [m]
           (let [people (set (map :id (:people m)))]
             (-> m
                 (assoc :people [])
                 (update :relationships (fn [rs] (vec (remove #(or (people (:from %)) (people (:to %))) rs))))
                 (update :views (fn [vs] (mapv (fn [v] (if (:steps v)
                                                         (update v :steps (fn [st] (vec (remove #(or (people (first %)) (people (second %))) st))))
                                                         v))
                                               vs)))))))
        (assoc :view "context"))))

(defstep #"a container declared with kind (\S+)"
  (fn [ctx kind]
    (-> (a/change-model! ctx #(a/update-element % :repository (fn [c] (assoc c :kind (keyword kind)))))
        (assoc :element :repository))))

(defstep #"a relationship that publishes the event (\S+)"
  (fn [ctx event]
    (-> (a/change-model! ctx (fn [m] (update m :relationships conj
                                             {:from :spec/check :to :publishing/site :description "Publishes"
                                              :event (keyword event)})))
        (assoc :rel [:spec/check :publishing/site] :event event))))

(defstep #"(\S+) is not in the event catalogue"
  (fn [ctx event]
    (let [ctx (a/ensure-room! ctx)
          dir (:dir ctx)
          c (a/read-events dir)]
      (when (contains? c (keyword event))
        (s/write! dir a/events-rel (pr-str (dissoc c (keyword event)))))
      (s/check! (not (contains? (a/read-events dir) (keyword event))) (str event " is still catalogued"))
      ctx)))

(defstep #"it fails rule (\S+) and names the relationship and the event"
  (fn [ctx rule] (apply a/fails-rule! ctx rule (concat (map pr-str (:rel ctx)) [(:event ctx)]))))

(defstep #"the configured view limit is (\d+) elements"
  (fn [ctx n]
    (let [ctx (a/ensure-room! ctx)]
      (a/update-config! (:dir ctx) #(assoc-in % [:c4 :view-element-limit] (parse-long n)))
      (assoc ctx :limit n))))

(defstep #"a view showing (\d+) elements"
  (fn [ctx n]
    (let [n (parse-long n)]
      (-> (a/change-model!
           ctx
           (fn [m]
             ;; the context view shows the system, every person and every external system
             (let [shown (+ 1 (count (:people m)) (count (:external m)))]
               (s/check! (<= shown n) (str "the context view already shows " shown " elements"))
               (update m :external into (for [i (range (- n shown))]
                                          {:id (keyword (str "extra-" i)) :name (str "Extra " i)
                                           :description "An external system of the fixture"})))))
          (assoc :view "context" :count (str n))))))

(defstep #"it warns under rule (\S+) and names the view and both counts"
  (fn [ctx rule]
    (s/check! (:limit ctx) "no limit was configured")
    (a/warns-rule! ctx rule (pr-str (:view ctx)) (:count ctx) (:limit ctx))))

(defstep #"the configured description limit is (\d+) characters"
  (fn [ctx n]
    (let [ctx (a/ensure-room! ctx)]
      (a/update-config! (:dir ctx) #(assoc-in % [:c4 :description-max-chars] (parse-long n)))
      (assoc ctx :limit n))))

(defstep #"a person whose description is (\d+) characters"
  (fn [ctx n]
    (let [n (parse-long n)
          d (subs (apply str (repeat 10 "Writes the spec and its architecture, ")) 0 n)]
      (s/check! (= n (count d)) "the fixture's description has the wrong length")
      (-> (a/change-model! ctx #(a/update-element % :engineer (fn [p] (assoc p :description d))))
          (assoc :element :engineer :length (str n))))))

(defstep #"it fails rule (\S+) and names the person and both lengths"
  (fn [ctx rule]
    (s/check! (:limit ctx) "no limit was configured")
    (a/fails-rule! ctx rule (pr-str (:element ctx)) (:length ctx) (:limit ctx))))

(defstep #"it lists, for a person to answer, whether the view serves its audience, whether the true coupling is visible and whether the render was looked at"
  (fn [ctx]
    (let [r (a/the-run ctx)
          out (a/result r)
          items (get-in out [:lint :for-a-person])]
      (s/check! (seq items) (str "the check lists nothing for a person to answer\n" (s/show r)))
      (doseq [[what re] [["the view's audience" #"(?i)audience"]
                         ["the true coupling" #"(?i)coupling"]
                         ["the render looked at" #"(?i)render.*looked at"]]]
        (s/check! (some #(re-find re %) items) (str "the list does not ask about " what ": " (pr-str items)))))))

;; ---------------------------------------------------------------------------
;; Bounded contexts are modules of one container, and the model owns the spec

(defstep #"a bounded context with components and no component view"
  (fn [ctx]
    (-> (a/change-model! ctx (fn [m] (update m :views (fn [vs] (vec (remove #(= :publishing (:context %)) vs))))))
        (assoc :context :publishing))))

(defstep #"it fails rule (\S+) and names the context"
  (fn [ctx rule] (a/fails-rule! ctx rule (pr-str (:context ctx)))))

(defstep #"a component in the model with no feature file at its path in the spec"
  (fn [ctx]
    (let [ctx (a/change-model! ctx #(a/add-component % :spec {:id :spec/ghost :name "Ghost" :spec "spec/ghost"
                                                             :description "A component with no feature file"}))]
      (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/spec/spec/ghost.feature"))) "the ghost has a feature file")
      (assoc ctx :element :spec/ghost))))

(defstep #"it fails rule (\S+) and names the component"
  (fn [ctx rule] (a/fails-rule! ctx rule (pr-str (:element ctx)))))

(defstep #"a feature file in the spec at a path no component of the model names"
  (fn [ctx]
    (let [ctx (a/ensure-room! ctx)
          rel "dhf/spec/spec/orphan.feature"]
      (s/write! (:dir ctx) rel (str "@UN-005\nFeature: Orphan\n  No component owns it.\n\n"
                                    "  Scenario: An orphan scenario\n    Given a step\n"))
      (assoc ctx :file rel))))

(defstep #"it fails rule (\S+) and names the file"
  (fn [ctx rule] (a/fails-rule! ctx rule (:file ctx))))

(defstep #"a relationship from a component of one bounded context to a component of another, naming no event"
  (fn [ctx]
    (-> (a/change-model! ctx (fn [m] (update m :relationships conj
                                             {:from :spec/parser :to :verification/trace :description "Hands scenarios to"})))
        (assoc :rel [:spec/parser :verification/trace]))))

(defstep #"it fails rule (\S+) and names the relationship"
  (fn [ctx rule] (apply a/fails-rule! ctx rule (map pr-str (:rel ctx)))))

