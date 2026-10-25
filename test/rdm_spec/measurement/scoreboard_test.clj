(ns rdm-spec.measurement.scoreboard-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scoreboard :as sb]))

(defn- score [scope vs] {:scope scope :items (for [[id v] vs] {:id id :verdict v})})

(deftest both-directions-never-a-net
  (let [b (sb/compare-scores "01" (score :project {"a" :fail "b" :pass "c" :fail})
                             "02" (score :project {"a" :pass "b" :fail "c" :fail}))]
    (is (= [3 1 1 ["a"] ["b"]] ((juxt :compared :better :worse :better-items :worse-items) b)))
    (is (empty? (:statements b)))
    (is (not-any? #(re-find #"net" (name %)) (keys b)))))

(deftest a-moved-denominator-is-said
  (let [b (sb/compare-scores "01" (score :project {"a" :fail "b" :pass})
                             "02" (score :project {"a" :pass "b" :pass "c" :fail "d" :pass}))]
    (is (= 2 (:compared b)))
    (is (= ["c" "d"] (get-in b [:only-in "02"])))
    (is (some #(str/includes? % "the denominator moved from 2 to 4") (:statements b)))))

(deftest a-scoped-cycle-is-reported-target-first
  (let [b (sb/compare-scores "01" (score :project {"v1" :fail "v2" :pass "r1" :pass})
                             "02" (score {:context :verification} {"v1" :pass "v2" :pass}))]
    (is (= [:cycles :target :rest :statements] (keys b)))
    (is (= [2 1 0] ((juxt :compared :better :worse) (:target b))))
    (is (= {"02" 1} (get-in b [:rest :not-scored])))
    (is (some #(str/includes? % "the rest was not scored in cycle 02") (:statements b))))
  (testing "the earlier cycle scoped"
    (let [b (sb/compare-scores "01" (score {:context :verification} {"v1" :fail})
                               "02" (score :project {"v1" :pass "r1" :pass}))]
      (is (= "01" (get-in b [:target :cycle])))
      (is (= 1 (get-in b [:target :better]))))))

(deftest the-stage
  (let [root (str (fs/create-temp-dir))
        w (fn [c f v] (spit (str (doto (fs/file root "dhf/cycles" c) fs/create-dirs) "/" f) (pr-str v)))
        req {:root root :task "scoreboard" :args ["00" "01"] :config {:cycles-dir "dhf/cycles"}}]
    (try
      (w "00" "cycle.edn" {:cut "spec-1" :bootstrap true})
      (w "01" "cycle.edn" {:cut "spec-2"})
      (testing "a cycle that was not scored cannot be compared"
        (w "02" "cycle.edn" {:cut "spec-3"})
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has not been scored"
                              (sb/scoreboard (assoc req :args ["01" "02"])))))
      (testing "a bootstrap is marked and nothing is compared"
        (let [{:keys [result findings]} (sb/scoreboard req)]
          (is (= ["00"] (get-in result [:scoreboard :bootstrap])))
          (is (not (contains? (:scoreboard result) :better)))
          (is (every? #(= :warning (:severity %)) findings))))
      (finally (fs/delete-tree root)))))
