(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as sources]))

(def idx
  {:loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"]
           :fenced ["impl" "harness/fence.sh"]}
   :rdm {:repository "https://github.com/scope-impact/rdm" :record "rdm.md" :pins ["3763e08"]
         :retired "holds today's implementation" :fenced ["."]}})

(def srcs (sources/index-sources idx))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                                      "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"}}
                 "git" "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null" args)]
    (is (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(defn- write! [dir rel text]
  (fs/create-dirs (fs/parent (fs/file dir rel)))
  (spit (str (fs/file dir rel)) text))

(deftest citations-are-found-with-their-lines
  (is (= [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2" :line 2}
          {:citation "a-b:x/y.md@abcdef0" :source "a-b" :path "x/y.md" :commit "abcdef0" :line 3}]
         (sources/citations-in "No citation here.\n[^1]: `loyly:README.md@17d68d2`, \"S\".\n(a-b:x/y.md@abcdef0)\n")))
  (is (empty? (sources/citations-in "https://example.org/a@b and C:\\path"))))

(deftest the-index-is-read-sorted
  (is (= ["loyly" "rdm"] (map :id srcs)))
  (is (= "holds today's implementation" (:retired (second srcs))))
  (is (nil? (:retired (first srcs)))))

(deftest index-rules
  (let [fs* (sources/index-findings "dhf/raw/sources.edn" "{:a\n {:repository \"r\"}\n :b {:pins [\"1234567\"]}}"
                                    (sources/index-sources {:a {:repository "r"} :b {:pins ["1234567"]}}))]
    (is (= [["sources-no-pin" 1] ["sources-no-repository" 3]] (map (juxt :rule :line) fs*)))
    (is (str/includes? (:message (first fs*)) "a"))))

(deftest dropped-pins
  (testing "a pin listed once and listed no more is dropped, by the commit that dropped it"
    (is (= [{:source "loyly" :pin "17d68d2" :commit "bbb"}]
           (sources/dropped-pins [{:commit "aaa" :index {:loyly {:pins ["17d68d2"]}}}
                                  {:commit "bbb" :index {:loyly {:pins ["1111111"]}}}]
                                 {:loyly {:pins ["1111111"]}}))))
  (testing "a dropped source drops its pins; the working tree can drop one too"
    (is (= [{:source "loyly" :pin "17d68d2" :commit :working-tree}]
           (sources/dropped-pins [{:commit "aaa" :index {:loyly {:pins ["17d68d2"]}}}] {}))))
  (testing "a retired source keeps its pins, so drops nothing; a pin restored is not dropped"
    (is (empty? (sources/dropped-pins [{:commit "aaa" :index idx}
                                       {:commit "bbb" :index (dissoc idx :loyly)}]
                                      idx)))))

(deftest resolving-without-a-checkout
  (let [r #(sources/resolve-citation srcs nil %)]
    (is (= :unchecked (:status (r "loyly:README.md@17d68d2"))))
    (is (seq (:reasons (r "loyly:README.md@17d68d2"))))
    (is (= "citation-unknown-source" (:rule (r "innolitix:README.md@f21b508"))))
    (is (= "citation-unpinned" (:rule (r "loyly:README.md@0000000"))))
    (is (= :unchecked (:status (r "loyly:README.md@17d68d2abcdef")))
        "a longer commit names its short pin")
    (is (= "citation-fenced" (:rule (r "loyly:harness/fence.sh@17d68d2"))))
    (is (str/includes? (first (:reasons (r "loyly:harness/fence.sh@17d68d2"))) "fenced path"))
    (is (str/includes? (first (:reasons (r "loyly:impl/a.clj@17d68d2"))) "fenced directory impl"))
    (is (= :unchecked (:status (r "loyly:implementation.md@17d68d2"))) "a prefix of a name is not inside it")
    (is (= "citation-retired" (:rule (r "rdm:README.md@3763e08"))))
    (is (every? #(= :unresolved (:status (r %)))
                ["innolitix:README.md@f21b508" "loyly:README.md@0000000" "rdm:README.md@3763e08"]))))

(deftest resolving-against-a-checkout
  (let [sdir (str (fs/create-temp-dir))
        co (fs/file sdir "loyly")]
    (write! co "README.md" "x")
    (write! co "impl/a.clj" "x")
    (git! co "init" "-q")
    (git! co "add" "-A")
    (git! co "commit" "-q" "-m" "c")
    (git! co "tag" "17d68d2")
    (is (= :resolved (:status (sources/resolve-citation srcs sdir "loyly:README.md@17d68d2"))))
    (let [r (sources/resolve-citation srcs sdir "loyly:docs/plan.md@17d68d2")]
      (is (= ["citation-path-missing" :unresolved] ((juxt :rule :status) r)))
      (is (str/includes? (first (:reasons r)) "docs/plan.md")))
    (testing "a retired source fails before its checkout is looked for"
      (is (= "citation-retired" (:rule (sources/resolve-citation srcs sdir "rdm:README.md@3763e08")))))
    (testing "no checkout of a source in the directory: unchecked"
      (let [srcs* (sources/index-sources (assoc idx :demo {:repository "r" :pins ["abcdef0"]}))]
        (is (= :unchecked (:status (sources/resolve-citation srcs* sdir "demo:README.md@abcdef0"))))))
    (testing "fenced paths that match nothing at a pin"
      (let [fs* (sources/fence-findings "dhf/raw/sources.edn" "{:loyly {}}" srcs sdir)]
        (is (= [["sources-fenced-missing" "harness/fence.sh"]]
               (map (juxt :rule #(re-find #"harness/fence\.sh" (:message %))) fs*)))
        (is (str/includes? (:message (first fs*)) "17d68d2"))))))

(deftest findings-of-citations
  (let [c (merge (sources/resolve-citation srcs nil "loyly:README.md@17d68d2") {:file "f" :line 3 :need "UN-001"})
        w (sources/citation-finding "the need UN-001" c)]
    (is (= [:warning "citation-unchecked" "f" 3] ((juxt :severity :rule :file :line) w)))
    (is (str/includes? (:message w) "not checked")))
  (is (nil? (sources/citation-finding "x" {:status :resolved})))
  (let [c (merge (sources/resolve-citation srcs nil "loyly:impl/a.clj@17d68d2") {:file "f" :line 5})
        f (sources/citation-finding "the footnote [^x]" c)]
    (is (= :error (:severity f)))
    (is (str/starts-with? (:message f) "the footnote [^x] cites loyly:impl/a.clj@17d68d2"))))

(deftest needs-citations
  (let [text ";; a comment citing loyly:README.md@17d68d2\n{\"UN-001\" {:text \"t\"\n :sources [\"loyly:README.md@17d68d2\"\n \"rdm:x.md@3763e08\"]}\n \"UN-002\" {:sources [\"no-at-all\"]}}\n"
        cs (sources/need-citations text)]
    (is (= [["loyly:README.md@17d68d2" "UN-001" 3] ["rdm:x.md@3763e08" "UN-001" 4]]
           (map (juxt :citation :need :line) cs)))
    (is (= [["UN-002" "no-at-all"]]
           (map (juxt :need :source) (sources/malformed-need-sources (read-string (subs text (str/index-of text "{")))))))))

(deftest the-check-of-a-project
  (let [dir (str (fs/create-temp-dir))]
    (write! dir "dhf/raw/sources.edn" (pr-str idx))
    (write! dir "dhf/spec/needs.edn" "{\"UN-001\" {:text \"t\" :sources [\"loyly:README.md@17d68d2\"\n \"rdm:README.md@3763e08\"]}}\n")
    (let [published (atom nil)
          out (with-redefs [rdm-spec.kernel.command/publish! (fn [_ p e payload] (reset! published [p e payload]))]
                (sources/check {:root dir :config {:raw-dir "dhf/raw" :needs "dhf/spec/needs.edn" :wiki-dir "dhf/wiki"}
                                :opts {}}))
          rules (map :rule (:findings out))]
      (is (= ["citation-retired" "citation-unchecked"] (sort rules)))
      (is (= [:knowledge/sources :KnowledgeIndexed] (take 2 @published)))
      (is (= #{:revision :sources :pages :citations :findings} (set (keys (nth @published 2)))))
      (is (= ["loyly" "rdm"] (map :id (:sources (:knowledge (:result out))))))
      (is (= [:unchecked :unresolved] (map :status (:citations (:knowledge (:result out)))))))))

(deftest a-missing-sources-directory-cannot-run
  (let [dir (str (fs/create-temp-dir))]
    (write! dir "dhf/raw/sources.edn" "{}")
    (is (thrown-with-msg? Exception #"does not exist"
                          (sources/check {:root dir :config {:raw-dir "dhf/raw"} :opts {:sources "nowhere"}})))))
