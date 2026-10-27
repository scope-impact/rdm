(ns rdm-spec.knowledge.sources-test
  (:require [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as s]))

(deftest parse-citation
  (is (= {:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"}
         (s/parse-citation "loyly:README.md@17d68d2")))
  (is (= "innolitics-rdm" (:source (s/parse-citation "innolitics-rdm:rdm/render.py@f21b508"))))
  (is (nil? (s/parse-citation "the loyly README")))
  (is (nil? (s/parse-citation "loyly:README.md")))
  (is (nil? (s/parse-citation "loyly:README.md@nothex"))))

(deftest page-citations
  (let [text "# P\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"A\".\n[^2]: `demo:a/b.md@abc1234` and `demo:c.md@abc1234`\n"]
    (is (= [["loyly:README.md@17d68d2" 5] ["demo:a/b.md@abc1234" 6] ["demo:c.md@abc1234" 6]]
           (map (juxt :citation :line) (s/page-citations text)))))
  (testing "only footnotes are read"
    (is (empty? (s/page-citations "A claim cites `loyly:README.md@17d68d2` in passing.\n")))))

(deftest fenced
  (is (= "harness/fence.sh" (s/fenced-by ["impl" "harness/fence.sh"] "harness/fence.sh")))
  (is (= "rdm" (s/fenced-by ["rdm" "tests"] "rdm/render.py")))
  (is (nil? (s/fenced-by ["rdm"] "rdmx/render.py")))
  (is (= "." (s/fenced-by ["."] "README.md")))
  (is (nil? (s/fenced-by [] "README.md"))))

(deftest pins
  (is (= "17d68d2" (s/pin-of ["17d68d2"] "17d68d2")))
  (is (= "17d68d2" (s/pin-of ["17d68d2"] "17d68d2aaaaaaa")))
  (is (nil? (s/pin-of ["17d68d2"] "0000000")))
  (is (nil? (s/pin-of [] "17d68d2"))))

(deftest dropped-pins
  (testing "a pin replaced is dropped, by the commit that replaced it"
    (is (= [{:source :loyly :pin "17d68d2" :commit "c2"}]
           (s/dropped-pins [["c1" {:loyly {:pins ["17d68d2"]}}] ["c2" {:loyly {:pins ["0a1b2c3"]}}]]
                           {:loyly {:pins ["0a1b2c3"]}}))))
  (testing "a pin dropped and later restored is not dropped (spec-0365)"
    (is (empty? (s/dropped-pins [["c1" {:rdm {:pins ["3763e08"]}}] ["c2" {}]
                                 ["c3" {:rdm {:pins ["3763e08"] :retired "r"}}]]
                                {:rdm {:pins ["3763e08"] :retired "r"}}))))
  (testing "a source deleted drops its pins; the working tree is the dropper when no commit is"
    (is (= [{:source :rdm :pin "3763e08" :commit "c2"}]
           (s/dropped-pins [["c1" {:rdm {:pins ["3763e08"]}}] ["c2" {}]] {})))
    (is (= [{:source :rdm :pin "3763e08" :commit nil}]
           (s/dropped-pins [["c1" {:rdm {:pins ["3763e08"]}}]] {}))))
  (testing "a pin dropped, restored, dropped again names the last dropper"
    (is (= [{:source :a :pin "1111111" :commit "c4"}]
           (s/dropped-pins [["c1" {:a {:pins ["1111111"]}}] ["c2" {:a {:pins []}}]
                            ["c3" {:a {:pins ["1111111"]}}] ["c4" {:a {:pins []}}]]
                           {:a {:pins []}}))))
  (testing "an unreadable commit of the index is skipped"
    (is (empty? (s/dropped-pins [["c1" nil] ["c2" {:a {:pins ["1"]}}]] {:a {:pins ["1"]}})))))

(deftest line-of-key
  (is (= 3 (s/key-line ";; :loyly in a comment\n{:demo {}\n :loyly {:pins []}}\n" :loyly)))
  (is (nil? (s/key-line "{:demo {}}" :loyly))))
