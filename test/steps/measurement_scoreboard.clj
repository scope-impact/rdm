(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature. A
  scoreboard fixture is a project holding the room's configuration and
  cycles whose cycle.edn and score.edn are written directly, as the scorer
  files them."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-scoreboard! [] (s/needs-built! 'rdm-spec.measurement.scoreboard))

(defn- ids [from n] (vec (for [i (range n)] (format "spec-%04d" (+ from i)))))

(defn write-scored!
  "Writes cycle `cycle` scored with `verdicts` {id :pass|:fail} over `scope`."
  [dir cycle verdicts & [scope]]
  (let [scope (or scope :project)
        items (vec (for [[id v] (sort verdicts)] {:id id :verdict v :evidence (str "graded " (name v))}))]
    (s/write! dir (mc/cycle-path cycle "cycle.edn") (pr-str (assoc mc/default-cycle :scope scope)))
    (s/write! dir (mc/cycle-path cycle "score.edn")
              (pr-str (merge {:cycle cycle :scope scope :items items
                              :passed (count (filter #(= :pass (:verdict %)) items)) :scored (count items)}
                             (select-keys mc/default-cycle [:cut :builder :grader :spec-owner]))))))

(defn- board [ctx]
  (let [r (or (:run ctx) (s/fail! "no command was run"))]
    (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
    (or (:scoreboard (kc/stdout-edn r)) (s/fail! (str "the result holds no :scoreboard\n" (s/show r))))))

(defn- fixture! [ctx a b & [scope-b]]
  (needs-scoreboard!)
  (let [dir (or (:dir ctx) (s/project!))]
    (write-scored! dir "01" a)
    (write-scored! dir "02" b scope-b)
    (assoc ctx :dir dir :a a :b b)))

(defn- all [ids v] (into {} (map #(vector % v) ids)))

;; ---------------------------------------------------------------------------
;; The scoreboard compares cycles honestly

(defstep #"cycle 01 and cycle 02 scored over the same (\d+) items"
  (fn [ctx n]
    (let [xs (ids 501 (parse-long n))]
      ;; half pass in both, to begin with
      (fixture! ctx (all xs :pass) (all xs :pass)))))

(defstep #"(\d+) items passed in 02 that failed in 01, and (\d+) failed in 02 that passed in 01"
  (fn [ctx better worse]
    (let [xs (sort (keys (:a ctx)))
          [up more] (split-at (parse-long better) xs)
          down (take (parse-long worse) more)
          a (merge (:a ctx) (all up :fail))
          b (merge (:b ctx) (all down :fail))]
      ;; one item failed in both: neither better nor worse
      (let [both (last xs)]
        (fixture! ctx (assoc a both :fail) (assoc b both :fail))))))

(defstep #"it reports (\d+) got better and (\d+) got worse"
  (fn [ctx better worse]
    (let [b (board ctx)]
      (s/check! (= [(parse-long better) (parse-long worse)] [(:better b) (:worse b)])
                (str "it reports " (:better b) " better and " (:worse b) " worse: " (pr-str b))))))

(defn- keys-in [x]
  (cond (map? x) (concat (keys x) (mapcat keys-in (vals x)))
        (sequential? x) (mapcat keys-in x)
        :else []))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [b (board ctx)
          r (:run ctx)]
      (s/check! (not-any? #(re-find #"(?i)net|delta|change|diff" (name %)) (filter keyword? (keys-in b)))
                (str "the scoreboard reports a net figure: " (pr-str b)))
      (s/check! (not (re-find #"(?i)\bnet\b" (str (:out r) (:err r)))) (str "the output names a net\n" (s/show r))))))

(defstep #"cycle 01 scored (\d+) items and cycle 02 scored (\d+)"
  (fn [ctx n m]
    (let [a (ids 501 (parse-long n))
          b (ids 501 (parse-long m))]
      ;; the shared items: one got better, one got worse; a new item failed
      (fixture! ctx (merge (all a :pass) {(first a) :fail})
                (merge (all b :pass) {(second b) :fail (last b) :fail})))))

(defstep #"it compares only the (\d+) items both scored"
  (fn [ctx n]
    (let [b (board ctx)]
      (s/check! (= (parse-long n) (:compared b)) (str "it compared " (:compared b) " items: " (pr-str b)))
      (s/check! (= [1 1] [(:better b) (:worse b)]) (str "the new items were counted as better or worse: " (pr-str b))))))

(defstep #"it states that the denominator moved from (\d+) to (\d+)"
  (fn [ctx n m]
    (let [b (board ctx)
          said (str "the denominator moved from " n " to " m)]
      (s/check! (some #(str/includes? % said) (:statements b)) (str "the result does not state it: " (pr-str b)))
      (s/check! (str/includes? (:err (:run ctx)) said) (str "it is not said on the error stream\n" (s/show (:run ctx)))))))

(defstep #"it counts the (\d+) new items separately"
  (fn [ctx n]
    (let [b (board ctx)
          new (get-in b [:only-in "02"])]
      (s/check! (= (parse-long n) (count new)) (str "new items: " (pr-str new)))
      (s/check! (= (sort new) (sort (remove (set (keys (:a ctx))) (keys (:b ctx))))) (str "new items: " (pr-str new))))))

;; ---------------------------------------------------------------------------
;; A scoped cycle is reported target first

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (let [verification (ids 601 4)
          rest-ids (ids 701 6)
          a (merge (all verification :fail) (all rest-ids :pass) {(last verification) :pass})
          b (merge (all verification :pass) {(last verification) :fail (first verification) :fail})]
      (assoc (fixture! ctx a b {:context :verification}) :verification verification :rest-ids rest-ids))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (let [b (board ctx)
          t (:target b)
          v (:verification ctx)]
      (s/check! t (str "no target is reported: " (pr-str b)))
      (s/check! (= {:context :verification} (:scope t)) (str "the target is " (pr-str (:scope t))))
      (s/check! (= [(count v) 2 1 [(nth v 1) (nth v 2)] [(last v)]]
                   [(:compared t) (:better t) (:worse t) (:better-items t) (:worse-items t)])
                (str "the target reports " (pr-str t))))))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (let [b (board ctx)]
      (s/check! (= (count (:rest-ids ctx)) (get-in b [:rest :not-scored "02"])) (str "the rest: " (pr-str (:rest b))))
      (s/check! (= 0 (get-in b [:rest :compared])) (str "the rest was compared: " (pr-str (:rest b))))
      (s/check! (some #(str/includes? % "the rest was not scored in cycle 02") (:statements b))
                (str "the result does not state it: " (pr-str b))))))

;; ---------------------------------------------------------------------------
;; A bootstrap cycle is never a baseline

(defstep #"cycle 00's cycle\.edn holds :bootstrap true"
  (fn [ctx]
    (needs-scoreboard!)
    (let [dir (s/project!)]
      (s/write! dir (mc/cycle-path "00" "cycle.edn") (pr-str (assoc mc/default-cycle :bootstrap true)))
      (write-scored! dir "01" (all (ids 501 3) :pass))
      (assoc ctx :dir dir))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (let [b (board ctx)]
      (s/check! (= ["00"] (:bootstrap b)) (str "the scoreboard marks " (pr-str (:bootstrap b))))
      (s/check! (some #(re-find #"cycle 00 is a bootstrap" %) (:statements b)) (pr-str b)))))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [b (board ctx)]
      (s/check! (not-any? #{:better :worse :better-items :worse-items :compared} (keys-in b))
                (str "a count is reported against a bootstrap: " (pr-str b))))))

