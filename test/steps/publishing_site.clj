(ns steps.publishing-site
  "Steps of the component Site (09-publish/site.feature). Pages are read from
  the site's Markdown under site-src/docs/, HTML from site/."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn page [ctx rel] (k/slurp-in (:dir ctx) (str "site-src/docs/" rel)))
(defn pages [ctx] (let [d (k/path (:dir ctx) "site-src/docs")] (if (fs/exists? d) (sort (map #(str (fs/relativize d %)) (fs/glob d "**.md"))) [])))
(defn html? [ctx] (seq (fs/glob (k/path (:dir ctx) "site") "**.html")))

(def site-model
  {:system {:id :s :name "Sauna" :description "Books saunas"}
   :people [{:id :guest :name "Guest" :actor #{:human} :description "Books a sauna"}]
   :containers [{:id :app :name "App" :kind :runs :technology "a program" :description "The one program"
                 :contexts [{:id :spec :name "spec" :description "Reads the spec"
                             :components [{:id :spec/parser :name "Parser" :description "Reads Gherkin" :spec "spec/parser"}
                                          {:id :spec/stamper :name "Stamper" :description "Stamps ids" :spec "spec/stamper"}]}
                            {:id :architecture :name "architecture" :description "The C4 model"
                             :components [{:id :architecture/lint :name "Lint" :description "Lints the model" :spec "architecture/lint"}
                                          {:id :architecture/views :name "Views" :description "Draws views" :spec "architecture/views"}
                                          {:id :architecture/model :name "Model" :description "Reads the model" :spec "architecture/model"}]}]}]
   :relationships [{:from :guest :to :app :description "Books with"}]
   :views [{:key "context" :level :context :description "The system and its people"}
           {:key "containers" :level :container :description "The program"}
           {:key "spec-components" :level :component :context :spec :description "Reading"}
           {:key "architecture-components" :level :component :context :architecture :description "The model"}]})

(defn site-project!
  "A project with two bounded contexts holding five components, each with a feature file, rendered views and a wiki."
  [& {:keys [model extra]}]
  (let [m (or model site-model)
        comps (concat (mapcat :components (mapcat :contexts (:containers m))) (:kernel m))
        ctx (k/spec-project! (merge (into {} (for [[i c] (map-indexed vector comps)]
                                               [(str (:spec c) ".feature")
                                                (k/feature-text {:name (:name c) :tags ["UN-001"]
                                                                 :scenarios [{:id (format "spec-%04d" (+ 11 i)) :title (str (:name c) " works")}]})]))
                                    extra)
                             :model m)]
    (k/write! (:dir ctx) "dhf/wiki/pages/one.md" "# One\n\nA claim.[^a]\n\n[^a]: `loyly:README.md@17d68d2`, \"one\".\n")
    (k/write! (:dir ctx) "dhf/wiki/index.md" "# Wiki\n\nThe domain.\n\n## Pages\n\n- [One](pages/one.md): the first.\n")
    ctx))

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [_] (site-project!)))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (k/exits! ctx 0)
    (doseq [p ["spec/parser.md" "spec/stamper.md" "architecture/lint.md" "architecture/views.md" "architecture/model.md"]]
      (k/expect! (page ctx p) ctx (str "no page " p)))
    (k/expect! (str/includes? (page ctx "architecture/lint.md") "architecture/lint") ctx "the page is not named for its context and component")
    ctx))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [_] (let [ctx (site-project!)]
            (k/write! (:dir ctx) "dhf/spec/architecture/lint.md" "# Lint\n\nThe lint holds the model to a checklist.\n")
            ctx)))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [p (page ctx "architecture/lint.md")]
      (doseq [x ["Lints the model" "The lint holds the model to a checklist." "spec-0013" "e2e" "Given a thing" "Then it holds"]]
        (k/expect! (str/includes? p x) ctx (str "the page lacks " x))))
    ctx))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [_] (let [ctx (site-project!)] (k/exits! (k/rdm! ctx "views") 0) ctx)))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [p (page ctx "architecture/index.md")]
      (k/expect! (re-find #"!\[[^\]]*\]\(\.\./views/architecture-components\.svg\)" p) ctx "the view is not shown")
      (k/expect! (fs/exists? (k/path (:dir ctx) "site-src/docs/views/architecture-components.svg")) ctx "the view is not in the site")
      (doseq [c ["lint" "views" "model"]] (k/expect! (str/includes? p (str "(" c ".md)")) ctx (str "no link to " c))))
    ctx))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [s (site-project!)
          _ (k/exits! (k/rdm! s "views") 0)
          r (k/rdm! s "publish")
          home (page s "index.md")]
      (k/exits! r 0)
      (doseq [x ["views/context.svg" "views/containers.svg" "UN-001" "UN-002" "UN-003" "spec-0011" "spec-0015"]]
        (k/expect! (str/includes? home x) r (str "the home page lacks " x))))
    ctx))

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [_] (let [ctx (k/var-dir! (site-project!) "DIR") d (get-in ctx [:vars "DIR"])]
            (k/result! d "spec-0012" "passed") (k/result! d "spec-0013" "failed") ctx)))

(defn shows [ctx id]
  (some (fn [p] (some #(when (str/includes? % id) %) (str/split-lines (page ctx p)))) (pages ctx)))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (str/includes? (str (shows ctx "spec-0012")) "verified") ctx "spec-0012 does not show verified")
    (k/expect! (str/includes? (str (shows ctx "spec-0013")) "failed") ctx "spec-0013 does not show failed")
    ctx))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [_] (site-project! :extra {"architecture/lint.feature" (k/feature-text {:name "Lint" :scenarios [{:id "spec-0013"} {:id "spec-0014" :wip true}]})})))

(defstep #"spec-0014 shows planned"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (str/includes? (str (shows ctx "spec-0014")) "planned") ctx "spec-0014 does not show planned") ctx))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (some #(str/starts-with? % "wiki/") (pages ctx)) ctx "no wiki page")
    (doseq [p (pages ctx)]
      (k/expect! (str/includes? (str/lower-case (page ctx p)) "no status was derived because no results were given") ctx (str p " does not say so")))
    ctx))

(defstep #"it exits 1 and names the stale view"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "dhf/views/shop-components" "stale") (k/expect! (not (html? ctx)) ctx "HTML was written") ctx))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [_] (k/keep-spec (k/new-project))))

(defstep #"a spec, its views and a results directory"
  (fn [_] (let [ctx (site-project!)]
            (k/exits! (k/rdm! ctx "views") 0)
            (k/result! (k/path (:dir ctx) "dhf/allure-results") "spec-0011" "passed")
            ctx)))

(defn site-files [ctx]
  (into (sorted-map) (for [f (fs/glob (k/path (:dir ctx) "site-src") "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize (k/path (:dir ctx) "site-src") f)) (slurp (str f))])))

(defstep #"\"rdm-spec publish\" is run twice, the first site kept aside"
  (fn [ctx]
    (let [a (k/rdm! ctx "publish") first-site (site-files ctx) b (k/rdm! ctx "publish")]
      (k/exits! a 0)
      (assoc b :first-site first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= (:first-site ctx) (site-files ctx)) ctx "the sites differ") ctx))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (doseq [[f t] (site-files ctx) :when (not (str/ends-with? f ".svg"))]
      (k/expect! (not (or (str/includes? t (str (:dir ctx))) (str/includes? t (k/tmp-root)) (str/includes? t "/home/"))) ctx (str f " names a path outside the project")))
    ctx))

(defstep #"the page is published and the footnote links to README.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (str/includes? (page ctx "wiki/pages/a-page.md") "https://github.com/skallinen/loyly/blob/17d68d2/README.md") ctx "the footnote is not linked")
    ctx))

(defstep #"the wiki's index holds a footnote citing \"loyly:README.md@17d68d2\""
  (fn [_] (let [ctx (site-project!)]
            (k/write! (:dir ctx) "dhf/wiki/index.md" "# Wiki\n\nThe domain.[^w]\n\n## Pages\n\n- [One](pages/one.md): the first.\n\n[^w]: `loyly:README.md@17d68d2`, \"the wiki\".\n")
            ctx)))

(defstep #"the published index's footnote links to README.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (str/includes? (page ctx "wiki/index.md") "https://github.com/skallinen/loyly/blob/17d68d2/README.md") ctx "the index's footnote is not linked")
    ctx))

(defstep #"a wiki whose index lists two pages"
  (fn [_] (let [ctx (site-project! :model (assoc site-model :kernel [{:id :kernel/bus :name "Event bus" :description "Carries events" :spec "kernel/bus"}]))]
            (k/write! (:dir ctx) "dhf/wiki/pages/two.md" "# Two\n\nA claim.[^a]\n\n[^a]: `loyly:README.md@17d68d2`, \"two\".\n")
            (k/write! (:dir ctx) "dhf/wiki/index.md" "# Wiki\n\n## Pages\n\n- [One](pages/one.md): the first.\n- [Two](pages/two.md): the second.\n")
            ctx)))

(defn nav [ctx] (k/slurp-in (:dir ctx) "site-src/mkdocs.yml"))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (str/includes? (page ctx "index.md") "(wiki/index.md)") ctx "the home page does not link the wiki")
    (k/expect! (and (str/includes? (page ctx "wiki/index.md") "pages/one.md") (str/includes? (page ctx "wiki/index.md") "pages/two.md")) ctx "the index does not link both")
    (let [n (nav ctx)]
      (k/expect! (< (str/index-of n "kernel/index.md") (str/index-of n "wiki/index.md")) ctx "the wiki is not after the kernel"))
    ctx))

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [_] (site-project! :model (assoc site-model :kernel [{:id :kernel/bus :name "Event bus" :description "Carries events" :spec "kernel/bus"}]))))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [n (nav ctx) at #(or (str/index-of n %) (fail! (str "the navigation lacks " %)))]
      (k/expect! (< (at "index.md") (at "spec/index.md") (at "architecture/index.md") (at "kernel/index.md")) ctx "not in that order"))
    ctx))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (nav ctx) at #(str/index-of n %)]
      (k/expect! (< (at "spec/index.md") (at "spec/parser.md") (at "spec/stamper.md")) ctx "spec's pages are out of order")
      (k/expect! (< (at "architecture/index.md") (at "architecture/lint.md") (at "architecture/views.md") (at "architecture/model.md")) ctx "architecture's pages are out of order"))
    ctx))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [_] (site-project!)))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (fs/exists? (k/path (:dir ctx) "site/index.html")) ctx "no HTML")
    (k/expect! (some #{"--strict"} (get-in (k/config (:dir ctx)) [:site :builder])) ctx "the builder is not strict")
    ctx))

(defstep #"a component page that links to a page the site does not hold"
  (fn [_] (let [ctx (site-project!)]
            (k/write! (:dir ctx) "dhf/spec/architecture/lint.md" "# Lint\n\nSee [the missing page](nowhere.md).\n")
            ctx)))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "architecture/lint.md" "nowhere.md") ctx))

(defstep #"no HTML is left in the site directory"
  (fn [ctx] (k/expect! (not (html? ctx)) ctx "HTML is left") ctx))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [_] (let [ctx (site-project!)]
            (k/write! (:dir ctx) "site/index.html" "<html>old</html>\n")
            (k/edit-config! (:dir ctx) #(assoc-in % [:site :builder] ["no-such-site-builder" "build" "--strict" "-f" "site-src/mkdocs.yml"]))
            ctx)))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx] (k/exits! ctx 2) (k/line-has! ctx "no-such-site-builder" "no HTML was written") ctx))
