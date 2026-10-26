(ns rdm-spec.publishing.site
  "Site (dhf/spec/publishing/site.feature): `rdm-spec publish [--results DIR]`
  writes the spec, its views and its status as Markdown and the site
  builder's configuration under the configured :site :source, and has the
  configured :site :builder build it into :site :dir. rdm-spec writes the
  builder's input, never its code.

  It reads what the run's earlier stages published: the scenarios and needs
  (SpecParsed), the statuses (VerificationDerived, when results were given)
  and the sources (KnowledgeIndexed); and from the repository the model, the
  components' design prose (<spec-dir>/<spec>.md), the rendered views and
  the wiki. The views' freshness is publish's gate, before this stage, so
  the site never computes the model hash itself.

  The site, under <source>:
    mkdocs.yml                  the builder's configuration: the home page,
                                then a section per bounded context in the
                                model's order (its page, then its components'),
                                then the kernel's, then the wiki's
    docs/index.md               the context and container views, the bounded
                                contexts, every need with the scenarios
                                tracing to it, a link to the wiki
    docs/<context>/index.md     the context's component view and its components
    docs/<context>/<comp>.md    the component (<comp> is its :spec after the
                                context): description, prose, every scenario
                                with its id, level, steps and status
    docs/kernel/...             the kernel's components, as a context's
    docs/wiki/...               every wiki page, its footnotes' citations
                                linked to <repository>/blob/<commit>/<path>
    docs/views/<key>.svg        the rendered views
  Every page says how its status was derived, or that no status was derived
  because no results were given. Pages name paths relative to the project
  only, and the same inputs give the same bytes.

  Before building, the site directory is removed, so a failed build leaves
  no HTML. A builder that is not installed cannot run (exit 2); a broken
  link (site-link-broken), any other warning of the strict build
  (site-build-warning) or a failed build (site-build-failed) is a finding.

  Result: {:publish {:source DIR :site DIR :pages n :status :derived|:not-derived}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps the payloads the site is made from in the run's state."
  [req]
  (for [e [:SpecParsed :SpecGated :VerificationDerived :KnowledgeIndexed]]
    [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)]))

;; ---------------------------------------------------------------------------
;; Text

(defn- esc
  "Text for Markdown outside code: angle brackets escaped."
  [s]
  (-> (str s) (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- nm [x] (cond (nil? x) nil (keyword? x) (name x) :else (str x)))

(def no-status "no status was derived because no results were given")

(defn- status-note [results?]
  (if results?
    "_Status was derived from the Allure results of the run._"
    (str "_Status: " no-status "._")))

;; ---------------------------------------------------------------------------
;; The model, as the site reads it

(defn- comp-key
  "The page name of a component: its :spec after the context, else its id's name."
  [c]
  (or (some-> (:spec c) str (str/split #"/") last not-empty) (some-> (:id c) name)))

(defn sections
  "The model's sections in order: [{:key :name :description :view
  :components [{:id :key :name :description :spec}]}], the kernel last."
  [m]
  (let [ctxs (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)] x)
        view-of (fn [ctx-id] (some #(when (and (= :component (:level %)) (= ctx-id (:context %))) (:key %)) (:views m)))
        comps (fn [cs] (vec (for [c cs :when (map? c)]
                              {:id (:id c) :key (comp-key c) :name (or (:name c) (nm (:id c)))
                               :description (:description c) :spec (:spec c)})))]
    (vec (concat
          (for [x ctxs]
            {:id (:id x) :key (nm (:id x)) :name (or (:name x) (nm (:id x))) :description (:description x)
             :view (view-of (:id x)) :components (comps (:components x))})
          (when (seq (:kernel m))
            [{:id :kernel :key "kernel" :name "kernel"
              :description "Shared code no bounded context owns."
              :components (comps (:kernel m))}])))))

(defn- page-of-component
  "{component-id page} of every component page."
  [secs]
  (into {} (for [s secs c (:components s)] [(:id c) (str (:key s) "/" (:key c) ".md")])))

;; ---------------------------------------------------------------------------
;; Footnotes

(def ^:private footnote-re #"^(\s*\[\^[^\]\s]+\]:)(.*)$")

(def ^:private citation-re #"`?([A-Za-z0-9][A-Za-z0-9_.-]*):([^\s`@]+)@([0-9a-fA-F]{4,40})`?")

(defn- blob-url [repository path commit]
  (str (-> (str repository) (str/replace #"/+$" "") (str/replace #"\.git$" "")) "/blob/" commit "/" path))

(defn link-footnotes
  "`text` with every citation in a footnote definition linked to its source
  at its commit; `sources` is {source-id repository}."
  [text sources]
  (str/join "\n"
            (for [l (str/split (str text) #"\n" -1)]
              (if-let [[_ head body] (re-matches footnote-re l)]
                (str head (str/replace body citation-re
                                       (fn [[whole src path commit]]
                                         (if-let [repo (get sources src)]
                                           (str "[" (if (str/starts-with? whole "`") whole (str "`" whole "`")) "]("
                                                (blob-url repo path commit) ")")
                                           whole))))
                l))))

;; ---------------------------------------------------------------------------
;; Pages (pure)

(defn- scenario-block [sc statuses results?]
  (let [st (cond (:wip? sc) "planned"
                 (and results? (:id sc)) (nm (get statuses (:id sc) :unverified))
                 :else no-status)
        steps (concat (:background sc) (:steps sc))]
    (str "### " (or (:id sc) "(no id)") ": " (esc (:title sc)) "\n\n"
         (str/join " · " (remove nil? [(str "Level: " (or (nm (:level sc)) "none"))
                                       (str "Status: " st)
                                       (when (seq (:needs sc)) (str "Needs: " (str/join ", " (:needs sc))))
                                       (when (:rule sc) (str "Rule: " (esc (:rule sc))))]))
         "\n\n```gherkin\n"
         (str/join (for [s steps] (str (:keyword s) " " (:text s) "\n")))
         (str/join (for [ex (:examples sc)]
                     (str "Examples:\n"
                          (str/join (for [row (cons (:header ex) (map :cells (:rows ex)))]
                                      (str "  | " (str/join " | " row) " |\n"))))))
         "```\n")))

(defn- by-id [scs] (sort-by (fn [sc] [(nil? (:id sc)) (str (:id sc)) (str (:file sc)) (or (:line sc) 0)]) scs))

(defn- component-page [sec c {:keys [scenarios statuses results? prose]}]
  (let [scs (by-id (filter #(= (:id c) (:component %)) scenarios))
        text (get prose (:spec c))]
    (str "# " (esc (:name c)) "\n\n"
         "Bounded context: [" (esc (:name sec)) "](index.md)\n\n"
         (when (:description c) (str (esc (:description c)) "\n\n"))
         (status-note results?) "\n\n"
         (when (and text (not (str/blank? text))) (str "## Design\n\n" (str/trim text) "\n\n"))
         "## Scenarios\n\n"
         (if (seq scs)
           (str/join "\n" (map #(scenario-block % statuses results?) scs))
           "No scenario.\n"))))

(defn- context-page [sec {:keys [results? views]}]
  (str "# " (esc (:name sec)) "\n\n"
       (when (:description sec) (str (esc (:description sec)) "\n\n"))
       (status-note results?) "\n\n"
       (when (and (:view sec) (contains? views (:view sec)))
         (str "## Component view\n\n![" (:view sec) "](../views/" (:view sec) ".svg)\n\n"))
       "## Components\n\n"
       (str/join (for [c (:components sec)]
                   (str "- [" (esc (:name c)) "](" (:key c) ".md)"
                        (when (:description c) (str ": " (esc (:description c)))) "\n")))))

(defn- home-page [{:keys [model needs scenarios results? views wiki]} secs pages-of]
  (let [shown (for [v (:views model)
                    :when (and (#{:context :container} (:level v)) (contains? views (:key v)))]
                v)]
    (str "# " (esc (or (get-in model [:system :name]) "The spec")) "\n\n"
         (when-let [d (get-in model [:system :description])] (str (esc d) "\n\n"))
         (status-note results?) "\n\n"
         (when (seq shown)
           (str "## Views\n\n"
                (str/join (for [v shown] (str "![" (:key v) "](views/" (:key v) ".svg)\n\n")))))
         (when (seq secs)
           (str "## Bounded contexts\n\n"
                (str/join (for [s secs]
                            (str "- [" (esc (:name s)) "](" (:key s) "/index.md)"
                                 (when (:description s) (str ": " (esc (:description s)))) "\n")))
                "\n"))
         "## User needs\n\n"
         (if (seq needs)
           (str/join "\n"
                     (for [n (sort-by :id needs)
                           :let [scs (by-id (filter #(and (:id %) (some #{(:id n)} (:needs %))) scenarios))]]
                       (str "### " (:id n) "\n\n"
                            (when (:text n) (str (esc (:text n)) "\n\n"))
                            (if (seq scs)
                              (str/join (for [sc scs]
                                          (str "- "
                                               (if-let [pg (pages-of (:component sc))]
                                                 (str "[" (:id sc) "](" pg ")")
                                                 (:id sc))
                                               ": " (esc (:title sc)) "\n")))
                              "No scenario traces to it.\n"))))
           "No need is declared.\n")
         (when (contains? wiki "index.md")
           (str "\n## Wiki\n\nThe domain, distilled from the raw sources: [the domain wiki](wiki/index.md).\n")))))

(defn pages
  "Every Markdown page of the site, {rel text} relative to docs/, from
  `input`: {:model :scenarios :needs :statuses :results? :prose {spec text}
  :views #{key} :wiki {rel text} :sources {id repository}}."
  [{:keys [model wiki sources results?] :as input}]
  (let [secs (sections model)
        pages-of (page-of-component secs)]
    (into (sorted-map)
          (concat
           [["index.md" (home-page input secs pages-of)]]
           (for [s secs] [(str (:key s) "/index.md") (context-page s input)])
           (for [s secs c (:components s)]
             [(str (:key s) "/" (:key c) ".md") (component-page s c input)])
           (for [[rel text] wiki]
             [(str "wiki/" rel) (str (str/trimr (link-footnotes text sources)) "\n\n---\n\n"
                                     (status-note results?) "\n")])))))

;; ---------------------------------------------------------------------------
;; The builder's configuration

(defn- q [s] (str "\"" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- title-of [text fallback]
  (or (some #(second (re-matches #"^#\s+(.+?)\s*#*$" %)) (str/split-lines (str text))) fallback))

(defn mkdocs-yml
  "The site builder's configuration for the pages `ps` of `input`; `site-dir`
  is the site directory relative to the source directory."
  [{:keys [model]} ps site-dir]
  (let [secs (sections model)
        wiki-pages (sort (filter #(and (str/starts-with? % "wiki/") (not= "wiki/index.md" %)) (keys ps)))]
    (str "site_name: " (q (or (get-in model [:system :name]) "The spec")) "\n"
         "docs_dir: docs\n"
         "site_dir: " (q site-dir) "\n"
         "use_directory_urls: false\n"
         "markdown_extensions:\n  - footnotes\n  - tables\n"
         "nav:\n"
         "  - " (q "Home") ": " (q "index.md") "\n"
         (str/join (for [s secs]
                     (str "  - " (q (:name s)) ":\n"
                          "      - " (q (:name s)) ": " (q (str (:key s) "/index.md")) "\n"
                          (str/join (for [c (:components s)]
                                      (str "      - " (q (:name c)) ": " (q (str (:key s) "/" (:key c) ".md")) "\n"))))))
         (when (contains? ps "wiki/index.md")
           (str "  - " (q "wiki") ":\n"
                "      - " (q "Wiki") ": " (q "wiki/index.md") "\n"
                (str/join (for [w wiki-pages]
                            (str "      - " (q (title-of (get ps w) (fs/file-name w))) ": " (q w) "\n"))))))))

;; ---------------------------------------------------------------------------
;; Building

(def ^:private broken-re
  #"Doc file '([^']+)' contains a link '([^']+)', but the target '([^']+)' is not found among documentation files\.?")

(defn build-findings
  "The findings in the site builder's output `out`, the source directory
  being `source`."
  [source out]
  (vec (for [l (str/split-lines (str out))
             :when (re-find #"^\s*WARNING\b" l)
             :let [msg (str/trim (str/replace l #"^\s*WARNING\s*-?\s*" ""))]]
         (if-let [[_ page link target] (re-find broken-re msg)]
           (command/finding (str source "/docs/" page) "site-link-broken"
                            (str "the page links to " link " (" target "), which the site does not hold;"
                                 " the strict build failed, so no HTML was written"))
           (command/finding (str source "/mkdocs.yml") "site-build-warning"
                            (str "the strict build failed on a warning: " msg))))))

(defn- inside?
  "Whether `d` lies strictly inside `root`."
  [root d]
  (let [r (fs/normalize (fs/absolutize root))
        a (fs/normalize (fs/absolutize d))]
    (and (fs/starts-with? a r) (not= (str a) (str r)))))

(defn- guarded-dir [req k]
  (let [rel (str (get-in (:config req) [:site k] (get-in command/defaults [:site k])))
        d (command/path req rel)]
    (when-not (inside? (:root req) d)
      (command/cannot-run! (str "the configured :site " k " " rel " is not a directory inside the project")))
    (when (some #(= (str (fs/normalize (fs/absolutize d))) (str (fs/normalize (fs/absolutize (command/path req %)))))
                [(command/config-value req :spec-dir) "dhf" "dhf/harness"])
      (command/cannot-run! (str "the configured :site " k " " rel " would overwrite the spec")))
    {:rel rel :dir d}))

(defn- installed? [exe]
  (boolean (if (str/includes? (str exe) "/") (fs/executable? exe) (fs/which exe))))

(defn- read-wiki [req]
  (let [d (command/path req (command/config-value req :wiki-dir))]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**.md") :when (fs/regular-file? f)]
                           [(str/replace (str (fs/relativize d f)) "\\" "/") (slurp (str f))]))
      {})))

(defn- read-model [req]
  (let [f (command/path req (command/config-value req :model))]
    (if (fs/regular-file? f)
      (let [m (try (edn/read-string (slurp (str f))) (catch Exception _ nil))]
        (if (map? m) m (command/cannot-run! (str (command/config-value req :model) " cannot be read as the model"))))
      {})))

(defn publish
  "The site's stage: writes the site's Markdown and configuration, and builds it."
  [req]
  (let [ev (some-> (:state req) deref ::events)
        spec (or (:SpecParsed ev) (command/cannot-run! "the spec was not read: no SpecParsed event reached the site"))
        model (read-model req)
        spec-dir (str/replace (str (command/config-value req :spec-dir)) #"/+$" "")
        views-dir (str/replace (str (command/config-value req :views-dir)) #"/+$" "")
        results? (boolean (and (not (str/blank? (:results req))) (:VerificationDerived ev)))
        comps (for [s (sections model) c (:components s) :when (:spec c)] c)
        prose (into {} (for [c comps
                             :let [f (command/path req (str spec-dir "/" (:spec c) ".md"))]
                             :when (fs/regular-file? f)]
                         [(:spec c) (slurp (str f))]))
        views (set (for [v (:views model) :when (fs/regular-file? (command/path req (str views-dir "/" (:key v) ".svg")))]
                     (:key v)))
        input {:model model :scenarios (:scenarios spec) :needs (:needs spec)
               :statuses (:statuses (:VerificationDerived ev)) :results? results?
               :prose prose :views views :wiki (read-wiki req)
               :sources (into {} (for [s (:sources (:KnowledgeIndexed ev))] [(str (:id s)) (:repository s)]))}
        ps (pages input)
        {source-rel :rel source :dir} (guarded-dir req :source)
        {site-rel :rel site :dir} (guarded-dir req :dir)
        site-from-source (str/replace (str (fs/relativize (fs/normalize (fs/absolutize source))
                                                          (fs/normalize (fs/absolutize site))))
                                      "\\" "/")
        builder (vec (get-in (:config req) [:site :builder] (get-in command/defaults [:site :builder])))
        env (or (:env req) (into {} (System/getenv)))]
    (fs/delete-tree source)
    (doseq [[rel text] ps]
      (let [f (fs/file source "docs" rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) text)))
    (doseq [k (sort views)]
      (fs/create-dirs (fs/file source "docs" "views"))
      (fs/copy (command/path req (str views-dir "/" k ".svg")) (fs/file source "docs" "views" (str k ".svg"))
               {:replace-existing true}))
    (spit (str (fs/file source "mkdocs.yml")) (mkdocs-yml input ps site-from-source))
    (fs/delete-tree site)
    (when (or (empty? builder) (not (installed? (first builder))))
      (command/cannot-run! (str "the site builder " (or (first builder) "(none configured)")
                                " is not installed (not on PATH), so no HTML was written")))
    (let [r (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in "" :env env} builder)
                 (catch Exception e
                   (fs/delete-tree site)
                   (command/cannot-run! (str "the site builder " (first builder) " cannot be run, so no HTML was written: "
                                             (ex-message e)))))
          out (str (:out r) "\n" (:err r))
          result {:publish {:source source-rel :site site-rel :pages (count ps)
                            :status (if results? :derived :not-derived)}}]
      (if (zero? (:exit r))
        {:result result :findings []}
        (do (fs/delete-tree site)
            {:result result
             :findings (or (not-empty (build-findings source-rel out))
                           [(command/finding (str source-rel "/mkdocs.yml") "site-build-failed"
                                             (str "the site builder " (str/join " " builder) " exited " (:exit r)
                                                  ", so no HTML was written: "
                                                  (str/join " " (take-last 3 (remove str/blank? (str/split-lines out))))))])})))))
