(ns harness.score
  "`harness score NN` (DI-93): file a cycle's verdicts against its checklist
  (`harness/cycles/NN/checklist.json`) as the cycle's record
  (`harness/cycles/NN/score.json`). Verdicts come from a file (`--verdicts`,
  an object of item id to \"pass\" or \"fail\") or from an acceptance run's
  results (`--results`), where each result carries its scenario's item id as a
  tag. An item no verdict reached is a failure marked unreached, never a
  smaller denominator; zero verdicts means the grading did not happen."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [harness.events :refer [event]]
            [harness.json :as json]))

(def scored "Cycle Scored")
(def no-verdicts "Cycle Not Scored / No Verdicts")
(def unknown-item "Cycle Not Scored / Unknown Item")

(defn cycle-dir [root cycle] (fs/path root "harness" "cycles" cycle))

(defn- option [args name] (second (drop-while #(not= name %) args)))

(defn verdicts-from-results
  "Each item's verdict from an acceptance run's Allure results: a pass when
  every result tagged with it passed, a failure when any did not."
  [dir]
  (let [results (for [f (fs/glob dir "*-result.json")
                      :let [r (json/read-file (str f))]
                      label (:labels r)
                      :when (and (= "tag" (:name label)) (re-matches #"item-\d+-\d+" (str (:value label))))]
                  [(:value label) (if (= "passed" (:status r)) "pass" "fail")])]
    (reduce (fn [m [item v]] (update m item #(if (= "fail" %) "fail" v))) {} results)))

(defn derive-score
  "The cycle's record and the events of scoring it."
  [cycle checklist given]
  (let [ids (map :item (:items checklist))
        known (set ids)
        unknown (sort (remove known (keys given)))]
    (cond
      (empty? given) {:events [(event no-verdicts (str "cycle " cycle ": no verdicts at all; the grading did not happen"))]}
      (seq unknown) {:events (vec (for [i unknown] (event unknown-item (str "cycle " cycle ": a verdict for @" i
                                                                             ", which the checklist does not carry"))))}
      :else
      (let [verdicts (into {} (for [i ids]
                                [i (if-let [v (given i)]
                                     {:verdict (if (= "pass" v) "pass" "fail") :unreached false}
                                     {:verdict "fail" :unreached true})]))
            passed (count (filter #(= "pass" (:verdict %)) (vals verdicts)))
            unreached (count (filter :unreached (vals verdicts)))]
        {:record {:cycle cycle :checklist (:items checklist) :verdicts verdicts
                  :passed passed :total (count ids)}
         :events [(event scored (str "cycle " cycle ": " passed " of " (count ids) " passed, "
                                     unreached " unreached"))]}))))

(defn run [root _dhf [cycle & args]]
  (let [dir (cycle-dir root cycle)
        checklist-file (fs/path dir "checklist.json")]
    (when-not (fs/exists? checklist-file)
      (binding [*out* *err*]
        (println (str "harness score: no checklist for cycle " cycle " at " checklist-file
                      "; derive it with: bb harness checklist --out " checklist-file)))
      (System/exit 2))
    (let [given (cond (option args "--verdicts")
                      (into {} (map (fn [[k v]] [(name k) (str/lower-case (str v))]))
                            (json/read-file (option args "--verdicts")))
                      (option args "--results") (verdicts-from-results (option args "--results"))
                      :else {})
          {:keys [record events]} (derive-score cycle (json/read-file (str checklist-file)) given)]
      (when record (spit (str (fs/path dir "score.json")) (json/write-str record)))
      events)))
