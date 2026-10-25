(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature.

  Every scenario runs `rdm-spec publish` in a project of its own: a copy of
  the room's allow-listed paths, or (for the scenarios about the model's
  shape) such a copy whose model and feature files are replaced by a small
  fixture, its ids within the copied ledger, its views rendered afresh. What
  was published is read back from the configured site source (:site :source,
  its Markdown under docs/, the builder's configuration mkdocs.yml) and the
  configured site directory, and what it should hold is read independently
  from the project's own files (feature files, needs, model, views).

  Two steps share their wording with other features and keep one meaning
  there: \"a wiki page whose footnote cites C\" (knowledge: a project, the
  scenario's own or else a fresh copy of the room, whose wiki holds a page
  listed in the index with one footnote [^1] citing C; ctx :page, :citation,
  :footnote) and \"a copy of rdm-spec's own project in which the configured
  results directory does not exist\" (kernel/command, verification/trace).
  Each is defined here only when no step file loaded before this one defines
  it, so never twice."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def site-ns 'rdm-spec.publishing.site)

(defn- needs-site! [] (s/needs-built! site-ns))

;; ---------------------------------------------------------------------------
;; Reading a project and its site

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- source-dir [dir] (fs/file dir (get-in (config-of dir) [:site :source])))
(defn- docs-dir [dir] (fs/file (source-dir dir) "docs"))
(defn- site-dir [dir] (fs/file dir (get-in (config-of dir) [:site :dir])))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- ran!
  "Fails unless the run published (exit 0)."
  [ctx]
  (let [r (the-run ctx)]
    (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
    r))

(defn- md-pages
  "Every Markdown page published: {path-under-docs text}."
  [dir]
  (let [d (docs-dir dir)]
    (into (sorted-map)
          (for [f (fs/glob d "**.md")]
            [(str/replace (str (fs/relativize d f)) "\\" "/") (slurp (str f))]))))

(defn- page-text [dir rel]
  (let [f (fs/file (docs-dir dir) rel)]
    (s/check! (fs/regular-file? f) (str "the site holds no page " rel))
    (slurp (str f))))

(defn- title-of [text] (str/trim (str/replace (or (first (str/split-lines text)) "") #"^#+" "")))

(defn- links
  "The targets of every Markdown link and image in `text`."
  [text]
  (map second (re-seq #"\]\(([^)\s]+)\)" text)))

(defn- resolve-link
  "The path under docs/ a relative link from the page `from` points at, its
  anchor dropped."
  [from link]
  (let [target (first (str/split link #"#"))]
    (when-not (or (str/blank? target) (re-find #"^[a-z]+:" target))
      (str/replace (str (fs/normalize (if-let [p (fs/parent from)] (fs/file p target) (fs/file target))))
                   "\\" "/"))))

(defn- tree
  "{rel bytes} of every file under `d`."
  [d]
  (if (fs/directory? d)
    (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                         [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))]))
    {}))

(defn- html-files [d]
  (if (fs/directory? d) (vec (fs/glob d "**.html")) []))

;; --- the spec, read independently of the parser -----------------------------

(defn feature-scenarios
  "The scenarios of a feature file's text: [{:id :level :needs :wip? :steps [text]}],
  needs inherited from the feature's and the rule's tags."
  [text]
  (loop [[l & more :as ls] (str/split-lines text) pending [] feature-tags [] rule-tags [] doc? false cur nil out []]
    (if (empty? ls)
      (cond-> out cur (conj cur))
      (let [t (str/trim l)]
        (cond
          (str/starts-with? t "\"\"\"") (recur more pending feature-tags rule-tags (not doc?) cur out)
          doc? (recur more pending feature-tags rule-tags doc? cur out)
          (or (str/blank? t) (str/starts-with? t "#")) (recur more pending feature-tags rule-tags doc? cur out)
          (str/starts-with? t "@") (recur more (into pending (str/split t #"\s+")) feature-tags rule-tags doc? cur out)
          (str/starts-with? t "Feature:") (recur more [] pending [] doc? cur out)
          (str/starts-with? t "Rule:") (recur more [] feature-tags pending doc? cur (cond-> out cur (conj cur)))
          (re-find #"^Scenario( Outline)?:" t)
          (let [tags (concat feature-tags rule-tags pending)
                sc {:id (some #(second (re-matches #"@(spec-\d{4,})" %)) pending)
                    :level (some #(second (re-matches #"@(e2e|contract)" %)) pending)
                    :needs (vec (distinct (keep #(second (re-matches #"@(UN-\d+)" %)) tags)))
                    :wip? (boolean (some #{"@wip"} tags))
                    :title (str/trim (str/replace t #"^Scenario( Outline)?:" ""))
                    :steps []}]
            (recur more [] feature-tags rule-tags doc? sc (cond-> out cur (conj cur))))
          (str/starts-with? t "Examples:") (recur more [] feature-tags rule-tags doc? cur out)
          (and cur (re-find #"^(Given|When|Then|And|But|\*)\s" t))
          (recur more pending feature-tags rule-tags doc?
                 (update cur :steps conj (str/trim (str/replace t #"^(Given|When|Then|And|But|\*)\s" ""))) out)
          :else (recur more pending feature-tags rule-tags doc? cur out))))))

(defn- all-scenarios [dir]
  (vec (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
             sc (feature-scenarios (slurp f))
             :when (:id sc)]
         sc)))

(defn- model-of [dir] (edn/read-string (slurp (str (fs/file dir (:model (config-of dir)))))))

(defn- model-component [dir component-name]
  (first (for [c (:containers (model-of dir)) ctx (:contexts c) comp (:components ctx)
               :when (= component-name (:name comp))]
           (assoc comp :context ctx))))

(defn- scenario-section
  "The part of a published page about the scenario `id`: from its heading
  to the next heading of any level. nil when no page holds one."
  [dir id]
  (first (for [[_ text] (md-pages dir)
               sec (str/split text #"(?m)^(?=#{1,6} )")
               :when (re-find (re-pattern (str "^#{1,6} .*\\b" id "\\b")) sec)
               :when (str/includes? sec "Status")]
           sec)))

(defn- shows! [dir id status]
  (let [sec (scenario-section dir id)]
    (s/check! sec (str "no page shows the scenario " id))
    (s/check! (some #(and (str/includes? % "Status") (re-find (re-pattern (str "(?i)\\b" status "\\b")) %))
                    (str/split-lines sec))
              (str id " does not show " status ":\n" sec))))

;; ---------------------------------------------------------------------------
;; Fixtures

(defn- result-json [id status]
  (json/generate-string
   {:uuid (str "fixture-" id) :name (str "the scenario " id) :status status
    :statusDetails (if (= "passed" status) {} {:message "it did not pass"})
    :stage "finished" :start 1 :stop 2 :steps []
    :labels [{:name "spec" :value id}]}))

(defn- write-results!
  "Writes one Allure result per [id status] into `d`."
  [d pairs]
  (fs/create-dirs d)
  (doseq [[id status] pairs]
    (spit (str (fs/file d (str "fixture-" id "-result.json"))) (result-json id status)))
  d)

(defn- render-views!
  "Renders the project's views afresh (the views' freshness is checked before
  the site), when the views are built."
  [dir]
  (let [vd (fs/file dir (:views-dir (config-of dir)))]
    (when (fs/exists? vd) (fs/delete-tree vd))
    (when (s/built? 'rdm-spec.architecture.views)
      (let [r (s/rdm-spec! dir "views")]
        (s/check! (= 0 (:exit r)) (str "the fixture's views were not rendered\n" (s/show r)))))
    dir))

(defn- component [ctx-id cname description]
  {:id (keyword (name ctx-id) (str/lower-case cname)) :name cname :description description
   :spec (str (name ctx-id) "/" (str/lower-case cname))})

(defn- fixture-model
  "A small model: a person, one container holding `contexts` ([id [names]]),
  `kernel` components, and one view of each level that shows them."
  [contexts kernel]
  (let [ctxs (vec (for [[id names] contexts]
                    {:id id :name (name id) :description (str "The " (name id) " context")
                     :components (mapv #(component id % (str "Does the " (str/lower-case %) " work")) names)}))
        kern (vec (for [n kernel] (assoc (component :kernel n (str "Shared " (str/lower-case n))) :id (keyword "kernel" (str/lower-case n)))))
        comps (vec (for [c ctxs comp (:components c)] comp))]
    {:system {:id :shop :name "Shop" :description "Sells things"}
     :people [{:id :buyer :name "Buyer" :description "Buys things" :actor #{:human}}]
     :external []
     :containers [{:id :app :name "App" :kind :runs :technology "a program" :description "The shop's program"
                   :contexts ctxs}]
     :kernel kern
     :relationships (vec (concat [{:from :buyer :to :app :description "Buys through"}]
                                 (for [[a b] (partition 2 1 (concat comps kern))]
                                   {:from (:id a) :to (:id b) :description "Hands work to"})))
     :views (vec (concat [{:key "context" :level :context :description "The shop and who uses it"}
                          {:key "containers" :level :container :description "The program"}]
                         (for [c ctxs]
                           {:key (str (name (:id c)) "-components") :level :component :context (:id c)
                            :description (str "Inside " (name (:id c)))})))}))

(defn- fixture-project!
  "A copy of the room whose model is `model` and whose feature files are one
  per component, each with one stamped scenario: ids from spec-0001 on,
  within the copied ledger."
  [model]
  (let [dir (s/copy-of-room!)
        cfg (config-of dir)
        spec-dir (fs/file dir (:spec-dir cfg))
        issued (:issued (edn/read-string (slurp (str (fs/file dir (:ledger cfg))))))
        comps (concat (for [c (:containers model) ctx (:contexts c) comp (:components ctx)] comp) (:kernel model))]
    (s/check! (<= (count comps) issued) "the copied ledger has issued too few ids for the fixture")
    (doseq [f (fs/glob spec-dir "**.feature")] (fs/delete f))
    (doseq [f (fs/glob spec-dir "**.md") :when (not= spec-dir (fs/parent f))] (fs/delete f))
    (s/write! dir (:model cfg) (pr-str model))
    (doseq [[i comp] (map-indexed vector comps)]
      (s/write! dir (str (:spec-dir cfg) "/" (:spec comp) ".feature")
                (str "@UN-001\nFeature: " (:name comp) "\n  " (:description comp) ".\n\n"
                     (format "  @spec-%04d @e2e\n" (inc i))
                     "  Scenario: " (:name comp) " does its work\n"
                     "    Given a request for " (str/lower-case (:name comp)) "\n"
                     "    When \"rdm-spec check\" is run\n"
                     "    Then it exits 0\n")))
    (render-views! dir)))

;; ---------------------------------------------------------------------------
;; Steps shared with other features: defined once

(defmacro ^:private defstep-once
  "Defines the step unless a step file loaded earlier already matches `text`."
  [text pattern f]
  `(when (empty? (runner/matching ~text))
     (runner/register! ~pattern ~f ~(str *file*) ~(:line (meta &form)))))

(defstep-once "a wiki page whose footnote cites \"loyly:README.md@17d68d2\""
  #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          wiki (or (:wiki-dir (config-of dir)) "dhf/wiki")
          rel "regeneration.md"]
      (s/write! dir (str wiki "/" rel)
                (str "# Regeneration\n\nThe code is built from the spec, and can be deleted and built again.[^1]\n\n"
                     "[^1]: `" citation "`, \"What to steal\" 1.\n"))
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [A page](" rel ")\n") :append true)
      (assoc ctx :dir dir :page (str wiki "/" rel) :citation citation :footnote "[^1]"))))

(defstep-once "a copy of rdm-spec's own project in which the configured results directory does not exist"
  #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          results (fs/file dir (:results-dir (config-of dir)))]
      (when (fs/exists? results) (fs/delete-tree results))
      (assoc ctx :dir dir))))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (needs-site!)
    (let [model (fixture-model [[:orders ["Cart" "Pay" "Refund"]] [:stock ["Count" "Reorder"]]] [])]
      (assoc ctx :dir (fixture-project! model) :model model))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (ran! ctx)
    (let [dir (:dir ctx)
          expected (for [c (:containers (:model ctx)) cx (:contexts c) comp (:components cx)] [cx comp])
          pages (md-pages dir)
          component-pages (remove (fn [[p _]] (or (str/ends-with? p "index.md") (str/starts-with? p "wiki/"))) pages)]
      (s/check! (= 5 (count expected)) "the fixture does not hold five components")
      (s/check! (= 5 (count component-pages))
                (str "the site holds " (count component-pages) " component pages: " (pr-str (keys component-pages))))
      (doseq [[cx comp] expected
              :let [rel (str (name (:id cx)) "/" (str/lower-case (:name comp)) ".md")
                    title (title-of (page-text dir rel))]]
        (s/check! (and (str/includes? title (:name cx)) (str/includes? title (:name comp)))
                  (str rel "'s title " (pr-str title) " does not name " (:name cx) " and " (:name comp)))))))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          comp (model-component dir "Lint")
          prose "The lint reads the model once and names every finding with the element it concerns."]
      (s/check! comp "the model has no component Lint")
      (s/write! dir (str (:spec-dir (config-of dir)) "/" (:spec comp) ".md")
                (str "# Lint\n\n" prose "\n"))
      (assoc ctx :dir dir :prose prose :component comp))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (ran! ctx)
    (let [dir (:dir ctx)
          comp (:component ctx)
          text (page-text dir (str (:spec comp) ".md"))
          scenarios (feature-scenarios (slurp (str (fs/file dir (:spec-dir (config-of dir)) (str (:spec comp) ".feature")))))]
      (s/check! (str/includes? text (:description comp)) (str "the page lacks the description " (:description comp)))
      (s/check! (str/includes? text (:prose ctx)) "the page lacks the prose")
      (s/check! (seq scenarios) "Lint owns no scenario")
      (doseq [sc scenarios
              :let [sec (scenario-section dir (:id sc))]]
        (s/check! (and sec (str/includes? text sec)) (str "the page does not hold " (:id sc)))
        (s/check! (re-find (re-pattern (str "\\b" (:level sc) "\\b")) sec) (str (:id sc) " does not show its level " (:level sc)))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? sec st) (str (:id sc) " does not show its step " (pr-str st))))))))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          v (first (filter #(and (= :component (:level %)) (= :architecture (:context %))) (:views (model-of dir))))
          svg (fs/file dir (:views-dir (config-of dir)) (str (:key v) ".svg"))]
      (s/check! v "the model has no component view of architecture")
      (s/check! (fs/regular-file? svg) (str "the view " (:key v) " is not rendered"))
      (assoc ctx :dir dir :view v :view-file (str svg)))))

(defn- shows-view!
  "Fails unless the page `rel` shows an image whose file in the site is the
  rendered view `svg`."
  [dir rel svg]
  (let [text (page-text dir rel)
        images (map second (re-seq #"!\[[^\]]*\]\(([^)\s]+)\)" text))
        same? (fn [img] (let [f (fs/file (docs-dir dir) (or (resolve-link rel img) ""))]
                          (and (fs/regular-file? f) (= (vec (fs/read-all-bytes f)) (vec (fs/read-all-bytes svg))))))]
    (s/check! (some same? images) (str rel " does not show the view " svg))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (ran! ctx)
    (let [dir (:dir ctx)
          rel "architecture/index.md"
          text (page-text dir rel)
          targets (keep #(resolve-link rel %) (links text))
          comps (:components (first (for [c (:containers (model-of dir)) cx (:contexts c) :when (= :architecture (:id cx))] cx)))]
      (shows-view! dir rel (:view-file ctx))
      (doseq [comp comps]
        (s/check! (some #(and (fs/regular-file? (fs/file (docs-dir dir) %))
                              (str/includes? (title-of (page-text dir %)) (:name comp)))
                        targets)
                  (str rel " does not link to the page of " (:name comp)))))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (ran! ctx)
    (let [dir (:dir ctx)
          cfg (config-of dir)
          model (model-of dir)
          view-file (fn [level] (fs/file dir (:views-dir cfg) (str (:key (first (filter #(= level (:level %)) (:views model)))) ".svg")))
          text (page-text dir "index.md")
          needs (edn/read-string (slurp (str (fs/file dir (:needs cfg)))))
          scenarios (all-scenarios dir)
          sections (str/split text #"(?m)^(?=#{1,6} )")]
      (shows-view! dir "index.md" (view-file :context))
      (shows-view! dir "index.md" (view-file :container))
      (doseq [[id {need-text :text}] needs
              :let [sec (first (filter #(re-find (re-pattern (str "^#{1,6} .*\\b" id "\\b")) %) sections))]]
        (s/check! sec (str "the home page has no section for the need " id))
        (s/check! (str/includes? sec need-text) (str "the home page does not give " id "'s text"))
        (doseq [sc scenarios :when (some #{id} (:needs sc))]
          (s/check! (re-find (re-pattern (str "\\b" (:id sc) "\\b")) sec)
                    (str "the home page does not list " (:id sc) " under " id)))))))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which (spec-\d+) passed and (spec-\d+) failed"
  (fn [ctx passed failed]
    (needs-site!)
    (let [d (str (fs/file (s/tmp-dir) "results"))]
      (write-results! d [[passed "passed"] [failed "failed"]])
      (assoc ctx :results-dir d))))

(defstep #"\"rdm-spec (publish[^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results-dir ctx) "no results directory was given to stand for DIR")
    (kc/run-line ctx (str/replace line #"\bDIR\b" (:results-dir ctx)))))

(defstep #"(spec-\d+) shows (\w+) and (spec-\d+) shows (\w+)"
  (fn [ctx a sa b sb]
    (ran! ctx)
    (shows! (:dir ctx) a sa)
    (shows! (:dir ctx) b sb)))

(defstep #"(spec-\d+) shows (\w+)"
  (fn [ctx a sa]
    (ran! ctx)
    (shows! (:dir ctx) a sa)))

(defstep #"the scenario @(spec-\d+) tagged @wip"
  (fn [ctx id]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          f (first (for [f (fs/glob (fs/file dir "dhf/spec") "**.feature")
                         :when (re-find (re-pattern (str "(?m)^\\s*(@\\S+\\s+)*@" id "\\b")) (slurp (str f)))]
                     (str f)))]
      (s/check! f (str "no feature file holds @" id))
      (spit f (str/replace (slurp f) (re-pattern (str "(?m)^(\\s*(@\\S+\\s+)*@" id "\\b.*)$")) "$1 @wip"))
      (assoc ctx :dir dir))))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (ran! ctx)
    (let [dir (:dir ctx)
          pages (md-pages dir)
          wiki (fs/file dir (:wiki-dir (config-of dir)))
          wiki-pages (map #(str "wiki/" (str/replace (str (fs/relativize wiki %)) "\\" "/")) (fs/glob wiki "**.md"))]
      (s/check! (seq pages) "the site holds no page")
      (doseq [w wiki-pages]
        (s/check! (contains? pages w) (str "the wiki page " w " is not published")))
      (doseq [[p text] pages]
        (s/check! (str/includes? (str/lower-case text) "no status was derived because no results were given")
                  (str p " does not say no status was derived"))))))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (the-run ctx)
          k (:stale-view ctx)]
      (s/check! k "no view was made stale")
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (some #(and (str/includes? % "stale") (str/includes? % k)) (s/err-lines r))
                (str "no finding names the view " k " as stale\n" (s/show r))))))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "publish did not run\n" (s/show r)))
      (s/check! (= (tree (fs/file s/room-root "dhf/spec")) (tree (fs/file (:dir ctx) "dhf/spec")))
                "a file under dhf/spec changed"))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          ids (map :id (take 3 (all-scenarios dir)))]
      (write-results! (fs/file dir (:results-dir (config-of dir))) (map vector ids ["passed" "failed" "passed"]))
      (assoc ctx :dir dir))))

(defn- site-input-copy!
  "A fresh project holding `dir`'s files but its site's."
  [dir]
  (let [d (s/tmp-dir)
        skip #{(str (source-dir dir)) (str (site-dir dir))}]
    (doseq [f (fs/glob dir "**")
            :when (fs/regular-file? f)
            :when (not-any? #(str/starts-with? (str f) (str % "/")) skip)]
      (let [t (fs/file d (fs/relativize dir f))]
        (fs/create-dirs (fs/parent t))
        (fs/copy f t)))
    d))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (let [[r1 r2] (:runs ctx)
          dir (:dir ctx)]
      (s/check! (and r1 r2) "publish was not run twice")
      (doseq [r [r1 r2]] (s/check! (= 0 (:exit r)) (str "publish did not pass\n" (s/show r))))
      (let [published (tree (source-dir dir))
            ;; the second run overwrote the first site, so the same inputs are
            ;; published once more in a fresh project and compared
            again (site-input-copy! dir)
            r3 (kc/run! ctx again ["publish"])]
        (s/check! (= 0 (:exit r3)) (str "publish did not pass in a copy\n" (s/show r3)))
        (s/check! (contains? published "mkdocs.yml") "no site builder configuration was written")
        (s/check! (= published (tree (source-dir again)))
                  (str "the sites differ in "
                       (pr-str (remove #(= (get published %) (get (tree (source-dir again)) %))
                                       (distinct (concat (keys published) (keys (tree (source-dir again)))))))))))))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (ran! ctx)
    (let [dir (:dir ctx)
          wiki (str/replace (:wiki-dir (config-of dir)) #"/+$" "")
          rel (str "wiki/" (subs (:page ctx) (inc (count wiki))))
          text (page-text dir rel)
          footnote (first (filter #(str/starts-with? (str/trim %) (str (:footnote ctx) ":")) (str/split-lines text)))
          urls (map second (re-seq #"\]\((\S+?)\)" (or footnote "")))]
      (s/check! footnote (str rel " has no footnote " (:footnote ctx)))
      (s/check! (some #(and (str/includes? % (str "/" repo "/")) (str/includes? % (str "/" commit "/"))
                            (str/ends-with? % (str "/" path)))
                      urls)
                (str "the footnote does not link to " path " at " commit " in " repo ": " footnote)))))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (needs-site!)
    (let [model (fixture-model [[:spec ["Parser" "Stamper"]] [:architecture ["Model" "Lint" "Views"]]] ["Bus"])]
      (assoc ctx :dir (fixture-project! model) :model model))))

(defn- nav-of [dir]
  (let [cfg (yaml/parse-string (slurp (str (fs/file (source-dir dir) "mkdocs.yml"))))]
    (vec (for [entry (:nav cfg)]
           (let [[k v] (first entry)] [(name k) v])))))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (ran! ctx)
    (let [nav (nav-of (:dir ctx))]
      (s/check! (= "index.md" (second (first nav))) (str "the first entry is not the home page: " (pr-str (first nav))))
      (s/check! (= ["spec" "architecture" "kernel"] (map first (rest nav)))
                (str "the sections are " (pr-str (map first (rest nav))))))))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [dir (:dir ctx)
          nav (into {} (rest (nav-of dir)))
          model (:model ctx)
          sections (concat (for [c (:containers model) cx (:contexts c)] [(:name cx) (:components cx)])
                           [["kernel" (:kernel model)]])]
      (doseq [[sname comps] sections
              :let [entries (mapv #(let [[k v] (first %)] [(name k) v]) (get nav sname))]]
        (s/check! (seq entries) (str "no section " sname))
        (s/check! (= sname (title-of (page-text dir (second (first entries)))))
                  (str "the section " sname " does not start with its context's page: " (pr-str (first entries))))
        (s/check! (= (map :name comps) (map first (rest entries)))
                  (str "the section " sname " lists " (pr-str (map first (rest entries)))))
        (doseq [[cname page] (rest entries)]
          (s/check! (str/includes? (title-of (page-text dir page)) cname) (str page " is not " cname "'s page")))))))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx]
    (needs-site!)
    (assoc ctx :dir (s/copy-of-room!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (ran! ctx)
    (let [dir (:dir ctx)
          builder (get-in (config-of dir) [:site :builder])
          site (site-dir dir)]
      (s/check! (some #{"--strict"} builder) (str "the configured builder does not fail on a broken link: " (pr-str builder)))
      (doseq [[p _] (md-pages dir)
              :let [base (str/replace p #"\.md$" "")
                    candidates [(str base ".html") (str base "/index.html")]]]
        (s/check! (some #(fs/regular-file? (fs/file site %)) candidates) (str "no HTML was written for " p))))))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          comp (model-component dir "Lint")
          link "missing-page.md"]
      (s/write! dir (str (:spec-dir (config-of dir)) "/" (:spec comp) ".md")
                (str "# Lint\n\nSee [the missing page](" link ").\n"))
      (assoc ctx :dir dir :broken-link link :broken-page (str (:spec comp) ".md")))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (:broken-page ctx)) (str/includes? % (:broken-link ctx))) (s/err-lines r))
                (str "no finding names " (:broken-page ctx) " and " (:broken-link ctx) "\n" (s/show r))))))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (let [html (html-files (site-dir (:dir ctx)))]
      (s/check! (empty? html) (str (count html) " HTML files are left in the site directory")))))

(def ^:private missing-builder "rdm-spec-test-no-such-site-builder")

(defstep #"the configured site builder is not installed"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          cfg (config-of dir)]
      (s/check! (nil? (fs/which missing-builder)) (str missing-builder " is installed"))
      (s/write! dir "dhf/harness/config.edn"
                (pr-str (assoc-in cfg [:site :builder] (into [missing-builder] (rest (get-in cfg [:site :builder]))))))
      (assoc ctx :dir dir :builder missing-builder))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r))]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (:builder ctx)) (re-find #"(?i)no HTML was written" %)) lines)
                (str "no cannot-run line names " (:builder ctx) " and says no HTML was written\n" (s/show r)))
      (s/check! (empty? (html-files (site-dir (:dir ctx)))) "HTML was written"))))
