(ns rdm-spec.knowledge.sources-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.knowledge.sources :as sources]))

(def index-text
  (str ";; an index\n"
       "{:loyly {:repository \"https://github.com/skallinen/loyly\" :record \"loyly.md\"\n"
       "         :pins [\"17d68d2\"] :fenced [\"impl\" \"harness/fence.sh\"]}\n"
       " :bare {:repository \"\" :record \"bare.md\" :pins []}}\n"))

(def index (:sources (sources/parse-index "dhf/raw/sources.edn" index-text)))

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null"
                                      "GIT_AUTHOR_NAME" "t" "GIT_AUTHOR_EMAIL" "t@example.org"
                                      "GIT_COMMITTER_NAME" "t" "GIT_COMMITTER_EMAIL" "t@example.org"}}
                 "git" "-c" "commit.gpgsign=false" args)]
    (assert (zero? (:exit r)) (:err r))
    (str/trim (:out r))))

(defn- checkouts
  "A --sources directory holding a checkout loyly/ with `files`, tagged `pin`."
  [pin files]
  (let [dir (str (fs/create-temp-dir))
        co (fs/file dir "loyly")]
    (doseq [f files]
      (fs/create-dirs (fs/parent (fs/file co f)))
      (spit (str (fs/file co f)) "x\n"))
    (git! co "init" "-q")
    (git! co "add" "-A")
    (git! co "commit" "-q" "-m" "c")
    (git! co "tag" pin)
    dir))

(deftest citations-are-parsed
  (is (= {:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"}
         (sources/parse-citation "loyly:README.md@17d68d2")))
  (is (nil? (sources/parse-citation "<source>:<path>@<commit>")))
  (is (nil? (sources/parse-citation "loyly:README.md")))
  (testing "in prose, a URL or a placeholder is no citation"
    (is (= [["loyly:docs/a.md@17d68d2" 2]]
           (map (juxt :citation :line)
                (sources/citations-in (str "See https://example.org/x@abcdef0 and `<source>:<path>@<commit>`.\n"
                                           "[^1]: `loyly:docs/a.md@17d68d2`, \"Section\".")))))))

(deftest the-index-is-read-with-each-source-s-line
  (is (= [["bare" 4 []] ["loyly" 2 ["17d68d2"]]] (map (juxt :id :line :pins) index)))
  (is (= ["impl" "harness/fence.sh"] (:fenced (second index))))
  (is (some? (:error (sources/parse-index "f" "{:a"))))
  (is (some? (:error (sources/parse-index "f" "[1 2]")))))

(deftest index-findings-name-each-source
  (let [dir (str (fs/create-temp-dir))
        _ (fs/create-dirs (fs/file dir "dhf/raw"))
        _ (spit (str (fs/file dir "dhf/raw/loyly.md")) "# loyly\n")
        fs* (sources/index-findings dir {:raw-dir "dhf/raw"} {:file "dhf/raw/sources.edn" :sources index})]
    (is (= #{["sources-no-repository" "bare"] ["sources-no-pin" "bare"] ["sources-no-record" "bare"]}
           (set (map (fn [f] [(:rule f) (if (str/includes? (:message f) "bare") "bare" "loyly")]) fs*))))
    (is (every? #(= 4 (:line %)) fs*))))

(deftest a-dropped-pin-is-found
  (let [head [{:id "loyly" :pins ["17d68d2" "aaaaaaa"]}]
        fs* (sources/dropped-pin-findings {:file "dhf/raw/sources.edn" :sources index} head)]
    (is (= ["sources-dropped-pin"] (map :rule fs*)))
    (is (str/includes? (:message (first fs*)) "aaaaaaa"))
    (is (empty? (sources/dropped-pin-findings {:file "x" :sources index} [{:id "loyly" :pins ["17d68d2"]}])))))

(deftest citations-resolve-against-pins-fences-and-checkouts
  (let [st (fn [c dir] (select-keys (sources/resolve-citation index dir c) [:status :rule]))]
    (is (= {:status :unresolved :rule "citation-unknown-source"} (st "innolitix:README.md@f21b508" nil)))
    (is (= {:status :unresolved :rule "citation-unpinned"} (st "loyly:README.md@0000000" nil)))
    (is (= {:status :unresolved :rule "citation-fenced"} (st "loyly:harness/fence.sh@17d68d2" nil)))
    (is (= {:status :unresolved :rule "citation-fenced"} (st "loyly:impl/a.clj@17d68d2" nil)))
    (is (= {:status :unchecked :rule "citation-unchecked"} (st "loyly:README.md@17d68d2" nil)))
    (is (= {:status :unresolved :rule "citation-malformed"} (st "loyly README" nil)))
    (testing "a fence matches whole path segments only"
      (is (= :unchecked (:status (sources/resolve-citation index nil "loyly:implementation.md@17d68d2")))))
    (let [dir (checkouts "17d68d2" ["README.md" "impl/x.clj"])]
      (is (= {:status :resolved :rule nil} (st "loyly:README.md@17d68d2" dir)))
      (is (= :unresolved (:status (sources/resolve-citation index dir "loyly:docs/plan.md@17d68d2"))))
      (is (str/includes? (first (:reasons (sources/resolve-citation index dir "loyly:docs/plan.md@17d68d2")))
                         "docs/plan.md does not exist at 17d68d2"))
      (testing "a --sources directory with no checkout of the source leaves it unchecked"
        (is (= :unchecked (:status (sources/resolve-citation
                                    (conj index {:id "other" :pins ["1234567"] :fenced []})
                                    dir "other:README.md@1234567"))))))))

(deftest fences-are-checked-at-each-pin
  (let [dir (checkouts "17d68d2" ["README.md" "impl/x.clj"])
        fs* (sources/fence-findings {:file "dhf/raw/sources.edn" :sources index} dir)]
    (is (= [["sources-fence-unmatched" true]]
           (map (juxt :rule #(str/includes? (:message %) "harness/fence.sh")) fs*)))
    (is (= [:warning] (map :severity (sources/fence-findings {:file "f" :sources index} nil))))))

(deftest need-citations-carry-their-need-and-line
  (let [text (str ";; needs, citing \"loyly:README.md@17d68d2\" in a comment\n"
                  "{\"UN-001\" {:text \"a\"\n"
                  "            :sources [\"loyly:README.md@17d68d2\"\n"
                  "                      \"innolitix:a.md@1234567\"]}\n"
                  " \"UN-002\" {:text \"b\" :sources [\"loyly:README.md@17d68d2\"]}}\n")]
    (is (= [["UN-001" "loyly:README.md@17d68d2" 3] ["UN-001" "innolitix:a.md@1234567" 4]
            ["UN-002" "loyly:README.md@17d68d2" 5]]
           (map (juxt :need :citation :line) (sources/need-citations "dhf/spec/needs.edn" text))))
    (is (nil? (sources/need-citations "n" "{:a")))))

(deftest a-citation-finding-names-the-citer-and-why
  (let [r (merge (sources/resolve-citation index nil "loyly:README.md@0000000") {:file "n" :line 3})
        f (sources/citation-finding "UN-004" r)]
    (is (= ["n" 3 "citation-unpinned" :error] ((juxt :file :line :rule :severity) f)))
    (is (str/includes? (:message f) "UN-004 cites loyly:README.md@0000000, which does not resolve"))
    (is (= :warning (:severity (sources/citation-finding "UN-1" (merge (sources/resolve-citation index nil "loyly:README.md@17d68d2")
                                                                       {:file "n" :line 1})))))
    (is (nil? (sources/citation-finding "UN-1" {:status :resolved})))))

(deftest the-stage-publishes-knowledge-indexed
  (let [dir (str (fs/create-temp-dir))
        w (fn [rel text] (fs/create-dirs (fs/parent (fs/file dir rel))) (spit (str (fs/file dir rel)) text))
        _ (w "dhf/raw/sources.edn" index-text)
        _ (w "dhf/raw/loyly.md" "# loyly\n")
        _ (w "dhf/raw/bare.md" "# bare\n")
        _ (w "dhf/spec/needs.edn" "{\"UN-001\" {:text \"a\" :sources [\"loyly:README.md@17d68d2\"]}}\n")
        _ (w "dhf/wiki/index.md" "# Wiki\n\n- [A](pages/a.md)\n")
        _ (w "dhf/wiki/pages/a.md" "# A\n\nA claim.[^1]\n\n[^1]: `loyly:impl/x.clj@17d68d2`.\n")
        b (bus/make-bus)
        got (atom nil)
        _ (bus/subscribe! b :KnowledgeIndexed :test (fn [p] (reset! got p)))
        out (sources/check {:root dir :bus b :state (atom {}) :opts {}
                            :config {:raw-dir "dhf/raw" :needs "dhf/spec/needs.edn" :wiki-dir "dhf/wiki"}})]
    (is (= [:KnowledgeIndexed] (map :event (bus/events b))))
    (is (= :working-tree (:revision @got)))
    (is (= ["dhf/wiki/pages/a.md"] (:pages @got)))
    (is (= [["UN-001" :unchecked] [nil :unresolved]] (map (juxt :need :status) (:citations @got))))
    (is (= #{"bare" "loyly"} (set (map :id (:sources @got)))))
    (is (false? (get-in out [:result :knowledge :passed?])))
    (is (some #(= "citation-fenced" (:rule %)) (:findings out)))))
