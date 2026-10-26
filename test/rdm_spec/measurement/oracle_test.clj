(ns rdm-spec.measurement.oracle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.measurement.oracle :as oracle]))

(deftest every-seconds-is-held-as-measured
  (is (= {:verify {:checks [{:name "tests" :seconds :measured} {:seconds :measured :x [1 {:seconds :measured}]}]}}
         (oracle/hold-seconds {:verify {:checks [{:name "tests" :seconds 1.2} {:seconds 3 :x [1 {:seconds 0.1}]}]}}))))

(deftest the-cases-are-ordered-by-component-then-case
  (is (= [[:measurement/scorer "01"] [:regeneration/scopes "a"] [:regeneration/scopes "b"]]
         (oracle/cases {:project {} :cycles {} :recorded []
                        :regeneration/scopes {"b" {} "a" {}} :measurement/scorer {"01" {}}}))))

(deftest differences-name-the-path-and-both-values
  (is (= [] (oracle/differences {:exit 0 :out {:a 1}} {:exit 0 :out {:a 1}})))
  (is (= [[[:out :a] 1 2]] (oracle/differences {:exit 0 :out {:a 1}} {:exit 0 :out {:a 2}})))
  (is (= [[[:wrote "s" :items 1 :verdict] :pass :fail]]
         (oracle/differences {:wrote {"s" {:items [{:id "a" :verdict :pass} {:id "b" :verdict :pass}]}}}
                             {:wrote {"s" {:items [{:id "a" :verdict :pass} {:id "b" :verdict :fail}]}}})))
  (testing "a finding names the item's id where the path passes through one"
    (let [g {:wrote {"s" {:items [{:id "a"} {:id "spec-0014" :verdict :pass}]}}}
          a {:wrote {"s" {:items [{:id "a"} {:id "spec-0014" :verdict :fail}]}}}
          f (first (oracle/difference-findings :measurement/scorer "01" g a))]
      (is (= "oracle-golden-differs" (:rule f)))
      (doseq [w [":measurement/scorer" "\"01\"" "spec-0014" ":pass" ":fail"]]
        (is (str/includes? (:message f) w) w)))))

(deftest changes-are-named-component-colon-case
  (is (= #{[:measurement/scoreboard "01-02"] [:regeneration/scopes "order"]}
         (oracle/parse-changes "measurement/scoreboard:01-02,regeneration/scopes:order")))
  (is (= #{} (oracle/parse-changes nil)))
  (is (command/cannot-run? (try (oracle/parse-changes "nonsense") nil (catch Exception e e)))))

(deftest the-configuration-names-the-new-digest-and-keeps-its-text
  (let [text ";; c\n{:oracle {:repository \"r\"\n          :digest \"sha256:aaa\"}\n :x 1}\n"]
    (is (= ";; c\n{:oracle {:repository \"r\"\n          :digest \"sha256:bbb\"}\n :x 1}\n"
           (oracle/with-digest text "sha256:aaa" "sha256:bbb")))))
