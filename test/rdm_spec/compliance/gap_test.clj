(ns rdm-spec.compliance.gap-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.gap :as gap]))

(defn names [line] (gap/named-keys (map :text (gap/tags (str "---\nid: A\n---\n" line "\n"))) ["STD:5.1"]))

(deftest what-names-a-clause
  (is (= #{"STD:5.1"} (names "We plan [[STD:5.1]].")))
  (is (= #{"STD:5.1"} (names "[[This section meets STD:5.1]]")))
  (is (= #{"STD:5.1"} (names "[[STD:5.1: the plan is this document]]")))
  (is (= #{"STD:5.1"} (names "We plan [[STD:5.1.a]].")))
  (is (empty? (names "We meet STD:5.1 here.")))
  (is (empty? (names "We plan [[STD:5.12]].")))
  (is (empty? (names "We plan [[STD:5]].")))
  (is (empty? (names "`[[STD:5.1]]`"))))

(deftest code-and-paragraphs
  (is (= 1 (count (gap/tags "- item\n\n    continuation [[STD:5.1]]\n"))))
  (is (empty? (gap/tags "Text.\n\n    [[STD:5.1]]\n")))
  (is (empty? (gap/tags "An open [[ tag\n\nSTD:5.1 ]]\n")))
  (is (empty? (gap/tags "```\n[[STD:5.1]]\n```\n")))
  (is (= [5] (map :line (gap/tags "---\ntitle: x\n---\n\nA [[K:1]]\n" {:skip-frontmatter true})))))

(deftest clause-lines
  (is (= {:key "STD:5.1" :description "Development plan"} (gap/clause-line "STD:5.1 Development plan")))
  (is (nil? (gap/clause-line "Quality system"))))

(deftest engineer-copy
  (is (= "We plan." (gap/strip-tags "We plan [[STD:5.1]].")))
  (is (= "We plan and test." (gap/strip-tags "We plan [[STD:5.1]] and test [[STD:5.2]].")))
  (is (= "We plan\n the work." (gap/strip-tags "We plan [[STD:\n5.1]] the work.")))
  (is (= "We plan `[[STD:5.1]]`." (gap/strip-tags "We plan `[[STD:5.1]]`.")))
  (is (= "---\ntitle: Development plan\n---\n" (gap/strip-tags "---\ntitle: Development plan [[STD:5.1]]\n---\n"))))
