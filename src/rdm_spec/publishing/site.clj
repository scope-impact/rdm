(ns rdm-spec.publishing.site
  "The Site component of the publishing context
  (dhf/spec/publishing/site.feature): publishes the spec, its views, its
  status and the domain wiki as one site, written as Markdown with the site
  builder's configuration, then built to HTML by the configured site builder
  (dhf/harness/config.edn :site), an external tool rdm-spec writes the input
  of, never the code.

  The site is a reading, never a copy that can drift: it reads what the other
  contexts publish on the run's bus (subscriptions) and the files they own,
  and writes only under the configured :source and :dir. It never decides a
  status (VerificationDerived does) and never decides freshness: publish runs
  the views' freshness check as a gate before the site (the kernel's task
  table), so the site never computes the model hash.

    SpecParsed        every scenario, with its steps, owner and needs
    KnowledgeIndexed  the wiki's pages and the sources' repositories
    SpecGated         whether the spec check passed
    VerificationDerived  each scenario's status (only when results were given)

  What is written, under <source> (:site :source):

    mkdocs.yml                  the builder's configuration: nav follows the model
    docs/index.md               home: context and container views, every need
                                with the scenarios tracing to it
    docs/<context>/index.md     a bounded context: its component view and
                                its components (kernel/ for the kernel)
    docs/<spec>.md              a component: its description, its prose
                                (<spec-dir>/<spec>.md) and every scenario
                                with its id, level, status and steps
    docs/wiki/...               every wiki page, its citations linked to
                                the source at the pin
    docs/views/<key>.svg        each rendered view

  Without results every page says no status was derived because no results
  were given. The same inputs write the same bytes: nothing depends on time
  or on the order of a directory listing.

  Findings (exit 1):
    site-broken-link  a page links to a page the site does not hold (the
                      builder's strict build); no HTML is left behind
    site-build        any other complaint of the builder; no HTML is left
  Cannot run (exit 2): the site builder is not installed, the spec was not
  read (no SpecParsed), the model cannot be read."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; What the other contexts publish

(defn subscriptions
  "Keeps the payload of every event the site reads in the run's state."
  [req]
  (vec (for [e [:SpecParsed :KnowledgeIndexed :SpecGated :VerificationDerived]]
         [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)])))

(defn- received [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; Text helpers

(def no-status-note
  "What every page says when no results were given."
  "No status was derived because no results were given (rdm-spec publish --results DIR).")

(defn- kw-name [x] (cond (keyword? x) (if (namespace x) (str (namespace x) "/" (name x)) (name x))
                         (nil? x) nil
                         :else (str x)))

(defn escape-inline
  "Text safe to put in a Markdown heading or paragraph: no HTML, no emphasis."
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace #"([\\`*_\[\]#])" "\\\\$1")))

(defn yaml-str
  "A YAML scalar: a double-quoted string (YAML reads JSON strings)."
  [s]
  (json/generate-string (str s)))

(defn- fence-for
  "A code fence longer than any run of backticks in `text`."
  [text]
  (let [longest (reduce max 0 (map count (re-seq #"`+" (str text))))]
    (apply str (repeat (max 3 (inc longest)) "`"))))

(defn demote-headings
  "`md` with every ATX heading outside a code fence made `n` levels deeper
  (at most level 6), so prose sits under the page's own headings."
  [md n]
  (loop [[l & more :as ls] (str/split-lines (str md)) fence nil out []]
    (if (empty? ls)
      (str/join "\n" out)
      (let [f (second (re-find #"^\s{0,3}(`{3,}|~{3,})" l))]
        (cond
          (and fence f (str/starts-with? f fence)) (recur more nil (conj out l))
          fence (recur more fence (conj out l))
          f (recur more f (conj out l))
          :else (if-let [[_ hashes rest*] (re-matches #"^(#{1,6})(\s.*|)$" l)]
                  (recur more nil (conj out (str (apply str (repeat (min 6 (+ n (count hashes))) "#")) rest*)))
                  (recur more nil (conj out l))))))))

(defn relative-link
  "The link from the page `from` to the page `to`, both paths under docs/."
  [from to]
  (let [from-dir (butlast (str/split from #"/"))
        to-parts (str/split to #"/")
        common (count (take-while true? (map = from-dir to-parts)))]
    (str/join "/" (concat (repeat (- (count from-dir) common) "..") (drop common to-parts)))))

;; ---------------------------------------------------------------------------
;; The model's pages

(defn- slug [s] (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn- component-page [ctx-dir comp]
  (if (and (string? (:spec comp)) (re-matches #"[^/\s]+(/[^/\s]+)+" (:spec comp)))
    (str (:spec comp) ".md")
    (str ctx-dir "/" (slug (or (:name comp) (some-> (:id comp) name))) ".md")))

(defn sections
  "The site's sections, in the model's order: every bounded context of every
  container, then the kernel when it has components.
  [{:id :name :description :dir :page :view :components [{:id :name :description :spec :page}]}]"
  [model]
  (let [component-views (into {} (for [v (:views model) :when (= :component (:level v))] [(:context v) v]))
        section (fn [id nm description comps]
                  (let [dir (or (some-> id name) (slug nm))]
                    {:id id :name (or nm dir) :description description :dir dir
                     :page (str dir "/index.md") :view (component-views id)
                     :components (mapv #(assoc (select-keys % [:id :name :description :spec])
                                               :page (component-page dir %))
                                       comps)}))]
    (vec (concat
          (for [c (:containers model) ctx (:contexts c)]
            (section (:id ctx) (:name ctx) (:description ctx) (:components ctx)))
          (when (seq (:kernel model))
            [(section :kernel "kernel" "Shared code of the container, in no bounded context" (:kernel model))])))))

(defn nav
  "The site's navigation: the home page first, then one section per bounded
  context and one for the kernel, each listing its own page and then its
  components' pages, in the model's order: [[title page-or-entries]]."
  [secs]
  (vec (cons ["Home" "index.md"]
             (for [s secs]
               [(:name s) (vec (cons [(:name s) (:page s)]
                                     (for [c (:components s)] [(:name c) (:page c)])))]))))

(defn mkdocs-yml
  "The site builder's configuration."
  [{:keys [site-name nav site-dir]}]
  (let [entry (fn entry [indent [title target]]
                (if (string? target)
                  [(str indent "- " (yaml-str title) ": " (yaml-str target))]
                  (cons (str indent "- " (yaml-str title) ":")
                        (mapcat #(entry (str indent "    ") %) target))))]
    (str (str/join "\n"
                   (concat ["# Written by rdm-spec publish from the model; edits are overwritten."
                            (str "site_name: " (yaml-str site-name))
                            "docs_dir: \"docs\""
                            (str "site_dir: " (yaml-str site-dir))
                            "markdown_extensions:"
                            "    - \"attr_list\""
                            "    - \"footnotes\""
                            "    - \"tables\""
                            "    - \"toc\""
                            "nav:"]
                           (mapcat #(entry "    " %) nav)))
         "\n")))

;; ---------------------------------------------------------------------------
;; Status

(defn status-of
  "The status a scenario shows: the derived one when results were given; a
  planned scenario shows planned whatever; else nil (none derived)."
  [statuses scenario]
  (cond
    (:wip? scenario) "planned"
    statuses (some-> (get statuses (:id scenario)) kw-name)
    :else nil))

(defn- status-text [statuses scenario]
  (or (status-of statuses scenario) (if statuses "unverified" "no status derived")))

(defn- banner [{:keys [statuses results]}]
  (if statuses
    (str "> Each scenario's status was derived from the results in `" results "`.")
    (str "> " no-status-note)))

;; ---------------------------------------------------------------------------
;; Pages

(defn- view-image
  "Markdown showing the view `v` from the page `from`, or a note when it was
  not rendered."
  [{:keys [rendered]} from v]
  (when v
    (if (contains? rendered (:key v))
      (str "![" (escape-inline (or (:description v) (:key v))) "]("
           (relative-link from (str "views/" (:key v) ".svg")) ")\n\n"
           "*" (escape-inline (or (:description v) (:key v))) "*")
      (str "The view `" (:key v) "` is not rendered: run rdm-spec views."))))

(defn- scenario-anchor [s] (or (:id s) (str "line-" (:line s))))

(defn scenario-steps
  "A scenario's steps as Gherkin, in a code block: its background, its steps
  (doc strings and data tables included) and an outline's examples."
  [s]
  (let [step-lines (fn [st]
                     (concat [(str (:keyword st) " " (:text st))]
                             (when-let [d (:doc-string st)]
                               (concat ["  \"\"\""] (map #(str "  " %) (str/split-lines (str (if (map? d) (:content d) d)))) ["  \"\"\""]))
                             (when-let [t (:data-table st)]
                               (for [row (if (map? t) (:rows t) t)]
                                 (str "  | " (str/join " | " (if (map? row) (:cells row) row)) " |")))))
        lines (concat (when (seq (:background s))
                        (cons "Background:" (map #(str "  " %) (mapcat step-lines (:background s)))))
                      (mapcat step-lines (:steps s))
                      (for [ex (:examples s)
                            line (concat ["" "Examples:"]
                                         (when (seq (:header ex)) [(str "  | " (str/join " | " (:header ex)) " |")])
                                         (for [r (:rows ex)] (str "  | " (str/join " | " (:cells r)) " |")))]
                        line))
        body (str/join "\n" lines)
        fence (fence-for body)]
    (str fence "gherkin\n" body "\n" fence)))

(defn- scenario-md [opts page s]
  (str/join "\n\n"
            (remove nil?
                    [(str "### " (when (:id s) (str (:id s) ": ")) (escape-inline (:title s))
                          " {#" (scenario-anchor s) "}")
                     (str "**Level:** " (or (some-> (:level s) kw-name) "none")
                          " · **Status:** " (status-text (:statuses opts) s)
                          (when (seq (:needs s))
                            (str " · **Needs:** "
                                 (str/join ", " (for [n (:needs s)]
                                                  (str "[" n "](" (relative-link page "index.md") "#" (str/lower-case n) ")")))))
                          (when (:rule s) (str " · **Rule:** " (escape-inline (:rule s))))
                          " · `" (:file s) ":" (:line s) "`")
                     (scenario-steps s)])))

(defn component-md
  "A component's page."
  [opts section comp scenarios prose]
  (let [page (:page comp)]
    (str (str/join "\n\n"
                   (remove nil?
                           [(str "# " (escape-inline (:name section)) " / " (escape-inline (:name comp)))
                            (banner opts)
                            (when (:description comp) (escape-inline (:description comp)))
                            (str "Bounded context: [" (escape-inline (:name section)) "]("
                                 (relative-link page (:page section)) ")"
                                 (when (:spec comp) (str " · Feature file: `" (:spec-dir opts) "/" (:spec comp) ".feature`")))
                            (when prose (str "## Design\n\n" (str/trim (demote-headings prose 2))))
                            (str "## Scenarios\n\n"
                                 (if (seq scenarios)
                                   (str/join "\n\n" (map #(scenario-md opts page %) scenarios))
                                   "This component owns no scenario yet."))]))
         "\n")))

(defn section-md
  "A bounded context's page (or the kernel's)."
  [opts section]
  (let [page (:page section)]
    (str (str/join "\n\n"
                   (remove nil?
                           [(str "# " (escape-inline (:name section)))
                            (banner opts)
                            (when (:description section) (escape-inline (:description section)))
                            (when (:view section) (str "## Component view\n\n" (view-image opts page (:view section))))
                            (str "## Components\n\n"
                                 (if (seq (:components section))
                                   (str/join "\n" (for [c (:components section)]
                                                    (str "- [" (escape-inline (:name c)) "]("
                                                         (relative-link page (:page c)) ")"
                                                         (when (:description c) (str ": " (escape-inline (:description c)))))))
                                   "None."))]))
         "\n")))

(defn home-md
  "The home page: the context and container views, every user need with the
  scenarios tracing to it, the bounded contexts and the wiki."
  [opts model secs needs scenarios pages-of gated]
  (let [page "index.md"
        view (fn [level] (first (filter #(= level (:level %)) (:views model))))
        system (:system model)
        tracing (fn [need] (filter #(some #{(:id need)} (:needs %)) scenarios))
        link (fn [s] (if-let [p (pages-of s)]
                       (str "[" (or (:id s) "unstamped") "](" (relative-link page p) "#" (scenario-anchor s) ")")
                       (or (:id s) "unstamped")))]
    (str (str/join "\n\n"
                   (remove nil?
                           [(str "# " (escape-inline (or (:name system) "The spec")))
                            (banner opts)
                            (when (:description system) (escape-inline (:description system)))
                            (when (some? gated)
                              (if (:passed? gated)
                                "The spec check passed."
                                (str "The spec check found " (count (:findings gated)) " finding(s): run rdm-spec check.")))
                            (str "## Context view\n\n" (or (view-image opts page (view :context)) "The model has no context view."))
                            (str "## Container view\n\n" (or (view-image opts page (view :container)) "The model has no container view."))
                            (str "## User needs\n\n"
                                 (if (seq needs)
                                   (str/join "\n\n"
                                             (for [n needs
                                                   :let [ts (tracing n)]]
                                               (str "### " (:id n) " {#" (str/lower-case (:id n)) "}\n\n"
                                                    (escape-inline (:text n)) "\n\n"
                                                    (if (seq ts)
                                                      (str/join "\n" (for [s ts]
                                                                       (str "- " (link s) ": " (escape-inline (:title s))
                                                                            " (" (status-text (:statuses opts) s) ")")))
                                                      "No scenario traces to it."))))
                                   "No user need is declared."))
                            (str "## Bounded contexts\n\n"
                                 (if (seq secs)
                                   (str/join "\n" (for [s secs]
                                                    (str "- [" (escape-inline (:name s)) "](" (:page s) ")"
                                                         (when (:description s) (str ": " (escape-inline (:description s)))))))
                                   "None."))
                            (when (:wiki? opts) "## Domain wiki\n\nThe domain, distilled from the raw sources: [the wiki](wiki/index.md).")]))
         "\n")))

;; ---------------------------------------------------------------------------
;; The wiki

(defn source-url
  "The address of `path` at `commit` in the repository `repository`."
  [repository path commit]
  (str (str/replace (str repository) #"(\.git)?/*$" "") "/blob/" commit "/" path))

(defn link-citations
  "`md` with every citation of a known source (`id:path@commit` in
  backticks) linked to the file at that commit in its repository."
  [md repositories]
  (str/replace (str md) #"(?<!\[)`([A-Za-z0-9][A-Za-z0-9._-]*):([^`\s@]+)@([0-9a-fA-F]{4,40})`"
               (fn [[whole id path commit]]
                 (if-let [repo (get repositories id)]
                   (str "[" whole "](" (source-url repo path commit) ")")
                   whole))))

(defn wiki-md
  "A wiki page as published: its citations linked, the banner under its title."
  [opts md repositories]
  (let [linked (link-citations md repositories)
        [first-line & more] (str/split-lines linked)
        b (banner opts)]
    (str (if (and first-line (re-find #"^#\s" first-line))
           (str/join "\n" (concat [first-line "" b] more))
           (str b "\n\n" linked))
         "\n")))

;; ---------------------------------------------------------------------------
;; Building

(def ^:private link-warning
  #"Doc file '([^']+)' contains a link '([^']+)', but the target .*is not found among documentation files.*")

(defn- line-of [text needle]
  (some (fn [[i l]] (when (str/includes? l needle) (inc i))) (map-indexed vector (str/split-lines (str text)))))

(defn builder-findings
  "The builder's complaints as findings: a broken link names the page and the
  link (`docs` the docs directory as the project names it, `read-page` gives
  a page's text); any other warning or error is relayed."
  [output docs read-page]
  (vec (for [l (str/split-lines (str output))
             :let [l (str/trim l)]
             :when (re-find #"^(WARNING|ERROR)\b" l)
             :let [msg (str/trim (str/replace l #"^(WARNING|ERROR)\s*-?\s*" ""))]]
         (if-let [[_ page link] (re-find link-warning msg)]
           (command/finding (str docs "/" page) (line-of (read-page page) (str "](" link))
                            "site-broken-link"
                            (str "the page links to " link ", which the site does not hold"))
           (command/finding nil nil "site-build" (str "the site builder says: " msg))))))

(defn- builder-installed? [req tool]
  (if (str/includes? tool "/")
    (fs/executable? (command/path req tool))
    (some? (fs/which tool))))

(defn- build!
  "Runs the site builder in the project; findings, and no HTML left behind
  when it fails."
  [req builder docs-rel site-dir]
  (let [r (try (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true
                               :env (:env req)}
                      builder)
               (catch Exception e
                 (command/cannot-run! (str "the site builder " (first builder) " could not be run ("
                                           (ex-message e) "), so no HTML was written"))))
        out (str (:out r) "\n" (:err r))]
    (if (zero? (:exit r))
      []
      (do (when (fs/exists? site-dir) (fs/delete-tree site-dir))
          (let [fs* (builder-findings out docs-rel
                                      #(let [f (command/path req (str docs-rel "/" %))]
                                         (when (fs/regular-file? f) (slurp f))))]
            (if (seq fs*)
              fs*
              [(command/finding nil nil "site-build"
                                (str "the site builder " (first builder) " failed (exit " (:exit r) "): "
                                     (str/join " / " (take-last 3 (remove str/blank? (str/split-lines out))))))]))))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- read-model [req]
  (let [rel (command/config-value req :model)
        f (command/path req rel)]
    (when-not (fs/regular-file? f) (command/cannot-run! (str "no model " rel)))
    (let [m (try (edn/read-string (slurp f))
                 (catch Exception e (command/cannot-run! (str "the model " rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? m) (command/cannot-run! (str "the model " rel " is not an EDN map")))
      m)))

(defn- write-page! [docs rel text]
  (let [f (fs/file docs rel)]
    (fs/create-dirs (fs/parent f))
    (spit f text)))

(defn- relative-dir
  "The directory `to` (project-relative or absolute) as the builder's
  configuration in `from` names it."
  [req from to]
  (if (fs/absolute? (str to))
    (str to)
    (str/replace (str (fs/relativize (fs/absolutize (command/path req from))
                                     (fs/absolutize (command/path req to))))
                 "\\" "/")))

(defn publish
  "The site stage of `rdm-spec publish`: writes the site's Markdown and the
  builder's configuration, then builds it."
  [req]
  (let [site (command/config-value req :site)
        {:keys [dir source builder]} site
        _ (when-not (and (string? dir) (string? source) (sequential? builder) (seq builder))
            (command/cannot-run! (str "the configuration (" command/config-file ") :site needs :dir, :source and :builder")))
        tool (str (first builder))
        _ (when-not (builder-installed? req tool)
            (command/cannot-run! (str "the site builder " tool " is not installed (not found on PATH),"
                                      " so no HTML was written")))
        parsed (or (received req :SpecParsed)
                   (command/cannot-run! "the spec was not read: no SpecParsed event reached the site"))
        knowledge (received req :KnowledgeIndexed)
        derived (received req :VerificationDerived)
        gated (received req :SpecGated)
        spec-dir (command/config-value req :spec-dir)
        views-dir (:views-dir (:config req))
        wiki-dir (:wiki-dir (:config req))
        source-dir (command/path req source)
        docs (fs/file source-dir "docs")
        docs-rel (str (str/replace source #"/+$" "") "/docs")
        site-dir (command/path req dir)
        _ (let [abs #(str (fs/normalize (fs/absolutize %)))
                root (abs (:root req))
                inside? (fn [a b] (or (= a b) (str/starts-with? a (str b "/"))))]
            (doseq [[k d] [[:source source-dir] [:dir site-dir]]
                    :when (or (inside? root (abs d)) (fs/exists? (fs/file d "dhf")))]
              (command/cannot-run! (str "the configured site " k " " (get site k) " holds the project; publish"
                                        " replaces what it writes there, so it must be a directory of its own")))
            (when (inside? (abs source-dir) (abs site-dir))
              (command/cannot-run! (str "the configured site :source " source " is inside its :dir " dir))))
        model (read-model req)
        rendered (into (sorted-set)
                       (for [v (:views model)
                             :when (and views-dir (fs/regular-file? (command/path req (str views-dir "/" (:key v) ".svg"))))]
                         (:key v)))
        wiki-index (when wiki-dir (str (str/replace wiki-dir #"/+$" "") "/index.md"))
        wiki-pages (vec (distinct (concat (when (and wiki-index (fs/regular-file? (command/path req wiki-index))) [wiki-index])
                                          (sort (:pages knowledge)))))
        opts {:statuses (:statuses derived) :results (:results req) :rendered rendered
              :spec-dir spec-dir :wiki? (boolean (seq wiki-pages))}
        secs (sections model)
        scenarios (:scenarios parsed)
        by-component (group-by :component scenarios)
        page-of-component (into {} (for [s secs c (:components s)] [(:id c) (:page c)]))
        pages-of (fn [s] (page-of-component (:component s)))
        repositories (into {} (for [s (:sources knowledge)] [(str (:id s)) (:repository s)]))]
    (when (fs/exists? docs) (fs/delete-tree docs))
    (fs/create-dirs docs)
    (doseq [k rendered]
      (fs/create-dirs (fs/file docs "views"))
      (fs/copy (command/path req (str views-dir "/" k ".svg")) (fs/file docs "views" (str k ".svg"))
               {:replace-existing true}))
    (write-page! docs "index.md" (home-md opts model secs (:needs parsed) scenarios pages-of gated))
    (doseq [s secs]
      (write-page! docs (:page s) (section-md opts s))
      (doseq [c (:components s)
              :let [prose-f (when (:spec c) (command/path req (str spec-dir "/" (:spec c) ".md")))
                    prose (when (and prose-f (fs/regular-file? prose-f)) (slurp prose-f))]]
        (write-page! docs (:page c) (component-md opts s c (get by-component (:id c)) prose))))
    (doseq [w wiki-pages
            :let [f (command/path req w)]
            :when (fs/regular-file? f)]
      (write-page! docs (str "wiki/" (str (fs/relativize (fs/absolutize (command/path req wiki-dir)) (fs/absolutize f))))
                   (wiki-md opts (slurp f) repositories)))
    (spit (fs/file source-dir "mkdocs.yml")
          (mkdocs-yml {:site-name (or (get-in model [:system :name]) "spec")
                       :nav (nav secs)
                       :site-dir (relative-dir req source dir)}))
    (let [findings (build! req builder docs-rel site-dir)]
      {:result {:site {:source source :dir dir :built? (empty? findings)
                       :pages (count (fs/glob docs "**.md"))
                       :status (if derived :derived :none)}}
       :findings (cond-> findings
                   (nil? knowledge) (conj (command/warning "site-no-wiki"
                                                           "no KnowledgeIndexed event reached the site, so the wiki was not published")))})))
