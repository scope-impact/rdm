(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec
  publish` in a project the scenario makes, a copy of the room (its spec,
  views, sources and wiki) or a project set up by `rdm-spec init` holding a
  model of its own.

  The site, as the Site component writes it (read here through the
  configuration's :site): the site builder's configuration
  <source>/mkdocs.yml; the Markdown under <source>/docs: index.md (home),
  <context>/index.md (a context's page), <context>/<component>.md (a
  component's page, named by the last part of its :spec path),
  kernel/index.md and kernel/<component>.md, views/<key>.svg, and wiki/ (the
  wiki, as laid out under its directory); the HTML under <dir>. Each
  scenario's section starts at <a id=\"<id>\"></a> and carries
  \"**Status:** <status>\".

  The shared When and Givens are the kernel's (steps.kernel-command).
  \"a wiki page whose footnote cites C\" is shared with the knowledge
  context's features and defined here, when this file loads (the kernel may
  come to own it; its owner then narrows this one away).

  Context keys: :dir the project; :results the results directory; :run the
  last run; :first-site the site kept aside; :spec-before the spec kept
  aside; :page, :citation, :footnote as the wiki Givens say."
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
;; Reading the site

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- site-conf [dir] (:site (config-of dir)))

(defn source-dir [dir] (fs/file dir (:source (site-conf dir))))

(defn docs-dir [dir] (fs/file (source-dir dir) "docs"))

(defn html-dir [dir] (fs/file dir (:dir (site-conf dir))))

(defn page-text
  "The published Markdown page `rel` (relative to the docs directory); the
  step fails when there is none."
  [ctx rel]
  (let [f (fs/file (docs-dir (:dir ctx)) rel)]
    (s/check! (fs/regular-file? f) (str "no page " rel "\n" (some-> (:run ctx) s/show)))
    (slurp (str f))))

(defn- pages
  "Every published Markdown page: {rel text}."
  [dir]
  (let [d (docs-dir dir)]
    (into (sorted-map) (for [f (fs/glob d "**.md") :when (fs/regular-file? f)]
                         [(str/replace (str (fs/relativize d f)) "\\" "/") (slurp (str f))]))))

(defn- passed! [ctx]
  (kc/exits! ctx 0))

(defn- section
  "The text of the scenario `id`'s section of `text`."
  [text id]
  (let [start (str/index-of text (str "<a id=\"" id "\"></a>"))]
    (s/check! start (str "no section for " id))
    (let [end (str/index-of text "<a id=\"" (inc start))]
      (subs text start (or end (count text))))))

(defn- status-shown [text id]
  (second (re-find #"\*\*Status:\*\* ([a-z ]+)" (section text id))))

(defn- html-files [dir]
  (let [d (html-dir dir)]
    (if (fs/exists? d) (vec (map str (fs/glob d "**.html"))) [])))

(defn- room-model [] (edn/read-string (slurp (str (fs/file s/room-root "dhf/spec/model.edn")))))

(defn- room-scenarios
  "Every scenario of the room's spec: [{:id :tags :all-tags :steps :spec}]."
  []
  (let [spec (fs/file s/room-root "dhf/spec")]
    (vec (for [f (sort (map str (fs/glob spec "**.feature")))
               :let [p (gherkin/parse-feature f (slurp f))]
               sc (:scenarios p)]
           (assoc sc :id (some #(second (re-matches #"@(spec-\d+)" %)) (:tags sc))
                  :spec (str/replace (str (fs/relativize spec f)) #"\.feature$" ""))))))

;; ---------------------------------------------------------------------------
;; Projects

(defn- room! [ctx] (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!))))

(def ^:private citation "demo:README.md@abc1234")

(defn- feature-text [name ids]
  (str "@UN-001\nFeature: " name "\n  A fixture of the site's steps.\n\n  Rule: The fixture rule\n"
       (str/join (for [id ids]
                   (str "\n    @" id " @e2e\n    Scenario: Scenario " id " of " name "\n"
                        "      Given a step of " id "\n      Then it holds\n")))))

(defn- model-project!
  "A project set up by rdm-spec whose model holds `contexts`
  [[context [[component-key component-name] ..]] ..] and `kernel`
  [[key name] ..], each component owning a feature file with one scenario."
  [ctx contexts kernel]
  (let [parent (kc/tmp-dir)
        r (kc/run! ctx parent ["init" "project"])
        _ (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
        dir (str (fs/file parent "project"))
        n (atom 0)
        next-id #(format "spec-%04d" (swap! n inc))
        comp (fn [c [k nm]] {:id (keyword c k) :name nm :spec (str c "/" k) :description (str "The " nm " component")})
        model {:system {:id :demo-system :name "Demo" :description "A demo system"}
               :people [] :external []
               :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                             :contexts (vec (for [[c comps] contexts]
                                              {:id (keyword c) :name c :description (str "The " c " context")
                                               :components (mapv #(comp c %) comps)}))}]
               :kernel (mapv #(comp "kernel" %) kernel)
               :relationships [] :views []}
        model (if (seq kernel) model (dissoc model :kernel))]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text "The first need." :sources [citation]}}))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (s/write! dir "dhf/raw/sources.edn" (pr-str {:demo {:repository "https://example.invalid/demo" :record "demo.md"
                                                       :pins ["abc1234"] :fenced []}}))
    (s/write! dir "dhf/raw/demo.md" "# Demo\n\nA demo source.\n")
    (doseq [[c comps] (concat contexts [["kernel" kernel]])
            [k nm] comps]
      (s/write! dir (str "dhf/spec/" c "/" k ".feature") (feature-text nm [(next-id)])))
    (-> ctx
        (assoc :dir dir :model model)
        (assoc-in [:env "RESULTS_DIR"] nil))))

(defn- result! [dir id status]
  (let [uuid (str (java.util.UUID/randomUUID))]
    (s/write! dir (str uuid "-result.json")
              (json/generate-string {:uuid uuid :name (str "a test of " id) :status status :stage "finished"
                                     :labels [{:name "spec" :value id}] :steps []}))))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (model-project! ctx [["alpha" [["one" "One"] ["two" "Two"] ["three" "Three"]]]
                         ["beta" [["four" "Four"] ["five" "Five"]]]]
                    [])))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (passed! ctx)
    (let [ps (pages (:dir ctx))
          comps (for [c (get-in ctx [:model :containers 0 :contexts]) cp (:components c)]
                  [(:name c) (:name cp) (str (:spec cp) ".md")])
          component-pages (remove #(or (str/ends-with? % "index.md") (str/starts-with? % "wiki/")) (keys ps))]
      (s/check! (= 5 (count component-pages)) (str "the component pages are " (pr-str component-pages)))
      (doseq [[c n rel] comps
              :let [text (get ps rel)]]
        (s/check! text (str "no page " rel))
        (let [title (first (str/split-lines text))]
          (s/check! (and (str/starts-with? title "# ") (str/includes? title c) (str/includes? title n))
                    (str rel "'s title " (pr-str title) " does not name " c " and " n))))
      nil)))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [ctx (room! ctx)
          prose "## How the lint reads the model\n\nThe lint reads the model whole before it judges any view.\n"]
      (s/write! (:dir ctx) "dhf/spec/architecture/lint.md" prose)
      (assoc ctx :prose prose))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (passed! ctx)
    (let [text (page-text ctx "architecture/lint.md")
          desc (first (for [c (:containers (room-model)) cx (:contexts c) cp (:components cx)
                            :when (= "architecture/lint" (:spec cp))]
                        (:description cp)))
          scs (filter #(= "architecture/lint" (:spec %)) (room-scenarios))]
      (s/check! (str/includes? text desc) (str "the page lacks the description " (pr-str desc)))
      (s/check! (str/includes? text "The lint reads the model whole before it judges any view.") "the page lacks the prose")
      (s/check! (seq scs) "the room's Lint owns no scenario")
      (doseq [sc scs
              :let [sec (section text (:id sc))
                    level (some #(second (re-matches #"@(e2e|contract)" %)) (:tags sc))]]
        (s/check! (str/includes? sec (:id sc)) (str (:id sc) " is not named"))
        (s/check! (re-find (re-pattern (str "(?i)level:?\\**:? " level)) sec) (str (:id sc) "'s level " level " is not shown: " sec))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? sec (str (:keyword st) " " (:text st)))
                    (str (:id sc) "'s step " (pr-str (:text st)) " is not shown"))))
      nil)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [ctx (room! ctx)]
      (s/check! (fs/regular-file? (fs/file (:dir ctx) "dhf/views/architecture-components.svg"))
                "the component view of architecture is not rendered")
      ctx)))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (passed! ctx)
    (let [text (page-text ctx "architecture/index.md")
          comps (first (for [c (:containers (room-model)) cx (:contexts c) :when (= :architecture (:id cx))]
                         (:components cx)))]
      (s/check! (re-find #"!\[[^\]]*\]\(\.\./views/architecture-components\.svg\)" text) "the page does not show the view")
      (s/check! (fs/regular-file? (fs/file (docs-dir (:dir ctx)) "views/architecture-components.svg")) "the view is not published")
      (doseq [cp comps
              :let [rel (str (last (str/split (:spec cp) #"/")) ".md")]]
        (s/check! (str/includes? text (str "](" rel ")")) (str "the page does not link " rel)))
      nil)))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (passed! ctx)
    (let [text (page-text ctx "index.md")
          m (room-model)
          needs (edn/read-string (slurp (str (fs/file s/room-root "dhf/spec/needs.edn"))))
          scs (room-scenarios)]
      (doseq [lvl [:context :container]
              :let [v (first (filter #(= lvl (:level %)) (:views m)))]]
        (s/check! v (str "the room has no " (name lvl) " view"))
        (s/check! (str/includes? text (str "](views/" (:key v) ".svg)")) (str "the home page does not show the " (name lvl) " view")))
      (doseq [[id _] needs
              :let [start (str/index-of text (str "### " id))
                    _ (s/check! start (str "the home page does not show " id))
                    end (str/index-of text "\n### " (inc start))
                    sec (subs text start (or end (count text)))]
              sc scs
              :when (and (:id sc) (some #{(str "@" id)} (:all-tags sc)))]
        (s/check! (str/includes? sec (:id sc)) (str id " does not list " (:id sc))))
      nil)))

;; ---------------------------------------------------------------------------
;; The site shows status

(defstep #"Allure results in which (spec-\d+) passed and (spec-\d+) failed"
  (fn [ctx a b]
    (let [ctx (room! ctx)
          d (kc/tmp-dir)]
      (result! d a "passed")
      (result! d b "failed")
      (assoc ctx :results d))))

(defn- page-of [id]
  (some #(when (= id (:id %)) (str (:spec %) ".md")) (room-scenarios)))

(defstep #"(spec-\d+) shows verified and (spec-\d+) shows failed"
  (fn [ctx a b]
    (passed! ctx)
    (doseq [[id want] [[a "verified"] [b "failed"]]]
      (let [got (status-shown (page-text ctx (page-of id)) id)]
        (s/check! (= want got) (str id " shows " (pr-str got) ", not " want))))
    nil))

(defstep #"the scenario @(spec-\d+) tagged @wip"
  (fn [ctx id]
    (let [ctx (room! ctx)
          f (fs/file (:dir ctx) "dhf/spec" (page-of id))
          f (fs/file (str/replace (str f) #"\.md$" ".feature"))
          text (slurp (str f))
          changed (str/replace-first text (re-pattern (str "@" id "(?!\\d)")) (str "@" id " @wip"))]
      (s/check! (not= text changed) (str id " is not in " f))
      (spit (str f) changed)
      (-> ctx (assoc :id id) (assoc-in [:env "RESULTS_DIR"] nil)))))

(defstep #"(spec-\d+) shows planned"
  (fn [ctx id]
    (passed! ctx)
    (let [got (status-shown (page-text ctx (page-of id)) id)]
      (s/check! (= "planned" got) (str id " shows " (pr-str got) ", not planned")))
    nil))

(def ^:private no-status #"(?i)no status was derived because no results were given")

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (passed! ctx)
    (let [ps (pages (:dir ctx))]
      (s/check! (some #(str/starts-with? % "wiki/") (keys ps)) "no wiki page is published")
      (doseq [[rel text] ps]
        (s/check! (re-find no-status text) (str rel " does not say no status was derived"))))
    nil))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(str/includes? % (str (:stale-view ctx) ".")) (s/err-lines r))
                (str "no line names the view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

(defn- tree
  "{rel bytes-as-string} of every file under `d`."
  [d]
  (if (fs/exists? d)
    (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                         [(str (fs/relativize d f)) (slurp (str f))]))
    {}))

(defn- spec-tree [dir] (tree (fs/file dir "dhf/spec")))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx]
    (let [parent (kc/tmp-dir)
          r (kc/run! ctx parent ["init" "project"])
          _ (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
          dir (str (fs/file parent "project"))]
      (-> ctx
          (assoc :dir dir :spec-before (spec-tree dir))
          (assoc-in [:env "RESULTS_DIR"] nil)))))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (passed! ctx)
    (s/check! (= (:spec-before ctx) (spec-tree (:dir ctx))) "a file under dhf/spec changed")
    nil))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [ctx (room! ctx)
          d (kc/tmp-dir)]
      (result! d "spec-0012" "passed")
      (result! d "spec-0013" "failed")
      (assoc ctx :results d))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice, the first site kept aside"
  (fn [ctx line]
    (let [ctx (kc/run-line ctx line)
          _ (passed! ctx)
          first-site (tree (source-dir (:dir ctx)))
          ctx (kc/run-line ctx line)]
      (passed! ctx)
      (assoc ctx :first-site first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (let [second-site (tree (source-dir (:dir ctx)))
          md? #(or (str/ends-with? % ".md") (str/ends-with? % ".yml"))]
      (s/check! (some #(str/ends-with? % "mkdocs.yml") (keys second-site)) "no site builder configuration")
      (s/check! (= (into {} (filter (comp md? key) (:first-site ctx)))
                   (into {} (filter (comp md? key) second-site)))
                (str "the sites differ in "
                     (pr-str (remove #(= (get (:first-site ctx) %) (get second-site %))
                                     (distinct (concat (keys (:first-site ctx)) (keys second-site)))))))
      nil)))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [dir (:dir ctx)
          outside (remove str/blank? [(str (fs/absolutize dir)) (str (fs/canonicalize dir)) (str (:results ctx))
                                      s/room-root (System/getProperty "user.home")
                                      (System/getProperty "java.io.tmpdir")])]
      (doseq [[rel text] (assoc (pages dir) "mkdocs.yml" (slurp (str (fs/file (source-dir dir) "mkdocs.yml"))))]
        (doseq [o outside]
          (s/check! (not (str/includes? text o)) (str rel " names " o)))
        (s/check! (not (re-find #"(?:^|[\s(\"'`\[])/(?:tmp|home|root|Users|var|private)/" text))
                  (str rel " names an absolute path")))
      nil)))

;; ---------------------------------------------------------------------------
;; The wiki beside the spec

(defn- footnoted-page
  "A wiki page with one footnote citing `c`."
  [c]
  (str "# Regeneration\n\n"
       "The code is built from the spec, and can be deleted and built again.[^1]\n\n"
       "[^1]: `" c "`, \"What to steal\" 1.\n"))

(defn- wiki-page!
  "The meaning of \"a wiki page whose footnote cites C\": a fresh copy of the
  room whose wiki holds a page, listed in the index, one footnote citing C."
  [ctx c]
  (let [dir (s/copy-of-room!)
        rel "pages/regeneration.md"]
    (s/write! dir (str "dhf/wiki/" rel) (footnoted-page c))
    (spit (str (fs/file dir "dhf/wiki/index.md")) (str "\n- [Regeneration](" rel ")\n") :append true)
    (assoc ctx :dir dir :page (str "dhf/wiki/" rel) :wiki-rel rel :citation c :footnote "[^1]")))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx c] (wiki-page! ctx c)))

(defn- links-to!
  "Checks the footnote [^1] of `text` links to `path` at `commit` in `repo`."
  [text path commit repo]
  (let [defn-line (first (filter #(str/starts-with? % "[^1]:") (str/split-lines text)))
        urls (map second (re-seq #"\]\(([^)\s]+)\)" (str defn-line)))]
    (s/check! defn-line "the page holds no footnote [^1]")
    (s/check! (some #(and (str/includes? % repo) (str/includes? % commit) (str/ends-with? % path)) urls)
              (str "the footnote does not link " path " at " commit " in " repo ": " defn-line))))

(defn- citation-parts [c]
  (let [[_ _ path commit] (re-matches #"([^:]+):(.+)@([0-9a-f]+)" c)] [path commit]))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (passed! ctx)
    ;; the kernel owns the Given and names the page :wiki-file
    (let [text (page-text ctx (str "wiki/" (or (:wiki-file ctx) (:wiki-rel ctx))))
          html (fs/file (html-dir (:dir ctx)) "wiki")]
      (links-to! text path commit repo)
      (s/check! (some #(str/includes? (slurp %) (str repo "/blob/" commit "/" path)) (html-files (:dir ctx)))
                (str "no HTML page links " path " at " commit " in " repo " under " html)))
    nil))

(defstep #"the wiki's index holds a footnote citing \"([^\"]+)\""
  (fn [ctx c]
    (let [ctx (room! ctx)
          f (fs/file (:dir ctx) "dhf/wiki/index.md")
          text (slurp (str f))]
      ;; the index's own footnote is renamed [^1], the one this scenario adds
      (spit (str f) (str (str/replace text #"\[\^ex\]" "[^ex0]")
                         "\nThe index cites a source too.[^1]\n\n[^1]: `" c "`, \"What to steal\" 1.\n"))
      (assoc ctx :citation c))))

(defstep #"the published index's footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (passed! ctx)
    (links-to! (page-text ctx "wiki/index.md") path commit repo)
    nil))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [ctx (room! ctx)
          dir (:dir ctx)
          w (fs/file dir "dhf/wiki")]
      (fs/delete-tree w)
      (s/write! dir "dhf/wiki/index.md"
                (str "# A wiki\n\nThe domain, distilled.[^1]\n\n## Pages\n\n"
                     "- [First](pages/first.md): the first page.\n"
                     "- [Second](pages/second.md): the second page.\n\n"
                     "[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n"))
      (doseq [n ["first" "second"]]
        (s/write! dir (str "dhf/wiki/pages/" n ".md")
                  (str "# The " n " page\n\nA claim.[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n")))
      (assoc ctx :wiki-pages ["pages/first.md" "pages/second.md"]))))

(defn- nav [ctx]
  (let [f (fs/file (source-dir (:dir ctx)) "mkdocs.yml")]
    (s/check! (fs/regular-file? f) "no site builder configuration was written")
    (get (yaml/parse-string (slurp (str f)) :keywords false) "nav")))

(defn- entry-name [e] (first (keys e)))

(defn- entry-value [e] (first (vals e)))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (passed! ctx)
    (let [home (page-text ctx "index.md")
          idx (page-text ctx "wiki/index.md")
          names (map entry-name (nav ctx))
          k (.indexOf ^java.util.List (vec names) "kernel")
          w (.indexOf ^java.util.List (vec (map str/lower-case names)) "wiki")]
      (s/check! (str/includes? home "](wiki/index.md)") "the home page does not link the wiki's index")
      (doseq [p (:wiki-pages ctx)]
        (s/check! (str/includes? idx (str "](" p ")")) (str "the index does not link " p))
        (s/check! (fs/regular-file? (fs/file (docs-dir (:dir ctx)) "wiki" p)) (str p " is not published")))
      (s/check! (and (>= k 0) (= w (inc k))) (str "the navigation is " (pr-str names)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site builder

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (model-project! ctx [["spec" [["parser" "Parser"] ["stamper" "Stamper"]]]
                         ["architecture" [["model" "Model"] ["lint" "Lint"]]]]
                    [["command" "Command"]])))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (passed! ctx)
    (let [n (nav ctx)
          names (vec (map entry-name n))]
      (s/check! (= "index.md" (entry-value (first n))) (str "the first entry is " (pr-str (first n))))
      (s/check! (= ["spec" "architecture" "kernel"] (subvec names 1 4)) (str "the navigation is " (pr-str names)))
      nil)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (nav ctx)
          m (:model ctx)
          want (concat (for [cx (get-in m [:containers 0 :contexts])]
                         [(:name cx) (cons (str (:name cx) "/index.md") (map #(str (:spec %) ".md") (:components cx)))])
                       [["kernel" (cons "kernel/index.md" (map #(str (:spec %) ".md") (:kernel m)))]])]
      (doseq [[sec paths] want
              :let [e (some #(when (= sec (entry-name %)) %) n)]]
        (s/check! e (str "no section " sec))
        (s/check! (= (vec paths) (mapv entry-value (entry-value e)))
                  (str "the section " sec " lists " (pr-str (entry-value e)))))
      nil)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (room! ctx)))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (let [r (passed! ctx)
          builder (:builder (site-conf (:dir ctx)))]
      (s/check! (some #{"--strict"} builder) (str "the configured builder does not fail on a broken link: " (pr-str builder)))
      (s/check! (= builder (get-in (kc/stdout-edn r) [:site :builder])) (str "the result names no builder\n" (s/show r)))
      (s/check! (fs/regular-file? (fs/file (html-dir (:dir ctx)) "index.html")) "no index.html was written")
      (s/check! (fs/regular-file? (fs/file (html-dir (:dir ctx)) "architecture" "lint.html"))
                "no HTML for Lint's page was written")
      nil)))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [ctx (room! ctx)]
      (s/write! (:dir ctx) "dhf/spec/architecture/lint.md"
                "## Design\n\nSee [the missing page](no-such-page.md) for more.\n")
      (assoc ctx :broken-page "architecture/lint.md" :broken-link "no-such-page.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % (:broken-page ctx)) (str/includes? % (:broken-link ctx))) (s/err-lines r))
                (str "no line names " (:broken-page ctx) " and " (:broken-link ctx) "\n" (s/show r)))
      nil)))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (let [hs (html-files (:dir ctx))]
      (s/check! (empty? hs) (str "HTML is left: " (pr-str (take 3 hs))))
      nil)))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [ctx (room! ctx)
          dir (:dir ctx)
          bin (kc/tmp-dir)
          builder (first (:builder (site-conf dir)))]
      (s/write! dir (str (:dir (site-conf dir)) "/index.html") "<html><body>An earlier site</body></html>\n")
      (s/write! dir (str (:dir (site-conf dir)) "/architecture/lint.html") "<html><body>An earlier page</body></html>\n")
      ;; PATH holds git and nothing else: the site builder is not installed
      (when-let [g (fs/which "git")] (fs/create-sym-link (fs/file bin "git") g))
      (s/check! (nil? (fs/which builder {:paths [bin]})) "the site builder is still on PATH")
      (-> ctx
          (assoc-in [:env "PATH"] (str bin))
          (assoc :builder builder)))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (kc/refused-naming! ctx (:builder ctx))
    (let [r (kc/the-run ctx)]
      (s/check! (some #(re-find #"(?i)no HTML was written" %) (s/err-lines r))
                (str "it does not say no HTML was written\n" (s/show r))))
    nil))
