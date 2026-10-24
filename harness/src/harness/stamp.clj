(ns harness.stamp
  "`harness stamp` (DI-91): each unstamped scenario of a slice's spec gets the
  next free item id of its slice, on its own line directly above it, at its
  indentation. An id once there is never changed, so rewording or reordering
  keeps the cycle-to-cycle join."
  (:require [clojure.string :as str]
            [harness.events :refer [event]]
            [harness.spec :as spec]))

(def stamped "Scenarios Stamped")
(def no-spec "Scenarios Not Stamped / No Spec")

(defn stamp-text
  "The spec's text with every unstamped scenario stamped, and how many were."
  [text number]
  (let [lines (str/split-lines text)
        scenarios (spec/read-lines lines)
        highest (reduce max 0 (keep spec/item-number (mapcat :tags scenarios)))
        highest (reduce max highest (keep #(some-> (re-find #"@item-\d+-(\d+)" %) second parse-long) lines))
        unstamped (remove :item scenarios)
        ids (zipmap (map :line unstamped)
                    (map #(format "@item-%s-%03d" number (+ highest %)) (iterate inc 1)))
        out (mapcat (fn [n line] (if-let [id (ids n)]
                                   [(str (:indent (first (filter #(= n (:line %)) unstamped))) id) line]
                                   [line]))
                    (iterate inc 1) lines)]
    [(str (str/join "\n" out) (when (str/ends-with? text "\n") "\n")) (count unstamped)]))

(defn derive-events
  "The events of stamping, and the files to write (only on success)."
  [root dhf]
  (let [slices (spec/slices dhf)]
    (if (empty? slices)
      {:events [(event no-spec (str "no spec under " (spec/relative root (str dhf "/" spec/plan-dir))
                                    "/*/spec.feature"))]}
      (let [results (for [s slices :let [file (spec/spec-file s) text (slurp (str file))
                                         [out n] (stamp-text text (spec/slice-number s))]]
                      {:file file :changed (not= out text) :text out :count n})]
        {:writes (filter :changed results)
         :events [(event stamped (str "stamped " (reduce + (map :count results)) " scenario(s) in "
                                      (count results) " spec(s)"))]}))))

(defn run [root dhf _args]
  (let [{:keys [events writes]} (derive-events root dhf)]
    (doseq [{:keys [file text]} writes] (spit (str file) text))
    events))
