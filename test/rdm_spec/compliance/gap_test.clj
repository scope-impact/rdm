(ns rdm-spec.compliance.gap-test
  "One test per rule Gap analysis enforces (10-regulated-profile/gap.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.gap :as gap]))

(deftest without-regulated-nothing-of-the-profile-runs
  (is (= {} (gap/index {:cfg {} :root "."}))))

(deftest a-clause-list-is-read-as-clause-lines-comments-and-includes
  (let [{:keys [entries findings]} (gap/parse-list "s.txt" "# c\ninclude base.txt\n\nSTD:5.1 Development plan\nQuality system\n")]
    (is (= [:include :clause] (map :kind entries)))
    (is (= ["clause-line-malformed"] (map :rule findings)))
    (is (= 5 (:line (first findings))))))

(deftest a-clause-is-met-only-by-an-audit-tag-naming-it
  (is (gap/names? "STD:5.1" "STD:5.1"))
  (is (gap/names? "This section meets STD:5.1" "STD:5.1"))
  (is (gap/names? "STD:5.1: the plan" "STD:5.1"))
  (is (gap/names? "STD:5.1.a" "STD:5.1"))
  (is (not (gap/names? "STD:5.12" "STD:5.1")))
  (is (not (gap/names? "STD:5" "STD:5.1")))
  (is (= [] (gap/tags "We plan `[[STD:5.1]]`.\n")))
  (is (= [] (gap/tags "Text.\n\n    [[STD:5.1]]\n")))
  (is (= ["STD:5.1"] (map :text (gap/tags "- item\n\n    [[STD:5.1]]\n"))))
  (is (= [] (gap/tags "An open [[ tag\n\nSTD:5.1 ]]\n"))))

(deftest frontmatter-names-no-clause
  (is (= [] (gap/tags "---\ntitle: Plan [[STD:5.1]]\n---\n\nBody.\n"))))

(deftest a-placeholder-is-a-warning
  (is (= ["document-placeholder"] (map :rule (gap/placeholder-warnings "p.md" "a\nTODO: write\n")))))

(deftest a-controlled-document-carries-its-identity
  (is (= {"id" "P" "title" "T"} (:frontmatter (gap/frontmatter "---\nid: P\ntitle: T\n---\nbody\n")))))
