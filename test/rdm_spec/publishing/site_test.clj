(ns rdm-spec.publishing.site-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(deftest the-model-hash-ignores-order-and-formatting
  (let [a (edn/read-string "{:b 1 :a #{:x :y :z} :c [{:k 1 :j 2}]}")
        b (edn/read-string "{:c [{:j 2\n :k 1}]\n :a #{:z :y :x}\n :b 1}")]
    (is (= "{:a #{:x :y :z}, :b 1, :c [{:j 2, :k 1}]}" (site/printed a)))
    (is (= (site/model-hash a) (site/model-hash b)))
    (is (re-matches #"[0-9a-f]{64}" (site/model-hash a)))
    (is (not= (site/model-hash a) (site/model-hash (assoc a :b 2))))))

(deftest a-view-records-its-hash-on-its-first-line
  (let [h (apply str (repeat 64 "a"))]
    (is (= h (site/recorded-hash (str "%% model-sha256: " h "; generated\nC4Context"))))
    (is (= h (site/recorded-hash (str "<!-- model-sha256: " h " -->\n<svg/>"))))
    (is (nil? (site/recorded-hash (str "C4Context\n%% model-sha256: " h))))))

(def model
  {:system {:name "Demo"}
   :containers [{:id :app :contexts [{:id :spec :name "spec" :description "S"
                                      :components [{:id :spec/parser :name "Parser" :spec "spec/parser"}
                                                   {:id :spec/check :name "Spec check" :spec "spec/check"}]}
                                     {:id :architecture :name "architecture" :description "A"
                                      :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"}]}]}]
   :kernel [{:id :kernel/command :name "Command" :spec "kernel/command"}]
   :views [{:key "context" :level :context}
           {:key "spec-components" :level :component :context :spec}]})

(deftest sections-follow-the-model-then-the-kernel
  (let [ss (site/sections model)]
    (is (= ["spec" "architecture" "kernel"] (map :name ss)))
    (is (= ["spec/parser.md" "spec/check.md"] (map :page (:components (first ss)))))
    (is (= "spec-components" (:key (:view (first ss)))))
    (is (nil? (:view (second ss))))
    (is (= ["spec" "architecture"] (map :name (site/sections (dissoc model :kernel)))))))

(deftest the-navigation-lists-home-then-each-section-in-order
  (let [yml (site/mkdocs-yml {:model model :sections (site/sections model) :site-dir "../site"
                              :wiki-pages [{:page "wiki/index.md" :title "Wiki"}]})
        nav-lines (drop-while #(not= "nav:" %) (str/split-lines yml))]
    (is (str/includes? yml "site_dir: \"../site\""))
    (is (= ["  - \"Home\": index.md"
            "  - \"spec\":"
            "      - \"spec\": \"spec/index.md\""
            "      - \"Parser\": \"spec/parser.md\""
            "      - \"Spec check\": \"spec/check.md\""
            "  - \"architecture\":"]
           (take 6 (rest nav-lines))))
    (is (< (str/index-of yml "\"kernel\":") (str/index-of yml "\"Wiki\":")))))

(deftest relative-links-climb-to-the-docs-root
  (is (= "views/x.svg" (site/relative "index.md" "views/x.svg")))
  (is (= "../views/x.svg" (site/relative "spec/index.md" "views/x.svg")))
  (is (= "../../a.md" (site/relative "wiki/pages/p.md" "a.md"))))

(deftest status-is-shown-never-decided
  (let [derived {:derived? true :statuses {"spec-0001" :verified "spec-0002" :failed}}]
    (is (= "verified" (site/status-of derived {:id "spec-0001"})))
    (is (= "failed" (site/status-of derived {:id "spec-0002"})))
    (is (= "unverified" (site/status-of derived {:id "spec-0003"})))
    (is (= "planned" (site/status-of {:derived? false} {:id "spec-0004" :wip? true})))
    (is (= "not derived" (site/status-of {:derived? false} {:id "spec-0001"})))
    (is (re-find #"no status was derived because no results were given"
                 (str/lower-case (site/status-note {:derived? false}))))))

(deftest a-scenario-shows-its-id-level-status-and-steps
  (let [md (site/scenario-md {:derived? true :statuses {"spec-0001" :verified}}
                             {:id "spec-0001" :title "It works" :level :contract :needs ["UN-001"]
                              :steps [{:keyword "Given" :text "a thing"} {:keyword "Then" :text "it works"}]})]
    (is (str/starts-with? md "### spec-0001: It works"))
    (is (str/includes? md "**Level:** contract"))
    (is (str/includes? md "**Status:** verified"))
    (is (str/includes? md "- **Given** a thing"))
    (is (str/includes? md "- **Then** it works"))))

(deftest broken-links-name-the-line-and-the-link
  (let [present #{"index.md" "spec/parser.md" "views/context.svg" "wiki/index.md"}
        text "# P\n\n[ok](parser.md) [up](../index.md#top)\n![v](../views/context.svg)\n[bad](nowhere.md)\n`[code](x.md)`\n[w](../wiki/)\n[ext](https://x.org/a.md)\n"]
    (is (= [{:line 5 :link "nowhere.md"}] (site/broken-links "spec/parser.md" text present)))))

(deftest footnote-citations-link-to-their-source-at-their-commit
  (let [sources [{:id "loyly" :repository "https://github.com/skallinen/loyly"}
                 {:id "short" :repository "owner/name"}]
        text "A claim.[^1] Not linked: `loyly:README.md@17d68d2`.\n\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\".\n"
        out (site/link-citations text sources [{:citation "loyly:README.md@17d68d2"}])]
    (is (= "https://github.com/owner/name/blob/abc1234/a/b.md"
           (site/source-url sources {:source "short" :path "a/b.md" :commit "abc1234"})))
    (is (nil? (site/source-url sources {:source "nobody" :path "a" :commit "abc1234"})))
    (is (str/includes? out "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md)"))
    (testing "only footnote definitions are linked"
      (is (str/includes? out "Not linked: `loyly:README.md@17d68d2`.")))))
