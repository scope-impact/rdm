(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature, in cycle
  projects (steps.measurement-cycle)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-checklist! []
  (s/needs-built! 'rdm-spec.spec.parser 'rdm-spec.measurement.checklist))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- checklist-file [ctx] (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json")))

(defn read-checklist
  "The cycle's checklist.json, keyword keys, and its text."
  [ctx]
  (let [f (checklist-file ctx)]
    (s/check! (fs/exists? f) (str "no " (mc/cycle-path "03" "checklist.json") "\n" (s/show (run ctx))))
    (let [t (slurp (str f))]
      (assoc (json/parse-string t true) ::text t))))

(defn numbered
  "n scenarios from spec-NNNN `from`, their titles numbered."
  [from n & [extra]]
  (vec (for [i (range n)]
         (merge {:id (format "spec-%04d" (+ from i)) :title (str "Scenario number " (inc i))} extra))))

(defstep #"the spec at cut spec-3 has (\d+) stamped scenarios, one of them tagged @wip"
  (fn [ctx n]
    (needs-checklist!)
    (let [n (parse-long n)
          half (quot n 2)
          lint (numbered 101 half)
          status (update (numbered 201 (- n half) {:level "contract"}) 0 assoc :wip? true)
          dir (mc/project! {mc/lint-feature lint "dhf/spec/verification/status.feature" status})]
      (mc/cut! dir "spec-3")
      (assoc ctx :dir dir
             :expected {:items (concat (map #(assoc % :context "architecture" :component "Lint" :level "e2e") lint)
                                       (map #(assoc % :context "verification" :component "Status") (rest status)))
                        :planned [(first status)]}))))

(defstep #"dhf/cycles/03/checklist\.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (s/check! (zero? (:exit (run ctx))) (str "expected exit 0\n" (s/show (run ctx))))
    (let [{:keys [items cut cycle]} (read-checklist ctx)
          want (sort-by :id (map #(select-keys % [:id :title :context :component :level]) (get-in ctx [:expected :items])))]
      (s/check! (= (parse-long n) (count items)) (str (count items) " items: " (pr-str (map :id items))))
      (s/check! (= ["spec-3" "03"] [cut cycle]) (str "the checklist names cut " cut " and cycle " cycle))
      (s/check! (= want items) (str "the items are\n" (pr-str items) "\nexpected\n" (pr-str want))))))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [items planned]} (read-checklist ctx)
          wip (first (get-in ctx [:expected :planned]))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] planned) (str "planned: " (pr-str planned)))
      (s/check! (not-any? #(= (:id wip) (:id %)) items) "the @wip scenario is also an item to grade"))))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (needs-checklist!)
    (let [dir (mc/project! {mc/lint-feature [(first mc/default-scenarios) (last mc/default-scenarios)]})]
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      (assoc ctx :dir dir :cycle "03"))))

(defstep #"dhf/cycles/03/checklist\.json holds:"
  (fn [ctx doc]
    (s/check! (zero? (:exit (run ctx))) (str "expected exit 0\n" (s/show (run ctx))))
    (let [got (dissoc (read-checklist ctx) ::text)
          want (json/parse-string doc true)]
      (s/check! (= want got) (str "checklist.json holds\n" (pr-str got) "\nexpected\n" (pr-str want))))))

(defn- in-order? [text ks]
  (let [ix (map #(str/index-of text (str "\"" % "\"")) ks)]
    (and (every? some? ix) (apply < ix))))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [{:keys [items planned] :as c} (read-checklist ctx)
          text (::text c)
          parts (str/split text #"\"items\"")]
      (s/check! (in-order? text ["cycle" "cut" "items" "planned"]) (str "the top-level keys are out of order:\n" text))
      (s/check! (in-order? (second parts) ["id" "title" "context" "component" "level"])
                (str "an item's keys are out of order:\n" text))
      (s/check! (in-order? (last (str/split text #"\"planned\"")) ["id" "title"])
                (str "a planned scenario's keys are out of order:\n" text))
      (doseq [xs [items planned]]
        (s/check! (= (map :id xs) (sort (map :id xs))) (str "not sorted by id: " (pr-str (map :id xs))))))))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (needs-checklist!)
    (let [r (run ctx)
          {:keys [file line]} (:unstamped ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (re-find (re-pattern (str "(?m)^" (java.util.regex.Pattern/quote (str file ":" line ":")) " unstamped-scenario: "))
                         (:err r))
                (str "the error stream does not name " file ":" line "\n" (s/show r)))
      (s/check! (not (fs/exists? (checklist-file ctx))) "a checklist.json was written"))))
