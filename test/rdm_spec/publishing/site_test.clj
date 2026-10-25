(ns rdm-spec.publishing.site-test
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def h (apply str (repeat 64 "a")))
(def other (apply str (repeat 64 "b")))

(deftest sha256-hex-is-lowercase-hex
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (site/sha256-hex (byte-array 0))))
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (site/sha256-hex (.getBytes "abc" "UTF-8")))))

(deftest the-hash-is-read-from-the-first-line-only
  (is (= h (site/recorded-hash (str "%% model-hash: " h "\nC4Context\n"))))
  (is (= h (site/recorded-hash (str "<svg><!-- Generated; model-hash: " h " --><g/></svg>"))))
  (is (nil? (site/recorded-hash (str "%% Generated\n%% model-hash: " h "\n"))) "a hash on the second line is none")
  (is (nil? (site/recorded-hash "%% model-hash: abc\n")) "a short hash is none")
  (is (nil? (site/recorded-hash nil))))

(deftest stale-and-missing-views-are-findings
  (let [model {:views [{:key "context"} {:key "containers"}]}
        outputs {"v/context.mmd" (str "%% model-hash: " h "\n")
                 "v/context.svg" (str "<svg><!-- model-hash: " other " --></svg>")
                 "v/containers.mmd" (str "%% model-hash: " h "\n")}
        fs (site/view-findings model h "v" outputs)]
    (is (= [["v/context.svg" "view-stale"] ["v/containers.svg" "view-missing"]]
           (mapv (juxt :file :rule) fs)))
    (is (str/includes? (:message (first fs)) "\"context\""))
    (is (empty? (site/view-findings model h "v" (constantly (str "%% model-hash: " h)))))))

(def model
  {:system {:name "Sys" :description "The system"}
   :containers [{:id :app
                 :contexts [{:id :b :name "b" :description "Context b"
                             :components [{:id :b/two :name "Two" :spec "b/two" :description "Second"}
                                          {:id :b/one :name "One" :spec "b/one" :description "First"}]}
                            {:id :a :name "a" :description "Context a"
                             :components [{:id :a/x :name "X Y" :description "No spec"}]}]}
                {:id :db}]
   :kernel [{:id :kernel/k :name "K" :spec "kernel/k" :description "Shared"}]
   :views [{:key "context" :level :context :description "Ctx"}
           {:key "b-components" :level :component :context :b :description "B"}]})

(deftest sections-follow-the-model-kernel-last
  (let [secs (site/sections model)]
    (is (= ["b" "a" "kernel"] (mapv :name secs)))
    (is (= [["b/two.md" "b/one.md"] ["a/x-y.md"] ["kernel/k.md"]] (mapv #(mapv :page (:components %)) secs)))
    (is (= "b-components" (:key (:view (first secs)))))
    (is (nil? (:view (second secs))))
    (is (= ["b" "a"] (mapv :name (site/sections (dissoc model :kernel)))) "no kernel, no kernel section")
    (is (= 2 (count (site/sections (assoc model :kernel [])))))))

(deftest the-builder-configuration-orders-the-navigation-by-the-model
  (let [y (yaml/parse-string (site/mkdocs-yml {:site-name "Sys \"q\"" :site-dir "../site" :sections (site/sections model)})
                             :keywords false)]
    (is (= "Sys \"q\"" (get y "site_name")))
    (is (= "../site" (get y "site_dir")))
    (is (some #{"footnotes"} (get y "markdown_extensions")))
    (is (= [{"Home" "index.md"}
            {"b" [{"b" "b/index.md"} {"Two" "b/two.md"} {"One" "b/one.md"}]}
            {"a" [{"a" "a/index.md"} {"X Y" "a/x-y.md"}]}
            {"kernel" [{"kernel" "kernel/index.md"} {"K" "kernel/k.md"}]}]
           (mapv #(into {} %) (map (fn [e] (update-vals (into {} e) (fn [v] (if (sequential? v) (mapv (partial into {}) v) v))))
                                   (get y "nav")))))))

(deftest status-shown
  (is (= "planned" (site/status-of {"spec-1" :verified} {:id "spec-1" :wip? true})))
  (is (= "planned" (site/status-of nil {:id "spec-1" :wip? true})) "planned without results")
  (is (nil? (site/status-of nil {:id "spec-1"})) "nothing derived without results")
  (is (= "failed" (site/status-of {"spec-1" :failed} {:id "spec-1"})))
  (is (= "unverified" (site/status-of {} {:id "spec-1"})))
  (is (str/includes? (str/lower-case (site/status-notice {:derived? false}))
                     "no status was derived because no results were given"))
  (is (str/includes? (site/status-notice {:derived? true :results "r"}) "`r`")))

(deftest a-scenario-shows-its-id-level-status-and-steps
  (let [md (site/scenario-md {:id "spec-0007" :name "A <b> case" :level "e2e" :needs ["UN-001"]
                              :steps [{:keyword "Given" :text "a `x` thing"}
                                      {:keyword "Then" :text "it holds" :data-table [["a" "b"]]}]
                              :examples [{:header ["k"] :rows [{:cells ["v"]}]}]}
                             "verified")]
    (is (str/includes? md "<a id=\"spec-0007\"></a>"))
    (is (str/includes? md "#### spec-0007: A &lt;b&gt; case"))
    (is (str/includes? md "**Level:** e2e · **Status:** verified · **Needs:** UN-001"))
    (is (str/includes? md "Given a `x` thing"))
    (is (str/includes? md "  | a | b |"))
    (is (str/includes? md "Examples:"))
    (is (str/includes? (site/scenario-md {:id "s" :name "n" :steps []} nil) "**Status:** not derived"))))

(deftest code-fences-outgrow-backticks
  (is (= "```" (site/fence "plain")))
  (is (= "````" (site/fence "a ``` b"))))

(deftest prose-headings-are-demoted-outside-code
  (is (= "## A\ntext\n```\n# not a heading\n```\n### B"
         (site/demote-headings "# A\ntext\n```\n# not a heading\n```\n## B"))))

(deftest relative-links-between-pages
  (is (= "b/one.md" (site/relative "index.md" "b/one.md")))
  (is (= "../views/x.svg" (site/relative "b/index.md" "views/x.svg")))
  (is (= "one.md" (site/relative "b/index.md" "b/one.md")))
  (is (= "index.md" (site/relative "b/one.md" "b/index.md"))))

(deftest citations-link-to-their-source-at-their-pin
  (let [repos {"loyly" "https://github.com/skallinen/loyly.git" "local" "skallinen/loyly"}]
    (is (= "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md), \"x\" 1."
           (site/link-citations "[^1]: `loyly:README.md@17d68d2`, \"x\" 1." repos)))
    (is (= "see [loyly:a/b.md@17d68d2](https://github.com/skallinen/loyly/blob/17d68d2/a/b.md)."
           (site/link-citations "see loyly:a/b.md@17d68d2." repos)))
    (testing "left alone"
      (doseq [t ["[`loyly:README.md@17d68d2`](elsewhere)" "`other:README.md@17d68d2`"
                 "https://example.org/x@17d68d2" "`local:README.md@17d68d2`"]]
        (is (= t (site/link-citations t repos)) t)))))

(deftest builder-output-becomes-findings
  (let [out "INFO - x\nWARNING -  Doc file 'architecture/lint.md' contains a link 'missing.md', but the target is not found among documentation files.\nAborted with 1 warnings in strict mode!"
        [f :as fs] (site/build-findings 1 out "site-src/docs")]
    (is (= 1 (count fs)))
    (is (= ["site-src/docs/architecture/lint.md" "site-broken-link"] [(:file f) (:rule f)]))
    (is (str/includes? (:message f) "missing.md")))
  (is (empty? (site/build-findings 0 "INFO - built" "d")))
  (is (= ["site-build"] (mapv :rule (site/build-findings 1 "Error: boom" "d"))) "a failure with no warning is still one"))
