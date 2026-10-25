(ns rdm-spec.compliance.audit-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [rdm-spec.compliance.audit :as audit]
            [rdm-spec.kernel.command :as command]))

(defn- refusal [f] (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest the-clauses-page
  (let [page (audit/clauses-page [{:key "STD:4.1" :description "Base" :clause-list "c/base.txt" :named-at [{:file "d.md" :line 2}]}
                                  {:key "STD:5.1" :description "A | B" :clause-list "c/std.txt"
                                   :named-at [{:file "d.md" :line 6} {:file "r.md" :line 3}]}])]
    (is (str/includes? page "## c/std.txt\n\n| Clause | Description | Named at |\n|---|---|---|\n| STD:5.1 | A \\| B | d.md:6, r.md:3 |\n"))
    (is (< (str/index-of page "STD:4.1") (str/index-of page "STD:5.1")))))

(deftest the-traceability-page
  (let [page (audit/traceability-page [{:id "spec-0002" :title "Two" :needs ["UN-001"] :component-name "Alpha"}
                                       {:id nil :title "Unstamped" :needs ["UN-001"]}]
                                      [{:id "UN-010" :text "Tenth."} {:id "UN-001" :text "First."}]
                                      {"spec-0002" :verified})]
    (is (< (str/index-of page "## UN-001") (str/index-of page "## UN-010")))
    (is (str/includes? page "## UN-001\n\nFirst.\n\n| Scenario | Title | Component | Status |\n|---|---|---|---|\n| @spec-0002 | Two | Alpha | verified |\n"))
    (is (not (str/includes? page "Unstamped")))
    (is (str/includes? page "## UN-010\n\nTenth.\n\nNo scenario traces to this need.\n"))))

(deftest the-pdf-names-the-document
  (is (str/starts-with? (audit/pdf-source {:id "P-1" :revision "3" :title "Plan"} "Body\n")
                        "---\ntitle: \"Plan\"\n---\n\nDocument P-1, revision 3\n\nBody")))

(deftest preparing
  (let [d (str (fs/create-temp-dir))]
    (try
      (is (re-find #"holds no :regulated" (refusal #(audit/prepare {:root d :args ["OUT"] :config {}}))))
      (fs/create-dirs (fs/file d "OUT"))
      (is (re-find #"OUT OUT already exists"
                   (refusal #(audit/prepare {:root d :args ["OUT"]
                                             :config {:regulated {:clause-lists ["c.txt"] :documents "docs"}}}))))
      (finally (fs/delete-tree d)))))
