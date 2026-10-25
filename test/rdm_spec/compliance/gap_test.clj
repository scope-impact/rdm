(ns rdm-spec.compliance.gap-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as command]))

(defn- refusal [f] (try (f) nil (catch Exception e (when (command/cannot-run? e) (ex-message e)))))

(deftest what-names-a-clause
  (doseq [[text named?] [["STD:5.1" true] ["This section meets STD:5.1" true] ["STD:5.1: the plan" true]
                         ["STD:5.1.a" true] ["meets STD:5.1." true] ["STD:5.12" false] ["STD:5" false]
                         ["STD:5.1-x" false] ["XSTD:5.1" false] ["STD:5.1:x" false]]]
    (is (= named? (gap/names-key? text "STD:5.1")) text)))

(deftest single-keys
  (is (= "STD:9.9" (gap/single-key "STD:9.9")))
  (is (= "STD:9.9" (gap/single-key " STD:9.9: a note ")))
  (is (nil? (gap/single-key "meets STD:9.9"))))

(deftest clause-lists
  (testing "clauses, includes in place, comments, blanks, each key once"
    (let [files {"c/std.txt" "# std\ninclude base.txt\n\nSTD:5.1 Development plan\nSTD:4.1 again\n"
                 "c/base.txt" "STD:4.1 Quality system\n"}
          {:keys [clauses findings]} (gap/read-clause-lists files ["c/std.txt"])]
      (is (empty? findings))
      (is (= [["STD:4.1" "Quality system" "c/base.txt" 1] ["STD:5.1" "Development plan" "c/std.txt" 4]]
             (map (juxt :key :description :clause-list :line) clauses)))))
  (testing "a malformed line is a finding"
    (is (= ["clause-line-malformed"]
           (map :rule (:findings (gap/read-clause-lists {"s.txt" "STD:1 One\n* bullet\n"} ["s.txt"]))))))
  (testing "cannot run: an empty list, a missing include, a missing list"
    (is (re-find #"s\.txt holds no clause" (refusal #(gap/read-clause-lists {"s.txt" "# none\n"} ["s.txt"]))))
    (is (re-find #"c/missing\.txt" (refusal #(gap/read-clause-lists {"c/s.txt" "include missing.txt\n"} ["c/s.txt"]))))
    (is (re-find #"nope\.txt" (refusal #(gap/read-clause-lists {} ["nope.txt"])))))
  (testing "an include cycle ends"
    (is (= ["A"] (map :key (:clauses (gap/read-clause-lists {"a" "include b\nA x\n" "b" "include a\n"} ["a"])))))))

(deftest audit-tags-and-the-engineers-copy
  (let [doc "---\nid: D-1\nrevision: 2\ntitle: Doc [[K:1]]\n---\nWe plan [[K:2]] and test [[K:3]].\nOpen [[K:4\nclosed]] here.\n`[[K:5]]`\n\n```\n[[K:6]]\n```\nno [[close\n\nhere ]]\n"]
    (is (= [[4 "K:1"] [6 "K:2"] [6 "K:3"] [7 "K:4\nclosed"]] (map (juxt :line :text) (gap/audit-tags doc))))
    (is (= "---\nid: D-1\nrevision: 2\ntitle: Doc\n---\nWe plan and test.\nOpen\n here.\n`[[K:5]]`\n\n```\n[[K:6]]\n```\nno [[close\n\nhere ]]\n"
           (gap/strip-tags doc)))
    (is (= {"id" "D-1" "revision" "2" "title" "Doc [[K:1]]"} (:fields (gap/frontmatter doc))))))

(deftest documents
  (let [d (gap/read-document "p.md" "---\nid: P\ntitle: \"T\"\n---\nx [[STD:9.9]]\nTODO: more\n" ["STD:5.1"])]
    (is (= ["document-unidentified" "clause-unknown" "document-placeholder"] (map :rule (:findings d))))
    (is (= [1 5 6] (map :line (:findings d))))
    (is (re-find #"no revision" (:message (first (:findings d))))))
  (is (= [["b.md" "document-id-duplicate"]]
         (map (juxt :file :rule) (gap/duplicate-findings [{:file "a.md" :id "X"} {:file "b.md" :id "X"} {:file "c.md" :id "Y"}])))))

(deftest the-stage
  (let [root (str (fs/create-temp-dir))
        w (fn [rel text] (let [f (fs/file root rel)] (fs/create-dirs (fs/parent f)) (spit (str f) text)))]
    (try
      (w "dhf/clauses/std.txt" "STD:5.1 Plan\nSTD:5.2 Analysis\n")
      (w "dhf/documents/plan.md" "---\nid: P\nrevision: 1\ntitle: Plan\n---\nWe plan [[STD:5.1]].\n")
      (let [b (bus/make-bus)
            req {:root root :task "check" :args [] :opts {} :bus b :state (atom {})
                 :config {:regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"}}}
            {:keys [result findings]} (gap/gap req)
            [e] (bus/events b)]
        (is (= [["dhf/clauses/std.txt" 2 "clause-unreferenced"]] (map (juxt :file :line :rule) findings)))
        (is (= ["STD:5.2"] (get-in result [:clauses :gaps])))
        (is (= :ClausesIndexed (:event e)))
        (is (= [[{:file "dhf/documents/plan.md" :line 6}] []] (map :named-at (get-in e [:payload :clauses]))))
        (is (= :working-tree (get-in e [:payload :revision]))))
      (testing "no documents directory, or :regulated of another shape, cannot run"
        (fs/delete-tree (fs/file root "dhf/documents"))
        (is (re-find #"dhf/documents" (refusal #(gap/gap {:root root :state (atom {})
                                                           :config {:regulated {:clause-lists ["dhf/clauses/std.txt"]
                                                                                :documents "dhf/documents"}}}))))
        (is (re-find #":regulated" (refusal #(gap/gap {:root root :state (atom {}) :config {:regulated {:documents "d"}}})))))
      (finally (fs/delete-tree root)))))
