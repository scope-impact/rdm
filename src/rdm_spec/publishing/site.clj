(ns rdm-spec.publishing.site
  "Site (dhf/spec/publishing/site.feature): `rdm-spec publish [--results
  DIR]` writes the site's Markdown and the site builder's configuration
  under the configured :site :source, then runs the configured :site
  :builder, which writes the HTML into :site :dir.

  It reads the spec from SpecParsed, the sources from KnowledgeIndexed and
  the statuses from VerificationDerived (published by the stages the task
  table runs before it, quietly), the model and the views from the
  repository; it never decides a status and never writes in the spec. The
  views' freshness check runs as the task's gate, so the site never computes
  the model hash itself.

  The pages, under <source>/docs:
    index.md                 the system, the context and container views,
                             every bounded context, the wiki, and every user
                             need with the scenarios tracing to it
    <context>/index.md       a bounded context: its component view and a
                             link to each of its components' pages
    <spec>.md                a component (<context>/<component>, as the
                             model's :spec names it): its description, its
                             design prose (## Design) and every scenario with
                             its id, level, status and steps (## Scenarios)
    kernel/index.md, kernel/<component>.md   the kernel, as a context is
    wiki/...                 every wiki file, its index included, each
                             footnote's citation linked to the source at its pin
    views/<key>.svg          every rendered view
  Without results every page says that no status was derived because no
  results were given; a planned scenario shows planned either way.

  A component's design prose keeps its own headings, every one shifted down
  so that its top level sits one level under the page's \"## Design\" (a
  prose \"# Title\" is published as \"### Title\"); headings inside fenced
  code are left alone.

  The navigation: the home page, then one section per bounded context in
  the model's order (its page, then its components' pages in the model's
  order), then the kernel, then the wiki.

  Findings: site-link-broken (a page linking to a page the site does not
  hold, named with the page and the link; no HTML is left), site-build (the
  builder failed otherwise), model-unreadable. Cannot run (exit 2) when the
  site builder is not installed: no HTML is left either. Every path a page
  names is relative to the project."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def no-status "no status was derived because no results were given")

(def default-site {:dir "site" :source "site-src"
                   :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]})

;; ---------------------------------------------------------------------------
;; Events

(def events [:SpecParsed :SpecGated :KnowledgeIndexed :VerificationDerived])

(defn subscriptions
  "Keeps every event the site reads in the run's state."
  [req]
  (for [e events]
    [e (fn [payload] (swap! (:state req) assoc-in [::events e] payload) nil)]))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; Pure: Markdown

(defn escape
  "Text for a heading or a paragraph: an angle bracket that would open an
  HTML tag is written as an entity."
  [s]
  (str/replace (str s) "<" "&lt;"))

(defn shift-headings
  "The Markdown `text` with every heading outside fenced code shifted so
  that its top level becomes `top` (capped at 6)."
  [text top]
  (let [lines (str/split-lines (str text))
        fence? #(re-find #"^\s*(```|~~~)" %)
        levels (loop [[l & more] lines in-code false out []]
                 (if (nil? l) out
                     (cond (fence? l) (recur more (not in-code) out)
                           in-code (recur more in-code out)
                           :else (recur more in-code (if-let [h (re-find #"^#{1,6}(?= )" l)] (conj out (count h)) out)))))
        shift (if (seq levels) (- top (apply min levels)) 0)]
    (str/join "\n"
              (loop [[l & more] lines in-code false out []]
                (if (nil? l) out
                    (cond (fence? l) (recur more (not in-code) (conj out l))
                          in-code (recur more in-code (conj out l))
                          :else (recur more in-code
                                       (conj out (if-let [h (re-find #"^#{1,6}(?= )" l)]
                                                   (str (apply str (repeat (min 6 (max 1 (+ (count h) shift))) "#"))
                                                        (subs l (count h)))
                                                   l)))))))))

(defn rel-link
  "The relative link from the page `from` to the file `to`, both relative
  to the docs directory."
  [from to]
  (let [base (or (fs/parent from) "")]
    (str/replace (str (.relativize (.normalize (.toPath (fs/file (str "/r/" base))))
                                   (.normalize (.toPath (fs/file (str "/r/" to))))))
                 "\\" "/")))

(def citation-re #"([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})")

(defn source-url
  "The URL of `path` at `commit` in the repository `repo`."
  [repo path commit]
  (let [r (-> (str repo) (str/replace #"\.git$" "") (str/replace #"/+$" ""))
        r (if (re-find #"^[a-z]+://" r) r (str "https://github.com/" r))]
    (str r "/blob/" commit "/" path)))

(defn link-citations
  "The footnote definition lines of `text` with every citation of a known
  source (`repos` {id repository}) linked to its file at its pin."
  [text repos]
  (str/join "\n"
            (for [l (str/split-lines (str text))]
              (if (re-find #"^\s*\[\^[^\]]+\]:" l)
                (str/replace l (re-pattern (str "`?" citation-re "`?"))
                             (fn [[whole src path commit]]
                               (if-let [repo (get repos src)]
                                 (str "[`" src ":" path "@" commit "`](" (source-url repo path commit) ")")
                                 whole)))
                l))))
    ;; split-lines drops a final newline; pages end with one when written

(defn with-note
  "`text` with the status note after its first heading (at its top when it
  has none)."
  [text note]
  (if-not note
    text
    (let [lines (str/split-lines (str text))
          i (first (keep-indexed (fn [i l] (when (re-find #"^# " l) i)) lines))]
      (if i
        (str/join "\n" (concat (take (inc i) lines) ["" note] (drop (inc i) lines)))
        (str note "\n\n" text)))))

(defn yaml-str [s] (json/generate-string (str s)))

;; ---------------------------------------------------------------------------
;; Pages

(defn status-text
  "A scenario's status as its page shows it."
  [sc statuses]
  (cond
    (:wip? sc) "planned"
    (nil? statuses) "not derived"
    (nil? (:id sc)) "no id"
    :else (name (get statuses (:id sc) :unverified))))

(defn- fence-for [lines] (if (some #(str/includes? % "```") lines) "~~~~" "```"))

(defn- step-lines [st]
  (concat [(str (:keyword st) " " (:text st))]
          (when-let [d (:doc-string st)] (concat ["  \"\"\""] (map #(str "  " %) (str/split-lines (str d))) ["  \"\"\""]))
          (when-let [t (:data-table st)]
            (for [row t] (str "  | " (str/join " | " (if (map? row) (:cells row) row)) " |")))))

(defn- examples-lines [sc]
  (for [ex (:examples sc)
        l (concat ["" "Examples:"]
                  [(str "  | " (str/join " | " (:header ex)) " |")]
                  (for [row (:rows ex)] (str "  | " (str/join " | " (:cells row)) " |")))]
    l))

(defn scenario-md
  "One scenario's section."
  [sc statuses]
  (let [lines (vec (concat (mapcat step-lines (:background sc)) (mapcat step-lines (:steps sc)) (examples-lines sc)))
        fence (fence-for lines)]
    (str "### " (or (:id sc) "(no id)") ": " (escape (or (:title sc) (:name sc))) "\n\n"
         (str/join " · " (remove nil? [(when (:level sc) (str "`@" (name (:level sc)) "`"))
                                       (str "status: **" (status-text sc statuses) "**")
                                       (when (seq (:needs sc)) (str "needs: " (str/join ", " (:needs sc))))]))
         "\n\n"
         (when (:rule sc) (str "Rule: " (escape (:rule sc)) "\n\n"))
         fence "gherkin\n" (str/join "\n" lines) "\n" fence "\n")))

(defn component-page
  "A component's page."
  [{:keys [context-name component prose scenarios statuses note]}]
  (str "# " (escape (:name component)) " (" (escape context-name) ")\n\n"
       (when note (str note "\n\n"))
       (escape (:description component)) "\n\n"
       (when prose (str "## Design\n\n" (str/trim (shift-headings prose 3)) "\n\n"))
       "## Scenarios\n\n"
       (if (seq scenarios)
         (str/join "\n" (map #(scenario-md % statuses) scenarios))
         "The component's feature file holds no scenario.\n")))

(defn context-page
  "A bounded context's page, at `page`: its view (when rendered) and its
  components."
  [{:keys [page title description view components note]}]
  (str "# " (escape title) "\n\n"
       (when note (str note "\n\n"))
       (when description (str (escape description) "\n\n"))
       (when view (str "![" (:key view) "](" (rel-link page (str "views/" (:key view) ".svg")) ")\n\n"))
       "## Components\n\n"
       (str/join (for [c components]
                   (str "- [" (escape (:name c)) "](" (rel-link page (str (:spec c) ".md")) "): "
                        (escape (:description c)) "\n")))))

(defn home-page
  [{:keys [system views sections wiki? needs scenarios statuses page-of note]}]
  (str "# " (escape (:name system)) "\n\n"
       (when note (str note "\n\n"))
       (when (:description system) (str (escape (:description system)) "\n\n"))
       (str/join (for [v views] (str "![" (:key v) "](views/" (:key v) ".svg)\n\n")))
       "## Bounded contexts\n\n"
       (str/join (for [{:keys [title page description]} sections]
                   (str "- [" (escape title) "](" page ")" (when description (str ": " (escape description))) "\n")))
       (when wiki? "\n## Domain wiki\n\n- [The domain wiki](wiki/index.md): the domain, distilled from the raw sources\n")
       "\n## User needs\n\n"
       (str/join "\n"
                 (for [{:keys [id text]} needs
                       :let [tracing (filter #(some #{id} (:needs %)) scenarios)]]
                   (str "### " id "\n\n" (escape text) "\n\n"
                        (if (seq tracing)
                          (str/join (for [sc tracing]
                                      (str "- " (if-let [pg (page-of sc)] (str "[" (or (:id sc) "(no id)") "](" pg ")") (or (:id sc) "(no id)"))
                                           ": " (escape (or (:title sc) (:name sc))) " (" (status-text sc statuses) ")\n")))
                          "No scenario traces to it.\n"))))))

;; ---------------------------------------------------------------------------
;; Reading the project

(defn- read-edn-file [f]
  (when (fs/regular-file? f)
    (try (edn/read-string (slurp (str f))) (catch Exception _ ::unreadable))))

(defn- model-contexts
  "[{:id :name :description :dir :components [..]}] in the model's order,
  the kernel last (when it has components)."
  [model]
  (concat (for [c (:containers model) x (:contexts c)]
            {:id (:id x) :name (or (:name x) (name (:id x))) :description (:description x)
             :dir (name (:id x)) :components (vec (filter :spec (:components x)))})
          (when (seq (:kernel model))
            [{:id :kernel :name "kernel" :description "Shared code no bounded context owns"
              :dir "kernel" :components (vec (filter :spec (:kernel model)))}])))

(defn- wiki-files [req]
  (let [d (command/path req (command/config-value req :wiki-dir))]
    (if (fs/directory? d)
      (vec (sort (for [f (fs/glob d "**.md") :when (fs/regular-file? f)] (str (fs/relativize d f)))))
      [])))

(defn- title-of [text fallback]
  (or (some #(second (re-matches #"# (.+)" %)) (str/split-lines (str text))) fallback))

;; ---------------------------------------------------------------------------
;; Writing and building

(defn- write! [docs rel text]
  (let [f (fs/file docs rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (if (str/ends-with? text "\n") text (str text "\n")))))

(defn- nav-yaml [nav]
  (str/join
   (for [[title target] nav]
     (if (string? target)
       (str "  - " (yaml-str title) ": " target "\n")
       (str "  - " (yaml-str title) ":\n"
            (str/join (for [[t p] target] (str "      - " (yaml-str t) ": " p "\n"))))))))

(defn builder-config
  "The site builder's configuration."
  [{:keys [site-name site-dir nav]}]
  (str "# Written by rdm-spec publish from the model; never edited by hand.\n"
       "site_name: " (yaml-str site-name) "\n"
       "docs_dir: docs\n"
       "site_dir: " (yaml-str site-dir) "\n"
       "use_directory_urls: false\n"
       "markdown_extensions:\n  - footnotes\n  - tables\n"
       "nav:\n" (nav-yaml nav)))

(defn- inside? [parent child]
  (let [p (str (fs/normalize (fs/absolutize parent))) c (str (fs/normalize (fs/absolutize child)))]
    (or (= p c) (str/starts-with? c (str p "/")))))

(defn- generated-dir!
  "The configured directory `rel`, refused when deleting it could delete
  the project or its spec."
  [req rel what]
  (let [d (command/path req rel)
        root (:root req)]
    (when (or (str/blank? (str rel)) (not (inside? root d)) (= (str (fs/normalize (fs/absolutize root)))
                                                               (str (fs/normalize (fs/absolutize d))))
              (inside? d (command/path req (command/config-value req :spec-dir)))
              (inside? d (command/path req command/config-file)))
      (command/cannot-run! (str "the configured :site " what " " (pr-str rel)
                                " is not a directory of its own inside the project")))
    d))

(defn- clear-html! [d] (when (fs/exists? d) (fs/delete-tree d)))

(def broken-link-re
  #"Doc file '([^']+)' contains a link '([^']+)', but the target '([^']+)' is not found among documentation files\.?")

(defn build-findings
  "The findings in the builder's output `text` when it failed: each broken
  link at its page (`docs` the docs directory, relative to the project),
  else every warning or error line."
  [docs text exit]
  (let [lines (map str/trim (str/split-lines (str text)))
        broken (for [l lines :let [[_ page link target] (re-find broken-link-re l)] :when page]
                 (command/finding (str docs "/" page) nil "site-link-broken"
                                  (str "the page links to " link ", which the site does not hold (" docs "/" target ")")))
        other (for [l lines
                    :when (re-find #"^(WARNING|ERROR)\b" l)
                    :when (not (re-find broken-link-re l))]
                (command/finding "site-build" (str/replace l #"^(WARNING|ERROR)\s*-\s*" "")))]
    (vec (if (or (seq broken) (seq other))
           (concat broken other)
           [(command/finding "site-build" (str "the site builder failed (exit " exit "): "
                                                (or (last (remove str/blank? lines)) "it said nothing")))]))))

(defn publish
  "The site stage: writes the site and builds it."
  [req]
  (let [site (merge default-site (get-in req [:config :site]))
        builder (vec (:builder site))
        source-rel (str/replace (str (:source site)) #"/+$" "")
        dir-rel (str/replace (str (:dir site)) #"/+$" "")
        source (generated-dir! req source-rel ":source")
        out (generated-dir! req dir-rel ":dir")
        _ (when (empty? builder) (command/cannot-run! "the configuration's :site names no :builder"))
        _ (when-not (fs/which (first builder))
            (clear-html! out)
            (command/cannot-run! (str "the site builder " (first builder) " is not installed (not on PATH),"
                                      " so no HTML was written; " dir-rel " holds none")))
        model-rel (command/config-value req :model)
        model (read-edn-file (command/path req model-rel))]
    (if-not (map? model)
      {:result {} :findings [(command/finding model-rel nil "model-unreadable"
                                              "the model cannot be read, so the site cannot follow it")]}
      (let [parsed (or (event req :SpecParsed)
                       (command/cannot-run! "the spec was not read: no SpecParsed event reached the site"))
            statuses (:statuses (event req :VerificationDerived))
            derived? (some? (event req :VerificationDerived))
            note (when-not derived? (str "> No status is shown: " no-status "."))
            repos (into {} (for [s (:sources (event req :KnowledgeIndexed))] [(name (:id s)) (:repository s)]))
            spec-dir (command/config-value req :spec-dir)
            views-dir (command/config-value req :views-dir)
            rendered (fn [v] (fs/regular-file? (command/path req (str views-dir "/" (:key v) ".svg"))))
            views (filterv rendered (:views model))
            contexts (model-contexts model)
            scenarios (:scenarios parsed)
            by-comp (group-by :component scenarios)
            page-of (fn [sc] (some (fn [x] (some #(when (= (:id %) (:component sc)) (str (:spec %) ".md")) (:components x)))
                                   contexts))
            wiki (wiki-files req)
            wiki-dir (command/config-value req :wiki-dir)
            docs-rel (str source-rel "/docs")
            docs (fs/file source "docs")
            pages (atom [])
            page! (fn [rel text] (write! docs rel text) (swap! pages conj rel))]
        (when (fs/exists? docs) (fs/delete-tree docs))
        (fs/create-dirs docs)
        ;; the views
        (doseq [v views]
          (fs/create-dirs (fs/file docs "views"))
          (fs/copy (command/path req (str views-dir "/" (:key v) ".svg")) (fs/file docs "views" (str (:key v) ".svg"))
                   {:replace-existing true}))
        ;; components and contexts
        (doseq [x contexts]
          (doseq [c (:components x)
                  :let [prose-f (command/path req (str spec-dir "/" (:spec c) ".md"))]]
            (page! (str (:spec c) ".md")
                   (component-page {:context-name (:name x) :component c
                                    :prose (when (fs/regular-file? prose-f) (slurp (str prose-f)))
                                    :scenarios (get by-comp (:id c)) :statuses statuses :note note})))
          (page! (str (:dir x) "/index.md")
                 (context-page {:page (str (:dir x) "/index.md") :title (:name x) :description (:description x)
                                :view (first (filter #(and (= :component (:level %)) (= (:id x) (:context %))) views))
                                :components (:components x) :note note})))
        ;; the wiki
        (doseq [w wiki
                :let [text (slurp (str (command/path req (str wiki-dir "/" w))))]]
          (page! (str "wiki/" w) (with-note (link-citations text repos) note)))
        ;; home
        (page! "index.md"
               (home-page {:system (:system model)
                           :views (filter #(#{:context :container} (:level %)) views)
                           :sections (for [x contexts] {:title (:name x) :page (str (:dir x) "/index.md")
                                                        :description (:description x)})
                           :wiki? (some #{"index.md"} wiki)
                           :needs (:needs parsed) :scenarios scenarios :statuses statuses
                           :page-of page-of :note note}))
        (spit (str (fs/file source "mkdocs.yml"))
              (builder-config
               {:site-name (or (get-in model [:system :name]) "rdm-spec")
                :site-dir (str (fs/relativize (fs/absolutize source) (fs/absolutize out)))
                :nav (concat [["Home" "index.md"]]
                             (for [x contexts]
                               [(:name x) (cons [(:name x) (str (:dir x) "/index.md")]
                                                (for [c (:components x)] [(:name c) (str (:spec c) ".md")]))])
                             (when (some #{"index.md"} wiki)
                               [["wiki" (cons ["wiki" "wiki/index.md"]
                                              (for [w wiki :when (not= "index.md" w)]
                                                [(title-of (slurp (str (command/path req (str wiki-dir "/" w)))) w)
                                                 (str "wiki/" w)]))]]))}))
        ;; build
        (clear-html! out)
        (let [r (try (apply p/shell {:dir (:root req) :out :string :err :string :continue true :in ""
                                     :env (or (:env req) (into {} (System/getenv)))}
                            builder)
                     (catch java.io.IOException e
                       (clear-html! out)
                       (command/cannot-run! (str "the site builder " (first builder) " could not be run, so no HTML was"
                                                 " written: " (ex-message e)))))
              result {:site {:source source-rel :dir dir-rel :pages (count @pages) :derived? derived?
                             :built? (zero? (:exit r))}}]
          (if (zero? (:exit r))
            {:result result :findings []}
            (do (clear-html! out)
                {:result result
                 :findings (build-findings docs-rel (str (:err r) "\n" (:out r)) (:exit r))})))))))
