(ns rdm-spec.verification.release-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.release :as release]))

(deftest every-scenario-not-verified-is-a-finding
  (let [scs [{:id "spec-0001" :title "A" :file "f" :line 3} {:id "spec-0002" :title "B" :file "f" :line 6}
             {:id "spec-0003" :title "C" :file "f" :line 9} {:id "spec-0004" :title "D" :file "f" :line 12}
             {:id nil :title "E" :file "f" :line 15}]
        fs (release/scenario-findings scs {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified
                                           "spec-0004" :planned})]
    (is (= #{"release-unstamped" "release-failed" "release-unverified" "release-planned"} (set (map :rule fs))))
    (is (re-find #"spec-0002 .*failed" (:message (first (filter #(= "release-failed" (:rule %)) fs)))))
    (is (not-any? #(re-find #"spec-0001" (:message %)) fs))))
