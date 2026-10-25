(ns rdm-spec.compliance.gap-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

(defn- mem-reader
  "A reader over the map {path text}: no file is made."
  [files]
  {:read (fn [f] (get files f))
   :list (fn [dir] (vec (sort (filter #(.startsWith ^String % (str dir "/")) (keys files)))))
   :dir? (constantly true)})

(defn- refusal [f]
  (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest clause-list-lines
  (is (= [{:line 2 :include "dhf/clauses/base.txt"}
          {:line 4 :clause {:key "STD:5.1" :description "Development plan"}}
          {:line 5 :malformed "not/a key"}]
         (gap/parse-clause-list "dhf/clauses/std.txt"
                                "# comment\ninclude base.txt\n\nSTD:5.1 Development plan\nnot/a key\n"))))

(deftest clauses-in-order-includes-in-place-each-key-once
  (let [rd (mem-reader {"dhf/clauses/std.txt" "include base.txt\nSTD:5.1 Plan\nSTD:4.1 Again\n"
                        "dhf/clauses/base.txt" "STD:4.1 Quality system\n"})
        {:keys [clauses]} (gap/read-clauses rd ["dhf/clauses/std.txt"])]
    (is (= ["STD:4.1" "STD:5.1"] (map :key clauses)))
    (is (= {:key "STD:4.1" :description "Quality system" :clause-list "dhf/clauses/base.txt" :line 1
            :declared "dhf/clauses/std.txt"}
           (first clauses)))))

(deftest clause-list-refusals
  (is (re-find #"dhf/clauses/missing\.txt"
               (refusal #(gap/read-clauses (mem-reader {"dhf/clauses/std.txt" "include missing.txt\n"})
                                           ["dhf/clauses/std.txt"]))))
  (is (re-find #"dhf/clauses/std\.txt holds no clause"
               (refusal #(gap/read-clauses (mem-reader {"dhf/clauses/std.txt" "# only\n"})
                                           ["dhf/clauses/std.txt"]))))
  (testing "an include cycle is read once"
    (is (= ["A:1"] (map :key (:clauses (gap/read-clauses (mem-reader {"a.txt" "include a.txt\nA:1 a\n"}) ["a.txt"])))))))

(deftest what-names-a-clause
  (doseq [[text named?] [["STD:5.1" true] ["This section meets STD:5.1" true]
                         ["STD:5.1: the plan is this document" true] ["STD:5.1.a" true]
                         ["STD:5.12" false] ["STD:5" false] ["XSTD:5.1" false]]]
    (is (= named? (gap/names? text "STD:5.1")) text)))

(deftest audit-tags-skip-frontmatter-code-and-blank-lines
  (let [text (str "---\ntitle: T [[STD:1]]\n---\n\n"
                  "We plan [[STD:5.1]].\n"
                  "Code `[[STD:5.2]]` here.\n\n"
                  "```\n[[STD:5.3]]\n```\n\n"
                  "Open [[ tag\n\nSTD:5.4 ]]\n\n"
                  "Over [[STD:5.5\nlines]] ok.\n")
        tags (gap/audit-tags text)]
    (is (= ["STD:5.1" "STD:5.5\nlines"] (map :text tags)))
    (is (= [5 16] (map :line tags)))))

(deftest stripping-tags
  (is (= "We plan and test." (gap/strip-tags "We plan [[STD:5.1]] and test [[STD:5.2]].")))
  (is (= "We plan `[[STD:5.1]]`." (gap/strip-tags "We plan `[[STD:5.1]]`.")))
  (is (= "We plan the work\nand keep it." (gap/strip-tags "We plan the work [[STD:5.1\nthe plan]] and keep it.")))
  (is (= "---\ntitle: T [[X]]\n---\nBody." (gap/strip-tags "---\ntitle: T [[X]]\n---\nBody [[X]]."))))

(deftest frontmatter-and-identity
  (let [text "---\nid: PLAN-001\nrevision: 3\ntitle: Plan\n---\n\nBody\n"
        {:keys [frontmatter body-line]} (gap/split-frontmatter text)]
    (is (= 6 body-line))
    (is (= {:id "PLAN-001" :revision "3" :title "Plan"} (gap/identity-of frontmatter))))
  (is (:error (gap/identity-of nil))))

(deftest the-analysis
  (let [clauses [{:key "STD:5.1" :description "Plan" :clause-list "std.txt" :line 1}
                 {:key "STD:5.2" :description "Req" :clause-list "std.txt" :line 2}]
        doc (fn [id body] (str "---\nid: " id "\nrevision: 1\ntitle: T\n---\n\n" body "\n"))
        {:keys [findings] :as out}
        (gap/analyse clauses [{:file "a.md" :text (doc "A" "We plan [[STD:5.1]] [[STD:9.9]].\nTODO: more")}
                              {:file "b.md" :text (doc "A" "Nothing.")}
                              {:file "c.md" :text "No frontmatter."}])
        by-rule (group-by :rule findings)]
    (is (= [{:file "a.md" :line 7}] (:named-at (first (:clauses out)))))
    (is (= [["std.txt" 2]] (map (juxt :file :line) (by-rule "clause-unreferenced"))))
    (is (= #{"a.md" "b.md"} (set (map :file (by-rule "document-duplicate-id")))))
    (is (= ["c.md"] (map :file (by-rule "document-unidentified"))))
    (is (= [["a.md" 7]] (map (juxt :file :line) (by-rule "clause-unknown"))))
    (is (= [["a.md" 8]] (map (juxt :file :line) (by-rule "document-placeholder"))))
    (is (every? command/warning? (concat (by-rule "clause-unknown") (by-rule "document-placeholder"))))))
