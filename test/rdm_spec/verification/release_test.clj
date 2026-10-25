(ns rdm-spec.verification.release-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.verification.release :as release]))

(deftest refuses-every-scenario-not-verified
  (let [report {:scenarios [{:id "spec-0001" :status :verified :file "f" :line 1}
                            {:id "spec-0002" :status :failed :file "f" :line 5}
                            {:id "spec-0003" :status :unverified :file "f" :line 9}
                            {:id "spec-0004" :status :planned :file "f" :line 13}]
                :unstamped [{:file "g" :line 3 :name "x"}]}]
    (is (= [["spec-0002" :failed] ["spec-0003" :unverified] ["spec-0004" :planned] [nil :unstamped]]
           (map (juxt :id :status) (release/refusals report))))))

(deftest passes-when-every-scenario-is-verified
  (is (empty? (release/refusals {:scenarios [{:id "spec-0001" :status :verified}] :unstamped []}))))
