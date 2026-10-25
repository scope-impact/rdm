(ns rdm-spec.spec.stamper
  "Stamper (dhf/spec/spec/stamper.feature): `rdm-spec stamp` gives every
  scenario that carries no id the next id from the ledger, and records the
  highest id issued in the ledger. An id is handed out once: never computed
  from a scenario's wording or location, never issued again, not even after
  its scenario is deleted.

  The id is @<id-prefix>-NNNN (the configured :id-prefix, four digits or
  more), written first on the scenario's tag line, or on a new tag line
  above it when it has none. Scenarios are stamped in file order, then line
  order. An id some scenario already carries is never issued (the spec
  check names an id the ledger never issued).

  Findings (exit 1), stamping nothing: the Parser's refusals (a feature file
  outside the subset might hide a scenario), and a ledger missing or
  unreadable (ledger-unreadable). Stamping a spec in which every scenario
  carries an id changes no file.

  Result: {:stamp {:stamped [{:id :file :line :title}] :issued n}}."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.check :as check]
            [rdm-spec.spec.parser :as parser]))

(defn format-id
  "The id number `n` with `prefix`: spec-0042."
  [prefix n]
  (format "%s-%04d" prefix n))

(defn next-ids
  "Pure: `k` ids after `issued`, skipping the numbers in `taken`."
  [issued taken k]
  (vec (take k (remove (set taken) (iterate inc (inc issued))))))

(defn tag-line-of
  "Pure: the index (0-based) of the tag line directly above the scenario on
  line `line` (1-based) of `lines`, comments and blank lines skipped, or nil."
  [lines line]
  (loop [i (- line 2)]
    (when (>= i 0)
      (let [t (str/trim (nth lines i))]
        (cond
          (str/starts-with? t "@") i
          (str/starts-with? t "#") (recur (dec i))
          :else nil)))))

(defn stamp-text
  "Pure: `text` with each scenario of `stamps` ({line id}) tagged @id."
  [text stamps]
  (let [lines (vec (str/split-lines text))
        trailing (if (str/ends-with? text "\n") "\n" "")
        ;; from the bottom up, so earlier line numbers stay where they are
        out (reduce (fn [ls [line id]]
                      (let [raw (nth ls (dec line))
                            indent (subs raw 0 (- (count raw) (count (str/triml raw))))]
                        (if-let [i (tag-line-of ls line)]
                          (let [tl (nth ls i)
                                ind (subs tl 0 (- (count tl) (count (str/triml tl))))]
                            (assoc ls i (str ind "@" id " " (str/triml tl))))
                          (vec (concat (subvec ls 0 (dec line)) [(str indent "@" id)] (subvec ls (dec line)))))))
                    lines
                    (sort-by key > stamps))]
    (str (str/join "\n" out) trailing)))

(defn- write-ledger!
  "Records `issued` in the ledger `f`, keeping its comments."
  [f text issued]
  (let [changed (if (and text (re-find #":issued\s+\d+" text))
                  (str/replace-first text #":issued\s+\d+" (str ":issued " issued))
                  (str "{:issued " issued "}\n"))]
    (spit (str f) changed)))

(defn stamp
  "The stamp task's stage."
  [req]
  (let [prefix (command/config-value req :id-prefix)
        spec (parser/read-spec (:root req) (:config req))
        ledger-rel (command/config-value req :ledger)
        ledger-f (command/path req ledger-rel)
        ledger-text (when (fs/regular-file? ledger-f) (slurp (str ledger-f)))
        ledger (check/read-ledger ledger-rel ledger-text)
        refusals (for [{:keys [file line rule message]} (:refusals spec)] (command/finding file line rule message))
        stops (vec (concat refusals (when-let [f (:finding ledger)] [f])))]
    (if (seq stops)
      {:result {} :findings stops}
      (let [unstamped (filter #(empty? (:ids %)) (:scenarios spec))
            taken (keep check/id-number (filter #(str/starts-with? % (str prefix "-")) (mapcat :ids (:scenarios spec))))
            numbers (next-ids (:issued ledger) taken (count unstamped))
            stamped (mapv (fn [sc n] {:id (format-id prefix n) :file (:file sc) :line (:line sc) :title (:title sc)})
                          unstamped numbers)]
        (doseq [[file ss] (group-by :file stamped)
                :let [f (command/path req file)]]
          (spit (str f) (stamp-text (slurp (str f)) (into {} (map (juxt :line :id)) ss))))
        (let [issued (if (seq numbers) (apply max numbers) (:issued ledger))]
          (when (seq numbers) (write-ledger! ledger-f ledger-text issued))
          {:result {:stamp {:stamped stamped :issued issued}} :findings []})))))
