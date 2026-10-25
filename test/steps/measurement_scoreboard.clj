(ns steps.measurement-scoreboard
  "Step definitions for dhf/spec/measurement/scoreboard.feature. Each Given
  writes cycles' cycle.edn and score.edn into a project (the room's
  configuration); the command is the kernel's shared When. The scoreboard's
  result is read from standard output ({:scoreboard ..}, the shape
  rdm-spec.measurement.scoreboard states)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- score [cycle scope verdicts]
  (let [items (vec (for [[id v] verdicts] {:id id :verdict v :evidence (str "graded " (name v))}))]
    (merge (select-keys mc/default-cycle [:builder :grader :spec-owner])
           {:cycle cycle :cut "spec-3" :scope scope :room-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904"
            :items items :passed (count (filter #(= :pass (:verdict %)) items)) :scored (count items)})))

(defn- write-scored! [dir cycle scope verdicts]
  (mc/write-cycle! dir cycle {:scope scope})
  (mc/write-edn! dir (mc/cycle-path cycle "score.edn") (score cycle scope verdicts)))

(defn- ids [n] (mapv :id (mc/numbered 101 n)))

(defn- all [ids v] (mapv #(vector % v) ids))

(defn- result [ctx]
  (let [r (kc/exits! ctx 0)
        out (kc/stdout-edn r)]
    (s/check! (map? (:scoreboard out)) (str "the result holds no :scoreboard\n" (s/show r)))
    (:scoreboard out)))

;; ---------------------------------------------------------------------------
;; Both directions, never the net

(defstep #"cycle 01 and cycle 02 scored over the same (\d+) items"
  (fn [ctx n]
    (let [dir (s/project!)
          xs (ids (parse-long n))]
      (write-scored! dir "01" :project (all xs :pass))
      (write-scored! dir "02" :project (all xs :pass))
      (assoc ctx :dir dir :ids xs))))

(defstep #"(\d+) items passed in 02 that failed in 01, and (\d+) failed in 02 that passed in 01"
  (fn [ctx better worse]
    (let [[b w] [(parse-long better) (parse-long worse)]
          xs (:ids ctx)
          better-ids (set (take b xs))
          worse-ids (set (take w (drop b xs)))]
      (write-scored! (:dir ctx) "01" :project (mapv (fn [id] [id (if (better-ids id) :fail :pass)]) xs))
      (write-scored! (:dir ctx) "02" :project (mapv (fn [id] [id (if (worse-ids id) :fail :pass)]) xs))
      (assoc ctx :better (sort better-ids) :worse (sort worse-ids)))))

(defstep #"it reports (\d+) got better and (\d+) got worse"
  (fn [ctx better worse]
    (let [sb (result ctx)]
      (s/check! (= (parse-long better) (get-in sb [:better :count])) (str "better: " (pr-str (:better sb))))
      (s/check! (= (parse-long worse) (get-in sb [:worse :count])) (str "worse: " (pr-str (:worse sb))))
      (s/check! (= (:better ctx) (get-in sb [:better :items])) (str "better: " (pr-str (:better sb))))
      (s/check! (= (:worse ctx) (get-in sb [:worse :items])) (str "worse: " (pr-str (:worse sb)))))))

(defstep #"it reports no net figure"
  (fn [ctx]
    (let [sb (result ctx)
          net (- (get-in sb [:better :count]) (get-in sb [:worse :count]))
          ks (atom []) nums (atom [])]
      (walk/postwalk (fn [x] (cond (keyword? x) (swap! ks conj x) (number? x) (swap! nums conj x)) x) sb)
      (s/check! (not-any? #(re-find #"net|delta|change" (name %)) @ks) (str "a key names a net: " (pr-str @ks)))
      (s/check! (not-any? #{net} @nums) (str "the result holds the net figure " net ": " (pr-str sb)))
      (s/check! (not (str/includes? (str/lower-case (pr-str sb)) " net")) (str "the result says a net: " (pr-str sb))))))

;; ---------------------------------------------------------------------------
;; A moved denominator is said out loud

(defstep #"cycle 01 scored (\d+) items and cycle 02 scored (\d+)"
  (fn [ctx a b]
    (let [dir (s/project!)
          [a b] [(parse-long a) (parse-long b)]]
      (write-scored! dir "01" :project (all (ids a) :pass))
      (write-scored! dir "02" :project (all (ids b) :pass))
      (assoc ctx :dir dir :new (vec (drop a (ids b)))))))

(defstep #"it compares only the (\d+) items both scored"
  (fn [ctx n]
    (let [sb (result ctx)]
      (s/check! (= (parse-long n) (:compared sb)) (str "it compared " (pr-str (:compared sb)))))))

(defstep #"it states that the denominator moved from (\d+) to (\d+)"
  (fn [ctx a b]
    (let [sb (result ctx)]
      (s/check! (= {:from (parse-long a) :to (parse-long b) :moved true} (:denominator sb))
                (str "the denominator: " (pr-str (:denominator sb))))
      (s/check! (some #(str/includes? % (str "denominator moved from " a " to " b)) (:says sb))
                (str "it does not say the denominator moved: " (pr-str (:says sb)))))))

(defstep #"it counts the (\d+) new items separately"
  (fn [ctx n]
    (let [sb (result ctx)]
      (s/check! (= {:cycle "02" :count (parse-long n) :items (:new ctx)} (:new sb)) (str "new: " (pr-str (:new sb))))
      (s/check! (not-any? (set (:new ctx)) (concat (get-in sb [:better :items]) (get-in sb [:worse :items])))
                "a new item is counted as better or worse"))))

;; ---------------------------------------------------------------------------
;; A scoped cycle is reported target first

(defstep #"cycle 01 scored every scenario and cycle 02 scored only the bounded context verification's"
  (fn [ctx]
    (let [dir (s/project!)
          xs (ids 12)
          verification (subvec xs 8)]
      ;; one of verification's got better, one got worse; one of the rest failed in 01
      (write-scored! dir "01" :project (mapv (fn [id] [id (if (#{(nth xs 8) (nth xs 0)} id) :fail :pass)]) xs))
      (write-scored! dir "02" {:context :verification}
                     (mapv (fn [id] [id (if (= id (nth xs 9)) :fail :pass)]) verification))
      (assoc ctx :dir dir :target verification :rest (subvec xs 0 8)))))

(defstep #"it reports better and worse for verification's scenarios"
  (fn [ctx]
    (let [sb (result ctx)]
      (s/check! (= (count (:target ctx)) (:compared sb)) (str "it compared " (pr-str (:compared sb))))
      (s/check! (= [(nth (:target ctx) 0)] (get-in sb [:better :items])) (str "better: " (pr-str (:better sb))))
      (s/check! (= [(nth (:target ctx) 1)] (get-in sb [:worse :items])) (str "worse: " (pr-str (:worse sb))))
      (s/check! (str/includes? (first (:says sb)) "got better") (str "the target is not reported first: " (pr-str (:says sb)))))))

(defstep #"it states that the rest was not scored in cycle 02"
  (fn [ctx]
    (let [sb (result ctx)]
      (s/check! (= {:cycle "02" :count (count (:rest ctx)) :items (:rest ctx)} (:not-scored sb))
                (str "not scored: " (pr-str (:not-scored sb))))
      (s/check! (some #(str/includes? % "not scored in cycle 02") (:says sb))
                (str "it does not say the rest was not scored: " (pr-str (:says sb)))))))

;; ---------------------------------------------------------------------------
;; A bootstrap cycle is never a baseline

(defstep #"cycle 00's cycle\.edn holds :bootstrap true"
  (fn [ctx]
    (let [dir (s/project!)]
      (mc/write-cycle! dir "00" {:bootstrap true})
      (write-scored! dir "01" :project (all (ids 3) :pass))
      (assoc ctx :dir dir))))

(defstep #"it marks cycle 00 as bootstrap"
  (fn [ctx]
    (let [sb (result ctx)]
      (s/check! (= ["00"] (:bootstrap sb)) (str "bootstrap: " (pr-str (:bootstrap sb))))
      (s/check! (some #(str/includes? % "cycle 00 is a bootstrap") (:says sb)) (str "it says " (pr-str (:says sb)))))))

(defstep #"it reports no better or worse count against it"
  (fn [ctx]
    (let [sb (result ctx)]
      (s/check! (not-any? #(contains? sb %) [:better :worse :compared])
                (str "it compares against a bootstrap: " (pr-str sb))))))
