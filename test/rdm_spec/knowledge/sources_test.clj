(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.knowledge.sources :as sources]))

(def recorded
  [{:id "loyly" :repository "https://example.org/loyly" :pins ["17d68d2"] :fenced ["impl" "harness/fence.sh"]}
   {:id "old" :repository "https://example.org/old" :pins ["3763e08"] :fenced ["."] :retired "holds an implementation"}])

(defn- with-tmp [f]
  (let [d (str (fs/create-temp-dir {:prefix "sources-test-"}))]
    (try (f d) (finally (fs/delete-tree d)))))

(defn- git! [dir & args]
  (apply p/shell {:dir dir :out :string :err :string
                  :extra-env {"GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@t.invalid"
                              "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@t.invalid"}}
         "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" args))

(defn- write! [dir rel text]
  (fs/create-dirs (fs/parent (fs/file dir rel)))
  (spit (str (fs/file dir rel)) text))

(deftest citations-are-read-from-text
  (is (= [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2" :line 2}]
         (sources/citations-in "A claim.\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\".\n")))
  (is (empty? (sources/citations-in "the loyly README, and an address me@example.org")))
  (is (= {:source "a-b" :path "docs/x.md" :commit "abc1234"} (sources/parse-citation "a-b:docs/x.md@abc1234")))
  (is (nil? (sources/parse-citation "the loyly README"))))

(deftest a-citation-resolves-in-order
  (let [rule (fn [c] (:rule (sources/resolve-citation recorded nil c)))]
    (is (= "citation-malformed" (rule "the loyly README")))
    (is (= "citation-unknown-source" (rule "innolitix:README.md@f21b508")))
    (is (= "citation-retired" (rule "old:README.md@3763e08")))
    (is (= "citation-fenced" (rule "loyly:harness/fence.sh@17d68d2")))
    (is (= "citation-fenced" (rule "loyly:impl/booking.clj@17d68d2")))
    (is (nil? (rule "loyly:implementation.md@17d68d2")))
    (is (= "citation-unpinned" (rule "loyly:README.md@0000000")))
    (is (= :unchecked (:status (sources/resolve-citation recorded nil "loyly:README.md@17d68d2"))))
    (is (= :unchecked (:status (sources/resolve-citation recorded nil "loyly:README.md@17d68d2abcdef"))))))

(deftest a-checkout-resolves-the-path-at-its-pin
  (with-tmp
    (fn [dir]
      (let [co (str (fs/file dir "loyly"))]
        (write! co "README.md" "x\n")
        (git! co "init" "-q")
        (git! co "add" "-A")
        (git! co "commit" "-q" "-m" "one")
        (git! co "tag" "17d68d2")
        (is (= :resolved (:status (sources/resolve-citation recorded dir "loyly:README.md@17d68d2"))))
        (let [r (sources/resolve-citation recorded dir "loyly:docs/plan.md@17d68d2")]
          (is (= ["citation-path-missing" :unresolved] [(:rule r) (:status r)]))
          (is (str/includes? (first (:reasons r)) "docs/plan.md")))
        (is (= :unchecked (:status (sources/resolve-citation
                                    (conj recorded {:id "other" :repository "r" :pins ["abc1234"] :fenced []})
                                    dir "other:README.md@abc1234"))))))))

(deftest a-citation-finding-names-where-it-is-cited
  (is (nil? (sources/citation-finding "the page" {:status :resolved})))
  (let [w (sources/citation-finding "the need UN-004" {:status :unchecked :citation "c:a@1234567" :reasons ["why"]
                                                      :file "n.edn" :line 3})]
    (is (= :warning (:severity w)))
    (is (= "the need UN-004 cites c:a@1234567, which was not checked: why" (:message w))))
  (is (= "citation-fenced" (:rule (sources/citation-finding "x" {:status :unresolved :rule "citation-fenced"
                                                                 :citation "c" :reasons ["r"]})))))

(deftest a-dropped-pin-is-named-with-what-dropped-it
  (is (= [{:id "loyly" :pin "17d68d2" :by {:index {:loyly {:pins ["abcdef1"]}} :name "B"} :deleted? false}]
         (sources/dropped-pins [{:index {:loyly {:pins ["17d68d2"]}} :name "A"}
                                {:index {:loyly {:pins ["abcdef1"]}} :name "B"}
                                {:index {:loyly {:pins ["abcdef1"]}} :name "C"}])))
  (testing "a retired source keeps its pins: nothing is dropped"
    (is (empty? (sources/dropped-pins [{:index {:rdm {:pins ["1234567"]}}}
                                       {:index {:rdm {:pins ["1234567"] :retired "why"}}}]))))
  (testing "a pin dropped and given back is not dropped"
    (is (empty? (sources/dropped-pins [{:index {:a {:pins ["1234567"]}}} {:index {:a {:pins []}}}
                                       {:index {:a {:pins ["1234567"]}}}])))))

(deftest the-stage-publishes-knowledge-indexed
  (with-tmp
    (fn [dir]
      (write! dir "dhf/raw/sources.edn" (pr-str {:loyly {:repository "r" :record "loyly.md" :pins ["17d68d2"] :fenced []}
                                                 :bare {:record "nowhere.md"}}))
      (write! dir "dhf/raw/loyly.md" "# loyly\n")
      (write! dir "dhf/spec/needs.edn" "{\"UN-001\" {:text \"t\" :sources [\"loyly:README.md@17d68d2\" \"a book\"]}}\n")
      (write! dir "dhf/wiki/index.md" "# Wiki\n\n- [A](pages/a.md)\n")
      (write! dir "dhf/wiki/pages/a.md" "# A\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`.\n")
      (let [b (bus/make-bus)
            seen (atom nil)
            _ (bus/subscribe! b :KnowledgeIndexed :publishing/site #(reset! seen %))
            out (sources/check {:root dir :config {:raw-dir "dhf/raw" :needs "dhf/spec/needs.edn" :wiki-dir "dhf/wiki"}
                                :opts {} :bus b :state (atom {})})
            rules (set (map :rule (remove #(= :warning (:severity %)) (:findings out))))]
        (is (= #{"source-no-repository" "source-no-pin" "source-no-record" "citation-malformed"} rules))
        (is (= ["dhf/wiki/pages/a.md"] (get-in out [:result :knowledge :pages])))
        (is (= #{:revision :sources :pages :citations :findings} (set (keys @seen))))
        (is (= [["UN-001" :unchecked] ["UN-001" :unresolved] [nil :unchecked]]
               (map (juxt :need :status) (:citations @seen))))
        (is (every? #(= "dhf/spec/needs.edn" (:file %)) (filter :need (:citations @seen))))))))
