(ns rdm-spec.knowledge.sources-test
  "One test per rule Sources enforces (03-ground-the-spec/sources.feature)."
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.knowledge.sources :as sources]))

(def idx {:loyly {:repository "r" :record "loyly.md" :pins ["17d68d2"] :fenced ["harness/fence.sh" "impl"]}
          :rdm {:repository "r" :record "rdm.md" :pins ["3763e08"] :retired "old" :fenced ["."]}})

(deftest a-source-is-indexed-with-its-repository-pins-and-record
  (is (= #{"source-no-pin" "source-no-repository" "source-no-record"}
         (set (map :rule (sources/source-findings {:a {:record "a.md"} :b {:repository "r" :pins ["1"] :record "b.md"}} #{"b.md"} "s.edn" "")))))
  (is (= [["loyly" "17d68d2" "c2"]]
         (sources/dropped-pins [["c1" {:loyly {:pins ["17d68d2"]}}] ["c2" {:loyly {:pins ["aaaaaaa"]}}]] {:loyly {:pins ["aaaaaaa"]}})))
  (is (empty? (sources/dropped-pins [["c1" {:rdm {:pins ["3763e08"]}}] ["c2" {}] ["c3" {:rdm {:pins ["3763e08"]}}]] {:rdm {:pins ["3763e08"]}}))))

(deftest a-citation-resolves-to-a-source-a-pin-and-a-path
  (is (= {:source "loyly" :path "README.md" :commit "17d68d2"} (sources/parse-citation "loyly:README.md@17d68d2")))
  (is (nil? (sources/parse-citation "the loyly README")))
  (is (= :unknown-source (:why (sources/resolve-citation (sources/parse-citation "innolitix:README.md@f21b508") idx nil))))
  (is (= :unpinned (:why (sources/resolve-citation (sources/parse-citation "loyly:README.md@0000000") idx nil))))
  (is (= :unchecked (:status (sources/resolve-citation (sources/parse-citation "loyly:README.md@17d68d2") idx nil)))))

(deftest a-sources-implementation-is-fenced
  (is (= :fenced (:why (sources/resolve-citation (sources/parse-citation "loyly:harness/fence.sh@17d68d2") idx nil))))
  (is (= :fenced (:why (sources/resolve-citation (sources/parse-citation "loyly:impl/x.clj@17d68d2") idx nil)))))

(deftest citations-are-read-from-the-needs-and-the-wiki
  (is (= ["loyly:README.md@17d68d2"] (map :citation (sources/footnote-citations "p.md" "text[^a]\n\n[^a]: `loyly:README.md@17d68d2`, \"x\".\n")))))

(deftest a-needs-citations-resolve
  (is (= :retired (:why (sources/resolve-citation (sources/parse-citation "rdm:README.md@3763e08") idx nil)))))
