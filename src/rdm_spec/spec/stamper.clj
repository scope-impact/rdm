(ns rdm-spec.spec.stamper
  "The stamper (dhf/spec/spec/stamper.feature): gives every scenario that
  carries no id the next id from the ledger, in file order, and records the
  highest id issued, so no id is ever issued twice, not even after its
  scenario is deleted. An id says nothing about a scenario's wording or
  location. Each spec stamps from its own ledger, with its own prefix."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.spec.parser :as parser]))

(defn stamp-text
  "The text with each id [scenario-line id] put on the scenario's tag line,
  or on a tag line of its own above it."
  [text stamps]
  (let [lines (vec (str/split text #"\n" -1))]
    (str/join "\n"
              (reduce (fn [ls [line id]]
                        (let [i (dec line)
                              above (when (pos? i) (nth ls (dec i)))
                              indent (re-find #"^\s*" (nth ls i))]
                          (if (and above (str/starts-with? (str/trim above) "@"))
                            (let [ai (re-find #"^\s*" above)]
                              (assoc ls (dec i) (str ai "@" id " " (str/trim above))))
                            (vec (concat (subvec ls 0 i) [(str indent "@" id)] (subvec ls i))))))
                      lines
                      (sort-by first > stamps)))))

(defn ledger-text
  "The ledger's text with the highest id issued set to n, its comments kept."
  [text n]
  (if (and text (re-find #":issued\s+\d+" text))
    (str/replace-first text #":issued\s+\d+" (str ":issued " n))
    (str "{:issued " n "}\n")))

(defn issued
  "The highest id the ledger has issued, or nil when it cannot be read."
  [root rev config]
  (let [v (try (cmd/read-edn (cmd/read-at root rev (:ledger config "dhf/spec/ids.edn"))) (catch Exception _ nil))]
    (when (and (map? v) (integer? (:issued v))) (:issued v))))

(defn stage [{:keys [root config]}]
  (let [ledger (:ledger config "dhf/spec/ids.edn")
        prefix (:id-prefix config "spec")
        n0 (issued root nil config)]
    (if (nil? n0)
      {:findings [(cmd/finding ledger nil "ledger-unreadable" "the ledger is missing or holds no {:issued N}: no id can be handed out")]}
      (let [{:keys [scenarios]} (parser/read-spec root nil config)
            unstamped (filter #(empty? (:ids %)) scenarios)
            stamps (map-indexed (fn [i s] {:file (:file s) :line (:line s) :id (format "%s-%04d" prefix (+ n0 i 1))}) unstamped)
            n (+ n0 (count stamps))]
        (doseq [[file ss] (group-by :file stamps)]
          (let [f (fs/file root file)]
            (spit f (stamp-text (slurp f) (map (juxt :line :id) ss)))))
        (when (seq stamps)
          (let [f (fs/file root ledger)]
            (spit f (ledger-text (when (fs/exists? f) (slurp f)) n))))
        {:result {:stamp {:stamped (vec stamps) :issued n}}}))))
