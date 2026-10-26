(ns steps.publishing-site
  "Step definitions of dhf/spec/publishing/site.feature."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn page [ctx p]
  (let [f (fs/file (k/guard! (:dir ctx)) "site-src/docs" p)]
    (when-not (fs/exists? f) (fail! (str "no page " p ": " (:err ctx))))
    (slurp f)))

(defn feature [name scenarios] (k/feature-text {:name name :tags ["UN-001"] :scenarios scenarios}))

(defn site-project!
  "A project of the model spec, a feature per component, views rendered."
  [ctx model-spec & [extra]]
  (let [m (k/model model-spec)
        comps (concat (for [[c ks] (:contexts model-spec) kk ks] (str c "/" kk))
                      (for [kk (:kernel model-spec)] (str "kernel/" kk)))
        ctx (k/project! ctx {:model-spec model-spec
                             :features (into {} (map-indexed (fn [i c] [(str "dhf/spec/" c ".feature")
                                                                        (feature c [(k/sc (inc i) (str "About " c))])])
                                                             comps))
                             :extra extra})]
    (when-not (some #{"kernel/core"} comps) (fs/delete-if-exists (fs/path (:dir ctx) "dhf/spec/kernel/core.feature")))
    (k/views! ctx)))

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx] (site-project! ctx {:contexts [["shop" ["till" "cart" "door"]] ["stock" ["shelf" "crate"]]]})))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [p ["shop/till.md" "shop/cart.md" "shop/door.md" "stock/shelf.md" "stock/crate.md"]] (page ctx p))
    (assert (not (fs/exists? (fs/path (:dir ctx) "site-src/docs/kernel"))))
    ctx))

(def lint-feature
  (feature "Lint" [(k/sc 12 "A view with no description fails the lint" ["Given a view with no description" "When the model is linted" "Then the view is named"])
                   {:tags ["spec-0013" "contract"] :title "A component that owns no feature" :steps ["Given a component" "Then it is named"]}]))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [ctx (site-project! ctx {:contexts [["architecture" ["lint"]]]}
                             {"dhf/spec/architecture/lint.md" "The lint holds the model to the C4 checklist.\n"})]
      (k/write-files! (:dir ctx) {"dhf/spec/architecture/lint.feature" lint-feature})
      ctx)))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [p (page ctx "architecture/lint.md")]
      (doseq [s ["The lint component" "The lint holds the model to the C4 checklist." "spec-0012" "A view with no description fails the lint"
                 "e2e" "Given a view with no description" "When the model is linted" "Then the view is named"
                 "spec-0013" "contract" "Given a component"]]
        (assert (str/includes? p s) (str "the page lacks " s ":\n" p)))
      ctx)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx] (site-project! ctx {:contexts [["architecture" ["lint" "views"]]]})))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [p (page ctx "architecture/index.md")]
      (assert (str/includes? p "architecture-components.svg") p)
      (assert (fs/exists? (fs/path (:dir ctx) "site-src/docs/views/architecture-components.svg")))
      (assert (str/includes? p "(lint.md)") p)
      (assert (str/includes? p "(views.md)") p)
      ctx)))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (let [ctx (if (str/includes? (slurp (fs/file (k/guard! (:dir ctx)) "dhf/spec/model.edn")) ":level :context")
                ctx
                ;; the project the When made has no context or container view: give it both
                (do (k/update-edn! ctx "dhf/spec/model.edn" update :views (fnil into [])
                                   [{:key "context" :level :context :description "The demo and its users"}
                                    {:key "containers" :level :container :description "The program"}])
                    (k/views! ctx)
                    (k/rdm ctx ["publish"])))]
      (k/check-exit ctx 0)
      (let [p (page ctx "index.md")]
        (assert (str/includes? p "views/context.svg") p)
        (assert (str/includes? p "views/containers.svg") p)
        (assert (str/includes? p "UN-001") p)
        (assert (str/includes? p "A user need") p)
        (assert (str/includes? p "spec-0001") p))
      ctx)))

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [ctx (-> (site-project! ctx {:contexts [["architecture" ["lint"]]]}) (k/placeholder! "DIR"))]
      (k/write-files! (:dir ctx) {"dhf/spec/architecture/lint.feature" lint-feature})
      (k/write-result! (get-in ctx [:ph "DIR"]) {:id "spec-0012"})
      (k/write-result! (get-in ctx [:ph "DIR"]) {:id "spec-0013" :status "failed"})
      ctx)))

(defn status-near [p id]
  (let [i (or (str/index-of p (str "### " id)) (fail! (str "no " id " on the page:\n" p)))
        s (subs p i (min (count p) (+ i 300)))]
    s))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [p (page ctx "architecture/lint.md")]
      (assert (str/includes? (status-near p "spec-0012") "verified") p)
      (assert (str/includes? (status-near p "spec-0013") "failed") p)
      ctx)))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (let [ctx (site-project! ctx {:contexts [["architecture" ["lint"]]]})]
      (k/write-files! (:dir ctx) {"dhf/spec/architecture/lint.feature"
                                  (feature "Lint" [{:tags ["spec-0014" "e2e" "wip"] :title "Planned" :steps ["Given a step"]}])})
      ctx)))

(defstep #"spec-0014 shows planned"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (str/includes? (status-near (page ctx "architecture/lint.md") "spec-0014") "planned"))
    ctx))

(defn pages [ctx]
  (let [d (fs/path (k/guard! (:dir ctx)) "site-src/docs")]
    (->> (fs/glob d "**.md") (map #(str (fs/relativize d %))) sort)))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [ps (pages ctx)]
      (assert (some #(str/starts-with? % "wiki/") ps) (pr-str ps))
      (doseq [p ps] (assert (str/includes? (page ctx p) "No status was derived: no results were given") p)))
    ctx))

(defstep #"it exits 1 and names the stale view"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "shop-components" "stale")
    (assert (not (fs/exists? (fs/path (:dir ctx) "site")))) ctx))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx] (let [ctx (k/init-project! ctx)] (assoc ctx :spec-before (k/spec-snapshot ctx)))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [ctx (site-project! ctx {:contexts [["shop" ["till"]]] :kernel ["core"]
                                  :views [{:key "context" :level :context :description "The demo"}]})]
      (k/write-result! (fs/path (:dir ctx) "dhf/allure-results") {:id "spec-0001"})
      ctx)))

(defn tree-bytes [d]
  (into {} (for [f (fs/glob d "**") :when (fs/regular-file? f)] [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

(defstep #"\"rdm-spec publish\" is run twice, the first site kept aside"
  (fn [ctx]
    (let [a (k/rdm ctx ["publish"])
          _ (k/check-exit a 0)
          first-site (tree-bytes (fs/path (:dir ctx) "site-src"))
          b (k/rdm ctx ["publish"])]
      (assoc b ::first first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [second-site (tree-bytes (fs/path (:dir ctx) "site-src"))]
      (assert (seq second-site))
      (assert (= (::first ctx) second-site) "the two sites differ"))
    ctx))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (doseq [[f bs] (tree-bytes (fs/path (:dir ctx) "site-src")) :when (re-find #"\.(md|yml)$" f)]
      (let [t (String. (byte-array bs) "UTF-8")]
        (assert (not (str/includes? t (:base ctx))) f)
        (assert (not (str/includes? t k/tmp-root)) f)))
    ctx))

(defstep #"the page is published and the footnote links to README.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (str/includes? (page ctx "wiki/pages/footnoted.md") "](https://github.com/skallinen/loyly/blob/17d68d2/README.md)")
            (page ctx "wiki/pages/footnoted.md"))
    ctx))

(defstep #"the wiki's index holds a footnote citing \"loyly:README.md@17d68d2\""
  (fn [ctx]
    (let [ctx (k/project! ctx)
          f (fs/file (:dir ctx) "dhf/wiki/index.md")]
      (spit f (str (slurp f) "\nThe wiki rests on its sources.[^i]\n\n[^i]: `loyly:README.md@17d68d2`, \"How to read the repo\".\n"))
      ctx)))

(defstep #"the published index's footnote links to README.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (str/includes? (page ctx "wiki/index.md") "](https://github.com/skallinen/loyly/blob/17d68d2/README.md)") (page ctx "wiki/index.md"))
    ctx))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/wiki-page! ctx "dhf/wiki/pages/one.md" "# One\n\nA claim.[^1]\n\n[^1]: `demo:README.md@abc1234`, \"A\".\n")
      (k/wiki-page! ctx "dhf/wiki/pages/two.md" "# Two\n\nA claim.[^1]\n\n[^1]: `demo:README.md@abc1234`, \"B\".\n"))))

(defn nav [ctx] (slurp (fs/file (k/guard! (:dir ctx)) "site-src/mkdocs.yml")))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (str/includes? (page ctx "index.md") "(wiki/index.md)"))
    (let [i (page ctx "wiki/index.md")]
      (assert (and (str/includes? i "(pages/one.md)") (str/includes? i "(pages/two.md)")) i))
    (let [n (nav ctx)]
      (assert (< (str/index-of n "kernel/index.md") (str/index-of n "wiki/index.md")) n))
    ctx))

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx] (site-project! ctx {:contexts [["spec" ["parser" "check"]] ["architecture" ["model" "lint"]]] :kernel ["bus"]})))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [n (nav ctx) at #(or (str/index-of n %) (fail! (str % " is not in the navigation:\n" n)))]
      (assert (< (at "index.md") (at "spec/index.md") (at "architecture/index.md") (at "kernel/index.md")) n)
      ctx)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (nav ctx) at #(str/index-of n %)]
      (assert (< (at "spec/index.md") (at "spec/parser.md") (at "spec/check.md")) n)
      (assert (< (at "architecture/index.md") (at "architecture/model.md") (at "architecture/lint.md")) n)
      (assert (< (at "kernel/index.md") (at "kernel/bus.md")) n)
      ctx)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (site-project! ctx {:contexts [["shop" ["till"]]]})))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (fs/exists? (fs/path (:dir ctx) "site/index.html")))
    (assert (fs/exists? (fs/path (:dir ctx) "site/shop/till.html")))
    ctx))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (site-project! ctx {:contexts [["shop" ["till"]]]} {"dhf/spec/shop/till.md" "See [the cart](cart-missing.md).\n"})))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "shop/till.md" "cart-missing.md")))

(defn html-left [ctx]
  (let [d (fs/path (k/guard! (:dir ctx)) "site")]
    (when (fs/exists? d) (seq (filter #(str/ends-with? (str %) ".html") (fs/glob d "**"))))))

(defstep #"no HTML is left in the site directory"
  (fn [ctx] (assert (not (html-left ctx)) (pr-str (html-left ctx))) ctx))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [ctx (site-project! ctx {:contexts [["shop" ["till"]]]})]
      (k/update-edn! ctx "dhf/harness/config.edn" assoc-in [:site :builder] ["no-such-site-builder" "build"])
      (k/write-files! (:dir ctx) {"site/index.html" "<p>old</p>\n"})
      ctx)))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "no-such-site-builder" "no HTML was written")))
