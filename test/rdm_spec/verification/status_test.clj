(ns rdm-spec.verification.status-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(defn- with-dir [f]
  (let [d (str (fs/create-temp-dir {:prefix "status-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(defn- write! [d n m] (spit (str (fs/file d (str n "-result.json"))) (if (string? m) m (json/generate-string m))))

(deftest the-reader-names-what-it-cannot-read
  (with-dir
    (fn [d]
      (write! d "a" {:status "passed" :labels [{:name "spec" :value "spec-0001"}]})
      (write! d "b" "{not json")
      (write! d "c" {:status "green" :labels [{:name "spec" :value "spec-0001"}]})
      (write! d "e" {:status "unknown" :labels [{:name "spec" :value "spec-0002"}]})
      (spit (str (fs/file d "x-attachment.txt")) "ignored")
      (let [r (results/read-results {:root "/"} d)]
        (is (= [{:file (str d "/a-result.json") :spec "spec-0001" :status "passed" :parameters nil}
                {:file (str d "/e-result.json") :spec "spec-0002" :status "unknown" :parameters nil}]
               (:results r)))
        (is (= [(str d "/b-result.json") (str d "/c-result.json")] (map :file (:unreadable r))))
        (is (re-find #"passed, failed, broken or skipped" (:why (second (:unreadable r)))))
        (is (= 2 (count (results/unreadable-findings r))))))))

(deftest an-empty-or-missing-directory-cannot-be-read
  (with-dir
    (fn [d]
      (is (re-find #"no test run wrote results there"
                   (ex-message (try (results/read-results {:root "/"} d) (catch Exception e e)))))
      (is (command/cannot-run? (try (results/read-results {:root "/"} (str d "/none")) (catch Exception e e)))))))

(def scenarios
  [{:id "spec-0001" :title "A"} {:id "spec-0002" :title "B"} {:id "spec-0003" :title "C" :wip? true}
   {:id "spec-0004" :title "D" :outline? true
    :rows [{:line 9 :values {"n" "1"}} {:line 10 :values {"n" "2"}}]}
   {:id nil :title "unstamped"}])

(deftest statuses-are-derived-from-results
  (let [rs [{:spec "spec-0001" :status "passed"} {:spec "spec-0002" :status "passed"} {:spec "spec-0002" :status "skipped"}
            {:spec "spec-0003" :status "passed"} {:spec "spec-0004" :status "passed" :parameters {"n" "1"}}
            {:spec "spec-0099" :status "passed" :file "x"} {:spec nil :status "failed"}]
        d (status/derive-statuses scenarios rs)]
    (is (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :planned "spec-0004" :unverified} (:statuses d)))
    (is (= [{:file "x" :spec "spec-0099"}] (:unknown-results d)))
    (testing "an outline is verified only when every row passed"
      (is (= :verified (get-in (status/derive-statuses scenarios
                                                       [{:spec "spec-0004" :status "passed" :parameters {"n" "1"}}
                                                        {:spec "spec-0004" :status "passed" :parameters {"n" "2"}}])
                               [:statuses "spec-0004"])))
      (is (= :unverified (get-in (status/derive-statuses scenarios (repeat 3 {:spec "spec-0004" :status "passed"}))
                                 [:statuses "spec-0004"]))))
    (is (= {:verified 1 :failed 1 :unverified 1 :planned 1} (status/counts (:statuses d))))))
