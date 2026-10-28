(ns steps.measurement-scoreboard
  "Steps of the component Scoreboard (dhf/spec/07-measure/scoreboard.feature)."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn scored!
  "Records cycle N as scored: VERDICTS {id :pass|:fail}, with SCOPE and BOOTSTRAP."
  [ctx n verdicts & [{:keys [scope bootstrap]}]]
  (let [ctx (if (:dir ctx) ctx (k/project! ctx))
        items (vec (for [[id v] (sort-by key verdicts)] {:id id :verdict v :evidence "graded"}))]
    (k/write-edn! ctx (str "dhf/cycles/" n "/cycle.edn")
                  (cond-> {:cut (str "spec-" (parse-long n)) :scope (or scope :project)
                           :builder {:name "b" :actor :agent} :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}}
                    bootstrap (assoc :bootstrap true)))
    (k/write-edn! ctx (str "dhf/cycles/" n "/score.edn")
                  {:cycle n :cut (str "spec-" (parse-long n)) :scope (or scope :project) :items items
                   :passed (count (filter #(= :pass (:verdict %)) items)) :scored (count items)})
    ctx))

(defn ids [from to] (for [i (range from (inc to))] (format "spec-%04d" i)))

(defn board [ctx] (get-in ctx [:result :scoreboard]))

(defstep #"cycle 01 and cycle 02 scored over the same 12 items"
  (fn [ctx] (assoc ctx ::twelve true)))

(defstep #"3 items passed in 02 that failed in 01, and 1 failed in 02 that passed in 01"
  (fn [ctx]
    (let [base (zipmap (ids 1 12) (repeat :pass))]
      (-> ctx
          (scored! "01" (merge base {"spec-0001" :fail "spec-0002" :fail "spec-0003" :fail}))
          (scored! "02" (merge base {"spec-0004" :fail}))))))

(defstep #"it reports 3 got better and 1 got worse"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= [3 1] [(:better (board ctx)) (:worse (board ctx))]) "the board is " (pr-str (board ctx))) ctx))

(defstep #"it reports no net figure"
  (fn [ctx] (k/check! (not-any? #(re-find #"(?i)net" (name %)) (keys (board ctx))) "the board has a net figure") ctx))

(defstep #"cycle 01 scored 12 items and cycle 02 scored 15"
  (fn [ctx] (-> ctx (scored! "01" (zipmap (ids 1 12) (repeat :pass))) (scored! "02" (zipmap (ids 1 15) (repeat :pass))))))

(defstep #"it compares only the 12 items both scored"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= 12 (:compared (board ctx))) "it compares " (:compared (board ctx))) ctx))

(defstep #"it states that the denominator moved from 12 to 15"
  (fn [ctx]
    (k/check! (some #(and (str/includes? % "denominator") (str/includes? % "12") (str/includes? % "15")) (:statements (board ctx)))
              "the statements are " (pr-str (:statements (board ctx))))
    ctx))

(defstep #"it counts the 3 new items separately"
  (fn [ctx] (k/check! (= (vec (ids 13 15)) (get-in (board ctx) [:only-in "02"])) "only in 02: " (pr-str (:only-in (board ctx)))) ctx))

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (-> ctx
        (scored! "01" (merge (zipmap (ids 1 10) (repeat :pass)) {"spec-0001" :fail}))
        (scored! "02" {"spec-0001" :pass "spec-0002" :fail} {:scope {:context :verification}}))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= {:compared 2 :better 1 :worse 1} (select-keys (:target (board ctx)) [:compared :better :worse])) "the board is " (pr-str (board ctx)))
    ctx))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (k/check! (some #(str/includes? % "the rest was not scored in cycle 02") (:statements (board ctx))) "the statements are " (pr-str (:statements (board ctx))))
    ctx))

(defstep #"cycle 00's cycle.edn holds :bootstrap true"
  (fn [ctx]
    (-> ctx (scored! "00" (zipmap (ids 1 3) (repeat :fail)) {:bootstrap true}) (scored! "01" (zipmap (ids 1 3) (repeat :pass))))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= ["00"] (:bootstrap (board ctx))) "the board is " (pr-str (board ctx))) ctx))

(defstep #"it reports no better or worse count against it"
  (fn [ctx] (k/check! (not-any? #(contains? (board ctx) %) [:better :worse :target]) "the board counts " (pr-str (board ctx))) ctx))

(defstep #"cycle 01 and cycle 02 each scored 5 items of the whole project, 1 better and 1 worse"
  (fn [ctx]
    (let [base (zipmap (ids 1 5) (repeat :pass))]
      (-> ctx (scored! "01" (assoc base "spec-0002" :fail)) (scored! "02" (assoc base "spec-0001" :fail))))))

(defstep #"cycle 02 scored 5 items of the whole project and cycle 03, scoped to the component shop/till, scored 2 of them, 1 better"
  (fn [ctx]
    (-> ctx
        (scored! "02" (assoc (zipmap (ids 1 5) (repeat :pass)) "spec-0005" :fail))
        (scored! "03" {"spec-0004" :pass "spec-0005" :pass} {:scope {:component :shop/till}}))))
