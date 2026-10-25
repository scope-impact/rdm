(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec publish`
  in a project the scenario makes, a copy of the room's allow-list changed by
  the scenario (its model, its feature files, its wiki, its configuration),
  then read back as a reader would: the site's Markdown under the configured
  :site :source, the site builder's configuration there, and the HTML in the
  configured :site :dir.

  Defined here because publishing/site owns it: \"a wiki page whose footnote
  cites ...\", which dhf/spec/knowledge/ shares (its steps define it only when
  no step file does). Its meaning: the scenario's project (a copy of the room
  when the scenario has none yet) holds a wiki page, listed in the index, with
  one footnote citing the citation; ctx gets :page, :citation and :footnote.

  Shared steps used, the kernel's: the When \"rdm-spec ...\" is run (and its
  variants), \"a copy of rdm-spec's own project in which the configured
  results directory does not exist\" and \"a rendered view whose recorded hash
  differs from the model's\"."
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
;; Reading a project and its site

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- config! [dir f] (s/write! dir "dhf/harness/config.edn" (pr-str (f (config-of dir)))))

(defn- site-cfg [dir] (or (:site (config-of dir)) (s/fail! "the project configures no :site")))

(defn- builder-config-file
  "The site builder's configuration file: the one the builder is given with
  -f, else mkdocs.yml in the source directory."
  [dir]
  (let [{:keys [source builder]} (site-cfg dir)
        f (second (drop-while #(not= "-f" %) builder))]
    (fs/file dir (or f (str source "/mkdocs.yml")))))

(defn- builder-config [dir]
  (let [f (builder-config-file dir)]
    (s/check! (fs/regular-file? f) (str "no site builder configuration at " f))
    (yaml/parse-string (slurp (str f)) :keywords false)))

(defn- docs-dir
  "Where the site's Markdown is: the builder configuration's docs_dir."
  [dir]
  (fs/file (fs/parent (builder-config-file dir)) (get (builder-config dir) "docs_dir" "docs")))

(defn- html-dir [dir] (fs/file dir (:dir (site-cfg dir))))

(defn- md-pages
  "{docs-relative path text} of every Markdown page of the site."
  [dir]
  (let [d (docs-dir dir)]
    (s/check! (fs/directory? d) (str "the site holds no Markdown directory " d))
    (into (sorted-map)
          (for [f (fs/glob d "**.md") :when (fs/regular-file? f)]
            [(str/replace (str (fs/relativize d f)) "\\" "/") (slurp (str f))]))))

(defn- page-text [dir rel]
  (let [f (fs/file (docs-dir dir) rel)]
    (s/check! (fs/regular-file? f) (str "the site holds no page " rel))
    (slurp (str f))))

(defn- unescape [s] (str/replace (str s) #"\\([\\`*_\[\]<>{}|#()!.+-])" "$1"))

(defn- h1 [text] (some #(some-> (re-matches #"#\s+(.*?)\s*" %) second unescape) (str/split-lines (str text))))

(defn- links
  "The link targets of Markdown `text` (images included), anchors dropped."
  [text]
  (vec (for [[_ t] (re-seq #"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)" (str text))]
         t)))

(defn- resolve-link
  "The docs-relative path a relative link `target` on the page `from` names,
  or nil for a URL."
  [from target]
  (when-not (re-find #"^[a-zA-Z][\w+.-]*:" target)
    (let [t (str/replace target #"#.*$" "")]
      (when-not (str/blank? t)
        (str/replace (str (fs/normalize (fs/path (or (some-> (fs/parent from) str) "") t))) "\\" "/")))))

(defn- linked-pages [from text] (set (keep #(resolve-link from %) (links text))))

(defn- html-of
  "The HTML file the builder wrote for the page `rel`, or nil."
  [dir rel]
  (let [base (str/replace rel #"\.md$" "")
        cands [(str base ".html")
               (if (= "index" (fs/file-name base)) (str base ".html") (str base "/index.html"))]]
    (some #(let [f (fs/file (html-dir dir) %)] (when (fs/regular-file? f) f)) cands)))

(defn- html-files [dir]
  (let [d (html-dir dir)]
    (if (fs/exists? d) (vec (map str (fs/glob d "**.html"))) [])))

(defn- passed! [ctx]
  (let [r (kc/exits! ctx 0)] r))

(defn- sections-of
  "The page's sections: [[heading-line body]] split at every heading."
  [text]
  (let [lines (str/split-lines (str text))]
    (loop [ls lines cur nil out []]
      (if-let [[l & more] (seq ls)]
        (if (re-matches #"#{1,6}\s.*" l)
          (recur more [l []] (if cur (conj out cur) out))
          (recur more (when cur (update cur 1 conj l)) out))
        (if cur (conj out cur) out)))))

(defn- scenario-section
  "The text of the section whose heading names the scenario `id`, on any page
  of the site but the wiki's: [page text]."
  [dir id]
  (or (first (for [[rel text] (md-pages dir)
                   :when (not (str/starts-with? rel "wiki/"))
                   [h body] (sections-of text)
                   :when (re-find (re-pattern (str "(?<![\\w-])" id "(?![\\w-])")) h)]
               [rel (str h "\n" (str/join "\n" body))]))
      (s/fail! (str "no page of the site has a section for " id))))

(defn- shows! [ctx id word]
  (let [[rel sec] (scenario-section (:dir ctx) id)]
    (s/check! (re-find (re-pattern (str "(?i)status:?\\W*" word "\\b")) sec)
              (str id " does not show " word " on " rel ":\n" sec))
    nil))

(defn- tree-digest
  "{relative path sha-256} of every file under `d`."
  [d]
  (into (sorted-map)
        (for [f (fs/glob d "**") :when (fs/regular-file? f)]
          [(str (fs/relativize d f))
           (let [md (java.security.MessageDigest/getInstance "SHA-256")]
             (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes f)))))])))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- views!
  "Renders the project's views, so the views' gate passes on its model."
  [ctx dir]
  (let [r (kc/run! ctx dir ["views"])]
    (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))))

(defn- feature-of [ctx-key comp-key ids]
  (str "@UN-001\nFeature: " (str/capitalize comp-key) "\n  A fixture component of " ctx-key ".\n\n"
       "  Rule: The fixture's rule\n"
       (str/join (for [id ids]
                   (str "\n    @" id " @e2e\n    Scenario: Scenario " id " of " comp-key "\n"
                        "      Given a step of " id "\n")))))

(defn- model-project!
  "A copy of the room whose model and spec are replaced: `contexts` is
  [[context [component ..]] ..], `kernel` [component ..]. Each component
  owns a feature file of one scenario. Its views are rendered."
  [ctx contexts kernel]
  (let [dir (s/copy-of-room!)
        cfg (config-of dir)
        spec-dir (:spec-dir cfg)
        n (atom 0)
        comp (fn [c k] {:id (keyword c k) :name (str/capitalize k) :spec (str c "/" k)
                        :description (str "The " k " of " c)})
        model {:system {:id :demo-system :name "Demo" :description "A demo system"}
               :people [{:id :user :name "User" :actor #{:human} :description "Uses the demo"}]
               :external []
               :containers [{:id :demo :name "demo" :kind :runs :technology "t" :description "The one program"
                             :contexts (vec (for [[c ks] contexts]
                                              {:id (keyword c) :name c :description (str "The " c " context")
                                               :components (mapv #(comp c %) ks)}))}]
               :kernel (mapv #(comp "kernel" %) kernel)
               :relationships [{:from :user :to :demo :description "Uses"}]
               :views (vec (concat [{:key "context" :level :context :description "The demo and its user"}
                                    {:key "containers" :level :container :description "The one program"}]
                                   (for [[c _] contexts]
                                     {:key (str c "-components") :level :component :context (keyword c)
                                      :description (str "The " c " context's components")})))}]
    (doseq [f (fs/glob (fs/file dir spec-dir) "**.feature")] (fs/delete f))
    (s/write! dir (:model cfg) (pr-str model))
    (doseq [[c ks] (concat contexts [["kernel" kernel]]) k ks]
      (s/write! dir (str spec-dir "/" c "/" k ".feature")
                (feature-of c k [(format "spec-%04d" (swap! n inc))])))
    (views! ctx dir)
    (assoc ctx :dir dir :model model :contexts contexts :kernel kernel)))

(def ^:private lint-rel "architecture/lint")

(defn- lint-fixture!
  "A copy of the room whose Lint feature holds only the scenarios spec-0012,
  spec-0013 and spec-0014 (the last tagged @wip when `wip?`)."
  [ctx wip?]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        spec-dir (:spec-dir (config-of dir))]
    (s/write! dir (str spec-dir "/" lint-rel ".feature")
              (str "@UN-005\nFeature: Lint\n  The fixture's lint.\n\n  Rule: The fixture's rule\n"
                   (str/join (for [[id tags] [["spec-0012" ""] ["spec-0013" ""] ["spec-0014" (if wip? " @wip" "")]]]
                               (str "\n    @" id " @e2e" tags "\n    Scenario: Scenario " id "\n"
                                    "      Given a step of " id "\n")))))
    (assoc ctx :dir dir)))

(defn- result! [dir id status]
  (let [uuid (str (java.util.UUID/randomUUID))]
    (s/write! dir (str uuid "-result.json")
              (json/generate-string {:uuid uuid :name (str "a test of " id) :status status :stage "finished"
                                     :labels [{:name "spec" :value id}] :steps []}))))

(defn- wiki-dir-of [dir] (or (:wiki-dir (config-of dir)) "dhf/wiki"))

(def ^:private footnoted
  "The code is built from the spec, and can be deleted and built again.")

(defn- wiki-page!
  "The meaning of \"a wiki page whose footnote cites C\"."
  [ctx citation]
  (let [dir (or (:dir ctx) (s/copy-of-room!))
        wd (wiki-dir-of dir)
        rel "pages/regeneration.md"
        index (fs/file dir wd "index.md")]
    (s/write! dir (str wd "/" rel)
              (str "# Regeneration\n\n" footnoted "[^1]\n\n[^1]: `" citation "`, \"What to steal\" 1.\n"))
    (if (fs/regular-file? index)
      (spit (str index) (str "\n- [Regeneration](" rel "): what a regeneration measures.\n") :append true)
      (s/write! dir (str wd "/index.md") (str "# The wiki\n\n- [Regeneration](" rel ")\n")))
    (assoc ctx :dir dir :page (str wd "/" rel) :wiki-file rel :citation citation :footnote "[^1]")))

(defn- footnote-links!
  "Checks the published wiki file `rel` holds the footnote `label`, linked to
  `path` at `commit` in the repository `repo`, in its Markdown and its HTML."
  [ctx rel label path commit repo]
  (passed! ctx)
  (let [dir (:dir ctx)
        text (page-text dir (str "wiki/" rel))
        line (first (filter #(str/starts-with? (str/trim %) (str label ":")) (str/split-lines text)))
        ok? (fn [url] (and (str/includes? url repo) (str/includes? url (str "/" commit "/"))
                           (str/ends-with? url (str "/" path))))]
    (s/check! line (str "the published " rel " holds no footnote " label ":\n" text))
    (let [urls (keep #(when (re-find #"^https?://" %) %) (links line))]
      (s/check! (some ok? urls)
                (str "the footnote " label " links to " (vec urls) ", not to " path " at " commit " in " repo
                     "\n" line))
      (let [h (html-of dir (str "wiki/" rel))]
        (s/check! h (str "no HTML was written for wiki/" rel))
        (s/check! (some #(str/includes? (slurp (str h)) (str "href=\"" % "\"")) (filter ok? urls))
                  (str "the HTML of wiki/" rel " does not link the footnote's source"))))
    nil))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (model-project! ctx [["shop" ["cart" "checkout" "payment"]] ["stock" ["items" "orders"]]] [])))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          pages (md-pages dir)
          want (for [[c ks] (:contexts ctx) k ks] [c (str/capitalize k)])
          found (for [[c k] want
                      :let [hit (first (for [[rel text] pages
                                             :when (and (str/starts-with? rel (str c "/"))
                                                        (not= "index.md" (fs/file-name rel))
                                                        (= k (h1 text)))]
                                         rel))]]
                  [c k hit])]
      (s/check! (= 5 (count want)) "the fixture does not hold five components")
      (doseq [[c k hit] found]
        (s/check! hit (str "no page under " c "/ is named for the component " k))
        (s/check! (str/includes? (str/lower-case hit) (str/lower-case k))
                  (str "the page " hit " is not named for the component " k))
        (s/check! (html-of dir hit) (str "no HTML was written for " hit)))
      (let [component-pages (filter (fn [[rel _]] (and (re-matches #"[^/]+/[^/]+\.md" rel)
                                                       (not= "index.md" (fs/file-name rel))
                                                       (not (re-find #"^(wiki|views)/" rel))))
                                    pages)]
        (s/check! (= 5 (count component-pages))
                  (str "the site holds " (count component-pages) " component pages: " (vec (keys component-pages)))))
      nil)))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          spec-dir (:spec-dir (config-of dir))
          prose "Lint reads the model the way a reviewer would, and names every rule a view or element breaks."]
      (s/write! dir (str spec-dir "/" lint-rel ".md") (str "# Lint\n\n" prose "\n"))
      (assoc ctx :dir dir :prose prose))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          cfg (config-of dir)
          model (edn/read-string (slurp (str (fs/file dir (:model cfg)))))
          comp (first (for [c (:containers model) cx (:contexts c) k (:components cx)
                            :when (= lint-rel (:spec k))] k))
          page (first (for [[rel text] (md-pages dir)
                            :when (and (str/starts-with? rel "architecture/") (= (:name comp) (h1 text)))]
                        text))
          plain (unescape page)
          f (str (fs/file dir (:spec-dir cfg) (str lint-rel ".feature")))
          scs (:scenarios (gherkin/parse-feature f (slurp f)))]
      (s/check! page (str "no page of the bounded context architecture is named " (:name comp)))
      (s/check! (str/includes? plain (:description comp)) (str "the page lacks the description " (:description comp)))
      (s/check! (str/includes? plain (:prose ctx)) "the page lacks the prose")
      (s/check! (seq scs) "the fixture's Lint has no scenario")
      (doseq [sc scs
              :let [id (some #(second (re-matches #"@(spec-\d+)" %)) (:tags sc))
                    level (some #(second (re-matches #"@(e2e|contract)" %)) (:tags sc))
                    [_ sec] (scenario-section dir id)]]
        (s/check! (re-find (re-pattern (str "\\b" level "\\b")) sec) (str id "'s section lacks its level " level))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? sec (str (:keyword st) " " (:text st)))
                    (str id "'s section lacks the step " (:keyword st) " " (:text st)))))
      nil)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          views (or (:views-dir (config-of dir)) "dhf/views")
          k "architecture-components"]
      (s/check! (fs/regular-file? (fs/file dir views (str k ".svg"))) (str "the view " k " is not rendered"))
      (assoc ctx :dir dir :view k))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          pages (md-pages dir)
          model (edn/read-string (slurp (str (fs/file dir (:model (config-of dir))))))
          cx (first (for [c (:containers model) cx (:contexts c) :when (= :architecture (:id cx))] cx))
          [rel text] (or (first (filter (fn [[rel text]] (and (str/starts-with? rel "architecture/")
                                                              (= (:name cx) (h1 text))))
                                        pages))
                         (s/fail! "no page of the site is the architecture page"))
          targets (linked-pages rel text)
          svg (str "views/" (:view ctx) ".svg")]
      (s/check! (contains? targets svg) (str rel " does not show " svg ": " (vec targets)))
      (s/check! (fs/regular-file? (fs/file (docs-dir dir) svg)) (str "the site holds no " svg))
      (doseq [k (:components cx)]
        (s/check! (some #(= (:name k) (h1 (get pages %))) targets)
                  (str rel " links to no page of the component " (:name k))))
      nil)))

(defn- fixture-scenarios
  "Every stamped scenario of the project's spec: [{:id :needs}]."
  [dir]
  (let [spec (fs/file dir (:spec-dir (config-of dir)))]
    (vec (for [f (sort (map str (fs/glob spec "**.feature")))
               sc (:scenarios (gherkin/parse-feature f (slurp f)))
               :let [ids (keep #(second (re-matches #"@(spec-\d{4,})" %)) (:tags sc))]
               :when (= 1 (count ids))]
           {:id (first ids) :needs (set (keep #(second (re-matches #"@(UN-\d+)" %)) (:all-tags sc)))}))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          cfg (config-of dir)
          model (edn/read-string (slurp (str (fs/file dir (:model cfg)))))
          needs (edn/read-string (slurp (str (fs/file dir (:needs cfg)))))
          home (page-text dir "index.md")
          targets (linked-pages "index.md" home)
          scs (fixture-scenarios dir)
          secs (sections-of home)]
      (doseq [v (:views model) :when (#{:context :container} (:level v))]
        (s/check! (some #(str/ends-with? % (str (:key v) ".svg")) targets)
                  (str "the home page does not show the view " (:key v))))
      (s/check! (seq needs) "the fixture declares no need")
      (doseq [[n _] needs
              :let [i (first (keep-indexed (fn [i [h _]] (when (re-find (re-pattern (str "\\b" n "\\b")) h) i)) secs))
                    _ (s/check! i (str "the home page has no section for " n))
                    [h body] (nth secs i)
                    depth (count (re-find #"^#+" h))
                    more (take-while (fn [[h2 _]] (> (count (re-find #"^#+" h2)) depth)) (drop (inc i) secs))
                    text (str/join "\n" (concat body (mapcat (fn [[h2 b]] (cons h2 b)) more)))]
              sc scs :when (contains? (:needs sc) n)]
        (s/check! (re-find (re-pattern (str "\\b" (:id sc) "\\b")) text)
                  (str "the home page does not list " (:id sc) " under " n)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which (spec-\d+) passed and (spec-\d+) failed"
  (fn [ctx passed failed]
    (let [ctx (lint-fixture! ctx false)
          results (kc/tmp-dir)]
      (result! results passed "passed")
      (result! results failed "failed")
      (assoc ctx :results results))))

(defstep #"(spec-\d+) shows (verified|failed|unverified|planned) and (spec-\d+) shows (verified|failed|unverified|planned)"
  (fn [ctx a wa b wb]
    (passed! ctx)
    (shows! ctx a wa)
    (shows! ctx b wb)))

(defstep #"the scenario @(spec-0014) tagged @wip"
  (fn [ctx _id] (lint-fixture! ctx true)))

(defstep #"(spec-\d+) shows (verified|failed|unverified|planned)"
  (fn [ctx id word]
    (passed! ctx)
    (shows! ctx id word)))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          pages (md-pages dir)
          wiki (fs/file dir (wiki-dir-of dir))
          wiki-count (count (fs/glob wiki "**.md"))]
      (s/check! (pos? wiki-count) "the fixture has no wiki")
      (s/check! (= wiki-count (count (filter #(str/starts-with? % "wiki/") (keys pages))))
                (str "the site holds " (count (filter #(str/starts-with? % "wiki/") (keys pages)))
                     " wiki pages, the wiki " wiki-count))
      (doseq [[rel text] pages]
        (s/check! (str/includes? (str/lower-case (unescape text)) "no status was derived because no results were given")
                  (str rel " does not say no status was derived because no results were given")))
      nil)))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          k (:stale-view ctx)]
      (s/check! k "no view was made stale")
      (s/check! (some #(and (str/includes? % k) (str/includes? % "stale")) (s/err-lines r))
                (str "no finding names the stale view " k "\n" (s/show r)))
      nil)))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (passed! ctx)
    (let [before (or (:spec-before ctx) (kc/spec-snapshot s/room-root))]
      (s/check! (= before (kc/spec-snapshot (:dir ctx))) "a file under dhf/spec changed")
      nil)))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [ctx (lint-fixture! ctx false)
          dir (:dir ctx)
          rd (fs/file dir (:results-dir (config-of dir)))]
      (fs/create-dirs rd)
      (result! rd "spec-0012" "passed")
      (result! rd "spec-0013" "failed")
      (assoc-in ctx [:env "RESULTS_DIR"] nil))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice, the first site kept aside"
  (fn [ctx line]
    (let [c1 (kc/run-line ctx line)
          dir (:dir c1)
          src (fs/parent (builder-config-file dir))
          kept (fs/file (kc/tmp-dir) "first-site")]
      (s/check! (zero? (:exit (:run c1))) (str "the first publish failed\n" (s/show (:run c1))))
      (fs/copy-tree src kept)
      (let [c2 (kc/run-line c1 line)]
        (assoc c2 :runs [(:run c1) (:run c2)] :kept (str kept))))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          first-site (tree-digest (:kept ctx))
          second-site (tree-digest (fs/parent (builder-config-file dir)))]
      (s/check! (some #(str/ends-with? % ".md") (keys first-site)) "the first site holds no Markdown")
      (s/check! (contains? first-site (str (fs/file-name (builder-config-file dir))))
                "the first site holds no site builder configuration")
      (s/check! (= first-site second-site)
                (str "the sites differ in "
                     (vec (for [k (sort (into (set (keys first-site)) (keys second-site)))
                                :when (not= (get first-site k) (get second-site k))] k))))
      (s/check! (some #(re-find #"(?i)status:?\W*verified" %) (vals (md-pages dir)))
                "no page shows a status: the results directory was not read")
      nil)))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [dir (:dir ctx)
          src (fs/parent (builder-config-file dir))
          bad (remove str/blank? [(str (fs/canonicalize dir)) (str dir) s/room-root
                                  (str (fs/temp-dir)) (System/getProperty "user.home")])]
      (doseq [f (fs/glob src "**")
              :when (and (fs/regular-file? f) (re-find #"\.(md|ya?ml)$" (str f)))
              :let [text (slurp (str f))]
              b bad]
        (s/check! (not (str/includes? text b)) (str (fs/relativize dir f) " names the path " b)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation] (wiki-page! ctx citation)))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (footnote-links! ctx (:wiki-file ctx) (:footnote ctx) path commit repo)))

(defstep #"the wiki's index holds a footnote citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          index (fs/file dir (wiki-dir-of dir) "index.md")]
      (s/check! (fs/regular-file? index) "the fixture's wiki has no index")
      (spit (str index) (str "\nThe index rests on its sources too.[^index-note]\n\n"
                             "[^index-note]: `" citation "`, \"What to steal\" 1.\n")
            :append true)
      (assoc ctx :dir dir :citation citation :footnote "[^index-note]"))))

(defstep #"the published index's footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (footnote-links! ctx "index.md" (:footnote ctx) path commit repo)))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          wd (wiki-dir-of dir)
          pages {"pages/first.md" "First" "pages/second.md" "Second"}]
      (fs/delete-tree (fs/file dir wd))
      (s/write! dir (str wd "/index.md")
                (str "# The wiki\n\nTwo pages, each resting on a source.[^1]\n\n"
                     (str/join (for [[rel t] pages] (str "- [" t "](" rel ")\n")))
                     "\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n"))
      (doseq [[rel t] pages]
        (s/write! dir (str wd "/" rel)
                  (str "# " t "\n\n" footnoted "[^1]\n\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n")))
      (assoc ctx :dir dir :wiki-pages (vec (keys pages))))))

(defn- nav-paths
  "Every page path a navigation entry (or list of entries) holds, in order."
  [x]
  (cond (string? x) [x]
        (map? x) (mapcat nav-paths (vals x))
        (sequential? x) (mapcat nav-paths x)
        :else []))

(defn- nav-key [entry] (when (map? entry) (str (first (keys entry)))))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          home (page-text dir "index.md")
          index (page-text dir "wiki/index.md")
          nav (get (builder-config dir) "nav")
          wanted (map #(str "wiki/" %) (:wiki-pages ctx))
          ki (first (keep-indexed (fn [i e] (when (re-find #"(?i)kernel" (str (nav-key e))) i)) nav))
          wi (first (keep-indexed (fn [i e] (when (re-find #"(?i)wiki" (str (nav-key e))) i)) nav))]
      (s/check! (contains? (linked-pages "index.md" home) "wiki/index.md") "the home page does not link the wiki's index")
      (doseq [p wanted]
        (s/check! (contains? (linked-pages "wiki/index.md" index) p) (str "the wiki's index does not link " p))
        (s/check! (fs/regular-file? (fs/file (docs-dir dir) p)) (str "the site does not hold " p)))
      (s/check! ki (str "the navigation holds no kernel section: " (pr-str nav)))
      (s/check! wi (str "the navigation holds no wiki section: " (pr-str nav)))
      (s/check! (> wi ki) "the wiki section does not come after the kernel's")
      (let [in-wiki (set (nav-paths (nth nav wi)))]
        (doseq [p (cons "wiki/index.md" wanted)]
          (s/check! (contains? in-wiki p) (str "the wiki section does not hold " p))))
      nil)))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (model-project! ctx [["spec" ["parser" "stamper"]] ["architecture" ["model" "lint"]]] ["command"])))

(defn- sections-nav [dir]
  (let [nav (get (builder-config dir) "nav")]
    (s/check! (sequential? nav) "the site builder's configuration holds no navigation")
    nav))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (passed! ctx)
    (let [nav (sections-nav (:dir ctx))
          home (nav-paths (first nav))
          sections (filter #(and (map? %) (sequential? (first (vals %)))) (rest nav))]
      (s/check! (= ["index.md"] home) (str "the first entry is not the home page: " (pr-str (first nav))))
      (s/check! (>= (count sections) 3) (str "the navigation holds " (count sections) " sections"))
      (doseq [[e want] (map vector sections ["spec" "architecture" "kernel"])]
        (s/check! (re-find (re-pattern (str "(?i)\\b" want "\\b")) (nav-key e))
                  (str "the section " (pr-str (nav-key e)) " is not the one for " want)))
      nil)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [dir (:dir ctx)
          pages (md-pages dir)
          sections (filter #(and (map? %) (sequential? (first (vals %)))) (rest (sections-nav dir)))
          want (concat (for [[c ks] (:contexts ctx)] [c (mapv str/capitalize ks)])
                       [["kernel" (mapv str/capitalize (:kernel ctx))]])]
      (doseq [[e [c comps]] (map vector sections want)
              :let [paths (nav-paths e)]]
        (s/check! (seq paths) (str "the section " c " lists no page"))
        (s/check! (= c (h1 (get pages (first paths))))
                  (str "the section " c " does not list its context's page first: " (vec paths)))
        (s/check! (= comps (mapv #(h1 (get pages %)) (rest paths)))
                  (str "the section " c " lists " (mapv #(h1 (get pages %)) (rest paths)) ", not " comps)))
      nil)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          {:keys [builder]} (site-cfg dir)]
      (s/check! (some #{"--strict"} builder) (str "the configured builder does not fail on a broken link: " builder))
      (doseq [[rel _] (md-pages dir)]
        (s/check! (html-of dir rel) (str "no HTML was written for " rel)))
      nil)))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (s/write! dir (str (:spec-dir (config-of dir)) "/" lint-rel ".md")
                "# Lint\n\nSee [the page nobody wrote](no-such-page.md).\n")
      (assoc ctx :dir dir :broken-link "no-such-page.md" :broken-page "lint.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % (:broken-page ctx)) (str/includes? % (:broken-link ctx)))
                      (s/err-lines r))
                (str "no line names the page " (:broken-page ctx) " and the link " (:broken-link ctx) "\n" (s/show r)))
      nil)))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (let [left (html-files (:dir ctx))]
      (s/check! (empty? left) (str "HTML is left in the site directory: " (vec (take 5 left))))
      nil)))

(def ^:private missing-builder "rdm-spec-no-such-site-builder")

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (config! dir #(update-in % [:site :builder] (fn [b] (vec (cons missing-builder (rest b))))))
      (s/write! dir (str (:dir (site-cfg dir)) "/index.html") "<html><body>An earlier site</body></html>\n")
      (s/write! dir (str (:dir (site-cfg dir)) "/architecture/lint.html") "<html><body>Lint</body></html>\n")
      (s/check! (seq (html-files dir)) "the earlier site holds no HTML")
      (assoc ctx :dir dir :builder missing-builder))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (kc/refused-naming! ctx (:builder ctx) "no HTML was written")
    nil))
