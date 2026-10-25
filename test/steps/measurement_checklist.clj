(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature.

  Helpers other measurement step files use (a contract):
    (numbered start n)   n scenarios [{:id :title}], ids spec-<start>.. in order
    (read-checklist ctx) the project's dhf/cycles/03/checklist.json, keys as
                         keywords; fails the step when there is none
    (top-keys text)      the keys of the JSON object `text`, in written order

  Fixture projects are cycle projects (steps.measurement-cycle); every one
  is made with steps.support/tmp-dir, so the run removes it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-checklist! [] (s/needs-built! 'rdm-spec.measurement.checklist))

(defn numbered
  "n scenarios [{:id :title}], their ids spec-<start> onwards."
  [start n]
  (vec (for [i (range n)
             :let [id (format "spec-%04d" (+ start i))]]
         {:id id :title (str "The numbered scenario " id)})))

(defn- checklist-file [ctx] (fs/file (:dir ctx) (mc/cycle-path "03" "checklist.json")))

(defn read-checklist
  "The project's dhf/cycles/03/checklist.json, keys as keywords."
  [ctx]
  (let [f (checklist-file ctx)]
    (s/check! (fs/exists? f) (str "no " (mc/cycle-path "03" "checklist.json")
                                  (when (:run ctx) (str "\n" (s/show (:run ctx))))))
    (json/parse-string (slurp (str f)) true)))

;; ---------------------------------------------------------------------------
;; JSON key order, read from the text

(defn- objects
  "Every JSON object in `text` as [start end] offsets, with the keys written
  directly in it, in order: [{:keys [k ..] :depth d}]."
  [text]
  (let [n (count text)]
    (loop [i 0 stack [] out [] pending nil]
      (if (>= i n)
        out
        (let [c (.charAt ^String text i)]
          (cond
            (= c \") (let [end (loop [j (inc i)]
                                 (let [d (.charAt ^String text j)]
                                   (cond (= d \\) (recur (+ j 2))
                                         (= d \") j
                                         :else (recur (inc j)))))
                           s (subs text (inc i) end)
                           after (str/triml (subs text (inc end)))
                           key? (and (seq stack) (= :obj (:kind (peek stack))) (str/starts-with? after ":"))]
                       (recur (inc end)
                              (if key? (conj (pop stack) (update (peek stack) :keys conj s)) stack)
                              out pending))
            (= c \{) (recur (inc i) (conj stack {:kind :obj :keys [] :depth (count stack)}) out pending)
            (= c \[) (recur (inc i) (conj stack {:kind :arr}) out pending)
            (= c \}) (recur (inc i) (pop stack) (conj out (select-keys (peek stack) [:keys :depth])) pending)
            (= c \]) (recur (inc i) (pop stack) out pending)
            :else (recur (inc i) stack out pending)))))))

(defn top-keys
  "The keys of the outermost JSON object of `text`, in written order."
  [text]
  (:keys (first (filter #(zero? (:depth %)) (objects text)))))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private status-feature "dhf/spec/verification/status.feature")

(defn- cycle-project!
  "A cycle project whose spec is `files`, cut spec-3, cycle 03 declared."
  [ctx files cycle-overrides]
  (let [dir (mc/project! files)]
    (mc/cut! dir "spec-3")
    (mc/write-cycle! dir "03" cycle-overrides)
    (assoc ctx :dir dir :room (mc/room!) :cycle "03")))

(defstep #"the spec at cut spec-3 has 12 stamped scenarios, one of them tagged @wip"
  (fn [ctx]
    (needs-checklist!)
    (let [scs (numbered 12 12)
          scs (-> scs
                  (assoc-in [3 :wip?] true)
                  (assoc-in [1 :level] "contract")
                  (assoc-in [8 :level] "contract"))
          [lint status] (split-at 6 scs)
          owner (fn [sc] (if (some #(= (:id sc) (:id %)) lint) ["architecture" "Lint"] ["verification" "Status"]))
          dir (mc/project! {mc/lint-feature (vec lint) status-feature (vec status)})]
      (mc/cut! dir "spec-3")
      (assoc ctx :dir dir :room (mc/room!)
             :expected {:items (vec (for [sc scs :when (not (:wip? sc))
                                          :let [[c k] (owner sc)]]
                                      {:id (:id sc) :title (:title sc) :context c :component k
                                       :level (or (:level sc) "e2e")}))
                        :planned (vec (for [sc scs :when (:wip? sc)] {:id (:id sc) :title (:title sc)}))}))))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (needs-checklist!)
    (cycle-project! ctx {mc/lint-feature [{:id "spec-0013" :title "A component that owns no feature file fails the lint"
                                           :wip? true}
                                          {:id "spec-0012" :title "A view with no description fails the lint"}]}
                    {})))

(def ^:private documents
  {"dhf/documents/plan.md" "---\nid: PLAN-001\nrevision: 1\ntitle: Development plan\n---\n\n# Plan\n\nWe plan [[STD:5.1]].\n"})

(defn- regulated-project!
  "A regulated cycle project: config :regulated over dhf/clauses/std.txt,
  the clause lists `lists` {rel text}, cut spec-3, cycle 03 of `scope`."
  [ctx scope lists]
  (let [ctx (cycle-project! ctx {mc/lint-feature mc/default-scenarios} {:scope scope})
        dir (:dir ctx)
        cfg-rel "dhf/harness/config.edn"
        cfg (read-string (slurp (str (fs/file dir cfg-rel))))]
    (s/write! dir cfg-rel (pr-str (assoc cfg :regulated {:clause-lists ["dhf/clauses/std.txt"]
                                                         :documents "dhf/documents"})))
    (doseq [[f t] (merge lists documents)] (s/write! dir f t))
    (mc/cut! dir "spec-3")
    ctx))

(def ^:private clause-lists
  "std.txt includes base.txt in place and repeats one of its keys."
  {"dhf/clauses/std.txt" "# The standard\nSTD:4 General requirements\ninclude base.txt\nSTD:4.1 Quality system\nSTD:5.1 Development plan\n"
   "dhf/clauses/base.txt" "STD:4.1 Quality system\nSTD:4.2 Documentation\n"})

(def ^:private expected-clauses
  [{:id "STD:4" :title "General requirements" :context "compliance" :component "dhf/clauses/std.txt" :level "clause"}
   {:id "STD:4.1" :title "Quality system" :context "compliance" :component "dhf/clauses/base.txt" :level "clause"}
   {:id "STD:4.2" :title "Documentation" :context "compliance" :component "dhf/clauses/base.txt" :level "clause"}
   {:id "STD:5.1" :title "Development plan" :context "compliance" :component "dhf/clauses/std.txt" :level "clause"}])

(defstep #"cycle 03 of cut spec-3, scoped to the whole project, in a regulated project whose clause list dhf/clauses/std\.txt at the cut holds \"STD:5\.1 Development plan\""
  (fn [ctx]
    (needs-checklist!)
    (assoc (regulated-project! ctx :project clause-lists) :expected-clauses expected-clauses)))

(defstep #"cycle 03 of cut spec-3, scoped to the context (\S+), in a regulated project whose clause list at the cut holds clauses"
  (fn [ctx context]
    (needs-checklist!)
    (regulated-project! ctx {:context (keyword context)} clause-lists)))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"dhf/cycles/03/checklist\.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (kc/exits! ctx 0)
    (let [{:keys [items]} (read-checklist ctx)]
      (s/check! (= (parse-long n) (count items)) (str "the checklist holds " (count items) " items"))
      (doseq [it items]
        (s/check! (= #{:id :title :context :component :level} (set (keys it)))
                  (str "an item does not hold exactly the id, title, context, component and level: " (pr-str it))))
      (s/check! (= (get-in ctx [:expected :items]) items)
                (str "the items are\n" (pr-str items) "\nexpected\n" (pr-str (get-in ctx [:expected :items]))))
      nil)))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [items planned]} (read-checklist ctx)
          want (get-in ctx [:expected :planned])]
      (s/check! (= want planned) (str "planned holds " (pr-str planned) ", expected " (pr-str want)))
      (s/check! (not-any? (set (map :id want)) (map :id items)) "the @wip scenario is also an item to grade")
      nil)))

(defstep #"dhf/cycles/03/checklist\.json holds:"
  (fn [ctx doc]
    (kc/exits! ctx 0)
    (let [got (json/parse-string (slurp (str (checklist-file ctx))))
          want (json/parse-string doc)]
      (s/check! (= want got) (str "checklist.json holds\n" (pr-str got) "\nexpected\n" (pr-str want)))
      (assoc ctx :expected-json doc))))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (slurp (str (checklist-file ctx)))
          objs (objects text)
          {:keys [items planned]} (read-checklist ctx)]
      (s/check! (= ["cycle" "cut" "items" "planned"] (top-keys text))
                (str "the top-level keys are in the order " (pr-str (top-keys text))))
      (doseq [o (filter #(= 2 (:depth %)) objs)]
        (s/check! (contains? #{["id" "title" "context" "component" "level"] ["id" "title"]} (:keys o))
                  (str "an item's keys are in the order " (pr-str (:keys o)))))
      (doseq [[what xs] [["items" items] ["planned" planned]]]
        (s/check! (= (map :id xs) (sort (map :id xs))) (str what " are not sorted by id: " (pr-str (map :id xs)))))
      nil)))

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          {:keys [file line]} (:unstamped ctx)]
      (s/check! (not (fs/exists? (checklist-file ctx))) "a checklist.json was written")
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (s/err-lines r))
                (str "no finding names " file ":" line "\n" (s/show r)))
      nil)))

(defstep #"the items of dhf/cycles/03/checklist\.json end with:"
  (fn [ctx doc]
    (kc/exits! ctx 0)
    (let [{:keys [items]} (read-checklist ctx)
          want (json/parse-string doc true)]
      (s/check! (= want (last items)) (str "the last item is " (pr-str (last items)) ", expected " (pr-str want)))
      nil)))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (let [{:keys [items]} (read-checklist ctx)
          levels (map :level items)
          clauses (filter #(= "clause" (:level %)) items)]
      (s/check! (= levels (concat (remove #{"clause"} levels) (filter #{"clause"} levels)))
                (str "a clause item comes before a scenario item: " (pr-str levels)))
      (s/check! (seq (remove #(= "clause" (:level %)) items)) "the checklist holds no scenario item")
      (s/check! (= (:expected-clauses ctx) clauses)
                (str "the clause items are\n" (pr-str clauses) "\nexpected\n" (pr-str (:expected-clauses ctx))))
      nil)))

(defstep #"dhf/cycles/03/checklist\.json holds no item at the level clause"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [{:keys [items]} (read-checklist ctx)]
      (s/check! (seq items) "the checklist holds no item at all")
      (s/check! (not-any? #(= "clause" (:level %)) items)
                (str "the checklist holds clause items: " (pr-str (filter #(= "clause" (:level %)) items))))
      nil)))
