(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as sources]))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                                      "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"}}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git failed: " (:err r)) {})))
    (str/trim (:out r))))

(defn- write! [dir rel text]
  (fs/create-dirs (fs/parent (fs/file dir rel)))
  (spit (str (fs/file dir rel)) text))

(defn- checkout!
  "A sources directory holding a checkout of `id` with `files`, tagged `pin`."
  [id pin files]
  (let [sdir (str (fs/create-temp-dir))
        co (fs/file sdir id)]
    (doseq [f files] (write! co f "x\n"))
    (git! co "init" "-q")
    (git! co "add" "-A")
    (git! co "commit" "-q" "-m" "c")
    (git! co "tag" pin)
    sdir))

(def index [{:id "loyly" :repository "r" :pins ["17d68d2"] :fenced ["impl" "harness/fence.sh"]}])

(deftest citations-are-read-from-text
  (is (= [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2" :line 2}]
         (sources/citations-in "See https://x.org/a@17d68d2.\n[^1]: `loyly:README.md@17d68d2`, \"S\".")))
  (is (nil? (sources/parse-citation "loyly:README.md")))
  (is (= "a/b.md" (:path (sources/parse-citation "src:a/b.md@abcd123")))))

(deftest the-index-is-read-with-lines
  (let [{:keys [sources error]} (sources/read-index ";; :loyly in a comment\n{:loyly\n {:repository \"r\" :pins [\"17d68d2\"]}\n :demo {:repository \"d\"}}")]
    (is (nil? error))
    (is (= [["loyly" 2 ["17d68d2"]] ["demo" 4 []]] (map (juxt :id :line :pins) sources))))
  (is (:error (sources/read-index "[1 2]")))
  (is (:error (sources/read-index "{:a"))))

(deftest fences
  (is (= "impl" (sources/fenced-by ["impl"] "impl/a.clj")))
  (is (= "harness/fence.sh" (sources/fenced-by ["harness/fence.sh"] "harness/fence.sh")))
  (is (nil? (sources/fenced-by ["impl"] "implementation.md"))))

(deftest resolving
  (let [sdir (checkout! "loyly" "17d68d2" ["README.md" "impl/a.clj" "harness/fence.sh"])
        r #(sources/resolve-citation index sdir %)]
    (testing "statuses and rules"
      (is (= [:resolved nil] ((juxt :status :rule) (r "loyly:README.md@17d68d2"))))
      (is (= [:unresolved "citation-path-missing"] ((juxt :status :rule) (r "loyly:docs/plan.md@17d68d2"))))
      (is (= [:unresolved "citation-unpinned"] ((juxt :status :rule) (r "loyly:README.md@0000000"))))
      (is (= [:unresolved "citation-unknown-source"] ((juxt :status :rule) (r "innolitix:README.md@f21b508"))))
      (is (= [:unresolved "citation-fenced"] ((juxt :status :rule) (r "loyly:impl/a.clj@17d68d2"))))
      (is (= [:unresolved "citation-malformed"] ((juxt :status :rule) (r "loyly:README.md")))))
    (testing "reasons name what is wrong"
      (is (str/includes? (first (:reasons (r "loyly:README.md@0000000"))) "17d68d2"))
      (is (str/includes? (first (:reasons (r "loyly:impl/a.clj@17d68d2"))) "fenced directory impl")))
    (testing "without a checkout, a path is unchecked, never resolved"
      (let [c (sources/resolve-citation index nil "loyly:README.md@17d68d2")]
        (is (= :unchecked (:status c)))
        (is (re-find #"no checkout of loyly" (first (:reasons c))))))
    (testing "a checkout that does not hold the pin leaves it unchecked"
      (let [other (checkout! "loyly" "1111111" ["README.md"])
            idx [{:id "loyly" :pins ["17d68d2"] :fenced []}]]
        (is (= :unchecked (:status (sources/resolve-citation idx other "loyly:README.md@17d68d2"))))))))

(deftest findings-of-citations
  (let [c {:citation "x:a@1234567" :file "f" :line 3}]
    (is (nil? (sources/citation-finding "UN-001" (assoc c :status :resolved))))
    (is (= :warning (:severity (sources/citation-finding "UN-001" (assoc c :status :unchecked :reasons ["not checked: why"])))))
    (let [f (sources/citation-finding "the footnote [^1]" (assoc c :status :unresolved :rule "citation-unpinned" :reasons ["r"]))]
      (is (= ["f" 3 "citation-unpinned" :error] ((juxt :file :line :rule :severity) f)))
      (is (str/starts-with? (:message f) "the footnote [^1] cites x:a@1234567")))))

(deftest dropped-pins-across-history
  (let [dir (str (fs/create-temp-dir))
        rel "dhf/raw/sources.edn"]
    (write! dir rel "{:loyly {:pins [\"17d68d2\"]}}")
    (git! dir "init" "-q") (git! dir "add" "-A") (git! dir "commit" "-q" "-m" "first")
    (write! dir rel "{:loyly {:pins [\"abcdef1\"]}}")
    (git! dir "commit" "-q" "-am" "drop")
    (let [dropper (git! dir "rev-parse" "HEAD")]
      (write! dir rel "{:loyly {:pins [\"abcdef1\"]} :x {:pins []}}")
      (git! dir "commit" "-q" "-am" "later")
      (testing "a pin dropped by an earlier commit is found, with that commit"
        (is (= [{:source "loyly" :pin "17d68d2" :commit dropper :subject "drop"}]
               (sources/dropped-pins dir rel {"loyly" #{"abcdef1"} "x" #{}}))))
      (testing "a pin listed again is no longer dropped"
        (is (empty? (sources/dropped-pins dir rel {"loyly" #{"abcdef1" "17d68d2"} "x" #{}}))))
      (testing "a pin dropped in the working tree is found, with no commit"
        (is (= [{:source "loyly" :pin "abcdef1" :commit nil :subject "the working tree"}]
               (filter #(= "abcdef1" (:pin %)) (sources/dropped-pins dir rel {"loyly" #{"17d68d2"} "x" #{}}))))))
    (testing "no repository, no history"
      (is (empty? (sources/dropped-pins (str (fs/create-temp-dir)) rel {}))))))
