(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as sources]))

(def idx [{:id "loyly" :repository "r" :pins ["17d68d2"] :fenced ["impl" "harness/fence.sh"]}])

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                                      "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"}}
                 "git" "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null" args)]
    (is (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(defn- checkout!
  "A sources directory holding a checkout of loyly with `files`, tagged 17d68d2."
  [files]
  (let [sdir (str (fs/create-temp-dir))
        co (fs/file sdir "loyly")]
    (doseq [f files]
      (fs/create-dirs (fs/parent (fs/file co f)))
      (spit (str (fs/file co f)) "x\n"))
    (git! co "init" "-q")
    (git! co "add" "-A")
    (git! co "commit" "-q" "-m" "c")
    (git! co "tag" "17d68d2")
    sdir))

(deftest citations-in-text
  (is (= [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2" :line 2}]
         (sources/citations-in "x\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\".")))
  (testing "a URL is not a citation, nor is a short hex"
    (is (empty? (sources/citations-in "https://github.com/a/b@1234567 and loyly:a.md@12ab")))))

(deftest resolving-without-a-checkout
  (testing "an unknown source"
    (let [r (sources/resolve-citation idx nil "innolitix:README.md@f21b508")]
      (is (= [:unresolved "citation-unknown-source"] ((juxt :status :rule) r)))
      (is (str/includes? (first (:reasons r)) "innolitix"))))
  (testing "a commit that is not a pin names the pins"
    (let [r (sources/resolve-citation idx nil "loyly:README.md@0000000")]
      (is (= "citation-not-pinned" (:rule r)))
      (is (str/includes? (first (:reasons r)) "17d68d2"))))
  (testing "a fenced path and a path inside a fenced directory"
    (is (= "citation-fenced" (:rule (sources/resolve-citation idx nil "loyly:harness/fence.sh@17d68d2"))))
    (is (str/includes? (first (:reasons (sources/resolve-citation idx nil "loyly:impl/a.clj@17d68d2")))
                       "fenced directory impl"))
    (is (nil? (sources/fence-of ["impl"] "implementation.md"))))
  (testing "without a checkout, a path is unchecked, with the reason"
    (let [r (sources/resolve-citation idx nil "loyly:README.md@17d68d2")]
      (is (= :unchecked (:status r)))
      (is (seq (:reasons r)))
      (is (= :warning (:severity (sources/citation-finding "the need UN-1" (assoc r :file "f" :line 1))))))))

(deftest resolving-against-a-checkout
  (let [sdir (checkout! ["README.md" "impl/a.clj"])]
    (is (= :resolved (:status (sources/resolve-citation idx sdir "loyly:README.md@17d68d2"))))
    (let [r (sources/resolve-citation idx sdir "loyly:docs/plan.md@17d68d2")
          f (sources/citation-finding "the need UN-1" (assoc r :file "dhf/spec/needs.edn" :line 3))]
      (is (= "citation-missing-path" (:rule r)))
      (is (= ["dhf/spec/needs.edn" 3 "citation-missing-path"] ((juxt :file :line :rule) f)))
      (is (str/includes? (:message f) "docs/plan.md does not exist at the pin 17d68d2")))
    (testing "a fenced path matching nothing at a pin"
      (let [fs* (sources/fence-findings "dhf/raw/sources.edn" "{:loyly\n {:fenced [\"impl\" \"harness/fence.sh\"]}}"
                                        sdir idx)]
        (is (= [["source-fence-unmatched" 2]] (map (juxt :rule :line) fs*)))
        (is (str/includes? (:message (first fs*)) "harness/fence.sh"))))))

(deftest index-rules
  (let [dir (fs/create-temp-dir)
        text "{:a {:repository \"r\" :record \"a.md\" :pins []}\n :b {:record \"b.md\" :pins [\"1234567\"]}}"]
    (spit (str (fs/file dir "a.md")) "a")
    (is (= #{["source-no-pin" 1] ["source-no-repository" 2] ["source-no-record" 2]}
           (set (map (juxt :rule :line)
                     (sources/source-findings "s.edn" text dir (sources/index-sources (read-string text))))))))
  (testing "a dropped pin"
    (let [fs* (sources/dropped-pin-findings "s.edn" "{:loyly {:pins [\"aaaaaaa\"]}}"
                                            [{:id "loyly" :pins ["17d68d2" "aaaaaaa"]}]
                                            [{:id "loyly" :pins ["aaaaaaa"]}])]
      (is (= ["source-pin-dropped"] (map :rule fs*)))
      (is (str/includes? (:message (first fs*)) "17d68d2")))))

(deftest need-citations-carry-their-need-and-line
  (let [dir (fs/create-temp-dir)
        f (fs/file dir "needs.edn")]
    (spit (str f) "{\"UN-001\" {:text \"t\"\n           :sources [\"loyly:README.md@17d68d2\"\n  \"nonsense\"]}}\n")
    (let [cs (sources/need-citations f "needs.edn" idx nil)]
      (is (= [["UN-001" 2 :unchecked] ["UN-001" 3 :unresolved]] (map (juxt :need :line :status) cs)))
      (is (= "citation-malformed" (:rule (second cs)))))))
