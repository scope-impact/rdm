(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec publish`
  in a copy of the room (or a project made from one), read back from the
  Markdown and the site builder's configuration it writes under the
  configured :site :source, and the HTML the builder writes to :site :dir.

  Views are recorded as dhf/spec/architecture/views.feature (@spec-0017)
  fixes: the SHA-256 of model.edn's bytes, as lowercase hex, on each output's
  first line. A Given that changes the model writes fresh views for it, so the
  scenario depends on nothing but the recorded hash."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(def model-rel "dhf/spec/model.edn")

(defn- site-cfg [] (:site (s/room-config)))
(defn- source-dir [dir] (fs/file dir (:source (site-cfg))))
(defn- docs-dir [dir] (fs/file (source-dir dir) "docs"))
(defn- html-dir [dir] (fs/file dir (:dir (site-cfg))))

(defn- needs-site! [] (s/needs-built! 'rdm-spec.publishing.site 'rdm-spec.spec.check 'rdm-spec.spec.parser))

(defn sha256-hex [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn- file-hash [f] (sha256-hex (fs/read-all-bytes f)))

(defn- read-model [dir] (edn/read-string (slurp (str (fs/file dir model-rel)))))

(defn- write-model! [dir m]
  (s/write! dir model-rel (with-out-str (binding [pprint/*print-right-margin* 110] (pprint/pprint m)))))

(defn fresh-views!
  "Writes, for every view of the project's model, a diagram source and a
  render recording the model's hash on their first line."
  [dir]
  (let [h (file-hash (fs/file dir model-rel))
        views-dir (:views-dir (s/room-config))]
    (fs/delete-tree (fs/file dir views-dir))
    (doseq [{k :key} (:views (read-model dir))]
      (s/write! dir (str views-dir "/" k ".mmd") (str "%% model-hash: " h "\nC4Context\n  title " k "\n"))
      (s/write! dir (str views-dir "/" k ".svg")
                (str "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"40\"><!-- model-hash: " h " -->"
                     "\n<text x=\"4\" y=\"20\">" k "</text></svg>\n")))
    dir))

(defn- room!
  "A copy of the room's allow-list whose views are fresh."
  []
  (needs-site!)
  (fresh-views! (s/copy-of-room!)))

(defn- contexts-of [m] (vec (mapcat :contexts (:containers m))))

(defn- keep-contexts
  "The model with only the bounded contexts `ids`, in the model's order, and
  only the views that still have their context."
  [m ids]
  (let [keep? (set ids)]
    (-> m
        (update :containers (fn [cs] (mapv #(cond-> % (:contexts %) (update :contexts (fn [xs] (filterv (comp keep? :id) xs)))) cs)))
        (update :views (fn [vs] (filterv #(or (not= :component (:level %)) (keep? (:context %))) vs))))))

(defn- page-of
  "A component's page, relative to the docs directory."
  [ctx-name comp]
  (if (:spec comp)
    (str (:spec comp) ".md")
    (str ctx-name "/" (str/lower-case (str/replace (:name comp) #"\W+" "-")) ".md")))

(defn- page-text [dir rel]
  (let [f (fs/file (docs-dir dir) rel)]
    (s/check! (fs/exists? f) (str "the site holds no page " rel))
    (slurp (str f))))

(defn- md-pages [dir]
  (let [d (docs-dir dir)]
    (if (fs/directory? d)
      (vec (sort (map #(str/replace (str (fs/relativize d %)) "\\" "/") (fs/glob d "**.md"))))
      [])))

(defn- ok! [ctx]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (= 0 (:exit r)) (str "publish did not pass\n" (s/show r)))
    r))

(defn- h1 [text] (second (re-find #"(?m)^# (.+)$" text)))

(defn- scenario-section
  "The part of the site's page that shows scenario `id`: from its anchor to
  the next scenario's."
  [dir id]
  (let [anchor (str "<a id=\"" id "\"></a>")
        hits (for [p (md-pages dir)
                   :let [t (page-text dir p)
                         i (str/index-of t anchor)]
                   :when i]
               (let [rest-t (subs t (+ i (count anchor)))
                     j (str/index-of rest-t "<a id=\"")]
                 (if j (subs rest-t 0 j) rest-t)))]
    (s/check! (= 1 (count hits)) (str id " is shown on " (count hits) " pages, not one"))
    (first hits)))

(defn- shown-status [dir id]
  (second (re-find #"\*\*Status:\*\* ([a-z]+(?: derived)?)" (scenario-section dir id))))

(defn- allure-result [id status]
  (json/generate-string {:uuid (str (java.util.UUID/randomUUID)) :name (str "a test of " id) :status status
                         :labels [{:name "spec" :value id}] :steps []}))

(defn- write-results! [dir pairs]
  (doseq [[id status] pairs]
    (s/write! dir (str (java.util.UUID/randomUUID) "-result.json") (allure-result id status)))
  (str dir))

(defn- spec-files
  "{relative path bytes-hash} of every file under dhf/spec in `dir`."
  [dir]
  (let [d (fs/file dir "dhf/spec")]
    (into (sorted-map)
          (for [f (fs/glob d "**") :when (fs/regular-file? f)]
            [(str (fs/relativize d f)) (file-hash f)]))))

(defn- parsed-features [dir]
  (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))]
    (gherkin/parse-feature f (slurp f))))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          m (-> (read-model dir) (keep-contexts [:spec :knowledge]) (dissoc :kernel))
          _ (needs-site!)
          _ (write-model! dir m)
          pages (vec (for [c (contexts-of m) comp (:components c)]
                       {:context (:name c) :component (:name comp) :page (page-of (:name c) comp)}))]
      (s/check! (= 5 (count pages)) (str "the model holds " (count pages) " components"))
      (fresh-views! dir)
      (assoc ctx :dir dir :expected-pages pages))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (ok! ctx)
    (let [dir (:dir ctx)
          comp-pages (remove #(or (= "index.md" (fs/file-name %)) (str/starts-with? % "wiki/")) (md-pages dir))]
      (s/check! (= 5 (count comp-pages)) (str "the site holds the component pages " (pr-str comp-pages)))
      (s/check! (= (set (map :page (:expected-pages ctx))) (set comp-pages))
                (str "the pages are " (pr-str comp-pages) ", not " (pr-str (map :page (:expected-pages ctx)))))
      (doseq [{:keys [context component page]} (:expected-pages ctx)
              :let [title (h1 (page-text dir page))]]
        (s/check! (and title (str/includes? title context) (str/includes? title component))
                  (str page "'s title " (pr-str title) " does not name " context " and " component))))))

(def lint-prose "The lint reads the model once and reports every finding it holds, never the first alone.")

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [dir (room!)]
      (s/write! dir "dhf/spec/architecture/lint.md" (str "# Lint, by design\n\n" lint-prose "\n"))
      (assoc ctx :dir dir))))

(defstep #"Lint's page holds its description, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (ok! ctx)
    (let [dir (:dir ctx)
          comp (first (for [c (contexts-of (read-model dir)) comp (:components c)
                            :when (= :architecture/lint (:id comp))] comp))
          page (page-text dir (page-of "architecture" comp))
          feature (gherkin/parse-feature "lint.feature" (slurp (str (fs/file dir "dhf/spec/architecture/lint.feature"))))]
      (s/check! (str/includes? page (:description comp)) "the page does not hold Lint's description")
      (s/check! (str/includes? page lint-prose) "the page does not hold Lint's prose")
      (s/check! (seq (:scenarios feature)) "Lint has no scenario")
      (doseq [sc (:scenarios feature)
              :let [id (subs (first (filter #(re-matches #"@spec-\d+" %) (:tags sc))) 1)
                    level (subs (first (filter #{"@e2e" "@contract"} (:tags sc))) 1)
                    section (scenario-section dir id)]]
        (s/check! (str/includes? section (str "**Level:** " level)) (str id " does not show its level " level))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? section (str (:keyword st) " " (:text st)))
                    (str id " does not show its step " (pr-str (:text st)))))))))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [dir (room!)]
      (s/check! (fs/exists? (fs/file dir "dhf/views/architecture-components.svg")) "no rendered component view")
      (assoc ctx :dir dir))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (ok! ctx)
    (let [dir (:dir ctx)
          page (page-text dir "architecture/index.md")
          arch (first (filter #(= :architecture (:id %)) (contexts-of (read-model dir))))]
      (s/check! (str/includes? page "](../views/architecture-components.svg)") "the page does not show the view")
      (s/check! (re-find #"!\[[^\]]*\]\(\.\./views/architecture-components\.svg\)" page) "the view is no image")
      (s/check! (fs/exists? (fs/file (docs-dir dir) "views/architecture-components.svg")) "the view is not published")
      (doseq [comp (:components arch)
              :let [target (fs/file-name (page-of "architecture" comp))]]
        (s/check! (str/includes? page (str "](" target ")")) (str "no link to " target))
        (s/check! (fs/exists? (fs/file (docs-dir dir) "architecture" target)) (str target " does not exist"))))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (needs-site!)
    (ok! ctx)
    (let [dir (:dir ctx)
          home (page-text dir "index.md")
          m (read-model dir)
          needs (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn"))))]
      (doseq [v (:views m) :when (#{:context :container} (:level v))]
        (s/check! (re-find (re-pattern (str "!\\[[^\\]]*\\]\\(views/" (:key v) "\\.svg\\)")) home)
                  (str "the home page does not show the view " (:key v))))
      (doseq [[need _] needs
              :let [i (str/index-of home (str "### " need))
                    _ (s/check! i (str "the home page shows no need " need))
                    section (let [t (subs home (+ i 4))
                                  j (str/index-of t "\n### ")]
                              (if j (subs t 0 j) t))
                    traced (for [f (parsed-features dir) sc (:scenarios f)
                                 :when (some #{(str "@" need)} (:all-tags sc))
                                 id (filter #(re-matches #"@spec-\d+" %) (:tags sc))]
                             (subs id 1))]]
        (doseq [id traced]
          (s/check! (str/includes? section id) (str need " does not list " id)))))))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [dir (room!)]
      (assoc ctx :dir dir :results (write-results! (s/tmp-dir) [["spec-0012" "passed"] ["spec-0013" "failed"]])))))

(defstep #"\"rdm-spec publish([^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (let [args (mapv #(if (= "DIR" %) (:results ctx) %) (s/split-args (str "publish " line)))]
      (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args)))))

(defstep #"(spec-\d+) shows (\w+) and (spec-\d+) shows (\w+)"
  (fn [ctx id1 st1 id2 st2]
    (ok! ctx)
    (doseq [[id st] [[id1 st1] [id2 st2]]]
      (s/check! (= st (shown-status (:dir ctx) id)) (str id " shows " (pr-str (shown-status (:dir ctx) id)) ", not " st)))))

(defstep #"the scenario @(spec-\d+) tagged @wip"
  (fn [ctx id]
    (let [dir (room!)
          tag-line (re-pattern (str "(?m)^(\\s*)@" id "(?=\\s)"))
          f (first (filter #(re-find tag-line (slurp (str %))) (fs/glob (fs/file dir "dhf/spec") "**.feature")))]
      (s/check! f (str "no scenario is tagged @" id))
      (spit (str f) (str/replace (slurp (str f)) tag-line (str "$1@wip @" id)))
      (assoc ctx :dir dir))))

(defstep #"(spec-\d+) shows (\w+)"
  (fn [ctx id st]
    (ok! ctx)
    (s/check! (= st (shown-status (:dir ctx) id)) (str id " shows " (pr-str (shown-status (:dir ctx) id)) ", not " st))))

(defstep #"every page says no status was derived because no results were given"
  (fn [ctx]
    (needs-site!)
    (ok! ctx)
    (let [pages (md-pages (:dir ctx))]
      (s/check! (seq pages) "the site holds no page")
      (doseq [p pages]
        (s/check! (str/includes? (str/lower-case (page-text (:dir ctx) p))
                                 "no status was derived because no results were given")
                  (str p " does not say no status was derived"))))))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (needs-site!)
    (let [r (:run ctx)
          k (:stale-view ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (some #(and (str/includes? % "view-stale") (str/includes? % (str "\"" k "\""))) (s/err-lines r))
                (str "no finding names the stale view " k "\n" (s/show r))))))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (needs-site!)
    (ok! ctx)
    (let [before (spec-files s/room-root)
          after (spec-files (:dir ctx))]
      (s/check! (= before after)
                (str "dhf/spec changed: "
                     (pr-str (remove #(= (get before %) (get after %)) (distinct (concat (keys before) (keys after))))))))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [dir (room!)]
      (write-results! (fs/file dir (:results-dir (s/room-config)))
                      [["spec-0012" "passed"] ["spec-0013" "failed"] ["spec-0092" "passed"]])
      (assoc ctx :dir dir))))

(defn- digest-of-site
  "{path sha256} of every file under the site's source directory."
  [dir]
  (let [d (source-dir dir)]
    (into (sorted-map)
          (for [f (fs/glob d "**") :when (fs/regular-file? f)]
            [(str/replace (str (fs/relativize dir f)) "\\" "/") (file-hash f)]))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (needs-site!)
    (let [[r1 r2] (:runs ctx)
          files-of #(get-in (edn/read-string (:out %)) [:site :files])]
      (s/check! (and r1 r2) "publish was not run twice")
      (doseq [r [r1 r2]] (s/check! (= 0 (:exit r)) (s/show r)))
      (let [f1 (files-of r1) f2 (files-of r2) now (digest-of-site (:dir ctx))
            cfg (str (:source (site-cfg)) "/mkdocs.yml")]
        (s/check! (some #(str/ends-with? % ".md") (keys f1)) (str "the first run reports no Markdown: " (pr-str f1)))
        (s/check! (contains? f1 cfg) (str "the first run reports no " cfg))
        (s/check! (= f1 f2) "the two runs wrote different files")
        (s/check! (= f2 (select-keys now (keys f2))) "the files reported are not the files written")
        (s/check! (every? (set (keys f2)) (filter #(or (str/ends-with? % ".md") (str/ends-with? % ".yml")) (keys now)))
                  "a file written is not reported")))))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (room!)
          wiki (:wiki-dir (s/room-config))]
      (s/write! dir (str wiki "/regeneration.md")
                (str "# Regeneration\n\nThe code is built from the spec, and can be deleted and built again.[^1]\n\n"
                     "[^1]: `" citation "`, \"What to steal\" 1.\n"))
      (spit (str (fs/file dir wiki "index.md")) "\n- [Regeneration](regeneration.md)\n" :append true)
      (assoc ctx :dir dir :wiki-page "regeneration"))))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (ok! ctx)
    (let [dir (:dir ctx)
          page (page-text dir (str "wiki/" (:wiki-page ctx) ".md"))
          footnote (first (filter #(str/starts-with? % "[^1]:") (str/split-lines page)))
          urls (map second (re-seq #"\]\((\S+?)\)" (or footnote "")))
          ok? (fn [u] (and (str/includes? u repo)
                           (re-find (re-pattern (str "/" commit "/" (java.util.regex.Pattern/quote path) "$")) u)))
          html (fs/file (html-dir dir) "wiki" (:wiki-page ctx) "index.html")]
      (s/check! footnote (str "the published page holds no footnote\n" page))
      (s/check! (some ok? urls) (str "the footnote links to " (pr-str urls)))
      (s/check! (fs/exists? html) (str "no HTML was written for the page: " html))
      (s/check! (str/includes? (slurp (str html)) (first (filter ok? urls))) "the HTML does not carry the link"))))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          _ (needs-site!)
          m (keep-contexts (read-model dir) [:spec :architecture])]
      (s/check! (= [:spec :architecture] (mapv :id (contexts-of m))) "the model's contexts are not spec, architecture")
      (write-model! dir m)
      (fresh-views! dir)
      (assoc ctx :dir dir))))

(defn- nav [dir]
  (let [f (fs/file (source-dir dir) "mkdocs.yml")]
    (s/check! (fs/exists? f) "the site builder's configuration was not written")
    (vec (get (yaml/parse-string (slurp (str f)) :keywords false) "nav"))))

(defn- entry [x] (first (seq x)))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (ok! ctx)
    (let [n (nav (:dir ctx))
          titles (mapv (comp key entry) n)]
      (s/check! (= "index.md" (val (entry (first n)))) (str "the first entry is " (pr-str (first n))))
      (s/check! (= ["spec" "architecture" "kernel"] (subvec titles 1)) (str "the sections are " (pr-str titles))))))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [dir (:dir ctx)
          m (read-model dir)
          n (nav dir)
          expected (concat (for [c (contexts-of m)]
                             [(:name c) (cons (str (:name c) "/index.md") (map #(page-of (:name c) %) (:components c)))])
                           [["kernel" (cons "kernel/index.md" (map #(page-of "kernel" %) (:kernel m)))]])]
      (doseq [[title pages] expected
              :let [section (some #(when (= title (key (entry %))) (val (entry %))) n)
                    listed (mapv (comp val entry) section)]]
        (s/check! (= (vec pages) listed) (str title " lists " (pr-str listed) ", not " (pr-str pages)))
        (doseq [p pages] (s/check! (fs/exists? (fs/file (docs-dir dir) p)) (str p " does not exist")))))))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (assoc ctx :dir (room!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (ok! ctx)
    (let [dir (:dir ctx)]
      (s/check! (some #{"--strict"} (:builder (site-cfg))) "the configured builder does not fail on a broken link")
      (doseq [p ["index.html" "architecture/index.html" "architecture/lint/index.html" "kernel/command/index.html"]]
        (s/check! (fs/exists? (fs/file (html-dir dir) p)) (str "no HTML was written at " p))))))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [dir (room!)]
      (s/write! dir "dhf/spec/architecture/lint.md" "# Lint\n\nSee [the missing page](missing-page.md).\n")
      (assoc ctx :dir dir :page "architecture/lint.md" :link "missing-page.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (needs-site!)
    (let [r (:run ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (some #(and (str/includes? % (:page ctx)) (str/includes? % (:link ctx))) (s/err-lines r))
                (str "no line names " (:page ctx) " and " (:link ctx) "\n" (s/show r))))))

(def missing-builder "rdm-spec-test-no-such-site-builder")

(defstep #"the configured site builder is not installed"
  (fn [ctx]
    (let [dir (room!)
          f (str (fs/file dir "dhf/harness/config.edn"))
          cfg (edn/read-string (slurp f))]
      (s/check! (nil? (fs/which missing-builder)) (str missing-builder " is installed"))
      (spit f (pr-str (assoc-in cfg [:site :builder] [missing-builder "build" "--strict"])))
      (assoc ctx :dir dir))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (needs-site!)
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (some #(and (str/includes? % missing-builder) (str/includes? % "no HTML was written")) (s/err-lines r))
                (str "no line names " missing-builder " and says no HTML was written\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file (html-dir (:dir ctx)) "index.html"))) "HTML was written"))))
