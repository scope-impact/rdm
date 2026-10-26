(ns rdm-spec.spec.check-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.check :as check]))

(def s {:id "spec-0001" :ids ["spec-0001"] :file "f.feature" :line 3 :title "T" :level :e2e :levels ["e2e"]
        :tags ["spec-0001" "e2e"] :tag-lines [["spec-0001" 2] ["e2e" 2]] :feature-tags ["UN-001"] :rule-tags []
        :needs ["UN-001"] :wip? false})

(defn rules [scenarios]
  (map :rule (check/scenario-findings {:scenarios scenarios :needs [{:id "UN-001" :text "x" :sources ["a:b@c"]}]
                                       :issued 10 :prefix "spec"})))

(deftest a-good-scenario-passes (is (empty? (rules [s]))))
(deftest unstamped (is (= ["scenario-unstamped"] (rules [(assoc s :id nil :ids [] :tags ["e2e"])]))))
(deftest two-ids (is (= ["scenario-ids"] (rules [(assoc s :id nil :ids ["spec-0001" "spec-0002"] :tags ["spec-0001" "spec-0002" "e2e"])]))))
(deftest duplicate (is (= ["id-duplicate" "id-duplicate"] (rules [s (assoc s :file "g.feature")]))))
(deftest unissued (is (= ["id-unissued"] (rules [(assoc s :id "spec-0011" :ids ["spec-0011"] :tags ["spec-0011" "e2e"])]))))
(deftest no-level (is (= ["level-missing"] (rules [(assoc s :level nil :levels [] :tags ["spec-0001"])]))))
(deftest two-levels (is (= ["level-multiple"] (rules [(assoc s :level nil :levels ["e2e" "contract"] :tags ["spec-0001" "e2e" "contract"])]))))
(deftest unknown-tag (is (= ["tag-unknown"] (rules [(assoc s :tags ["spec-0001" "e2e" "persona"])]))))
(deftest no-need (is (= ["need-missing"] (rules [(assoc s :needs [] :feature-tags [])]))))
(deftest undeclared-need (is (= ["need-undeclared"] (rules [(assoc s :needs ["UN-001" "UN-009"] :tags ["spec-0001" "e2e" "UN-009"])]))))

(deftest stack-words
  (is (= [["f.md" 2 "Cobalt"]] (map (juxt :file :line #(re-find #"Cobalt" (:message %)))
                                     (check/stack-findings [["f.md" "a\nwritten in Cobalt.\nCobalts"]] ["Cobalt"])))))
