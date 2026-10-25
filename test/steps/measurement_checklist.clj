(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature. The project
  and cycle fixtures are steps.measurement-cycle's; the command is run by
  steps.kernel-command."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- scenarios-of
  "`n` stamped scenarios from spec-0301, the last tagged @wip; those from the
  third on in the context verification (component Status), the others in
  architecture (component Lint)."
  [n]
  (vec (for [i (range n)]
         {:id (format "spec-%04d" (+ 301 i))
          :title (str "Scenario number " (inc i))
          :wip? (= i (dec n))
          :owner (if (< i 2) ["architecture" "Lint"] ["verification" "Status"])})))

(defstep #"the spec at cut spec-3 has (\d+) stamped scenarios, one of them tagged @wip"
  (fn [ctx n]
    (let [scs (scenarios-of (parse-long n))
          lint (filter #(= "Lint" (second (:owner %))) scs)
          status (remove #(= "Lint" (second (:owner %))) scs)
          dir (mc/spec-project! {mc/lint-feature (mc/feature-text lint)
                                 mc/status-feature (mc/feature-text "Status" "UN-003" status)})]
      (assoc ctx :dir (str dir) :scenarios scs))))

(defstep #"dhf/cycles/03/checklist.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (let [r (:run ctx)
          _ (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
          {:keys [cycle cut items]} (mc/read-checklist ctx "03")
          want (remove :wip? (:scenarios ctx))]
      (s/check! (= "03" cycle) (str "the checklist names the cycle " (pr-str cycle)))
      (s/check! (= "spec-3" cut) (str "the checklist names the cut " (pr-str cut)))
      (s/check! (= (parse-long n) (count items)) (str (count items) " items: " (pr-str (map :id items))))
      (doseq [{:keys [id title owner]} want
              :let [item (first (filter #(= id (:id %)) items))]]
        (s/check! item (str "no item for " id))
        (s/check! (= {:id id :title title :context (first owner) :component (second owner) :level "e2e"} item)
                  (str "the item of " id " is " (pr-str item)))))))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [items planned]} (mc/read-checklist ctx "03")
          wip (first (filter :wip? (:scenarios ctx)))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] planned) (str "planned is " (pr-str planned)))
      (s/check! (not-any? #(= (:id wip) (:id %)) items) (str (:id wip) " is an item to grade")))))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (mc/cycle-project! ctx {mc/lint-feature (mc/feature-text (take 2 mc/default-scenarios))})))

(defn- keys-in-order
  "The keys of every JSON object in `text`, in the order they are written."
  [text]
  (mapv second (re-seq #"\"([A-Za-z_-]+)\"\s*:" text)))

(defstep #"dhf/cycles/03/checklist.json holds:"
  (fn [ctx doc]
    (let [r (:run ctx)
          _ (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
          got (mc/read-checklist ctx "03")
          want (json/parse-string doc true)]
      (s/check! (= want got) (str "checklist.json holds " (pr-str got) "\nexpected " (pr-str want)))
      (assoc ctx :expected-doc doc))))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (slurp (str (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json"))))
          {:keys [items planned]} (json/parse-string text true)]
      (s/check! (= (keys-in-order (:expected-doc ctx)) (keys-in-order text))
                (str "the keys are written in the order " (keys-in-order text)))
      (doseq [xs [items planned]]
        (s/check! (= (map :id xs) (sort (map :id xs))) (str "not sorted by id: " (pr-str (map :id xs))))))))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)]
      (mc/refused! ctx 1 "checklist.json"
                   (re-pattern (str "(^|\\s)" (java.util.regex.Pattern/quote (str file ":" line ":"))))))))
