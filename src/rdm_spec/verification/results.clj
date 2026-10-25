(ns rdm-spec.verification.results
  "Results reader (dhf/spec/verification/results.feature): reads a run's
  Allure results, whatever stack wrote them, naming what it cannot read.

  An Allure result is one `<uuid>-result.json` file in the results directory
  (dhf/spec/glossary.md); every other file there (containers, attachments,
  environment files) is not a result and is ignored. Only the JSON matters,
  never which runner wrote it: a result is read from its `status`, its `name`
  and its `spec` labels.

  A result read:
    {:file \"<dir>/<uuid>-result.json\"   ; under the directory as given
     :name \"..\" :status \"passed\" | \"failed\" | ... | \"unknown\"
     :ids [\"spec-0007\"]                  ; every spec label's value
     :spec \"spec-0007\" or nil}           ; the first, for display
  A result with no spec label names no scenario and verifies nothing; it is
  not unknown either.

  A file that cannot be read (not JSON, not an object, labels not a list) is
  {:file :reason}: named, never skipped."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [rdm-spec.kernel.command :as command]))

(def result-suffix "-result.json")

(defn result-file?
  "Whether the file named `f` is an Allure result."
  [f]
  (let [n (str (fs/file-name (str f)))]
    (and (> (count n) (count result-suffix))
         (.endsWith n result-suffix))))

(defn- label-values [labels k]
  (vec (keep #(when (and (map? %) (= k (get % "name"))) (str (get % "value"))) labels)))

(defn parse-result
  "Pure: the result in the file `file` holding `text`, or {:file :unreadable
  reason} when it cannot be read as an Allure result."
  [file text]
  (let [parsed (try {:ok (json/parse-string text)}
                    (catch Exception e {:error (str "not valid JSON: " (or (ex-message e) (str e)))}))
        x (:ok parsed)
        labels (when (map? x) (get x "labels" []))]
    (cond
      (:error parsed) {:file file :unreadable (:error parsed)}
      (nil? x) {:file file :unreadable "not valid JSON: the file is empty"}
      (not (map? x)) {:file file :unreadable "not an Allure result: the JSON is not an object"}
      (not (sequential? labels)) {:file file :unreadable "not an Allure result: its labels are not a list"}
      :else (let [ids (label-values labels "spec")
                  status (get x "status")]
              {:file file
               :name (some-> (get x "name") str)
               :status (if (string? status) status "unknown")
               :ids ids
               :spec (first ids)}))))

(defn read-dir
  "Reads every Allure result in `dir` (relative to the request's project
  root, or absolute): {:dir dir :results [result] :unreadable [{:file
  :reason}]}, both in file-name order. Cannot run when the directory does not
  exist or holds no result file: no test run wrote results there."
  [req dir]
  (let [abs (command/path req dir)]
    (when-not (fs/directory? abs)
      (command/cannot-run! (str "the results directory " dir " does not exist")))
    (let [files (->> (fs/list-dir abs)
                     (filter #(and (fs/regular-file? %) (result-file? %)))
                     (map #(str (fs/file-name %)))
                     sort)]
      (when (empty? files)
        (command/cannot-run! (str "the results directory " dir " holds no Allure result"
                                  " (no *" result-suffix "): no test run wrote results there")))
      (let [read (for [n files
                       :let [shown (str (fs/file dir n))
                             text (try {:ok (slurp (fs/file abs n))}
                                       (catch Exception e {:error (str "cannot be read: " (ex-message e))}))]]
                   (if (:error text)
                     {:file shown :unreadable (:error text)}
                     (parse-result shown (:ok text))))]
        {:dir dir
         :results (vec (remove :unreadable read))
         :unreadable (vec (for [r read :when (:unreadable r)] {:file (:file r) :reason (:unreadable r)}))}))))

(defn by-scenario
  "Pure: the results grouped by the stamped scenario ids `ids` they name,
  {:by-id {id [result]} :unknown [{:id :file}]}. A result counts toward every
  id its spec labels name; a label naming no stamped scenario is unknown; a
  result with no spec label counts toward nothing and is not unknown."
  [ids results]
  (reduce (fn [acc r]
            (reduce (fn [acc id]
                      (if (contains? ids id)
                        (update-in acc [:by-id id] (fnil conj []) r)
                        (update acc :unknown conj {:id id :file (:file r)})))
                    acc
                    (distinct (:ids r))))
          {:by-id {} :unknown []}
          results))
