(ns harness.scoreboard
  "`harness scoreboard NN` (DI-94): a cycle's pass rate over every item its
  checklist carried, and, against the latest earlier cycle scored, what got
  better and what got worse on the items both scored, counted apart and never
  netted; a moved denominator is said out loud, and new items are counted
  apart from the comparison."
  (:require [babashka.fs :as fs]
            [harness.events :refer [event]]
            [harness.json :as json]
            [harness.score :as score]))

(def compared "Cycles Compared")

(defn- record [root cycle]
  (let [f (fs/path (score/cycle-dir root cycle) "score.json")]
    (when (fs/exists? f) (json/read-file (str f)))))

(defn- earlier [root cycle]
  (->> (fs/list-dir (fs/parent (score/cycle-dir root cycle)))
       (map fs/file-name)
       (filter #(and (re-matches #"\d+" %) (< (parse-long %) (parse-long cycle))))
       (sort-by parse-long) reverse
       (some #(when (record root %) %))))

(defn- verdict [r item] (get-in r [:verdicts (keyword item) :verdict]))

(defn report
  "The scoreboard's lines, from a cycle's record and the earlier one's (or nil)."
  [cycle r before-cycle before]
  (let [items (map :item (:checklist r))
        lines [(str "cycle " cycle ": " (:passed r) " of " (:total r) " passed")]]
    (if-not before
      (conj lines "no earlier cycle was scored: nothing to compare")
      (let [shared (filter #(verdict before %) items)
            new (remove #(verdict before %) items)
            better (count (filter #(and (= "fail" (verdict before %)) (= "pass" (verdict r %))) shared))
            worse (count (filter #(and (= "pass" (verdict before %)) (= "fail" (verdict r %))) shared))]
        (cond-> (conj lines (str "against cycle " before-cycle ", on " (count shared) " shared item(s): "
                                 better " got better, " worse " got worse (counted apart, never netted)"))
          (not= (:total before) (:total r))
          (conj (str "the denominator moved from " (:total before) " to " (:total r)))
          (seq new)
          (conj (str (count new) " new item(s), not compared: "
                     (count (filter #(= "pass" (verdict r %)) new)) " passed")))))))

(defn run [root _dhf [cycle]]
  (let [r (record root cycle)]
    (when-not r
      (binding [*out* *err*] (println (str "harness scoreboard: cycle " cycle " has not been scored")))
      (System/exit 2))
    (let [before-cycle (earlier root cycle)]
      (doseq [line (report cycle r before-cycle (when before-cycle (record root before-cycle)))]
        (println line))
      (flush)
      [(event compared (if before-cycle (str "cycle " cycle " against cycle " before-cycle)
                           (str "cycle " cycle " has no earlier cycle")))])))
