(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.knowledge.sources :as sources]))

(defn- with-tmp
  "Calls (f dir) with a new temporary directory, removed afterwards."
  [f]
  (let [d (str (fs/create-temp-dir {:prefix "sources-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(defn- git! [dir & args]
  (apply p/shell {:dir dir :out :string :err :string
                  :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                              "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"
                              "GIT_CONFIG_GLOBAL" "/dev/null"}}
         "git" "-c" "commit.gpgsign=false" args))

(def index
  [{:id "loyly" :repository "r" :pins ["17d68d2"] :fenced ["impl" "harness/fence.sh"]}
   {:id "rdm" :repository "r" :pins ["3763e08"] :fenced ["."] :retired "holds an implementation"}])

(defn- status [c & [dir]] ((juxt :status :rule) (sources/resolve-citation index dir c)))

(deftest citations-are-read-from-text
  (is (= [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2" :line 2}]
         (sources/citations-in "No citation: https://example.org/a@abcdef.\n[^1]: `loyly:README.md@17d68d2`, \"S\".")))
  (is (nil? (sources/parse-citation "the loyly README")))
  (is (= "docs/a.md" (:path (sources/parse-citation " loyly:docs/a.md@17d68d2 ")))))

(deftest a-citation-resolves-or-says-why-not
  (is (= [:unresolved "citation-malformed"] (status "the loyly README")))
  (is (= [:unresolved "citation-unknown-source"] (status "innolitix:README.md@f21b508")))
  (is (= [:unresolved "citation-retired"] (status "rdm:README.md@3763e08")))
  (is (= [:unresolved "citation-unpinned"] (status "loyly:README.md@0000000")))
  (is (= [:unresolved "citation-fenced"] (status "loyly:harness/fence.sh@17d68d2")))
  (is (= [:unresolved "citation-fenced"] (status "loyly:impl/a/b.clj@17d68d2")))
  (is (= [:unchecked nil] (status "loyly:implementation.md@17d68d2")))
  (testing "the reasons name what is wrong"
    (is (str/includes? (first (:reasons (sources/resolve-citation index nil "loyly:README.md@0000000"))) "17d68d2"))
    (is (str/includes? (first (:reasons (sources/resolve-citation index nil "loyly:impl/x.clj@17d68d2"))) "impl"))))

(deftest a-checkout-decides-whether-the-path-exists
  (with-tmp
    (fn [dir]
      (let [co (str (fs/file dir "loyly"))]
        (fs/create-dirs (fs/file co "docs"))
        (spit (str (fs/file co "README.md")) "read me\n")
        (git! co "init" "-q")
        (git! co "add" "-A")
        (git! co "commit" "-q" "-m" "one")
        (git! co "tag" "17d68d2")
        (is (= [:resolved nil] (status "loyly:README.md@17d68d2" dir)))
        (is (= [:unresolved "citation-path-missing"] (status "loyly:docs/plan.md@17d68d2" dir)))
        (is (= :unchecked (:status (sources/resolve-citation
                                    [{:id "demo" :repository "r" :pins ["abcd123"] :fenced []}] dir
                                    "demo:README.md@abcd123"))))
        (testing "a fenced path that matches nothing at a pin"
          (is (= ["fenced-path-missing"]
                 (map :rule (sources/fenced-findings "dhf/raw/sources.edn"
                                                     [{:id "loyly" :pins ["17d68d2"] :fenced ["README.md" "gone.sh"]}]
                                                     dir)))))))))

(deftest a-finding-names-who-cites
  (let [c (assoc (sources/resolve-citation index nil "loyly:impl/a.clj@17d68d2") :file "f.md" :line 3)
        u (assoc (sources/resolve-citation index nil "loyly:README.md@17d68d2") :file "f.md" :line 4)]
    (is (str/includes? (:message (sources/citation-finding "the need UN-004" c)) "the need UN-004 cites loyly:impl/a.clj@17d68d2"))
    (is (= :warning (:severity (sources/citation-finding "x" u))))
    (is (nil? (sources/citation-finding "x" {:status :resolved})))))

(deftest the-index-is-read-and-held-to-its-rules
  (let [{:keys [sources error]} (sources/read-index "{:a {:repository \"r\" :record \"a.md\" :pins [\"abc1\"]}\n :b {:pins []}}")]
    (is (nil? error))
    (is (= [1 2] (map :line sources)))
    (is (= #{"source-no-repository" "source-no-pin" "source-no-record"}
           (set (map :rule (sources/index-findings "i.edn" "dhf/raw" sources #(= "a.md" %))))))
    (is (= ["source-no-record"]
           (map :rule (sources/index-findings "i.edn" "dhf/raw" (take 1 sources) (constantly false))))))
  (is (some? (:error (sources/read-index "[1 2]")))))

(deftest a-dropped-pin-is-found-with-the-commit-that-dropped-it
  (let [v1 {:commit "c1" :sources [{:id "loyly" :pins ["17d68d2"]} {:id "rdm" :pins ["3763e08"]}]}
        v2 {:commit "c2" :sources [{:id "rdm" :pins ["3763e08"]}]}]
    (is (= [{:source "loyly" :pin "17d68d2" :commit "c2"}] (sources/dropped-pins [v1 v2] (:sources v2))))
    (is (= [{:source "loyly" :pin "17d68d2" :commit nil}] (sources/dropped-pins [v1] (:sources v2))))
    (is (empty? (sources/dropped-pins [v1 v2] (:sources v1))))))
