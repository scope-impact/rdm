(ns rdm-spec.knowledge.wiki-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.wiki :as wiki]))

(defn- project [files]
  (let [dir (str (fs/create-temp-dir {:prefix "wiki-test-"}))]
    (doseq [[rel text] files]
      (fs/create-dirs (fs/parent (fs/file dir rel)))
      (spit (str (fs/file dir rel)) text))
    dir))

(defn- req [dir] {:root dir :config {:wiki-dir "dhf/wiki" :stack-words ["Cobalt"]}})

(defn- resolve-all [status]
  (fn [c] (assoc c :status status :why (when (not= :resolved status) "a reason"))))

(defn- rules [out] (set (map :rule (:findings out))))

(deftest reading-markdown
  (is (= ["loyly:README.md@17d68d2" "x:a/b.md@abcdef1"]
         (wiki/citations-in "See `loyly:README.md@17d68d2`, and x:a/b.md@abcdef1.")))
  (is (= [{:label "1" :line 3 :text "`a:b@1234567`, \"S\".\nmore"}]
         (wiki/footnotes "Claim.[^1]\n\n[^1]: `a:b@1234567`, \"S\".\n  more\n")))
  (testing "nothing is read inside a code block"
    (is (empty? (wiki/footnotes "```\n[^1]: x:y@1234567\n```\n")))
    (is (empty? (wiki/index-entries "```\n- [A](pages/a.md)\n```\n"))))
  (is (= [{:target "pages/a.md" :line 1}]
         (wiki/index-entries "- [A](pages/a.md#top) [B](https://example.org/b.md)\n"))))

(deftest a-good-wiki-passes
  (let [dir (project {"dhf/wiki/index.md" "# W\n\n- [A](pages/a.md)\n"
                      "dhf/wiki/pages/a.md" "Claim.[^1]\n\n[^1]: `s:p.md@1234567`.\n"})
        out (wiki/check (req dir) (resolve-all :resolved))]
    (is (= [] (:findings out)))
    (is (= ["dhf/wiki/pages/a.md"] (:pages out)))
    (is (= [{:citation "s:p.md@1234567" :file "dhf/wiki/pages/a.md" :line 3 :footnote "[^1]"
             :status :resolved :why nil}]
           (:citations out)))))

(deftest every-rule
  (let [dir (project {"dhf/wiki/index.md" "# W\n\n- [A](pages/a.md)\n- [S](pages/scoring.md)\n- [N](pages/n.md)\n"
                      "dhf/wiki/pages/a.md" "Written in Cobalt.[^1] And[^2].\n\n[^1]: no citation\n"
                      "dhf/wiki/pages/n.md" "No footnote at all.\n"
                      "dhf/wiki/pages/b.md" "Claim.[^1]\n\n[^1]: `s:p.md@1234567`.\n"})
        out (wiki/check (req dir) (resolve-all :failed))]
    (is (= #{"wiki-entry" "wiki-unlisted" "wiki-no-footnote" "wiki-footnote-no-citation"
             "wiki-footnote-undefined" "wiki-citation" "wiki-stack-word"}
           (rules out)))
    (is (some #(and (= "wiki-stack-word" (:rule %)) (= "dhf/wiki/pages/a.md" (:file %)) (= 1 (:line %))) (:findings out)))
    (is (some #(and (= "wiki-entry" (:rule %)) (= 4 (:line %)) (str/includes? (:message %) "pages/scoring.md"))
              (:findings out)))
    (is (some #(and (= "wiki-citation" (:rule %)) (str/includes? (:message %) "[^1]")
                    (str/includes? (:message %) "does not resolve: a reason"))
              (:findings out)))))

(deftest an-unchecked-citation-warns
  (let [dir (project {"dhf/wiki/index.md" "- [B](pages/b.md)\n"
                      "dhf/wiki/pages/b.md" "Claim.[^1]\n\n[^1]: `s:p.md@1234567`.\n"})
        out (wiki/check (req dir) (resolve-all :unchecked))]
    (is (= [:warning] (map :severity (:findings out))))))

(deftest pages-and-no-index
  (let [dir (project {"dhf/wiki/pages/b.md" "Claim.[^1]\n\n[^1]: `s:p.md@1234567`.\n"})]
    (is (= #{"wiki-index"} (rules (wiki/check (req dir) (resolve-all :resolved)))))))

(deftest no-wiki-no-findings
  (is (= {:pages [] :citations [] :findings []}
         (wiki/check (req (project {})) (resolve-all :resolved)))))
