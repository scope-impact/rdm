(ns rdm-spec.publishing.site-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(deftest text-is-escaped
  (is (= "An outline over &lt;n&gt;" (site/esc "An outline over <n>")))
  (is (= "a \\*b\\* \\_c\\_ \\`d\\`" (site/esc "a *b* _c_ `d`"))))

(deftest status-is-shown-never-decided
  (is (= "verified" (site/status-of {"spec-0001" :verified} {:id "spec-0001"})))
  (is (= "planned" (site/status-of nil {:id "spec-0001" :wip? true})))
  (is (= "not derived" (site/status-of nil {:id "spec-0001"}))))

(def repos {"loyly" "https://github.com/skallinen/loyly"})

(deftest footnotes-link-their-citations
  (is (= "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md), \"x\" 1."
         (site/link-footnotes repos "[^1]: `loyly:README.md@17d68d2`, \"x\" 1.")))
  (testing "only footnote definitions, only known sources"
    (is (= "See `loyly:README.md@17d68d2`." (site/link-footnotes repos "See `loyly:README.md@17d68d2`.")))
    (is (= "[^2]: `other:a.md@abc1234`" (site/link-footnotes repos "[^2]: `other:a.md@abc1234`")))))

(deftest the-builder-configuration-is-deterministic
  (let [nav [["Home" "index.md"] ["spec" [["spec" "spec/index.md"] ["Parser" "spec/parser.md"]]]]
        y (site/mkdocs-yml "Demo" "../site" nav)]
    (is (= y (site/mkdocs-yml "Demo" "../site" nav)))
    (is (str/includes? y "site_dir: \"../site\""))
    (is (str/includes? y "  - \"spec\":\n      - \"spec\": \"spec/index.md\"\n      - \"Parser\": \"spec/parser.md\"\n"))))

(deftest a-broken-link-names-the-page
  (let [[f] (site/builder-findings "site-src/docs"
                                   "WARNING -  Doc file 'architecture/lint.md' contains a link 'gone.md', but the target is not found among documentation files.")]
    (is (= "site-src/docs/architecture/lint.md" (:file f)))
    (is (= "site-link-broken" (:rule f)))
    (is (str/includes? (:message f) "gone.md"))))

(deftest the-wiki-index-comes-first-then-its-order
  (let [d (fs/create-temp-dir {:prefix "rdm-spec-site-test-"})]
    (try
      (spit (str (fs/file d "index.md")) "# W\n\n- [B](pages/b.md)\n- [A](pages/a.md)\n")
      (fs/create-dirs (fs/file d "pages"))
      (doseq [n ["a" "b" "c"]] (spit (str (fs/file d "pages" (str n ".md"))) (str "# " n "\n")))
      (is (= ["index.md" "pages/b.md" "pages/a.md" "pages/c.md"] (map :rel (site/wiki-pages d))))
      (finally (fs/delete-tree d)))))

(deftest contexts-then-the-kernel
  (let [m {:containers [{:contexts [{:id :spec :name "spec" :components [{:id :spec/parser :name "Parser" :spec "spec/parser"}]}]}]
           :kernel [{:id :kernel/command :name "Command" :spec "kernel/command"}]}]
    (is (= [["spec" ["spec/parser.md"]] ["kernel" ["kernel/command.md"]]]
           (map (juxt :key #(map :page (:components %))) (site/contexts-of m))))))
