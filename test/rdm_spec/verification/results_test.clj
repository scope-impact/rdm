(ns rdm-spec.verification.results-test
  "Unit tests of the Results reader; each fixture directory is removed."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.verification.results :as results]))

(defn- with-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "results-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(defn- write! [d n m] (spit (str (fs/file d (str n "-result.json"))) (if (string? m) m (json/generate-string m))))

(deftest a-rows-parameters-are-read-by-name
  (with-dir
    (fn [d]
      (write! d "a" {:status "passed" :labels [{:name "spec" :value "spec-0013"}]
                     :parameters [{:name "written" :value "plan"} {:name "kept" :value "3"}]})
      (is (= {"written" "plan" "kept" "3"} (:parameters (first (:results (results/read-results {:root "/"} d)))))))))

(deftest an-unreadable-file-is-named-in-one-line
  (with-dir
    (fn [d]
      (write! d "b" "{\"status\":\n")
      (write! d "c" {:status "passed" :labels [{:name "spec" :value "spec-0001"} {:name "spec" :value "spec-0002"}]})
      (write! d "e" "[1, 2]")
      (let [r (results/read-results {:root "/"} d)
            fs* (results/unreadable-findings r)]
        (is (= 3 (count fs*)))
        (is (every? #(not (str/includes? (:message %) "\n")) fs*))
        (is (every? #(= "result-unreadable" (:rule %)) fs*))))))

(deftest every-element-is-kept-when-asked
  (with-dir
    (fn [d]
      (write! d "a" {:status "passed" :labels [{:name "spec" :value "spec-0001"}] :steps [{:name "Given x"}]})
      (is (= [{:name "Given x"}] (get-in (results/read-results {:root "/"} d {:keep? true}) [:results 0 :result :steps]))))))
