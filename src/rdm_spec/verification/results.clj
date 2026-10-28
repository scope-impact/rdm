(ns rdm-spec.verification.results
  "Component Results reader (verification context): reads a run's Allure
  results, whatever stack wrote them, for what a status depends on (the
  status, the spec label, an outline row's parameters), keeping the rest,
  and naming every file it cannot read."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def statuses #{"passed" "failed" "broken" "skipped" "unknown"})

(defn label [r n] (some #(when (= n (get % "name")) (get % "value")) (get r "labels")))

(defn read-result
  "One result file: {:file :status :spec :parameters :raw} or {:file :unreadable why}."
  [abs-file shown]
  (try
    (let [r (json/parse-string (slurp abs-file))]
      (cond
        (not (map? r)) {:file shown :unreadable "it is not an Allure result: not a JSON object"}
        (not (statuses (get r "status")))
        {:file shown :unreadable (str "its status is " (pr-str (get r "status")) ": the status must be passed, failed, broken or skipped")}
        (> (count (filter #(= "spec" (get % "name")) (get r "labels"))) 1)
        {:file shown :unreadable "it carries more than one spec label: a result names exactly one scenario"}
        :else {:file shown :status (keyword (get r "status")) :spec (label r "spec")
               :parameters (into {} (for [p (get r "parameters") :when (map? p)] [(str (get p "name")) (str (get p "value"))]))
               :raw r}))
    (catch Exception e
      {:file shown :unreadable (str "it is not valid JSON: " (project/one-line (ex-message e)))})))

(defn read-dir
  "Every result of the directory {:dir :abs}; a directory that is missing or
  holds no result cannot be read (exit 2). {:results [...] :unreadable [...]}."
  [{:keys [dir abs]}]
  (when-not (fs/directory? abs)
    (project/cannot-run! (str "the results directory " dir " does not exist")))
  (let [files (sort (map str (fs/glob abs "*-result.json")))]
    (when (empty? files)
      (project/cannot-run! (str "the results directory " dir " holds no Allure result: no test run wrote results there")))
    (let [rs (for [f files] (read-result f (str (str/replace dir #"/+$" "") "/" (fs/file-name f))))]
      {:results (vec (remove :unreadable rs))
       :unreadable (vec (filter :unreadable rs))})))

(defn unreadable-findings [unreadable]
  (for [{:keys [file unreadable]} unreadable]
    (project/finding file "result-unreadable" (str "the result is unreadable: " unreadable))))
