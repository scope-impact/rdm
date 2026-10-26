(ns rdm-spec.compliance.audit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.audit :as audit]))

(def clauses
  [{:key "STD:4.1" :description "Quality system" :clause-list "dhf/clauses/base.txt" :line 1
    :declared "dhf/clauses/std.txt" :named-at [{:file "dhf/documents/plan.md" :line 9}]}
   {:key "STD:5.1" :description "Development plan" :clause-list "dhf/clauses/std.txt" :line 2
    :declared "dhf/clauses/std.txt" :named-at [{:file "dhf/documents/plan.md" :line 8}
                                               {:file "dhf/documents/risk/register.md" :line 9}]}])

(deftest the-clauses-page
  (let [text (audit/clauses-page ["dhf/clauses/std.txt"] clauses)
        ls (str/split-lines text)
        at (fn [part] (first (keep-indexed (fn [i l] (when (str/includes? l part) i)) ls)))]
    (is (at "## dhf/clauses/std.txt"))
    (is (not (at "## dhf/clauses/base.txt")))
    (is (< (at "STD:4.1") (at "STD:5.1")))
    (is (every? #(str/includes? (nth ls (at "STD:5.1")) %)
                ["Development plan" "dhf/documents/plan.md:8" "dhf/documents/risk/register.md:9"]))))

(deftest the-traceability-page
  (let [text (audit/traceability-page [{:id "UN-001" :text "A user needs the work planned."}]
                                      [{:id "spec-0001" :title "Planned" :needs ["UN-001"] :component-name "Alpha"}
                                       {:id "spec-0002" :title "Formatted" :needs ["UN-001"] :component-name "Alpha"}]
                                      {"spec-0001" :verified "spec-0002" :verified})]
    (is (str/includes? text "## UN-001"))
    (is (str/includes? text "A user needs the work planned."))
    (is (re-find #"@spec-0001.*Planned.*Alpha.*verified" text))
    (is (re-find #"@spec-0002.*Formatted.*Alpha.*verified" text))))

(deftest the-manifest
  (is (= {:commit "abc" :files [{:path "a.md" :sha256 "1"} {:path "b/c.md" :sha256 "2"}]}
         (audit/manifest "abc" {"b/c.md" "2" "a.md" "1"}))))

(deftest a-pdf-names-its-document-on-its-first-page
  (let [md (audit/pdf-source "---\nid: PLAN-001\nrevision: 3\ntitle: Plan\n---\n\n# Plan\nWe plan.\n"
                             {:id "PLAN-001" :revision "3" :title "Plan"})]
    (is (str/starts-with? md "---\n"))
    (is (str/includes? md "PLAN-001, revision 3"))))
