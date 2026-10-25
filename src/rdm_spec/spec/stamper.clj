(ns rdm-spec.spec.stamper
  "The Stamper component of the spec context (dhf/spec/spec/stamper.feature):
  `rdm-spec stamp` gives every scenario that carries no id the next id from the
  ledger, and records it, so an id is never handed out twice.

  - An id is @<id-prefix>-NNNN (at least four digits), the prefix from the
    configuration (:id-prefix).
  - The next id is one more than the highest ever issued: the ledger's
    :issued, or a higher id already in the spec if the ledger fell behind
    (so a hand-stamped id is never issued a second time).
  - Scenarios are stamped in path order, then file order. The id is put first
    on the tag line right above the scenario, or on a new line above it, at
    its indentation, when it has no tags.
  - The ledger is written only when an id is issued; a spec whose scenarios
    all carry an id is left untouched, files and ledger alike.
  - A spec the parser refuses anywhere is not stamped at all: the refusals are
    the findings (exit 1) and nothing is written, so a scenario the parser
    could not read is never left silently unstamped."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.spec.parser :as parser]))

(defn format-id [prefix n] (format "%s-%04d" prefix n))

(defn- id-number [prefix id]
  (some-> (re-matches (re-pattern (str (java.util.regex.Pattern/quote prefix) "-(\\d+)")) id)
          second parse-long))

(defn read-ledger
  "The highest id the ledger records as issued; 0 when there is no ledger.
  A ledger that is not EDN or has no :issued number: cannot-run."
  [ledger-file rel]
  (if-not (fs/exists? ledger-file)
    0
    (let [v (try (edn/read-string (slurp (str ledger-file)))
                 (catch Exception e (command/cannot-run! (str "the ledger " rel " is not EDN: " (ex-message e)))))]
      (when-not (and (map? v) (nat-int? (:issued v)))
        (command/cannot-run! (str "the ledger " rel " records no :issued number")))
      (:issued v))))

(def ^:private new-ledger
  (str ";; The id ledger. The stamper hands out the next scenario id from here and\n"
       ";; records it, so an id is never issued twice, not even after its scenario is\n"
       ";; deleted. Never edit by hand.\n"
       "{:issued 0}\n"))

(defn write-ledger!
  "Records `n` as the highest id issued, keeping the ledger's comments."
  [ledger-file n]
  (let [text (if (fs/exists? ledger-file) (slurp (str ledger-file)) new-ledger)
        text (if (re-find #":issued\s+\d+" text)
               (str/replace-first text #":issued\s+\d+" (str ":issued " n))
               (str "{:issued " n "}\n"))]
    (fs/create-dirs (fs/parent (fs/absolutize ledger-file)))
    (spit (str ledger-file) text)))

(defn- tag-line? [s] (str/starts-with? (str/trim s) "@"))

(defn stamp-lines
  "The lines of one file with `stamps` ({scenario-line id-tag}) applied."
  [lines stamps]
  (reduce
   (fn [lines [line tag]]
     (let [i (dec line)                     ; index of the Scenario line
           above (loop [j (dec i) first-tag nil]
                   (if (and (>= j 0) (tag-line? (nth lines j)))
                     (recur (dec j) j)
                     first-tag))]
       (if above
         (let [l (nth lines above)
               indent (subs l 0 (- (count l) (count (str/triml l))))]
           (assoc lines above (str indent tag " " (str/triml l))))
         (let [l (nth lines i)
               indent (subs l 0 (- (count l) (count (str/triml l))))]
           (vec (concat (subvec lines 0 i) [(str indent tag)] (subvec lines i)))))))
   (vec lines)
   ;; bottom up, so inserted lines do not move the ones still to stamp
   (sort-by (comp - key) stamps)))

(defn stamp
  "The stamp task."
  [req]
  (let [prefix (command/config-value req :id-prefix)
        ledger-rel (command/config-value req :ledger)
        ledger-file (command/path req ledger-rel)
        issued (read-ledger ledger-file ledger-rel)
        spec (parser/read-spec (:root req) {:spec-dir (command/config-value req :spec-dir)
                                            :id-prefix prefix
                                            :model (get-in req [:config :model])
                                            :needs (get-in req [:config :needs])})
        refusals (parser/refusal-findings spec)]
    (if (seq refusals)
      {:result {:stamped [] :issued issued}
       :findings (conj refusals
                       (command/finding "stamp-refused"
                                        "the spec has lines the parser refuses; nothing was stamped"))}
      (let [highest-in-spec (reduce max 0 (keep #(id-number prefix %) (mapcat :ids (:scenarios spec))))
            start (max issued highest-in-spec)
            unstamped (filter #(empty? (:ids %)) (:scenarios spec))
            assigned (map-indexed (fn [k sc] (assoc sc :new-id (format-id prefix (+ start k 1)))) unstamped)]
        (when (seq assigned)
          (doseq [[file scs] (group-by :file assigned)]
            (let [f (command/path req file)
                  text (slurp (str f))
                  trailing? (str/ends-with? text "\n")
                  lines (str/split-lines text)
                  out (stamp-lines lines (into {} (map (fn [sc] [(:line sc) (str "@" (:new-id sc))]) scs)))]
              (spit (str f) (str (str/join "\n" out) (when trailing? "\n")))))
          (write-ledger! ledger-file (+ start (count assigned))))
        {:result {:stamped (mapv (fn [sc] {:file (:file sc) :line (:line sc) :name (:name sc) :id (:new-id sc)})
                                 assigned)
                  :issued (if (seq assigned) (+ start (count assigned)) issued)}
         :findings []}))))
