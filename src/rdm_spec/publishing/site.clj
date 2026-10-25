(ns rdm-spec.publishing.site
  "The Site component of the publishing context (dhf/spec/publishing/site.feature):
  `rdm-spec publish [--results DIR]` writes the site as Markdown and the site
  builder's configuration under the configured :site :source, then runs the
  configured :site :builder, which writes the HTML into :site :dir.

  What it reads. The spec, the knowledge and the statuses arrive as events
  (`subscriptions`): SpecParsed (every scenario with its owner), SpecGated,
  KnowledgeIndexed (the sources, to link wiki footnotes) and
  VerificationDerived (only when the run has results). From the repository
  it reads the model (for the pages and the navigation), each component's
  design prose (`<spec>.md` beside its feature file), the rendered views and
  the wiki's pages. It writes nothing under the spec.

  The pages, under <source>/docs:
    index.md                    home: the context and container views, every
                                user need with the scenarios tracing to it
    <context>/index.md          a bounded context: its component view, links
                                to its components' pages
    <context>/<component>.md    a component (named by its :spec): description,
                                prose, every scenario with id, level, status
                                and steps
    kernel/...                  the same for the kernel's components
    wiki/...                    every wiki page, footnote citations linked to
                                the cited file at its commit in its repository
    views/<key>.svg             the rendered views
  Without results every page says no status was derived; a scenario tagged
  @wip shows planned whatever the results.

  The rules, each a stable name on the error stream:
    view-stale        a view records another hash than the model's (nothing
                      is written)
    view-missing      a view of the model has no rendering (nothing is written)
    site-broken-link  the builder found a link to a page the site does not hold
    site-build-failed the builder failed for another reason
  A failed build leaves no HTML in the site directory; a builder that is not
  installed cannot run (exit 2) and no HTML is written."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps the payloads of the events the site reads in the run's state."
  [req]
  (let [keep! (fn [k] (fn [payload] (swap! (:state req) assoc-in [::events k] payload) nil))]
    [[:SpecParsed (keep! :spec)]
     [:SpecGated (keep! :gate)]
     [:KnowledgeIndexed (keep! :knowledge)]
     [:VerificationDerived (keep! :verification)]]))

(defn- event [req k] (some-> (:state req) deref ::events k))

;; ---------------------------------------------------------------------------
;; The model hash (glossary: Model hash)

(defn- printed
  [x]
  (binding [*print-namespace-maps* false *print-meta* false *print-length* nil *print-level* nil]
    (pr-str x)))

(defn- by-printed [a b] (compare (printed a) (printed b)))

(defn- canonical
  [x]
  (cond
    (map? x) (into (sorted-map-by by-printed) (map (fn [[k v]] [(canonical k) (canonical v)])) x)
    (set? x) (into (sorted-set-by by-printed) (map canonical) x)
    (vector? x) (mapv canonical x)
    (seq? x) (apply list (map canonical x))
    :else x))

(defn model-hash
  "The SHA-256, lowercase hex, of the model `m` printed back with every map's
  entries and every set's elements sorted by their printed form."
  [m]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256")
                   (.getBytes ^String (printed (canonical m)) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn recorded-hash
  "The model hash a view's first line records, or nil."
  [first-line]
  (second (re-find #"model-sha256:\s*([0-9a-f]{64})(?![0-9a-f])" (str first-line))))

;; ---------------------------------------------------------------------------
;; The model, as the site reads it

(defn contexts
  "The site's sections, in the model's order: every bounded context of every
  container, then the kernel when it has components.
  [{:id :name :description :key :kernel? :components [{:id :name :description :page}]}]."
  [model]
  (let [page-of (fn [ctx-key c]
                  (str (or (:spec c) (str ctx-key "/" (name (:id c)))) ".md"))
        section (fn [ctx kernel?]
                  (let [k (if kernel? "kernel" (name (:id ctx)))]
                    {:id (:id ctx) :name (or (:name ctx) k) :description (:description ctx)
                     :key k :kernel? kernel? :index (str k "/index.md")
                     :components (vec (for [c (:components ctx)]
                                        (assoc (select-keys c [:id :name :description :spec])
                                               :page (page-of k c))))}))]
    (vec (concat (for [cn (:containers model) ctx (:contexts cn)] (section ctx false))
                 (when (seq (:kernel model))
                   [(section {:id :kernel :name "kernel" :description "Shared code in no bounded context"
                              :components (:kernel model)}
                             true)])))))

(defn- view-of [model pred] (first (filter pred (:views model))))

;; ---------------------------------------------------------------------------
;; Markdown helpers

(defn- rel-link
  "The relative link from the page `from` to `to` (both under docs/)."
  [from to]
  (let [from-dir (vec (butlast (str/split from #"/")))
        to-parts (str/split to #"/")
        common (count (take-while true? (map = from-dir (butlast to-parts))))]
    (str (apply str (repeat (- (count from-dir) common) "../"))
         (str/join "/" (drop common to-parts)))))

(defn- esc [s] (-> (str s) (str/replace "|" "\\|") (str/replace "\n" " ")))

(def no-status-note
  (str "> **No status was derived** because no results were given:"
       " publish with `--results DIR` to show each scenario's status."))

(defn- status-note [results]
  (if results
    (str "> Status derived from the results in `" results "`.")
    no-status-note))

(defn- with-note
  "`text` with `note` after its first heading (or first, with none)."
  [text note]
  (let [lines (str/split-lines (str text))
        i (first (keep-indexed (fn [i l] (when (re-find #"^#\s" l) i)) lines))]
    (if i
      (str (str/join "\n" (concat (take (inc i) lines) ["" note ""] (drop (inc i) lines))) "\n")
      (str note "\n\n" text))))

(defn- image [page key title]
  (str "![" (esc title) "](" (rel-link page (str "views/" key ".svg")) ")"))

;; ---------------------------------------------------------------------------
;; Status

(defn status-of
  "A scenario's status on the site: planned when tagged @wip, else the
  derived one, else nil (no status was derived)."
  [sc statuses]
  (cond (:wip? sc) :planned
        statuses (get statuses (:id sc) :unverified)
        :else nil))

(defn- status-text [st]
  (if st (name st) "no status derived"))

;; ---------------------------------------------------------------------------
;; Pages

(defn- scenario-md [sc statuses]
  (let [id (:id sc)
        title (or (:title sc) (:name sc) "")
        steps (concat (:background sc) (:steps sc))]
    (str/join
     "\n"
     (concat
      [(str "### " title (when id (str " {#" id "}")))
       ""
       (str (if id (str "`" id "`") "unstamped")
            " · level: " (if (:level sc) (name (:level sc)) "none")
            " · status: **" (status-text (status-of sc statuses)) "**"
            (when (seq (:needs sc)) (str " · needs: " (str/join ", " (:needs sc)))))
       ""]
      (for [st steps]
        (str "- **" (:keyword st) "** " (esc (:text st))
             (when-let [d (:doc-string st)]
               (str "\n\n    ```\n" (str/join "\n" (map #(str "    " %) (str/split-lines d))) "\n    ```\n"))
             (when-let [t (seq (:data-table st))]
               (str "\n\n" (str/join "\n" (map #(str "    | " (str/join " | " (map esc %)) " |") t)) "\n"))))
      (when-let [rows (seq (:rows sc))]
        (let [header (vec (keys (:values (first rows))))]
          (concat [""
                   "Examples:"
                   ""
                   (str "| " (str/join " | " (map esc header)) " |")
                   (str "|" (str/join "|" (repeat (count header) "---")) "|")]
                  (for [r rows] (str "| " (str/join " | " (map #(esc (get (:values r) %)) header)) " |")))))
      [""]))))

(defn component-page
  "The Markdown of a component's page."
  [{:keys [section component prose scenarios statuses note]}]
  (let [page (:page component)]
    (str/join
     "\n"
     (concat
      [(str "# " (:name component))
       ""
       note
       ""
       (str "Component of the bounded context [" (esc (:name section)) "](" (rel-link page (:index section)) ")"
            (when (:spec component) (str ", specified in `" (:spec component) ".feature`")) ".")
       ""
       (str "_" (esc (:description component)) "_")
       ""]
      (when (seq (str/trim (str prose)))
        ["## Design" "" (str/trim prose) ""])
      ["## Scenarios" ""]
      (if (seq scenarios)
        (let [groups (partition-by (juxt :feature :rule) scenarios)]
          (for [g groups]
            (str (when-let [r (:rule (first g))] (str "**Rule: " (esc r) "**\n\n"))
                 (str/join "\n" (map #(scenario-md % statuses) g)))))
        ["No scenario."])
      [""]))))

(defn context-page
  "The Markdown of a bounded context's page (the kernel's included)."
  [{:keys [section view note]}]
  (let [page (:index section)]
    (str/join
     "\n"
     (concat
      [(str "# " (:name section))
       ""
       note
       ""
       (str "_" (esc (:description section)) "_")
       ""]
      (when view
        ["## Component view" "" (image page (:key view) (or (:description view) (:key view))) ""])
      ["## Components" ""]
      (for [c (:components section)]
        (str "- [" (esc (:name c)) "](" (rel-link page (:page c)) "): " (esc (:description c))))
      [""]))))

(defn home-page
  "The Markdown of the home page."
  [{:keys [model sections needs scenarios statuses note gate wiki? page-of]}]
  (let [page "index.md"
        ctx-view (view-of model #(= :context (:level %)))
        cnt-view (view-of model #(= :container (:level %)))
        dynamic (filter #(= :dynamic (:level %)) (:views model))
        by-need (fn [nid] (filter #(some #{nid} (:needs %)) scenarios))]
    (str/join
     "\n"
     (concat
      [(str "# " (or (get-in model [:system :name]) "The spec"))
       ""
       note
       ""
       (str "_" (esc (get-in model [:system :description])) "_")
       ""]
      (when gate
        [(if (:passed? gate)
           "The spec check passed."
           (str "The spec check found " (count (remove #(= :warning (:severity %)) (:findings gate)))
                " problem(s): run `rdm-spec check`."))
         ""])
      (when ctx-view ["## Context view" "" (image page (:key ctx-view) (or (:description ctx-view) "context")) ""])
      (when cnt-view ["## Container view" "" (image page (:key cnt-view) (or (:description cnt-view) "containers")) ""])
      (mapcat (fn [v] [(str "## " (esc (or (:description v) (:key v)))) "" (image page (:key v) (:key v)) ""]) dynamic)
      ["## Bounded contexts" ""]
      (for [s sections] (str "- [" (esc (:name s)) "](" (:index s) "): " (esc (:description s))))
      [""]
      (when wiki? ["The [domain wiki](wiki/index.md) holds the domain the spec rests on." ""])
      ["## User needs" ""]
      (mapcat (fn [{:keys [id text]}]
                (let [scs (by-need id)]
                  [(str "### " id " {#" id "}")
                   ""
                   (esc text)
                   ""
                   (if (seq scs)
                     (str "Scenarios: "
                          (str/join ", "
                                    (for [sc scs]
                                      (let [label (or (:id sc) (:title sc))]
                                        (if-let [pg (page-of sc)]
                                          (str "[" label "](" pg (when (:id sc) (str "#" (:id sc))) ")"
                                               " (" (status-text (status-of sc statuses)) ")")
                                          (str label " (" (status-text (status-of sc statuses)) ")"))))))
                     "Scenarios: none.")
                   ""]))
              needs)))))

;; ---------------------------------------------------------------------------
;; The wiki

(def citation-re #"([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})")

(defn source-url
  "Where `path` at `commit` is read in `repository`."
  [repository path commit]
  (let [repo (str/replace (str repository) #"(\.git)?/*$" "")]
    (str repo "/blob/" commit "/" path)))

(defn link-footnotes
  "`text` with every citation in a footnote definition linked to the cited
  file at its commit in its source's repository (`repos`: {source-id repository})."
  [text repos]
  (let [link (fn [[whole src path commit]]
               (if-let [repo (get repos src)]
                 (str "[`" whole "`](" (source-url repo path commit) ")")
                 (str "`" whole "`")))]
    (str/join
     "\n"
     (for [l (str/split-lines (str text))]
       (if (re-find #"^\s*\[\^[^\]]+\]:" l)
         (-> l
             (str/replace #"`([^`]*)`" (fn [[w inner]] (if (re-matches citation-re inner) inner w)))
             (str/replace citation-re link))
         l)))))

;; ---------------------------------------------------------------------------
;; The site builder's configuration

(defn- y [s] (json/generate-string (str s)))

(defn mkdocs-config
  "The site builder's configuration: the home page, then a section per
  bounded context and the kernel, each its context's page then its
  components' pages, in the model's order; then the wiki."
  [{:keys [site-name sections wiki-pages site-dir]}]
  (str/join
   "\n"
   (concat
    ["# Written by rdm-spec publish from the model; do not edit."
     (str "site_name: " (y site-name))
     "docs_dir: docs"
     (str "site_dir: " (y site-dir))
     "use_directory_urls: false"
     "theme:"
     "  name: mkdocs"
     "markdown_extensions:"
     "  - footnotes"
     "  - tables"
     "  - attr_list"
     "nav:"
     (str "  - " (y "Home") ": index.md")]
    (mapcat (fn [s]
              (concat [(str "  - " (y (:name s)) ":")
                       (str "      - " (y (:name s)) ": " (y (:index s)))]
                      (for [c (:components s)]
                        (str "      - " (y (:name c)) ": " (y (:page c))))))
            sections)
    (when (seq wiki-pages)
      (concat ["  - \"wiki\":"]
              (for [w wiki-pages] (str "      - " (y w)))))
    [""])))

;; ---------------------------------------------------------------------------
;; Building the pages

(defn- read-model [req]
  (let [rel (command/config-value req :model)
        f (command/path req rel)]
    (when-not (fs/regular-file? f) (command/cannot-run! (str "no model: " rel " does not exist")))
    (let [m (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str "the model " rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? m) (command/cannot-run! (str "the model " rel " is not a map")))
      {:model m :file rel})))

(defn- first-line [f]
  (with-open [r (clojure.java.io/reader (str f))]
    (or (first (line-seq r)) "")))

(defn freshness-findings
  "A finding for every view of `model` whose rendering is missing, or whose
  outputs record another hash than `h`."
  [req model h views-dir]
  (vec
   (for [v (:views model)
         ext ["mmd" "svg"]
         :let [rel (str views-dir "/" (:key v) "." ext)
               f (command/path req rel)
               exists? (fs/regular-file? f)]
         :when (or (and exists? (not= h (recorded-hash (first-line f))))
                   (and (not exists?) (= "svg" ext)))]
     (if exists?
       (command/finding rel 1 "view-stale"
                        (str "the view " (pr-str (:key v)) " is stale: it records "
                             (or (recorded-hash (first-line f)) "no model hash")
                             ", the model's hash is " h "; run rdm-spec views before publishing"))
       (command/finding rel nil "view-missing"
                        (str "the view " (pr-str (:key v)) " has no rendering; run rdm-spec views before publishing"))))))

(defn- wiki-files [req]
  (let [rel (get-in req [:config :wiki-dir])
        dir (when rel (command/path req rel))]
    (if (and dir (fs/directory? dir))
      (vec (sort (for [f (fs/glob dir "**.md") :when (fs/regular-file? f)]
                   (str/replace (str (fs/relativize dir f)) "\\" "/"))))
      [])))

(defn pages
  "Every file of the site's docs directory: {path content}, and :sources
  {page source-file} for the pages written from a file of the project."
  [req {:keys [model]}]
  (let [spec (event req :spec)
        statuses (some-> (event req :verification) :statuses)
        results (when statuses (:results req))
        note (status-note results)
        sections (contexts model)
        spec-dir (command/config-value req :spec-dir)
        scenarios (vec (:scenarios spec))
        by-comp (group-by :component scenarios)
        page-by-comp (into {} (for [s sections c (:components s)] [(:id c) (:page c)]))
        page-of (fn [sc] (get page-by-comp (:component sc)))
        views-dir (get-in req [:config :views-dir])
        comp-pages (for [s sections c (:components s)
                         :let [prose-rel (when (:spec c) (str spec-dir "/" (:spec c) ".md"))
                               prose-f (when prose-rel (command/path req prose-rel))
                               prose (when (and prose-f (fs/regular-file? prose-f)) (slurp (str prose-f)))]]
                     [(:page c) (component-page {:section s :component c :prose prose
                                                 :scenarios (get by-comp (:id c)) :statuses statuses
                                                 :note note})
                      (when prose prose-rel)])
        ctx-pages (for [s sections]
                    [(:index s) (context-page {:section s :note note
                                               :view (view-of model #(and (= :component (:level %))
                                                                          (= (:id s) (:context %))))})
                     nil])
        wiki-rel (get-in req [:config :wiki-dir])
        repos (into {} (for [s (:sources (event req :knowledge))] [(:id s) (:repository s)]))
        wiki (for [w (wiki-files req)
                   :let [src (str wiki-rel "/" w)]]
               [(str "wiki/" w)
                (with-note (link-footnotes (slurp (str (command/path req src))) repos) note)
                src])
        home ["index.md" (home-page {:model model :sections sections :needs (:needs spec)
                                     :scenarios scenarios :statuses statuses :note note
                                     :gate (event req :gate) :wiki? (some #(= "wiki/index.md" (first %)) wiki)
                                     :page-of page-of})
              nil]
        all (concat [home] ctx-pages comp-pages wiki)]
    {:pages (into (sorted-map) (map (juxt first second) all))
     :sources (into (sorted-map) (keep (fn [[p _ s]] (when s [p s])) all))
     :views (vec (for [v (:views model)] (str views-dir "/" (:key v) ".svg")))
     :sections sections
     :wiki-pages (vec (for [[p] wiki] p))}))

;; ---------------------------------------------------------------------------
;; Building the site

(defn broken-link-findings
  "The broken links the builder's output names: [{:page :link}]."
  [output]
  (vec (distinct
        (for [[_ page link] (re-seq #"Doc file '([^']+)' contains a link '([^']+)'" (str output))]
          {:page page :link link}))))

(defn- relative-dir
  "The site directory as the configuration file in `source` reaches it."
  [source dir]
  (let [depth (count (remove #{"." ""} (str/split source #"/")))]
    (str (apply str (repeat depth "../")) dir)))

(defn- html? [dir]
  (and (fs/directory? dir) (seq (fs/glob dir "**.html"))))

(defn- clear! [dir] (when (fs/exists? dir) (fs/delete-tree dir)))

(defn- write-source!
  [req source {:keys [pages views sections wiki-pages]} model site-dir-rel]
  (let [src (command/path req source)
        docs (fs/file src "docs")]
    (clear! docs)
    (doseq [[rel text] pages
            :let [f (fs/file docs rel)]]
      (fs/create-dirs (fs/parent f))
      (spit f text))
    (doseq [v views
            :let [f (command/path req v)]
            :when (fs/regular-file? f)]
      (fs/create-dirs (fs/file docs "views"))
      (fs/copy f (fs/file docs "views" (fs/file-name f)) {:replace-existing true}))
    (spit (fs/file src "mkdocs.yml")
          (mkdocs-config {:site-name (or (get-in model [:system :name]) "spec")
                          :sections sections :wiki-pages wiki-pages
                          :site-dir (relative-dir source site-dir-rel)}))))

(defn publish
  "The site's stage of `rdm-spec publish`."
  [req]
  (let [site (command/config-value req :site)
        source (or (:source site) (command/cannot-run! "the configuration's :site sets no :source"))
        site-dir (or (:dir site) (command/cannot-run! "the configuration's :site sets no :dir"))
        builder (:builder site)
        _ (when-not (and (sequential? builder) (seq builder))
            (command/cannot-run! "the configuration's :site sets no :builder"))
        {:keys [model]} (read-model req)
        h (model-hash model)
        stale (freshness-findings req model h (command/config-value req :views-dir))]
    (if (seq stale)
      {:result {:site {:published? false
                       :stale (vec (distinct (keep #(second (re-find #"the view \"([^\"]+)\"" (:message %))) stale)))}}
       :findings stale}
      (let [built (pages req {:model model})
            out-dir (command/path req site-dir)]
        (write-source! req source built model site-dir)
        (clear! out-dir)
        (when-not (fs/which (first builder))
          (command/cannot-run! (str "the site builder " (first builder) " is not installed (not found on the path):"
                                    " the Markdown is in " source ", and no HTML was written")))
        (let [r (apply p/shell {:dir (:root req) :out :string :err :string :continue true} (map str builder))
              output (str (:out r) "\n" (:err r))
              ok? (zero? (:exit r))]
          (if ok?
            {:result {:site {:published? true :built? true :source source :dir site-dir
                             :pages (count (:pages built))}}
             :findings []}
            (let [broken (broken-link-findings output)
                  docs-rel (str source "/docs")]
              (clear! out-dir)
              {:result {:site {:published? true :built? false :source source :dir site-dir}}
               :findings
               (if (seq broken)
                 (vec (for [{:keys [page link]} broken]
                        (command/finding (str docs-rel "/" page) nil "site-broken-link"
                                         (str "the page " page " links to " link ", which the site does not hold"
                                              (when-let [s (get (:sources built) page)] (str " (written from " s ")"))
                                              "; no HTML was left in " site-dir))))
                 [(command/finding nil nil "site-build-failed"
                                   (str "the site builder " (str/join " " builder) " exited " (:exit r)
                                        "; no HTML was left in " site-dir ": "
                                        (str/join " " (take-last 5 (remove str/blank? (str/split-lines output))))))])})))))))
