(ns rdm-spec.publishing.site-test
  "Unit tests of the Site component's pure parts. They make no fixture on
  disk."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def model
  {:system {:id :demo :name "Demo" :description "A demo"}
   :containers [{:id :c :name "c" :kind :runs
                 :contexts [{:id :spec :name "spec" :description "Reads the spec"
                             :components [{:id :spec/parser :name "Parser" :spec "spec/parser" :description "Reads"}
                                          {:id :spec/check :name "Spec check" :spec "spec/check" :description "Checks"}]}
                            {:id :architecture :name "architecture" :description "The model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "Lints"}]}]}]
   :kernel [{:id :kernel/command :name "Command" :spec "kernel/command" :description "Dispatches"}]
   :views [{:key "context" :level :context :description "The context"}
           {:key "containers" :level :container :description "The containers"}
           {:key "spec-components" :level :component :context :spec :description "The spec's components"}]})

(def spec
  {:scenarios [{:id "spec-0001" :name "A first" :title "A first" :component :spec/parser :context :spec
                :level :e2e :needs ["UN-001"] :rule "A rule"
                :steps [{:keyword "Given" :text "a <thing>"} {:keyword "Then" :text "it passes"}]}
               {:id "spec-0002" :name "A planned one" :title "A planned one" :component :architecture/lint
                :context :architecture :level :contract :needs ["UN-002"] :wip? true
                :steps [{:keyword "Given" :text "a table" :data-table [["a" "b"] ["1" "2"]]}]}
               {:id nil :name "Unstamped" :title "Unstamped" :component :spec/parser :level :e2e :needs []
                :steps [{:keyword "Given" :text "nothing"}]}]
   :needs [{:id "UN-001" :text "A first need"} {:id "UN-002" :text "A second need"}]})

(def wiki
  [{:file "index.md" :src "dhf/wiki/index.md"
    :text "# The wiki\n\n- [A page](pages/a.md)\n\n  [^ex]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n"}
   {:file "pages/a.md" :src "dhf/wiki/pages/a.md"
    :text "# A page\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"The idea\".\n"}])

(def knowledge
  {:sources [{:id "loyly" :repository "https://github.com/skallinen/loyly"}]
   :citations [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"
                :file "dhf/wiki/index.md" :line 5}
               {:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"
                :file "dhf/wiki/pages/a.md" :line 5}]})

(defn- publish [opts]
  (site/site-files (merge {:model model :spec spec :knowledge knowledge :wiki wiki
                           :views-on-disk ["context" "containers" "spec-components"]}
                          opts)))

(deftest escaping-and-links
  (is (= "a \\<thing\\> \\*x\\*" (site/escape "a <thing> *x*")))
  (is (= "../index.md" (site/relative-link "spec/parser.md" "index.md")))
  (is (= "views/context.svg" (site/relative-link "index.md" "views/context.svg")))
  (is (= "https://github.com/skallinen/loyly/blob/17d68d2/README.md"
         (site/citation-url "https://github.com/skallinen/loyly.git" "README.md" "17d68d2"))))

(deftest sections-follow-the-model
  (let [secs (site/sections model)]
    (is (= ["spec" "architecture" "kernel"] (map :key secs)))
    (is (= ["spec/parser.md" "spec/check.md"] (map :page (:components (first secs)))))
    (is (:kernel? (last secs)))
    (is (empty? (filter :kernel? (site/sections (dissoc model :kernel)))))))

(deftest status-is-shown-never-decided
  (testing "with results, the derived status"
    (is (= "verified" (site/status-word {"spec-0001" :verified} true {:id "spec-0001"})))
    (is (= "no status" (site/status-word {} true {:id "spec-0009"}))))
  (testing "without results, planned from the tag and nothing else"
    (is (= "planned" (site/status-word nil false {:id "spec-0002" :wip? true})))
    (is (= "no status derived" (site/status-word nil false {:id "spec-0001"}))))
  (is (= site/no-status-note (site/status-note {:derived? false})))
  (is (str/includes? (site/status-note {:derived? true :results "dhf/allure-results"}) "dhf/allure-results")))

(deftest the-site-files
  (let [{:keys [pages nav]} (publish {:derived? false})]
    (testing "one page per component, per context, the home page and the wiki"
      (is (= #{"index.md" "spec/index.md" "spec/parser.md" "spec/check.md" "architecture/index.md"
               "architecture/lint.md" "kernel/index.md" "kernel/command.md" "wiki/index.md" "wiki/pages/a.md"}
             (set (keys pages)))))
    (testing "every page says no status was derived"
      (doseq [[rel text] pages]
        (is (str/includes? text site/no-status-note) rel)))
    (testing "the component page carries every scenario, with its id, level, status and steps"
      (let [p (get pages "spec/parser.md")]
        (is (str/includes? p "spec-0001"))
        (is (str/includes? p "Level: e2e"))
        (is (str/includes? p "Given a <thing>"))
        (is (str/includes? p "(no id)"))
        (is (str/includes? (get pages "architecture/lint.md") "| a | b |"))
        (is (str/includes? (get pages "architecture/lint.md") "Status: **planned**"))))
    (testing "the home page shows the top views, the wiki and every need with its scenarios"
      (let [h (get pages "index.md")]
        (is (str/includes? h "views/context.svg"))
        (is (str/includes? h "views/containers.svg"))
        (is (not (str/includes? h "views/spec-components.svg")))
        (is (str/includes? h "(wiki/index.md)"))
        (is (str/includes? h "[spec-0001](spec/parser.md#spec-0001)"))
        (is (str/includes? h "UN-002"))))
    (testing "the context page shows its component view"
      (is (str/includes? (get pages "spec/index.md") "../views/spec-components.svg")))
    (testing "footnotes are linked, the index's included"
      (doseq [rel ["wiki/index.md" "wiki/pages/a.md"]]
        (is (str/includes? (get pages rel)
                           "[`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md)")
            rel)))
    (testing "the navigation: home, the contexts in the model's order, the kernel, the wiki"
      (is (= ["Home" "spec" "architecture" "kernel" "Wiki"] (map first nav)))
      (is (= ["spec/index.md" "spec/parser.md" "spec/check.md"] (map second (second (second nav))))))
    (testing "the same inputs give the same files"
      (is (= pages (:pages (publish {:derived? false})))))))

(deftest with-results
  (let [{:keys [pages]} (publish {:derived? true :statuses {"spec-0001" :failed "spec-0002" :planned}
                                  :results "dhf/allure-results"})]
    (is (str/includes? (get pages "spec/parser.md") "Status: **failed**"))
    (is (not (str/includes? (get pages "spec/parser.md") site/no-status-note)))))

(deftest linking-citations
  (testing "only in footnotes, keeping the backticks"
    (let [text "A claim `loyly:a.md@1234567`.\n\n[^1]: `loyly:a.md@1234567`, here.\n"
          out (site/link-citations text [{:citation "loyly:a.md@1234567" :line 3 :url "U"}])]
      (is (= "A claim `loyly:a.md@1234567`.\n\n[^1]: [`loyly:a.md@1234567`](U), here.\n" out))))
  (testing "a bare citation in a footnote's continuation line"
    (let [text "[^1]: The source\n    loyly:a.md@1234567, here.\n"]
      (is (str/includes? (site/link-citations text [{:citation "loyly:a.md@1234567" :line 2 :url "U"}])
                         "[loyly:a.md@1234567](U)")))))

(deftest a-note-after-the-title
  (is (= "# T\n\nN\n\nbody\n" (site/with-note "# T\n\nbody" "N")))
  (is (= "N\n\nbody\n" (site/with-note "body" "N"))))

(deftest the-builders-configuration
  (let [y (site/config-yaml {:site-name "Demo" :site-dir "../site"
                             :nav [["Home" "index.md"] ["spec" [["Overview" "spec/index.md"]]]]})]
    (is (str/includes? y "site_dir: \"../site\""))
    (is (str/includes? y "  - \"Home\": \"index.md\"\n  - \"spec\":\n      - \"Overview\": \"spec/index.md\"\n"))))

(deftest a-failed-build-names-the-page-and-the-link
  (let [out (str "INFO    -  Building documentation...\n"
                 "WARNING -  Doc file 'architecture/lint.md' contains a link 'no-such-page.md', but the target "
                 "'architecture/no-such-page.md' is not found among documentation files.\n"
                 "Aborted with 1 warnings in strict mode!\n")
        fs (site/build-findings out 1 ["mkdocs" "build"] #(str "site-src/docs/" %)
                                {"architecture/lint.md" "dhf/spec/architecture/lint.md"})]
    (is (= ["site-broken-link" "site-build-failed"] (map :rule fs)))
    (is (= "site-src/docs/architecture/lint.md" (:file (first fs))))
    (is (str/includes? (:message (first fs)) "no-such-page.md"))
    (is (str/includes? (:message (last fs)) "no HTML was written"))))
