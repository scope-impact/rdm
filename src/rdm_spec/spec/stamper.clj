(ns rdm-spec.spec.stamper
  "Hands out scenario ids from the ledger, never twice: every unstamped
  scenario gets the next id, and the ledger records the highest issued
  (dhf/spec/02-write-the-spec/stamper.feature); each spec stamps from its own
  ledger, with its own prefix (12-several-specs/stamper.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]))

(defn id-of [prefix n] (format "%s-%04d" prefix n))

(defn tag-text
  "Tags the scenario on 1-based line n of text with tag: on its tag line, else on a new line above it."
  [text n tag]
  (let [lines (str/split text #"\n" -1)
        i (dec n)
        above (when (pos? i) (nth lines (dec i)))]
    (str/join "\n"
              (if (and above (str/starts-with? (str/trim above) "@"))
                (assoc lines (dec i) (str/replace-first above #"^(\s*)" (str "$1" tag " ")))
                (let [indent (re-find #"^\s*" (nth lines i))]
                  (vec (concat (subvec lines 0 i) [(str indent tag)] (subvec lines i))))))))

(defn ledger-text [text n]
  (if (re-find #":issued\s+\d+" text)
    (str/replace-first text #":issued\s+\d+" (str ":issued " n))
    (str (project/edn-str {:issued n}) "\n")))

(defn stamp [run]
  (let [root (:root run)
        reading (parser/read-specs root (:cfg run))
        refusals (mapcat :refusals (:specs reading))]
    (if (seq refusals)
      {:findings (vec (for [r refusals] (project/finding (:file r) (:line r) (:rule r) (:message r))))
       :result {:stamp {:stamped [] :says "the spec could not be read: nothing was stamped"}}}
      (let [results
            (for [spec (:specs reading)]
              (let [lf (fs/path root (:ledger spec))
                    ltext (if (fs/exists? lf) (slurp (str lf)) "{:issued 0}\n")
                    issued (or (:issued (try (edn/read-string ltext) (catch Exception _ nil)))
                               (project/cannot-run! (str "the ledger " (:ledger spec) " cannot be read")))
                    todo (sort-by (juxt :file :line) (filter #(empty? (:ids %)) (:scenarios spec)))
                    assigned (map-indexed (fn [i sc] (assoc sc :new-id (id-of (:prefix spec) (+ issued i 1)))) todo)]
                (doseq [[file scs] (group-by :file assigned)]
                  (let [p (fs/path root file)]
                    ;; bottom-up, so earlier lines keep their numbers
                    (spit (str p) (reduce (fn [t sc] (tag-text t (:line sc) (str "@" (:new-id sc))))
                                          (slurp (str p)) (sort-by :line > scs)))))
                (when (seq assigned)
                  (spit (str lf) (ledger-text ltext (+ issued (count assigned)))))
                {:stamped (vec (for [sc assigned] {:id (:new-id sc) :file (:file sc) :title (:title sc)}))
                 :ledger (:ledger spec) :issued (+ issued (count assigned))}))]
        {:result {:stamp {:stamped (vec (mapcat :stamped results))
                          :ledgers (vec (for [r results] {:ledger (:ledger r) :issued (:issued r)}))}}}))))
