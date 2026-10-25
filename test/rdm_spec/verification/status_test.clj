(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.status :as status]))

(defn- r [id st] {:spec id :status st :file (str id "-" st)})

(deftest a-status-is-derived-from-the-results
  (let [scs [{:id "spec-0001"} {:id "spec-0002"} {:id "spec-0003"} {:id "spec-0004" :wip? true} {:id nil :title "unstamped"}]
        d (status/derive-statuses scs {:results [(r "spec-0001" "passed") (r "spec-0002" "passed") (r "spec-0002" "skipped")
                                                 (r "spec-0004" "passed") (r "spec-9999" "passed") (r nil "failed")]})]
    (is (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified "spec-0004" :planned} (:statuses d)))
    (is (= [{:file "spec-9999-passed" :spec "spec-9999"}] (:unknown-results d)))
    (is (= {:verified 1 :failed 1 :unverified 1 :planned 1} (status/counts (:statuses d))))))

(deftest any-status-but-passed-does-not-verify
  (doseq [st ["failed" "broken" "skipped" "unknown"]]
    (is (= :failed (status/status-of {:id "spec-0007"} [(r "spec-0007" st)])))))

(deftest a-scenario-is-counted-once
  (testing "an id carried twice is one scenario"
    (is (= 1 (count (status/stamped [{:id "spec-0001"} {:id "spec-0001"}]))))))
