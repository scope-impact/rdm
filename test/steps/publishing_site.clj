(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature. Each scenario
  publishes a project of its own: a copy of the room (the kernel's
  copy-of-room!), or one whose model, feature files and views the scenario
  writes, or one `rdm-spec init` wrote; never the room.

  The site is read as the site builder reads it: its configuration is the
  mkdocs.yml publish writes under the configured :site :source, the pages
  are the Markdown files under that configuration's docs_dir, and the HTML
  is what the builder wrote into the configured :site :dir.

  Context keys of this file: :model the fixture's model (when the scenario
  wrote one), :prose the design prose a Given wrote, :first-site the first
  site's files when the command ran twice. The kernel's :dir, :run,
  :results, :spec-before and :page are read as the kernel names them."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Reading the published site

(defn- site-config [dir] (merge {:dir "site" :source "site-src"} (:site (kc/config-of dir))))

(defn- builder-config-file [dir] (fs/file dir (:source (site-config dir)) "mkdocs.yml"))

(defn builder-config
  "The site builder's configuration publish wrote, read."
  [dir]
  (let [f (builder-config-file dir)]
    (s/check! (fs/exists? f) (str "publish wrote no " (fs/relativize dir f)))
    (yaml/parse-string (slurp (str f)) :keywords false)))

(defn docs-dir [dir]
  (fs/file dir (:source (site-config dir)) (get (builder-config dir) "docs_dir" "docs")))

(defn page-text
  "The text of the published page `rel` (relative to the docs directory)."
  [dir rel]
  (let [f (fs/file (docs-dir dir) rel)]
    (s/check! (fs/regular-file? f) (str "the site holds no page " rel))
    (slurp (str f))))

(defn pages
  "{rel text} of every published Markdown page."
  [dir]
  (let [d (docs-dir dir)]
    (into (sorted-map) (for [f (fs/glob d "**.md")] [(str (fs/relativize d f)) (slurp (str f))]))))

(defn- links
  "Every Markdown link and image target of `text`."
  [text]
  (map second (re-seq #"\]\(([^)\s]+)\)" text)))

(defn- resolves?
  "Whether the link `target` from the page `rel` names a file of the docs."
  [dir rel target]
  (let [t (first (str/split target #"#"))]
    (fs/exists? (fs/normalize (fs/file (docs-dir dir) (or (fs/parent rel) "") t)))))

(defn- links-to!
  "Checks that the page `rel` links to the page or image `target-rel` (both
  relative to the docs directory)."
  [dir rel target-rel]
  (let [text (page-text dir rel)
        base (if-let [p (fs/parent rel)] (str p "/") "")
        hits (filter #(and (resolves? dir rel %)
                           (= (str (fs/normalize (fs/path (str base (first (str/split % #"#"))))))
                              (str (fs/normalize (fs/path target-rel)))))
                     (links text))]
    (s/check! (seq hits) (str rel " does not link to " target-rel "\n" text))))

(defn- published! [ctx]
  (let [r (kc/exits! ctx 0)] r))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- slug [s] (str/lower-case (str/replace s #"\s+" "-")))

(defn- model-of
  "A model of one container holding `contexts` [[ctx [component-name ..]] ..]
  and the kernel components `kernel`, with no view."
  [contexts kernel]
  {:system {:id :demo :name "Demo" :description "A demo system"}
   :people [{:id :engineer :name "Engineer" :description "Writes the spec" :actor #{:human}}]
   :external []
   :containers [{:id :app :name "App" :description "The demo app" :technology "a language" :kind :runs
                 :contexts (vec (for [[c comps] contexts]
                                  {:id (keyword c) :name c :description (str "The " c " context")
                                   :components (vec (for [n comps]
                                                      {:id (keyword c (slug n)) :name n
                                                       :description (str n ", a component of " c)
                                                       :spec (str c "/" (slug n))}))}))}]
   :kernel (vec (for [n kernel] {:id (keyword "kernel" (slug n)) :name n :description (str n ", shared code")
                                 :spec (str "kernel/" (slug n))}))
   :relationships []
   :views []})

(defn- spec-paths [model]
  (concat (for [c (:containers model) x (:contexts c) comp (:components x)] (:spec comp))
          (map :spec (:kernel model))))

(defn- model-project!
  "ctx with :dir, a copy of the room whose spec is `model` with one stamped
  scenario per component, and no view."
  [ctx model]
  (let [dir (kc/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "*/**") :when (fs/regular-file? f)] (fs/delete f))
    (fs/delete-tree (fs/file dir "dhf/views"))
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (doseq [[i p] (map-indexed vector (spec-paths model))]
      (s/write! dir (str "dhf/spec/" p ".feature")
                (str "@UN-001\nFeature: " p "\n  A fixture.\n\n  Rule: One\n\n    @spec-" (format "%04d" (inc i)) " @e2e\n"
                     "    Scenario: The " p " scenario\n      Given a project\n      When \"rdm-spec check\" is run\n"
                     "      Then it passes\n")))
    (s/write! dir "dhf/spec/ids.edn" (str "{:issued " (count (spec-paths model)) "}\n"))
    (assoc ctx :dir dir :model model)))

(defn- room! [ctx] (assoc ctx :dir (kc/copy-of-room!)))

(defn- result! [dir id status]
  (let [uuid (str (random-uuid))]
    (s/write! dir (str uuid "-result.json")
              (json/generate-string {:uuid uuid :name (str "a test of " id) :status status
                                     :labels [{:name "spec" :value id}]}))))

(defn- room-scenarios
  "Every scenario of the project's spec, parsed: [{:id :file :sc}]."
  [dir]
  (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
        sc (:scenarios (gherkin/parse-feature f (slurp f)))]
    {:id (some #(second (re-matches #"@(spec-\d+)" %)) (:tags sc)) :file f :sc sc}))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx] (model-project! ctx (model-of [["orders" ["Cart" "Payment" "Shipping"]] ["billing" ["Invoice" "Refund"]]] []))))

(defn- component-pages
  "The published pages of components: every page but an index, outside the wiki."
  [dir]
  (sort (remove #(or (= "index.md" (str (fs/file-name %))) (str/starts-with? % "wiki/")) (keys (pages dir)))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (published! ctx)
    (let [m (:model ctx)
          comps (for [c (:containers m) x (:contexts c) comp (:components x)] [(:name x) comp])
          want (sort (for [[_ comp] comps] (str (:spec comp) ".md")))]
      (s/check! (= 5 (count comps)) "the fixture holds other than five components")
      (s/check! (= want (component-pages (:dir ctx))) (str "the component pages are " (pr-str (component-pages (:dir ctx)))))
      (doseq [[ctx-name comp] comps
              :let [title (first (str/split-lines (page-text (:dir ctx) (str (:spec comp) ".md"))))]]
        (s/check! (and (str/starts-with? title "# ") (str/includes? title (:name comp)) (str/includes? title ctx-name))
                  (str (:spec comp) ".md is titled " title)))
      nil)))

(def prose
  (str "# How the lint reads the model\n\n"
       "The lint reads the model once and holds every view to its limit.\n\n"
       "## What it never judges\n\n"
       "Whether a view is easy to read is a person's call.\n"))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [ctx (room! ctx)]
      (s/write! (:dir ctx) "dhf/spec/architecture/lint.md" prose)
      (assoc ctx :prose prose))))

(defn- model-component [dir id]
  (let [m (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn"))))]
    (first (for [c (:containers m) x (:contexts c) comp (:components x) :when (= id (:id comp))] comp))))

(defn- section
  "The lines of `text` after the first line holding `id` in a heading, up to
  the next heading of that level or above."
  [text id]
  (let [lines (str/split-lines text)
        i (first (keep-indexed (fn [i l] (when (and (str/starts-with? l "#") (str/includes? l id)) i)) lines))]
    (when i
      (let [level (count (re-find #"^#+" (nth lines i)))
            after (drop (inc i) lines)]
        (take-while #(not (when-let [h (re-find #"^#+(?= )" %)] (<= (count h) level))) after)))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          text (page-text dir "architecture/lint.md")
          lint (model-component dir :architecture/lint)]
      (s/check! (str/includes? text (:description lint)) "the page does not hold the model's description of Lint")
      (doseq [l (str/split-lines (:prose ctx))
              :when (not (str/starts-with? l "#")) :when (not (str/blank? l))]
        (s/check! (str/includes? text l) (str "the page does not hold the prose's line: " l)))
      ;; the prose's own headings sit under the page's Design section
      (s/check! (re-find #"(?m)^## Design$" text) "the page has no Design section")
      (s/check! (re-find #"(?m)^### How the lint reads the model$" text) "the prose's heading is not under Design")
      (s/check! (re-find #"(?m)^#### What it never judges$" text) "the prose's second heading is not under its first")
      (doseq [{:keys [id sc]} (room-scenarios dir)
              :when (str/includes? (str (:file (first (filter #(= id (:id %)) (room-scenarios dir))))) "architecture/lint.feature")
              :let [body (str/join "\n" (section text id))
                    level (some #(second (re-matches #"@(e2e|contract)" %)) (:tags sc))]]
        (s/check! (seq body) (str "the page holds no section for " id))
        (s/check! (str/includes? body level) (str id "'s level " level " is not on the page"))
        (doseq [st (concat (:background sc) (:steps sc))]
          (s/check! (str/includes? body (:text st)) (str id "'s step is not on the page: " (:text st)))))
      nil)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [ctx (room! ctx)]
      (s/check! (fs/exists? (fs/file (:dir ctx) "dhf/views/architecture-components.svg")) "the view is not rendered")
      ctx)))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          text (page-text dir "architecture/index.md")
          img (first (filter #(str/ends-with? % "architecture-components.svg") (links text)))]
      (s/check! (and img (re-find (re-pattern (str "!\\[[^\\]]*\\]\\(" (java.util.regex.Pattern/quote img) "\\)")) text))
                "the page shows no image of the view")
      (s/check! (resolves? dir "architecture/index.md" img) (str "the view's image " img " is not in the site"))
      (doseq [c ["lint" "model" "views"]] (links-to! dir "architecture/index.md" (str "architecture/" c ".md")))
      nil)))

(defn- need-tags [sc] (set (keep #(second (re-matches #"@(UN-\d+)" %)) (concat (:all-tags sc) (:tags sc)))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          text (page-text dir "index.md")
          needs (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn"))))
          scs (room-scenarios dir)]
      (doseq [v ["context.svg" "containers.svg"]
              :let [img (first (filter #(str/ends-with? % (str "/" v)) (links text)))]]
        (s/check! (and img (resolves? dir "index.md" img)) (str "the home page shows no " v)))
      (doseq [[id {t :text}] needs
              :let [body (str/join "\n" (section text id))
                    tracing (for [{sid :id sc :sc} scs :when sid :when (contains? (need-tags sc) id)] sid)]]
        (s/check! (seq (section text id)) (str "the home page has no section for " id))
        (s/check! (str/includes? (str/join "\n" (str/split-lines text)) (str/trim (first (str/split-lines t))))
                  (str "the home page does not hold " id "'s text"))
        (doseq [sid tracing]
          (s/check! (str/includes? body sid) (str sid " traces to " id " but is not listed under it"))))
      nil)))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [ctx (room! ctx)
          d (kc/tmp-dir)]
      (result! d "spec-0012" "passed")
      (result! d "spec-0013" "failed")
      (assoc ctx :results d))))

(defn- shows! [dir id status]
  (let [hits (keep (fn [[rel text]] (when-let [body (section text id)] [rel (str/join "\n" body)])) (pages dir))
        component-hit (first (filter (fn [[rel _]] (not= "index.md" rel)) hits))]
    (s/check! component-hit (str "no page holds a section for " id))
    (s/check! (re-find (re-pattern (str "(?i)status: \\**" status "\\b")) (second component-hit))
              (str id " does not show " status " on " (first component-hit) ":\n" (second component-hit)))))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx]
    (published! ctx)
    (shows! (:dir ctx) "spec-0012" "verified")
    (shows! (:dir ctx) "spec-0013" "failed")
    nil))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (let [ctx (room! ctx)
          {:keys [file]} (first (filter #(= "spec-0014" (:id %)) (room-scenarios (:dir ctx))))
          text (slurp file)]
      (s/check! file "the room holds no spec-0014")
      (when-not (re-find #"@spec-0014[^\n]*@wip" text)
        (spit file (str/replace text #"(@spec-0014[^\n]*)" "$1 @wip")))
      ctx)))

(defstep #"spec-0014 shows planned"
  (fn [ctx] (published! ctx) (shows! (:dir ctx) "spec-0014" "planned") nil))

(def no-status "no status was derived because no results were given")

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (published! ctx)
    (let [ps (pages (:dir ctx))]
      (s/check! (some #(str/starts-with? % "wiki/") (keys ps)) "the site holds no wiki page")
      (doseq [[rel text] ps]
        (s/check! (str/includes? text no-status) (str rel " does not say " no-status)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          views (str (or (:views-dir (kc/config-of (:dir ctx))) "dhf/views") "/")]
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/starts-with? % views)) (kc/finding-lines r))
                (str "no finding names a stale view under " views "\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file (:dir ctx) (:dir (site-config (:dir ctx))) "index.html")))
                "the site was built")
      nil)))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx]
    (let [ctx (kc/project-set-up! ctx)
          v (kc/run! ctx (:dir ctx) ["views"])]
      (s/check! (zero? (:exit v)) (str "rdm-spec views failed\n" (s/show v)))
      (assoc ctx :spec-before (kc/spec-snapshot (:dir ctx))))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [ctx (room! ctx)
          rd (fs/file (:dir ctx) (:results-dir (kc/config-of (:dir ctx))))]
      (result! rd "spec-0012" "passed")
      (result! rd "spec-0013" "failed")
      ctx)))

(defn- tree [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

(defstep #"\"rdm-spec publish\" is run twice, the first site kept aside"
  (fn [ctx]
    (let [c1 (kc/run-line ctx "publish")
          _ (s/check! (zero? (:exit (:run c1))) (str "the first publish failed\n" (s/show (:run c1))))
          first-site (tree (fs/file (:dir c1) (:source (site-config (:dir c1)))))
          c2 (kc/run-line c1 "publish")]
      (assoc c2 :first-site first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (published! ctx)
    (let [now (tree (fs/file (:dir ctx) (:source (site-config (:dir ctx)))))]
      (s/check! (some #(str/ends-with? % ".md") (keys now)) "the site holds no Markdown")
      (s/check! (contains? now "mkdocs.yml") "the site holds no builder configuration")
      (s/check! (= (:first-site ctx) now)
                (str "the sites differ in " (pr-str (sort (filter #(not= (get (:first-site ctx) %) (get now %))
                                                                  (distinct (concat (keys now) (keys (:first-site ctx)))))))))
      nil)))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [dir (:dir ctx)
          outside [(str (fs/absolutize dir)) (str (fs/canonicalize dir)) (System/getProperty "user.home")
                   (System/getProperty "java.io.tmpdir") "/tmp/"]]
      (doseq [f (fs/glob (fs/file dir (:source (site-config dir))) "**")
              :when (re-find #"\.(md|yml)$" (str f))
              :let [text (slurp (str f))]
              o outside :when (not (str/blank? o))]
        (s/check! (not (str/includes? text o)) (str (fs/relativize dir f) " names " o)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(def loyly-readme "https://github.com/skallinen/loyly/blob/17d68d2/README.md")

(defn- wiki-dir [dir] (or (:wiki-dir (kc/config-of dir)) "dhf/wiki"))

(defn- footnote-links! [dir rel label]
  (let [text (page-text dir rel)
        line (first (filter #(str/starts-with? % (str label ":")) (str/split-lines text)))]
    (s/check! line (str rel " holds no footnote " label "\n" text))
    (s/check! (str/includes? line (str "(" loyly-readme ")"))
              (str "the footnote does not link to " loyly-readme ": " line))))

(defstep #"the page is published and the footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          rel (str "wiki/" (subs (:page ctx) (inc (count (wiki-dir dir)))))]
      (footnote-links! dir rel "[^1]")
      nil)))

(defstep #"the wiki's index holds a footnote citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [ctx (room! ctx)]
      (spit (str (fs/file (:dir ctx) (wiki-dir (:dir ctx)) "index.md"))
            (str "\nThe index rests on its source.[^index]\n\n[^index]: `" citation "`, the index.\n") :append true)
      ctx)))

(defstep #"the published index's footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx] (published! ctx) (footnote-links! (:dir ctx) "wiki/index.md" "[^index]") nil))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [ctx (room! ctx)
          index (slurp (str (fs/file (:dir ctx) (wiki-dir (:dir ctx)) "index.md")))]
      (s/check! (= 2 (count (filter #(str/starts-with? % "pages/") (links index)))) "the room's wiki index does not list two pages")
      ctx)))

(defn- nav [dir] (get (builder-config dir) "nav"))

(defn- section-titles [dir] (mapv #(first (keys %)) (nav dir)))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          index (slurp (str (fs/file dir (wiki-dir dir) "index.md")))]
      (links-to! dir "index.md" "wiki/index.md")
      (doseq [p (filter #(str/starts-with? % "pages/") (links index))]
        (links-to! dir "wiki/index.md" (str "wiki/" p)))
      (let [titles (section-titles dir)]
        (s/check! (= ["kernel" "wiki"] (take-last 2 titles)) (str "the navigation's sections are " (pr-str titles))))
      nil)))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx] (model-project! ctx (model-of [["spec" ["Parser" "Check"]] ["architecture" ["Model" "Lint"]]] ["Bus"]))))

(defn- entry-target [entry] (first (vals entry)))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (published! ctx)
    (let [n (nav (:dir ctx))]
      (s/check! (= "index.md" (entry-target (first n))) (str "the navigation starts with " (pr-str (first n))))
      (s/check! (= ["spec" "architecture" "kernel"] (take 3 (rest (section-titles (:dir ctx)))))
                (str "the navigation's sections are " (pr-str (section-titles (:dir ctx)))))
      nil)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (nav (:dir ctx))
          m (:model ctx)
          want (concat (for [c (:containers m) x (:contexts c)]
                         [(:name x) (cons (str (name (:id x)) "/index.md") (map #(str (:spec %) ".md") (:components x)))])
                       [["kernel" (cons "kernel/index.md" (map #(str (:spec %) ".md") (:kernel m)))]])]
      (doseq [[title targets] want
              :let [entry (first (filter #(contains? % title) n))
                    items (get entry title)]]
        (s/check! (sequential? items) (str "no section " title))
        (s/check! (= targets (map entry-target items)) (str "the section " title " lists " (pr-str items))))
      nil)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (room! ctx)))

(defn- html-files [dir]
  (let [d (fs/file dir (:dir (site-config dir)))]
    (if (fs/exists? d) (vec (map str (fs/glob d "**.html"))) [])))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)]
      (s/check! (some #{"--strict"} (:builder (site-config dir))) "the configured site builder does not fail on a broken link")
      (s/check! (fs/exists? (fs/file dir (:dir (site-config dir)) "index.html")) "no HTML home page was written")
      (s/check! (some #(str/includes? % "architecture") (html-files dir)) "no HTML page of a component was written")
      nil)))

(def missing-page "no-such-page.md")

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [ctx (room! ctx)]
      (s/write! (:dir ctx) "dhf/spec/architecture/lint.md"
                (str "# Lint\n\nSee [the missing page](" missing-page ").\n"))
      ctx)))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % "architecture/lint.md") (str/includes? % missing-page)) (kc/finding-lines r))
                (str "no finding names the page and the link\n" (s/show r)))
      nil)))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (s/check! (empty? (html-files (:dir ctx))) (str "HTML is left: " (pr-str (take 3 (html-files (:dir ctx))))))
    nil))

(def missing-builder "rdm-spec-no-such-site-builder")

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [ctx (room! ctx)
          dir (:dir ctx)
          c (kc/config-of dir)]
      (s/write! dir "dhf/harness/config.edn"
                (pr-str (assoc-in c [:site :builder] [missing-builder "build" "--strict"])))
      (s/write! dir (str (:dir (site-config dir)) "/index.html") "<html>an earlier site</html>\n")
      ctx)))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (let [r (kc/refused-naming! ctx missing-builder)]
      (s/check! (some #(and (str/includes? % missing-builder) (str/includes? % "no HTML")) (kc/cannot-run-lines r))
                (str "it does not say no HTML was written\n" (s/show r)))
      nil)))
