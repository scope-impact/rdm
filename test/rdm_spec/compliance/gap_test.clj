(ns rdm-spec.compliance.gap-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.gap :as gap]))

(deftest a-clause-list-line-is-one-of-four-kinds
  (is (= {:kind :clause :key "STD:5.1" :description "Development plan"} (gap/classify "STD:5.1 Development plan")))
  (is (= {:kind :include :file "base.txt"} (gap/classify "include base.txt")))
  (is (= {:kind :comment} (gap/classify "# a comment")))
  (is (= {:kind :blank} (gap/classify "   ")))
  (testing "a key holds a digit or a colon, so prose is malformed"
    (is (= :malformed (:kind (gap/classify "Quality system"))))
    (is (= :malformed (:kind (gap/classify "STD:5.1"))))
    (is (= :clause (:kind (gap/classify "62304:6.2.4 Software release"))))))

(defn- reader [files] (fn [f] (get files f)))

(deftest clause-lists-are-read-includes-in-place
  (let [{:keys [clauses findings]} (gap/index-lists (reader {"c/std.txt" "STD:4 General\ninclude base.txt\nSTD:4.1 Again\nSTD:5.1 Plan\n"
                                                             "c/base.txt" "STD:4.1 Quality\nSTD:4.2 Docs\n"})
                                                    ["c/std.txt"])]
    (is (empty? findings))
    (is (= [["STD:4" "c/std.txt" 1] ["STD:4.1" "c/base.txt" 1] ["STD:4.2" "c/base.txt" 2] ["STD:5.1" "c/std.txt" 4]]
           (map (juxt :key :clause-list :line) clauses)))
    (is (every? #(= "c/std.txt" (:declared %)) clauses)))
  (testing "an include cycle and a malformed line are findings"
    (let [{:keys [findings]} (gap/index-lists (reader {"a.txt" "A:1 a\ninclude b.txt\n" "b.txt" "include a.txt\nprose here\n"}) ["a.txt"])]
      (is (= #{"clause-include-cycle" "clause-line-malformed"} (set (map :rule findings))))))
  (testing "what cannot be read, or holds no clause, stops the task"
    (is (thrown-with-msg? Exception #"missing.txt" (gap/index-lists (reader {"s.txt" "include missing.txt\n"}) ["s.txt"])))
    (is (thrown-with-msg? Exception #"holds no clause" (gap/index-lists (reader {"s.txt" "# only\n"}) ["s.txt"])))))

(def doc "---\nid: P-1\nrevision: 2\ntitle: Plan [[A:1]]\n---\n\nWe plan [[STD:5.1]] and `[[X:1]]` test [[STD:5.2]].\n\n    [[C:1]]\n\n- item\n\n    more [[L:1]]\n\n```\n[[F:1]]\n```\nopen [[ tag\n\nclose ]]\nmulti [[M:1\nM:2]] end\n")

(deftest audit-tags-are-found-outside-code-in-the-body
  (let [d (gap/read-document "d.md" doc)]
    (is (= ["P-1" "2" "Plan [[A:1]]"] [(:id d) (:revision d) (:title d)]))
    (is (= [[7 "STD:5.1"] [7 "STD:5.2"] [13 "L:1"] [21 "M:1\nM:2"]] (map (juxt :line :text) (:tags d))))))

(deftest a-tag-names-its-key-and-every-ancestor
  (let [ks #{"STD:5" "STD:5.1" "STD:5.2"}]
    (is (= #{"STD:5" "STD:5.1"} (gap/named-keys ks "STD:5.1.a")))
    (is (= #{"STD:5" "STD:5.1"} (gap/named-keys ks "This section meets STD:5.1")))
    (is (= #{"STD:5.1"} (gap/named-keys #{"STD:5.1"} "STD:5.1: the plan")))
    (is (= #{"STD:5"} (gap/named-keys ks "STD:5.12")))
    (is (= #{} (gap/named-keys #{"STD:5.1"} "STD:5")))
    (is (= "STD:9.9" (gap/one-key " STD:9.9 ")))
    (is (nil? (gap/one-key "a note")))))

(deftest the-engineers-copy-drops-every-tag-and-keeps-every-line
  (let [e (gap/strip-tags doc)]
    (is (= (count (str/split-lines doc)) (count (str/split-lines e))))
    (is (str/includes? e "title: Plan\n"))
    (is (str/includes? e "We plan and `[[X:1]]` test.\n"))
    (is (str/includes? e "    [[C:1]]"))
    (is (str/includes? e "\n    more\n"))
    (is (str/includes? e "[[F:1]]"))
    (is (str/includes? e "open [[ tag"))
    (is (str/includes? e "multi\n end\n"))))

(deftest a-controlled-document-carries-its-identity
  (let [docs [{:file "a.md" :id "P-1" :revision "1" :title "A" :frontmatter {:lines {"id" 2}}}
              {:file "b.md" :id "P-1" :revision "1" :title "B" :frontmatter {:lines {"id" 2}}}
              {:file "c.md" :id "C-1" :title "C" :frontmatter {}}]
        fs (gap/document-findings docs)]
    (is (= [["c.md" "document-unidentified"] ["a.md" "document-id-duplicate"] ["b.md" "document-id-duplicate"]]
           (map (juxt :file :rule) fs)))
    (is (re-find #"revision" (:message (first fs))))
    (is (re-find #"a\.md and b\.md" (:message (second fs))))))
