(ns rdm-spec.compliance.audit-test
  "Unit tests of the Audit copy's pure parts. They make no fixture on disk."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.audit :as audit]))

(deftest the-engineers-copy
  (testing "a tag goes with the white space before it"
    (is (= "We plan." (audit/engineer-copy "We plan [[STD:5.1]].")))
    (is (= "We plan and test." (audit/engineer-copy "We plan [[A:1]] and test [[A:2]]."))))
  (testing "a tag over two lines leaves both lines"
    (is (= "We plan\n every week." (audit/engineer-copy "We plan [[A:1\nmore]] every week."))))
  (testing "code and unclosed tags are kept"
    (doseq [t ["We plan `[[A:1]]`." "We [[A:1 and\n\nthen ]]." "```\n[[A:1]]\n```" "\n    [[A:1]]\n"]]
      (is (= t (audit/engineer-copy t)))))
  (testing "the frontmatter loses its tags too"
    (is (= "---\ntitle: Plan\n---\n\nBody.\n"
           (audit/engineer-copy "---\ntitle: Plan [[A:1]]\n---\n\nBody [[A:1]].\n")))))

(deftest pages
  (testing "one section per declared list, included clauses in place"
    (let [page (audit/clauses-page "abc" ["s.txt"]
                                   [{:key "A:4" :description "Q" :clause-list "b.txt" :line 1 :declared "s.txt"
                                     :named-at [{:file "d/q.md" :line 3}]}
                                    {:key "A:5" :description "P" :clause-list "s.txt" :line 2 :declared "s.txt"
                                     :named-at [{:file "d/p.md" :line 8} {:file "d/r.md" :line 9}]}])]
      (is (= ["## s.txt"] (filter #(str/starts-with? % "## ") (str/split-lines page))))
      (is (str/includes? page "| A:5 | P | s.txt:2 | d/p.md:8, d/r.md:9 |"))))
  (testing "each need with its scenarios and their status"
    (let [page (audit/traceability-page [{:id "UN-001" :text "A need."}]
                                        [{:id "spec-0001" :title "T" :component-name "Alpha" :needs ["UN-001"]}]
                                        {"spec-0001" :verified})]
      (is (str/includes? page "## UN-001\n\nA need."))
      (is (str/includes? page "| @spec-0001 | T | Alpha | verified |")))))

(deftest the-manifest
  (let [m (audit/manifest-text "abc" true [["a.md" "11"] ["b.md" "22"]])]
    (is (str/starts-with? m "{:commit \"abc\", :clean true, :files [{:path \"a.md\", :sha256 \"11\"}"))
    (is (= {:commit "abc" :clean true :files [{:path "a.md" :sha256 "11"} {:path "b.md" :sha256 "22"}]}
           (edn/read-string m)))))

(deftest pdf-input
  (let [{:keys [title markdown]} (audit/pdf-input "---\nid: P-1\nrevision: 3\ntitle: Plan\n---\n\nBody.\n")]
    (is (= "Plan" title))
    (is (str/starts-with? markdown "**P-1**, revision 3"))
    (is (not (str/includes? markdown "title:")))))
