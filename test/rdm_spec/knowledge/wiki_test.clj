(ns rdm-spec.knowledge.wiki-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.wiki :as wiki]))

(def sources [{:id "loyly" :repository "r" :pins ["17d68d2"] :fenced ["impl"]}])

(defn- project [files]
  (let [dir (str (fs/create-temp-dir))]
    (doseq [[rel text] files]
      (fs/create-dirs (fs/parent (fs/file dir rel)))
      (spit (str (fs/file dir rel)) text))
    dir))

(defn- check [files & [config]]
  (wiki/check-wiki (project files) (merge {:wiki-dir "dhf/wiki" :stack-words ["Cobalt"]} config)
                   {:sources sources :sources-dir nil}))

(defn- rules [r] (set (map :rule (remove #(= :warning (:severity %)) (:findings r)))))

(def good-page "# A\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"Section\".\n")

(deftest footnotes-and-references
  (is (= [{:label "1" :line 3 :text "`loyly:a.md@17d68d2`\n  continued"}]
         (wiki/footnotes "A.[^1]\n\n[^1]: `loyly:a.md@17d68d2`\n  continued\nNext.")))
  (is (= [{:label "1" :line 1} {:label "2" :line 1}]
         (wiki/footnote-references "A.[^1] B.[^2]\n[^1]: c"))))

(deftest index-entries-are-relative-links
  (is (= ["pages/a.md" "b.md"]
         (map :path (wiki/index-entries "- [A](pages/a.md)\n- [B](./b.md#top) [U](https://x.org/a.md) [H](#here)")))))

(deftest a-good-page-passes
  (let [r (check {"dhf/wiki/index.md" "# Wiki\n\n- [A](pages/a.md)\n" "dhf/wiki/pages/a.md" good-page})]
    (is (empty? (rules r)))
    (is (= ["dhf/wiki/pages/a.md"] (:pages r)))
    (is (= [["dhf/wiki/pages/a.md" 5 :unchecked]] (map (juxt :file :line :status) (:citations r))))))

(deftest page-rules
  (testing "a page with no footnote"
    (is (= #{"wiki-no-footnote"}
           (rules (check {"dhf/wiki/index.md" "- [A](pages/a.md)\n" "dhf/wiki/pages/a.md" "# A\n\nOne.\n\nTwo.\n\nThree.\n"})))))
  (testing "a footnote citing nothing, and a reference with no footnote"
    (is (= #{"wiki-footnote-uncited" "wiki-footnote-undefined"}
           (rules (check {"dhf/wiki/index.md" "- [A](pages/a.md)\n"
                          "dhf/wiki/pages/a.md" "# A\n\nOne.[^1] Two.[^2]\n\n[^1]: A book.\n"})))))
  (testing "a footnote whose citation does not resolve names the footnote"
    (let [r (check {"dhf/wiki/index.md" "- [A](pages/a.md)\n"
                    "dhf/wiki/pages/a.md" "# A\n\nOne.[^x]\n\n[^x]: `loyly:impl/a.clj@17d68d2`.\n"})
          f (first (filter #(= "citation-fenced" (:rule %)) (:findings r)))]
      (is (= ["dhf/wiki/pages/a.md" 5] ((juxt :file :line) f)))
      (is (str/includes? (:message f) "the footnote [^x] cites loyly:impl/a.clj@17d68d2")))))

(deftest index-rules
  (let [r (check {"dhf/wiki/index.md" "- [S](pages/scoring.md)\n" "dhf/wiki/pages/a.md" good-page})]
    (is (= #{"wiki-index-dangling" "wiki-unindexed"} (rules r)))
    (is (some #(and (= "dhf/wiki/index.md" (:file %)) (str/includes? (:message %) "pages/scoring.md")) (:findings r)))
    (is (some #(= "dhf/wiki/pages/a.md" (:file %)) (:findings r))))
  (is (= #{"wiki-no-index"} (rules (check {"dhf/wiki/pages/a.md" good-page})) ))
  (is (empty? (:findings (check {})))))

(deftest stack-words
  (let [r (check {"dhf/wiki/index.md" "- [A](pages/a.md)\nNot cobalt-blue.\n"
                  "dhf/wiki/pages/a.md" (str good-page "Written in Cobalt.\nCobaltic is fine.\n")})
        fs* (filter #(= "wiki-stack-word" (:rule %)) (:findings r))]
    (is (= [["dhf/wiki/index.md" 2] ["dhf/wiki/pages/a.md" 6]] (map (juxt :file :line) fs*)))
    (is (every? #(str/includes? (:message %) "Cobalt") fs*))))
