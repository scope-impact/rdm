(ns rdm-spec.verification.results
  "The results reader (dhf/spec/verification/results.feature): reads a run's
  Allure results, whatever stack wrote them, naming each file it cannot read.
  A result counts by its status, its spec label and an outline row's
  parameters; every other element is kept for the report."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

(def statuses #{"passed" "failed" "broken" "skipped" "unknown"})

(defn- labels [j n]
  (keep #(when (and (map? %) (= n (get % "name"))) (get % "value")) (get j "labels")))

(defn parse-result
  "One result's text: {:spec :status :parameters :json}, or {:unreadable why}."
  [text]
  (let [j (try (json/parse-string text) (catch Exception e {::error (ex-message e)}))]
    (cond
      (::error j) {:unreadable (str "not valid JSON: " (::error j))}
      (not (map? j)) {:unreadable "not a JSON object"}
      (not (statuses (get j "status")))
      {:unreadable (str "its status " (pr-str (get j "status"))
                        " is not one of Allure's: the status must be passed, failed, broken or skipped (or unknown)")}
      (> (count (labels j "spec")) 1)
      {:unreadable (str "it carries " (count (labels j "spec")) " spec labels: a result names exactly one scenario")}
      :else {:spec (first (labels j "spec"))
             :status (get j "status")
             :parameters (vec (filter map? (get j "parameters")))
             :json j})))

(defn read-dir
  "Reads every result in a results directory. `named` is the directory as
  the task was given it, so each file is named DIR/name. Cannot run when the
  directory is missing or holds no result."
  [root named]
  (let [abs (fs/absolutize (fs/path root named))]
    (when-not (fs/directory? abs)
      (cmd/cannot-run! (str "the results directory " named " does not exist")))
    (let [files (sort (map str (fs/glob abs "*-result.json")))]
      (when (empty? files)
        (cmd/cannot-run! (str "no test run wrote results there: the results directory " named " holds no Allure result")))
      (let [read (for [f files]
                   (assoc (parse-result (slurp f)) :file (str (str/replace named #"/$" "") "/" (fs/file-name f))))]
        {:results (vec (remove :unreadable read))
         :unreadable (vec (filter :unreadable read))}))))

(defn unreadable-findings [unreadable]
  (vec (for [u unreadable]
         (cmd/finding (:file u) nil "result-unreadable" (str "the result cannot be read: " (:unreadable u))))))
