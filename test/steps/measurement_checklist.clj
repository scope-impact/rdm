(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature. Each project
  is a cut copy of the room's allow-list (steps.measurement-cycle) whose spec
  is one small feature file; the working tree may differ from the cut."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-checklist! [] (s/needs-built! 'rdm-spec.measurement.checklist 'rdm-spec.spec.parser))

(def checklist-path (mc/cycle-path "03" "checklist.json"))

(defn- checklist-text [ctx]
  (mc/exits! ctx 0)
  (let [f (fs/file (:dir ctx) checklist-path)]
    (s/check! (fs/exists? f) (str "no " checklist-path "\n" (s/show (mc/the-run ctx))))
    (slurp (str f))))

(defn- checklist [ctx] (json/parse-string (checklist-text ctx) true))

(defn- json-keys
  "Every key of a JSON text, in the order it is written."
  [text]
  (mapv second (re-seq #"\"([A-Za-z_-]+)\"\s*:" text)))

;; ---------------------------------------------------------------------------

(defstep #"the spec at cut spec-3 has (\d+) stamped scenarios, one of them tagged @wip"
  (fn [ctx n]
    (needs-checklist!)
    (let [n (parse-long n)
          scs (vec (for [i (range n)]
                     (cond-> {:id (format "spec-%04d" (+ 301 i)) :title (str "Scenario number " (inc i))
                              :level (if (even? i) "e2e" "contract")}
                       (= i (dec n)) (assoc :wip? true))))
          ctx (mc/cycle-project! ctx {mc/lint-feature (mc/feature-text scs)})]
      ;; after the cut, the working tree gains a scenario the cut never held
      (s/write! (:dir ctx) mc/lint-feature
                (mc/feature-text (conj scs {:id "spec-0399" :title "Added after the cut"})))
      (assoc ctx :scenarios scs))))

(defstep #"dhf/cycles/03/checklist\.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (let [c (checklist ctx)
          want (vec (for [sc (:scenarios ctx) :when (not (:wip? sc))]
                      {:id (:id sc) :title (:title sc) :context "architecture" :component "Lint"
                       :level (:level sc)}))]
      (s/check! (= (parse-long n) (count (:items c))) (str (count (:items c)) " items: " (pr-str (map :id (:items c)))))
      (s/check! (= want (:items c)) (str "items:\n" (pr-str (:items c)) "\nexpected\n" (pr-str want)))
      (s/check! (= {:cycle "03" :cut "spec-3"} (select-keys c [:cycle :cut])) (pr-str (select-keys c [:cycle :cut]))))))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [c (checklist ctx)
          wip (first (filter :wip? (:scenarios ctx)))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] (:planned c)) (str "planned: " (pr-str (:planned c))))
      (s/check! (not-any? #(= (:id wip) (:id %)) (:items c)) "the planned scenario is an item to grade"))))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (needs-checklist!)
    (let [scs [{:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}
               {:id "spec-0012" :title "A view with no description fails the lint"}]
          ctx (mc/cycle-project! ctx {mc/lint-feature (mc/feature-text scs)})]
      (mc/write-cycle! (:dir ctx) "03" {})
      ctx)))

(defstep #"dhf/cycles/03/checklist\.json holds:"
  (fn [ctx doc]
    (let [got (checklist ctx)
          want (json/parse-string doc true)]
      (s/check! (= want got) (str "checklist.json holds\n" (pr-str got) "\nexpected\n" (pr-str want)))
      (assoc ctx :expected-doc doc))))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (checklist-text ctx)
          c (json/parse-string text true)]
      (s/check! (= (json-keys (:expected-doc ctx)) (json-keys text))
                (str "keys in the order " (pr-str (json-keys text)) ", expected " (pr-str (json-keys (:expected-doc ctx)))))
      (doseq [k [:items :planned]]
        (s/check! (= (sort (map :id (k c))) (map :id (k c))) (str (name k) " are not sorted by id"))))))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (needs-checklist!)
    (mc/exits! ctx 1)
    (mc/wrote-nothing! ctx checklist-path)
    (let [{:keys [file line]} (:unstamped ctx)]
      (mc/says! ctx (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote (str file ":" line ":")) " "))
                (str file ":" line)))))
