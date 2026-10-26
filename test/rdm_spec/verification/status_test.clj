(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.status :as status]))

(defn- sc [id & {:as more}] (merge {:id id :title id :file "f" :line 1} more))

(defn- r [spec st & [nm]] {:file (str "r/" spec "-" st) :spec spec :status st :name nm})

(def outline
  (sc "spec-0001" :outline? true
      :examples [{:line 9 :header ["n"] :rows [{:line 10 :cells ["1"]} {:line 11 :cells ["2"]}
                                                {:line 12 :cells ["3"]}]}]))

(deftest a-scenario-is-verified-failed-unverified-or-planned
  (is (= :verified (status/status-of (sc "a") [(r "a" "passed")])))
  (is (= :unverified (status/status-of (sc "a") [])))
  (doseq [st ["failed" "broken" "skipped" "unknown"]]
    (is (= :failed (status/status-of (sc "a") [(r "a" "passed") (r "a" st)])) st))
  (testing "@wip is planned whatever the results"
    (is (= :planned (status/status-of (sc "a" :wip? true) [(r "a" "passed")])))
    (is (= :planned (status/status-of (sc "a" :wip? true) [(r "a" "failed")])))))

(deftest an-outline-is-verified-only-when-every-row-passed
  (let [row (fn [n st] (r "spec-0001" st (str "An outline over " n " [n=" n "]")))]
    (is (= :verified (status/status-of outline [(row 1 "passed") (row 2 "passed") (row 3 "passed")])))
    (is (= :failed (status/status-of outline [(row 1 "passed") (row 2 "passed") (row 3 "failed")])))
    (is (= :unverified (status/status-of outline [(row 1 "passed") (row 2 "passed")])))
    (testing "two results for one row do not stand for another"
      (is (= :unverified (status/status-of outline [(row 1 "passed") (row 1 "passed") (row 2 "passed")]))))
    (testing "a result naming no row verifies no row"
      (is (= :unverified (status/status-of outline [(r "spec-0001" "passed" "x") (r "spec-0001" "passed" "y")
                                                    (r "spec-0001" "passed" "z")]))))))

(deftest a-row-is-named-by-its-pairs-at-the-end-of-the-name
  (let [row ["n" "1"]]
    (is (status/names-row? "An outline over 1 [n=1]" [row]))
    (is (status/names-row? "t (a=x, b=y)" [["a" "x"] ["b" "y"]]))
    (is (not (status/names-row? "An outline over 1 [n=11]" [row])))
    (is (not (status/names-row? "An outline [n=1] then more" [row])))
    (is (not (status/names-row? nil [row])))))

(deftest statuses-are-derived-for-every-stamped-scenario
  (let [scs [(sc "a") (sc "b") (sc "c") (sc nil)]
        d (status/derive-statuses scs [(r "a" "passed") (r "b" "failed") (r "zz" "passed") (r nil "passed")])]
    (is (= {"a" :verified "b" :failed "c" :unverified} (:statuses d)))
    (testing "a result naming no stamped scenario is unknown; one with no spec label is neither"
      (is (= [{:file "r/zz-passed" :spec "zz"}] (:unknown-results d))))
    (is (= {:verified 1 :failed 1 :unverified 1 :planned 0} (status/counts (:statuses d))))))
