(ns rdm-spec.spec.check-test
  "One test per rule the spec check enforces (02-write-the-spec/check.feature,
  12-several-specs/check.feature, 13-slice-development/check.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.spec.check :as check]))

(defn sc [m] (merge {:id "spec-0001" :ids ["spec-0001"] :file "f.feature" :line 3 :own-tags ["spec-0001" "e2e"]
                     :levels ["e2e"] :level :e2e :needs ["UN-001"] :tags ["UN-001" "spec-0001" "e2e"]} m))
(def opts {:prefix "spec" :issued 10 :declared #{"UN-001"}})
(defn rules [scs] (set (map :rule (check/scenario-findings scs opts))))

(deftest a-scenario-carries-exactly-one-id
  (is (empty? (rules [(sc {})])))
  (is ((rules [(sc {:id nil :ids ["spec-0001" "spec-0002"]})]) "scenario-ids"))
  (is ((rules [(sc {}) (sc {:file "g.feature"})]) "id-duplicate"))
  (is ((rules [(sc {:id nil :ids []})]) "scenario-unstamped"))
  (is ((rules [(sc {:id "spec-0901" :ids ["spec-0901"]})]) "id-unissued")))

(deftest a-scenario-carries-exactly-one-level
  (is ((rules [(sc {:level nil :levels []})]) "level-missing"))
  (is ((rules [(sc {:level nil :levels ["e2e" "contract"]})]) "level-twice"))
  (is ((rules [(sc {:own-tags ["spec-0001" "e2e" "persona"]})]) "tag-unknown")))

(deftest every-scenario-traces-to-a-user-need
  (is ((rules [(sc {:needs []})]) "need-missing"))
  (is ((rules [(sc {:needs ["UN-009"]})]) "need-unknown")))

(deftest every-user-need-rests-on-a-source
  (is (= ["need-unsourced"] (map :rule (check/need-findings [{:id "UN-004" :sources [] :line 2}] "needs.edn")))))

(deftest planned-work-is-a-scenario-tagged-wip
  (is (empty? (rules [(sc {:own-tags ["spec-0001" "e2e" "wip"] :wip? true})]))))

(deftest the-spec-never-names-an-implementation
  (is (= [{:file "f.md" :line 2 :rule "stack-word" :word "Cobalt"}]
         (map #(select-keys % [:file :line :rule :word]) (check/stack-findings "f.md" "a\nwritten in Cobalt.\n" ["Cobalt"])))))

(deftest each-spec-is-checked-on-its-own-terms
  (is ((set (map :rule (check/scenario-findings [(sc {:needs ["UN-002"]})] opts))) "need-unknown")))

(deftest every-slice-is-planned
  (is (= ["slice-unplanned"] (map :rule (check/plan-findings "s/plan.md" nil #{}))))
  (is (= ["slice-unfounded"] (map :rule (check/plan-findings "s/plan.md" "# S\n\nstatus: ready\n\n## Goal\n\n## References\n\nnone\n\n## Scope\n\n**In:**\n\n**Out:**\n\n## Decisions\n" #{})))))

(deftest a-slice-says-where-it-stands
  (is (= ["slice-status"] (map :rule (check/plan-findings "s/plan.md" "# S\n\nstatus: finished\n\n## Goal\n\n## References\n\n- UN-001\n\n## Scope\n\n**In:**\n\n**Out:**\n\n## Decisions\n" #{"UN-001"})))))
