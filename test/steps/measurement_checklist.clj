(ns steps.measurement-checklist
  "Step definitions for dhf/spec/measurement/checklist.feature, in cycle
  projects (steps.measurement-cycle: a copy of the room's allow-list whose
  spec is the fixture's, cut spec-3, with cycle 03). The When is the
  kernel's.

  Helpers (a contract for the measurement step files using them):
    (numbered start n)     n scenarios {:id :title}, ids from spec-<start>
    (read-checklist ctx)   the cycle's checklist.json (ctx :cycle, else 03),
                           keyword keys; fails when there is none"
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-checklist! [] (s/needs-built! 'rdm-spec.measurement.checklist))

(defn numbered
  "`n` scenarios {:id :title}, ids spec-<start> upward."
  [start n]
  (vec (for [i (range n) :let [k (+ start i)]]
         {:id (format "spec-%04d" k) :title (str "Numbered scenario " k)})))

(defn- checklist-file [ctx] (fs/file (:dir ctx) (mc/cycle-path (or (:cycle ctx) "03") "checklist.json")))

(defn read-checklist
  "The cycle's checklist.json, keyword keys."
  [ctx]
  (let [f (checklist-file ctx)]
    (s/check! (fs/regular-file? f) (str "no checklist.json\n" (some-> (:run ctx) s/show)))
    (json/parse-string (slurp (str f)) true)))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- passed! [ctx]
  (let [r (run ctx)]
    (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
    r))

(defn- cycle-project!
  "ctx with a cycle project whose spec at the cut spec-3 is `scenarios` in
  the Lint feature, and cycle 03 naming that cut."
  [ctx scenarios]
  (needs-checklist!)
  (let [dir (mc/project! {mc/lint-feature scenarios})]
    (mc/cut! dir "spec-3")
    (mc/write-cycle! dir "03" {})
    (assoc ctx :dir dir :cycle "03" :scenarios scenarios)))

;; ---------------------------------------------------------------------------
;; The checklist is derived from the cycle's cut

(defstep #"the spec at cut spec-3 has (\d+) stamped scenarios, one of them tagged @wip"
  (fn [ctx n]
    (needs-checklist!)
    (let [scs (vec (map-indexed (fn [i sc] (cond-> sc (= i 4) (assoc :wip? true)
                                                  (odd? i) (assoc :level "contract")))
                                (numbered 12 (parse-long n))))
          dir (mc/project! {mc/lint-feature scs})]
      (mc/cut! dir "spec-3")
      (assoc ctx :dir dir :scenarios scs))))

(defstep #"dhf/cycles/03/checklist\.json holds (\d+) items to grade, each with the scenario's id, title, context, component and level"
  (fn [ctx n]
    (passed! ctx)
    (let [{:keys [items cut]} (read-checklist ctx)
          want (remove :wip? (:scenarios ctx))]
      (s/check! (= "spec-3" cut) (str "the checklist names the cut " (pr-str cut)))
      (s/check! (= (parse-long n) (count items)) (str "the checklist holds " (count items) " items"))
      (s/check! (= (map :id want) (map :id items)) (str "the items are " (pr-str (map :id items))))
      (doseq [[sc it] (map vector want items)]
        (s/check! (= {:id (:id sc) :title (:title sc) :context "architecture" :component "Lint"
                      :level (or (:level sc) "e2e")}
                     it)
                  (str "the item " (pr-str it) " does not name its scenario " (pr-str sc)))))))

(defstep #"it lists the @wip scenario separately, as planned"
  (fn [ctx]
    (let [{:keys [items planned]} (read-checklist ctx)
          wip (first (filter :wip? (:scenarios ctx)))]
      (s/check! (= [{:id (:id wip) :title (:title wip)}] planned) (str "planned is " (pr-str planned)))
      (s/check! (not-any? #(= (:id wip) (:id %)) items) "the @wip scenario is an item to grade"))))

(defstep #"cycle 03 of cut spec-3, with one item to grade and one planned scenario"
  (fn [ctx]
    (cycle-project! ctx [{:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}
                         {:id "spec-0012" :title "A view with no description fails the lint"}])))

(defstep #"dhf/cycles/03/checklist\.json holds:"
  (fn [ctx doc]
    (passed! ctx)
    (let [want (json/parse-string doc)
          got (json/parse-string (slurp (str (checklist-file ctx))))]
      (s/check! (= want got) (str "checklist.json holds " (pr-str got))))))

(defn- in-order? [text ks]
  (let [ps (map #(str/index-of text (str "\"" % "\"")) ks)]
    (and (every? some? ps) (apply < ps))))

(defstep #"its keys appear in that order, items and planned sorted by id"
  (fn [ctx]
    (let [text (slurp (str (checklist-file ctx)))
          {:keys [items planned]} (read-checklist ctx)
          item-keys (map second (re-seq #"\"(id|title|context|component|level)\"\s*:" text))
          sorted? (fn [xs] (= (map :id xs) (sort (map :id xs))))]
      (s/check! (in-order? text ["cycle" "cut" "items" "planned"]) (str "the keys are not in order:\n" text))
      (s/check! (= (take (* 5 (count items)) item-keys)
                   (take (* 5 (count items)) (cycle ["id" "title" "context" "component" "level"])))
                (str "an item's keys are not in order:\n" text))
      (s/check! (and (sorted? items) (sorted? planned)) (str "not sorted by id:\n" text)))))

;; ---------------------------------------------------------------------------
;; An unstamped scenario stops the checklist

(defstep #"it exits 1, writes nothing, and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [r (run ctx)
          {:keys [file line]} (:unstamped ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (not (fs/exists? (checklist-file ctx))) "a checklist.json was written")
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (s/err-lines r))
                (str "no finding names " file ":" line "\n" (s/show r))))))

;; ---------------------------------------------------------------------------
;; Under the regulated profile, every clause is an item to grade

(def ^:private regulated
  {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"})

(defstep #"cycle 03 of cut spec-3 in a regulated project whose clause list dhf/clauses/std\.txt at the cut holds \"([^\"]+)\""
  (fn [ctx line]
    (needs-checklist!)
    (let [dir (mc/project! {mc/lint-feature mc/default-scenarios})
          cfg-file (fs/file dir "dhf/harness/config.edn")
          cfg (read-string (slurp (str cfg-file)))]
      (spit (str cfg-file) (pr-str (assoc cfg :regulated regulated)))
      ;; an include, in place before it, and a key listed twice
      (s/write! dir "dhf/clauses/std.txt" (str "# The standard\ninclude base.txt\n" line "\nSTD:4.1 Quality system, again\n"))
      (s/write! dir "dhf/clauses/base.txt" "STD:4.1 Quality system\n")
      (s/write! dir "dhf/documents/plan.md"
                "---\nid: PLAN-001\nrevision: 1\ntitle: Development plan\n---\n\nWe plan [[STD:4.1]].\n")
      (mc/cut! dir "spec-3")
      ;; after the cut: what the checklist must not read
      (s/write! dir "dhf/clauses/std.txt" (str line "\nSTD:9.1 Written after the cut\n"))
      (mc/write-cycle! dir "03" {})
      (assoc ctx :dir dir :cycle "03"
             :clauses [{:id "STD:4.1" :title "Quality system" :context "compliance"
                        :component "dhf/clauses/base.txt" :level "clause"}
                       {:id (first (str/split line #"\s+" 2)) :title (second (str/split line #"\s+" 2))
                        :context "compliance" :component "dhf/clauses/std.txt" :level "clause"}]))))

(defstep #"the items of dhf/cycles/03/checklist\.json end with:"
  (fn [ctx doc]
    (passed! ctx)
    (let [want (json/parse-string doc true)
          {:keys [items]} (read-checklist ctx)]
      (s/check! (= want (last items)) (str "the last item is " (pr-str (last items)))))))

(defstep #"the clause items come after every scenario item, in the clause lists' order with includes in place, each key once"
  (fn [ctx]
    (let [{:keys [items]} (read-checklist ctx)
          levels (map :level items)
          clause-items (filter #(= "clause" (:level %)) items)]
      (s/check! (= levels (concat (remove #{"clause"} levels) (filter #{"clause"} levels)))
                (str "a clause item comes before a scenario item: " (pr-str (map :id items))))
      (s/check! (seq (remove #(= "clause" (:level %)) items)) "the checklist holds no scenario item")
      (s/check! (= (:clauses ctx) clause-items) (str "the clause items are " (pr-str clause-items))))))
