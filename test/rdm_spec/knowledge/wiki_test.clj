(ns rdm-spec.knowledge.wiki-test
  "One test per rule the Wiki enforces (03-ground-the-spec/wiki.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.knowledge.wiki :as wiki]))

(deftest every-claim-in-the-wiki-cites-a-source
  (is (= ["wiki-uncited"] (map :rule (wiki/page-findings "dhf/wiki/pages/a.md" "One.\n\nTwo.\n\nThree.\n" [])))))

(deftest the-wiki-has-one-index-and-every-page-is-in-it
  (is (= #{"wiki-unindexed" "wiki-page-missing"}
         (set (map :rule (wiki/index-findings "dhf/wiki" "- [S](pages/scoring.md)\n" ["dhf/wiki/pages/cut-and-fence.md"]))))))

(deftest the-wiki-never-names-an-implementation
  (is (= ["stack-word"] (map :rule (wiki/page-findings "p.md" "x[^a]\n\n[^a]: `loyly:README.md@17d68d2`\nwritten in Cobalt\n" ["Cobalt"])))))
