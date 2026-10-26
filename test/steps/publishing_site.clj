(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec publish`
  on a copy of the room or a project rdm-spec init wrote, read back from the
  Markdown and the site builder's configuration it writes under the
  configured :site :source (site-src), and the HTML under :site :dir (site).

  The site, as these steps read it (rdm-spec.publishing.site):
    <source>/mkdocs.yml                    the site builder's configuration
    <source>/docs/index.md                 the home page
    <source>/docs/<context>/index.md       a bounded context's page
    <source>/docs/<context>/<component>.md a component's page (the part of
                                           its :spec after the context)
    <source>/docs/kernel/...               the kernel's, as a context's
    <source>/docs/wiki/...                 the wiki, as it lies under :wiki-dir
    <source>/docs/views/<key>.svg          the rendered views
  A scenario is a heading naming its id; its status is a line under it. A
  wiki footnote's citation links to <repository>/blob/<commit>/<path>.

  Context keys set here: :dir the project; :results a results directory;
  :spec-before the spec kept aside; :prose the prose written beside Lint's
  feature file; :first-site the first site kept aside ({rel text});
  :builder the configured site builder that is not installed."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Reading the site

(defn- site-cfg [dir] (merge {:dir "site" :source "site-src"} (:site (kc/config-of dir))))

(defn- docs [dir] (fs/file dir (:source (site-cfg dir)) "docs"))

(defn- page-text
  "The published page `rel` (under docs/); the step fails when there is none."
  [ctx rel]
  (let [f (fs/file (docs (:dir ctx)) rel)]
    (s/check! (fs/regular-file? f) (str "no page " rel "\n" (s/show (kc/the-run ctx))))
    (slurp (str f))))

(defn- pages
  "{rel text} of every Markdown page of the site."
  [dir]
  (let [d (docs dir)]
    (into (sorted-map) (for [f (fs/glob d "**.md") :when (fs/regular-file? f)]
                         [(str (fs/relativize d f)) (slurp (str f))]))))

(defn- site-source-files [dir]
  (let [d (fs/file dir (:source (site-cfg dir)))]
    (into (sorted-map) (for [f (fs/glob d "**") :when (and (fs/regular-file? f) (not (str/ends-with? (str f) ".svg")))]
                         [(str (fs/relativize d f)) (slurp (str f))]))))

(defn- nav [ctx]
  (let [f (fs/file (:dir ctx) (:source (site-cfg (:dir ctx))) "mkdocs.yml")]
    (s/check! (fs/regular-file? f) (str "no mkdocs.yml\n" (s/show (kc/the-run ctx))))
    (vec (get (yaml/parse-string (slurp (str f)) :keywords false) "nav"))))

(defn- nav-entries
  "The navigation as [[title target]], target a page or a vector of entries."
  [n]
  (vec (for [e n] (let [[k v] (first (if (map? e) e {"" e}))]
                    [k (if (sequential? v) (nav-entries v) v)]))))

(defn- model-of [dir]
  (edn/read-string (slurp (str (fs/file dir (or (:model (kc/config-of dir)) "dhf/spec/model.edn"))))))

(defn- spec-scenarios
  "Every stamped scenario of the project's spec: [{:id :file :name :level
  :needs :steps}]."
  [dir]
  (vec (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
             :let [rel (str (fs/relativize dir f))]
             sc (:scenarios (gherkin/parse-feature rel (slurp f)))
             :let [id (some #(second (re-matches #"@(spec-\d{4,})" %)) (:tags sc))]
             :when id]
         {:id id :file rel :name (:name sc)
          :level (some #(second (re-matches #"@(e2e|contract)" %)) (:tags sc))
          :needs (keep #(second (re-matches #"@(UN-\d+)" %)) (:all-tags sc))
          :steps (map :text (concat (:background sc) (:steps sc)))})))

(defn- section-of
  "The part of `text` from the line naming `id` up to the next heading."
  [text id]
  (let [ls (str/split-lines text)
        i (first (keep-indexed (fn [i l] (when (and (str/starts-with? l "#") (str/includes? l id)) i)) ls))]
    (when i
      (str/join "\n" (cons (nth ls i) (take-while #(not (str/starts-with? % "#")) (drop (inc i) ls)))))))

(defn- scenario-section
  "The section of the scenario `id` on whichever component page holds it."
  [ctx id]
  (or (some #(section-of % id) (vals (pages (:dir ctx))))
      (s/fail! (str "no page shows " id "\n" (s/show (kc/the-run ctx))))))

(defn- shows! [ctx id status]
  (kc/exits! ctx 0)
  (let [sec (scenario-section ctx id)]
    (s/check! (re-find (re-pattern (str "(?i)\\bstatus\\b.*\\b" status "\\b")) sec)
              (str id " does not show " status ":\n" sec))))

(defn- write-model! [dir m]
  (s/write! dir (or (:model (kc/config-of dir)) "dhf/spec/model.edn") (pr-str m)))

(defn- model-with [contexts kernel]
  {:system {:id :sauna :name "Sauna" :description "Books sauna turns"}
   :people [{:id :bather :name "Bather" :description "Books a turn" :actor #{:human}}]
   :containers [{:id :app :name "App" :kind :runs :technology "A program" :description "Takes the bookings"
                 :contexts (vec (for [[c comps] contexts]
                                  {:id c :name (name c) :description (str "The " (name c) " context")
                                   :components (vec (for [x comps]
                                                      {:id (keyword (name c) x) :name (str/capitalize x)
                                                       :description (str "The " x " component")
                                                       :spec (str (name c) "/" x)}))}))}]
   :kernel (vec (for [x kernel] {:id (keyword "kernel" x) :name (str/capitalize x)
                                 :description (str "The " x " of the kernel") :spec (str "kernel/" x)}))
   :relationships [{:from :bather :to :app :description "Books with"}]})

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (let [{:keys [dir] :as ctx} (kc/project-set-up! ctx)
          m (model-with [[:booking ["calendar" "turns" "reminder"]] [:billing ["invoice" "payment"]]] [])]
      (write-model! dir m)
      (assoc ctx :model m))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [want #{"booking/calendar.md" "booking/turns.md" "booking/reminder.md" "billing/invoice.md" "billing/payment.md"}
          got (set (for [rel (keys (pages (:dir ctx)))
                         :let [parts (str/split rel #"/")]
                         :when (and (= 2 (count parts)) (not= "index.md" (second parts))
                                    (not (#{"wiki" "kernel" "views"} (first parts))))]
                     rel))]
      (s/check! (= want got) (str "the component pages are " (pr-str (sort got))))
      nil)))

(def ^:private prose "The lint's own design prose, written beside its feature file.")

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (s/write! dir "dhf/spec/architecture/lint.md" (str "# Lint\n\n" prose "\n"))
      (assoc ctx :dir dir :prose prose))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [text (page-text ctx "architecture/lint.md")
          desc (some (fn [c] (some #(when (= :architecture/lint (:id %)) (:description %)) (:components c)))
                     (mapcat :contexts (:containers (model-of (:dir ctx)))))
          scs (filter #(= "dhf/spec/architecture/lint.feature" (:file %)) (spec-scenarios (:dir ctx)))]
      (s/check! (str/includes? text desc) "the page does not hold the model's description")
      (s/check! (str/includes? text (:prose ctx)) "the page does not hold the prose")
      (s/check! (seq scs) "the lint feature holds no scenario")
      (doseq [sc scs
              :let [sec (scenario-section ctx (:id sc))]]
        (s/check! (str/includes? (str/lower-case sec) (:level sc)) (str (:id sc) " shows no level " (:level sc)))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? text st) (str (:id sc) "'s step is not shown: " st))))
      nil)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (s/check! (fs/exists? (fs/file dir "dhf/views/architecture-components.svg")) "the view is not rendered")
      (assoc ctx :dir dir))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [text (page-text ctx "architecture/index.md")]
      (s/check! (re-find #"!\[[^\]]*\]\(\.\./views/architecture-components\.svg\)" text) "the page does not show the view")
      (s/check! (fs/exists? (fs/file (docs (:dir ctx)) "views/architecture-components.svg")) "the view is not published")
      (doseq [c ["lint" "model" "views"]]
        (s/check! (str/includes? text (str "](" c ".md)")) (str "the page does not link " c ".md")))
      nil)))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [text (page-text ctx "index.md")
          needs (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/needs.edn"))))
          scs (spec-scenarios (:dir ctx))]
      (doseq [v ["views/context.svg" "views/containers.svg"]]
        (s/check! (str/includes? text (str "](" v ")")) (str "the home page does not show " v)))
      (doseq [[id _] needs
              :let [sec (or (section-of text id) (s/fail! (str "the home page shows no need " id)))]
              sc scs :when (some #{id} (:needs sc))]
        (s/check! (str/includes? sec (:id sc)) (str (:id sc) " is not listed under " id)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defn- result-json [id status]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\",\"steps\":[],"
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          rd (str (fs/file (s/tmp-dir) "results"))]
      (s/write! rd "a-result.json" (result-json "spec-0012" "passed"))
      (s/write! rd "b-result.json" (result-json "spec-0013" "failed"))
      (assoc ctx :dir dir :results rd))))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx] (shows! ctx "spec-0012" "verified") (shows! ctx "spec-0013" "failed") nil))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          f (fs/file dir "dhf/spec/architecture/lint.feature")
          text (slurp (str f))]
      (when-not (re-find #"@spec-0014\b[^\n]*@wip" text)
        (spit (str f) (str/replace text #"(@spec-0014\b[^\n]*)" "$1 @wip")))
      (assoc ctx :dir dir))))

(defstep #"spec-0014 shows planned"
  (fn [ctx] (shows! ctx "spec-0014" "planned") nil))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [ps (pages (:dir ctx))]
      (s/check! (some #(str/starts-with? % "wiki/") (keys ps)) "no wiki page was published")
      (doseq [[rel text] ps]
        (s/check! (str/includes? text "no status was derived because no results were given")
                  (str rel " does not say no status was derived")))
      nil)))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (str "\"" (:stale-view ctx) "\"")))
                      (kc/finding-lines r))
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

(defn- spec-files [dir]
  (into (sorted-map) (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize dir f)) (slurp (str f))])))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx]
    (let [ctx (kc/project-set-up! ctx)]
      (assoc ctx :spec-before (spec-files (:dir ctx))))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          rd (fs/file dir (or (:results-dir (kc/config-of dir)) "dhf/allure-results"))]
      (s/write! rd "a-result.json" (result-json "spec-0012" "passed"))
      (s/write! rd "b-result.json" (result-json "spec-0013" "failed"))
      (assoc ctx :dir dir))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice, the first site kept aside"
  (fn [ctx line]
    (let [a (kc/run-line ctx line)
          first-site (site-source-files (:dir a))
          b (kc/run-line a line)]
      (s/check! (zero? (:exit (:run a))) (str "the first run failed\n" (s/show (:run a))))
      (assoc b :first-site first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [a (:first-site ctx)
          b (site-source-files (:dir ctx))]
      (s/check! (seq a) "the first site holds nothing")
      (s/check! (= (set (keys a)) (set (keys b))) "the sites hold different files")
      (doseq [[rel t] a] (s/check! (= t (get b rel)) (str rel " differs between the runs")))
      nil)))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [outside [(str (fs/absolutize (:dir ctx))) (s/fixture-root) s/room-root (System/getProperty "user.home")]]
      (doseq [[rel t] (site-source-files (:dir ctx)) p outside :when (not (str/blank? p))]
        (s/check! (not (str/includes? t p)) (str rel " names " p)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(def ^:private loyly-readme "https://github.com/skallinen/loyly/blob/17d68d2/README.md")

(defn- footnote-links! [text where]
  (s/check! (some #(and (re-find #"^\s*\[\^[^\]]+\]:" %) (str/includes? % (str "](" loyly-readme ")")))
                  (str/split-lines text))
            (str "no footnote of " where " links to " loyly-readme)))

(defstep #"the page is published and the footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [wiki (or (:wiki-dir (kc/config-of (:dir ctx))) "dhf/wiki")
          rel (str "wiki/" (subs (:page ctx) (inc (count wiki))))]
      (footnote-links! (page-text ctx rel) rel)
      nil)))

(defstep #"the wiki's index holds a footnote citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          f (fs/file dir (or (:wiki-dir (kc/config-of dir)) "dhf/wiki") "index.md")]
      (when-not (re-find (re-pattern (str "(?m)^\\s*\\[\\^[^\\]]+\\]:.*" (java.util.regex.Pattern/quote citation))) (slurp (str f)))
        (spit (str f) (str "\nThe checklist is derived.[^cite]\n\n[^cite]: `" citation "`, \"The idea\".\n") :append true))
      (assoc ctx :dir dir))))

(defstep #"the published index's footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (kc/exits! ctx 0)
    (footnote-links! (page-text ctx "wiki/index.md") "the index")
    nil))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (fs/file dir (or (:wiki-dir (kc/config-of dir)) "dhf/wiki"))
          ps (sort (map #(str (fs/relativize wiki %)) (remove #(= "index.md" (str (fs/file-name %))) (fs/glob wiki "**.md"))))]
      (s/check! (= 2 (count ps)) (str "the wiki holds the pages " (pr-str ps)))
      (assoc ctx :dir dir :wiki-pages ps))))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [home (page-text ctx "index.md")
          index (page-text ctx "wiki/index.md")
          titles (mapv first (nav-entries (nav ctx)))]
      (s/check! (str/includes? home "](wiki/index.md)") "the home page does not link the wiki's index")
      (doseq [p (:wiki-pages ctx)]
        (s/check! (str/includes? index (str "](" p ")")) (str "the index does not link " p)))
      (s/check! (= ["kernel" "wiki"] (take-last 2 (map str/lower-case titles)))
                (str "the navigation's sections are " (pr-str titles)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (let [{:keys [dir] :as ctx} (kc/project-set-up! ctx)
          m (model-with [[:spec ["parser" "stamper"]] [:architecture ["model" "lint"]]] ["command"])]
      (write-model! dir m)
      (assoc ctx :model m))))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [es (nav-entries (nav ctx))
          titles (mapv (comp str/lower-case first) es)]
      (s/check! (= "index.md" (second (first es))) (str "the first entry is " (pr-str (first es))))
      (s/check! (= ["spec" "architecture" "kernel"] (subvec titles 1 4)) (str "the sections are " (pr-str titles)))
      nil)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [es (into {} (map (fn [[k v]] [(str/lower-case k) v])) (nav-entries (nav ctx)))]
      (doseq [[c comps] [["spec" ["parser" "stamper"]] ["architecture" ["model" "lint"]] ["kernel" ["command"]]]]
        (s/check! (= (cons (str c "/index.md") (map #(str c "/" % ".md") comps)) (map second (get es c)))
                  (str "the section " c " lists " (pr-str (get es c))))))
    nil))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [{:keys [builder dir]} (site-cfg (:dir ctx))]
      (s/check! (some #{"--strict"} builder) (str "the site builder " (pr-str builder) " does not fail on a broken link"))
      (s/check! (fs/regular-file? (fs/file (:dir ctx) dir "index.html")) "no HTML was written")
      nil)))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))]
      (s/write! dir "dhf/spec/architecture/lint.md" "# Lint\n\nSee [the missing page](missing-page.md).\n")
      (assoc ctx :dir dir))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % "architecture/lint.md") (str/includes? % "missing-page.md")) (kc/finding-lines r))
                (str "no finding names the page and the link\n" (s/show r)))
      nil)))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (kc/the-run ctx)
    (let [d (fs/file (:dir ctx) (:dir (site-cfg (:dir ctx))))
          html (when (fs/exists? d) (seq (fs/glob d "**.html")))]
      (s/check! (empty? html) (str "HTML is left: " (pr-str (map str html))))
      nil)))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          cfg (kc/config-of dir)
          builder "rdm-spec-no-such-site-builder"]
      (s/write! dir "dhf/harness/config.edn" (pr-str (assoc-in cfg [:site :builder] [builder "build"])))
      (s/write! dir (str (:dir (site-cfg dir)) "/index.html") "<html><body>an earlier publish</body></html>\n")
      (assoc ctx :dir dir :builder builder))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx] (kc/refused-naming! ctx (:builder ctx) "no HTML was written") nil))
