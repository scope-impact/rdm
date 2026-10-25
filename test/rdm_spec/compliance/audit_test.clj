(ns rdm-spec.compliance.audit-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.audit :as audit]))

(deftest the-clauses-page-has-one-section-per-declared-list
  (let [page (audit/clauses-page ["c/std.txt"]
                                 [{:key "STD:4.1" :description "Quality" :declared "c/std.txt" :clause-list "c/base.txt" :named-at []}
                                  {:key "STD:5.1" :description "Plan | spec" :declared "c/std.txt" :clause-list "c/std.txt"
                                   :named-at [{:file "d/plan.md" :line 8} {:file "d/r.md" :line 9}]}])]
    (is (= ["# Clauses" "## c/std.txt"] (filter #(str/starts-with? % "#") (str/split-lines page))))
    (is (str/includes? page "| STD:5.1 | Plan \\| spec | d/plan.md:8, d/r.md:9 |"))
    (is (< (str/index-of page "STD:4.1") (str/index-of page "STD:5.1")))))

(deftest the-traceability-page-lists-each-need
  (let [page (audit/traceability-page {:needs [{:id "UN-001" :text "A need."} {:id "UN-002" :text "Another."}]
                                       :scenarios [{:id "spec-0001" :title "T" :needs ["UN-001"] :component-name "Alpha"
                                                    :context-name "demo" :level :e2e}]}
                                      {"spec-0001" :verified})]
    (is (str/includes? page "| @spec-0001 | T | Alpha | demo | e2e | verified |"))
    (is (str/includes? page "No scenario traces to UN-002."))))

(deftest the-manifest-is-edn
  (is (= {:commit "abc" :files [{:path "a" :sha256 "1"} {:path "b" :sha256 "2"}]}
         (edn/read-string (audit/manifest-text "abc" [{:path "a" :sha256 "1"} {:path "b" :sha256 "2"}])))))

(deftest a-pdf-starts-with-the-documents-identity
  (let [{:keys [title markdown]} (audit/pdf-markdown "---\nid: P-1\nrevision: 3\ntitle: Plan\n---\n\nBody\n")]
    (is (= "Plan" title))
    (is (str/starts-with? markdown "**P-1**, revision 3\n\n"))))
