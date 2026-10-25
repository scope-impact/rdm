(ns rdm-spec.publishing.site-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(deftest names
  (is (= "spec-check" (site/slug "Spec check")))
  (is (= "results-reader" (site/slug " Results  reader "))))

(deftest footnotes-link-their-sources
  (let [text "# P\n\nA.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"x\".\n"
        out (#'site/link-citations text "dhf/wiki/p.md"
                                   [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md"
                                     :commit "17d68d2" :file "dhf/wiki/p.md" :line 5}]
                                   {"loyly" "https://github.com/skallinen/loyly"})]
    (is (str/includes? out "[`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md)"))
    (testing "a citation of an unknown source is left as it is"
      (is (= text (#'site/link-citations text "dhf/wiki/p.md"
                                         [{:citation "x:a@17d68d2" :source "x" :path "a" :commit "17d68d2"
                                           :file "dhf/wiki/p.md" :line 5}] {}))))))

(deftest the-banner-follows-the-title
  (is (= "# T\n\n> b\n\nbody\n" (#'site/with-banner "# T\nbody\n" "> b"))))

(deftest a-broken-link-names-the-page-and-the-link
  (let [fs* (#'site/build-findings
             "WARNING -  Doc file 'architecture/lint.md' contains a link 'missing.md', but the target is not found among documentation files.\nAborted with 1 warnings in strict mode!"
             "site-src/docs")]
    (is (= [["site-src/docs/architecture/lint.md" "site-link-broken"]] (map (juxt :file :rule) fs*)))
    (is (str/includes? (:message (first fs*)) "missing.md"))))
