(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as sources]))

(def idx {:loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md"
                  :pins ["17d68d2"] :fenced ["impl" "harness/fence.sh"]}
          :rdm {:repository "https://github.com/scope-impact/rdm" :record "rdm.md"
                :pins ["3763e08"] :retired "holds today's implementation" :fenced ["."]}})

(def srcs (sources/index->sources idx))

(defn- resolve* [c & [dir]] (sources/resolve-citation srcs dir c))

(deftest citations
  (is (= {:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"}
         (sources/parse-citation "loyly:README.md@17d68d2")))
  (is (nil? (sources/parse-citation "the loyly README")))
  (is (= [["loyly:a/b.md@17d68d2" 2] ["x:y@abcd" 2]]
         (map (juxt :citation :line)
              (sources/citations-in "See https://x.org/a@beef.\n[^1]: `loyly:a/b.md@17d68d2`, \"S\"; x:y@abcd.")))))

(deftest the-index
  (is (= ["loyly" "rdm"] (map :id srcs)))
  (is (= "holds today's implementation" (:retired (second srcs))))
  (is (:error (sources/read-index "[1 2]")))
  (is (= {:index {}} (sources/read-index nil)))
  (let [s (sources/index->sources {:x {:repository "" :record "x.md" :pins []}})
        fs* (sources/index-findings "dhf/raw/sources.edn" "{:x {}}" s (constantly false))]
    (is (= #{"source-no-repository" "source-no-pin" "source-no-record"} (set (map :rule fs*))))
    (is (every? #(= 1 (:line %)) fs*))))

(defn- are-rules [cases]
  (doseq [[c rule why] cases
          :let [r (resolve* c)]]
    (is (= [:unresolved rule] [(:status r) (:rule r)]) c)
    (is (str/includes? (str/join " " (:reasons r)) why) c)))

(deftest resolving-without-a-checkout
  (testing "each failure names its rule and why"
    (are-rules [["innolitix:README.md@f21b508" "citation-unknown-source" "innolitix"]
                ["rdm:README.md@3763e08" "citation-retired" "retired"]
                ["loyly:README.md@0000000" "citation-unpinned" "17d68d2"]
                ["loyly:harness/fence.sh@17d68d2" "citation-fenced" "harness/fence.sh"]
                ["loyly:impl/a.clj@17d68d2" "citation-fenced" "inside impl"]
                ["the loyly README" "citation-malformed" "not a citation"]]))
  (testing "a pin may be abbreviated either way"
    (is (= :unchecked (:status (resolve* "loyly:README.md@17d68d2abc")))))
  (testing "no checkout: unchecked, with the reason, never resolved"
    (let [r (resolve* "loyly:README.md@17d68d2")]
      (is (= :unchecked (:status r)))
      (is (str/includes? (first (:reasons r)) "no checkout of loyly")))))

(defn- git! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string
                  :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                              "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                              "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"}}
         "git" "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null" args))

(deftest resolving-against-a-checkout
  (let [dir (fs/create-temp-dir)
        co (fs/file dir "loyly")]
    (try
      (fs/create-dirs (fs/file co "impl"))
      (spit (str (fs/file co "README.md")) "r\n")
      (spit (str (fs/file co "impl" "a.clj")) "a\n")
      (git! co "init" "-q")
      (git! co "add" "-A")
      (git! co "commit" "-q" "-m" "c")
      (git! co "tag" "17d68d2")
      (is (= :resolved (:status (resolve* "loyly:README.md@17d68d2" (str dir)))))
      (let [r (resolve* "loyly:docs/plan.md@17d68d2" (str dir))]
        (is (= [:unresolved "citation-path-missing"] [(:status r) (:rule r)]))
        (is (str/includes? (first (:reasons r)) "docs/plan.md does not exist at 17d68d2")))
      (testing "a fenced path matching nothing at a pin"
        (let [fs* (sources/fenced-findings "dhf/raw/sources.edn" "" srcs (str dir))]
          (is (= [["source-fenced-missing" true]]
                 (map (juxt :rule #(str/includes? (:message %) "harness/fence.sh")) fs*)))))
      (finally (fs/delete-tree dir)))))

(deftest pins-are-never-dropped
  (let [v1 {:commit "aaaaaaa1" :index {:loyly {:pins ["17d68d2"]} :rdm {:pins ["3763e08"]}}}
        v2 {:commit "bbbbbbb2" :index {:loyly {:pins ["a1b2c3d"]} :rdm {:pins ["3763e08"] :retired "r"}}}
        now {:commit nil :index (:index v2)}]
    (is (= [{:source "loyly" :pin "17d68d2" :commit "bbbbbbb2"}] (sources/dropped-pins [v1 v2 now])))
    (is (= [{:source "loyly" :pin "17d68d2" :commit nil}] (sources/dropped-pins [v1 now])))
    (is (empty? (sources/dropped-pins [v1 {:commit nil :index (:index v1)}])))))

(deftest a-finding-names-where-and-why
  (let [c (merge (resolve* "rdm:README.md@3763e08") {:file "dhf/spec/needs.edn" :line 4})
        f (sources/citation-finding "UN-004" c)]
    (is (= ["dhf/spec/needs.edn" 4 "citation-retired" :error] ((juxt :file :line :rule :severity) f)))
    (is (str/starts-with? (:message f) "UN-004 cites rdm:README.md@3763e08: ")))
  (is (= :warning (:severity (sources/citation-finding "UN-1" (resolve* "loyly:README.md@17d68d2")))))
  (is (nil? (sources/citation-finding "UN-1" {:status :resolved}))))

(deftest need-citations-carry-their-lines
  (is (= [{:need "UN-001" :cited "a:b@1234" :line 2} {:need "UN-002" :cited "the README" :line 4}]
         (sources/need-citations "{\"UN-001\" {:text \"t\"\n :sources [\"a:b@1234\"]}\n \"UN-002\" {:text \"u\"\n :sources [\"the README\"]}}"))))
