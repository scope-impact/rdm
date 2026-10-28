(ns rdm-spec.verification.release-test
  "One test per rule the Release check enforces (05-verify-by-results/release.feature, 10, 12 and 13)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.release :as release]))

(deftest a-release-needs-every-scenario-verified
  (is (= ["release-planned" "release-failed" "release-unverified"]
         (map :rule (release/refusals [{:id "a" :file "f" :line 1} {:id "b" :file "f" :line 2} {:id "c" :file "f" :line 3} {:id "d" :file "f" :line 4}]
                                      {"a" :planned "b" :failed "c" :unverified "d" :verified})))))

(deftest under-the-regulated-profile-a-release-needs-every-clause-named
  (is (= 1 (count (release/gap-refusals [{:file "c.txt" :line 1 :rule "clause-unreferenced" :message "x"} {:file "d.md" :line 1 :rule "clause-unknown" :message "x" :warning? true}])))))

(deftest a-release-reads-every-spec-or-the-one-named-and-can-be-asked-of-one-slice
  (is (= ["release-unverified"] (map :rule (release/refusals [{:id "b" :file "f" :line 2 :slice "11-x"}] {"b" :unverified})))))
