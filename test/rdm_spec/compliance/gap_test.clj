(ns rdm-spec.compliance.gap-test
  "Unit tests of Gap analysis's pure parts. They make no fixture on disk."
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

(deftest clause-list-lines
  (is (= {:clause "STD:5.1" :description "Development plan"} (gap/parse-line "STD:5.1 Development plan")))
  (is (= {:include "base.txt"} (gap/parse-line "include base.txt")))
  (is (:comment (gap/parse-line "  # a comment")))
  (is (:blank (gap/parse-line "   ")))
  (is (:malformed (gap/parse-line "STD/5 nope"))))

(deftest reading-clause-lists
  (let [files {"dhf/clauses/std.txt" "# c\ninclude base.txt\nSTD:4.1 Again\nSTD:5.1 Plan\n"
               "dhf/clauses/base.txt" "STD:4.1 Quality\n"}
        {:keys [clauses findings]} (gap/read-clauses files ["dhf/clauses/std.txt"])]
    (testing "includes in place, each key once, where first reached"
      (is (= [["STD:4.1" "dhf/clauses/base.txt" 1] ["STD:5.1" "dhf/clauses/std.txt" 4]]
             (map (juxt :key :clause-list :line) clauses)))
      (is (every? #(= "dhf/clauses/std.txt" (:declared %)) clauses))
      (is (empty? findings))))
  (testing "a list that reaches no clause cannot run"
    (is (thrown-with-msg? Exception #"holds no clause" (gap/read-clauses {"a.txt" "# only\n"} ["a.txt"]))))
  (testing "an unreadable include cannot run, naming the file"
    (let [e (try (gap/read-clauses {"d/a.txt" "include missing.txt\n"} ["d/a.txt"]) nil (catch Exception e e))]
      (is (command/cannot-run? e))
      (is (re-find #"d/missing\.txt" (ex-message e))))))

(deftest naming-clauses
  (is (gap/names? "STD:5.1" "STD:5.1"))
  (is (gap/names? "STD:5.1.a" "STD:5.1"))
  (is (not (gap/names? "STD:5.12" "STD:5.1")))
  (is (not (gap/names? "STD:5" "STD:5.1")))
  (is (= ["STD:5.1" "the" "plan"] (gap/tag-words "STD:5.1: the plan")))
  (is (= "STD:9.9" (gap/single-key " STD:9.9 ")))
  (is (= "STD:9.9" (gap/single-key "STD:9.9: a note")))
  (is (nil? (gap/single-key "This section meets STD:5.1"))))

(deftest audit-tags-in-a-body
  (testing "outside code, never past a blank line, across one line break"
    (is (= [{:line 1 :inner "A"} {:line 9 :inner "B\nC"}]
           (gap/body-tags ["x [[A]] `[[no]]`" "" "    [[indented]]" "" "```" "[[fenced]]" "```" "open [[ x"
                           "[[B" "C]] y"]))))
  (testing "an indented line inside a paragraph is not code"
    (is (= ["A"] (map :inner (gap/body-tags ["text" "    [[A]]"])))))
  (testing "a tab indents a block too"
    (is (empty? (gap/body-tags ["" "\t[[A]]"])))))

(deftest documents
  (let [d (gap/read-document "dhf/documents/p.md"
                             "---\nid: P-1\nrevision: 2\ntitle: \"Plan [[X:1]]\"\n---\n\nWe plan [[X:1]].\nTODO later\n")]
    (is (= ["P-1" "2" "Plan [[X:1]]"] ((juxt :id :revision :title) d)))
    (testing "frontmatter names nothing; lines count from the file's start"
      (is (= [{:line 7 :inner "X:1"}] (:tags d))))
    (is (= [8] (:todos d)))))

(deftest analysis
  (let [clauses [{:key "X:1" :description "One" :clause-list "c.txt" :line 1 :declared "c.txt"}
                 {:key "X:2" :description "Two" :clause-list "c.txt" :line 2 :declared "c.txt"}]
        docs [{:file "a.md" :id "A" :revision "1" :title "A" :tags [{:line 3 :inner "X:1"} {:line 4 :inner "Y:9"}] :todos []}
              {:file "b.md" :id "A" :revision nil :title "B" :tags [] :todos [5]}]
        {:keys [clauses findings]} (gap/analyse clauses docs)
        by-rule (group-by :rule findings)]
    (is (= [[{:file "a.md" :line 3}] []] (map :named-at clauses)))
    (is (= [["c.txt" 2]] (map (juxt :file :line) (get by-rule "clause-unreferenced"))))
    (is (re-find #"revision" (:message (first (get by-rule "document-unidentified")))))
    (is (= 2 (count (get by-rule "document-id-duplicate"))))
    (is (= [["a.md" 4]] (map (juxt :file :line) (get by-rule "clause-unknown"))))
    (is (every? command/warning? (concat (get by-rule "clause-unknown") (get by-rule "document-placeholder"))))))

(deftest paths
  (is (= "dhf/clauses/base.txt" (gap/resolve-include "dhf/clauses/std.txt" "base.txt")))
  (is (= "dhf/other/x.txt" (gap/resolve-include "dhf/clauses/std.txt" "../other/x.txt"))))
