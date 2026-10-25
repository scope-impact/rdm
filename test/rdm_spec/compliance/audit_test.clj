(ns rdm-spec.compliance.audit-test
  "Unit tests of the Audit copy's pure parts. They make no fixture on disk."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.audit :as audit]))

(deftest git-status-is-read
  (is (= [{:file "dhf/documents/a.md" :code "??"} {:file "dhf/spec/x.feature" :code " M"}
          {:file "new.md" :code "R "} {:file "z.md" :code "!!"}]
         (audit/parse-status "?? dhf/documents/a.md\u0000 M dhf/spec/x.feature\u0000R  new.md\u0000old.md\u0000!! z.md\u0000"))))

(deftest the-clauses-page
  (let [page (audit/clauses-page ["s.txt"]
                                 [{:key "A:1" :description "One" :clause-list "b.txt" :line 1 :declared "s.txt"
                                   :named-at [{:file "d/p.md" :line 8} {:file "d/r.md" :line 9}]}
                                  {:key "A:2" :description "Two | more" :clause-list "s.txt" :line 2 :declared "s.txt"
                                   :named-at []}])]
    (is (str/includes? page "\n## s.txt\n"))
    (is (str/includes? page "| A:1 | One | b.txt:1 | d/p.md:8, d/r.md:9 |"))
    (is (str/includes? page "| A:2 | Two \\| more | s.txt:2 | not named |"))
    (is (< (str/index-of page "A:1") (str/index-of page "A:2")))))

(deftest the-traceability-page
  (let [page (audit/traceability-page [{:id "UN-001" :text "A need."} {:id "UN-002" :text "Another."}]
                                      [{:id "spec-0001" :title "One" :needs ["UN-001"] :component-name "Plan"}
                                       {:id nil :title "Unstamped" :needs ["UN-001"]}]
                                      {"spec-0001" :verified})]
    (is (str/includes? page "## UN-001\n\nA need.\n"))
    (is (str/includes? page "| @spec-0001 | One | Plan | verified |"))
    (is (not (str/includes? page "Unstamped")))
    (is (str/includes? page "## UN-002\n\nAnother.\n\nNo scenario traces to it."))))

(deftest the-manifest
  (let [m (edn/read-string (audit/manifest-text "abc" [{:path "b" :sha256 "2"} {:path "a" :sha256 "1"}]))]
    (is (= {:commit "abc" :files [{:path "a" :sha256 "1"} {:path "b" :sha256 "2"}]} m))))

(deftest the-pdfs-markdown
  (is (= "Document PLAN\\-001, revision 3\n\n\n# Plan\n"
         (audit/pdf-markdown "---\nid: PLAN-001\n---\n\n# Plan\n" {"id" "PLAN-001" "revision" "3"}))))
