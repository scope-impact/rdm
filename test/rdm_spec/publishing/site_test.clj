(ns rdm-spec.publishing.site-test
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.publishing.site :as site]))

(def model
  {:system {:id :s :name "Shop" :description "Sells"}
   :containers [{:id :app :kind :runs
                 :contexts [{:id :orders :name "orders" :description "Takes orders"
                             :components [{:id :orders/cart :name "Cart" :spec "orders/cart" :description "Holds"}
                                          {:id :orders/pay :name "Pay" :spec "orders/pay"}]}
                            {:id :stock :name "stock"
                             :components [{:id :stock/count :name "Count" :spec "stock/count"}]}]}
                {:id :db :kind :stores}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus"}]
   :views [{:key "orders-components" :level :component :context :orders}]})

(deftest sections-follow-the-model
  (let [secs (site/sections model)]
    (is (= ["orders" "stock" "kernel"] (map :name secs)))
    (is (= ["orders/index.md" "stock/index.md" "kernel/index.md"] (map :page secs)))
    (is (= ["orders/cart.md" "orders/pay.md"] (map :page (:components (first secs)))))
    (is (= "orders-components" (:key (:view (first secs)))))
    (is (nil? (:view (second secs)))))
  (testing "no kernel section without kernel components"
    (is (= ["orders" "stock"] (map :name (site/sections (assoc model :kernel [])))))))

(deftest nav-puts-home-first-then-each-section-with-its-page-then-its-components
  (let [n (site/nav (site/sections model))]
    (is (= ["Home" "index.md"] (first n)))
    (is (= ["orders" [["orders" "orders/index.md"] ["Cart" "orders/cart.md"] ["Pay" "orders/pay.md"]]] (second n)))
    (is (= "kernel" (first (last n))))))

(deftest the-builder-configuration-is-yaml-the-builder-reads
  (let [y (site/mkdocs-yml {:site-name "A: \"quoted\" name" :nav (site/nav (site/sections model)) :site-dir "../site"})
        parsed (yaml/parse-string y)]
    (is (= "A: \"quoted\" name" (:site_name parsed)))
    (is (= "docs" (:docs_dir parsed)))
    (is (= "../site" (:site_dir parsed)))
    (is (= [{:Home "index.md"}] (take 1 (:nav parsed))))
    (is (= [{:orders "orders/index.md"} {:Cart "orders/cart.md"} {:Pay "orders/pay.md"}]
           (:orders (second (:nav parsed)))))
    (is (some #{"footnotes"} (:markdown_extensions parsed)))
    (is (= y (site/mkdocs-yml {:site-name "A: \"quoted\" name" :nav (site/nav (site/sections model)) :site-dir "../site"}))
        "the same inputs write the same bytes")))

(deftest relative-links-between-pages
  (is (= "../index.md" (site/relative-link "orders/cart.md" "index.md")))
  (is (= "cart.md" (site/relative-link "orders/index.md" "orders/cart.md")))
  (is (= "orders/cart.md" (site/relative-link "index.md" "orders/cart.md")))
  (is (= "../views/x.svg" (site/relative-link "orders/index.md" "views/x.svg"))))

(deftest prose-headings-are-demoted-outside-code
  (is (= "### Title\n\ntext\n```\n# not a heading\n```\n#### Sub"
         (site/demote-headings "# Title\n\ntext\n```\n# not a heading\n```\n## Sub" 2)))
  (is (= "###### Deep" (site/demote-headings "##### Deep" 2))))

(deftest citations-link-to-the-source-at-the-pin
  (let [repos {"loyly" "https://github.com/skallinen/loyly"}]
    (is (= "https://github.com/skallinen/loyly/blob/17d68d2/README.md"
           (site/source-url "https://github.com/skallinen/loyly.git" "README.md" "17d68d2")))
    (is (= "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md), \"x\" 1."
           (site/link-citations "[^1]: `loyly:README.md@17d68d2`, \"x\" 1." repos)))
    (testing "an unknown source is left as written"
      (is (= "`nope:README.md@17d68d2`" (site/link-citations "`nope:README.md@17d68d2`" repos))))
    (testing "linking twice changes nothing"
      (let [once (site/link-citations "`loyly:a.md@17d68d2`" repos)]
        (is (= once (site/link-citations once repos)))))))

(deftest a-wiki-page-says-whether-a-status-was-derived
  (let [page (site/wiki-md {} "# Title\n\nBody" {})]
    (is (str/starts-with? page "# Title\n\n> "))
    (is (str/includes? page site/no-status-note)))
  (is (not (str/includes? (site/wiki-md {:statuses {} :results "r"} "Body" {}) site/no-status-note))))

(deftest status-is-shown-never-decided
  (is (= "planned" (site/status-of nil {:id "spec-0001" :wip? true})))
  (is (= "planned" (site/status-of {"spec-0001" :verified} {:id "spec-0001" :wip? true})))
  (is (= "failed" (site/status-of {"spec-0001" :failed} {:id "spec-0001"})))
  (is (nil? (site/status-of nil {:id "spec-0001"}))))

(deftest steps-are-shown-as-written
  (let [md (site/scenario-steps {:steps [{:keyword "Given" :text "a `quoted` <thing>"}
                                         {:keyword "Then" :text "it holds ```three```"}]})]
    (is (str/starts-with? md "````gherkin\n"))
    (is (str/includes? md "Given a `quoted` <thing>"))
    (is (str/ends-with? md "\n````"))))

(deftest builder-warnings-become-findings
  (let [out (str "INFO    -  Building\n"
                 "WARNING -  Doc file 'orders/cart.md' contains a link 'missing.md', but the target 'orders/missing.md' is not found among documentation files.\n"
                 "WARNING -  Something else\n")
        fs* (site/builder-findings out "site-src/docs" (fn [_] "# Cart\n\nSee [it](missing.md).\n"))]
    (is (= ["site-src/docs/orders/cart.md" 3 "site-broken-link"] ((juxt :file :line :rule) (first fs*))))
    (is (str/includes? (:message (first fs*)) "missing.md"))
    (is (= "site-build" (:rule (second fs*))))
    (is (= 2 (count fs*)))))

(deftest the-site-refuses-when-the-spec-was-not-read
  (let [root (str (fs/create-temp-dir {:prefix "rdm-spec-site-test-"}))
        req {:root root :state (atom {}) :env {}
             :config {:model "dhf/spec/model.edn" :spec-dir "dhf/spec"
                      :site {:dir "site" :source "site-src" :builder ["bb" "--version"]}}}]
    (try
      (site/publish req)
      (is false "publish ran without the spec")
      (catch Exception e
        (is (command/cannot-run? e))
        (is (str/includes? (ex-message e) "SpecParsed"))))
    (is (not (fs/exists? (fs/file root "site-src"))) "nothing was written")))

(deftest a-missing-builder-cannot-run-and-writes-nothing
  (let [root (str (fs/create-temp-dir {:prefix "rdm-spec-site-test-"}))
        req {:root root :state (atom {}) :env {}
             :config {:site {:dir "site" :source "site-src" :builder ["rdm-spec-no-such-builder" "build"]}}}]
    (try
      (site/publish req)
      (is false "publish ran without its builder")
      (catch Exception e
        (is (command/cannot-run? e))
        (is (str/includes? (ex-message e) "rdm-spec-no-such-builder"))
        (is (str/includes? (ex-message e) "no HTML was written"))))
    (is (not (fs/exists? (fs/file root "site-src"))))))

(deftest a-site-directory-holding-the-project-cannot-run
  (let [root (str (fs/create-temp-dir {:prefix "rdm-spec-site-test-"}))]
    (fs/create-dirs (fs/file root "dhf"))
    (doseq [site-cfg [{:dir "." :source "site-src"} {:dir "site" :source "."} {:dir ".." :source "site-src"}]
            :let [req {:root root :state (atom {::site/events {:SpecParsed {:scenarios [] :needs []}}}) :env {}
                       :config {:site (assoc site-cfg :builder ["bb" "--version"]) :model "m.edn" :spec-dir "dhf/spec"}}]]
      (try
        (site/publish req)
        (is false (str "publish ran with " site-cfg))
        (catch Exception e
          (is (command/cannot-run? e))
          (is (str/includes? (ex-message e) "holds the project")))))
    (is (fs/exists? (fs/file root "dhf")))))
