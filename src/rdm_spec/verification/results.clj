(ns rdm-spec.verification.results
  "Reads a run's Allure results, whatever stack wrote them, naming what it
  cannot read (dhf/spec/05-verify-by-results/results.feature). A result
  counts by its status, its spec label and an outline row's parameters alone."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def statuses #{"passed" "failed" "broken" "skipped" "unknown"})

(defn label [r n] (some #(when (= n (get % "name")) (get % "value")) (get r "labels")))

(defn read-dir
  "Reads every <uuid>-result.json in dir; each file is named as display/name.
  {:results [{:file :spec :status :parameters :raw}] :unreadable [{:file :why}]}"
  [dir display]
  (let [files (sort (map str (fs/glob dir "*-result.json")))]
    (reduce
     (fn [acc f]
       (let [shown (str (str/replace (str display) #"/$" "") "/" (fs/file-name f))
             parsed (try {:ok (json/parse-string (slurp f))} (catch Exception e {:err (ex-message e)}))
             r (:ok parsed)]
         (cond
           (:err parsed) (update acc :unreadable conj {:file shown :why (str "not valid JSON: " (project/one-line (:err parsed)))})
           (not (map? r)) (update acc :unreadable conj {:file shown :why "not an Allure result: not a JSON object"})
           (not (statuses (get r "status")))
           (update acc :unreadable conj {:file shown :why (str "its status " (pr-str (get r "status"))
                                                               " is not one of Allure's: the status must be passed, failed, broken or skipped")})
           :else (update acc :results conj {:file shown :path f :spec (label r "spec") :status (get r "status")
                                            :parameters (get r "parameters") :raw r}))))
     {:results [] :unreadable []}
     files)))

(defn dir-problem
  "Why a results directory cannot be read, or nil."
  [root dir]
  (let [p (fs/path root dir)]
    (cond (not (fs/directory? p)) (str "the results directory " dir " does not exist")
          (empty? (fs/glob p "*-result.json")) (str "no test run wrote results there: " dir " holds no Allure result"))))
