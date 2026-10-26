(ns rdm-spec.verification.status-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.verification.status :as status]))

(defn- sc [id & {:as more}] (merge {:id id :title id :file "f.feature" :line 1} more))
(defn- r [spec st & [params]] (cond-> {:file (str "r/" spec "-" st) :spec spec :status st} params (assoc :parameters params)))

(deftest statuses-are-derived-from-results
  (let [{:keys [statuses unknown-results by-id]}
        (status/derive-statuses [(sc "spec-0001") (sc "spec-0002") (sc "spec-0003") (sc "spec-0004" :wip? true)
                                 (sc nil :ids [])]
                                [(r "spec-0001" "passed") (r "spec-0002" "passed") (r "spec-0002" "broken")
                                 (r "spec-0004" "failed") (r "spec-0099" "passed") (r nil "passed")])]
    (is (= {"spec-0001" :verified "spec-0002" :failed "spec-0003" :unverified "spec-0004" :planned} statuses))
    (is (= [{:file "r/spec-0099-passed" :spec "spec-0099"}] unknown-results))
    (is (= 2 (count (get by-id "spec-0002"))))))

(deftest an-outline-is-verified-by-all-its-rows
  (let [o (sc "spec-0001" :outline? true :rows [{:line 9 :values {"n" "1"}} {:line 10 :values {"n" "2"}}])
        st (fn [rs] (get (:statuses (status/derive-statuses [o] rs)) "spec-0001"))]
    (is (= :verified (st [(r "spec-0001" "passed" {"n" "1"}) (r "spec-0001" "passed" {"n" "2"})])))
    (is (= :unverified (st [(r "spec-0001" "passed" {"n" "1"})])))
    (testing "results naming no row verify no row"
      (is (= :unverified (st [(r "spec-0001" "passed") (r "spec-0001" "passed") (r "spec-0001" "passed")]))))
    (is (= :failed (st [(r "spec-0001" "passed" {"n" "1"}) (r "spec-0001" "failed" {"n" "2"})])))))

(deftest counts-name-every-status
  (is (= {:verified 1 :failed 0 :unverified 2 :planned 0}
         (status/counts {"a" :verified "b" :unverified "c" :unverified}))))

(deftest unknown-results-are-warnings
  (is (= ["result-unknown"] (map :rule (status/unknown-warnings [{:file "r/x" :spec "spec-0099"}])))))
