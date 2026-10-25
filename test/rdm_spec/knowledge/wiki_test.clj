(ns rdm-spec.knowledge.wiki-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.wiki :as wiki]))

(defn- with-wiki
  "Calls (f root) with a project whose wiki holds `files` {rel text}, removed after."
  [files f]
  (let [root (str (fs/create-temp-dir {:prefix "wiki-test-"}))]
    (try
      (doseq [[rel text] files]
        (fs/create-dirs (fs/parent (fs/file root "dhf/wiki" rel)))
        (spit (str (fs/file root "dhf/wiki" rel)) text))
      (f root)
      (finally (fs/delete-tree root)))))

(defn- rules [files & [words]]
  (with-wiki files
    (fn [root] (set (map :rule (wiki/wiki-findings root "dhf/wiki" (wiki/read-wiki root "dhf/wiki") words))))))

(def good-page "# P\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"x\".\n")

(deftest citations-are-found-with-their-footnote
  (let [cs (wiki/citations-in "p.md" good-page)]
    (is (= 1 (count cs)))
    (is (= {:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"
            :file "p.md" :line 5 :footnote "[^1]"}
           (first cs))))
  (testing "a placeholder, a URL or an e-mail address is no citation"
    (is (empty? (wiki/citations-in "p.md" "`<source>:<path>@<commit>` https://x.org/a@abcdef1 me@abcdef12")))))

(deftest a-clean-wiki-has-no-finding
  (is (empty? (rules {"index.md" "# W\n\n- [P](pages/p.md)\n" "pages/p.md" good-page}))))

(deftest the-index-and-its-pages
  (is (contains? (rules {"index.md" "# W\n" "pages/p.md" good-page}) "wiki-page-unlisted"))
  (is (contains? (rules {"index.md" "# W\n\n- [Gone](pages/gone.md)\n"}) "wiki-index-entry-missing"))
  (is (contains? (rules {"pages/p.md" good-page}) "wiki-no-index")))

(deftest footnotes
  (is (contains? (rules {"index.md" "- [P](pages/p.md)\n" "pages/p.md" "# P\n\nNo footnote.\n"}) "wiki-no-footnote"))
  (is (contains? (rules {"index.md" "- [P](pages/p.md)\n" "pages/p.md" "# P\n\nA.[^1]\n\n[^1]: a book.\n"})
                 "wiki-footnote-uncited")))

(deftest stack-words
  (let [fs* (with-wiki {"index.md" "- [P](pages/p.md)\n" "pages/p.md" (str good-page "Written in Cobalt.\n")}
              (fn [root] (wiki/wiki-findings root "dhf/wiki" (wiki/read-wiki root "dhf/wiki") ["Cobalt"])))
        f (first (filter #(= "wiki-stack-word" (:rule %)) fs*))]
    (is (= "dhf/wiki/pages/p.md" (:file f)))
    (is (= 6 (:line f))))
  (testing "a word is matched whole"
    (is (not (contains? (rules {"index.md" "- [P](pages/p.md)\n" "pages/p.md" (str good-page "Cobalts.\n")} ["Cobalt"])
                        "wiki-stack-word")))))
