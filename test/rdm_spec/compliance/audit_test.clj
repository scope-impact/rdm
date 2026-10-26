(ns rdm-spec.compliance.audit-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.audit :as audit]))

(deftest git-status-names-every-path-that-differs-from-head
  (is (= ["d/a.md" "d/new.md" "d/ign.md" "d/renamed.md" "d/old.md"]
         (audit/status-paths (str " M d/a.md\u0000?? d/new.md\u0000!! d/ign.md\u0000"
                                  "R  d/renamed.md\u0000d/old.md\u0000")))))

(deftest a-path-is-made-from-when-it-is-or-lies-under-an-input
  (is (audit/under? ["dhf/spec" "dhf/harness/config.edn"] "dhf/spec/demo/alpha.feature"))
  (is (audit/under? ["dhf/spec" "dhf/harness/config.edn"] "dhf/harness/config.edn"))
  (is (not (audit/under? ["dhf/spec"] "dhf/specs/x")))
  (is (not (audit/under? ["dhf/harness/config.edn"] "dhf/harness/stack.md"))))

(def clauses
  [{:key "STD:4.1" :description "Quality system" :clause-list "dhf/clauses/base.txt" :line 1
    :declared "dhf/clauses/std.txt" :named-at [{:file "dhf/documents/plan.md" :line 9}]}
   {:key "STD:5.1" :description "Development plan" :clause-list "dhf/clauses/std.txt" :line 2
    :declared "dhf/clauses/std.txt" :named-at [{:file "dhf/documents/plan.md" :line 8}
                                                {:file "dhf/documents/risk/register.md" :line 9}]}
   {:key "X:1" :description "Other | piped" :clause-list "dhf/clauses/other.txt" :line 1
    :declared "dhf/clauses/other.txt" :named-at []}])

(deftest the-clauses-page-has-a-section-per-declared-list-includes-in-place
  (let [page (audit/clauses-page ["dhf/clauses/std.txt" "dhf/clauses/other.txt"] clauses)
        ls (str/split-lines page)
        idx (fn [s] (first (keep-indexed #(when (str/includes? %2 s) %1) ls)))]
    (is (= 2 (count (filter #(str/starts-with? % "## ") ls))))
    (is (< (idx "## dhf/clauses/std.txt") (idx "STD:4.1") (idx "STD:5.1") (idx "## dhf/clauses/other.txt")))
    (is (str/includes? (nth ls (idx "STD:5.1")) "dhf/documents/plan.md:8, dhf/documents/risk/register.md:9"))
    (testing "a cell's pipe is escaped"
      (is (str/includes? page "Other \\| piped")))))

(deftest the-traceability-page-lists-each-need-with-its-scenarios
  (let [page (audit/traceability-page
              [{:id "UN-001" :text "A need."} {:id "UN-002" :text "Untraced."}]
              [{:id "spec-0001" :title "One" :needs ["UN-001"] :component-name "Alpha" :context-name "demo"}
               {:id "spec-0002" :title "Two" :needs ["UN-001"] :component-name "Alpha" :context-name "demo"}]
              {"spec-0001" :verified "spec-0002" :failed})
        ls (str/split-lines page)]
    (is (some #(and (str/includes? % "spec-0001") (str/includes? % "One") (str/includes? % "Alpha")
                    (str/includes? % "verified")) ls))
    (is (some #(and (str/includes? % "spec-0002") (str/includes? % "failed")) ls))
    (is (str/includes? page "A need."))
    (is (str/includes? page "No scenario traces to UN-002"))))

(deftest the-manifest-is-the-commit-and-every-file-sorted
  (let [m (edn/read-string (audit/manifest-text "abc" [{:path "b" :sha256 "2"} {:path "a" :sha256 "1"}]))]
    (is (= {:commit "abc" :files [{:path "a" :sha256 "1"} {:path "b" :sha256 "2"}]} m))))

(deftest the-pdf-input-names-the-document-first
  (let [{:keys [title body]} (audit/pdf-input "---\nid: PLAN-001\nrevision: 3\ntitle: Development plan\n---\n\n# Development plan\nWe plan.\n")]
    (is (= "Development plan" title))
    (is (str/starts-with? body "PLAN-001, revision 3"))
    (is (not (str/includes? body "---\nid:")))
    (is (str/includes? body "We plan."))))
