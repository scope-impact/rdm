(ns rdm-spec.publishing.site
  "Site (dhf/spec/publishing/site.feature): the spec, its views, the domain
  wiki and, when results were given, each scenario's status, as one site:
  Markdown and the site builder's configuration, which rdm-spec writes, built
  to HTML by the configured site builder, which it runs and never writes.
  The last stage of `rdm-spec publish`, after the views' freshness gate.

  What it reads: the spec as SpecParsed carries it (scenarios and needs), the
  spec check's verdict (SpecGated), the statuses (VerificationDerived, only
  when results were given: the site shows status and never decides it), the
  sources (KnowledgeIndexed, to link each wiki footnote's citation to its
  source), and from the project the model, the components' design prose
  (<spec-dir>/<context>/<component>.md), the rendered views and the wiki.

  What it writes, under the configured :site (paths relative to the project):
    <source>/mkdocs.yml                 the site builder's configuration
    <source>/docs/index.md              home: the context and container views,
                                        the contexts, the wiki, every need with
                                        the scenarios tracing to it
    <source>/docs/<context>/index.md    a context: its component view, its components
    <source>/docs/<spec>.md             a component (<spec> its :spec path): its
                                        description, prose and scenarios
    <source>/docs/kernel/...            the kernel, as a context
    <source>/docs/views/<key>.svg       the rendered views
    <source>/docs/wiki/...              the wiki, its footnotes' citations linked
    <dir>/                              the HTML, by the site builder
  The Markdown and the configuration are rewritten whole on every publish,
  and the same inputs write the same bytes; no page names a path outside the
  project. A build that fails leaves no HTML; a missing site builder cannot
  run (exit 2) and leaves no HTML either.

  Rules: site-link-broken (a page links a page the site does not hold),
  site-build (any other warning of the builder), site-build-failed, and
  site-no-model."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def no-status
  "What every page says when no results were given."
  "No status was derived because no results were given.")

(def with-status "Each scenario's status is derived from the Allure results given.")

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps what the site reads from the other contexts in the run's state."
  [req]
  (let [keep! (fn [k] (fn [payload] (swap! (:state req) assoc k payload) nil))]
    [[:SpecParsed (keep! ::spec)]
     [:SpecGated (keep! ::gated)]
     [:VerificationDerived (keep! ::derived)]
     [:KnowledgeIndexed (keep! ::knowledge)]]))

;; ---------------------------------------------------------------------------
;; Markdown

(defn esc
  "`s` as inline Markdown text: nothing in it is read as markup."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace #"[`*_\[\]#!]" #(str "\\" %))
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nm [x] (cond (keyword? x) (name x) (nil? x) nil :else (str x)))

(defn- fence
  "A fence longer than any run of backticks in `text`."
  [text]
  (let [longest (reduce max 0 (map count (re-seq #"`+" (str text))))]
    (apply str (repeat (max 3 (inc longest)) "`"))))

(defn- step-lines [{:keys [keyword text doc-string data-table]}]
  (concat [(str "  " keyword " " text)]
          (when doc-string
            (concat ["    \"\"\""] (map #(str "    " %) (str/split-lines doc-string)) ["    \"\"\""]))
          (for [row data-table] (str "    | " (str/join " | " row) " |"))))

(defn scenario-block
  "The scenario's Gherkin, as a fenced block."
  [sc]
  (let [body (str/join "\n"
                       (concat
                        [(str (or (:keyword sc) "Scenario") ": " (:title sc))]
                        (mapcat step-lines (:steps sc))
                        (for [ex (:examples sc)
                              line (concat ["" "  Examples:" (str "    | " (str/join " | " (:header ex)) " |")]
                                           (for [r (:rows ex)] (str "    | " (str/join " | " (:cells r)) " |")))]
                          line)))
        f (fence body)]
    (str f "gherkin\n" body "\n" f "\n")))

(defn status-of
  "The status a page shows for the scenario `sc`: the derived one when
  statuses were derived (`statuses`, {id status}), else planned for a
  scenario tagged @wip (what the tag says, whatever any result says) and
  otherwise \"not derived\"."
  [statuses sc]
  (cond
    (nil? (:id sc)) "no id, so no status"
    statuses (or (nm (get statuses (:id sc))) "unverified")
    (:wip? sc) "planned"
    :else "not derived"))

(defn- status-line [statuses] (if statuses with-status no-status))

;; ---------------------------------------------------------------------------
;; Citations

(def ^:private citation-re
  #"(`?)([A-Za-z0-9][A-Za-z0-9_-]*):([^\s`@\"]+)@([0-9a-f]{7,40})(`?)")

(defn citation-url
  "The URL of the cited path at its commit in its source's repository, or
  nil when the source is not known. `repos` {source-id repository}."
  [repos source path commit]
  (when-let [repo (get repos source)]
    (str (str/replace repo #"/+$" "") "/blob/" commit "/" path)))

(defn link-citations
  "`line` with each citation of a known source turned into a link."
  [repos line]
  (str/replace line citation-re
               (fn [[whole tick-a source path commit tick-b]]
                 (if-let [url (citation-url repos source path commit)]
                   (str "[" tick-a source ":" path "@" commit tick-b "](" url ")")
                   whole))))

(defn link-footnotes
  "The wiki text `text` with every footnote definition's citations linked."
  [repos text]
  (let [lines (str/split (str text) #"\n" -1)]
    (str/join "\n" (map #(if (re-find #"^\s{0,3}\[\^[^\]]+\]:" %) (link-citations repos %) %) lines))))

;; ---------------------------------------------------------------------------
;; The model

(defn contexts-of
  "The model's bounded contexts, then the kernel as a context named kernel:
  [{:key :name :description :kernel? :components [{:id :name :description :spec :page}]}]."
  [model]
  (let [page (fn [ctx-key comp]
               (str (or (:spec comp) (str ctx-key "/" (nm (:id comp)))) ".md"))
        ctxs (for [c (:containers model) cx (:contexts c)
                   :let [k (nm (:id cx))]]
               {:key k :id (:id cx) :name (or (:name cx) k) :description (:description cx)
                :components (vec (for [cp (:components cx)] (assoc cp :page (page k cp))))})
        kernel (when (seq (:kernel model))
                 [{:key "kernel" :id :kernel :name "kernel" :kernel? true
                   :description "Shared code no bounded context owns"
                   :components (vec (for [cp (:kernel model)] (assoc cp :page (page "kernel" cp))))}])]
    (vec (concat ctxs kernel))))

(defn- rel-link
  "The link from the page `from` to the page `to` (both relative to docs)."
  [from to]
  (let [from-dir (or (fs/parent (fs/path from)) (fs/path ""))]
    (str/replace (str (fs/relativize (fs/absolutize (fs/path "/" (str from-dir)))
                                     (fs/absolutize (fs/path "/" to))))
                 "\\" "/")))

;; ---------------------------------------------------------------------------
;; Pages

(defn component-page
  "A component's page."
  [{:keys [ctx comp prose scenarios statuses]}]
  (let [by-rule (partition-by :rule scenarios)]
    (str "# " (esc (:name comp)) " (" (esc (:name ctx)) ")\n\n"
         "Component " (esc (:name comp)) " of " (if (:kernel? ctx) "the kernel" (str "the bounded context " (esc (:name ctx))))
         ".\n\n" (esc (:description comp)) "\n\n"
         (status-line statuses) "\n\n"
         (when (and prose (not (str/blank? prose)))
           (str "## Design\n\n" (str/trim prose) "\n\n"))
         "## Scenarios\n\n"
         (if (empty? scenarios)
           "This component owns no scenario.\n"
           (str/join
            (for [group by-rule
                  :let [rule (:rule (first group))]]
              (str (when rule (str "### Rule: " (esc rule) "\n\n"))
                   (str/join
                    (for [sc group]
                      (str (when (:id sc) (str "<a id=\"" (:id sc) "\"></a>\n\n"))
                           "#### " (when (:id sc) (str (:id sc) ": ")) (esc (:title sc)) "\n\n"
                           "- **Level:** " (or (nm (:level sc)) "none") "\n"
                           "- **Status:** " (status-of statuses sc) "\n"
                           "- **Needs:** " (if (seq (:needs sc)) (str/join ", " (:needs sc)) "none") "\n\n"
                           (scenario-block sc) "\n"))))))))))

(defn- view-image [from v views-present]
  (if (views-present (:key v))
    (str "![" (esc (or (:description v) (:key v))) "](" (rel-link from (str "views/" (:key v) ".svg")) ")\n\n")
    (str "The view " (esc (:key v)) " is not rendered.\n\n")))

(defn context-page
  "A bounded context's page (or the kernel's)."
  [{:keys [ctx views views-present statuses]}]
  (let [from (str (:key ctx) "/index.md")
        cviews (filter #(and (= :component (:level %)) (= (:id ctx) (:context %))) views)]
    (str "# " (esc (:name ctx)) "\n\n"
         (if (:kernel? ctx) "The kernel. " "Bounded context. ") (esc (:description ctx)) "\n\n"
         (status-line statuses) "\n\n"
         (when-not (:kernel? ctx)
           (str "## Component view\n\n"
                (if (seq cviews)
                  (str/join (map #(view-image from % views-present) cviews))
                  "This bounded context has no component view.\n\n")))
         "## Components\n\n"
         (str/join (for [cp (:components ctx)]
                     (str "- [" (esc (:name cp)) "](" (rel-link from (:page cp)) "): " (esc (:description cp)) "\n"))))))

(defn home-page
  "The home page."
  [{:keys [model contexts views views-present needs scenarios statuses gated wiki? page-of]}]
  (let [from "index.md"
        level-views (fn [lvl] (filter #(= lvl (:level %)) views))]
    (str "# " (esc (get-in model [:system :name] "The spec")) "\n\n"
         (esc (get-in model [:system :description])) "\n\n"
         (status-line statuses) "\n\n"
         (when gated
           (str (if (:passed? gated)
                  "The spec check passed."
                  (str "The spec check found " (count (remove #(= :warning (:severity %)) (:findings gated)))
                       " findings."))
                "\n\n"))
         "## Context view\n\n"
         (let [vs (level-views :context)]
           (if (seq vs) (str/join (map #(view-image from % views-present) vs)) "The model has no context view.\n\n"))
         "## Container view\n\n"
         (let [vs (level-views :container)]
           (if (seq vs) (str/join (map #(view-image from % views-present) vs)) "The model has no container view.\n\n"))
         (when-let [vs (seq (level-views :dynamic))]
           (str "## Dynamic views\n\n" (str/join (map #(view-image from % views-present) vs))))
         "## Bounded contexts\n\n"
         (if (seq contexts)
           (str/join (for [c contexts]
                       (str "- [" (esc (:name c)) "](" (:key c) "/index.md): " (esc (:description c)) "\n")))
           "The model has no bounded context yet.\n")
         "\n## Domain wiki\n\n"
         (if wiki? "The domain, distilled from the raw sources: [the wiki](wiki/index.md).\n" "There is no wiki.\n")
         "\n## User needs\n\n"
         (if (empty? needs)
           "No user need is declared.\n"
           (str/join
            (for [n needs
                  :let [scs (filter #(and (:id %) (some #{(:id n)} (:needs %))) scenarios)]]
              (str "### " (:id n) "\n\n"
                   (esc (:text n)) "\n\n"
                   (if (seq scs)
                     (str/join (for [sc scs]
                                 (str "- [" (:id sc) "](" (page-of sc) "#" (:id sc) "): " (esc (:title sc))
                                      " (" (status-of statuses sc) ")\n")))
                     "No scenario traces to this need.\n")
                   "\n")))))))

;; ---------------------------------------------------------------------------
;; The wiki

(def ^:private link-re #"\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")

(defn- title-of [text fallback]
  (or (some #(second (re-matches #"#\s+(.+?)\s*#*\s*" %)) (str/split-lines (str text))) fallback))

(defn wiki-pages
  "The wiki's Markdown files under `dir`: [{:rel :text}], the index first,
  then the pages the index links in its order, then the rest by path."
  [dir]
  (let [files (if (fs/directory? dir)
                (into (sorted-map) (for [f (fs/glob dir "**.md") :when (fs/regular-file? f)]
                                     [(str/replace (str (fs/relativize dir f)) "\\" "/") (slurp (str f))]))
                {})
        index (get files "index.md")
        linked (distinct (for [[_ t] (re-seq link-re (str index))
                               :let [t (str/replace t #"#.*$" "")]
                               :when (contains? files t)]
                           t))
        order (distinct (concat (when index ["index.md"]) linked (keys files)))]
    (vec (for [r order] {:rel r :text (get files r)}))))

;; ---------------------------------------------------------------------------
;; The site builder's configuration

(defn- q [s] (json/generate-string (str s)))

(defn mkdocs-yml
  "The site builder's configuration: `nav` [[title page-or-entries] ..]."
  [site-name site-dir nav]
  (let [entry (fn entry [indent [title v]]
                (if (string? v)
                  (str indent "- " (q title) ": " (q v) "\n")
                  (str indent "- " (q title) ":\n" (str/join (map #(entry (str indent "    ") %) v)))))]
    (str "# Written by rdm-spec publish from the spec; rewritten on every publish.\n"
         "site_name: " (q site-name) "\n"
         "docs_dir: \"docs\"\n"
         "site_dir: " (q site-dir) "\n"
         "use_directory_urls: false\n"
         "markdown_extensions:\n  - toc\n  - tables\n  - fenced_code\n  - footnotes\n"
         "nav:\n"
         (str/join (map #(entry "  " %) nav)))))

;; ---------------------------------------------------------------------------
;; Building

(defn- path-dirs [env]
  (remove str/blank? (str/split (str (get env "PATH" "")) (re-pattern java.io.File/pathSeparator))))

(defn builder-findings
  "Findings from the site builder's output `out`, the pages under `docs-rel`."
  [docs-rel out]
  (vec (for [l (str/split-lines (str out))
             :when (re-find #"^(WARNING|ERROR)\b" l)
             :let [[_ page link] (re-find #"Doc file '([^']+)' contains a link '([^']+)'" l)]]
         (if page
           (command/finding (str docs-rel "/" page) nil "site-link-broken"
                            (str "the page links " link ", which the site does not hold"))
           (command/finding (str docs-rel) nil "site-build"
                            (str "the site builder says: " (str/trim (str/replace l #"^(WARNING|ERROR)\s*-\s*" ""))))))))

(defn- write-tree!
  "Writes `files` {rel text} under `dir`, which is emptied first."
  [dir files]
  (when (fs/exists? dir) (fs/delete-tree dir))
  (fs/create-dirs dir)
  (doseq [[rel text] files
          :let [f (fs/file dir rel)]]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn publish
  "Publish's last stage: see the namespace doc. Result {:site {:source :dir
  :builder :pages :built? :status-derived?}}."
  [req]
  (let [config (:config req)
        site (command/config-value req :site)
        source-rel (strip-slash (or (:source site) (command/cannot-run! "the configuration's :site names no :source")))
        html-rel (strip-slash (or (:dir site) (command/cannot-run! "the configuration's :site names no :dir")))
        builder (vec (:builder site))
        _ (when (empty? builder) (command/cannot-run! "the configuration's :site names no :builder"))
        st (some-> (:state req) deref)
        spec (or (::spec st) (command/cannot-run! "the spec was not read: no SpecParsed event reached the site"))
        statuses (:statuses (::derived st))
        repos (into {} (for [s (:sources (::knowledge st))] [(nm (:id s)) (:repository s)]))
        spec-dir (strip-slash (command/config-value req :spec-dir))
        model-rel (or (:model config) (str spec-dir "/model.edn"))
        model-f (command/path req model-rel)
        model (when (fs/regular-file? model-f)
                (try (let [m (edn/read-string (slurp (str model-f)))] (when (map? m) m)) (catch Exception _ nil)))]
    (if (nil? model)
      {:findings [(command/finding model-rel nil "site-no-model"
                                   "the model cannot be read, so no site was written: the site follows the model")]}
      (let [views-rel (strip-slash (or (:views-dir config) "dhf/views"))
            views (vec (:views model))
            svgs (into (sorted-map)
                       (for [v views
                             :let [f (command/path req (str views-rel "/" (:key v) ".svg"))]
                             :when (fs/regular-file? f)]
                         [(:key v) (slurp (str f))]))
            views-present (set (keys svgs))
            contexts (contexts-of model)
            scenarios (vec (:scenarios spec))
            by-component (group-by :component scenarios)
            page-of (into {} (for [c contexts cp (:components c)] [(:id cp) (:page cp)]))
            page-of-sc (fn [sc] (get page-of (:component sc) "index.md"))
            wiki-dir (command/path req (or (:wiki-dir config) "dhf/wiki"))
            wiki (wiki-pages wiki-dir)
            wiki? (boolean (some #(= "index.md" (:rel %)) wiki))
            prose (fn [cp] (when (:spec cp)
                             (let [f (command/path req (str spec-dir "/" (:spec cp) ".md"))]
                               (when (fs/regular-file? f) (slurp (str f))))))
            pages (into (sorted-map)
                        (concat
                         [["index.md" (home-page {:model model :contexts contexts :views views
                                                  :views-present views-present :needs (:needs spec)
                                                  :scenarios scenarios :statuses statuses :gated (::gated st)
                                                  :wiki? wiki? :page-of page-of-sc})]]
                         (for [c contexts]
                           [(str (:key c) "/index.md")
                            (context-page {:ctx c :views views :views-present views-present :statuses statuses})])
                         (for [c contexts cp (:components c)]
                           [(:page cp) (component-page {:ctx c :comp cp :prose (prose cp) :statuses statuses
                                                        :scenarios (vec (get by-component (:id cp)))})])
                         (for [{:keys [rel text]} wiki]
                           [(str "wiki/" rel)
                            (str (link-footnotes repos text)
                                 (when-not statuses (str "\n\n_" no-status "_\n")))])))
            nav (concat
                 [["Home" "index.md"]]
                 (for [c contexts]
                   [(:name c) (vec (cons [(:name c) (str (:key c) "/index.md")]
                                         (for [cp (:components c)] [(or (:name cp) (nm (:id cp))) (:page cp)])))])
                 (when wiki?
                   [["wiki" (vec (for [{:keys [rel text]} wiki]
                                   [(if (= "index.md" rel) "Wiki" (title-of text rel)) (str "wiki/" rel)]))]]))
            source (command/path req source-rel)
            html (command/path req html-rel)
            site-dir (str/replace (str (fs/relativize (fs/absolutize source) (fs/absolutize html))) "\\" "/")
            docs-rel (str source-rel "/docs")
            files (merge (update-keys pages #(str "docs/" %))
                         (into {} (for [[k svg] svgs] [(str "docs/views/" k ".svg") svg]))
                         ;; non-Markdown files of the wiki, as they are
                         (into {} (for [f (if (fs/directory? wiki-dir) (fs/glob wiki-dir "**") [])
                                        :when (and (fs/regular-file? f) (not (str/ends-with? (str f) ".md")))]
                                    [(str "docs/wiki/" (str/replace (str (fs/relativize wiki-dir f)) "\\" "/"))
                                     (slurp (str f))]))
                         {"mkdocs.yml" (mkdocs-yml (get-in model [:system :name] "Spec") site-dir nav)})]
        (write-tree! source files)
        (let [tool (first builder)
              exe (or (when (fs/absolute? tool) (when (fs/executable? tool) tool))
                      (some-> (fs/which tool {:paths (path-dirs (:env req))}) str))]
          (when-not exe
            (when (fs/exists? html) (fs/delete-tree html))
            (command/cannot-run! (str "the site builder " tool " is not installed (not on PATH), so the site cannot be"
                                      " built: no HTML was written, and " html-rel " holds none")))
          (let [r (apply p/shell {:dir (:root req) :env (:env req) :out :string :err :string :continue true :in ""}
                         exe (rest builder))
                out (str (:out r) "\n" (:err r))
                fs* (builder-findings docs-rel out)]
            (if (zero? (:exit r))
              {:result {:site {:source source-rel :dir html-rel :builder builder :pages (count pages)
                               :built? true :status-derived? (some? statuses)}}
               :findings fs*}
              (do (when (fs/exists? html) (fs/delete-tree html))
                  {:result {:site {:source source-rel :dir html-rel :builder builder :pages (count pages)
                                   :built? false :status-derived? (some? statuses)}}
                   :findings (if (seq (remove command/warning? fs*))
                               fs*
                               (conj fs* (command/finding docs-rel nil "site-build-failed"
                                                          (str "the site builder exited " (:exit r) ": "
                                                               (str/join " | " (take-last 3 (remove str/blank? (str/split-lines out))))))))}))))))))
