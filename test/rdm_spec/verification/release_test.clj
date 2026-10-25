(ns rdm-spec.verification.release-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.release :as release]))

(deftest what-blocks-a-release
  (let [report {:scenarios [{:id "spec-0001" :name "One" :file "f" :line 3 :status :verified}
                            {:id "spec-0002" :name "Two" :file "f" :line 7 :status :failed}
                            {:id "spec-0003" :name "Three" :file "f" :line 9 :status :planned}]
                :unstamped [{:file "f" :line 12 :name "Four"}]}
        fs* (release/scenario-findings report)]
    (is (= [["f" 7 "scenario-not-verified"] ["f" 9 "scenario-not-verified"] ["f" 12 "scenario-unstamped"]]
           (map (juxt :file :line :rule) fs*)))
    (is (re-find #"spec-0002 .*failed" (:message (first fs*)))))
  (is (= [["std.txt" 2 "clause-unreferenced" "STD:5.2 is named by no audit tag"]]
         (map (juxt :file :line :rule :message)
              (release/gap-findings {:findings [{:file "std.txt" :line 2 :rule "clause-unreferenced"
                                                 :message "STD:5.2 is named by no audit tag"}
                                                {:file "d.md" :line 1 :rule "document-unidentified" :message "x"}]})))))
