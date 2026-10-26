(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec publish`
  in a copy of the room (its spec, views, sources and wiki as they are), or
  in a small project whose model a step gives (no view, so every view is
  fresh). The site's Markdown and the site builder's configuration are
  written under the configured :site :source (pages under its docs/), the
  HTML into :site :dir.

  Context keys set here: :dir, :results (a results directory outside the
  project), :first-site ({path text} of the first publish), :page (a
  component page a step is about), :link (a link that leads nowhere),
  :builder (the configured site builder's program)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Where the site is

(defn- site-cfg [ctx] (merge {:dir "site" :source "site-src"} (:site (kc/config-of (:dir ctx)))))

(defn- docs [ctx] (fs/file (:dir ctx) (:source (site-cfg ctx)) "docs"))

(defn- page [ctx rel]
  (let [f (fs/file (docs ctx) rel)]
    (s/check! (fs/regular-file? f) (str "the site holds no page " rel "\n" (s/show (kc/the-run ctx))))
    (slurp (str f))))

(defn- pages [ctx]
  (into (sorted-map) (for [f (fs/glob (docs ctx) "**.md")] [(str (fs/relativize (docs ctx) f)) (slurp (str f))])))

(defn- site-files [ctx]
  (let [src (fs/file (:dir ctx) (:source (site-cfg ctx)))]
    (into (sorted-map) (for [f (fs/glob src "**") :when (fs/regular-file? f)
                             :let [r (str (fs/relativize src f))]
                             :when (or (str/ends-with? r ".md") (str/ends-with? r ".yml"))]
                         [r (slurp (str f))]))))

(defn- nav [ctx]
  (let [f (fs/file (:dir ctx) (:source (site-cfg ctx)) "mkdocs.yml")]
    (s/check! (fs/exists? f) "no mkdocs.yml was written")
    (:nav (yaml/parse-string (slurp (str f))))))

(defn- published! [ctx] (kc/exits! ctx 0))

;; ---------------------------------------------------------------------------
;; A small project

(defn- component [ctx-id slug nm]
  {:id (keyword (name ctx-id) slug) :name nm :spec (str (name ctx-id) "/" slug) :description (str "The " nm " of the fixture")})

(defn- small-model [contexts kernel]
  {:system {:id :store :name "Store" :description "Sells things"}
   :people [{:id :buyer :name "Buyer" :description "Buys things" :actor #{:human}}]
   :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The shop program"
                 :contexts (vec (for [[cid comps] contexts]
                                  {:id cid :name (name cid) :description (str "The " (name cid) " context")
                                   :components (vec (for [[slug nm] comps] (component cid slug nm)))}))}]
   :kernel (vec (for [[slug nm] kernel] {:id (keyword "kernel" slug) :name nm :spec (str "kernel/" slug)
                                         :description (str "The " nm " of the kernel")}))
   :relationships [] :views []})

(defn- small-project!
  "A project whose model is `m`, one scenario in each component's feature
  file, the need UN-001, an empty wiki and no source."
  [ctx m]
  (let [dir (s/project!)
        comps (concat (for [c (:containers m) x (:contexts c) comp (:components x)] comp) (:kernel m))]
    (s/write! dir "dhf/spec/model.edn" (pr-str m))
    (s/write! dir "dhf/spec/events.edn" "{}\n")
    (s/write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text "A buyer needs things sold." :sources ["demo:README.md@abc1234"]}}))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (s/write! dir "dhf/raw/sources.edn" "{}\n")
    (s/write! dir "dhf/wiki/index.md" "# Wiki\n\n## Pages\n\nNone yet.\n")
    (doseq [[i c] (map-indexed vector comps)]
      (s/write! dir (str "dhf/spec/" (:spec c) ".feature")
                (str "@UN-001\nFeature: " (:name c) "\n  " (:description c) "\n\n"
                     (format "  @spec-%04d @e2e\n" (inc i))
                     "  Scenario: The " (:name c) " works\n    Given a thing\n    Then it holds\n")))
    (assoc ctx :dir dir :model m)))

(def ^:private five
  [[:shop [["till" "Till"] ["shelf" "Shelf"] ["stock" "Stock"]]]
   [:billing [["invoice" "Invoice"] ["ledger" "Ledger"]]]])

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx] (small-project! ctx (small-model five []))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (published! ctx)
    (doseq [[cid comps] five [slug nm] comps
            :let [text (page ctx (str (name cid) "/" slug ".md"))]]
      (s/check! (str/includes? text nm) (str (name cid) "/" slug ".md does not name " nm)))
    (s/check! (= 5 (count (remove #(or (str/ends-with? % "index.md") (str/starts-with? % "wiki/")) (keys (pages ctx)))))
              (str "the component pages are " (pr-str (keys (pages ctx)))))
    nil))

(defn- room-model [ctx] (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/model.edn")))))

(def ^:private lint-prose "The lint reads the model once and judges what a machine can judge.")

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (s/write! dir "dhf/spec/architecture/lint.md" (str "# Lint\n\n" lint-prose "\n"))
      (assoc ctx :dir dir))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "architecture/lint.md")
          desc (some #(when (= :architecture/lint (:id %)) (:description %))
                     (for [c (:containers (room-model ctx)) x (:contexts c) comp (:components x)] comp))
          rel "dhf/spec/architecture/lint.feature"
          scs (:scenarios (gherkin/parse-feature rel (slurp (str (fs/file (:dir ctx) rel)))))]
      (s/check! (str/includes? text desc) (str "the page does not hold the description " (pr-str desc)))
      (s/check! (str/includes? text lint-prose) "the page does not hold the prose")
      (doseq [sc scs
              :let [id (some #(when (str/starts-with? % "@spec-") %) (:tags sc))
                    level (some #{"@e2e" "@contract"} (:tags sc))]]
        (s/check! (some #(and (str/includes? % id) (str/includes? % (subs level 1))) (str/split-lines text))
                  (str "no line of the page names " id " and its level"))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? text (:text st)) (str "the page does not hold the step " (pr-str (:text st))))))
      nil)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (s/check! (fs/exists? (fs/file dir "dhf/views/architecture-components.svg")) "no rendered component view")
      (assoc ctx :dir dir))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "architecture/index.md")]
      (s/check! (re-find #"!\[[^\]]*\]\([^)]*architecture-components\.svg\)" text) "the page does not show the view")
      (s/check! (fs/exists? (fs/file (docs ctx) "views/architecture-components.svg")) "the view is not in the site")
      (doseq [c ["model" "lint" "views"]]
        (s/check! (str/includes? text (str "(" c ".md)")) (str "the page does not link " c ".md")))
      nil)))

(defn- traced
  "{need [scenario-id]} of the project's spec, by need tags."
  [ctx]
  (let [files (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature")]
    (apply merge-with into
           (for [f files
                 sc (:scenarios (gherkin/parse-feature (str f) (slurp (str f))))
                 :let [id (some #(when (re-matches #"@spec-\d+" %) (subs % 1)) (:tags sc))]
                 :when id
                 n (filter #(re-matches #"@UN-\d+" %) (:all-tags sc))]
             {(subs n 1) [id]}))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "index.md")
          needs (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/needs.edn"))))]
      (doseq [k ["context" "containers"]]
        (s/check! (re-find (re-pattern (str "!\\[[^\\]]*\\]\\([^)]*" k "\\.svg\\)")) text) (str "the home page does not show " k)))
      (doseq [[n ids] (traced ctx) :when (contains? needs n)]
        (s/check! (str/includes? text n) (str "the home page does not show " n))
        (doseq [id ids] (s/check! (str/includes? text id) (str "the home page does not show " id " under " n))))
      (doseq [n (keys needs)] (s/check! (str/includes? text (str n)) (str "the home page does not show " n)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defn- result-json [id status]
  (json/generate-string {:uuid (str (random-uuid)) :name (str "a test of " id) :status status
                         :labels [{:name "spec" :value id}]}))

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [rd (s/tmp-dir)]
      (s/write! rd (str (random-uuid) "-result.json") (result-json "spec-0012" "passed"))
      (s/write! rd (str (random-uuid) "-result.json") (result-json "spec-0013" "failed"))
      (assoc ctx :dir (s/copy-of-room!) :results rd))))

(defn- shows! [ctx id st]
  (let [ls (mapcat str/split-lines (vals (pages ctx)))]
    (s/check! (some #(and (str/includes? % id) (re-find (re-pattern (str "\\b" st "\\b")) %)) ls)
              (str "no line of the site shows " id " " st))))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx]
    (published! ctx)
    (shows! ctx "spec-0012" "verified")
    (shows! ctx "spec-0013" "failed")
    nil))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          f (fs/file dir "dhf/spec/architecture/lint.feature")
          text (slurp (str f))]
      (s/check! (str/includes? text "@spec-0014 ") "the lint holds no @spec-0014")
      (spit (str f) (str/replace-first text "@spec-0014 " "@spec-0014 @wip "))
      (assoc ctx :dir dir))))

(defstep #"spec-0014 shows planned"
  (fn [ctx] (published! ctx) (shows! ctx "spec-0014" "planned") nil))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (published! ctx)
    (let [ps (pages ctx)]
      (s/check! (some #(str/starts-with? % "wiki/") (keys ps)) "no wiki page was published")
      (doseq [[rel text] ps]
        (s/check! (str/includes? text "no status was derived because no results were given")
                  (str rel " does not say no status was derived"))))
    nil))

;; ---------------------------------------------------------------------------
;; A reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\"")))
                      (kc/finding-lines r))
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx] (kc/project-set-up! ctx)))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (fs/file dir (or (:results-dir (kc/config-of dir)) "dhf/allure-results"))]
      (s/write! rd (str (random-uuid) "-result.json") (result-json "spec-0012" "passed"))
      (assoc ctx :dir dir))))

(defstep #"\"rdm-spec publish\" is run twice, the first site kept aside"
  (fn [ctx]
    (let [a (kc/run-line ctx "publish")
          first-site (site-files a)
          b (kc/run-line a "publish")]
      (s/check! (zero? (:exit (:run a))) (str "the first publish failed\n" (s/show (:run a))))
      (assoc b :first-site first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (published! ctx)
    (let [b (site-files ctx)]
      (s/check! (seq (:first-site ctx)) "the first site holds nothing")
      (s/check! (some #(str/ends-with? % "mkdocs.yml") (keys b)) "no site builder configuration")
      (s/check! (= (keys (:first-site ctx)) (keys b)) "the sites hold different files")
      (doseq [[k v] (:first-site ctx)] (s/check! (= v (get b k)) (str k " differs"))))
    nil))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [bad [(str (fs/absolutize (:dir ctx))) (s/fixture-root) (str (fs/parent (fs/absolutize (:dir ctx))))]]
      (doseq [[rel text] (site-files ctx) b bad]
        (s/check! (not (str/includes? text b)) (str rel " names " b))))
    nil))

;; ---------------------------------------------------------------------------
;; The domain wiki

(def ^:private loyly-readme "https://github.com/skallinen/loyly/blob/17d68d2/README.md")

(defstep #"the page is published and the footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (published! ctx)
    (let [rel (str "wiki/" (subs (:page ctx) (count "dhf/wiki/")))
          text (page ctx rel)]
      (s/check! (str/includes? text (str "(" loyly-readme ")")) (str rel " does not link the citation:\n" text))
      nil)))

(defstep #"the wiki's index holds a footnote citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (s/copy-of-room!)]
      (spit (str (fs/file dir "dhf/wiki/index.md"))
            (str "\nThe index rests on a source too.[^site]\n\n[^site]: `" citation "`, \"The idea in five lines\".\n")
            :append true)
      (assoc ctx :dir dir))))

(defstep #"the published index's footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "wiki/index.md")]
      (s/check! (some #(and (str/starts-with? % "[^site]:") (str/includes? % (str "(" loyly-readme ")")))
                      (str/split-lines text))
                (str "the index's footnote is not linked:\n" text))
      nil)))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          index (slurp (str (fs/file dir "dhf/wiki/index.md")))]
      (s/check! (= 2 (count (re-seq #"\]\(pages/[^)]+\.md\)" index))) "the wiki's index does not list two pages")
      (assoc ctx :dir dir))))

(defn- nav-entries
  "[[title value]] of the navigation's top entries."
  [n]
  (vec (for [e n] (if (map? e) (first e) [nil e]))))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (published! ctx)
    (s/check! (str/includes? (page ctx "index.md") "(wiki/index.md)") "the home page does not link the wiki's index")
    (let [idx (page ctx "wiki/index.md")
          titles (map (comp str/lower-case name first) (nav-entries (nav ctx)))]
      (doseq [p ["pages/loyly-method.md" "pages/regulated-profile.md"]]
        (s/check! (str/includes? idx (str "(" p ")")) (str "the index does not link " p)))
      (s/check! (= "wiki" (last titles)) (str "the navigation's sections are " (pr-str titles)))
      (s/check! (< (.indexOf (vec titles) "kernel") (.indexOf (vec titles) "wiki")) (str "the sections are " (pr-str titles))))
    nil))

;; ---------------------------------------------------------------------------
;; The site builder

(def ^:private two
  [[:spec [["parser" "Parser"] ["stamper" "Stamper"] ["check" "Spec check"]]]
   [:architecture [["model" "Model"] ["lint" "Lint"]]]])

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx] (small-project! ctx (small-model two [["bus" "Event bus"]]))))

(defn- section [n title]
  (some (fn [[t v]] (when (= title (str/lower-case (name t))) v)) (nav-entries n)))

(defn- paths-of [entries] (vec (for [e entries] (if (map? e) (val (first e)) e))))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (published! ctx)
    (let [n (nav ctx)
          titles (mapv (comp str/lower-case name first) (nav-entries n))]
      (s/check! (= "index.md" (second (first (nav-entries n)))) (str "the navigation does not start with the home page: " (pr-str n)))
      (s/check! (= ["spec" "architecture" "kernel"] (subvec titles 1 4)) (str "the sections are " (pr-str titles))))
    nil))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (nav ctx)]
      (doseq [[cid comps] (conj two [:kernel [["bus" "Event bus"]]])
              :let [c (name cid)]]
        (s/check! (= (vec (cons (str c "/index.md") (for [[slug _] comps] (str c "/" slug ".md"))))
                     (paths-of (section n c)))
                  (str "the section " c " is " (pr-str (section n c)))))
      nil)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (small-project! ctx (small-model five [["bus" "Event bus"]]))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (published! ctx)
    (let [{:keys [dir builder]} (site-cfg ctx)]
      (s/check! (some #{"--strict"} builder) (str "the site builder does not fail on a broken link: " (pr-str builder)))
      (s/check! (fs/regular-file? (fs/file (:dir ctx) dir "index.html")) "no HTML was written"))
    nil))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [ctx (small-project! ctx (small-model five []))]
      (s/write! (:dir ctx) "dhf/spec/shop/till.md" "# Till\n\nThe till follows [a page nobody wrote](nowhere.md).\n")
      (assoc ctx :page "shop/till.md" :link "nowhere.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % (:page ctx)) (str/includes? % (:link ctx))) (kc/finding-lines r))
                (str "no finding names " (:page ctx) " and " (:link ctx) "\n" (s/show r)))
      nil)))

(defn- html [ctx]
  (let [d (fs/file (:dir ctx) (:dir (site-cfg ctx)))]
    (if (fs/exists? d) (vec (map str (fs/glob d "**.html"))) [])))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (s/check! (#{1 2} (:exit (kc/the-run ctx))) (str "the publish did not fail\n" (s/show (kc/the-run ctx))))
    (s/check! (empty? (html ctx)) (str "HTML is left: " (pr-str (take 3 (html ctx)))))
    nil))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [ctx (small-project! ctx (small-model five []))
          builder "rdm-spec-no-such-site-builder"
          cfg (kc/config-of (:dir ctx))]
      (s/write! (:dir ctx) "dhf/harness/config.edn"
                (pr-str (assoc cfg :site (assoc (site-cfg ctx) :builder [builder "build" "--strict"]))))
      (s/write! (:dir ctx) "site/index.html" "<html>an earlier site</html>\n")
      (assoc ctx :builder builder))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx] (kc/refused-naming! ctx (:builder ctx) "no HTML was written") nil))
