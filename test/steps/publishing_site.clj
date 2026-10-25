(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature: `rdm-spec publish`
  run in a copy of the room's allow-listed paths (or a fixture project made
  from one), and the published Markdown, the site builder's configuration
  and the HTML read back.

  Shared steps used here and defined elsewhere: the kernel's \"rdm-spec ...\"
  runs and \"a copy of rdm-spec's own project in which the configured results
  directory does not exist\" (steps.kernel-command), and architecture's \"a
  rendered view whose recorded hash differs from the model's\" (it sets :dir
  and :stale-view).

  Shared step defined here, with one meaning wherever it is used:
  \"a wiki page whose footnote cites C\": a fresh copy of the room whose wiki
  holds the page dhf/wiki/pages/regeneration.md, listed in the index, with
  one footnote [^1] citing C (ctx :page, :citation, :footnote). The knowledge
  context's scenarios read :page.

  What is read from the published site follows its layout, decided by the
  Site component: under the configured site source, mkdocs.yml and docs/,
  with docs/index.md the home page, docs/<context>/index.md each bounded
  context's page, docs/<context>/<component>.md each component's page (the
  component's spec path), docs/views/ the views and docs/wiki/ the wiki.

  Context keys: :dir the project, :run the last run, :runs both runs and
  :aside the first site's copy (run twice), :results a results directory,
  :page, :citation, :footnote, :model the fixture model."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers

(def site-ns 'rdm-spec.publishing.site)

(defn- needs-site! [] (s/needs-built! site-ns))

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- site-config [dir] (:site (config-of dir)))

(defn- source-dir [dir] (fs/file dir (:source (site-config dir))))

(defn- docs-dir [dir] (fs/file (source-dir dir) "docs"))

(defn- html-dir [dir] (fs/file dir (:dir (site-config dir))))

(defn- published!
  "Fails unless the scenario's last publish exited 0; returns the run."
  [ctx]
  (needs-site!)
  (kc/exits! ctx 0))

(defn- page
  "The published page `rel` (under docs/) of the scenario's project."
  [ctx rel]
  (let [f (fs/file (docs-dir (:dir ctx)) rel)]
    (s/check! (fs/regular-file? f) (str "the site holds no page " rel "\n" (s/show (:run ctx))))
    (slurp (str f))))

(defn- pages
  "Every published Markdown page: {rel text}."
  [dir]
  (let [d (docs-dir dir)]
    (into (sorted-map) (for [f (fs/glob d "**.md")] [(str (fs/relativize d f)) (slurp (str f))]))))

(defn- links
  "Every Markdown link target of `text` (images included), without anchors."
  [text]
  (vec (for [[_ target] (re-seq #"\]\(([^)\s]+)\)" text)] (first (str/split target #"#")))))

(defn- links-to? [text target] (some #{target} (links text)))

(defn- nav
  "The navigation of the site builder's configuration, as data."
  [dir]
  (let [f (fs/file (source-dir dir) "mkdocs.yml")]
    (s/check! (fs/regular-file? f) (str "no site builder configuration " f))
    (get (yaml/parse-string (slurp (str f)) :keywords false) "nav")))

(defn- entry
  "A nav entry as [title target], target a path or a vector of entries."
  [e]
  (cond (string? e) [nil e]
        (map? e) (let [[k v] (first e)] [k (if (string? v) v (vec v))])
        :else (s/fail! (str "not a navigation entry: " (pr-str e)))))

(defn- section-paths
  "The page paths a nav section lists, in order."
  [items]
  (vec (for [i items :let [[_ t] (entry i)] :when (string? t)] t)))

(defn- tree
  "{rel bytes} of every file under `d`."
  [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

(defn- page-of-feature
  "The page of the component owning the feature file `rel` (dhf/spec/...)."
  [rel]
  (str/replace (subs rel (count "dhf/spec/")) #"\.feature$" ".md"))

(defn- scenario-file
  "The feature file (relative to `dir`) holding the scenario `id`."
  [dir id]
  (or (some (fn [f] (when (re-find (re-pattern (str "@" id "\\b")) (slurp (str f)))
                      (str (fs/relativize dir f))))
            (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature"))))
      (s/fail! (str "no feature file holds " id))))

(defn- scenario-block
  "The part of `text` about the scenario `id`: from the heading anchored at
  it to the next heading."
  [text id]
  (let [lines (str/split-lines text)
        start (first (keep-indexed (fn [i l] (when (and (str/starts-with? l "#")
                                                        (str/includes? l (str "{#" id "}"))) i))
                                   lines))]
    (s/check! start (str "no heading for " id))
    (str/join "\n" (cons (nth lines start)
                         (take-while #(not (str/starts-with? % "#")) (drop (inc start) lines))))))

(defn- shows-status!
  [ctx id status]
  (published! ctx)
  (let [rel (page-of-feature (scenario-file (:dir ctx) id))
        block (scenario-block (page ctx rel) id)]
    (s/check! (re-find (re-pattern (str "(?m)^- Status: " status "$")) block)
              (str id " does not show " status " on " rel ":\n" block))))

(defn- write-result!
  [dir id status]
  (s/write! dir (str (random-uuid) "-result.json")
            (str "{\"name\":\"" id "\",\"status\":\"" status "\",\"steps\":[],"
                 "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}")))

;; ---------------------------------------------------------------------------
;; Fixture projects: a copy of the room with a model of the scenario's own

(defn- model-file [dir] (fs/file dir "dhf/spec/model.edn"))

(defn- read-model [dir] (edn/read-string (slurp (str (model-file dir)))))

(defn- component-ids [contexts kernel]
  (set (concat (for [c contexts comp (:components c)] (:id comp)) (map :id kernel))))

(defn- feature-text
  [component id]
  (str "@UN-010\nFeature: " (:name component) "\n  " (:description component) "\n\n"
       "  @" id " @e2e\n  Scenario: " (:name component) " does its work\n"
       "    Given a project\n    When \"rdm-spec check\" is run\n    Then it exits 0\n"))

(defn- fixture!
  "A copy of the room whose model holds `contexts` (in that order) and
  `kernel`, and nothing else of the room's contexts: the other contexts'
  feature files are removed, a component without one gets one scenario
  (ids from `ids`, within the ledger), and the views are generated."
  [ctx contexts kernel ids]
  (needs-site!)
  (s/needs-built! 'rdm-spec.architecture.views)
  (let [dir (s/copy-of-room!)
        m (read-model dir)
        kept (component-ids contexts kernel)
        container-ids (set (map :id (:containers m)))
        element? (into (set (concat (map :id (:people m)) (map :id (:external m)) container-ids
                                    [(get-in m [:system :id])]))
                       kept)
        m (-> m
              (update :containers (fn [cs] (mapv #(if (contains? % :contexts) (assoc % :contexts contexts) %) cs)))
              (assoc :kernel (vec kernel))
              (update :relationships (fn [rs] (vec (filter #(and (element? (:from %)) (element? (:to %))) rs))))
              (assoc :views (vec (concat (filter #(#{:context :container} (:level %)) (:views m))
                                         (for [c contexts]
                                           {:key (str (:name c) "-components") :level :component
                                            :context (:id c) :description (str "The " (:name c) " context")})))))
        specs (set (keep :spec (concat (for [c contexts comp (:components c)] comp) kernel)))]
    (spit (str (model-file dir)) (with-out-str (binding [*print-namespace-maps* false] (prn m))))
    ;; only the kept components' feature files and prose stay
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "*/*.{feature,md}")
            :let [rel (str (fs/relativize (fs/file dir "dhf/spec") f))
                  spec (str/replace rel #"\.(feature|md)$" "")]
            :when (not (specs spec))]
      (fs/delete f))
    (doseq [[comp id] (map vector (concat (for [c contexts comp (:components c)] comp) kernel) ids)
            :let [rel (str "dhf/spec/" (:spec comp) ".feature")]
            :when (not (fs/exists? (fs/file dir rel)))]
      (s/write! dir rel (feature-text comp id)))
    (doseq [f (fs/glob (fs/file dir "dhf/views") "*")] (fs/delete f))
    (let [r (s/rdm-spec! dir "views")]
      (s/check! (zero? (:exit r)) (str "the fixture's views were not generated\n" (s/show r))))
    (assoc ctx :dir dir :model m)))

(defn- room-context [id]
  (let [m (read-model s/room-root)]
    (or (some #(when (= id (:id %)) %) (mapcat :contexts (:containers m)))
        (s/fail! (str "the room's model has no bounded context " id)))))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (let [comp (fn [ctx-id n d] {:id (keyword (name ctx-id) (str/lower-case n)) :name n
                                 :spec (str (name ctx-id) "/" (str/lower-case n)) :description d})]
      (fixture! ctx
                [{:id :orders :name "orders" :description "Takes orders"
                  :components [(comp :orders "Cart" "Holds the cart")
                               (comp :orders "Pay" "Takes payment")
                               (comp :orders "Refund" "Gives the money back")]}
                 {:id :stock :name "stock" :description "Counts the stock"
                  :components [(comp :stock "Count" "Counts items")
                               (comp :stock "Reorder" "Orders more")]}]
                []
                ["spec-0001" "spec-0002" "spec-0003" "spec-0004" "spec-0005"]))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (published! ctx)
    (let [expected (set (for [c (mapcat :contexts (:containers (:model ctx)))
                              comp (:components c)]
                          (str (:name c) "/" (name (:id comp)) ".md")))
          component-pages (set (for [rel (keys (pages (:dir ctx)))
                                     :when (re-matches #"[^/]+/[^/]+\.md" rel)
                                     :when (not (str/ends-with? rel "/index.md"))
                                     :when (not (str/starts-with? rel "wiki/"))]
                                 rel))]
      (s/check! (= 5 (count expected)) "the fixture does not hold five components")
      (s/check! (= expected component-pages)
                (str "the component pages are " (pr-str (sort component-pages)) ", not " (pr-str (sort expected)))))))

(def prose "Lint holds the model to the checklist; every finding names the element it is about.")

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)]
      (s/write! dir "dhf/spec/architecture/lint.md" (str "# Lint, by design\n\n" prose "\n"))
      (assoc ctx :dir dir))))

(defstep #"Lint's page holds its description from the model, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "architecture/lint.md")
          comp (some #(when (= :architecture/lint (:id %)) %) (:components (room-context :architecture)))
          parsed (gherkin/parse-feature "lint.feature"
                                        (slurp (str (fs/file (:dir ctx) "dhf/spec/architecture/lint.feature"))))]
      (s/check! (str/includes? text (:description comp)) (str "the page lacks the description " (:description comp)))
      (s/check! (str/includes? text prose) "the page lacks the prose")
      (s/check! (seq (:scenarios parsed)) "lint.feature holds no scenario")
      (doseq [sc (:scenarios parsed)
              :let [id (subs (first (filter #(re-matches #"@spec-\d+" %) (:tags sc))) 1)
                    level (subs (first (filter #{"@e2e" "@contract"} (:tags sc))) 1)
                    block (scenario-block text id)]]
        (s/check! (str/includes? block (:name sc)) (str "the page lacks the scenario " id " " (:name sc)))
        (s/check! (re-find (re-pattern (str "(?m)^- Level: " level "$")) block) (str id " shows no level " level))
        (doseq [st (:steps sc)]
          (s/check! (str/includes? block (str (:keyword st) " " (:text st)))
                    (str id " lacks the step " (:keyword st) " " (:text st))))))))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)]
      (s/check! (fs/exists? (fs/file dir "dhf/views/architecture-components.svg"))
                "the room has no rendered view architecture-components")
      (assoc ctx :dir dir))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "architecture/index.md")]
      (s/check! (links-to? text "../views/architecture-components.svg") "the page does not show the view")
      (s/check! (fs/exists? (fs/file (docs-dir (:dir ctx)) "views/architecture-components.svg"))
                "the view is not published")
      (doseq [comp (:components (room-context :architecture))
              :let [rel (str (name (:id comp)) ".md")]]
        (s/check! (links-to? text rel) (str "the page does not link " rel))
        (page ctx (str "architecture/" rel))))))

(defn- needs-tracing
  "{need [scenario-id]} of the spec in `dir`, read independently of rdm-spec."
  [dir]
  (reduce (fn [acc f]
            (let [p (gherkin/parse-feature (str f) (slurp (str f)))]
              (reduce (fn [acc sc]
                        (let [id (some #(when (re-matches #"@spec-\d+" %) (subs % 1)) (:tags sc))]
                          (if id
                            (reduce #(update %1 %2 (fnil conj []) id) acc
                                    (distinct (for [t (:all-tags sc) :when (re-matches #"@UN-\d+" t)] (subs t 1))))
                            acc)))
                      acc (:scenarios p))))
          {} (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (published! ctx)
    (let [text (page ctx "index.md")
          needs (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/needs.edn"))))
          traced (needs-tracing (:dir ctx))]
      (doseq [v ["views/context.svg" "views/containers.svg"]]
        (s/check! (links-to? text v) (str "the home page does not show " v))
        (s/check! (fs/exists? (fs/file (docs-dir (:dir ctx)) v)) (str v " is not published")))
      (s/check! (seq needs) "the room declares no need")
      (doseq [[need {:keys [text]}] needs
              :let [block (scenario-block (page ctx "index.md") need)]]
        (s/check! (str/includes? block (str/trim (first (str/split-lines text))))
                  (str "the home page lacks the text of " need))
        (doseq [id (get traced need)]
          (s/check! (re-find (re-pattern (str "\\[" id "\\]\\([^)]+#" id "\\)")) block)
                    (str "the home page does not link " id " under " need)))))))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (needs-site!)
    (let [results (s/tmp-dir)]
      (write-result! results "spec-0012" "passed")
      (write-result! results "spec-0013" "failed")
      (assoc ctx :dir (s/copy-of-room!) :results results))))

;; The kernel's "... is run with that directory as DIR" reads DIR as the
;; --sources directory; this wording names the results directory.
(defstep #"\"rdm-spec (publish[^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results ctx) "no results directory was given")
    (kc/run-line ctx (str/replace line #"\bDIR\b" (str (:results ctx))))))

(defstep #"spec-0012 shows verified and spec-0013 shows failed"
  (fn [ctx]
    (shows-status! ctx "spec-0012" "verified")
    (shows-status! ctx "spec-0013" "failed")))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          rel (scenario-file dir "spec-0014")
          f (fs/file dir rel)
          text (slurp (str f))
          changed (str/replace-first text #"@spec-0014\b" "@spec-0014 @wip")]
      (s/check! (not= text changed) "spec-0014 was not tagged")
      (spit (str f) changed)
      (assoc ctx :dir dir))))

(defstep #"spec-0014 shows planned"
  (fn [ctx] (shows-status! ctx "spec-0014" "planned")))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (published! ctx)
    (let [ps (pages (:dir ctx))]
      (s/check! (some #(str/starts-with? % "wiki/") (keys ps)) "the wiki is not published")
      (doseq [[rel text] ps]
        (s/check! (re-find #"(?i)no status was derived because no results were given" text)
                  (str rel " does not say no status was derived"))))))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          k (:stale-view ctx)]
      (s/check! (some #(and (str/includes? % (str "\"" k "\"")) (str/includes? % "stale")) (s/err-lines r))
                (str "no finding names the view " k " as stale\n" (s/show r))))))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (published! ctx)
    (s/check! (= (tree (fs/file s/room-root "dhf/spec")) (tree (fs/file (:dir ctx) "dhf/spec")))
              "a file under dhf/spec changed")))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (needs-site!)
    (let [results (s/tmp-dir)]
      (write-result! results "spec-0012" "passed")
      (write-result! results "spec-0013" "failed")
      ;; outside the project, given the way a test run gives it
      (-> ctx
          (assoc :dir (s/copy-of-room!) :results results)
          (assoc-in [:env "RESULTS_DIR"] results)))))

(defstep #"\"rdm-spec ([^\"]*)\" is run twice, the first site kept aside"
  (fn [ctx line]
    (let [c1 (kc/run-line ctx line)
          aside (s/tmp-dir)
          dir (:dir c1)]
      (doseq [k [:source :dir]
              :let [d (fs/file dir (get (site-config dir) k))]
              :when (fs/exists? d)]
        (fs/move d (fs/file aside (name k))))
      (let [c2 (kc/run-line c1 line)]
        (assoc c2 :runs [(:run c1) (:run c2)] :aside aside)))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (needs-site!)
    (doseq [r (:runs ctx)] (s/check! (zero? (:exit r)) (str "a run did not pass\n" (s/show r))))
    (let [keep? (fn [[rel _]] (or (str/ends-with? rel ".md") (= rel "mkdocs.yml")))
          first-site (into (sorted-map) (filter keep? (tree (fs/file (:aside ctx) "source"))))
          second-site (into (sorted-map) (filter keep? (tree (source-dir (:dir ctx)))))]
      (s/check! (contains? first-site "mkdocs.yml") "the first site has no site builder configuration")
      (s/check! (= (keys first-site) (keys second-site)) "the two sites hold different files")
      (doseq [[rel bytes] first-site]
        (s/check! (= bytes (get second-site rel)) (str rel " differs between the two sites"))))))

(defstep #"no page names a path outside the project"
  (fn [ctx]
    (let [dir (str (fs/canonicalize (:dir ctx)))
          outside (remove nil? [dir (some-> (:results ctx) fs/canonicalize str) (:results ctx)
                                (str (fs/canonicalize (System/getProperty "java.io.tmpdir")))
                                (System/getProperty "user.home") s/room-root])
          files (merge (pages (:dir ctx))
                       {"mkdocs.yml" (slurp (str (fs/file (source-dir (:dir ctx)) "mkdocs.yml")))})]
      (doseq [[rel text] files
              p outside]
        (s/check! (not (str/includes? text p)) (str rel " names the path " p))))))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(def ^:private footnoted-paragraph
  "The code is built from the spec, and can be deleted and built again.[^1]")

(defn- wiki-page!
  "Writes the wiki page dhf/wiki/pages/<name>.md holding `body`, listed in the
  index. Returns its path."
  [dir name title body]
  (let [rel (str "pages/" name ".md")]
    (s/write! dir (str "dhf/wiki/" rel) body)
    (spit (str (fs/file dir "dhf/wiki/index.md")) (str "- [" title "](" rel ")\n") :append true)
    (str "dhf/wiki/" rel)))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (s/copy-of-room!)
          page (wiki-page! dir "regeneration" "Regeneration"
                           (str "# Regeneration\n\n" footnoted-paragraph "\n\n[^1]: `" citation
                                "`, \"What to steal\" 1.\n"))]
      (assoc ctx :dir dir :page page :page-text (slurp (str (fs/file dir page)))
             :citation citation :footnote "[^1]"))))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (published! ctx)
    (let [rel (str "wiki/" (subs (:page ctx) (count "dhf/wiki/")))
          text (page ctx rel)
          footnote (or (first (filter #(str/starts-with? % (str (:footnote ctx) ":")) (str/split-lines text)))
                       (s/fail! (str rel " has no footnote " (:footnote ctx))))
          targets (links footnote)]
      (s/check! (some #(and (str/includes? % repo) (str/includes? % commit) (str/ends-with? % (str "/" path)))
                      targets)
                (str "the footnote links to " (pr-str targets) ", not to " path " at " commit " in " repo))
      (s/check! (fs/exists? (fs/file (html-dir (:dir ctx)) (str/replace rel #"\.md$" "") "index.html"))
                (str "the page " rel " was not built")))))

(defstep #"a wiki whose index lists two pages"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)]
      ;; the fixture's own wiki: an index listing exactly the two pages made
      ;; here, whatever the room's wiki holds
      (fs/delete-tree (fs/file dir "dhf/wiki/pages"))
      (s/write! dir "dhf/wiki/index.md" "# Wiki\n\n## Pages\n\n")
      (doseq [[name title] [["regeneration" "Regeneration"] ["cycles" "Cycles"]]]
        (wiki-page! dir name title
                    (str "# " title "\n\n" footnoted-paragraph "\n\n[^1]: `loyly:README.md@17d68d2`, \"What to steal\" 1.\n")))
      (assoc ctx :dir dir
             :wiki-pages (vec (for [[_ p] (re-seq #"\]\((pages/[^)]+\.md)\)" (slurp (str (fs/file dir "dhf/wiki/index.md"))))]
                                p))))))

(defn- section-titles [n] (vec (for [e n :let [[t v] (entry e)] :when (vector? v)] t)))

(defstep #"the home page links to the wiki's index, the index links to both pages, and the navigation holds a wiki section after the kernel"
  (fn [ctx]
    (published! ctx)
    (s/check! (links-to? (page ctx "index.md") "wiki/index.md") "the home page does not link the wiki's index")
    (let [index (page ctx "wiki/index.md")]
      (s/check! (= 2 (count (:wiki-pages ctx))) "the fixture's index does not list two pages")
      (doseq [p (:wiki-pages ctx)]
        (s/check! (links-to? index p) (str "the wiki's index does not link " p))
        (page ctx (str "wiki/" p))))
    (let [n (nav (:dir ctx))
          titles (section-titles n)
          wiki (some (fn [e] (let [[t v] (entry e)] (when (= "wiki" t) v))) n)]
      (s/check! (= ["kernel" "wiki"] (take-last 2 titles))
                (str "the navigation's sections end " (pr-str (take-last 2 titles)) ", not kernel then wiki"))
      (s/check! (= "wiki/index.md" (first (section-paths wiki))) "the wiki section does not start with its index")
      (doseq [p (:wiki-pages ctx)]
        (s/check! (some #{(str "wiki/" p)} (section-paths wiki)) (str "the wiki section does not list " p))))))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (let [kernel (some #(when (= :kernel/bus (:id %)) %) (:kernel (read-model s/room-root)))]
      (fixture! ctx [(room-context :spec) (room-context :architecture)] [kernel] []))))

(defn- top-sections [dir]
  (let [n (nav dir)]
    (s/check! (seq n) "the site builder's configuration has no navigation")
    n))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (published! ctx)
    (let [n (top-sections (:dir ctx))
          [_ home] (entry (first n))]
      (s/check! (= "index.md" home) (str "the navigation does not start with the home page: " (pr-str (first n))))
      (s/check! (= ["spec" "architecture" "kernel"] (vec (take 3 (section-titles n))))
                (str "the sections are " (pr-str (section-titles n)))))))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (top-sections (:dir ctx))
          m (:model ctx)
          expected (concat (for [c (mapcat :contexts (:containers m))]
                             [(:name c) (cons (str (:name c) "/index.md")
                                              (for [comp (:components c)] (str (:spec comp) ".md")))])
                           [["kernel" (cons "kernel/index.md" (for [comp (:kernel m)] (str (:spec comp) ".md")))]])]
      (doseq [[title paths] expected
              :let [items (some (fn [e] (let [[t v] (entry e)] (when (= title t) v))) n)]]
        (s/check! (= (vec paths) (section-paths items))
                  (str "the section " title " lists " (pr-str (section-paths items)) ", not " (pr-str paths)))))))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx]
    (needs-site!)
    (assoc ctx :dir (s/copy-of-room!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (published! ctx)
    (let [cfg (yaml/parse-string (slurp (str (fs/file (source-dir (:dir ctx)) "mkdocs.yml"))) :keywords false)
          html (html-dir (:dir ctx))]
      (s/check! (true? (get cfg "strict")) "the configuration does not fail on a broken link (strict)")
      (s/check! (= "warn" (get-in cfg ["validation" "links" "not_found"])) "a broken link is not reported")
      (s/check! (fs/exists? (fs/file html "index.html")) "no HTML home page was written")
      (doseq [rel (keys (pages (:dir ctx)))
              :let [out (if (str/ends-with? rel "index.md")
                          (str/replace rel #"index\.md$" "index.html")
                          (str (str/replace rel #"\.md$" "") "/index.html"))]]
        (s/check! (fs/exists? (fs/file html out)) (str "no HTML for " rel))))))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)]
      (s/write! dir "dhf/spec/architecture/lint.md"
                "# Lint, by design\n\nSee [the checklist](checklist-of-c4.md) for every rule.\n")
      ;; HTML of an earlier build, which a failed build must not leave behind
      (s/write! dir (str (:dir (site-config dir)) "/index.html") "<html>an earlier build</html>")
      (assoc ctx :dir dir :broken-page "architecture/lint.md" :broken-link "checklist-of-c4.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (needs-site!)
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % (:broken-page ctx)) (str/includes? % (:broken-link ctx)))
                      (s/err-lines r))
                (str "no finding names " (:broken-page ctx) " and " (:broken-link ctx) "\n" (s/show r))))))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (let [html (html-dir (:dir ctx))]
      (s/check! (or (not (fs/exists? html)) (empty? (fs/glob html "**.html")))
                (str "HTML is left in " html)))))

(defstep #"the configured site builder is not installed"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          f (fs/file dir "dhf/harness/config.edn")
          tool "rdm-spec-absent-site-builder"]
      (s/check! (nil? (fs/which tool)) (str tool " is installed"))
      (spit (str f) (pr-str (update-in (config-of dir) [:site :builder] #(vec (cons tool (rest %))))))
      (assoc ctx :dir dir :builder tool))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (needs-site!)
    (let [r (kc/exits! ctx 2)]
      (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
      (s/check! (some #(and (str/starts-with? % "cannot-run: ") (str/includes? % (:builder ctx))
                            (re-find #"(?i)no HTML was written" %))
                      (s/err-lines r))
                (str "the refusal does not name " (:builder ctx) " and say no HTML was written\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file (html-dir (:dir ctx)) "index.html"))) "HTML was written"))))
