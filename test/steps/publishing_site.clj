(ns steps.publishing-site
  "Steps of the component Site (dhf/spec/09-publish/site.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn page [ctx rel] (k/slurp-rel ctx (str "site-src/docs/" rel)))

(defn features-of [model]
  (into {} (map-indexed (fn [i c] [(str "dhf/spec/" (:spec c) ".feature")
                                   (k/feature-text (:name c) ["UN-001"] [{:id (format "spec-%04d" (inc i)) :title (str (:name c) " works")
                                                                         :steps ["Given a thing" "Then it works"]}])])
                        (concat (for [c (:containers model) ctx (:contexts c) comp (:components ctx)] comp) (:kernel model)))))

(defn site-project! [ctx model & [opts]]
  (k/spec-project! ctx (merge {:model model :features (features-of model)} opts)))

(def five (k/context-model [[:shop [[:cart "Cart"] [:till "Till"] [:shelf "Shelf"]]] [:stock [[:depot "Depot"] [:truck "Truck"]]]]))

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx] (site-project! ctx five)))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [p ["shop/cart.md" "shop/till.md" "shop/shelf.md" "stock/depot.md" "stock/truck.md"]]
      (k/check! (page ctx p) "no page " p))
    ctx))

(def lint-model (k/context-model [[:architecture [[:lint "Lint"]]]]))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [ctx (site-project! ctx lint-model)]
      (k/write! ctx "dhf/spec/architecture/lint.md" "# Lint\n\nThe lint holds the model to the checklist.\n"))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [p (page ctx "architecture/lint.md")]
      (doseq [s ["Lint does its part" "The lint holds the model to the checklist." "spec-0001" "Lint works" "e2e" "Given a thing" "Then it works"]]
        (k/check! (str/includes? (str p) s) "Lint's page does not hold " (pr-str s) ":\n" p)))
    ctx))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx] (site-project! ctx (k/context-model [[:architecture [[:lint "Lint"] [:views "Views"]]]]))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [p (page ctx "architecture/index.md")]
      (k/check! (re-find #"!\[[^\]]*\]\(\.\./views/architecture-components\.svg\)" (str p)) "the page does not show the view:\n" p)
      (k/check! (and (str/includes? p "(lint.md)") (str/includes? p "(views.md)")) "the page does not link its components:\n" p)
      (k/check! (fs/exists? (k/path ctx "site-src/docs/views/architecture-components.svg")) "the view is not published"))
    ctx))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [p (page ctx "index.md")]
      (k/check! (str/includes? p "](views/context.svg)") "the home page does not show the context view")
      (k/check! (str/includes? p "](views/containers.svg)") "the home page does not show the container view")
      (doseq [n (map #(format "UN-%03d" %) (range 1 16))] (k/check! (str/includes? p n) "the home page does not show " n))
      (k/check! (str/includes? p "spec-0176") "the home page does not list the scenarios tracing to UN-009"))
    ctx))

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [ctx (site-project! ctx (k/context-model [[:architecture [[:lint "Lint"]]]])
                             {:features {"dhf/spec/architecture/lint.feature"
                                         (k/feature-text "Lint" ["UN-001"] [{:id "spec-0012" :title "Twelve"} {:id "spec-0013" :title "Thirteen"}])}})
          d (str (:base ctx) "/dir")]
      (k/result-json! ctx d {:spec "spec-0012" :status "passed"})
      (k/result-json! ctx d {:spec "spec-0013" :status "failed"})
      (assoc-in ctx [:ph "DIR"] d))))

(defstep #"\"rdm-spec publish --results DIR\" is run with that results directory as DIR"
  (fn [ctx] (k/run-rdm ctx "publish --results DIR")))

(defn shows! [ctx id status]
  (let [p (page ctx "architecture/lint.md")]
    (k/check! (re-find (re-pattern (str "(?s)" id ".*?Status: " status)) (str p)) id " does not show " status ":\n" p)
    ctx))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx] (k/exits! ctx 0) (shows! ctx "spec-0012" "verified") (shows! ctx "spec-0013" "failed")))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (site-project! ctx (k/context-model [[:architecture [[:lint "Lint"]]]])
                   {:features {"dhf/spec/architecture/lint.feature" (k/feature-text "Lint" ["UN-001"] [{:id "spec-0014" :title "Planned" :wip? true}])}})))

(defstep #"spec-0014 shows planned"
  (fn [ctx] (k/exits! ctx 0) (shows! ctx "spec-0014" "planned")))

(defstep #"\"rdm-spec publish\" is run with no --results"
  (fn [ctx] (k/run-rdm ctx "publish")))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [d (k/path ctx "site-src/docs")
          pages (filter #(str/ends-with? (str %) ".md") (fs/glob d "**"))]
      (k/check! (some #(str/includes? (str %) "wiki/pages/") pages) "no wiki page is published")
      (doseq [p pages]
        (k/check! (str/includes? (slurp (str p)) "no status was derived because no results were given") (fs/relativize d p) " does not say so")))
    ctx))

(defstep #"it exits 1 and names the stale view"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/views/context" "stale") ctx))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx] (k/project! ctx)))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [ctx (site-project! ctx lint-model)]
      (k/result-json! ctx "dhf/allure-results" {:spec "spec-0001"})
      ctx)))

(defn snapshot [ctx]
  (let [d (k/path ctx "site-src")]
    (into (sorted-map) (for [f (fs/glob d "**") :when (and (fs/regular-file? f) (re-find #"\.(md|yml)$" (str f)))]
                         [(str (fs/relativize d f)) (slurp (str f))]))))

(defstep #"\"rdm-spec publish\" is run twice, the first site kept aside"
  (fn [ctx]
    (let [a (k/run-rdm ctx "publish")
          first-site (snapshot a)
          b (k/run-rdm a "publish")]
      (assoc b ::first first-site ::first-exit (:exit a)))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= 0 (::first-exit ctx)) "the first publish failed")
    (k/check! (seq (::first ctx)) "nothing was published")
    (k/check! (= (::first ctx) (snapshot ctx)) "the two sites differ")
    ctx))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (doseq [[f t] (snapshot ctx)]
      (k/check! (not (str/includes? t (str (:base ctx)))) f " names a path outside the project")
      (k/check! (not (str/includes? t "/home/")) f " names a machine's path"))
    ctx))

;; ------------------------------------------------------------------ the wiki

(defn loyly! [ctx]
  (k/write-edn! ctx "dhf/raw/sources.edn" (assoc k/demo-sources :loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"] :fenced []}))
  (k/write! ctx "dhf/raw/loyly.md" "# loyly\n")
  ctx)

(defn page! [ctx name text]
  (k/write! ctx (str "dhf/wiki/pages/" name ".md") text)
  (k/write! ctx "dhf/wiki/index.md" (str (k/slurp-rel ctx "dhf/wiki/index.md") "\n- [" name "](pages/" name ".md): a page.\n")))

(defstep #"a wiki page whose footnote cites \"loyly:README.md@17d68d2\""
  (fn [ctx]
    (let [ctx (loyly! (site-project! ctx lint-model))]
      (page! ctx "method" "# Method\n\nA claim.[^a]\n\n[^a]: `loyly:README.md@17d68d2`, \"The idea\".\n")
      ctx)))

(def loyly-link "https://github.com/skallinen/loyly/blob/17d68d2/README.md")

(defstep #"the page is published and the footnote links to README.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (str/includes? (str (page ctx "wiki/pages/method.md")) (str "](" loyly-link ")")) "the footnote is not linked:\n" (page ctx "wiki/pages/method.md"))
    ctx))

(defstep #"the wiki's index holds a footnote citing \"loyly:README.md@17d68d2\""
  (fn [ctx]
    (let [ctx (loyly! (site-project! ctx lint-model))]
      (k/write! ctx "dhf/wiki/index.md" (str (k/slurp-rel ctx "dhf/wiki/index.md") "\nThe rules come from Löyly.[^r]\n\n[^r]: `loyly:README.md@17d68d2`, \"Rules\".\n")))))

(defstep #"the published index's footnote links to README.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (str/includes? (str (page ctx "wiki/index.md")) (str "](" loyly-link ")")) "the footnote is not linked:\n" (page ctx "wiki/index.md"))
    ctx))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [ctx (site-project! ctx (assoc lint-model :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus" :description "Delivers events"}]))]
      (page! ctx "one" "# One\n\nA claim.[^a]\n\n[^a]: `demo:README.md@abc1234`, \"x\".\n")
      (page! ctx "two" "# Two\n\nA claim.[^a]\n\n[^a]: `demo:README.md@abc1234`, \"x\".\n")
      ctx)))

(defn nav-lines [ctx] (str/split-lines (k/slurp-rel ctx "site-src/mkdocs.yml")))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (str/includes? (page ctx "index.md") "(wiki/index.md)") "the home page does not link the wiki")
    (k/check! (and (str/includes? (page ctx "wiki/index.md") "(pages/one.md)") (str/includes? (page ctx "wiki/index.md") "(pages/two.md)")) "the index does not link both pages")
    (let [y (k/slurp-rel ctx "site-src/mkdocs.yml")]
      (k/check! (< (str/index-of y "- Kernel:") (str/index-of y "- Wiki:")) "the wiki section is not after the kernel:\n" y))
    ctx))

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (site-project! ctx (assoc (k/context-model [[:spec [[:parser "Parser"] [:stamper "Stamper"]]] [:architecture [[:model "Model"] [:lint "Lint"]]]])
                              :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus" :description "Delivers events"}]))))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [tops (map str/trim (filter #(re-find #"^  - " %) (nav-lines ctx)))]
      (k/check! (= ["- Home: index.md" "- spec:" "- architecture:" "- Kernel:"] (take 4 tops)) "the navigation is " (pr-str tops))
      ctx)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [y (k/slurp-rel ctx "site-src/mkdocs.yml")
          order ["spec/index.md" "spec/parser.md" "spec/stamper.md" "architecture/index.md" "architecture/model.md" "architecture/lint.md" "kernel/bus.md"]
          idx (map #(str/index-of y %) order)]
      (k/check! (and (every? some? idx) (apply < idx)) "the navigation is not in the model's order:\n" y)
      ctx)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (site-project! ctx lint-model)))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (fs/exists? (k/path ctx "site/index.html")) "no HTML was written")
    (k/check! (str/includes? (k/slurp-rel ctx "site-src/mkdocs.yml") "strict") "the builder is not strict")
    ctx))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [ctx (site-project! ctx lint-model)]
      (k/write! ctx "dhf/spec/architecture/lint.md" "# Lint\n\nSee [the missing page](nowhere.md).\n"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "architecture/lint.md" "nowhere.md") ctx))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (k/check! (empty? (filter #(str/ends-with? (str %) ".html") (fs/glob (k/path ctx "site") "**"))) "HTML is left in site/")
    ctx))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [ctx (site-project! ctx lint-model)]
      (k/write! ctx "site/index.html" "<html>old</html>\n")
      (k/config! ctx assoc-in [:site :builder] ["no-such-site-builder" "build" "-f" "site-src/mkdocs.yml"])
      ctx)))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx] (k/exits! ctx 2) (k/err-line-has! ctx "no-such-site-builder" "no HTML was written") ctx))
