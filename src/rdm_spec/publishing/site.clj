(ns rdm-spec.publishing.site
  "The Site, the publishing context's one component
  (dhf/spec/publishing/site.feature): `rdm-spec publish [--results DIR]`
  writes the spec, its views, its status and the domain wiki as one site of
  Markdown pages and the site builder's configuration, under the configured
  :site :source, and has the configured site builder (:site :builder, which
  fails on any broken link) build it into :site :dir. The site is a reading
  of the spec, never a copy that can drift: it computes no model hash (the
  views' freshness check is publish's gate) and writes nothing in the spec.

  It reads the spec (SpecParsed), the spec check's verdict (SpecGated), the
  wiki's pages and citations (KnowledgeIndexed) and, with results, every
  scenario's status (VerificationDerived); the model, the views, each
  component's design prose (<spec-dir>/<context>/<component>.md) and the
  wiki's files it reads from the project.

  Pages, under <source>/docs/:
    index.md                    the system, its context and container views,
                                the bounded contexts, the wiki, and every user
                                need with the scenarios tracing to it
    <context>/index.md          the context, its component view, its components
    <context>/<component>.md    the component's description, its prose and
                                every scenario with its id, level, status and
                                steps
    kernel/index.md, kernel/<component>.md   likewise for the kernel
    wiki/...                    the wiki's index and pages, each footnote's
                                citation linked to its source at its commit
    views/<key>.svg             the rendered views
  Without results, every page says no status was derived because no results
  were given; a scenario tagged @wip shows planned whatever. The
  configuration, <source>/mkdocs.yml, lists the home page first, then a
  section per bounded context in the model's order (its page, then its
  components' pages), then the kernel, then the wiki. The same inputs give
  the same bytes, and no page names a path outside the project.

  A build that fails is a finding (exit 1): site-link-broken names the page
  and the link, site-build-failed the builder's words; a builder that is not
  installed cannot run (exit 2). Either way no HTML is left in :site :dir.

  Result: {:publish {:source :site :pages n :statuses? bool}}."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def no-status "no status was derived because no results were given")

;; ---------------------------------------------------------------------------
;; Pure: Markdown

(defn- esc
  "Text as Markdown shows it, never as markup or a link."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")
      (str/replace "[" "\\[") (str/replace "]" "\\]")))

(defn citation-url
  "The address of a cited file at its commit in its source's repository."
  [{:keys [repository]} {:keys [path commit]}]
  (str (-> (str repository) (str/replace #"/+$" "") (str/replace #"\.git$" "")) "/blob/" commit "/" path))

(defn link-citations
  "`line` with each citation of `cites` it holds linked to its source
  (`sources` {id {:repository}}); a citation of no known source is left."
  [line cites sources]
  (reduce (fn [l {:keys [citation source] :as c}]
            (if-let [src (get sources source)]
              (let [url (citation-url src c)
                    quoted (str "`" citation "`")]
                (cond
                  (str/includes? l (str "[" quoted "]")) l
                  (str/includes? l quoted) (str/replace l quoted (str "[" quoted "](" url ")"))
                  (str/includes? l citation) (str/replace l citation (str "[" citation "](" url ")"))
                  :else l))
              l))
          line
          (distinct cites)))

(defn- status-word [sc statuses]
  (cond (:wip? sc) "planned"
        (nil? statuses) "no status"
        :else (some-> (get statuses (:id sc)) name)))

(defn- steps-md [steps]
  (str/join (for [st steps]
              (str "- **" (:keyword st) "** " (esc (:text st)) "\n"
                   (when (:doc-string st)
                     (str "\n    ```\n" (str/join "\n" (map #(str "    " %) (str/split-lines (:doc-string st)))) "\n    ```\n\n"))
                   (when (:data-table st)
                     (str "\n    ```\n" (str/join "\n" (for [row (:data-table st)] (str "    | " (str/join " | " row) " |")))
                          "\n    ```\n\n"))))))

(defn scenario-md
  "A scenario's section: its title, id, level, status and steps."
  [sc statuses]
  (str "### " (esc (:title sc)) "\n\n"
       "`@" (:id sc) "` · " (some-> (:level sc) name) " · **" (status-word sc statuses) "**"
       (when (and (nil? statuses) (not (:wip? sc))) (str ": " no-status))
       "\n\n"
       (when (seq (:background sc)) (str "Background:\n\n" (steps-md (:background sc)) "\n"))
       (steps-md (:steps sc))
       (when (seq (:rows sc))
         (str "\nExamples: " (str/join "; " (for [r (:rows sc)] (esc (str/join ", " (for [[k v] (:values r)] (str k "=" v))))))
              "\n"))))

(defn- component-paths
  "[[context-dir component-id page name description el]] of every
  component, the kernel's last, in the model's order."
  [m]
  (vec (concat
        (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)
              comp (:components x) :when (map? comp)]
          {:context (name (:id x)) :context-name (or (:name x) (name (:id x))) :el comp
           :page (str (name (:id x)) "/" (name (:id comp)) ".md")})
        (for [comp (:kernel m) :when (map? comp)]
          {:context "kernel" :context-name "kernel" :el comp :page (str "kernel/" (name (:id comp)) ".md")}))))

(defn nav
  "The navigation: [[title page-or-entries]]."
  [m wiki-pages wiki-titles]
  (let [ctxs (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)] x)
        section (fn [dir title comps]
                  [title (vec (cons [title (str dir "/index.md")]
                                    (for [comp comps] [(or (:name comp) (name (:id comp))) (str dir "/" (name (:id comp)) ".md")])))])]
    (vec (concat
          [["Home" "index.md"]]
          (for [x ctxs] (section (name (:id x)) (or (:name x) (name (:id x))) (filter map? (:components x))))
          (when (seq (:kernel m)) [(section "kernel" "kernel" (filter map? (:kernel m)))])
          (when (seq wiki-pages)
            [["wiki" (vec (cons ["wiki" "wiki/index.md"]
                                (for [p wiki-pages :when (not= p "wiki/index.md")] [(get wiki-titles p p) p])))]])))))

(defn nav-yaml
  "The navigation as the site builder's configuration writes it."
  [n]
  (let [q json/generate-string]
    (str "nav:\n"
         (str/join (for [[t v] n]
                     (if (string? v)
                       (str "  - " (q t) ": " (q v) "\n")
                       (str "  - " (q t) ":\n"
                            (str/join (for [[t2 v2] v] (str "      - " (q t2) ": " (q v2) "\n"))))))))))

(defn broken-links
  "[[page link]] of every broken link the builder's output names."
  [output]
  (vec (for [[_ pg link] (re-seq #"Doc file '([^']+)' contains a link '([^']+)'" (str output))] [pg link])))

;; ---------------------------------------------------------------------------
;; Reading the project

(defn- read-text [req rel]
  (let [f (command/path req rel)] (when (fs/regular-file? f) (slurp (str f)))))

(defn- strip-slash [s] (str/replace (str s) #"/+$" ""))

(defn subscriptions
  "Keeps what the site reads: KnowledgeIndexed, SpecParsed, SpecGated and
  VerificationDerived."
  [req]
  (for [[e k] [[:KnowledgeIndexed ::knowledge] [:SpecParsed ::spec] [:SpecGated ::gated]
               [:VerificationDerived ::verification]]]
    [e (fn [payload] (swap! (:state req) assoc k payload) nil)]))

(defn- note [statuses] (if statuses "" (str "\n---\n\n_Status: " no-status "._\n")))

(defn- pages-of
  "{path text} of every page of the site."
  [req m spec statuses gated knowledge views]
  (let [spec-dir (strip-slash (command/config-value req :spec-dir))
        comps (component-paths m)
        page-of (into {} (map (juxt (comp :id :el) :page)) comps)
        stamped (sort-by :id (filter :id (:scenarios spec)))
        by-comp (group-by :component stamped)
        ctxs (for [c (:containers m) :when (map? c) x (:contexts c) :when (map? x)] x)
        view-img (fn [prefix v] (when (views (:key v))
                                  (str "![" (esc (or (:description v) (:key v))) "](" prefix "views/" (:key v) ".svg)\n\n")))
        wiki-dir (strip-slash (command/config-value req :wiki-dir))
        wiki-files (let [d (command/path req wiki-dir)]
                     (if (fs/directory? d)
                       (vec (sort (for [f (fs/glob d "**.md") :when (fs/regular-file? f)] (command/rel req f))))
                       []))
        sources (into {} (for [s (:sources knowledge)] [(some-> (:id s) name) s]))
        cites-at (group-by (juxt :file :line) (:citations knowledge))
        wiki-pages (into (sorted-map)
                         (for [f wiki-files
                               :let [rel (str "wiki" (subs f (count wiki-dir)))
                                     text (str/join "\n" (map-indexed (fn [i l] (link-citations l (get cites-at [f (inc i)]) sources))
                                                                      (str/split (or (read-text req f) "") #"\n" -1)))]]
                           [rel (str text (note statuses))]))
        context-page (fn [dir title desc view comps]
                       (str "# " (esc title) "\n\n" (esc desc) "\n\n" (when view (view-img "../" view))
                            "## Components\n\n"
                            (str/join (for [{:keys [el page]} comps]
                                        (str "- [" (esc (:name el)) "](" (subs page (inc (count dir))) "): " (esc (:description el)) "\n")))
                            (note statuses)))]
    (merge
     {"index.md"
      (str "# " (esc (get-in m [:system :name])) "\n\n" (esc (get-in m [:system :description])) "\n\n"
           (str/join (keep #(view-img "" %) (filter #(#{:context :container} (:level %)) (filter map? (:views m)))))
           (when gated (str "The spec check " (if (:passed? gated) "passed" (str "found " (count (:findings gated)) " problems")) ".\n\n"))
           "## Bounded contexts\n\n"
           (str/join (for [x ctxs] (str "- [" (esc (or (:name x) (name (:id x)))) "](" (name (:id x)) "/index.md): "
                                        (esc (:description x)) "\n")))
           (when (seq (:kernel m)) "- [kernel](kernel/index.md): shared code in no bounded context\n")
           (when (seq wiki-pages) "\n## The domain wiki\n\nThe domain, distilled from the raw sources: [the wiki](wiki/index.md).\n")
           "\n## User needs\n"
           (str/join (for [n (:needs spec)
                           :let [scs (filter #(some #{(:id n)} (:needs %)) stamped)]]
                       (str "\n### " (esc (:id n)) "\n\n" (esc (:text n)) "\n\n"
                            (if (seq scs)
                              (str/join (for [sc scs]
                                          (str "- " (if-let [pg (page-of (:component sc))]
                                                      (str "[`@" (:id sc) "` " (esc (:title sc)) "](" pg ")")
                                                      (str "`@" (:id sc) "` " (esc (:title sc))))
                                               " · " (status-word sc statuses) "\n")))
                              "No scenario traces to it.\n"))))
           (note statuses))}
     (into {} (for [x ctxs
                    :let [dir (name (:id x))
                          view (first (filter #(and (= :component (:level %)) (= (:id x) (:context %))) (filter map? (:views m))))]]
                [(str dir "/index.md")
                 (context-page dir (or (:name x) dir) (:description x) view (filter #(= dir (:context %)) comps))]))
     (when (seq (:kernel m))
       {"kernel/index.md" (context-page "kernel" "kernel" "Shared code every bounded context may use, in none of them."
                                        nil (filter #(= "kernel" (:context %)) comps))})
     (into {} (for [{:keys [el page context-name context]} comps
                    :let [prose (when (string? (:spec el)) (read-text req (str spec-dir "/" (:spec el) ".md")))]]
                [page (str "# " (esc (:name el)) "\n\n"
                           (if (= "kernel" context) "_A kernel component._" (str "_A component of the bounded context " (esc context-name) "._"))
                           "\n\n" (esc (:description el)) "\n\n"
                           (when prose (str "## Design\n\n" prose (when-not (str/ends-with? prose "\n") "\n") "\n"))
                           "## Scenarios\n\n"
                           (let [scs (get by-comp (:id el))]
                             (if (seq scs)
                               (str/join "\n" (map #(scenario-md % statuses) scs))
                               "No stamped scenario.\n"))
                           (note statuses))]))
     wiki-pages)))

;; ---------------------------------------------------------------------------
;; Building

(defn- inside! [req rel what]
  (let [root (fs/normalize (fs/absolutize (:root req)))
        f (fs/normalize (fs/absolutize (command/path req rel)))]
    (when-not (and (fs/starts-with? f root) (not= f root))
      (command/cannot-run! (str "the configured " what " " rel " is not a directory inside the project")))
    f))

(defn- build!
  "Runs the site builder: {:findings} when it failed, nil when it built."
  [req builder source-rel site-dir]
  (let [r (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""
                               :env (or (:env req) (into {} (System/getenv)))}
                      builder)
               (catch java.io.IOException e
                 (fs/delete-tree site-dir)
                 (command/cannot-run! (str "the site builder " (first builder) " is not installed (" (ex-message e)
                                           "), so no HTML was written"))))]
    (when-not (zero? (:exit r))
      (fs/delete-tree site-dir)
      (let [out (str (:err r) "\n" (:out r))
            broken (broken-links out)]
        {:findings (if (seq broken)
                     (vec (for [[pg link] broken]
                            (command/finding (str source-rel "/docs/" pg) "site-link-broken"
                                             (str "the page " pg " links to " link ", which the site does not hold;"
                                                  " the build failed and no HTML was written"))))
                     [(command/finding "site-build-failed"
                                       (str "the site builder " (str/join " " builder) " failed with exit status " (:exit r)
                                            ", so no HTML was written: "
                                            (str/join " " (take-last 5 (remove str/blank? (str/split-lines out)))))) ])}))))

(defn publish
  "The site's stage of `rdm-spec publish`."
  [req]
  (let [st (some-> (:state req) deref)
        spec (or (::spec st) (command/cannot-run! "no SpecParsed reached the site: the spec was not read"))
        statuses (:statuses (::verification st))
        site (merge (command/config-value {} :site) (command/config-value req :site))
        source-rel (strip-slash (:source site))
        site-rel (strip-slash (:dir site))
        source (inside! req source-rel "site source")
        site-dir (inside! req site-rel "site directory")
        builder (vec (:builder site))
        _ (when (empty? builder) (command/cannot-run! "the configuration's :site names no :builder"))
        m (or (try (edn/read-string (read-text req (command/config-value req :model))) (catch Exception _ nil)) {})
        views-dir (strip-slash (command/config-value req :views-dir))
        views (into {} (for [v (filter map? (:views m))
                             :let [f (command/path req (str views-dir "/" (:key v) ".svg"))]
                             :when (fs/regular-file? f)]
                         [(:key v) f]))
        pages (pages-of req m spec statuses (::gated st) (::knowledge st) views)
        wiki-pages (vec (sort (filter #(str/starts-with? % "wiki/") (keys pages))))
        wiki-titles (into {} (for [p wiki-pages] [p (or (second (re-find #"(?m)^# (.+)$" (get pages p))) p)]))
        n (nav m (if (some #{"wiki/index.md"} wiki-pages) wiki-pages []) wiki-titles)
        docs (fs/file source "docs")]
    (fs/delete-tree source)
    (fs/create-dirs docs)
    (doseq [[rel text] pages]
      (let [f (fs/file docs rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) text)))
    (doseq [[k f] views]
      (fs/create-dirs (fs/file docs "views"))
      (fs/copy f (fs/file docs "views" (str k ".svg")) {:replace-existing true}))
    (spit (str (fs/file source "mkdocs.yml"))
          (str "site_name: " (json/generate-string (str (or (get-in m [:system :name]) "rdm-spec"))) "\n"
               "docs_dir: \"docs\"\n"
               "site_dir: " (json/generate-string (str (fs/relativize source site-dir))) "\n"
               "markdown_extensions:\n  - footnotes\n  - tables\n"
               (nav-yaml n)))
    (let [failed (build! req builder source-rel site-dir)]
      {:result {:publish {:source source-rel :site site-rel :pages (count pages) :statuses? (some? statuses)}}
       :findings (vec (:findings failed))})))
