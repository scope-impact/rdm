(ns rdm-spec.publishing.site-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def model
  {:system {:id :shop :name "Shop" :description "Sells things"}
   :containers [{:id :app :kind :runs
                 :contexts [{:id :spec :name "spec" :description "The spec"
                             :components [{:id :spec/parser :name "Parser" :spec "spec/parser" :description "Reads"}
                                          {:id :spec/check :name "Spec check" :spec "spec/check" :description "Checks"}]}
                            {:id :architecture :name "architecture" :description "The model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint" :description "Lints"}]}]}]
   :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus" :description "Carries"}]
   :views [{:key "architecture-components" :level :component :context :architecture :description "x"}]})

(deftest a-citation-links-to-its-source-at-its-commit
  (is (= "https://github.com/skallinen/loyly/blob/17d68d2/README.md"
         (site/citation-url {:repository "https://github.com/skallinen/loyly.git"} {:path "README.md" :commit "17d68d2"})))
  (is (= "[^1]: [`loyly:README.md@17d68d2`](https://x.invalid/l/blob/17d68d2/README.md), \"S\"."
         (site/link-citations "[^1]: `loyly:README.md@17d68d2`, \"S\"."
                              [{:citation "loyly:README.md@17d68d2" :source "loyly" :path "README.md" :commit "17d68d2"}]
                              {"loyly" {:repository "https://x.invalid/l"}})))
  (testing "a citation of no known source stays as it was"
    (is (= "`x:a@1234567`" (site/link-citations "`x:a@1234567`" [{:citation "x:a@1234567" :source "x" :path "a" :commit "1234567"}] {})))))

(deftest the-navigation-follows-the-model
  (let [n (site/nav model ["wiki/pages/a.md"] {"wiki/pages/a.md" "A page"})]
    (is (= [["Home" "index.md"]
            ["spec" [["spec" "spec/index.md"] ["Parser" "spec/parser.md"] ["Spec check" "spec/check.md"]]]
            ["architecture" [["architecture" "architecture/index.md"] ["Lint" "architecture/lint.md"]]]
            ["kernel" [["kernel" "kernel/index.md"] ["Event bus" "kernel/bus.md"]]]
            ["wiki" [["wiki" "wiki/index.md"] ["A page" "wiki/pages/a.md"]]]]
           n))
    (is (str/includes? (site/nav-yaml n) "- \"Home\": \"index.md\""))))

(deftest a-scenario-shows-its-status
  (let [text (site/scenario-md {:id "spec-0001" :title "A <thing>" :level :e2e :wip? false
                                :background [] :steps [{:keyword "Given" :text "a [link](x.md)"}]}
                               {"spec-0001" :verified})]
    (is (re-find #"spec-0001.*e2e.*verified" text))
    (is (str/includes? text "&lt;thing&gt;"))
    (is (str/includes? text "\\[link\\](x.md)")))
  (is (re-find #"planned" (site/scenario-md {:id "spec-0002" :title "T" :level :e2e :wip? true :steps []} nil)))
  (is (re-find #"no status was derived because no results were given"
               (site/scenario-md {:id "spec-0002" :title "T" :level :e2e :steps []} nil))))

(deftest a-broken-link-is-named
  (is (= [["shop/till.md" "nowhere.md"]]
         (site/broken-links "INFO - x\nWARNING -  Doc file 'shop/till.md' contains a link 'nowhere.md', but the target is not found among documentation files.\n"))))
