(ns rdm-spec.publishing.site
  "Site, the one component of the publishing context
  (dhf/spec/publishing/site.feature): `rdm-spec publish [--results DIR]`
  writes the spec, its views and its status as Markdown and the site
  builder's configuration under the configured :site :source, then runs the
  configured :site :builder, which writes the HTML to :site :dir.

  What it reads:
    the model (the configured :model)  the contexts, components, kernel and views
    SpecParsed                         every scenario and every user need
    SpecGated                          whether the spec check passed
    KnowledgeIndexed                   the sources' repositories and the citations
    VerificationDerived                each scenario's status, when results were given
    the views (the configured :views-dir), a component's prose
    (dhf/spec/<context>/<component>.md) and the wiki (the configured :wiki-dir)
  It never writes under the spec, and never decides a status: with no
  results, no status is derived and every page says so (a scenario tagged
  @wip is planned whatever any result says).

  The site:
    <source>/mkdocs.yml                  the builder's configuration: home, then one
                                         section per bounded context in the model's
                                         order, then the kernel, then the wiki
    <source>/docs/index.md               home: the context, container and dynamic
                                         views, the contexts, every user need with
                                         the scenarios tracing to it
    <source>/docs/<context>/index.md     a context: its component view, its components
    <source>/docs/<context>/<comp>.md    a component: description, prose, scenarios
    <source>/docs/kernel/...             the same for the kernel, when the model has one
    <source>/docs/views/<key>.svg        every rendered view
    <source>/docs/wiki/...               every wiki page, its citations linked to the
                                         cited file at its commit in its source's repository

  Freshness (glossary: Model hash): a view is fresh when the first line of
  each of its outputs (<key>.mmd and <key>.svg) records `model-sha256: <hex>`
  with the current model hash: the SHA-256, lowercase hex, of the model read
  as EDN and printed back with every map's keys sorted. Publishing refuses a
  stale or missing view and then writes nothing.

  The rules, each a finding's rule name:
    site-view-stale     a view whose outputs record another model hash (exit 1)
    site-view-missing   a view of the model with no output (exit 1)
    site-broken-link    a page links to a page the site does not hold (exit 1)
    site-build          the site builder failed otherwise (exit 1)
  The task cannot run (exit 2) without a readable model, without the spec
  having been read, or without the site builder installed (then the Markdown
  is written and no HTML)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps the payload of every event the site reads in the run's state."
  [req]
  (vec (for [e [:SpecParsed :SpecGated :KnowledgeIndexed :VerificationDerived]]
         [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)])))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; The model hash and the views' freshness

(defn- hex [^bytes d] (apply str (map #(format "%02x" (bit-and % 0xff)) d)))

(defn sha256 [^bytes bs] (hex (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)))

(defn- key-compare
  "Orders map keys: by value when they compare, else by their printed form."
  [a b]
  (try (compare a b)
       (catch Exception _ (compare (pr-str a) (pr-str b)))))

(defn printed-sorted
  "`x` printed back as EDN with every map's keys sorted."
  [x]
  (binding [*print-namespace-maps* false *print-meta* false *print-length* nil *print-level* nil]
    (pr-str (walk/postwalk #(if (map? %) (into (sorted-map-by key-compare) %) %) x))))

(defn model-hash
  "The model hash of the model value `m` (glossary: Model hash)."
  [m]
  (sha256 (.getBytes ^String (printed-sorted m) "UTF-8")))

(defn recorded-hash
  "The model hash the first line of a view's output records, or nil."
  [text]
  (some->> (first (str/split-lines (str text)))
           (re-find #"(?<![\w-])model-sha256:\s*([0-9a-f]{64})(?![0-9a-f])")
           second))

(def view-exts
  "Every output of a view: its diagram source and its rendering."
  ["mmd" "svg"])

(defn- describe-view [v] (str "view " (pr-str (:key v))))

(defn view-findings
  "A finding for every output of every view of model `m` that is missing or
  records a hash other than `h`. `read-file` returns a file's text or nil."
  [m h views-dir read-file]
  (vec
   (for [v (:views m)
         ext view-exts
         :let [rel (str views-dir "/" (:key v) "." ext)
               text (read-file rel)
               recorded (when text (recorded-hash text))]
         :when (not= h recorded)]
     (if (nil? text)
       (command/finding rel nil "site-view-missing"
                        (str (describe-view v) " has no " ext " output, so the site cannot show it; run rdm-spec views"))
       (command/finding rel 1 "site-view-stale"
                        (str (describe-view v) " is older than the model: it records "
                             (or recorded "no model hash") ", the model's hash is " h "; run rdm-spec views"))))))

;; ---------------------------------------------------------------------------
;; Markdown

(defn esc
  "`s` as literal Markdown text on one line."
  [s]
  (-> (str s)
      (str/replace #"[\r\n]+" " ")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace #"([\\`*_\[\]{}#|!])" "\\\\$1")))

(defn- link [text target] (str "[" (esc text) "](" target ")"))

(defn relative
  "The link from the page `from` to the page or file `to`, both relative to
  the docs directory."
  [from to]
  (let [depth (count (re-seq #"/" from))]
    (str (apply str (repeat depth "../")) to)))

(defn- page-dir [rel] (str/join "/" (butlast (str/split rel #"/"))))

;; ---------------------------------------------------------------------------
;; The site, as a value

(defn- ctx-slug [c] (or (:name c) (name (:id c))))

(defn- comp-path [slug comp]
  (str (or (:spec comp) (str slug "/" (name (:id comp)))) ".md"))

(defn sections
  "The bounded contexts of model `m`, then the kernel when it has one:
  [{:id :name :description :page :kernel? :components [{:id :name
  :description :spec :page}]}], in the model's order."
  [m]
  (let [ctxs (for [c (:containers m) ctx (:contexts c)] ctx)
        section (fn [id nm desc comps kernel?]
                  {:id id :name nm :description desc :kernel? kernel?
                   :page (str nm "/index.md")
                   :components (mapv #(assoc (select-keys % [:id :name :description :spec])
                                             :page (comp-path nm %))
                                     comps)})]
    (vec (concat
          (for [c ctxs] (section (:id c) (ctx-slug c) (:description c) (:components c) false))
          (when (seq (:kernel m))
            [(section :kernel "kernel" "Shared code no bounded context owns" (:kernel m) true)])))))

(defn- status-text
  "How a scenario's status reads on the site."
  [sc statuses]
  (cond
    (nil? (:id sc)) "none: the scenario carries no single id"
    statuses (name (get statuses (:id sc) :unverified))
    (:wip? sc) "planned"
    :else "no status was derived: no results were given"))

(defn banner
  "The line every page carries about status."
  [{:keys [statuses results]}]
  (if statuses
    (str "> Status is derived from the Allure results in `" results "`; the site never decides it.")
    "> No status was derived: no results were given (`rdm-spec publish --results DIR`)."))

(defn- fence-for [lines]
  (let [longest (reduce max 0 (map count (mapcat #(re-seq #"`+" %) lines)))]
    (apply str (repeat (max 3 (inc longest)) "`"))))

(defn scenario-md
  "A scenario's section of its component page."
  [sc statuses]
  (let [title (or (:title sc) (:name sc))
        head (str "### " (when (:id sc) (str (:id sc) ": ")) (esc title)
                  (when (:id sc) (str " {#" (:id sc) "}")))
        steps (mapv #(str (:keyword %) " " (:text %)) (:steps sc))
        examples (for [ex (:examples sc)
                       :let [rows (cons (:header ex) (map :cells (:rows ex)))]]
                   (cons "Examples:"
                         (for [r rows] (str "  | " (str/join " | " r) " |"))))
        body (concat steps (when (seq examples) (cons "" (apply concat (interpose [""] examples)))))
        fence (fence-for body)]
    (str/join "\n"
              (concat
               [head ""
                (str "- **Level:** " (some-> (:level sc) name))
                (str "- **Status:** " (status-text sc statuses))
                (str "- **Needs:** " (if (seq (:needs sc)) (str/join ", " (:needs sc)) "none"))]
               (when (:rule sc) [(str "- **Rule:** " (esc (:rule sc)))])
               [(str "- **Feature file:** `" (:file sc) ":" (:line sc) "`")
                ""
                (str fence "text")]
               body
               [fence ""]))))

(defn component-page
  "The page of component `comp` of `section`."
  [section comp scs prose site]
  (str/join "\n"
            (concat
             [(str "# " (esc (:name comp)) " (" (esc (:name section)) ")")
              ""
              (str "Component " (esc (:name comp)) " of "
                   (if (:kernel? section) "the kernel" (str "the bounded context " (esc (:name section))))
                   ": " (link (:name section) "index.md") ".")
              ""
              (esc (:description comp))
              ""
              (banner site)
              ""]
             (when prose ["## Design" "" (str/trimr prose) ""])
             [(str "## Scenarios") ""]
             (if (seq scs)
               (map #(scenario-md % (:statuses site)) scs)
               ["No scenario belongs to this component." ""]))))

(defn context-page
  "The index page of `section`, showing `view` (a view of the model, or nil)."
  [section view site]
  (str/join "\n"
            (concat
             [(str "# " (esc (:name section)))
              ""
              (str (if (:kernel? section) "The kernel. " "Bounded context. ") (esc (:description section)))
              ""
              (banner site)
              ""]
             (when view
               ["## Component view" ""
                (str "![" (esc (:description view)) "](" (relative (:page section) (str "views/" (:key view) ".svg")) ")")
                ""])
             ["## Components" ""]
             (for [c (:components section)]
               (str "- " (link (:name c) (subs (:page c) (inc (count (page-dir (:page c)))))) ": " (esc (:description c))))
             [""])))

(defn citation-url
  "Where a citation's file is, at its commit, in its source's repository, or
  nil when the source has no web repository."
  [repository path commit]
  (when (and repository (re-find #"^https?://" repository) path commit)
    (str (str/replace repository #"(\.git)?/*$" "") "/blob/" commit "/" path)))

(defn link-citations
  "`text` with every citation `cites` names ({citation url}) linked to its url."
  [text cites]
  (reduce (fn [t [c url]]
            (let [q (java.util.regex.Pattern/quote c)
                  u (str/re-quote-replacement url)]
              (-> t
                  (str/replace (re-pattern (str "(?<!\\[)`" q "`")) (str "[`" (str/re-quote-replacement c) "`](" u ")"))
                  (str/replace (re-pattern (str "(?<![`\\[\\w/])" q "(?![`\\]\\w])"))
                               (str "[" (str/re-quote-replacement c) "](" u ")")))))
          text
          (sort-by (comp - count key) cites)))

(defn- citations-by-file
  "{file {citation url}} from KnowledgeIndexed."
  [k]
  (let [repos (into {} (map (juxt (comp str :id) :repository)) (:sources k))]
    (reduce (fn [acc {:keys [citation file]}]
              ;; KnowledgeIndexed carries the citation as written,
              ;; id:path@commit (dhf/spec/events.edn), so its parts are read here.
              (let [[_ source path commit] (some->> citation (re-matches #"([^:\s]+):([^@\s]+)@([0-9a-f]{7,40})"))]
                (if-let [url (when source (citation-url (get repos source) path commit))]
                  (assoc-in acc [file citation] url)
                  acc)))
            {} (:citations k))))

(defn home-page
  "The home page."
  [m sections scs needs site]
  (let [view-of (fn [lvl] (filter #(= lvl (:level %)) (:views m)))
        img (fn [v] (str "![" (esc (:description v)) "](views/" (:key v) ".svg)"))
        page-of (into {} (for [s sections c (:components s)] [(:id c) (:page c)]))
        cite-urls (into {} (map (fn [[_ cs]] cs)) (:citations site))
        gated (:gated site)]
    (str/join
     "\n"
     (concat
      [(str "# " (esc (get-in m [:system :name] "The spec")))
       ""
       (esc (get-in m [:system :description]))
       ""
       (banner site)
       ""]
      (when gated
        [(if (:passed? gated)
           "The spec check passed."
           (str "The spec check found " (count (remove #(= :warning (:severity %)) (:findings gated)))
                " findings: run `rdm-spec check`."))
         ""])
      (when-let [c (:counts site)]
        [(str "Scenarios: " (str/join ", " (for [k [:verified :failed :unverified :planned]] (str (get c k 0) " " (name k)))) ".")
         ""])
      (for [[title lvl] [["System context" :context] ["Containers" :container] ["Dynamic views" :dynamic]]
            :let [vs (view-of lvl)]
            :when (seq vs)
            line (concat [(str "## " title) ""] (mapcat (fn [v] [(img v) ""]) vs))]
        line)
      ["## Bounded contexts" ""]
      (for [s sections] (str "- " (link (:name s) (:page s)) ": " (esc (:description s))))
      ["" "## User needs" ""]
      (mapcat
       (fn [n]
         (let [tracing (filter #(some #{(:id n)} (:needs %)) scs)]
           (concat
            [(str "### " (:id n) " {#" (str/lower-case (:id n)) "}") "" (esc (:text n)) ""]
            (when (seq (:sources n))
              [(str "Sources: "
                    (str/join ", " (for [c (:sources n)]
                                     (if-let [u (get cite-urls c)] (str "[`" c "`](" u ")") (str "`" c "`")))))
               ""])
            (if (seq tracing)
              (concat ["Scenarios tracing to it:" ""]
                      (for [sc tracing
                            :let [id (or (:id sc) "unstamped")
                                  pg (get page-of (:component sc))]]
                        (str "- " (if pg (str "[" id "](" pg (when (:id sc) (str "#" id)) ")") id)
                             " " (esc (or (:title sc) (:name sc)))
                             (when (or (:statuses site) (:wip? sc))
                               (str ": " (status-text sc (:statuses site)))))))
              ["No scenario traces to it."])
            [""])))
       needs)
      (when (:wiki site)
        ["## Domain wiki" "" (str "The domain, distilled from the raw sources: " (link "the wiki" "wiki/index.md") ".") ""])))))

(defn wiki-page
  "A wiki page as published: its citations linked, the status line last."
  [text cites site]
  (str (str/trimr (link-citations text cites)) "\n\n" (banner site) "\n"))

(defn- yq [s] (pr-str (str s)))

(defn builder-config
  "The site builder's configuration (YAML): docs in docs/, HTML to `site-dir`
  (relative to the configuration), the navigation in the model's order."
  [site-name site-dir sections wiki-pages]
  (str/join
   "\n"
   (concat
    [(str "site_name: " (yq site-name))
     "docs_dir: docs"
     (str "site_dir: " (yq site-dir))
     "markdown_extensions:"
     "  - toc"
     "  - tables"
     "  - fenced_code"
     "  - footnotes"
     "  - attr_list"
     "nav:"
     (str "  - " (yq "Home") ": " (yq "index.md"))]
    (mapcat (fn [s]
              (concat [(str "  - " (yq (:name s)) ":")
                       (str "      - " (yq (:name s)) ": " (yq (:page s)))]
                      (for [c (:components s)]
                        (str "      - " (yq (:name c)) ": " (yq (:page c))))))
            sections)
    (when (seq wiki-pages)
      (concat [(str "  - " (yq "wiki") ":")]
              (for [[title page] wiki-pages]
                (str "      - " (yq title) ": " (yq page)))))
    [""])))

;; ---------------------------------------------------------------------------
;; Reading the project

(defn- read-text [req rel]
  (let [f (command/path req rel)]
    (when (fs/regular-file? f) (slurp (str f)))))

(defn- read-model [req]
  (let [rel (command/config-value req :model)
        text (or (read-text req rel) (command/cannot-run! (str "no model " rel ": the site follows the model")))
        m (try (edn/read-string text)
               (catch Exception e (command/cannot-run! (str "the model " rel " is not readable EDN: " (ex-message e)))))]
    (when-not (map? m) (command/cannot-run! (str "the model " rel " is not a map")))
    m))

(defn- wiki-files
  "The wiki's Markdown files, relative to the wiki directory: index first."
  [req]
  (when-let [w (get-in req [:config :wiki-dir])]
    (let [d (command/path req w)]
      (when (fs/directory? d)
        (let [all (sort (map #(str/replace (str (fs/relativize d %)) "\\" "/")
                             (filter fs/regular-file? (fs/glob d "**.md"))))]
          (vec (concat (filter #{"index.md"} all) (remove #{"index.md"} all))))))))

(defn- first-heading [text]
  (some #(second (re-matches #"#\s+(.+?)\s*#*\s*" %)) (str/split-lines (str text))))

(defn site-pages
  "Every file of the site: {rel-under-source text}. Pure but for `read-text`."
  [{:keys [model sections scenarios needs site wiki read-text views-dir spec-dir source-rel site-rel]}]
  (let [by-comp (group-by :component scenarios)
        comp-view (into {} (for [v (:views model) :when (= :component (:level v))] [(:context v) v]))
        wiki-pages (for [f wiki]
                     [(if (= "index.md" f) "wiki" (or (first-heading (read-text (str (:wiki-dir site) "/" f))) f))
                      (str "wiki/" f)])
        docs (concat
              [["index.md" (home-page model sections scenarios needs (assoc site :wiki (seq wiki)))]]
              (for [s sections]
                [(:page s) (context-page s (when-not (:kernel? s) (comp-view (:id s))) site)])
              (for [s sections c (:components s)]
                [(:page c) (component-page s c (get by-comp (:id c))
                                           (when (:spec c) (read-text (str spec-dir "/" (:spec c) ".md")))
                                           site)])
              (for [f wiki
                    :let [rel (str (:wiki-dir site) "/" f)]]
                [(str "wiki/" f) (wiki-page (read-text rel) (get-in site [:citations rel]) site)]))]
    (into (sorted-map)
          (concat
           (for [[rel text] docs] [(str "docs/" rel) text])
           (for [v (:views model)]
             [(str "docs/views/" (:key v) ".svg") (read-text (str views-dir "/" (:key v) ".svg"))])
           [["mkdocs.yml" (builder-config (get-in model [:system :name] "Site")
                                          (relative (str source-rel "/x") site-rel)
                                          sections wiki-pages)]]))))

;; ---------------------------------------------------------------------------
;; The site builder

(defn broken-link-findings
  "The broken links the builder's output names, as findings on the pages
  (`docs-rel` is the docs directory relative to the project)."
  [output docs-rel]
  (vec (distinct
        (for [[_ page target] (re-seq #"Doc file '([^']+)' contains (?:a link|an unrecognized relative link) '([^']+)'" output)]
          (command/finding (str docs-rel "/" page) nil "site-broken-link"
                           (str "the page " page " links to " target ", which the site does not hold"))))))

(defn- installed? [exe]
  (if (str/includes? exe "/")
    (and (fs/regular-file? exe) (fs/executable? exe))
    (some? (fs/which exe))))

(defn- build!
  "Runs the configured site builder in the project root: {:findings [...]}."
  [req builder source-rel site-rel]
  (let [[exe & args] builder]
    (when-not (and (string? exe) (installed? exe))
      (command/cannot-run! (str "the site builder " exe " is not installed (not found on PATH): the Markdown was written to "
                                source-rel ", but no HTML was written to " site-rel)))
    (let [r (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true} exe args)
                 (catch java.io.IOException e
                   (command/cannot-run! (str "the site builder " exe " could not be run (" (ex-message e) "): no HTML was written"))))
          output (str (:err r) "\n" (:out r))]
      (if (zero? (:exit r))
        {:findings []}
        (let [links (broken-link-findings output (str source-rel "/docs"))
              warnings (filter #(re-find #"^\s*(WARNING|ERROR)\b" %) (str/split-lines output))]
          {:findings
           (if (seq links)
             links
             [(command/finding "site-build"
                               (str "the site builder " (str/join " " builder) " exited " (:exit r) ": "
                                    (if (seq warnings)
                                      (str/join "; " (map str/trim warnings))
                                      (str/trim (str/join " " (take-last 5 (str/split-lines output)))))))])})))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- clear! [req source-rel]
  (let [src (command/path req source-rel)]
    (fs/delete-tree (fs/file src "docs"))
    (fs/delete-if-exists (fs/file src "mkdocs.yml"))))

(defn- write! [req source-rel files]
  (doseq [[rel text] files
          :let [f (command/path req (str source-rel "/" rel))]]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn publish
  "Publish's own stage: checks the views are fresh, writes the site and
  builds it."
  [req]
  (let [config (:config req)
        {source-rel :source site-rel :dir builder :builder :as site-cfg} (command/config-value req :site)
        _ (when-not (and (string? source-rel) (string? site-rel) (sequential? builder) (seq builder))
            (command/cannot-run! (str "the configuration's :site " (pr-str site-cfg)
                                      " needs :source, :dir and :builder")))
        m (read-model req)
        h (model-hash m)
        views-dir (str/replace (or (:views-dir config) "dhf/views") #"/+$" "")
        stale (view-findings m h views-dir #(read-text req %))]
    (if (seq stale)
      {:result {:site {:written false :model-sha256 h
                       :refused (str "views older than the model; run rdm-spec views")}}
       :findings stale}
      (let [parsed (or (event req :SpecParsed)
                       (command/cannot-run! "the spec was not read: no SpecParsed event reached the site"))
            derived (event req :VerificationDerived)
            statuses (when (and (:results req) derived) (:statuses derived))
            counts (when statuses (frequencies (vals statuses)))
            secs (sections m)
            site {:statuses statuses
                  :results (:results req)
                  :counts counts
                  :gated (event req :SpecGated)
                  :citations (citations-by-file (event req :KnowledgeIndexed))
                  :wiki-dir (str/replace (or (:wiki-dir config) "dhf/wiki") #"/+$" "")}
            files (site-pages {:model m
                               :sections secs
                               :scenarios (:scenarios parsed)
                               :needs (:needs parsed)
                               :site site
                               :wiki (wiki-files req)
                               :read-text #(read-text req %)
                               :views-dir views-dir
                               :spec-dir (str/replace (or (:spec-dir config) "dhf/spec") #"/+$" "")
                               :source-rel source-rel
                               :site-rel site-rel})]
        (clear! req source-rel)
        (write! req source-rel files)
        (let [{:keys [findings]} (build! req builder source-rel site-rel)]
          {:result {:site {:written true
                           :source source-rel
                           :html site-rel
                           :built (empty? findings)
                           :builder (vec builder)
                           :model-sha256 h
                           :statuses-derived (boolean statuses)
                           :pages (vec (for [k (keys files) :when (str/ends-with? k ".md")] (subs k 5)))
                           :files (into (sorted-map)
                                        (for [[k text] files]
                                          [k (sha256 (.getBytes ^String text "UTF-8"))]))}}
           :findings findings})))))
