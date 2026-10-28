(ns rdm-spec.compliance.audit-test
  "One test per rule the Audit copy enforces (10-regulated-profile/audit.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.audit :as audit]))

(deftest only-a-releasable-record-has-an-audit-copy
  (is (audit/out-problem (System/getProperty "user.dir"))))

(deftest every-document-has-an-auditors-copy-and-an-engineers-copy
  (is (= "We plan." (audit/engineer "We plan [[STD:5.1]].")))
  (is (= "We plan and test." (audit/engineer "We plan [[STD:5.1]] and test [[STD:5.2]].")))
  (is (= "We plan\n the work." (audit/engineer "We plan [[STD:\n5.1]] the work.")))
  (is (= "We plan `[[STD:5.1]]`." (audit/engineer "We plan `[[STD:5.1]]`.")))
  (is (= "title: Development plan" (audit/engineer "title: Development plan [[STD:5.1]]"))))

(deftest the-audit-copy-carries-the-evidence-it-rests-on
  (is (= [{:path "a" :sha256 "x"} {:path "b" :sha256 "y"}] (audit/sorted-files [{:path "b" :sha256 "y"} {:path "a" :sha256 "x"}]))))
