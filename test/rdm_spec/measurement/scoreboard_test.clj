(ns rdm-spec.measurement.scoreboard-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.measurement.scoreboard :as sb]))

(deftest compare-verdicts
  (let [r (sb/compare-verdicts ["a" "b" "c" "d"] {"a" :fail "b" :pass "c" :pass "d" :fail}
                               {"a" :pass "b" :fail "c" :pass "d" :fail})]
    (is (= {:compared 4 :better 1 :worse 1 :better-ids ["a"] :worse-ids ["b"]} r))
    (is (not (contains? r :net)))))

(deftest item-in-scope
  (let [item {"context" "spec" "component" "Parser"}]
    (is (sb/item-in-scope? :project {} item))
    (is (sb/item-in-scope? {:context :spec} {} item))
    (is (sb/item-in-scope? {:component :spec/parser} {} item))
    (is (sb/item-in-scope? {:component :spec/parser} {:spec/parser "Parser"} item))
    (is (sb/item-in-scope? {:component :spec/check} {:spec/check "Parser"} item) "matched through the model's name")
    (is (not (sb/item-in-scope? {:component :spec/stamper} {} item)))
    (is (sb/item-in-scope? [{:context :knowledge} {:component :spec/parser}] {} item))))

(deftest compare-cycles
  (testing "a project cycle: the denominator moved, new items apart"
    (let [{:keys [result]} (sb/compare-cycles {:baseline "01" :cycle "02" :scope :project
                                               :before {"a" :fail "b" :pass}
                                               :after {"a" :pass "b" :pass "c" :fail}})]
      (is (= 1 (get-in result [:overall :better])))
      (is (= {:from 2 :to 3 :moved true :compared 2 :new 1 :new-ids ["c"] :dropped 0 :dropped-ids []}
             (:denominator result)))
      (is (re-find #"denominator moved from 2 to 3" (first (:notes result))))))
  (testing "a scoped cycle: target first, the rest unscored"
    (let [info {"a" {"context" "verification"} "b" {"context" "architecture"}}
          {:keys [result]} (sb/compare-cycles {:baseline "01" :cycle "02" :scope {:context :verification}
                                               :before {"a" :fail "b" :pass} :after {"a" :pass} :info info})]
      (is (= [:baseline :cycle :scope :target :rest :denominator :notes] (keys result)))
      (is (= 1 (get-in result [:target :better])))
      (is (false? (get-in result [:rest :scored]))))))
