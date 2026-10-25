(ns rdm-spec.publishing.site
  "The Site component of the publishing context (dhf/spec/publishing/site.feature):
  `rdm-spec publish [--results DIR]` writes the spec, its views, its status
  and the domain wiki as Markdown with the site builder's configuration, then
  runs the configured site builder (dhf/harness/config.edn, :site).

  What it reads, and from where:
    the scenarios and needs     SpecParsed (spec context)
    the wiki and its citations  KnowledgeIndexed (knowledge context)
    each scenario's status      VerificationDerived (verification context),
                                only when a results directory was given or the
                                configured one exists
    the model, the views, each  files in the repository: the model (:model),
    component's prose           the rendered views (:views-dir) and
                                <spec-dir>/<context>/<component>.md

  What it writes, and nothing else: <source>/mkdocs.yml and <source>/docs/
  (rewritten whole on every publish); the builder writes the HTML to :dir.

  The site, under <source>/docs:
    index.md                       home: the context, container and dynamic
                                   views, and every need with the scenarios
                                   tracing to it
    <context>/index.md             a context: its component view and its
                                   components
    <context>/<component>.md       a component: its description, its prose and
                                   its scenarios, each with id, level, status
                                   and steps
    kernel/index.md, kernel/*.md   the kernel, as a context with no view
    views/<key>.svg                the rendered views
    wiki/...                       the domain wiki, footnote citations linked
                                   to their source at their commit

  The rules, each a stable name on the error stream:
    site-stale-view    a view records a model hash other than the model's:
                       nothing is published (exit 1)
    site-view-missing  (warning) a view the model declares is not rendered
    site-broken-link   a page links to a page or image the site does not hold
    site-build         the site builder failed (exit 1)
  A missing site builder cannot run (exit 2): nothing is built."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The model hash (glossary: Model hash)

(defn printed
  "`x` printed back with every map's keys and every set's elements sorted by
  their printed form."
  [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (printed k) " " (printed v)))
                                              (sort-by (comp printed key) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map printed x))) "}")
        (vector? x) (str "[" (str/join " " (map printed x)) "]")
        (seq? x) (str "(" (str/join " " (map printed x)) ")")
        :else (binding [*print-namespace-maps* false] (pr-str x))))

(defn sha256-hex [^String text]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes text "UTF-8")))))

(defn model-hash
  "The model hash of the model's value `m`."
  [m]
  (sha256-hex (printed m)))

(defn recorded-hash
  "The model hash a view records on its first line, or nil."
  [text]
  (second (re-find #"model-sha256: ([0-9a-f]{64})(?![0-9a-f])"
                   (or (first (str/split-lines (or text ""))) ""))))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps the spec, the knowledge and the statuses this run publishes."
  [req]
  (let [keep! (fn [k] (fn [payload] (swap! (:state req) assoc k payload) nil))]
    [[:SpecParsed (keep! ::spec)]
     [:KnowledgeIndexed (keep! ::knowledge)]
     [:VerificationDerived (keep! ::statuses)]
     [:SpecGated (keep! ::gated)]]))

;; ---------------------------------------------------------------------------
;; The model's shape, as the site needs it

(defn- kw-name [k] (if (keyword? k) (name k) (str k)))

(defn- page-path
  "A component's page, relative to docs/: its spec path (context/component)."
  [cx-name c]
  (str (or (:spec c) (str cx-name "/" (str/lower-case (str/replace (str (:name c)) #"[^A-Za-z0-9]+" "-"))))
       ".md"))

(defn sections
  "Every context of the model, then the kernel, in the model's order:
  [{:id :name :description :dir :view :components [{:id :name :description
  :spec :page}]}]. The kernel is a section only when the model has one."
  [model]
  (let [views (:views model)
        view-of (fn [cx] (first (filter #(and (= :component (:level %)) (= cx (:context %))) views)))
        section (fn [{:keys [id name description components]} kernel?]
                  (let [dir (if kernel? "kernel" (str name))]
                    {:id id :name (if kernel? "kernel" (str name)) :description description :dir dir
                     :kernel? kernel?
                     :view (when-not kernel? (view-of id))
                     :components (vec (for [c components]
                                        (assoc (select-keys c [:id :name :description :spec])
                                               :page (page-path dir c))))}))]
    (vec (concat
          (for [ct (:containers model) cx (:contexts ct)] (section cx false))
          (when (seq (:kernel model))
            [(section {:id :kernel :name "kernel"
                       :description "Shared code every bounded context may use and that belongs to none of them"
                       :components (:kernel model)}
                      true)])))))

;; ---------------------------------------------------------------------------
;; Markdown

(defn- md-escape [s] (str/replace (str s) #"([\\`*_\[\]<>])" "\\\\$1"))

(defn relative
  "The link from the page `from` to `to`, both relative to docs/."
  [from to]
  (let [base (or (some-> (fs/parent (fs/path from)) str) "")
        up (count (remove str/blank? (str/split base #"/")))]
    (str (apply str (repeat up "../")) to)))

(defn status-note
  "The line every spec page carries about status."
  [{:keys [derived? results]}]
  (if derived?
    (str "_Status derived from the Allure results in `" results "`; the site shows it and never decides it._")
    (str "_No status was derived because no results were given._")))

(defn status-of
  "A scenario's status as the site shows it: derived when there is a
  derivation; planned for a @wip scenario whatever the results; else none."
  [{:keys [derived? statuses]} sc]
  (cond (:wip? sc) "planned"
        (not (:id sc)) "no id"
        (not derived?) "not derived"
        :else (kw-name (get statuses (:id sc) :unverified))))

(defn- demote
  "Prose under a component's page: every heading one level down, outside code."
  [text]
  (let [fence? (atom false)]
    (str/join "\n"
              (for [l (str/split-lines text)]
                (do (when (re-find #"^\s*(```|~~~)" l) (swap! fence? not))
                    (if (and (not @fence?) (re-find #"^#{1,5} " l)) (str "#" l) l))))))

(defn- step-line [{:keys [keyword text doc-string data-table]}]
  (str "- **" (str/trim (str keyword)) "** " (md-escape text)
       (when (string? doc-string) (str "\n\n    ```\n" (str/join "\n" (map #(str "    " %) (str/split-lines doc-string))) "\n    ```\n"))
       (when (seq data-table)
         (str "\n\n    ```\n"
              (str/join "\n" (map #(str "    | " (str/join " | " %) " |") data-table))
              "\n    ```\n"))))

(defn- examples-table [{:keys [header rows]}]
  (when (seq header)
    (str "\n| " (str/join " | " (map md-escape header)) " |\n"
         "|" (str/join "|" (repeat (count header) " --- ")) "|\n"
         (str/join (for [r rows] (str "| " (str/join " | " (map md-escape (:cells r))) " |\n"))))))

(defn scenario-md
  "One scenario of a component page."
  [status sc]
  (str "### " (or (:id sc) "no id") ": " (md-escape (or (:title sc) (:name sc))) "\n\n"
       "**Level:** " (or (some-> (:level sc) kw-name) "none")
       " · **Status:** " (status-of status sc)
       (when (seq (:needs sc)) (str " · **Needs:** " (str/join ", " (:needs sc))))
       "\n\n"
       (str/join "\n" (map step-line (concat (:background sc) (:steps sc)))) "\n"
       (str/join (for [ex (:examples sc) :let [t (examples-table ex)] :when t] (str "\nExamples:\n" t)))))

(defn- view-md [from view views-present]
  (when view
    (if (contains? views-present (:key view))
      (str "![" (md-escape (or (:description view) (:key view))) "](" (relative from (str "views/" (:key view) ".svg")) ")\n")
      (str "_The view " (:key view) " is not rendered._\n"))))

(defn component-page
  [{:keys [status spec-dir-files]} section c scs]
  (let [prose (get spec-dir-files (:spec c))]
    (str "# " (md-escape (:name c)) " (" (if (:kernel? section) "kernel" (str "bounded context " (:name section))) ")\n\n"
         (status-note status) "\n\n"
         (md-escape (:description c)) "\n\n"
         (when prose (str "## Design\n\n" (str/trim (demote prose)) "\n\n"))
         "## Scenarios\n\n"
         (if (seq scs)
           (str/join "\n" (map #(scenario-md status %) scs))
           "No scenario belongs to this component.\n"))))

(defn context-page
  [{:keys [status views-present]} section]
  (let [from (str (:dir section) "/index.md")]
    (str "# " (md-escape (:name section)) "\n\n"
         (status-note status) "\n\n"
         (md-escape (:description section)) "\n\n"
         (when-let [v (view-md from (:view section) views-present)] (str v "\n"))
         "## Components\n\n"
         (str/join (for [c (:components section)]
                     (str "- [" (md-escape (:name c)) "](" (relative from (:page c)) "): "
                          (md-escape (:description c)) "\n"))))))

(defn home-page
  [{:keys [status views-present model needs scenarios sections]}]
  (let [views (:views model)
        page-of (into {} (for [s sections c (:components s)] [(:id c) (:page c)]))
        level-views (fn [lv] (filter #(= lv (:level %)) views))]
    (str "# " (md-escape (get-in model [:system :name] "The spec")) "\n\n"
         (status-note status) "\n\n"
         (when-let [d (get-in model [:system :description])] (str (md-escape d) "\n\n"))
         (str/join (for [[lv title] [[:context "System context"] [:container "Containers"] [:dynamic "Dynamic views"]]
                         :let [vs (level-views lv)]
                         :when (seq vs)]
                     (str "## " title "\n\n" (str/join "\n" (keep #(view-md "index.md" % views-present) vs)) "\n")))
         "## Bounded contexts\n\n"
         (str/join (for [s sections]
                     (str "- [" (md-escape (:name s)) "](" (:dir s) "/index.md): " (md-escape (:description s)) "\n")))
         "\n## User needs\n\n"
         (str/join
          (for [{:keys [id text]} needs
                :let [traced (filter #(some #{id} (:needs %)) scenarios)]]
            (str "### " id "\n\n" (md-escape text) "\n\n"
                 (if (seq traced)
                   (str/join (for [sc traced]
                               (str "- " (if-let [pg (page-of (:component sc))]
                                           (str "[" (or (:id sc) "no id") "](" pg ")")
                                           (or (:id sc) "no id"))
                                    ": " (md-escape (or (:title sc) (:name sc)))
                                    " (" (status-of status sc) ")\n")))
                   "No scenario traces to this need.\n")
                 "\n"))))))

;; ---------------------------------------------------------------------------
;; The wiki

(defn source-url
  "Where a citation's path is at its commit, in its source's repository, or
  nil when the source is not recorded."
  [sources {:keys [source path commit]}]
  (when-let [repo (some #(when (= (str source) (str (:id %))) (:repository %)) sources)]
    (let [base (cond (re-find #"^https?://" repo) (str/replace repo #"(\.git)?/*$" "")
                     (re-matches #"[\w.-]+/[\w.-]+" repo) (str "https://github.com/" repo)
                     :else nil)]
      (when base (str base "/blob/" commit "/" path)))))

(def ^:private citation-re
  #"(?<![\w/.@:-])([A-Za-z0-9][\w.-]*):((?!/)[^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})(?![\w])")

(defn- parse-citation [s]
  (when-let [[_ source path commit] (re-matches citation-re (str s))]
    {:source source :path path :commit commit}))

(defn link-citations
  "The page's text with every citation of a recorded source in a footnote
  definition linked to its source at its commit. `citations` are the page's
  citations as KnowledgeIndexed carries them."
  [text sources citations]
  (let [known (into {} (for [c citations
                             :let [parts (merge (parse-citation (:citation c))
                                                (select-keys c [:source :path :commit]))
                                   url (source-url sources parts)]
                             :when url]
                         [(:citation c) url]))
        link (fn [line]
               (str/replace line #"`?([A-Za-z0-9][\w.-]*:[^\s@`\"'()\[\]<>]+@[0-9a-fA-F]{4,40})`?"
                            (fn [[whole c]]
                              (if-let [url (known c)] (str "[" whole "](" url ")") whole))))]
    (str/join "\n" (map #(if (re-find #"^\s*\[\^[^\]]+\]:" %) (link %) %) (str/split-lines text)))))

;; ---------------------------------------------------------------------------
;; Links

(defn- strip-code [text]
  (-> text
      (str/replace #"(?s)```.*?```" "")
      (str/replace #"`[^`\n]*`" "")))

(defn broken-links
  "[{:line :link}]: every relative link or image of the page `from` (relative
  to docs/) whose target is not among `present` (paths relative to docs/)."
  [from text present]
  (let [base (fs/parent (fs/path from))
        lines (str/split-lines (strip-code text))]
    (vec (for [[i l] (map-indexed vector lines)
               [_ target] (re-seq #"\]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)" l)
               :let [t (first (str/split target #"#"))]
               :when (and (not (str/blank? t)) (not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" t))
                          (not (str/starts-with? t "/")))
               :let [resolved (str (fs/normalize (if base (fs/path base t) (fs/path t))))
                     resolved (str/replace resolved "\\" "/")]
               :when (not (or (contains? present resolved)
                              (contains? present (str (str/replace resolved #"/$" "") "/index.md"))))]
           {:line (inc i) :link target}))))

;; ---------------------------------------------------------------------------
;; The builder's configuration

(defn- yq [s] (str "\"" (str/replace (str/replace (str s) "\\" "\\\\") "\"" "\\\"") "\""))

(defn mkdocs-yml
  "The site builder's configuration: the home page first, then a section per
  context (its page, then its components' pages, in the model's order), then
  the kernel, then the wiki."
  [{:keys [model sections wiki-pages site-dir]}]
  (str "# Written by rdm-spec publish; do not edit.\n"
       "site_name: " (yq (get-in model [:system :name] "The spec")) "\n"
       "docs_dir: docs\n"
       "site_dir: " (yq site-dir) "\n"
       "markdown_extensions:\n  - footnotes\n  - tables\n"
       "nav:\n"
       "  - " (yq "Home") ": index.md\n"
       (str/join (for [s sections]
                   (str "  - " (yq (:name s)) ":\n"
                        "      - " (yq (:name s)) ": " (yq (str (:dir s) "/index.md")) "\n"
                        (str/join (for [c (:components s)]
                                    (str "      - " (yq (:name c)) ": " (yq (:page c)) "\n"))))))
       (when (seq wiki-pages)
         (str "  - " (yq "Wiki") ":\n"
              (str/join (for [{:keys [page title]} wiki-pages]
                          (str "      - " (yq title) ": " (yq page) "\n")))))))

;; ---------------------------------------------------------------------------
;; Reading the inputs

(defn- read-model [req]
  (let [rel (command/config-value req :model)
        f (command/path req rel)]
    (when-not (fs/exists? f) (command/cannot-run! (str "no model " rel)))
    (let [m (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str "the model " rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? m) (command/cannot-run! (str "the model " rel " is not a map")))
      {:model m :file rel})))

(defn view-files
  "{:findings [..] :present #{key}}: the stale views (an error each) and the
  rendered ones, for every view the model declares."
  [req model hash]
  (let [dir (str/replace (command/config-value req :views-dir) #"/+$" "")]
    (reduce
     (fn [acc v]
       (let [k (:key v)
             fs* (for [ext ["mmd" "svg"]
                       :let [rel (str dir "/" k "." ext) f (command/path req rel)]
                       :when (fs/exists? f)]
                   [rel (recorded-hash (slurp (str f)))])
             stale (for [[rel h] fs* :when (not= h hash)]
                     (command/finding rel 1 "site-stale-view"
                                      (str "the view \"" k "\" records the model hash " (or h "none")
                                           ", not the model's " hash "; run \"rdm-spec views\" before publishing")))
             svg? (some #(str/ends-with? (first %) ".svg") fs*)]
         (cond-> (update acc :findings into stale)
           svg? (update :present conj k)
           (not svg?) (update :findings conj
                              (command/warning (str dir "/" k ".svg") nil "site-view-missing"
                                               (str "the view \"" k "\" is not rendered; its page says so"))))))
     {:findings [] :present #{}}
     (:views model))))

(defn- spec-prose
  "{spec-path text}: each component's design prose beside its feature file."
  [req secs]
  (let [spec-dir (str/replace (command/config-value req :spec-dir) #"/+$" "")]
    (into {} (for [s secs c (:components s)
                   :let [sp (:spec c)
                         f (when sp (command/path req (str spec-dir "/" sp ".md")))]
                   :when (and f (fs/regular-file? f))]
               [sp (slurp (str f))]))))

(defn- wiki-pages
  "The wiki's pages as the site holds them: [{:file :page :title :text}]."
  [req knowledge]
  (let [wiki-dir (str/replace (or (get-in req [:config :wiki-dir]) "dhf/wiki") #"/+$" "")
        cites (group-by :file (:citations knowledge))]
    (vec (for [file (sort (distinct (cond-> (vec (:pages knowledge))
                                       (and knowledge (fs/regular-file? (command/path req (str wiki-dir "/index.md"))))
                                       (conj (str wiki-dir "/index.md")))))
               :let [f (command/path req file)]
               :when (and (fs/regular-file? f) (str/starts-with? file (str wiki-dir "/")))
               :let [text (slurp (str f))
                     rel (subs file (inc (count wiki-dir)))
                     title (or (some #(second (re-matches #"#\s+(.+?)\s*" %)) (str/split-lines text)) rel)]]
           {:file file :page (str "wiki/" rel) :title title
            :text (link-citations text (:sources knowledge) (get cites file))}))))

;; ---------------------------------------------------------------------------
;; Building

(defn- build!
  "Runs the configured site builder in the project; its findings."
  [req builder site-dir]
  (let [tool (first builder)]
    (when-not (and (string? tool) (fs/which tool))
      (command/cannot-run! (str "the site builder " (pr-str tool) " (configured :site :builder) is not installed;"
                                " the Markdown was written, no HTML was written")))
    (let [r (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true} builder)]
      (if (zero? (:exit r))
        []
        (let [lines (->> (str/split-lines (str (:err r) "\n" (:out r)))
                         (filter #(re-find #"WARNING|ERROR|Error|Aborted" %))
                         (map str/trim)
                         distinct)]
          (conj (mapv #(command/finding "site-build" %) lines)
                (command/finding "site-build" (str "the site builder " (str/join " " builder) " exited " (:exit r)
                                                   "; no HTML was published to " site-dir))))))))

(defn- write-site! [src-dir yml files]
  (let [docs (fs/file src-dir "docs")]
    (when (fs/exists? docs) (fs/delete-tree docs))
    (fs/create-dirs docs)
    (spit (str (fs/file src-dir "mkdocs.yml")) yml)
    (doseq [[rel content] files
            :let [f (fs/file docs rel)]]
      (fs/create-dirs (fs/parent f))
      (if (string? content)
        (spit (str f) content)
        (fs/copy content f {:replace-existing true})))))

(defn publish
  "The publish task's own stage."
  [req]
  (let [site (command/config-value req :site)
        {:keys [model]} (read-model req)
        hash (model-hash model)
        {view-findings :findings present :present} (view-files req model hash)
        stale (remove command/warning? view-findings)]
    (if (seq stale)
      {:result {:site {:published? false :stale (vec (distinct (map :file stale)))}}
       :findings view-findings}
      (let [st @(:state req)
            spec (or (::spec st)
                     (command/cannot-run! "the spec was not read: no SpecParsed event reached the publishing context"))
            statuses (::statuses st)
            status {:derived? (some? statuses) :statuses (:statuses statuses) :results (:results req)}
            secs (sections model)
            scenarios (:scenarios spec)
            by-comp (group-by :component scenarios)
            cx {:status status :views-present present :model model :needs (:needs spec)
                :scenarios scenarios :sections secs :spec-dir-files (spec-prose req secs)}
            wiki (wiki-pages req (::knowledge st))
            source (:source site)
            src-dir (command/path req source)
            site-dir (str (fs/relativize (fs/absolutize src-dir) (fs/absolutize (command/path req (:dir site)))))
            views-dir (str/replace (command/config-value req :views-dir) #"/+$" "")
            pages (into (sorted-map)
                        (concat
                         [["index.md" (home-page cx)]]
                         (for [s secs] [(str (:dir s) "/index.md") (context-page cx s)])
                         (for [s secs c (:components s)]
                           [(:page c) (component-page cx s c (get by-comp (:id c)))])
                         (for [w wiki] [(:page w) (:text w)])))
            images (into (sorted-map) (for [k present] [(str "views/" k ".svg") (command/path req (str views-dir "/" k ".svg"))]))
            files (merge pages images)
            present-paths (set (keys files))
            link-findings (vec (for [[rel text] pages
                                     {:keys [line link]} (broken-links rel text present-paths)]
                                 (command/finding (str source "/docs/" rel) line "site-broken-link"
                                                  (str "links to " link ", which the site does not hold"))))
            yml (mkdocs-yml {:model model :sections secs :wiki-pages wiki :site-dir site-dir})]
        (write-site! src-dir yml files)
        (let [build-findings (build! req (:builder site) (:dir site))]
          {:result {:site {:published? true
                           :source source
                           :dir (:dir site)
                           :pages (vec (keys pages))
                           :status-derived? (:derived? status)
                           :built? (empty? build-findings)}}
           :findings (vec (concat view-findings link-findings build-findings))})))))
