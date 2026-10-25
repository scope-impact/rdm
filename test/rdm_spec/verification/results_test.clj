(ns rdm-spec.verification.results-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

(deftest a-result-is-read-whoever-wrote-it
  (is (= {:spec "spec-0001" :status "passed" :name "n"}
         (results/read-result "{\"name\":\"n\",\"status\":\"passed\",\"labels\":[{\"name\":\"language\",\"value\":\"javascript\"},{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")))
  (testing "no spec label verifies nothing"
    (is (nil? (:spec (results/read-result "{\"status\":\"passed\",\"labels\":[]}")))))
  (testing "a missing or unknown status does not pass"
    (is (= "unknown" (:status (results/read-result "{\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}"))))))

(deftest what-cannot-be-read-is-named
  (is (:why (results/read-result "{\"cut short")))
  (is (:why (results/read-result "[1,2]")))
  (is (:why (results/read-result "{\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"},{\"name\":\"spec\",\"value\":\"spec-0002\"}]}"))))

(deftest a-directory-is-read-or-refused
  (let [d (str (fs/create-temp-dir {:prefix "results-test-"}))]
    (try
      (testing "an empty directory cannot be read"
        (fs/create-dirs (fs/file d "r"))
        (is (thrown-with-msg? Exception #"no test run wrote results there" (results/read-dir {:root d} "r")))
        (fs/create-dirs (fs/file d "r"))
        (spit (str (fs/file d "r" "x-container.json")) "{}")
        (is (thrown-with-msg? Exception #"no test run wrote results there" (results/read-dir {:root d} "r"))))
      (testing "results and unreadable files"
        (spit (str (fs/file d "r" "a-result.json")) "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")
        (spit (str (fs/file d "r" "b-result.json")) "not json")
        (let [r (results/read-dir {:root d} "r/")]
          (is (= [{:spec "spec-0001" :status "passed" :name nil :file "r/a-result.json"}] (:results r)))
          (is (= ["r/b-result.json"] (map :file (:unreadable r))))
          (is (= ["result-unreadable"] (map :rule (results/unreadable-findings r))))))
      (testing "a missing directory cannot be read"
        (is (command/cannot-run? (try (results/read-dir {:root d} "missing") (catch Exception e e)))))
      (finally (fs/delete-tree d)))))
