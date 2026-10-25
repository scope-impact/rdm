(ns rdm-spec.knowledge.sources-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as sources]))

(def index-text
  "{:loyly {:repository \"https://github.com/skallinen/loyly\" :record \"loyly.md\"
          :pins [\"17d68d2\"] :fenced [\"impl\" \"harness/fence.sh\"]}
 :rdm {:repository \"https://github.com/scope-impact/rdm\" :record \"rdm.md\" :pins [\"3763e08\"]
       :retired \"holds an implementation\" :fenced []}}")

(def by-id (into {} (map (juxt :id identity)) (:sources (sources/parse-index "sources.edn" index-text))))

(defn- rules-of [citation]
  (let [[_ s p c] (re-matches sources/need-citation-re citation)]
    (map :rule (:reasons (sources/resolve-citation {:source s :path p :commit c} by-id nil nil)))))

(deftest the-index-is-read-with-lines
  (is (= 1 (:line (by-id "loyly"))))
  (is (= 3 (:line (by-id "rdm"))))
  (is (:error (sources/parse-index "s.edn" "[1 2]"))))

(deftest source-records
  (let [rules (fn [s] (set (map :rule (sources/source-findings "s.edn" [s] #{"ok.md"}))))]
    (is (empty? (rules {:id "a" :repository "r" :record "ok.md" :pins ["abc1234"]})))
    (is (= #{"source-no-pin"} (rules {:id "a" :repository "r" :record "ok.md" :pins []})))
    (is (= #{"source-no-repository"} (rules {:id "a" :record "ok.md" :pins ["abc1234"]})))
    (is (= #{"source-no-record"} (rules {:id "a" :repository "r" :record "gone.md" :pins ["abc1234"]})))))

(deftest resolving
  (is (= ["citation-unchecked"] (rules-of "loyly:README.md@17d68d2")) "without checkouts a path is unchecked")
  (is (= ["citation-unknown-source"] (rules-of "innolitix:README.md@f21b508")))
  (is (= ["citation-pin"] (rules-of "loyly:README.md@0000000")))
  (is (= ["citation-fenced"] (rules-of "loyly:harness/fence.sh@17d68d2")))
  (is (= ["citation-fenced"] (rules-of "loyly:impl/core.clj@17d68d2")) "a path inside a fenced directory")
  (is (= ["citation-unchecked"] (rules-of "loyly:implementation.md@17d68d2")) "a sibling of a fenced directory is not fenced")
  (is (some #{"citation-retired"} (rules-of "rdm:README.md@3763e08"))))

(deftest pins-are-never-dropped
  (let [v1 "{:loyly {:pins [\"17d68d2\"]}}"
        v2 "{:loyly {:pins [\"abcdef0\"]}}"
        current (:sources (sources/parse-index "s" v2))]
    (is (= [{:id "loyly" :pin "17d68d2" :commit "c2"}]
           (sources/dropped-pins [["c1" v1] ["c2" v2]] current)))
    (testing "a pin kept, or a source retired with its pins, drops nothing"
      (let [v3 "{:loyly {:pins [\"17d68d2\"] :retired \"why\"}}"]
        (is (empty? (sources/dropped-pins [["c1" v1] ["c2" v3]] (:sources (sources/parse-index "s" v3)))))))))

(deftest need-citations
  (let [cs (sources/need-citations "needs.edn" "{\"UN-001\" {:text \"A\"\n :sources [\"loyly:README.md@17d68d2\"\n \"the loyly README\"]}}")]
    (is (= ["loyly" nil] (map :source cs)))
    (is (= [2 3] (map :line cs)))
    (is (:malformed? (second cs)))))
