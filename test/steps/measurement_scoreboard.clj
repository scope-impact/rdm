(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature:
  `rdm-spec scoreboard A B` in a project (the room's configuration only)
  holding the cycles a step declares: each cycle.edn and score.edn written
  as their contracts state them (spec-0174), the scores' verdicts as the
  step says.

  The scoreboard's result: {:scoreboard ..} on standard output, its shapes
  stated by spec-0283 and spec-0284.

  Context keys set here: :dir, :scores ({cycle {id verdict}}), :scopes
  ({cycle scope})."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- ids [from n] (mapv :id (mc/numbered from n)))

(defn- write-score! [dir cycle scope verdicts]
  (mc/write-cycle! dir cycle {:scope scope})
  (let [items (vec (for [[id v] (sort-by key verdicts)] {:id id :verdict v :evidence (str id " was graded " (name v))}))]
    (mc/write-edn! dir (mc/cycle-path cycle "score.edn")
                   {:cycle cycle :cut "spec-3" :scope scope :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
                    :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
                    :spec-owner {:name "Sami" :actor :human}
                    :items items :passed (count (filter #{:pass} (vals verdicts))) :scored (count items)})))

(defn- cycles!
  "ctx with :dir (made unless an earlier Given made one), the cycles
  `scores` ({cycle {id verdict}}) scored over `scopes` ({cycle scope})."
  [ctx scores scopes]
  (let [dir (or (:dir ctx) (s/project!))
        scores (merge (:scores ctx) scores)
        scopes (merge (:scopes ctx) scopes)]
    (doseq [[c vs] scores] (write-score! dir c (get scopes c :project) vs))
    (assoc ctx :dir dir :scores scores :scopes scopes)))

(defn- all [vs v] (into (sorted-map) (for [id vs] [id v])))

(defn- board [ctx]
  (let [r (kc/exits! ctx 0)
        sb (:scoreboard (kc/stdout-edn r))]
    (s/check! (map? sb) (str "standard output holds no {:scoreboard ..}\n" (s/show r)))
    sb))

;; ---------------------------------------------------------------------------
;; Both directions, never the net

(defstep #"cycle 01 and cycle 02 scored over the same 12 items"
  (fn [ctx] (cycles! ctx {"01" (all (ids 1 12) :pass) "02" (all (ids 1 12) :pass)} {})))

(defstep #"3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01"
  (fn [ctx]
    (let [[a b c d] (ids 1 4)]
      (cycles! ctx {"01" (merge (get-in ctx [:scores "01"]) {a :fail b :fail c :fail})
                    "02" (merge (get-in ctx [:scores "02"]) {d :fail})}
               {}))))

(defstep #"it reports (\d+) got better and (\d+) got worse"
  (fn [ctx better worse]
    (let [sb (board ctx)]
      (s/check! (= [(parse-long better) (parse-long worse)] [(:better sb) (:worse sb)])
                (str "the scoreboard says " (pr-str sb)))
      nil)))

(defn- all-keys [x]
  (cond (map? x) (concat (keys x) (mapcat all-keys (vals x)))
        (sequential? x) (mapcat all-keys x)
        :else []))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [sb (board ctx)
          ks (map #(str/lower-case (name %)) (filter keyword? (all-keys sb)))]
      (s/check! (not-any? #(re-find #"net|delta|change|difference|total" %) ks) (str "the scoreboard nets: " (pr-str sb)))
      nil)))

;; ---------------------------------------------------------------------------
;; A moved denominator

(defstep #"cycle 01 scored 12 items and cycle 02 scored 15"
  (fn [ctx] (cycles! ctx {"01" (all (ids 1 12) :pass) "02" (all (ids 1 15) :pass)} {})))

(defstep #"it compares only the (\d+) items both scored"
  (fn [ctx n]
    (let [sb (board ctx)]
      (s/check! (= (parse-long n) (:compared sb)) (str "the scoreboard compares " (pr-str (:compared sb))))
      nil)))

(defn- states! [sb part]
  (s/check! (some #(str/includes? % part) (:statements sb)) (str "no statement says " (pr-str part) ": " (pr-str (:statements sb)))))

(defstep #"it states that the denominator moved from (\d+) to (\d+)"
  (fn [ctx a b]
    (states! (board ctx) (str "denominator moved from " a " to " b))
    nil))

(defstep #"it counts the (\d+) new items separately"
  (fn [ctx n]
    (let [sb (board ctx)]
      (s/check! (= (drop 12 (ids 1 15)) (get-in sb [:only-in "02"])) (str "the new items are " (pr-str (:only-in sb))))
      (s/check! (= (parse-long n) (count (get-in sb [:only-in "02"]))) "not every new item is counted")
      nil)))

;; ---------------------------------------------------------------------------
;; A scoped cycle

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (let [every (ids 1 10)
          verification (ids 4 3)]
      (cycles! ctx {"01" (assoc (all every :pass) (first verification) :fail)
                    "02" (all verification :pass)}
               {"02" {:context :verification}}))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (let [t (:target (board ctx))]
      (s/check! (= {:scope {:context :verification} :cycle "02" :compared 3 :better 1 :worse 0
                    :better-items [(first (ids 4 1))] :worse-items []}
                   t)
                (str "the target is " (pr-str t)))
      nil)))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (states! (board ctx) "the rest was not scored in cycle 02")
    nil))

;; ---------------------------------------------------------------------------
;; A bootstrap

(defstep #"cycle 00's cycle\.edn holds :bootstrap true"
  (fn [ctx]
    (let [ctx (cycles! ctx {"01" (all (ids 1 5) :pass)} {})]
      (mc/write-cycle! (:dir ctx) "00" {:bootstrap true :cut "spec-1"})
      ctx)))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (let [sb (board ctx)]
      (s/check! (= ["00"] (:bootstrap sb)) (str "the scoreboard marks " (pr-str (:bootstrap sb))))
      nil)))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [sb (board ctx)
          ks (set (all-keys sb))]
      (s/check! (not-any? ks [:better :worse :better-items :worse-items]) (str "the scoreboard compares: " (pr-str sb)))
      nil)))

;; ---------------------------------------------------------------------------
;; The result's shape

(defstep #"cycle 01 and cycle 02 each scored 5 items of the whole project, 1 better and 1 worse"
  (fn [ctx]
    (let [[a b] (ids 1 2)]
      (cycles! ctx {"01" (assoc (all (ids 1 5) :pass) b :fail)
                    "02" (assoc (all (ids 1 5) :pass) a :fail)}
               {}))))

(defstep #"cycle 02 scored 5 items of the whole project and cycle 03, scoped to the component shop/till, scored 2 of them, 1 better"
  (fn [ctx]
    (let [[d e] (ids 4 2)]
      (cycles! ctx {"02" (assoc (all (ids 1 5) :pass) e :fail)
                    "03" {d :pass e :pass}}
               {"03" {:component :shop/till}}))))
