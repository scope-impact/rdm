(ns rdm-spec.verification.results-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]))

(defn- cannot-run-message
  "The message `f` refuses with, as a task that cannot run, or nil."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (when (::command/cannot-run (ex-data e)) (ex-message e)))))

(deftest a-result-file-is-named-by-its-suffix
  (is (results/result-file? "0a1b-result.json"))
  (is (results/result-file? "/x/y/0a1b-result.json"))
  (doseq [f ["0a1b-container.json" "0a1b-attachment.txt" "environment.properties" "result.json.bak"]]
    (is (not (results/result-file? f)) f)))

(deftest parsing-one-result
  (testing "a result with a spec label names its scenario"
    (let [r (results/parse-result "d/a-result.json"
                                  "{\"name\":\"n\",\"status\":\"passed\",\"labels\":[{\"name\":\"framework\",\"value\":\"x\"},{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")]
      (is (= {:file "d/a-result.json" :name "n" :status "passed" :ids ["spec-0001"] :spec "spec-0001"}
             (select-keys r [:file :name :status :ids :spec])))))
  (testing "a result with no spec label names none"
    (let [r (results/parse-result "a-result.json" "{\"status\":\"failed\",\"labels\":[]}")]
      (is (= [] (:ids r)))
      (is (nil? (:spec r)))
      (is (nil? (:unreadable r)))))
  (testing "a result with no status did not pass"
    (is (= "unknown" (:status (results/parse-result "a-result.json" "{\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")))))
  (testing "what cannot be read says why"
    (doseq [text ["{\"status\": " "" "[1,2]" "\"passed\"" "{\"status\":\"passed\",\"labels\":{\"spec\":\"x\"}}"]]
      (let [r (results/parse-result "a-result.json" text)]
        (is (string? (:unreadable r)) (pr-str text))
        (is (= "a-result.json" (:file r)))))))

(deftest by-scenario-groups-results-by-id
  (let [rs [{:file "1" :ids ["spec-0001"] :status "passed"}
            {:file "2" :ids ["spec-0001"] :status "failed"}
            {:file "3" :ids ["spec-9999"] :status "passed"}
            {:file "4" :ids [] :status "passed"}]
        {:keys [by-id unknown]} (results/by-scenario #{"spec-0001" "spec-0002"} rs)]
    (is (= ["1" "2"] (map :file (get by-id "spec-0001"))))
    (is (nil? (get by-id "spec-0002")))
    (is (= [{:id "spec-9999" :file "3"}] unknown) "a result with no spec label is not unknown")))

(deftest reading-a-directory
  (let [root (str (fs/create-temp-dir))
        dir "dhf/allure-results"
        abs (fs/file root dir)]
    (testing "a directory that does not exist cannot be read"
      (is (str/includes? (cannot-run-message #(results/read-dir {:root root} dir)) "does not exist")))
    (fs/create-dirs abs)
    (spit (str (fs/file abs "c-container.json")) "{}")
    (testing "a directory with no result in it is refused"
      (let [m (cannot-run-message #(results/read-dir {:root root} dir))]
        (is (str/includes? m "no test run wrote results"))
        (is (str/includes? m dir))))
    (spit (str (fs/file abs "b-result.json")) "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0002\"}]}")
    (spit (str (fs/file abs "a-result.json")) "{\"status\":\"passed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}")
    (spit (str (fs/file abs "z-result.json")) "not json")
    (let [read (results/read-dir {:root root} dir)]
      (is (= dir (:dir read)))
      (testing "results in file order, named under the directory as given"
        (is (= [(str dir "/a-result.json") (str dir "/b-result.json")] (map :file (:results read)))))
      (testing "an unreadable file is named, never skipped"
        (is (= [(str dir "/z-result.json")] (map :file (:unreadable read))))
        (is (string? (:reason (first (:unreadable read)))))))))
