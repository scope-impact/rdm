(ns rdm-spec.publishing.site-test
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(deftest prose-headings-sit-under-design
  (is (= "### Title\n\nText\n\n#### Part" (site/shift-headings "# Title\n\nText\n\n## Part" 3)))
  (is (= "### Part\n#### Sub" (site/shift-headings "## Part\n### Sub" 3)))
  (testing "a heading in fenced code is left alone, and the shift is capped"
    (is (= "### A\n```\n# not a heading\n```\n###### F" (site/shift-headings "# A\n```\n# not a heading\n```\n###### F" 3))))
  (is (= "no heading" (site/shift-headings "no heading" 3))))

(deftest links-are-relative
  (is (= "lint.md" (site/rel-link "architecture/index.md" "architecture/lint.md")))
  (is (= "../views/a.svg" (site/rel-link "architecture/index.md" "views/a.svg")))
  (is (= "wiki/index.md" (site/rel-link "index.md" "wiki/index.md"))))

(deftest footnotes-link-their-sources
  (let [repos {"loyly" "https://github.com/skallinen/loyly"}
        text "A claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n[^2]: `other:a.md@abcdef1`."]
    (is (= (str "A claim.[^1]\n\n[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md),"
                " \"What to steal\" 1.\n[^2]: `other:a.md@abcdef1`.")
           (site/link-citations text repos)))
    (is (str/includes? (site/link-citations "loyly:README.md@17d68d2 in the body" repos) "in the body"))
    (is (= "https://github.com/o/r/blob/c/p.md" (site/source-url "o/r" "p.md" "c")))
    (is (= "https://x.org/r/blob/c/p.md" (site/source-url "https://x.org/r.git" "p.md" "c")))))

(deftest the-status-note-follows-the-title
  (is (= "# T\n\n> note\n\nbody" (site/with-note "# T\n\nbody" "> note")))
  (is (= "> note\n\nbody" (site/with-note "body" "> note")))
  (is (= "body" (site/with-note "body" nil))))

(deftest a-scenario-shows-its-status
  (is (= "planned" (site/status-text {:id "spec-1" :wip? true} {"spec-1" :verified})))
  (is (= "not derived" (site/status-text {:id "spec-1"} nil)))
  (is (= "failed" (site/status-text {:id "spec-1"} {"spec-1" :failed})))
  (let [md (site/scenario-md {:id "spec-7" :title "A <task> runs" :level :e2e :needs ["UN-001"]
                              :steps [{:keyword "When" :text "\"rdm-spec <task>\" is run"}]}
                             {"spec-7" :verified})]
    (is (str/starts-with? md "### spec-7: A &lt;task> runs"))
    (is (str/includes? md "`@e2e` · status: **verified** · needs: UN-001"))
    (is (str/includes? md "```gherkin\nWhen \"rdm-spec <task>\" is run\n```"))))

(deftest the-builder-configuration-follows-the-model
  (let [cfg (yaml/parse-string (site/builder-config {:site-name "Demo" :site-dir "../site"
                                                     :nav [["Home" "index.md"]
                                                           ["spec" [["spec" "spec/index.md"] ["Parser" "spec/parser.md"]]]]})
                               :keywords false)]
    (is (= "Demo" (get cfg "site_name")))
    (is (= "../site" (get cfg "site_dir")))
    (is (= [{"Home" "index.md"} {"spec" [{"spec" "spec/index.md"} {"Parser" "spec/parser.md"}]}] (get cfg "nav")))))

(deftest a-failed-build-names-the-page-and-the-link
  (let [out (str "INFO    -  Building documentation...\n"
                 "WARNING -  Doc file 'architecture/lint.md' contains a link 'no-such-page.md', but the target "
                 "'architecture/no-such-page.md' is not found among documentation files.\n"
                 "Aborted with 1 warnings in strict mode!\n")
        [f & more] (site/build-findings "site-src/docs" out 1)]
    (is (nil? more))
    (is (= "site-src/docs/architecture/lint.md" (:file f)))
    (is (= "site-link-broken" (:rule f)))
    (is (str/includes? (:message f) "no-such-page.md")))
  (is (= ["site-build"] (map :rule (site/build-findings "d" "something broke" 2)))))
