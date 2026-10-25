(ns rdm-spec.verification.results
  "The Results reader of the verification context
  (dhf/spec/verification/results.feature): reads a run's Allure results,
  whatever stack wrote them, naming what it cannot read.

  A result is one `*-result.json` file directly in the results directory
  (dhf/spec/glossary.md, \"Allure result\"); every other file there
  (containers, attachments) is not a result. Only `status` and the `spec`
  label count: a result is read the same whoever wrote it.

  `read-dir` returns

    {:dir \"DIR\"                        ; as given
     :results [{:file :spec :status :name}]  ; :spec nil for no spec label
     :unreadable [{:file :why}]}

  where :file is the result's path as DIR names it (DIR/name), and :status a
  string: passed, failed, broken, skipped or unknown (a result naming no
  status is unknown: it does not pass). A file that is not valid JSON, not
  a JSON object, or labelled with more than one spec id is unreadable: it
  verifies nothing and is named, never skipped.

  A results directory that does not exist, or holds no Allure result, cannot
  be read (exit 2): no test run wrote results there."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def statuses #{"passed" "failed" "broken" "skipped" "unknown"})

(defn- strip-slash [s] (let [t (str/replace (str s) #"/+$" "")] (if (str/blank? t) (str s) t)))

(defn result-files
  "The Allure result files directly in the directory `d`, sorted by name."
  [d]
  (vec (sort-by str (for [f (fs/list-dir d)
                          :when (and (fs/regular-file? f) (str/ends-with? (str (fs/file-name f)) "-result.json"))]
                      f))))

(defn- label-values [m nm]
  (vec (for [l (get m "labels") :when (and (map? l) (= nm (get l "name")))] (str (get l "value")))))

(defn read-result
  "One result's text, read: {:spec :status :name} or {:why} when unreadable."
  [text]
  (let [m (try (json/parse-string text) (catch Exception e {::error (ex-message e)}))]
    (cond
      (and (map? m) (::error m)) {:why (str "it is not valid JSON (" (first (str/split-lines (str (::error m)))) ")")}
      (not (map? m)) {:why "it is not a JSON object"}
      :else
      (let [specs (distinct (label-values m "spec"))
            st (get m "status")]
        (if (> (count specs) 1)
          {:why (str "it is labelled with more than one scenario (" (str/join ", " specs) "): a result verifies one")}
          {:spec (first specs)
           :status (if (contains? statuses st) st "unknown")
           :name (get m "name")})))))

(defn read-dir
  "The results in the directory `dir` (as given: relative to the project
  root, or absolute). Cannot run when it does not exist or holds no Allure
  result."
  [req dir]
  (let [shown (strip-slash dir)
        d (command/path req dir)]
    (when-not (fs/directory? d)
      (command/cannot-run! (str "the results directory " shown " does not exist")))
    (let [files (result-files d)]
      (when (empty? files)
        (command/cannot-run! (str "the results directory " shown " holds no Allure result (no *-result.json):"
                                  " no test run wrote results there")))
      (reduce (fn [acc f]
                (let [file (str shown "/" (fs/file-name f))
                      r (read-result (try (slurp (str f)) (catch Exception e (str "\u0000" (ex-message e)))))]
                  (if (:why r)
                    (update acc :unreadable conj {:file file :why (:why r)})
                    (update acc :results conj (assoc r :file file)))))
              {:dir shown :results [] :unreadable []}
              files))))

(defn unreadable-findings
  "One finding per unreadable result file."
  [{:keys [unreadable]}]
  (vec (for [{:keys [file why]} unreadable]
         (command/finding file "result-unreadable"
                          (str "this Allure result is unreadable: " why "; it verifies nothing")))))
