(ns rdm-spec.publishing.site-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def model
  {:system {:id :sys :name "Demo" :description "A demo system"}
   :containers [{:id :app :kind :runs
                 :contexts [{:id :alpha :name "alpha" :description "First"
                             :components [{:id :alpha/one :name "One" :spec "alpha/one" :description "The first"}
                                          {:id :alpha/two :name "Two" :spec "alpha/two" :description "The second"}]}
                            {:id :beta :name "beta" :description "Second"
                             :components [{:id :beta/three :name "Three" :spec "beta/three" :description "The third"}]}]}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus" :description "The bus"}]
   :views [{:key "context" :level :context :description "Context"}
           {:key "alpha-components" :level :component :context :alpha :description "Alpha"}]})

(deftest the-model-hash-is-of-the-value-with-sorted-keys
  (testing "reformatting or reordering keys keeps the hash"
    (is (= (site/model-hash {:b 1 :a {:d 2 :c 3}}) (site/model-hash {:a {:c 3 :d 2} :b 1}))))
  (testing "a changed value changes it"
    (is (not= (site/model-hash {:a 1}) (site/model-hash {:a 2}))))
  (testing "it is the SHA-256 of the sorted printing, lowercase hex"
    (is (= "{:a 1, :b [{:c 2, :d 3}]}" (site/printed-sorted {:b [{:d 3 :c 2}] :a 1})))
    (is (= (site/sha256 (.getBytes "{:a 1, :b 2}" "UTF-8")) (site/model-hash {:b 2 :a 1})))
    (is (re-matches #"[0-9a-f]{64}" (site/model-hash model))))
  (testing "keys that do not compare are ordered by their printed form"
    (is (= "{\"a\" 1, :b 2}" (site/printed-sorted {:b 2 "a" 1})))))

(deftest a-view-records-its-hash-on-its-first-line
  (let [h (apply str (repeat 64 "a"))]
    (is (= h (site/recorded-hash (str "%% model-sha256: " h "; generated\nC4Context"))))
    (is (= h (site/recorded-hash (str "<!-- model-sha256: " h " model-value-sha256: " (apply str (repeat 64 "b")) " -->"))))
    (testing "only the first line counts, and model-value-sha256 is not model-sha256"
      (is (nil? (site/recorded-hash (str "C4Context\n%% model-sha256: " h))))
      (is (nil? (site/recorded-hash (str "%% model-value-sha256: " h)))))))

(deftest stale-and-missing-views-are-findings
  (let [h (site/model-hash model)
        files {"v/context.mmd" (str "%% model-sha256: " h)
               "v/context.svg" (str "<!-- model-sha256: " (apply str (repeat 64 "0")) " -->\n<svg/>")
               "v/alpha-components.mmd" (str "%% model-sha256: " h)}
        fs (site/view-findings model h "v" files)]
    (is (= [["v/context.svg" "site-view-stale"] ["v/alpha-components.svg" "site-view-missing"]]
           (mapv (juxt :file :rule) fs)))
    (is (str/includes? (:message (first fs)) "view \"context\""))
    (is (empty? (site/view-findings model h "v" (constantly (str "%% model-sha256: " h)))))))

(deftest sections-follow-the-model-then-the-kernel
  (let [ss (site/sections model)]
    (is (= ["alpha" "beta" "kernel"] (map :name ss)))
    (is (= ["alpha/index.md" "beta/index.md" "kernel/index.md"] (map :page ss)))
    (is (= ["alpha/one.md" "alpha/two.md"] (map :page (:components (first ss)))))
    (is (:kernel? (last ss)))
    (is (= 2 (count (site/sections (assoc model :kernel [])))))))

(deftest the-builder-configuration-lists-the-model-in-order
  (let [y (site/builder-config "Demo" "../site" (site/sections model) [["wiki" "wiki/index.md"]])
        nav (drop-while #(not= "nav:" %) (str/split-lines y))]
    (is (str/includes? y "site_dir: \"../site\""))
    (is (str/includes? y "  - footnotes"))
    (is (= ["nav:"
            "  - \"Home\": \"index.md\""
            "  - \"alpha\":"
            "      - \"alpha\": \"alpha/index.md\""
            "      - \"One\": \"alpha/one.md\""
            "      - \"Two\": \"alpha/two.md\""
            "  - \"beta\":"
            "      - \"beta\": \"beta/index.md\""
            "      - \"Three\": \"beta/three.md\""
            "  - \"kernel\":"
            "      - \"kernel\": \"kernel/index.md\""
            "      - \"Bus\": \"kernel/bus.md\""
            "  - \"wiki\":"
            "      - \"wiki\": \"wiki/index.md\""]
           (vec nav)))))

(def lint-scenario
  {:id "spec-0001" :title "A <thing> *passes*" :level :e2e :needs ["UN-001"] :rule "The rule"
   :file "dhf/spec/alpha/one.feature" :line 9 :component :alpha/one
   :steps [{:keyword "Given" :text "a model with a view of level <level>"}
           {:keyword "When" :text "\"rdm-spec check\" is run"}]
   :examples [{:header ["level"] :rows [{:cells ["context"]}]}]})

(deftest a-scenario-shows-its-id-level-status-and-steps
  (testing "without results"
    (let [md (site/scenario-md lint-scenario nil)]
      (is (str/includes? md "### spec-0001: A &lt;thing&gt; \\*passes\\* {#spec-0001}"))
      (is (str/includes? md "- **Level:** e2e"))
      (is (str/includes? md "- **Status:** no status was derived: no results were given"))
      (is (str/includes? md "Given a model with a view of level <level>\nWhen \"rdm-spec check\" is run"))
      (is (str/includes? md "  | level |\n  | context |"))))
  (testing "a planned scenario is planned whatever the results"
    (is (str/includes? (site/scenario-md (assoc lint-scenario :wip? true) nil) "**Status:** planned")))
  (testing "with results"
    (is (str/includes? (site/scenario-md lint-scenario {"spec-0001" :failed}) "**Status:** failed"))
    (is (str/includes? (site/scenario-md lint-scenario {}) "**Status:** unverified"))))

(deftest every-page-carries-the-status-line
  (is (str/includes? (site/banner {:statuses nil}) "No status was derived: no results were given"))
  (is (str/includes? (site/banner {:statuses {} :results "r"}) "`r`")))

(deftest citations-link-to-the-file-at-its-commit
  (let [url (site/citation-url "https://github.com/skallinen/loyly" "README.md" "17d68d2")]
    (is (= "https://github.com/skallinen/loyly/blob/17d68d2/README.md" url))
    (is (= url (site/citation-url "https://github.com/skallinen/loyly.git" "README.md" "17d68d2")))
    (is (nil? (site/citation-url "/srv/git/loyly" "README.md" "17d68d2")))
    (let [cites {"loyly:README.md@17d68d2" url}]
      (is (= (str "[^1]: [`loyly:README.md@17d68d2`](" url "), the opening.")
             (site/link-citations "[^1]: `loyly:README.md@17d68d2`, the opening." cites)))
      (is (= (str "see [loyly:README.md@17d68d2](" url ").")
             (site/link-citations "see loyly:README.md@17d68d2." cites)))
      (testing "a citation already linked is left alone"
        (let [linked (str "[`loyly:README.md@17d68d2`](" url ")")]
          (is (= linked (site/link-citations linked cites))))))))

(deftest a-broken-link-names-the-page-and-the-link
  (let [out (str "INFO    -  Building documentation...\n"
                 "WARNING -  Doc file 'architecture/lint.md' contains a link 'gone.md', but the target "
                 "'architecture/gone.md' is not found among documentation files.\n"
                 "Aborted with 1 warnings in strict mode!\n")
        [f] (site/broken-link-findings out "site-src/docs")]
    (is (= "site-src/docs/architecture/lint.md" (:file f)))
    (is (= "site-broken-link" (:rule f)))
    (is (str/includes? (:message f) "gone.md"))))

(deftest links-between-pages-are-relative
  (is (= "views/context.svg" (site/relative "index.md" "views/context.svg")))
  (is (= "../views/a.svg" (site/relative "alpha/index.md" "views/a.svg"))))

(deftest the-site-is-a-function-of-its-inputs
  (let [h (site/model-hash model)
        texts {"dhf/views/context.svg" "<svg/>" "dhf/views/alpha-components.svg" "<svg/>"
               "dhf/spec/alpha/one.md" "# One\n\nWhy one exists.\n"
               "dhf/wiki/index.md" "# Wiki\n\n- [P](pages/p.md)\n"
               "dhf/wiki/pages/p.md" "# P\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`\n"}
        in {:model model :sections (site/sections model)
            :scenarios [lint-scenario] :needs [{:id "UN-001" :text "A need" :sources []}]
            :site {:statuses nil :wiki-dir "dhf/wiki"
                   :citations {"dhf/wiki/pages/p.md" {"loyly:README.md@17d68d2" "https://x/blob/1/README.md"}}}
            :wiki ["index.md" "pages/p.md"] :read-text texts :views-dir "dhf/views" :spec-dir "dhf/spec"
            :source-rel "site-src" :site-rel "site"}
        files (site/site-pages in)]
    (is (= files (site/site-pages in)))
    (is (= #{"mkdocs.yml" "docs/index.md" "docs/alpha/index.md" "docs/alpha/one.md" "docs/alpha/two.md"
             "docs/beta/index.md" "docs/beta/three.md" "docs/kernel/index.md" "docs/kernel/bus.md"
             "docs/views/context.svg" "docs/views/alpha-components.svg" "docs/wiki/index.md" "docs/wiki/pages/p.md"}
           (set (keys files))))
    (is (str/includes? (files "docs/alpha/one.md") "Why one exists."))
    (is (str/includes? (files "docs/alpha/index.md") "![Alpha](../views/alpha-components.svg)"))
    (is (str/includes? (files "docs/alpha/index.md") "[One](one.md)"))
    (is (str/includes? (files "docs/index.md") "[spec-0001](alpha/one.md#spec-0001)"))
    (is (str/includes? (files "docs/wiki/pages/p.md") "(https://x/blob/1/README.md)"))
    (is (str/includes? (files "mkdocs.yml") "\"P\": \"wiki/pages/p.md\""))
    (is (every? #(str/includes? (val %) "No status was derived")
                (filter #(str/ends-with? (key %) ".md") files)))
    (is (string? h))))
