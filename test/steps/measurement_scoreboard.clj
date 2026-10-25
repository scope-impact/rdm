(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature.

  The scoreboard meets the other steps of a cycle only through its files, so
  the fixture writes them directly: for each cycle, cycle.edn, checklist.json
  (each item's context and component) and score.edn (each item's verdict),
  in a project holding the room's configuration and C4 model. Items are
  spec-0501 on; :verification items are owned by the component Status of
  the context verification, the others by Lint of architecture."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- id [i] (format "spec-%04d" (+ 501 i)))

(defn board-project!
  "ctx with a project (the room's configuration and model, no cut needed)."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [dir (s/tmp-dir)]
      (s/write! dir "dhf/harness/config.edn" (pr-str (s/room-config)))
      (s/write! dir "dhf/spec/model.edn" (slurp (str (fs/file s/room-root "dhf/spec/model.edn"))))
      (assoc ctx :dir dir))))

(defn write-board-cycle!
  "Writes cycle `cycle` of the project: `verdicts` [[id verdict owner]]
  (owner :verification or :lint), declared with `extra` (merged into the
  default cycle), and a score.edn unless :no-score."
  [ctx cycle verdicts & [{:keys [extra no-score]}]]
  (let [dir (:dir ctx)
        decl (merge {:cut "spec-3" :scope :project} extra)
        owner {:verification ["verification" "Status"] :lint ["architecture" "Lint"]}]
    (mc/write-cycle! dir cycle decl)
    (s/write! dir (mc/cycle-path cycle "checklist.json")
              (json/generate-string
               (array-map :cycle cycle :cut "spec-3"
                          :items (vec (for [[i _ o] (sort-by first verdicts)
                                            :let [[c k] (owner (or o :lint))]]
                                        (array-map :id i :title (str "Scenario " i) :context c
                                                   :component k :level "e2e")))
                          :planned [])
               {:pretty true}))
    (when-not no-score
      (s/write! dir (mc/cycle-path cycle "score.edn")
                (pr-str {:cycle cycle :cut "spec-3" :scope (:scope decl) :room-tree "4b825dc"
                         :builder {:name "builder-a" :actor :agent}
                         :grader {:name "grader-b" :actor :agent}
                         :spec-owner {:name "Sami" :actor :human}
                         :items (vec (for [[i v] (sort-by first verdicts)]
                                       {:id i :verdict v :evidence "graded"}))
                         :passed (count (filter #(= :pass (second %)) verdicts))
                         :scored (count verdicts)})))
    ctx))

(defn- result [ctx]
  (let [r (:run ctx)]
    (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
    (kc/stdout-edn r)))

;; ---------------------------------------------------------------------------
;; The scoreboard compares cycles honestly

(defstep #"cycle 01 and cycle 02 scored over the same (\d+) items"
  (fn [ctx n] (assoc (board-project! ctx) :n (parse-long n))))

(defstep #"(\d+) items passed in 02 that failed in 01, and (\d+) failed in 02 that passed in 01"
  (fn [ctx better worse]
    (let [b (parse-long better) w (parse-long worse) n (:n ctx)
          ;; better, then worse, then passed in both, then (the last two) failed in both
          v01 (fn [i] (cond (< i b) :fail (< i (+ b w)) :pass (>= i (- n 2)) :fail :else :pass))
          v02 (fn [i] (cond (< i b) :pass (< i (+ b w)) :fail (>= i (- n 2)) :fail :else :pass))]
      (s/check! (<= (+ b w 2) n) "too few items for the fixture")
      (-> ctx
          (write-board-cycle! "01" (vec (for [i (range n)] [(id i) (v01 i)])))
          (write-board-cycle! "02" (vec (for [i (range n)] [(id i) (v02 i)])))))))

(defstep #"it reports (\d+) got better and (\d+) got worse"
  (fn [ctx better worse]
    (let [out (result ctx)]
      (s/check! (= [(parse-long better) (parse-long worse)] [(:better out) (:worse out)])
                (str "it reports better " (:better out) " and worse " (:worse out) ": " (pr-str out))))))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [out (result ctx)
          ks (atom [])]
      (walk/postwalk (fn [x] (when (map? x) (swap! ks into (keys x))) x) out)
      (s/check! (not-any? #(re-find #"(?i)net|total|delta|diff" (name %)) @ks)
                (str "the result holds a net figure: " (pr-str out)))
      (s/check! (not (re-find #"(?i)\bnet\b" (str (:out (:run ctx)) (:err (:run ctx)))))
                (str "it says net\n" (s/show (:run ctx)))))))

(defstep #"cycle 01 scored (\d+) items and cycle 02 scored (\d+)"
  (fn [ctx a b]
    (let [a (parse-long a) b (parse-long b)
          ctx (board-project! ctx)]
      ;; the first two shared items got better; the new items all pass
      (-> ctx
          (write-board-cycle! "01" (vec (for [i (range a)] [(id i) (if (< i 2) :fail :pass)])))
          (write-board-cycle! "02" (vec (for [i (range b)] [(id i) :pass])))
          (assoc :shared a :new (- b a))))))

(defstep #"it compares only the (\d+) items both scored"
  (fn [ctx n]
    (let [out (result ctx)]
      (s/check! (= (parse-long n) (:compared out)) (str "it compares " (:compared out) " items: " (pr-str out)))
      (s/check! (= [2 0] [(:better out) (:worse out)]) (str "better and worse are not over the shared items: " (pr-str out))))))

(defstep #"it states that the denominator moved from (\d+) to (\d+)"
  (fn [ctx from to]
    (let [out (result ctx)]
      (s/check! (= {:from (parse-long from) :to (parse-long to)} (select-keys (:denominator out) [:from :to]))
                (str "the denominator is " (pr-str (:denominator out))))
      (s/check! (re-find (re-pattern (str "denominator moved from " from " to " to)) (:err (:run ctx)))
                (str "it does not say so on the error stream\n" (s/show (:run ctx)))))))

(defstep #"it counts the (\d+) new items separately"
  (fn [ctx n]
    (let [out (result ctx)]
      (s/check! (= (parse-long n) (:new out)) (str "it counts " (pr-str (:new out)) " new items"))
      (s/check! (= (parse-long n) (count (:new-ids out))) (str "the new items are " (pr-str (:new-ids out)))))))

;; ---------------------------------------------------------------------------
;; A scoped cycle is reported target first

(defstep #"cycle 02 regenerated the bounded context verification and cycle 01 is its baseline"
  (fn [ctx]
    ;; verification: 2 better, 1 worse; the rest: 1 better, 2 worse
    (let [rows [[:verification :fail :pass] [:verification :fail :pass] [:verification :pass :fail]
                [:verification :pass :pass] [:lint :fail :pass] [:lint :pass :fail] [:lint :pass :fail]
                [:lint :pass :pass] [:lint :fail :fail]]
          ctx (board-project! ctx)]
      (-> ctx
          (write-board-cycle! "01" (vec (map-indexed (fn [i [o a _]] [(id i) a o]) rows)))
          (write-board-cycle! "02" (vec (map-indexed (fn [i [o _ b]] [(id i) b o]) rows))
                              {:extra {:scope {:context :verification}}})))))

(defn- part [ctx i]
  (let [out (result ctx)
        p (get (:parts out) i)]
    (s/check! p (str "the result has no part " i ": " (pr-str out)))
    p))

(defstep #"it reports better and worse for verification's scenarios first"
  (fn [ctx]
    (let [p (part ctx 0)]
      (s/check! (= :target (:part p)) (str "the first part is " (pr-str p)))
      (s/check! (= {:context :verification} (:scope p)) (str "the first part's scope is " (pr-str (:scope p))))
      (s/check! (= [2 1] [(:better p) (:worse p)]) (str "the first part is " (pr-str p))))))

(defstep #"then, separately, better and worse for every other scenario"
  (fn [ctx]
    (let [p (part ctx 1)]
      (s/check! (= :rest (:part p)) (str "the second part is " (pr-str p)))
      (s/check! (= [1 2 5] [(:better p) (:worse p) (:compared p)]) (str "the second part is " (pr-str p))))))

;; ---------------------------------------------------------------------------
;; A bootstrap cycle is never a baseline

(defstep #"cycle 00's cycle.edn holds :bootstrap true"
  (fn [ctx]
    (let [rows (vec (for [i (range 4)] [(id i) :fail]))]
      (-> (board-project! ctx)
          (write-board-cycle! "00" rows {:extra {:bootstrap true}})
          (write-board-cycle! "01" (assoc-in rows [0 1] :pass))))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (let [out (result ctx)]
      (s/check! (= ["00"] (:bootstrap out)) (str "the result marks as bootstrap " (pr-str (:bootstrap out))))
      (s/check! (str/includes? (:err (:run ctx)) "bootstrap") (str "it does not say so\n" (s/show (:run ctx)))))))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [out (result ctx)]
      (s/check! (not-any? #(contains? out %) [:better :worse :parts :compared])
                (str "it compares against the bootstrap: " (pr-str out))))))
