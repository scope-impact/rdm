(ns rdm-spec.publishing.site-test
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def root
  (-> (io/resource "rdm_spec/kernel/command.clj") io/file fs/absolutize
      fs/parent fs/parent fs/parent fs/parent str))

(deftest model-hash-follows-the-glossary
  (testing "reformatting and reordering a map's entries or a set's elements keeps the hash"
    (is (= (site/model-hash {:b 1 :a #{:y :x} :c [{:k 1 :j 2}]})
           (site/model-hash (edn/read-string "{:c [{:j 2 :k 1}] :a #{:x :y}\n :b 1}")))))
  (testing "a vector's order is part of the model"
    (is (not= (site/model-hash {:v [1 2]}) (site/model-hash {:v [2 1]}))))
  (testing "the room's rendered views record the hash of the room's model"
    (let [m (edn/read-string (slurp (str (fs/file root "dhf/spec/model.edn"))))
          line (first (str/split-lines (slurp (str (fs/file root "dhf/views/context.mmd")))))]
      (is (= (site/recorded-hash line) (site/model-hash m))))))

(deftest recorded-hash-reads-the-first-line
  (is (= (apply str (repeat 64 "a")) (site/recorded-hash (str "%% model-sha256: " (apply str (repeat 64 "a")) "; x"))))
  (is (nil? (site/recorded-hash "<!-- no hash -->"))))

(def model
  {:system {:id :s :name "S" :description "d"}
   :containers [{:id :app :kind :runs
                 :contexts [{:id :b :name "b" :description "B" :components [{:id :b/z :name "Z" :spec "b/z"}
                                                                            {:id :b/y :name "Y" :spec "b/y"}]}
                            {:id :a :name "a" :description "A" :components [{:id :a/x :name "X" :spec "a/x"}]}]}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus"}]})

(deftest contexts-follow-the-model
  (let [cs (site/contexts model)]
    (is (= ["b" "a" "kernel"] (map :key cs)))
    (is (= ["b/z.md" "b/y.md"] (map :page (:components (first cs)))))
    (is (= "kernel/index.md" (:index (last cs))))))

(deftest navigation-lists-home-then-each-section
  (let [yml (site/mkdocs-config {:site-name "S" :sections (site/contexts model) :wiki-pages ["wiki/index.md"]
                                 :site-dir "../site"})
        lines (str/split-lines yml)
        nav (drop-while #(not= "nav:" %) lines)]
    (is (= ["nav:"
            "  - \"Home\": index.md"
            "  - \"b\":"
            "      - \"b\": \"b/index.md\""
            "      - \"Z\": \"b/z.md\""
            "      - \"Y\": \"b/y.md\""
            "  - \"a\":"
            "      - \"a\": \"a/index.md\""
            "      - \"X\": \"a/x.md\""
            "  - \"kernel\":"
            "      - \"kernel\": \"kernel/index.md\""
            "      - \"Bus\": \"kernel/bus.md\""
            "  - \"wiki\":"
            "      - \"wiki/index.md\""]
           nav))
    (is (some #{"site_dir: \"../site\""} lines))))

(deftest status-follows-the-results
  (is (= :planned (site/status-of {:id "spec-0001" :wip? true} nil)))
  (is (= :planned (site/status-of {:id "spec-0001" :wip? true} {"spec-0001" :verified})))
  (is (nil? (site/status-of {:id "spec-0001"} nil)))
  (is (= :failed (site/status-of {:id "spec-0001"} {"spec-0001" :failed})))
  (is (= :unverified (site/status-of {:id "spec-0002"} {"spec-0001" :failed}))))

(deftest footnotes-link-their-sources
  (let [text "Body cites loyly:README.md@17d68d2 inline.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"What\" 1.\n  [^2]: `other:x.md@abcdef1`."
        out (site/link-footnotes text {"loyly" "https://github.com/skallinen/loyly"})]
    (is (str/includes? out "Body cites loyly:README.md@17d68d2 inline.[^1]") "the body is left alone")
    (is (str/includes? out "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md)"))
    (is (str/includes? out "[^2]: `other:x.md@abcdef1`.") "an unknown source stays unlinked")))

(deftest pages-carry-the-status-note
  (let [sections (site/contexts model)
        page (site/component-page {:section (first sections) :component (first (:components (first sections)))
                                   :prose "Prose." :note site/no-status-note
                                   :scenarios [{:id "spec-0001" :title "T" :level :e2e :wip? false
                                                :steps [{:keyword "Given" :text "a | b"}]}]
                                   :statuses nil})]
    (is (str/includes? page site/no-status-note))
    (is (str/includes? page "`spec-0001` · level: e2e · status: **no status derived**"))
    (is (str/includes? page "- **Given** a \\| b"))
    (is (str/includes? page "Prose."))
    (is (str/includes? page "[b](index.md)") "the component links its context's page")))

(deftest broken-links-are-read-from-the-builder
  (is (= [{:page "architecture/lint.md" :link "gone.md"}]
         (site/broken-link-findings
          (str "INFO    -  Building\nWARNING -  Doc file 'architecture/lint.md' contains a link 'gone.md', "
               "but the target 'architecture/gone.md' is not found among documentation files.\n")))))
