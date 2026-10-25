(ns rdm-spec.verification.release-test
  "Unit tests of the Release check's pure parts. They make no fixture on
  disk."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.verification.release :as release]))

(deftest every-scenario-not-verified-is-a-finding
  (let [fs (release/scenario-findings
            {:scenarios [{:id "spec-0001" :file "f" :line 3 :status :verified}
                         {:id "spec-0002" :file "f" :line 7 :status :failed}
                         {:id "spec-0003" :file "f" :line 9 :status :unverified}
                         {:id "spec-0004" :file "f" :line 11 :status :planned}]
             :unstamped [{:file "f" :line 13 :name "No id"}]})]
    (is (= ["scenario-failed" "scenario-unverified" "scenario-planned" "scenario-unstamped"] (map :rule fs)))
    (is (re-find #"spec-0002 is failed" (:message (first fs))))
    (is (= 7 (:line (first fs))))
    (is (not-any? command/warning? fs))))

(deftest gap-findings-are-relayed-unchanged
  (let [fs (release/clause-findings
            {:findings [(command/finding "c.txt" 2 "clause-unreferenced" "X:2 Two")
                        (command/warning "a.md" 4 "clause-unknown" "Y:9")]})]
    (is (= ["c.txt:2: clause-unreferenced: X:2 Two" "a.md:4: warning: clause-unknown: Y:9"]
           (map command/format-finding fs)))))
