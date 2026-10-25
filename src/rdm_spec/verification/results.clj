(ns rdm-spec.verification.results
  "Results reader (dhf/spec/verification/results.feature): reads a run's
  Allure results, whatever stack wrote them, naming what it cannot read.

  An Allure result is a file named <anything>-result.json, a JSON object whose
  `status` is passed, failed, broken, skipped or unknown and whose `labels`
  are name/value pairs. A result verifies toward the scenarios its `spec`
  labels name; a result with no spec label (a unit test's, say) counts toward
  nothing. Nothing else in the file matters, so the stack that wrote it does
  not either."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def statuses #{"passed" "failed" "broken" "skipped" "unknown"})

(defn result-files
  "Every Allure result file directly in `dir`, in name order."
  [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/glob dir "*-result.json"))))
    []))

(defn- label-pairs? [labels]
  (and (sequential? labels)
       (every? #(and (map? %) (string? (get % "name"))) labels)))

(defn interpret
  "One result file's parsed JSON as {:ids [..] :status \"..\" :name \"..\"},
  or {:unreadable \"why\"} when it is no Allure result."
  [data]
  (cond
    (not (map? data)) {:unreadable "it is not a JSON object"}
    (and (contains? data "labels") (not (label-pairs? (get data "labels"))))
    {:unreadable "its labels are not a list of name/value pairs"}
    :else
    (let [labels (get data "labels" [])
          ids (vec (distinct (keep #(when (= "spec" (get % "name"))
                                      (some-> (get % "value") str str/trim not-empty))
                                   labels)))
          status (get data "status")]
      {:ids ids
       ;; A status missing or outside Allure's five is unknown, which never verifies.
       :status (if (statuses status) status "unknown")
       :name (some-> (or (get data "name") (get data "fullName")) str)})))

(defn read-file
  "Reads one result file: {:file :ids :status :name} or {:file :unreadable}."
  [req f]
  (let [file (command/rel req f)]
    (try
      (assoc (interpret (json/parse-string (slurp f))) :file file)
      (catch Exception e
        {:file file :unreadable (str "it is not valid JSON: " (or (ex-message e) (str e)))}))))

(defn read-dir
  "Reads every Allure result in the results directory `dir` (relative to the
  request's root unless absolute). Cannot run when the directory holds no
  result. Returns {:dir dir :results [{:file :ids :status :name}]
                   :unreadable [{:file :reason}]}, both in file name order."
  [req dir]
  (let [d (command/path req dir)
        files (result-files d)]
    (when-not (fs/directory? d)
      (command/cannot-run! (str "the results directory " dir " does not exist")))
    (when (empty? files)
      (command/cannot-run! (str "no test run wrote results there: the results directory " dir
                                " holds no Allure result (*-result.json)")))
    (let [read (mapv #(read-file req %) files)]
      {:dir dir
       :results (vec (remove :unreadable read))
       :unreadable (vec (for [r read :when (:unreadable r)]
                          {:file (:file r) :reason (:unreadable r)}))})))

(defn by-scenario
  "Sorts the results among the scenario ids `known` (a set): {:by-id {id
  [result ..]} :unknown [{:id :file}]}. A result with no spec label is in
  neither: it counts toward nothing."
  [known results]
  (reduce (fn [acc r]
            (reduce (fn [acc id]
                      (if (contains? known id)
                        (update-in acc [:by-id id] (fnil conj []) r)
                        (update acc :unknown conj {:id id :file (:file r)})))
                    acc (:ids r)))
          {:by-id {} :unknown []}
          results))
