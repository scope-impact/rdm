(ns rdm-spec.publishing.site-test
  "One test per rule the Site enforces (09-publish/site.feature)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [rdm-spec.publishing.site :as site]))

(def model {:containers [{:id :c :contexts [{:id :spec :name "spec" :components [{:id :spec/parser :name "Parser"}]}]}]
            :kernel [{:id :kernel/bus :name "Event bus"}]})

(deftest the-site-follows-the-model
  (is (= "spec/parser.md" (site/component-page {:id :spec/parser}))))

(deftest the-site-shows-status-and-never-decides-it
  (is (str/includes? (site/status-text nil) "No status was derived because no results were given"))
  (is (= "verified" (site/status-text :verified))))

(deftest the-site-is-a-reading-of-the-spec-never-a-copy-that-can-drift
  (is (= (site/nav-yaml model ["wiki/index.md"]) (site/nav-yaml model ["wiki/index.md"]))))

(deftest the-site-publishes-the-domain-wiki-beside-the-spec
  (is (str/includes? (site/link-footnotes "[^a]: `loyly:README.md@17d68d2`, \"x\"." {:loyly {:repository "https://github.com/skallinen/loyly"}})
                     "(https://github.com/skallinen/loyly/blob/17d68d2/README.md)")))

(deftest the-site-builder-builds-the-site-from-a-configuration-the-model-writes
  (let [y (site/nav-yaml model ["wiki/index.md"])]
    (is (< (str/index-of y "spec/index.md") (str/index-of y "kernel/index.md") (str/index-of y "wiki/index.md")))))
