(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec publish`
  on a copy of the room, or on a small project `rdm-spec init` wrote with a
  model of the step's own, read back from the Markdown and the site
  builder's configuration it writes under the configured :site :source,
  and the HTML under :site :dir.

  The site's layout, as publish writes it (rdm-spec.publishing.site): the
  home page docs/index.md; a page per bounded context, docs/<context>/index.md,
  and per component, docs/<context>/<component>.md (names lower-cased,
  anything but letters and digits a hyphen); the kernel under docs/kernel/;
  the wiki under docs/wiki/ as it lies under the wiki directory.

  The shared steps (\"rdm-spec LINE\" is run, no file in the spec changes, a
  copy of rdm-spec's own project in which the configured results directory
  does not exist, a rendered view whose recorded hash differs from the
  model's, a wiki page whose footnote cites C) are the kernel's.

  Context keys set here: :dir the project; :results a results directory;
  :components the component pages expected; :first-site the first site's
  files {path bytes}; :spec-before (spec-0100); :builder the missing builder."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Where the site is

(defn- site-config [dir] (merge {:dir "site" :source "site-src"} (:site (kc/config-of dir))))

(defn- docs [dir] (fs/file dir (:source (site-config dir)) "docs"))

(defn- page-file [dir rel] (fs/file (docs dir) rel))

(defn- page [dir rel]
  (let [f (page-file dir rel)]
    (s/check! (fs/exists? f) (str "the site holds no page " rel))
    (slurp (str f))))

(defn- slug [s] (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn- html-files [dir]
  (let [d (fs/file dir (:dir (site-config dir)))]
    (if (fs/directory? d) (vec (fs/glob d "**.html")) [])))

(defn- published! [ctx]
  (let [r (kc/exits! ctx 0)]
    (s/check! (fs/directory? (docs (:dir ctx))) (str "no site was written\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------
;; Small projects

(defn- feature-text [comp id]
  (str "@UN-001\nFeature: " (:name comp) "\n  " (:description comp) "\n\n"
       "  Rule: " (:name comp) " does its work\n\n"
       "    @" id " @e2e\n"
       "    Scenario: " (:name comp) " does its work\n"
       "      Given a " (str/lower-case (:name comp)) "\n"
       "      When it runs\n"
       "      Then it holds\n"))

(defn- comp* [ctx-name cname]
  {:id (keyword ctx-name (slug cname)) :name cname :spec (str ctx-name "/" (slug cname))
   :description (str "Does the work of " cname)})

(defn- site-project!
  "A project `rdm-spec init` wrote, its model holding the bounded contexts
  `contexts` [[name [component-name ...]] ...] and the kernel components
  `kernel` [name ...], a feature file of one scenario per component, one
  need, and its views generated."
  [ctx contexts kernel]
  (let [{:keys [dir]} (kc/project-set-up! ctx)
        ctxs (vec (for [[n cs] contexts]
                    {:id (keyword n) :name n :description (str "The " n " context")
                     :components (mapv #(comp* n %) cs)}))
        kern (mapv (fn [n] (assoc (comp* "kernel" n) :id (keyword "kernel" (slug n)))) kernel)
        m {:system {:id :demo :name "Demo" :description "A demo system"}
           :people [{:id :reader :name "Reader" :description "Reads the site" :actor #{:human}}]
           :external []
           :containers [{:id :app :name "app" :kind :runs :technology "Any" :description "The demo program"
                         :contexts ctxs}]
           :kernel kern
           :relationships [{:from :reader :to :app :description "Reads with"}]
           :views (vec (concat [{:key "context" :level :context :description "The demo and its reader"}
                                {:key "containers" :level :container :description "The demo program"}]
                               (for [x ctxs] {:key (str (name (:id x)) "-components") :level :component
                                              :context (:id x) :description (str "The " (:name x) " context")})))}
        all (concat (for [x ctxs c (:components x)] c) kern)]
    (s/write! dir "dhf/spec/model.edn" (pr-str m))
    (doseq [[i c] (map-indexed vector all)]
      (s/write! dir (str "dhf/spec/" (:spec c) ".feature") (feature-text c (format "spec-%04d" (inc i)))))
    (s/write! dir "dhf/spec/ids.edn" (str "{:issued " (count all) "}\n"))
    (s/write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text "A reader reads the site." :sources ["loyly:README.md@17d68d2"]}}))
    (let [r (kc/run! ctx dir ["views"])]
      (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r))))
    (assoc ctx :dir dir :contexts contexts :kernel kernel)))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (site-project! ctx [["booking" ["Slots" "Rules" "Calendar"]] ["billing" ["Invoice" "Refund"]]] [])))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (let [r (published! ctx)
          d (docs (:dir ctx))
          want (for [[x cs] (:contexts ctx) c cs] (str x "/" (slug c) ".md"))
          pages (set (for [x (map first (:contexts ctx))
                           f (fs/glob (fs/file d x) "*.md")
                           :when (not= "index.md" (str (fs/file-name f)))]
                       (str x "/" (fs/file-name f))))]
      (s/check! (= 5 (count want)) "the fixture holds no five components")
      (s/check! (= (set want) pages) (str "the component pages are " (sort pages) ", not " (sort want) "\n" (s/show r)))
      (doseq [[x cs] (:contexts ctx) c cs]
        (let [t (page (:dir ctx) (str x "/" (slug c) ".md"))]
          (s/check! (and (str/includes? t c) (str/includes? t x))
                    (str "the page of " c " does not name " c " and " x))))
      nil)))

(defn- room-features [dir spec-path]
  (let [f (str (fs/file dir "dhf/spec" (str spec-path ".feature")))]
    (gherkin/parse-feature f (slurp f))))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          prose "The lint holds the model to the checklist a person would otherwise hold it to by hand."]
      (s/write! dir "dhf/spec/architecture/lint.md" (str "# Lint\n\n" prose "\n"))
      (assoc ctx :dir dir :prose prose))))

(defn- scenario-section
  "The text of the page `text` from the heading naming `id` to the next
  heading of a scenario or rule."
  [text id]
  (let [ls (str/split-lines text)
        start (first (keep-indexed (fn [i l] (when (and (str/starts-with? l "#") (str/includes? l id)) i)) ls))]
    (when start
      (str/join "\n" (cons (nth ls start)
                           (take-while #(not (re-find #"^#{1,3} " %)) (drop (inc start) ls)))))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (published! ctx)
    (let [t (page (:dir ctx) "architecture/lint.md")
          parsed (room-features (:dir ctx) "architecture/lint")]
      (s/check! (str/includes? t "The C4 skill's checklist and the bounded-context rules")
                "the page does not hold the component's description from the model")
      (s/check! (str/includes? t (:prose ctx)) "the page does not hold the component's prose")
      (doseq [sc (:scenarios parsed)
              :let [id (subs (first (filter #(str/starts-with? % "@spec-") (:tags sc))) 1)
                    level (subs (first (filter #{"@e2e" "@contract"} (:tags sc))) 1)
                    sec (scenario-section t id)]]
        (s/check! sec (str "the page holds no section for " id))
        (s/check! (str/includes? sec level) (str id "'s section does not give its level " level "\n" sec))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? sec (str (:keyword st) " " (:text st)))
                    (str id "'s section does not hold the step " (:keyword st) " " (:text st) "\n" sec))))
      nil)))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (s/check! (fs/exists? (fs/file dir "dhf/views/architecture-components.svg")) "no rendered component view")
      (assoc ctx :dir dir))))

(defn- links [text] (set (map second (re-seq #"\]\(([^)\s]+)" text))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (published! ctx)
    (let [t (page (:dir ctx) "architecture/index.md")
          ls (links t)]
      (s/check! (some #(str/ends-with? % "architecture-components.svg") ls)
                (str "the page does not show the component view\n" t))
      (doseq [c ["model.md" "lint.md" "views.md"]]
        (s/check! (contains? ls c) (str "the page does not link " c "\n" t)))
      nil)))

(defn- need-section [text need]
  (let [ls (str/split-lines text)
        start (first (keep-indexed (fn [i l] (when (and (str/starts-with? l "#") (str/includes? l need)) i)) ls))]
    (when start
      (str/join "\n" (take-while #(not (str/starts-with? % "#")) (drop (inc start) ls))))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          t (page dir "index.md")
          ls (links t)
          needs (keys (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn")))))
          tracing (fn [need]
                    (for [f (fs/glob (fs/file dir "dhf/spec") "**.feature")
                          :let [p (gherkin/parse-feature (str f) (slurp (str f)))]
                          sc (:scenarios p)
                          :when (some #{(str "@" need)} (:all-tags sc))]
                      (subs (first (filter #(str/starts-with? % "@spec-") (:tags sc))) 1)))]
      (doseq [v ["context.svg" "containers.svg"]]
        (s/check! (some #(str/ends-with? % v) ls) (str "the home page does not show " v)))
      (doseq [n needs
              :let [sec (need-section t n)]]
        (s/check! sec (str "the home page holds no section for " n))
        (doseq [id (tracing n)]
          (s/check! (str/includes? sec id) (str "the home page does not list " id " under " n))))
      nil)))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defn- result-json [id status]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defn- write-results! [d statuses]
  (doseq [[id st] statuses]
    (s/write! d (str (random-uuid) "-result.json") (result-json id st)))
  d)

(defstep #"Allure results in which (spec-\d+) passed and (spec-\d+) failed"
  (fn [ctx a b]
    (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!))
           :results (write-results! (s/tmp-dir) [[a "passed"] [b "failed"]]))))

(defn- shows! [ctx id status]
  (let [t (page (:dir ctx) "architecture/lint.md")
        sec (scenario-section t id)]
    (s/check! sec (str "the Lint page holds no section for " id))
    (s/check! (str/includes? sec (str "Status: " status)) (str id " does not show " status "\n" sec))))

(defstep #"(spec-\d+) shows verified and (spec-\d+) shows failed"
  (fn [ctx a b]
    (published! ctx)
    (shows! ctx a "verified")
    (shows! ctx b "failed")
    nil))

(defstep #"the scenario @(spec-\d+) tagged @wip"
  (fn [ctx id]
    (let [dir (s/copy-of-room!)
          f (fs/file dir "dhf/spec/architecture/lint.feature")
          text (slurp (str f))
          changed (str/replace text (re-pattern (str "@" id " @(e2e|contract)")) (str "@" id " @$1 @wip"))]
      (s/check! (not= text changed) (str "lint.feature holds no " id))
      (spit (str f) changed)
      (assoc ctx :dir dir))))

(defstep #"(spec-\d+) shows planned"
  (fn [ctx id]
    (published! ctx)
    (shows! ctx id "planned")
    nil))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (published! ctx)
    (let [pages (fs/glob (docs (:dir ctx)) "**.md")]
      (s/check! (some #(str/includes? (str %) "/wiki/") pages) "no wiki page was published")
      (doseq [p pages :let [t (str/lower-case (slurp (str p)))]]
        (s/check! (and (str/includes? t "no status was derived") (str/includes? t "no results were given"))
                  (str p " does not say no status was derived because no results were given")))
      nil)))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % ": view-stale: ") (str/includes? % (:stale-view ctx)))
                      (kc/finding-lines r))
                (str "no finding names the stale view " (:stale-view ctx) "\n" (s/show r)))
      nil)))

(defstep #"a project set up by rdm-spec, its spec as it is before the publish kept aside"
  (fn [ctx]
    (let [ctx (kc/project-set-up! ctx)]
      (assoc ctx :spec-before (kc/spec-snapshot (:dir ctx))))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (write-results! (fs/file dir (:results-dir (kc/config-of dir))) [["spec-0012" "passed"] ["spec-0013" "failed"]])
      (assoc ctx :dir dir))))

(defn- site-files
  "{path bytes-as-vector} of the site's Markdown and builder configuration."
  [dir]
  (let [src (fs/file dir (:source (site-config dir)))]
    (into (sorted-map)
          (for [f (fs/glob src "**")
                :when (and (fs/regular-file? f) (re-find #"\.(md|yml|yaml)$" (str f)))]
            [(str (fs/relativize src f)) (vec (fs/read-all-bytes f))]))))

(defstep #"\"rdm-spec publish\" is run twice, the first site kept aside"
  (fn [ctx]
    (let [a (kc/run-line ctx "publish")
          _ (s/check! (zero? (:exit (:run a))) (str "the first publish failed\n" (s/show (:run a))))
          first-site (site-files (:dir a))
          b (kc/run-line a "publish")]
      (assoc b :first-site first-site))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (published! ctx)
    (let [now (site-files (:dir ctx))
          was (:first-site ctx)]
      (s/check! (seq was) "the first site holds no file")
      (s/check! (some #(str/ends-with? % ".yml") (keys now)) "the site holds no builder configuration")
      (s/check! (= (set (keys was)) (set (keys now)))
                (str "the sites hold different files: " (sort (keys was)) " and " (sort (keys now))))
      (doseq [k (keys now)]
        (s/check! (= (get was k) (get now k)) (str k " differs between the two sites")))
      nil)))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [dir (str (fs/canonicalize (:dir ctx)))
          outside [dir (str (fs/canonicalize (s/fixture-root))) (str (fs/absolutize (:dir ctx)))
                   s/room-root (System/getProperty "user.home") "/tmp/"]]
      (doseq [[k bytes] (site-files (:dir ctx))
              :let [t (String. (byte-array bytes) "UTF-8")]
              o outside
              :when (not (str/blank? o))]
        (s/check! (not (str/includes? t o)) (str k " names the path " o)))
      nil)))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(def ^:private loyly-readme "https://github.com/skallinen/loyly/blob/17d68d2/README.md")

(defn- footnote-line [text label]
  (first (filter #(str/starts-with? (str/triml %) (str label ":")) (str/split-lines text))))

(defstep #"the page is published and the footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (published! ctx)
    (let [wiki (or (:wiki-dir (kc/config-of (:dir ctx))) "dhf/wiki")
          rel (str "wiki/" (subs (:page ctx) (inc (count wiki))))
          t (page (:dir ctx) rel)
          l (footnote-line t (:footnote ctx))]
      (s/check! l (str "the published page holds no footnote " (:footnote ctx) "\n" t))
      (s/check! (str/includes? l (str "(" loyly-readme ")")) (str "the footnote does not link " loyly-readme ": " l))
      nil)))

(defstep #"the wiki's index holds a footnote citing \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (or (:wiki-dir (kc/config-of dir)) "dhf/wiki")]
      (spit (str (fs/file dir wiki "index.md"))
            (str "\nThe index rests on a source too.[^idx]\n\n[^idx]: `" citation "`, \"How to read the repo\".\n")
            :append true)
      (assoc ctx :dir dir :footnote "[^idx]"))))

(defstep #"the published index's footnote links to README\.md at 17d68d2 in skallinen/loyly"
  (fn [ctx]
    (published! ctx)
    (let [t (page (:dir ctx) "wiki/index.md")
          l (footnote-line t (:footnote ctx))]
      (s/check! l (str "the published index holds no footnote " (:footnote ctx)))
      (s/check! (str/includes? l (str "(" loyly-readme ")")) (str "the footnote does not link " loyly-readme ": " l))
      nil)))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (or (:wiki-dir (kc/config-of dir)) "dhf/wiki")
          pages (vec (sort (map #(str (fs/relativize (fs/file dir wiki) %)) (fs/glob (fs/file dir wiki "pages") "*.md"))))]
      (s/check! (= 2 (count pages)) (str "the wiki holds " (count pages) " pages, not two"))
      (assoc ctx :dir dir :wiki-pages pages))))

(defn- nav [dir]
  (let [f (fs/file dir (:source (site-config dir)) "mkdocs.yml")]
    (s/check! (fs/exists? f) "no site builder configuration was written")
    (get (yaml/parse-string (slurp (str f)) :keywords false) "nav")))

(defn- section-title [entry] (first (keys entry)))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (published! ctx)
    (s/check! (contains? (links (page (:dir ctx) "index.md")) "wiki/index.md") "the home page does not link the wiki's index")
    (let [ls (links (page (:dir ctx) "wiki/index.md"))]
      (doseq [p (:wiki-pages ctx)]
        (s/check! (contains? ls p) (str "the published index does not link " p))))
    (let [titles (mapv section-title (nav (:dir ctx)))
          ki (.indexOf titles "kernel")
          wi (.indexOf titles "wiki")]
      (s/check! (and (>= ki 0) (= wi (inc ki))) (str "the navigation is " titles ": no wiki section right after the kernel")))
    nil))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (site-project! ctx [["spec" ["Parser" "Stamper"]] ["architecture" ["Model" "Lint"]]] ["Command"])))

(defn- entry-target [e] (let [v (first (vals e))] (if (string? v) v v)))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (published! ctx)
    (let [n (nav (:dir ctx))
          titles (mapv section-title n)]
      (s/check! (= "index.md" (entry-target (first n))) (str "the navigation does not start with the home page: " (pr-str (first n))))
      (s/check! (= ["spec" "architecture" "kernel"] (subvec titles 1 4))
                (str "the navigation's sections are " titles))
      nil)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (nav (:dir ctx))
          want (concat (:contexts ctx) [["kernel" (:kernel ctx)]])]
      (doseq [[x cs] want
              :let [entries (get (first (filter #(= x (section-title %)) n)) x)
                    targets (mapv entry-target entries)]]
        (s/check! (= (vec (cons (str x "/index.md") (map #(str x "/" (slug %) ".md") cs))) targets)
                  (str "the section " x " lists " targets)))
      nil)))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (let [r (published! ctx)
          builder (:builder (site-config (:dir ctx)))]
      (s/check! (some #{"--strict"} builder) (str "the configured builder does not fail on a broken link: " builder))
      (s/check! (fs/exists? (fs/file (:dir ctx) (:dir (site-config (:dir ctx))) "index.html"))
                (str "no HTML was written\n" (s/show r)))
      nil)))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (s/write! dir "dhf/spec/architecture/lint.md"
                "# Lint\n\nThe lint's checklist is explained on [another page](missing-page.md).\n")
      (assoc ctx :dir dir :page "architecture/lint.md" :link "missing-page.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % (:page ctx)) (str/includes? % (:link ctx))) (kc/finding-lines r))
                (str "no finding names the page " (:page ctx) " and the link " (:link ctx) "\n" (s/show r)))
      nil)))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (let [hs (html-files (:dir ctx))]
      (s/check! (empty? hs) (str "HTML is left in the site directory: " (str/join ", " (take 3 hs))))
      nil)))

(defstep #"the configured site builder is not installed, and a site directory left by an earlier publish"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          r (kc/run! ctx dir ["publish"])
          _ (s/check! (zero? (:exit r)) (str "the earlier publish failed\n" (s/show r)))
          _ (s/check! (seq (html-files dir)) "the earlier publish left no HTML")
          c (kc/config-of dir)
          missing "rdm-spec-no-such-site-builder"]
      (s/check! (nil? (fs/which missing)) (str missing " is installed"))
      (s/write! dir "dhf/harness/config.edn" (pr-str (assoc-in c [:site :builder] [missing "build" "--strict"])))
      (assoc ctx :dir dir :builder missing))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (kc/refused-naming! ctx (:builder ctx) "no HTML was written")
    nil))
