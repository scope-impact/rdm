(ns rdm-spec.verification.results-test
  "One test per rule the Results reader enforces (05-verify-by-results/results.feature)."
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [rdm-spec.verification.results :as results]))

(defn- with-dir [files f]
  (let [d (fs/create-temp-dir {:dir (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir"))})]
    (try (doseq [[n t] files] (spit (str (fs/path d n)) t)) (f (str d))
         (finally (fs/delete-tree d)))))

(deftest allure-results-are-the-oracle-whoever-wrote-them
  (with-dir {"a-result.json" "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}"
             "b-result.json" "not json"}
    (fn [d]
      (let [r (results/read-dir d d)]
        (is (= ["spec-0001"] (map :spec (:results r))))
        (is (= 1 (count (:unreadable r))))))))

(deftest a-result-with-no-spec-label-verifies-nothing
  (with-dir {"a-result.json" "{\"status\":\"passed\",\"labels\":[]}"}
    (fn [d] (is (= [nil] (map :spec (:results (results/read-dir d d))))))))

(deftest what-decides-a-status-and-what-is-only-kept
  (with-dir {"a-result.json" "{\"status\":\"green\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0012\"}]}"}
    (fn [d] (is (= 1 (count (:unreadable (results/read-dir d d))))))))
