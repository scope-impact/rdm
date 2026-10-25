(ns rdm-spec.publishing.site-test
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def model
  {:system {:id :shop :name "Shop" :description "Sells things"}
   :containers [{:id :app :contexts [{:id :orders :name "orders" :description "Takes orders"
                                      :components [{:id :orders/cart :name "Cart" :spec "orders/cart" :description "Holds the cart"}
                                                   {:id :orders/pay :name "Pay" :spec "orders/pay" :description "Takes payment"}]}
                                     {:id :stock :name "stock" :description "Counts"
                                      :components [{:id :stock/count :name "Count" :spec "stock/count" :description "Counts"}]}]}]
   :kernel [{:id :kernel/bus :name "Bus" :spec "kernel/bus" :description "Carries events"}]})

(deftest relative-paths
  (is (= "lint.md" (site/relative-to "architecture/index.md" "architecture/lint.md")))
  (is (= "../views/a.svg" (site/relative-to "architecture/index.md" "views/a.svg")))
  (is (= "spec/parser.md" (site/relative-to "index.md" "spec/parser.md")))
  (is (= "../site" (site/relative-to "site-src/x" "site"))))

(deftest contexts-follow-the-model
  (testing "the model's order, the kernel last"
    (is (= ["orders" "stock" "kernel"] (map (comp :name :context) (site/contexts model))))
    (is (= ["orders/cart.md" "orders/pay.md"]
           (map #(site/component-page (:context (first (site/contexts model))) %)
                (:components (first (site/contexts model)))))))
  (testing "no kernel, no kernel section"
    (is (= ["orders" "stock"] (map (comp :name :context) (site/contexts (dissoc model :kernel)))))))

(deftest the-navigation
  (let [yml (site/mkdocs-yml {:site-name "Shop" :site-dir "../site" :ctxs (site/contexts model)
                              :wiki-nav [["Overview" "wiki/index.md"] ["Cycles" "wiki/pages/cycles.md"]]})
        cfg (yaml/parse-string yml :keywords false)
        nav (get cfg "nav")]
    (is (true? (get cfg "strict")))
    (is (= "warn" (get-in cfg ["validation" "links" "not_found"])))
    (is (= {"Home" "index.md"} (into {} (first nav))))
    (is (= ["orders" "stock" "kernel" "wiki"] (map #(key (first %)) (rest nav))))
    (is (= ["orders/index.md" "orders/cart.md" "orders/pay.md"]
           (map #(val (first %)) (val (first (second nav))))))))

(deftest footnotes-link-their-sources
  (let [repos {"loyly" "https://github.com/skallinen/loyly"}
        text "A claim.[^1] See `loyly:README.md@17d68d2` in the body.\n\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n"
        out (site/link-footnotes text repos)]
    (is (str/includes? out "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md)"))
    (testing "only footnotes are rewritten"
      (is (str/includes? out "See `loyly:README.md@17d68d2` in the body.")))
    (testing "a bare citation in a footnote, and an unknown source"
      (is (= "[^2]: [loyly:a/b.md@17d68d2](https://github.com/skallinen/loyly/blob/17d68d2/a/b.md) and nope:x.md@abcd"
             (site/link-footnotes "[^2]: loyly:a/b.md@17d68d2 and nope:x.md@abcd" repos))))))

(deftest status-is-read-never-decided
  (is (= "planned" (site/scenario-status {:id "spec-0001" :wip? true} {"spec-0001" :verified} true)))
  (is (= "planned" (site/scenario-status {:id "spec-0001" :wip? true} nil false)))
  (is (= "not derived" (site/scenario-status {:id "spec-0001"} nil false)))
  (is (= "failed" (site/scenario-status {:id "spec-0001"} {"spec-0001" :failed} true)))
  (is (= "unverified" (site/scenario-status {:id "spec-0002"} {"spec-0001" :failed} true)))
  (is (re-find #"no status was derived because no results were given"
               (str/lower-case (site/status-banner {:derived? false}))))
  (testing "a results directory outside the project is never named"
    (is (not (str/includes? (site/status-banner {:derived? true :results-rel nil}) "/")))
    (is (str/includes? (site/status-banner {:derived? true :results-rel "dhf/allure-results"}) "dhf/allure-results"))))

(deftest the-banner-follows-the-title
  (is (= "# T\n\n> b\n\nbody\n" (site/with-banner "# T\n\nbody\n" "> b")))
  (is (= "> b\n\nbody" (site/with-banner "body" "> b"))))

(deftest a-component-page
  (let [md (site/component-markdown
            {:context {:name "orders"} :comp {:name "Cart" :description "Holds the cart"}
             :prose "Prose here." :statuses {"spec-0001" :verified} :status {:derived? true :results-rel "r"}
             :scenarios [{:id "spec-0001" :name "Adds" :level :e2e :rule "R"
                          :steps [{:keyword "Given" :text "a cart"} {:keyword "Then" :text "it holds `x`"}]}]})]
    (is (str/includes? md "Holds the cart"))
    (is (str/includes? md "Prose here."))
    (is (str/includes? md "#### Adds {#spec-0001}"))
    (is (str/includes? md "- Level: e2e"))
    (is (str/includes? md "- Status: verified"))
    (is (str/includes? md "Given a cart\nThen it holds `x`"))))

(deftest build-findings-name-the-page-and-the-link
  (let [out (str "INFO -  Building\n"
                 "WARNING -  Doc file 'architecture/lint.md' contains a link 'missing.md', but the target "
                 "'architecture/missing.md' is not found among documentation files.\n"
                 "Aborted with 1 warnings in strict mode!\n")
        [f & more] (site/build-findings "site-src" out 1)]
    (is (empty? more))
    (is (= "site-src/docs/architecture/lint.md" (:file f)))
    (is (= "site-broken-link" (:rule f)))
    (is (str/includes? (:message f) "missing.md")))
  (testing "a failed build that says nothing recognisable is still a finding"
    (is (= ["site-build-failed"] (map :rule (site/build-findings "site-src" "boom" 2)))))
  (testing "a clean build has no finding"
    (is (empty? (site/build-findings "site-src" "INFO - Documentation built" 0)))))
