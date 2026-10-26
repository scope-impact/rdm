(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.status :as status]))

(defn- sc [id & {:as more}] (merge {:id id :ids [id] :title id :file "a.feature" :line 1} more))

(defn- r [id st & [params]] (cond-> {:spec id :status st :name (str "a test of " id)} params (assoc :parameters params)))

(deftest a-status-is-derived-from-the-results
  (let [scs [(sc "spec-0001") (sc "spec-0002") (sc "spec-0003") (sc "spec-0004" :wip? true)]]
    (is (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified "spec-0004" :planned}
           (status/statuses-of scs [(r "spec-0001" "passed") (r "spec-0002" "passed") (r "spec-0002" "skipped")
                                    (r "spec-0004" "passed")])))))

(deftest any-status-but-passed-does-not-verify
  (doseq [st ["failed" "broken" "skipped" "unknown"]]
    (is (= {"spec-0007" :failed} (status/statuses-of [(sc "spec-0007")] [(r "spec-0007" st)])))))

(deftest an-outline-is-verified-by-every-row
  (let [o (sc "spec-0001" :outline? true :rows [{:line 10 :values {"n" "1"}} {:line 11 :values {"n" "2"}}])
        p (fn [n] [{:name "n" :value n}])]
    (is (= :verified (get (status/statuses-of [o] [(r "spec-0001" "passed" (p "1")) (r "spec-0001" "passed" (p "2"))]) "spec-0001")))
    (is (= :unverified (get (status/statuses-of [o] [(r "spec-0001" "passed" (p "1")) (r "spec-0001" "passed" (p "1"))]) "spec-0001")))
    (is (= :failed (get (status/statuses-of [o] [(r "spec-0001" "passed" (p "1")) (r "spec-0001" "failed" (p "2"))]) "spec-0001")))
    (testing "a row named in the result's name"
      (is (= :verified (get (status/statuses-of [o] [(assoc (r "spec-0001" "passed") :name "Over [n=1]")
                                                     (assoc (r "spec-0001" "passed") :name "Over [n=2]")])
                            "spec-0001"))))
    (testing "results that name no row: one passing result per row"
      (is (= :unverified (get (status/statuses-of [o] [(r "spec-0001" "passed")]) "spec-0001")))
      (is (= :verified (get (status/statuses-of [o] [(r "spec-0001" "passed") (r "spec-0001" "passed")]) "spec-0001"))))))

(deftest results-naming-no-stamped-scenario
  (is (= [{:file "d/x-result.json" :spec "spec-9999"}]
         (status/unknown [(sc "spec-0001")] [{:spec "spec-9999" :file "d/x-result.json" :status "passed"}
                                              {:spec nil :file "d/u-result.json" :status "passed"}
                                              {:spec "spec-0001" :file "d/a-result.json" :status "passed"}]))))

(deftest counts
  (is (= {:verified 1 :failed 0 :unverified 2 :planned 0}
         (status/counts {"a" :verified "b" :unverified "c" :unverified}))))
