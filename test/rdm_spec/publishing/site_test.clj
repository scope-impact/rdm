(ns rdm-spec.publishing.site-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.publishing.site :as site]))

(deftest footnotes-link-to-their-sources
  (is (= "[^1]: [loyly:README.md@17d68d2](https://github.com/skallinen/loyly/blob/17d68d2/README.md), \"x\"."
         (site/link-citations "[^1]: `loyly:README.md@17d68d2`, \"x\"." {"loyly" "https://github.com/skallinen/loyly"}))))

(deftest broken-links-are-read-from-the-builder
  (is (= [["site-src/docs/shop/till.md" "cart.md"]]
         (site/broken-links "WARNING -  Doc file 'shop/till.md' contains a link 'cart.md', but the target is not found among documentation files.\n" "site-src"))))

(deftest navigation
  (let [y (site/mkdocs-yml "demo" [["spec" [["spec/index.md" nil] ["spec/parser.md" "Parser"]]]] ["wiki/index.md"])]
    (is (< (str/index-of y "index.md") (str/index-of y "spec/index.md") (str/index-of y "spec/parser.md") (str/index-of y "wiki/index.md")))))
