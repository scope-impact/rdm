(ns rdm-spec.spec.stamper
  "The stamper (dhf/spec/spec/stamper.feature): hands every unstamped scenario
  the next id from the ledger, and records it there, so an id is never issued
  twice, not even after its scenario is deleted."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.spec.parser :as parser]))

(defn format-id [prefix n] (format "%s-%04d" prefix n))

(defn stamp-text
  "Tags the scenarios on `lines` (1-based) with `ids`: into the tag line just
  above a scenario (comments between allowed), else on a new line above it."
  [text lines ids]
  (let [ls (vec (str/split text #"\n" -1))]
    (str/join "\n"
              (reduce
               (fn [ls [n id]]
                 (let [i (dec n)
                       l (nth ls i)
                       j (loop [j (dec i)]
                           (cond (< j 0) nil
                                 (str/blank? (nth ls j)) nil
                                 (str/starts-with? (str/trim (nth ls j)) "#") (recur (dec j))
                                 :else j))]
                   (if (and j (str/starts-with? (str/trim (nth ls j)) "@"))
                     (let [tl (nth ls j)]
                       (assoc ls j (str (re-find #"^\s*" tl) "@" id " " (str/triml tl))))
                     (vec (concat (subvec ls 0 i) [(str (re-find #"^\s*" l) "@" id)] (subvec ls i))))))
               ls
               (sort-by first > (map vector lines ids))))))

(defn ledger-text
  "The ledger's text with the highest issued id set to n, its comments kept."
  [text n]
  (if (re-find #":issued\s+\d+" text)
    (str/replace-first text #":issued\s+\d+" (str ":issued " n))
    (str "{:issued " n "}\n")))

(defn stage
  "Stamps every unstamped scenario of the working tree's spec, in file order."
  [{:keys [root config]}]
  (let [prefix (:id-prefix config "spec")
        ledger-path (:ledger config "dhf/spec/ids.edn")
        ledger-src (cmd/read-at root nil ledger-path)
        issued (try (:issued (cmd/read-edn ledger-src)) (catch Exception _ nil))]
    (if-not (integer? issued)
      {:findings [(cmd/finding ledger-path nil "ledger-unreadable"
                               (if ledger-src "the ledger holds no :issued count" "the ledger is missing"))]}
      (let [{:keys [scenarios refusals features]} (parser/parse root nil config)
            bad-files (set (map :file refusals))
            todo (->> scenarios
                      (filter #(and (empty? (:ids %)) (not (bad-files (:file %)))))
                      (sort-by (juxt :file :line)))
            stamped (map-indexed (fn [i s] (assoc s :new-id (format-id prefix (+ issued 1 i)))) todo)]
        (doseq [[file ss] (group-by :file stamped)]
          (let [f (fs/file root file)]
            (spit f (stamp-text (slurp f) (mapv :line ss) (mapv :new-id ss)))))
        (when (seq stamped)
          (spit (fs/file root ledger-path) (ledger-text ledger-src (+ issued (count stamped)))))
        {:findings refusals
         :result {:stamp {:stamped (mapv (fn [s] {:id (:new-id s) :file (:file s) :line (:line s)}) stamped)
                          :issued (+ issued (count stamped))}}}))))
