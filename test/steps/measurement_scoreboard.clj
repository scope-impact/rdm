(ns steps.measurement-scoreboard
  "Step definitions of dhf/spec/measurement/scoreboard.feature: each cycle's
  score.edn written as the scorer writes one."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn id [i] (format "spec-%04d" i))

(defn score!
  "Cycle n scored {id verdict}, at the scope."
  [ctx n verdicts & {:keys [scope bootstrap]}]
  (let [ctx (k/project! ctx)
        items (vec (for [[i v] (sort-by key verdicts)] {:id i :verdict v :evidence "graded"}))]
    (k/write-files! (:dir ctx)
                    {(str "dhf/cycles/" n "/cycle.edn") (k/pr-edn (cond-> (merge {:cut "spec-3" :scope (or scope :project)} k/roles)
                                                                    bootstrap (assoc :bootstrap true)))
                     (str "dhf/cycles/" n "/score.edn")
                     (k/pr-edn (merge {:cycle n :cut "spec-3" :scope (or scope :project) :room-tree "x"} k/roles
                                      {:items items :passed (count (filter #(= :pass (:verdict %)) items)) :scored (count items)}))})
    ctx))

(defn board [ctx] (k/check-exit ctx 0) (:scoreboard (k/edn-out ctx)))

(defstep #"cycle 01 and cycle 02 scored over the same 12 items"
  (fn [ctx] (assoc ctx ::n 12)))

(defstep #"3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01"
  (fn [ctx]
    (let [ids (map id (range 1 13))
          one (into {} (for [i ids] [i (if (#{"spec-0001" "spec-0002" "spec-0003"} i) :fail :pass)]))
          two (into {} (for [i ids] [i (if (= "spec-0004" i) :fail :pass)]))]
      (-> ctx (score! "01" one) (score! "02" two)))))

(defstep #"it reports 3 got better and 1 got worse"
  (fn [ctx]
    (let [b (board ctx)]
      (assert (= [3 1] [(:better b) (:worse b)]) (pr-str b))
      ctx)))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [b (board ctx)]
      (assert (not-any? #(re-find #"net" (name %)) (keys b)) (pr-str b))
      (assert (not (re-find #"(?i)\bnet\b" (:out ctx))))
      ctx)))

(defstep #"cycle 01 scored 12 items and cycle 02 scored 15"
  (fn [ctx]
    (-> ctx (score! "01" (into {} (for [i (range 1 13)] [(id i) :pass])))
        (score! "02" (into {} (for [i (range 1 16)] [(id i) :pass]))))))

(defstep #"it compares only the 12 items both scored"
  (fn [ctx] (assert (= 12 (:compared (board ctx))) (:out ctx)) ctx))

(defstep #"it states that the denominator moved from 12 to 15"
  (fn [ctx] (assert (some #(str/includes? % "denominator moved from 12 to 15") (:statements (board ctx))) (:out ctx)) ctx))

(defstep #"it counts the 3 new items separately"
  (fn [ctx] (assert (= ["spec-0013" "spec-0014" "spec-0015"] (get-in (board ctx) [:only-in "02"])) (:out ctx)) ctx))

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (-> ctx
        (score! "01" {"spec-0001" :fail "spec-0002" :pass "spec-0003" :pass "spec-0004" :pass})
        (score! "02" {"spec-0001" :pass "spec-0002" :fail} :scope {:context :verification}))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (let [t (:target (board ctx))]
      (assert (= [2 1 1 ["spec-0001"] ["spec-0002"]] ((juxt :compared :better :worse :better-items :worse-items) t)) (:out ctx))
      ctx)))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (assert (some #(str/includes? % "the rest was not scored in cycle 02") (:statements (board ctx))) (:out ctx))
    ctx))

(defstep #"cycle 00's cycle.edn holds :bootstrap true"
  (fn [ctx]
    (-> ctx (score! "00" {"spec-0001" :fail "spec-0002" :pass} :bootstrap true)
        (score! "01" {"spec-0001" :pass "spec-0002" :fail}))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx] (assert (= ["00"] (:bootstrap (board ctx))) (:out ctx)) ctx))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [b (board ctx)]
      (assert (not-any? #{:better :worse :target :rest} (keys b)) (pr-str b))
      ctx)))

(defstep #"cycle 01 and cycle 02 each scored 5 items of the whole project, 1 better and 1 worse"
  (fn [ctx]
    (-> ctx (score! "01" {"spec-0001" :pass "spec-0002" :fail "spec-0003" :pass "spec-0004" :pass "spec-0005" :pass})
        (score! "02" {"spec-0001" :fail "spec-0002" :pass "spec-0003" :pass "spec-0004" :pass "spec-0005" :pass}))))

(defstep #"cycle 02 scored 5 items of the whole project and cycle 03, scoped to the component shop/till, scored 2 of them, 1 better"
  (fn [ctx]
    (-> ctx (score! "02" {"spec-0001" :pass "spec-0002" :pass "spec-0003" :pass "spec-0004" :pass "spec-0005" :fail})
        (score! "03" {"spec-0004" :pass "spec-0005" :pass} :scope {:component :shop/till}))))
