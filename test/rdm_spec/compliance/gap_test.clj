(ns rdm-spec.compliance.gap-test
  "Unit tests of Gap analysis's pure parts: clause lists, the audit tag
  scanner, naming, the engineer's copy. One test reads lists from a
  directory it makes and removes."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.compliance.gap :as gap]
            [rdm-spec.kernel.command :as command]))

(defn- doc [& body] (str "---\nid: D-1\nrevision: 1\ntitle: T\n---\n\n" (str/join "\n" body) "\n"))

(defn- tags [text] (mapv :text (remove :front? (gap/scan text))))

(deftest clause-list-lines
  (is (= {:kind :clause :key "STD:5.1" :description "Development plan"} (gap/parse-clause-line "STD:5.1 Development plan")))
  (is (= {:kind :include :file "base.txt"} (gap/parse-clause-line "include base.txt")))
  (is (= {:kind :comment} (gap/parse-clause-line "  # a comment")))
  (is (= {:kind :blank} (gap/parse-clause-line "   ")))
  (is (= :malformed (:kind (gap/parse-clause-line "STD:5.1"))))
  (is (= :malformed (:kind (gap/parse-clause-line "Not/a key here")))))

(deftest includes-resolve-against-the-including-file
  (is (= "dhf/clauses/base.txt" (gap/resolve-include "dhf/clauses/std.txt" "base.txt")))
  (is (= "dhf/other/x.txt" (gap/resolve-include "dhf/clauses/std.txt" "../other/x.txt"))))

(deftest reading-lists
  (let [files {"c/std.txt" "# std\ninclude base.txt\nSTD:5.1 Plan\n* STD:5.3 A bullet\nSTD:4.1 Again\n"
               "c/base.txt" "STD:4.1 Quality\ninclude std.txt\n"}
        {:keys [lists findings files]} (gap/read-lists ["c/std.txt"] files)]
    (testing "includes in place, each key once where first written"
      (is (= [["STD:4.1" "c/base.txt" 1] ["STD:5.1" "c/std.txt" 3]]
             (map (juxt :key :clause-list :line) (gap/index-clauses lists)))))
    (testing "a malformed line and an include cycle are findings"
      (is (= [["c/base.txt" 2 "clause-list-include-cycle"] ["c/std.txt" 4 "clause-list-line"]]
             (sort (map (juxt :file :line :rule) findings)))))
    (is (= ["c/std.txt" "c/base.txt"] files)))
  (testing "a list with no clause, or an unreadable include, cannot run"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"c/std.txt holds no clause"
                          (gap/read-lists ["c/std.txt"] {"c/std.txt" "# only\n"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"c/missing.txt"
                          (gap/read-lists ["c/std.txt"] {"c/std.txt" "include missing.txt\n"})))))

(deftest what-is-a-tag
  (is (= ["STD:5.1"] (tags (doc "We plan [[STD:5.1]]."))))
  (is (= [] (tags (doc "We plan `[[STD:5.1]]`."))) "an inline code span")
  (is (= [] (tags (doc "```" "[[STD:5.1]]" "```"))) "a fenced block")
  (is (= [] (tags (doc "Text." "" "    [[STD:5.1]]"))) "an indented block")
  (is (= ["STD:5.1"] (tags (doc "- item" "" "    more [[STD:5.1]]"))) "a list item's continuation")
  (is (= ["STD:5.1"] (tags (doc "Text" "    [[STD:5.1]]"))) "a paragraph's lazy continuation")
  (is (= [] (tags (doc "An open [[ tag" "" "STD:5.1 ]]"))) "never past a blank line")
  (is (= ["STD:5.1\nmore"] (tags (doc "We [[STD:5.1" "more]] plan."))) "across a line break")
  (testing "a tag in the frontmatter is marked"
    (is (= [true] (map :front? (gap/scan "---\ntitle: T [[STD:5.1]]\n---\n"))))
    (is (= 2 (:line (first (gap/scan "---\ntitle: T [[STD:5.1]]\n---\n")))))))

(deftest what-a-tag-names
  (let [ks #{"STD:5.1" "STD:5"}]
    (is (= ["STD:5.1" "STD:5"] (gap/names ks "STD:5.1")))
    (is (= ["STD:5.1" "STD:5"] (gap/names ks "STD:5.1.a")))
    (is (= ["STD:5.1"] (gap/names #{"STD:5.1"} "This section meets STD:5.1")))
    (is (= ["STD:5.1"] (gap/names #{"STD:5.1"} "STD:5.1: the plan is this document")))
    (is (= [] (gap/names #{"STD:5.1"} "STD:5.12")))
    (is (= [] (gap/names #{"STD:5.1"} "STD:5")))
    (is (= [] (gap/names #{"STD:5.1"} "XSTD:5.1")))))

(deftest one-key-tags
  (is (= "STD:9.9" (gap/one-key "STD:9.9")))
  (is (= "STD:9.9" (gap/one-key " STD:9.9: a note")))
  (is (nil? (gap/one-key "This section meets STD:5.1"))))

(deftest the-engineers-copy
  (is (= "We plan the work." (gap/strip-tags "We plan the work [[STD:5.1]].")))
  (is (= "We plan and test." (gap/strip-tags "We plan [[STD:5.1]] and test [[STD:5.2]].")))
  (is (= "We plan\n today." (gap/strip-tags "We plan [[STD:5.1\nmore]] today.")))
  (is (= "We plan `[[STD:5.1]]`." (gap/strip-tags "We plan `[[STD:5.1]]`.")))
  (is (= "An [[ open\n\nthen ]]" (gap/strip-tags "An [[ open\n\nthen ]]")))
  (is (= "---\ntitle: Plan\n---\n" (gap/strip-tags "---\ntitle: Plan [[STD:5.1]]\n---\n"))))

(deftest frontmatter-and-placeholders
  (is (= {"id" "D-1" "revision" "1" "title" "T"} (gap/frontmatter (doc "x"))))
  (is (= {"title" "Quoted"} (gap/frontmatter "---\ntitle: \"Quoted\"\n---\n")))
  (is (= {} (gap/frontmatter "# No frontmatter\n")))
  (is (= [8] (gap/todo-lines (doc "Text." "TODO: more" "" "    TODO in code")))))

(deftest analysis
  (let [files {"c/std.txt" "STD:5.1 Plan\nSTD:5.2 Review\n"
               "d/a.md" (doc "We plan [[STD:5.1]] [[STD:9.9]].")
               "d/b.md" "---\nid: D-1\ntitle: B\n---\n\nTODO\n"}
        rd {:revision :working-tree :read files
            :list (fn [dir] (vec (sort (filter #(str/starts-with? % (str dir "/")) (keys files)))))}
        out (gap/analyse {:clause-lists ["c/std.txt"] :documents "d"} rd)
        line (fn [f] (command/format-finding f))]
    (is (= [[{:file "d/a.md" :line 7}] []] (map :named-at (:clauses out))))
    (is (= ["c/std.txt:2: clause-unreferenced: STD:5.2 Review: no audit tag in a controlled document names it"
            "d/b.md: document-unidentified: the controlled document has no revision in its frontmatter"
            "d/b.md: document-id-duplicate: the id D-1 is also the id of d/a.md: an id names one controlled document"
            "d/a.md:7: warning: clause-unknown: STD:9.9: the audit tag names a key no declared clause list holds"
            "d/b.md:6: warning: document-placeholder: the document still holds TODO: a placeholder meets no clause"]
           (map line (:findings out))))
    (is (= [{:file "d/a.md" :id "D-1" :revision "1" :title "T"} {:file "d/b.md" :id "D-1" :revision nil :title "B"}]
           (:documents out)))))

(deftest reading-the-working-tree
  (let [dir (str (fs/create-temp-dir {:prefix "rdm-spec-gap-test-"}))]
    (try
      (fs/create-dirs (fs/file dir "c"))
      (spit (str (fs/file dir "c/std.txt")) "STD:1 One\n")
      (let [rd (gap/reader dir nil)]
        (is (= "STD:1 One\n" ((:read rd) "c/std.txt")))
        (is (nil? ((:read rd) "c/none.txt")))
        (is (= ["c/std.txt"] ((:list rd) "c")))
        (is (= [] ((:list rd) "none"))))
      (finally (fs/delete-tree dir)))
    (is (not (fs/exists? dir)))))
