(ns rdm-spec.publishing.site
  "Site (dhf/spec/publishing/site.feature): publishes the spec, its views, its
  status and the domain wiki as one site. rdm-spec writes the Markdown and the
  site builder's configuration under the configured :site :source; the
  configured site builder (:site :builder, an external tool) builds the HTML
  into :site :dir. rdm-spec writes the builder's configuration, never its
  code.

  What it reads:
    - the spec, as SpecParsed carries it (scenarios and needs), the spec
      check's verdict as SpecGated, and each scenario's status as
      VerificationDerived (only when results were given): the site shows
      status and never decides it. Without results a scenario tagged @wip
      shows planned, as the glossary defines it (planned whatever the
      results), and every other scenario shows no status;
    - the wiki's citations and the sources' repositories, as KnowledgeIndexed
      carries them, to link each footnote's citation to its source;
    - from the repository: the C4 model (the configured :model) for the
      contexts, components, descriptions and order, each component's design
      prose (<spec>.md beside its feature file), the rendered views (SVG,
      the configured :views-dir) and the wiki's files (:wiki-dir).
  Publish runs the views' freshness check as a gate before it (the task
  table), so the site never computes the model's hash itself.

  The site, under :site :source:
    mkdocs.yml                      the builder's configuration: the
                                    navigation follows the model (home, each
                                    bounded context in the model's order, the
                                    kernel, then the wiki)
    docs/index.md                   home: context and container views, the
                                    bounded contexts, the wiki, every need with
                                    the scenarios tracing to it
    docs/<context>/index.md         a bounded context: its component view and
                                    its components
    docs/<context>/<component>.md   a component: its description, prose and
                                    scenarios (id, level, needs, status, steps)
    docs/kernel/...                 the kernel, as a context
    docs/views/<key>.svg            the rendered views
    docs/wiki/...                   the wiki, footnotes linked to their sources
  Every page says where its status came from, or that no status was derived
  because no results were given. Pages name paths relative to the project
  only. The same inputs write the same bytes.

  Building: the source directory and the site directory are emptied first;
  a build that fails, whatever failed, leaves no HTML in the site directory.
  The rules, each a stable name on the error stream:
    site-model-unreadable  the model cannot be read: nothing is published
    site-broken-link       a page links to a page the site does not hold
    site-build-warning     another warning the builder fails on
    site-build-failed      the builder failed: no HTML was written
  A site builder that is not installed refuses the task (exit 2), naming it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps every event the site reads in the run's state."
  [req]
  (let [keep! (fn [k] (fn [payload] (swap! (:state req) assoc k payload) nil))]
    [[:KnowledgeIndexed (keep! ::knowledge)]
     [:SpecParsed (keep! ::spec)]
     [:SpecGated (keep! ::gate)]
     [:VerificationDerived (keep! ::verification)]]))

;; ---------------------------------------------------------------------------
;; Markdown helpers

(defn escape
  "Pure: `s` as literal inline Markdown text."
  [s]
  (str/replace (str s) #"[\\`*_\[\]<>{}|#]" #(str "\\" %)))

(defn- strip-slashes [s] (-> (str s) (str/replace #"^\./+" "") (str/replace #"/+$" "")))

(defn relative-link
  "Pure: the link from the page `from` to the page `to`, both relative to the
  docs directory, with / between names."
  [from to]
  (let [up (count (butlast (str/split from #"/")))]
    (str (str/join (repeat up "../")) to)))

(defn- name-of [k] (cond (keyword? k) (name k) (nil? k) nil :else (str k)))

(defn- anchor [id] (str/lower-case (str id)))

;; ---------------------------------------------------------------------------
;; The model as the site reads it

(defn sections
  "Pure: the site's sections from the model, in the model's order: every
  bounded context, then the kernel when the model has one:
  [{:key \"architecture\" :name :description :kernel? :page \"architecture/index.md\"
    :components [{:id :name :description :spec :page}]}]."
  [model]
  (let [comp-page (fn [ctx-key c]
                    (str (or (some-> (:spec c) strip-slashes not-empty)
                             (str ctx-key "/" (or (name-of (:id c)) (:name c))))
                         ".md"))
        section (fn [k nm desc comps kernel?]
                  {:key k :name nm :description desc :kernel? kernel? :page (str k "/index.md")
                   :components (vec (for [c comps]
                                      {:id (:id c) :name (or (:name c) (name-of (:id c)))
                                       :description (:description c) :spec (:spec c)
                                       :page (comp-page k c)}))})]
    (vec (concat
          (for [c (:containers model) ctx (:contexts c)
                :let [k (or (name-of (:id ctx)) (:name ctx))]]
            (section k (or (:name ctx) k) (:description ctx) (:components ctx) false))
          (when (seq (:kernel model))
            [(section "kernel" "kernel" "Shared code of the container, in no bounded context"
                      (:kernel model) true)])))))

(defn- read-model
  "The model at the configured path, or nil when it cannot be read."
  [req]
  (let [f (command/path req (command/config-value req :model))]
    (when (fs/regular-file? f)
      (try (let [m (edn/read-string (slurp (str f)))] (when (map? m) m))
           (catch Exception _ nil)))))

;; ---------------------------------------------------------------------------
;; Status: shown, never decided

(def no-status-note "No status was derived because no results were given.")

(defn status-note
  "Pure: the line every page carries about where its status came from."
  [{:keys [derived? results]}]
  (if derived?
    (str "Status derived from the Allure results"
         (when results (str " in `" results "`")) ".")
    no-status-note))

(defn status-word
  "Pure: what the site shows for a scenario: the derived status when results
  were given; without results, planned for a scenario tagged @wip (planned
  whatever the results) and no status for every other."
  [statuses derived? sc]
  (cond
    derived? (if-let [s (get statuses (:id sc))] (name-of s) "no status")
    (:wip? sc) "planned"
    :else "no status derived"))

;; ---------------------------------------------------------------------------
;; Pages

(defn- fence-for [lines]
  (if (some #(str/includes? % "```") lines) "~~~~~" "```"))

(defn- step-lines [indent steps]
  (mapcat (fn [{:keys [keyword text doc-string data-table]}]
            (concat [(str indent keyword " " text)]
                    (when doc-string
                      (concat [(str indent "  \"\"\"")]
                              (map #(str indent "  " %) (str/split-lines doc-string))
                              [(str indent "  \"\"\"")]))
                    (for [row data-table]
                      (str indent "  | " (str/join " | " row) " |"))))
          steps))

(defn scenario-steps
  "Pure: a scenario's background, steps and examples, as Gherkin lines."
  [sc]
  (vec (concat
        (when (seq (:background sc))
          (concat ["Background:"] (step-lines "  " (:background sc))))
        (step-lines "" (:steps sc))
        (mapcat (fn [ex]
                  (concat ["" "Examples:"]
                          [(str "  | " (str/join " | " (:header ex)) " |")]
                          (for [r (:rows ex)] (str "  | " (str/join " | " (:cells r)) " |"))))
                (:examples sc)))))

(defn scenario-md
  "Pure: one scenario on its component's page."
  [sc {:keys [statuses derived? home]}]
  (let [lines (scenario-steps sc)
        fence (fence-for lines)
        title (escape (or (:title sc) (:name sc)))
        needs (for [n (:needs sc)] (str "[" n "](" home "#" (anchor n) ")"))]
    (str (if (:id sc)
           (str "### " (:id sc) ": " title " { #" (anchor (:id sc)) " }\n\n")
           (str "### (no id): " title "\n\n"))
         "Level: " (or (name-of (:level sc)) "none")
         (when (seq needs) (str " · Needs: " (str/join ", " needs)))
         " · Status: **" (status-word statuses derived? sc) "**"
         (when (:rule sc) (str " · Rule: " (escape (:rule sc))))
         "\n\n"
         fence "gherkin\n" (str/join "\n" lines) "\n" fence "\n")))

(defn- view-md [from {:keys [key description]} svg?]
  (if svg?
    (str "![" (escape (or description key)) "](" (relative-link from (str "views/" key ".svg")) ")\n\n"
         "_" (escape (or description key)) "_ (view `" key "`)\n")
    (str "The view `" key "` is not rendered.\n")))

(defn- page-head [title note]
  (str "# " title "\n\n" note "\n\n"))

(defn component-page
  "Pure: a component's page."
  [section comp {:keys [scenarios prose note] :as opts}]
  (let [home (relative-link (:page comp) "index.md")]
    (str (page-head (escape (:name comp)) note)
         "Component of " (if (:kernel? section) "the kernel" (str "the bounded context " (escape (:name section))))
         " ([overview](index.md)).\n\n"
         (when (:description comp) (str (escape (:description comp)) "\n\n"))
         (when prose (str "## Design\n\n" (str/trim prose) "\n\n"))
         "## Scenarios\n\n"
         (if (seq scenarios)
           (str/join "\n" (map #(scenario-md % (assoc opts :home home)) scenarios))
           "No scenario belongs to this component.\n"))))

(defn section-page
  "Pure: a bounded context's (or the kernel's) page."
  [section views svg? note]
  (str (page-head (escape (:name section)) note)
       (when (:description section) (str (escape (:description section)) "\n\n"))
       (when (seq views)
         (str "## Views\n\n"
              (str/join "\n" (map #(view-md (:page section) % (svg? (:key %))) views))
              "\n"))
       "## Components\n\n"
       (if (seq (:components section))
         (str/join (for [c (:components section)]
                     (str "- [" (escape (:name c)) "](" (relative-link (:page section) (:page c)) ")"
                          (when (:description c) (str ": " (escape (:description c))))
                          "\n")))
         "No component.\n")))

(defn home-page
  "Pure: the home page."
  [{:keys [model sections views svg? needs scenarios page-of statuses derived? note wiki? gate]}]
  (let [traced (fn [n] (filter #(some #{n} (:needs %)) scenarios))]
    (str (page-head (escape (or (get-in model [:system :name]) "The spec")) note)
         (when-let [d (get-in model [:system :description])] (str (escape d) "\n\n"))
         (when gate
           (str "The spec check " (if (:passed? gate) "passed" (str "found " (count (:findings gate)) " findings"))
                ".\n\n"))
         (str/join (for [v views] (str "## " (escape (str/capitalize (name-of (:level v)))) " view\n\n"
                                       (view-md "index.md" v (svg? (:key v))) "\n")))
         "## Bounded contexts\n\n"
         (str/join (for [s sections]
                     (str "- [" (escape (:name s)) "](" (:page s) ")"
                          (when (:description s) (str ": " (escape (:description s)))) "\n")))
         "\n"
         (when wiki?
           "## Domain wiki\n\nThe domain, distilled from the raw sources: [the wiki's index](wiki/index.md).\n\n")
         "## User needs\n\n"
         (if (seq needs)
           (str/join "\n"
                     (for [{:keys [id text]} needs
                           :let [scs (traced id)]]
                       (str "### " id " { #" (anchor id) " }\n\n"
                            (when text (str (escape text) "\n\n"))
                            (if (seq scs)
                              (str/join (for [sc scs]
                                          (str "- " (if-let [pg (page-of (:component sc))]
                                                      (str "[" (:id sc) "](" pg "#" (anchor (:id sc)) ")")
                                                      (:id sc))
                                               ": " (escape (:title sc))
                                               " (" (status-word statuses derived? sc) ")\n")))
                              "No scenario traces to this need.\n"))))
           "No user need is declared.\n"))))

;; ---------------------------------------------------------------------------
;; The wiki

(defn citation-url
  "Pure: where a citation's path is at its commit in the source's repository."
  [repository path commit]
  (str (str/replace (str repository) #"(\.git)?/*$" "") "/blob/" commit "/" path))

(defn- footnote-def? [line] (re-find #"^\s*\[\^[^\]\s]+\]:" line))

(defn link-citations
  "Pure: the wiki file's `text` with each citation `cites` places in a
  footnote ({:citation :line :url}) linked to its source. A citation written
  in backticks keeps them, inside the link."
  [text cites]
  (let [lines (vec (str/split-lines (str text)))
        by-line (group-by :line cites)
        ;; a footnote's continuation lines belong to it
        in-note (loop [i 0 in? false out []]
                  (if (< i (count lines))
                    (let [l (get lines i)
                          d? (boolean (footnote-def? l))
                          in?' (or d? (and in? (re-matches #"(?:\s{2,}|\t)\S.*" l)))]
                      (recur (inc i) (boolean in?') (conj out (boolean in?'))))
                    out))
        link-line (fn [l cs]
                    (reduce (fn [l {:keys [citation url]}]
                              (let [q (java.util.regex.Pattern/quote citation)
                                    ticked (re-pattern (str "(?<!\\[)`" q "`"))
                                    bare (re-pattern (str "(?<![`\\[/\\w])" q "(?![`\\w])"))]
                                (if (re-find ticked l)
                                  (str/replace l ticked (str/re-quote-replacement (str "[`" citation "`](" url ")")))
                                  (str/replace l bare (str/re-quote-replacement (str "[" citation "](" url ")"))))))
                            l
                            (distinct cs)))]
    (str (str/join "\n" (map-indexed (fn [i l]
                                       (if (and (get in-note i) (seq (get by-line (inc i))))
                                         (link-line l (get by-line (inc i)))
                                         l))
                                     lines))
         "\n")))

(defn with-note
  "Pure: a wiki file's text with `note` after its first heading (or first)."
  [text note]
  (let [lines (str/split-lines (str text))
        i (first (keep-indexed (fn [i l] (when (re-matches #"#\s+\S.*" l) i)) lines))]
    (if i
      (str (str/join "\n" (concat (take (inc i) lines) ["" note] (drop (inc i) lines))) "\n")
      (str note "\n\n" (str/join "\n" lines) "\n"))))

(defn- title-of [text fallback]
  (or (some #(second (re-matches #"#\s+(\S.*?)\s*#*\s*" %)) (str/split-lines (str text))) fallback))

(defn- wiki-files
  "Every Markdown file of the wiki: [{:file rel-to-wiki :src rel-to-project}],
  the index first, then the pages by path."
  [req wiki-dir]
  (let [dir (command/path req wiki-dir)]
    (if (fs/directory? dir)
      (->> (fs/glob dir "**.md")
           (filter fs/regular-file?)
           (map #(str/replace (str (fs/relativize dir %)) "\\" "/"))
           (sort-by (fn [f] [(if (= "index.md" f) 0 1) f]))
           (mapv (fn [f] {:file f :src (str wiki-dir "/" f)})))
      [])))

(defn- wiki-cites
  "{wiki file (project-relative) [{:citation :line :url}]} from KnowledgeIndexed."
  [knowledge]
  (let [repos (into {} (for [s (:sources knowledge)] [(str (:id s)) (:repository s)]))]
    (group-by :file
              (for [c (:citations knowledge)
                    :let [repo (get repos (str (:source c)))]
                    :when (and repo (:file c) (:line c) (:path c) (:commit c))]
                {:file (:file c) :line (:line c) :citation (:citation c)
                 :url (citation-url repo (:path c) (:commit c))}))))

;; ---------------------------------------------------------------------------
;; The builder's configuration

(defn- yq [s] (json/generate-string (str s)))

(defn nav-yaml
  "Pure: the navigation, [[title path] | [title [[title path] ..]]], as YAML."
  [entries]
  (str "nav:\n"
       (str/join (for [[t v] entries]
                   (if (string? v)
                     (str "  - " (yq t) ": " (yq v) "\n")
                     (str "  - " (yq t) ":\n"
                          (str/join (for [[t2 p2] v] (str "      - " (yq t2) ": " (yq p2) "\n")))))))))

(defn config-yaml
  "Pure: the site builder's configuration."
  [{:keys [site-name site-dir nav]}]
  (str "# Written by rdm-spec publish from the spec and its model; do not edit.\n"
       "site_name: " (yq site-name) "\n"
       "docs_dir: docs\n"
       "site_dir: " (yq site-dir) "\n"
       "use_directory_urls: false\n"
       "strict: true\n"
       "validation:\n"
       "  links:\n"
       "    not_found: warn\n"
       "    unrecognized_links: warn\n"
       "markdown_extensions:\n"
       "  - attr_list\n"
       "  - footnotes\n"
       "  - tables\n"
       (nav-yaml nav)))

;; ---------------------------------------------------------------------------
;; Writing the site

(defn site-files
  "Pure: {docs-relative path text} of every page, and the navigation, from
  what the site reads."
  [{:keys [model spec statuses derived? results knowledge gate prose views-on-disk wiki]}]
  (let [note (status-note {:derived? derived? :results results})
        secs (sections model)
        page-of-comp (into {} (for [s secs c (:components s)] [(:id c) (:page c)]))
        scs (vec (:scenarios spec))
        by-comp (group-by :component scs)
        views (:views model)
        svg? (set views-on-disk)
        top-views (filter #(#{:context :container} (:level %)) views)
        opts {:statuses statuses :derived? derived? :note note}
        cites (wiki-cites knowledge)
        wiki-pages (for [{:keys [file src text]} wiki]
                     [(str "wiki/" file)
                      (with-note (link-citations text (get cites src)) note)])
        pages (concat
               [["index.md" (home-page {:model model :sections secs :views top-views :svg? svg?
                                        :needs (:needs spec) :scenarios (filter :id scs)
                                        :page-of page-of-comp :statuses statuses :derived? derived?
                                        :note note :wiki? (boolean (seq wiki)) :gate gate})]]
               (for [s secs]
                 [(:page s) (section-page s (filter #(and (:context %) (= (:key s) (name-of (:context %))))
                                                    (remove (set top-views) views))
                                          svg? note)])
               (for [s secs c (:components s)]
                 [(:page c) (component-page s c (assoc opts :scenarios (get by-comp (:id c))
                                                       :prose (get prose (:spec c))))])
               wiki-pages)
        nav (concat [["Home" "index.md"]]
                    (for [s secs]
                      [(:name s) (vec (cons ["Overview" (:page s)]
                                            (for [c (:components s)] [(:name c) (:page c)])))])
                    (when (seq wiki)
                      [["Wiki" (vec (for [{:keys [file text]} wiki]
                                      [(if (= "index.md" file) "Index" (title-of text file))
                                       (str "wiki/" file)]))]]))]
    {:pages (into (sorted-map) pages) :nav (vec nav)}))

(defn- inside?
  "Whether `f` lies strictly inside the project, and holds neither the spec
  nor the configuration."
  [req f]
  (let [root (fs/normalize (fs/absolutize (:root req)))
        abs (fs/normalize (fs/absolutize f))
        r (str (fs/relativize root abs))]
    (and (str/starts-with? (str abs) (str root))
         (not (str/blank? r)) (not= "." r) (not (str/starts-with? r ".."))
         (not (#{"dhf" "src" "test" ".git"} (first (str/split r #"[/\\]")))))))

(defn- empty-dir!
  "Removes the directory `rel` of the project, when it is one rdm-spec may own."
  [req rel]
  (let [f (command/path req rel)]
    (when-not (inside? req f)
      (command/cannot-run! (str "the configured site directory " rel " is not a directory inside the project rdm-spec may write")))
    (when (fs/exists? f) (fs/delete-tree f))))

(defn- write-site!
  [req source pages config-text views-dir views-on-disk]
  (empty-dir! req source)
  (let [docs (fs/file (command/path req source) "docs")]
    (doseq [[rel text] pages]
      (let [f (fs/file docs rel)]
        (fs/create-dirs (fs/parent f))
        (spit f text)))
    (doseq [k views-on-disk]
      (let [to (fs/file docs "views" (str k ".svg"))]
        (fs/create-dirs (fs/parent to))
        (fs/copy (command/path req (str views-dir "/" k ".svg")) to {:replace-existing true})))
    (spit (fs/file (command/path req source) "mkdocs.yml") config-text)))

;; ---------------------------------------------------------------------------
;; Building

(defn- on-path
  "The executable `cmd` names, looked up on the PATH of `env`, or nil."
  [cmd env]
  (if (str/includes? cmd "/")
    (let [f (fs/file cmd)] (when (and (fs/regular-file? f) (fs/executable? f)) (str f)))
    (some (fn [d]
            (let [f (fs/file d cmd)]
              (when (and (fs/regular-file? f) (fs/executable? f)) (str f))))
          (remove str/blank? (str/split (str (get env "PATH" (System/getenv "PATH"))) #":")))))

(defn build-findings
  "Pure: the findings of a failed build from the builder's output; `page-file`
  names a page (docs-relative) as a project path, `source-of` the file a page
  was written from (or nil)."
  [output exit builder page-file source-of]
  (let [lines (str/split-lines (str output))
        broken (for [l lines
                     :let [[_ page link] (re-find #"Doc file '([^']+)' contains (?:a|an unrecognized relative|an absolute) link '([^']+)'" l)]
                     :when page]
                 (command/finding (page-file page) nil "site-broken-link"
                                  (str "links " link ", which the site does not hold"
                                       (when-let [s (source-of page)] (str " (written from " s ")")))))
        others (for [l lines
                     :when (and (re-find #"^\s*WARNING\b" l)
                                (not (re-find #"Doc file '[^']+' contains (?:a|an unrecognized relative|an absolute) link" l)))]
                 (command/finding "site-build-warning" (str/trim (str/replace l #"^\s*WARNING\s*-?\s*" ""))))]
    (vec (concat broken others
                 [(command/finding "site-build-failed"
                                   (str "the site builder (" (str/join " " builder) ") exited " exit
                                        "; no HTML was written"
                                        (when (and (empty? broken) (empty? others))
                                          (str ": " (str/join " | " (take-last 5 (remove str/blank? lines)))))))]))))

(defn- build!
  "Builds the site with the configured builder: {:findings} (none on success)."
  [req {:keys [dir builder]} page-file source-of]
  (empty-dir! req dir)
  (let [cmd (first builder)
        exe (when cmd (on-path cmd (:env req)))]
    (when-not exe
      (command/cannot-run! (str "the configured site builder " (or cmd "(none)")
                                " is not installed (not found on PATH), so no HTML was written")))
    (let [r (try (apply p/shell {:dir (str (:root req)) :env (:env req) :out :string :err :string
                                 :continue true :in ""}
                        exe (map str (rest builder)))
                 (catch Exception e
                   (empty-dir! req dir)
                   (command/cannot-run! (str "the site builder " cmd " could not be run (" (ex-message e)
                                             "), so no HTML was written"))))]
      (if (zero? (:exit r))
        {:findings []}
        (do (empty-dir! req dir)
            {:findings (build-findings (str (:err r) "\n" (:out r)) (:exit r) builder page-file source-of)})))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- prose-of
  "{spec-path text} of every component's design prose beside its feature."
  [req spec-dir secs]
  (into {} (for [s secs c (:components s)
                 :let [sp (:spec c)
                       f (when sp (command/path req (str spec-dir "/" sp ".md")))]
                 :when (and f (fs/regular-file? f))]
             [sp (slurp (str f))])))

(defn publish
  "The site's stage of `rdm-spec publish [--results DIR]`: writes the site's
  Markdown and the builder's configuration, then builds the HTML."
  [req]
  (let [config (:config req)
        site (command/config-value req :site)
        {:keys [dir source builder]} site
        _ (when-not (and (string? dir) (string? source) (sequential? builder) (seq builder))
            (command/cannot-run! (str "the configuration " command/config-file
                                      " sets no :site {:dir :source :builder}")))
        state (some-> (:state req) deref)
        spec (or (::spec state)
                 (command/cannot-run! "the spec was not read: no SpecParsed event reached the publishing context"))
        model (read-model req)]
    (if (nil? model)
      (do (empty-dir! req dir)
          {:findings [(command/finding (:model config) nil "site-model-unreadable"
                                       "the model cannot be read, so no site was published and no HTML was written")]})
      (let [spec-dir (strip-slashes (or (:spec-dir config) "dhf/spec"))
            views-dir (strip-slashes (or (:views-dir config) "dhf/views"))
            wiki-dir (strip-slashes (or (:wiki-dir config) "dhf/wiki"))
            verification (::verification state)
            derived? (boolean verification)
            results (when (and derived? (:results req))
                      (let [r (command/rel req (:results req))]
                        (when-not (fs/absolute? r) r)))
            secs (sections model)
            views-on-disk (vec (for [v (:views model)
                                     :when (fs/regular-file? (command/path req (str views-dir "/" (:key v) ".svg")))]
                                 (:key v)))
            wiki (vec (for [w (wiki-files req wiki-dir)]
                        (assoc w :text (slurp (str (command/path req (:src w)))))))
            {:keys [pages nav]} (site-files {:model model :spec spec
                                             :statuses (:statuses verification) :derived? derived?
                                             :results results :knowledge (::knowledge state)
                                             :gate (::gate state) :prose (prose-of req spec-dir secs)
                                             :views-on-disk views-on-disk :wiki wiki})
            site-dir (str/replace (str (fs/relativize (fs/normalize (fs/absolutize (command/path req source)))
                                                      (fs/normalize (fs/absolutize (command/path req dir)))))
                                  "\\" "/")
            config-text (config-yaml {:site-name (or (get-in model [:system :name]) "The spec")
                                      :site-dir site-dir :nav nav})
            _ (write-site! req source pages config-text views-dir views-on-disk)
            sources (merge (into {} (for [s secs c (:components s)
                                          :when (fs/regular-file? (command/path req (str spec-dir "/" (:spec c) ".md")))]
                                      [(:page c) (str spec-dir "/" (:spec c) ".md")]))
                           (into {} (for [w wiki] [(str "wiki/" (:file w)) (:src w)])))
            {:keys [findings]} (build! req site
                                       #(str (strip-slashes source) "/docs/" %)
                                       sources)]
        {:result {:site {:source (strip-slashes source)
                         :dir (strip-slashes dir)
                         :pages (count pages)
                         :built? (empty? findings)
                         :status-derived? derived?}}
         :findings findings}))))
