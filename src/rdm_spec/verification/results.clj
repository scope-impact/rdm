(ns rdm-spec.verification.results
  "The Results reader of the verification context
  (dhf/spec/verification/results.feature): reads a run's Allure results,
  whatever stack wrote them, naming what it cannot read.

  A result is read for what a status depends on, its `status`, its `spec`
  label and an outline row's `parameters`, and nothing else decides
  anything (spec-0312). Each `*-result.json` of the directory is read; any
  other file (attachments, containers) is left alone.

    {:file \"DIR/NAME-result.json\"   ; DIR as given
     :spec \"spec-0012\" or nil       ; no spec label: counts toward nothing
     :status \"passed\"               ; passed failed broken skipped unknown
     :parameters {name value} or nil}

  Unreadable (named, never skipped; finding result-unreadable, one line):
  not JSON, not a JSON object, a status outside Allure's, labels that are
  not a list, more than one spec label. A directory that does not exist or
  holds no result cannot be read (cannot-run): no test run wrote results
  there.

  API: read-results, unreadable-findings."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def statuses
  "Allure's statuses: unknown is Allure's own, and never a pass."
  #{"passed" "failed" "broken" "skipped" "unknown"})

(defn- one-line [s] (str/trim (str/replace (str s) #"\s*\r?\n\s*" " ")))

(defn- read-one [file text keep?]
  (let [parsed (try {:ok (json/parse-string text)} (catch Exception e {:error (ex-message e)}))
        m (:ok parsed)
        labels (when (map? m) (get m "labels"))
        specs (when (sequential? labels)
                (vec (keep #(when (and (map? %) (= "spec" (get % "name"))) (get % "value")) labels)))
        params (when (map? m) (get m "parameters"))]
    (cond
      (:error parsed) {:unreadable {:file file :why (str "it is not valid JSON: " (one-line (:error parsed)))}}
      (not (map? m)) {:unreadable {:file file :why "it is not a JSON object, an Allure result"}}
      (not (contains? statuses (get m "status")))
      {:unreadable {:file file :why (str "its status is " (pr-str (get m "status"))
                                         ": the status must be passed, failed, broken or skipped")}}
      (and (some? labels) (not (sequential? labels)))
      {:unreadable {:file file :why "its labels are not a list of {name value}"}}
      (< 1 (count specs))
      {:unreadable {:file file :why (str "it carries " (count specs) " spec labels, " (str/join ", " specs)
                                         ": a result names exactly one scenario")}}
      :else
      {:result (cond-> {:file file
                        :spec (some-> (first specs) str)
                        :status (get m "status")
                        :parameters (when (and (sequential? params) (seq params))
                                      (into {} (for [p params :when (map? p)]
                                                 [(str (get p "name")) (str (get p "value"))])))}
                 keep? (assoc :result (json/parse-string text true)))})))

(defn read-results
  "The results of the directory `dir` (relative to the project of `req`, or
  absolute): {:dir :results :unreadable [{:file :why}]}. `opts` :keep? also
  keeps each whole result, read with keyword keys, as :result."
  ([req dir] (read-results req dir {}))
  ([req dir {:keys [keep?]}]
   (let [d (command/path req dir)]
     (when-not (fs/directory? d)
       (command/cannot-run! (str "the results directory " dir " does not exist: no test run wrote results there")))
     (let [files (sort (map #(str (fs/file-name %)) (fs/glob d "*-result.json")))]
       (when (empty? files)
         (command/cannot-run! (str "the results directory " dir " holds no Allure result: no test run wrote results there")))
       (let [read (for [n files]
                    (read-one (str (str/replace (str dir) #"/+$" "") "/" n) (slurp (str (fs/file d n))) keep?))]
         {:dir dir
          :results (vec (keep :result read))
          :unreadable (vec (keep :unreadable read))})))))

(defn unreadable-findings
  "A finding per file of `read` that could not be read, each one line."
  [read]
  (vec (for [{:keys [file why]} (:unreadable read)]
         (command/finding file "result-unreadable" (one-line (str "the result is unreadable, so it counts toward nothing: " why))))))
