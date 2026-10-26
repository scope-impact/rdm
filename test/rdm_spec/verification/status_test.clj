(ns rdm-spec.verification.status-test
  "Unit tests of the Status component's derivation. No fixture on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.status :as status]))

(defn- sc [id & {:as more}] (merge {:id id :title id :file "f" :line 1 :wip? false :outline? false} more))
(defn- r [id st & [params]] (cond-> {:spec id :status st :file (str id "-" st)} params (assoc :parameters params)))

(def outline (sc "spec-0001" :outline? true :rows [{:line 5 :values {"n" "1"}} {:line 6 :values {"n" "2"}}]))

(deftest a-scenario-is-derived-from-its-results
  (let [d (status/derive-statuses [(sc "spec-0001") (sc "spec-0002") (sc "spec-0003") (sc "spec-0004" :wip? true)]
                                  [(r "spec-0001" "passed") (r "spec-0002" "passed") (r "spec-0002" "broken")
                                   (r "spec-0004" "passed") (r "spec-9999" "passed") (r nil "passed")])]
    (is (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified "spec-0004" :planned} (:statuses d)))
    (is (= [{:file "spec-9999-passed" :spec "spec-9999"}] (:unknown-results d)))
    (is (= 2 (count (get-in d [:by-id "spec-0002"])))))
  (testing "an unstamped scenario has no status"
    (is (= {} (:statuses (status/derive-statuses [(sc nil)] []))))))

(deftest an-outline-is-verified-by-every-row
  (let [st #(get-in (status/derive-statuses [outline] %) [:statuses "spec-0001"])]
    (is (= :verified (st [(r "spec-0001" "passed" {"n" "1"}) (r "spec-0001" "passed" {"n" "2"})])))
    (is (= :failed (st [(r "spec-0001" "passed" {"n" "1"}) (r "spec-0001" "failed" {"n" "2"})])))
    (is (= :unverified (st [(r "spec-0001" "passed" {"n" "1"})])) "a row with no result")
    (testing "results naming no row verify nothing"
      (is (= :unverified (st [(r "spec-0001" "passed") (r "spec-0001" "passed") (r "spec-0001" "passed")])))
      (is (= :unverified (st [(r "spec-0001" "passed" {"n" "3"}) (r "spec-0001" "passed" {"n" "1"})]))))
    (testing "a result labelled with its id that did not pass fails it, whatever row it names"
      (is (= :failed (st [(r "spec-0001" "passed" {"n" "1"}) (r "spec-0001" "passed" {"n" "2"}) (r "spec-0001" "failed")]))))))

(deftest counts-hold-every-status
  (is (= {:verified 1 :failed 0 :unverified 2 :planned 0}
         (status/counts {"a" :verified "b" :unverified "c" :unverified}))))
