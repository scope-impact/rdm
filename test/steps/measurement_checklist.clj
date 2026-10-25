(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature. The cycle is
  named by the kernel's shared step (dhf/cycles/03/cycle.edn names the cut
  spec-3), which keeps the project these Givens make (:dir); the command is
  the kernel's shared When."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(def checklist-rel (mc/cycle-path "03" "checklist.json"))

(defn- read-checklist [ctx]
  (let [f (fs/file (:dir ctx) checklist-rel)]
    (s/check! (fs/exists? f) (str "no " checklist-rel "\n" (s/show (kc/the-run ctx))))
    (json/parse-string (slurp (str f)) true)))

(defn- passed-checklist [ctx]
  (kc/exits! ctx 0)
  (read-checklist ctx))

;; ---------------------------------------------------------------------------
;; One item per stamped scenario at the cut

(defstep #"the spec at cut spec-3 has (\d+) stamped scenarios, one of them tagged @wip"
  (fn [ctx n]
    (let [n (parse-long n)
          scs (conj (mc/numbered 101 (dec n)) {:id (format "spec-%04d" (+ 100 n)) :title "Planned work" :wip? true})
          dir (mc/project! {mc/lint-feature scs})]
      (mc/cut! dir "spec-3")
      ;; the working tree moves on after the cut: the checklist must not see it
      (s/write! dir mc/lint-feature (mc/feature-text (conj scs {:id "spec-0999" :title "Written after the cut"})))
      (assoc ctx :dir dir
             :items (vec (remove :wip? scs))
             :planned (filterv :wip? scs)))))

(defstep #"dhf/cycles/03/checklist\.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (let [{:keys [items cut cycle]} (passed-checklist ctx)]
      (s/check! (= "03" cycle) (str "the checklist names the cycle " (pr-str cycle)))
      (s/check! (= "spec-3" cut) (str "the checklist names the cut " (pr-str cut)))
      (s/check! (= (parse-long n) (count items)) (str (count items) " items: " (pr-str (map :id items))))
      (s/check! (= (map :id (:items ctx)) (map :id items))
                (str "the items are " (pr-str (map :id items)) ", not " (pr-str (map :id (:items ctx)))))
      (doseq [[want got] (map vector (:items ctx) items)]
        (s/check! (= {:id (:id want) :title (:title want) :context "architecture" :component "Lint" :level "e2e"} got)
                  (str "the item is " (pr-str got)))))))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [items planned]} (read-checklist ctx)
          wip (first (:planned ctx))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] planned) (str "planned: " (pr-str planned)))
      (s/check! (not-any? #(= (:id wip) (:id %)) items) "the @wip scenario is also an item to grade"))))

;; ---------------------------------------------------------------------------
;; checklist.json has one shape

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (let [dir (mc/project! {mc/lint-feature [(nth mc/default-scenarios 2) (first mc/default-scenarios)]})]
      (mc/cut! dir "spec-3")
      (mc/write-cycle! dir "03" {})
      (assoc ctx :dir dir :cycle "03"))))

(defstep #"dhf/cycles/03/checklist\.json holds:"
  (fn [ctx doc]
    (let [got (passed-checklist ctx)
          want (json/parse-string doc true)]
      (s/check! (= want got) (str "the checklist is\n" (pr-str got) "\nnot\n" (pr-str want))))))

(defn- keys-in-order
  "Every object key of a JSON text, in the order written."
  [text]
  (map second (re-seq #"\"([A-Za-z-]+)\"\s*:" text)))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (slurp (str (fs/file (:dir ctx) checklist-rel)))
          {:keys [items planned]} (json/parse-string text true)
          want (concat ["cycle" "cut" "items"]
                       (mapcat (constantly ["id" "title" "context" "component" "level"]) items)
                       ["planned"]
                       (mapcat (constantly ["id" "title"]) planned))]
      (s/check! (= want (keys-in-order text)) (str "the keys are written in the order " (pr-str (keys-in-order text))))
      (s/check! (= (map :id items) (sort (map :id items))) "the items are not sorted by id")
      (s/check! (= (map :id planned) (sort (map :id planned))) "the planned scenarios are not sorted by id"))))

;; ---------------------------------------------------------------------------
;; An unstamped scenario stops the checklist

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          {:keys [file line]} (:unstamped ctx)]
      (s/check! (not (fs/exists? (fs/file (:dir ctx) checklist-rel))) (str checklist-rel " was written"))
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (kc/finding-lines r))
                (str "no finding names " file ":" line "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Under the regulated profile, every clause is an item to grade

(def ^:private clause-lists
  {"dhf/clauses/std.txt" "# A fixture standard\nSTD:4 General requirements\ninclude std-part.txt\nSTD:5.1 Development plan\n"
   "dhf/clauses/std-part.txt" "STD:4.2 Records\nSTD:4 General requirements, listed twice\n"})

(def ^:private clause-items
  [{:id "STD:4" :title "General requirements" :context "compliance" :component "dhf/clauses/std.txt" :level "clause"}
   {:id "STD:4.2" :title "Records" :context "compliance" :component "dhf/clauses/std-part.txt" :level "clause"}
   {:id "STD:5.1" :title "Development plan" :context "compliance" :component "dhf/clauses/std.txt" :level "clause"}])

(defn- regulated-cycle! [ctx scope]
  (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})
        cfg (assoc (kc/config-of dir) :regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})]
    (mc/write-edn! dir "dhf/harness/config.edn" cfg)
    (doseq [[rel text] clause-lists] (s/write! dir rel text))
    (s/write! dir "dhf/documents/plan.md"
              "---\nid: DOC-1\nrevision: 1\ntitle: Development plan\n---\n\n# Plan\n\nThe plan. [[STD:5.1]]\n")
    (mc/cut! dir "spec-3")
    ;; after the cut, the working tree's list holds one more clause
    (spit (str (fs/file dir "dhf/clauses/std.txt")) "STD:9 Written after the cut\n" :append true)
    (mc/write-cycle! dir "03" {:scope scope})
    (assoc ctx :dir dir :cycle "03" :clause-items clause-items)))

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std\.txt at the cut holds \"STD:5\.1 Development plan\""
  (fn [ctx] (regulated-cycle! ctx :project)))

(defstep #"cycle 03 of cut spec-3, scoped to the context architecture, in a regulated project whose clause list at the cut holds clauses"
  (fn [ctx] (regulated-cycle! ctx {:context :architecture})))

(defstep #"the items of dhf/cycles/03/checklist\.json end with:"
  (fn [ctx doc]
    (let [{:keys [items]} (passed-checklist ctx)
          want (json/parse-string doc true)]
      (s/check! (= want (last items)) (str "the last item is " (pr-str (last items)))))))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (let [{:keys [items]} (read-checklist ctx)
          [scenarios clauses] (split-with #(not= "clause" (:level %)) items)]
      (s/check! (= ["spec-0012" "spec-0014"] (map :id scenarios)) (str "the scenario items are " (pr-str (map :id scenarios))))
      (s/check! (= (:clause-items ctx) clauses) (str "the clause items are " (pr-str clauses))))))

(defstep #"dhf/cycles/03/checklist\.json holds no item at the level clause"
  (fn [ctx]
    (let [{:keys [items]} (passed-checklist ctx)]
      (s/check! (seq items) "the checklist holds no item at all")
      (s/check! (not-any? #(= "clause" (:level %)) items) (str "clause items: " (pr-str (filter #(= "clause" (:level %)) items)))))))
