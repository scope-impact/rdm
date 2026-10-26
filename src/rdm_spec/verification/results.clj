(ns rdm-spec.verification.results
  "The Results reader of the verification context
  (dhf/spec/verification/results.feature): reads a run's Allure results,
  whatever stack wrote them, naming what it cannot read.

  A result is a `*-result.json` file of the directory, in Allure's own
  model (dhf/spec/glossary.md, \"Allure result\"); every other file
  (attachments, containers) is not a result. What decides a status is read,
  and only that: `status`, the `spec` label (exactly one; none verifies
  nothing) and an outline row's `parameters`. Everything else is kept in
  the file for the report.

  A file that is not a JSON object, whose status is not one of Allure's
  (passed, failed, broken or skipped; unknown, Allure's own word for a
  status nobody knows, is read as not passed), or that names two scenarios
  is unreadable: a finding (result-unreadable), never skipped.

  Cannot run (exit 2): a directory that does not exist, or that holds no
  result (no test run wrote results there).

  `read-results` gives {:dir DIR :results [{:file :spec :status
  :parameters}] :unreadable [{:file :why}]}, each :file named as DIR names
  it (DIR/name)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def statuses
  "The statuses a result may carry."
  #{"passed" "failed" "broken" "skipped" "unknown"})

(defn- read-one [file text]
  (let [m (try (json/parse-string text) (catch Exception e {::error (ex-message e)}))]
    (cond
      (::error m) {:unreadable {:file file :why (str "it is not valid JSON: " (::error m))}}
      (not (map? m)) {:unreadable {:file file :why "it is not a JSON object, as an Allure result is"}}
      :else
      (let [st (get m "status")
            specs (for [l (get m "labels") :when (and (map? l) (= "spec" (get l "name")))] (str (get l "value")))
            ps (get m "parameters")]
        (cond
          (not (statuses st))
          {:unreadable {:file file
                        :why (str "its status " (pr-str st) " is not one of Allure's: the status must be passed, failed,"
                                  " broken or skipped (unknown is read as not passed)")}}
          (< 1 (count specs))
          {:unreadable {:file file :why (str "it carries " (count specs) " spec labels: a result verifies exactly one scenario")}}
          :else
          {:result {:file file :spec (first specs) :status st
                    :parameters (when (and (sequential? ps) (seq ps))
                                  (into {} (for [p ps :when (map? p)] [(str (get p "name")) (str (get p "value"))])))}})))))

(defn read-results
  "The results in the directory `dir` (as given; relative to the project of
  `req`): see the namespace doc."
  [req dir]
  (let [d (command/path req dir)
        shown (str/replace (str dir) #"/+$" "")]
    (when-not (fs/directory? d)
      (command/cannot-run! (str "the results directory " shown " does not exist: no test run wrote results there")))
    (let [files (sort (map #(str (fs/file-name %)) (filter fs/regular-file? (fs/glob d "*-result.json"))))
          _ (when (empty? files)
              (command/cannot-run! (str "the results directory " shown " holds no Allure result: no test run wrote results there")))
          read (for [n files] (read-one (str shown "/" n) (slurp (str (fs/file d n)))))]
      {:dir shown
       :results (vec (keep :result read))
       :unreadable (vec (keep :unreadable read))})))

(defn unreadable-findings
  "A finding per unreadable result file of `read`, one line each."
  [read]
  (vec (for [{:keys [file why]} (:unreadable read)]
         (command/finding file "result-unreadable" (str "the result file is unreadable, so it counts toward nothing: " why)))))
