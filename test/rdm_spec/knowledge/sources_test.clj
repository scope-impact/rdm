(ns rdm-spec.knowledge.sources-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.knowledge.sources :as s]))

(deftest parses-citations
  (is (= {:source "loyly" :path "README.md" :commit "17d68d2"} (dissoc (s/parse-citation "loyly:README.md@17d68d2") :citation)))
  (is (nil? (s/parse-citation "the loyly README")))
  (is (= ["loyly:README.md@17d68d2" "a:b/c.md@abcdef1"]
         (map :citation (s/citations-in "[^1]: `loyly:README.md@17d68d2`, \"x\"; and a:b/c.md@abcdef1.")))))

(deftest fenced-paths
  (is (s/fenced? ["harness/fence.sh"] "harness/fence.sh"))
  (is (s/fenced? ["rdm"] "rdm/render.py"))
  (is (s/fenced? ["."] "README.md"))
  (is (not (s/fenced? ["rdm"] "rdmx/a.py"))))

(def index {"loyly" {:repository "r" :record "loyly.md" :pins ["17d68d2"] :fenced ["impl"]}
            "rdm" {:repository "r" :record "rdm.md" :pins ["3763e08"] :retired "old" :fenced ["."]}})

(deftest judges-citations-without-checkouts
  (is (= [:unchecked "citation-unchecked"] ((juxt :status :rule) (s/judge index nil (s/parse-citation "loyly:README.md@17d68d2")))))
  (is (= "citation-unknown-source" (:rule (s/judge index nil (s/parse-citation "innolitix:README.md@f21b508")))))
  (is (= "citation-unpinned" (:rule (s/judge index nil (s/parse-citation "loyly:README.md@0000000")))))
  (is (= "citation-fenced" (:rule (s/judge index nil (s/parse-citation "loyly:impl/x.clj@17d68d2")))))
  (is (= "citation-retired" (:rule (s/judge index nil (s/parse-citation "rdm:README.md@3763e08"))))))

(deftest dropped-pins
  (is (= [{:source "loyly" :pin "17d68d2" :by "bbb"}]
         (s/dropped-pins [["aaa" {:loyly {:pins ["17d68d2"]}}] ["bbb" {:loyly {:pins ["1111111"]}}]])))
  (is (empty? (s/dropped-pins [["aaa" {:rdm {:pins ["x"]}}] ["bbb" {:rdm {:pins ["x"] :retired "r"}}]]))))
