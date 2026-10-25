(ns rdm-spec.publishing.site
  "Site (dhf/spec/publishing/site.feature): the spec, its views, its status
  and the domain wiki published as one site of Markdown, built to HTML by the
  configured site builder (configuration :site). rdm-spec writes the
  builder's configuration and the pages, never the builder's code.

  The site is a reading of the spec. It reads what the run's other stages
  publish (SpecParsed, SpecGated, KnowledgeIndexed, VerificationDerived) and,
  from the repository, the model (for the order of everything), each
  component's design prose (<spec-dir>/<spec>.md), the rendered views and the
  wiki's pages. It never decides a status and never computes the model's
  hash: the views' freshness is the publish task's gate before this stage.
  It writes nothing outside the site's source and HTML directories.

  The layout, under the configured :source directory:

    mkdocs.yml                     the builder's configuration, nav from the model
    docs/index.md                  home: context, container and dynamic views,
                                   the contexts, the wiki, every need and the
                                   scenarios tracing to it
    docs/<context>/index.md        a bounded context: its component views and
                                   its components
    docs/<spec>.md                 a component (its spec path, so named for its
                                   context and component): description, prose,
                                   every scenario with id, level, status, steps
    docs/kernel/index.md           the kernel, when the model has one
    docs/views/<key>.svg           the rendered views
    docs/wiki/...                  the wiki, footnote citations linked to their
                                   sources at their pinned commits

  Every page says where its status came from: the results directory (named
  only when it is inside the project), or that no status was derived because
  no results were given. Published output names paths relative to the
  project, never a path of the machine (dhf/spec/README.md).

  The stage `publish` returns {:result {:site {...}} :findings [...]}; a
  broken link is a finding naming the page and the link (exit 1, and no HTML
  is left); a missing site builder cannot run (exit 2)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; The events this component reads

(defn subscriptions
  "Keeps the payloads of the events the site reads in the run's state."
  [req]
  (for [event [:SpecParsed :SpecGated :KnowledgeIndexed :VerificationDerived]]
    [event (fn [payload] (swap! (:state req) assoc-in [::events event] payload) nil)]))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; Pure helpers

(defn- kw-name [k] (when k (if (keyword? k) (name k) (str k))))

(defn yaml-str
  "A YAML scalar for `s`: always a double-quoted string (JSON is YAML)."
  [s]
  (json/generate-string (str s)))

(defn relative-to
  "The path of the file `to` (relative to the docs root) from the page
  `from` (relative to the docs root)."
  [from to]
  (let [from-dir (vec (butlast (str/split from #"/")))
        to-parts (str/split to #"/")
        common (count (take-while true? (map = from-dir (butlast to-parts))))]
    (str/join "/" (concat (repeat (- (count from-dir) common) "..") (drop common to-parts)))))

(defn component-page
  "The page of a component: its spec path, else <context>/<id name>."
  [context comp]
  (str (or (:spec comp) (str (:name context) "/" (kw-name (:id comp)))) ".md"))

(defn context-page [context] (str (:name context) "/index.md"))

(def kernel-context {:id :kernel :name "kernel"
                     :description "Shared code no bounded context owns"})

(defn contexts
  "The model's bounded contexts in its order: [{:context :components}],
  the kernel last when the model has one."
  [model]
  (vec (concat (for [c (mapcat :contexts (:containers model))]
                 {:context c :components (vec (:components c))})
               (when (seq (:kernel model))
                 [{:context kernel-context :components (vec (:kernel model)) :kernel? true}]))))

(defn heading-text
  "The first level-one heading of `text`, or nil."
  [text]
  (some #(second (re-matches #"#\s+(.+?)\s*#*\s*" %)) (str/split-lines (str text))))

(def citation-re #"([A-Za-z0-9][\w.-]*):([^\s@`\"'()\[\]<>]+)@([0-9a-fA-F]{4,40})")

(defn citation-url
  "The web address of `path` at `commit` in the repository `repository`, or
  nil when there is none to link to."
  [repository path commit]
  (when (and repository (re-find #"^https?://" repository))
    (str (str/replace repository #"(\.git)?/*$" "") "/blob/" commit "/" path)))

(defn- link-citations
  "`line` with every citation of a known source made a link."
  [line repositories]
  (let [link (fn [shown [_ src path commit]]
               (if-let [url (citation-url (get repositories src) path commit)]
                 (str "[" shown "](" url ")")
                 shown))]
    (-> line
        ;; a citation in a code span: the span becomes the link's text
        (str/replace #"`([^`]+)`"
                     (fn [[whole inner]]
                       (if-let [m (re-matches citation-re inner)] (link whole m) whole)))
        ;; a bare citation, not already inside a link or a code span
        (str/replace (re-pattern (str "(?<![\\[`/\\w.-])" citation-re "(?![`\\]\\w])"))
                     (fn [m] (link (first m) m))))))

(defn link-footnotes
  "`text` (a wiki page) with each citation in a footnote definition linked to
  its source at the cited commit; `repositories` is {source-id url}."
  [text repositories]
  (let [lines (str/split-lines text)]
    (loop [[l & more] lines in-note? false out []]
      (if (nil? l)
        (str (str/join "\n" out) (when (str/ends-with? text "\n") "\n"))
        (let [starts? (re-find #"^\[\^[^\]]+\]:" l)
              in-note? (cond starts? true
                             (and in-note? (or (str/blank? l) (re-find #"^\s+" l))) true
                             :else false)]
          (recur more in-note? (conj out (if in-note? (link-citations l repositories) l))))))))

(defn with-banner
  "`text` with the status banner after its first level-one heading (or at
  the top when it has none)."
  [text banner]
  (let [lines (str/split-lines text)
        i (first (keep-indexed (fn [i l] (when (re-matches #"#\s+.*" l) i)) lines))]
    (if i
      (str (str/join "\n" (concat (take (inc i) lines) ["" banner] (drop (inc i) lines)))
           (when (str/ends-with? text "\n") "\n"))
      (str banner "\n\n" text))))

;; ---------------------------------------------------------------------------
;; Status

(defn status-banner
  "The line every page carries about where its status came from."
  [{:keys [derived? results-rel]}]
  (if derived?
    (str "> Status derived from the Allure results "
         (if results-rel (str "in `" results-rel "`") "given to this run, from outside the project")
         ".")
    "> No status was derived because no results were given."))

(defn scenario-status
  "What a scenario shows: planned when tagged @wip, else its derived status,
  else that none was derived."
  [sc statuses derived?]
  (cond (:wip? sc) "planned"
        (not derived?) "not derived"
        :else (or (kw-name (get statuses (:id sc))) "unverified")))

;; ---------------------------------------------------------------------------
;; Pages

(defn- step-lines [st]
  (concat [(str (:keyword st) " " (:text st))]
          (when-let [ds (:doc-string st)]
            (let [content (if (map? ds) (:content ds) ds)]
              (concat ["  \"\"\""] (map #(str "  " %) (str/split-lines (str content))) ["  \"\"\""])))
          (when-let [dt (:data-table st)]
            (for [row (if (map? dt) (:rows dt) dt)
                  :let [cells (if (map? row) (:cells row) row)]]
              (str "  | " (str/join " | " cells) " |")))))

(defn- fence-for
  "A code fence longer than any run of backticks in `text`."
  [text]
  (let [longest (reduce max 0 (map count (re-seq #"`+" text)))]
    (apply str (repeat (max 3 (inc longest)) "`"))))

(defn scenario-block
  "The Markdown of one scenario."
  [sc status heading]
  (let [steps (str/join "\n" (mapcat step-lines (concat (:background sc) (:steps sc))))
        fence (fence-for steps)
        rows (:examples sc)]
    (str/join
     "\n"
     (concat
      [(str heading " " (:name sc) (when (:id sc) (str " {#" (:id sc) "}")))
       ""
       (str "- Id: " (if (:id sc) (str "`" (:id sc) "`") "none: not stamped"))
       (str "- Level: " (or (kw-name (:level sc)) "none"))
       (str "- Status: " status)
       ""
       (str fence "gherkin")
       steps
       fence]
      (for [ex rows
            :let [header (:header ex)]
            :when (seq header)]
        (str "\n" (fence-for "") "text\nExamples:\n  | " (str/join " | " header) " |\n"
             (str/join "\n" (for [r (:rows ex)] (str "  | " (str/join " | " (:cells r)) " |")))
             "\n" (fence-for "")))
      [""]))))

(defn component-markdown
  "The page of the component `comp` of `context`."
  [{:keys [context comp prose scenarios statuses status] :as _page}]
  (let [derived? (:derived? status)
        by-rule (partition-by :rule scenarios)]
    (str/join
     "\n"
     (concat
      [(str "# " (:name comp))
       ""
       (status-banner status)
       ""
       (str "Component of the bounded context [" (:name context) "](index.md).")
       ""
       (str (:description comp) ".")
       ""]
      (when (seq (str/trim (str prose)))
        ["## Design" "" (str/trim prose) ""])
      ["## Scenarios" ""]
      (if (empty? scenarios)
        ["No scenario is written for this component yet." ""]
        (for [group by-rule
              :let [rule (:rule (first group))]
              line (concat (when rule [(str "### Rule: " rule) ""])
                           (for [sc group]
                             (scenario-block sc (scenario-status sc statuses derived?)
                                             (if rule "####" "###"))))]
          line))))))

(defn context-markdown
  "The page of a bounded context: its component views and its components."
  [{:keys [context components views status kernel?]}]
  (str/join
   "\n"
   (concat
    [(str "# " (if kernel? "The kernel" (str "The bounded context " (:name context))))
     ""
     (status-banner status)
     ""
     (str (:description context) ".")
     ""]
    (for [v views] (str "![" (:description v) "](../views/" (:key v) ".svg)\n"))
    ["## Components" ""]
    (for [comp components]
      (str "- [" (:name comp) "](" (relative-to (context-page context) (component-page context comp)) "): "
           (:description comp)))
    [""])))

(defn home-markdown
  "The home page: the system's views, its contexts, the wiki, every need with
  the scenarios tracing to it, and the spec check's verdict."
  [{:keys [model views ctxs needs scenarios gated wiki? status]}]
  (let [sys (:system model)
        page-of (into {} (for [{:keys [context components]} ctxs comp components]
                           [(:id comp) (component-page context comp)]))
        traced (fn [need] (for [sc scenarios
                                :when (and (:id sc) (some #{need} (:needs sc)))]
                            (let [p (page-of (:component sc))]
                              (if p (str "[" (:id sc) "](" p "#" (:id sc) ")") (str "`" (:id sc) "`")))))]
    (str/join
     "\n"
     (concat
      [(str "# " (or (:name sys) "The spec"))
       ""
       (status-banner status)
       ""]
      (when (:description sys) [(str (:description sys) ".") ""])
      (for [[title level] [["System context" :context] ["Containers" :container] ["Dynamic views" :dynamic]]
            :let [vs (filter #(= level (:level %)) views)]
            :when (seq vs)
            line (concat [(str "## " title) ""]
                         (for [v vs] (str "![" (:description v) "](views/" (:key v) ".svg)\n")))]
        line)
      ["## Bounded contexts" ""]
      (for [{:keys [context]} ctxs]
        (str "- [" (:name context) "](" (context-page context) "): " (:description context)))
      [""]
      (when wiki?
        ["## Domain wiki" "" "The domain, distilled from the raw sources: [the domain wiki](wiki/index.md)." ""])
      ["## User needs" ""]
      (for [{:keys [id text]} needs
            :let [ts (traced id)]
            line [(str "### " id " {#" id "}")
                  ""
                  (str/trim (str text))
                  ""
                  (if (seq ts)
                    (str "Scenarios tracing to it: " (str/join ", " ts) ".")
                    "No scenario traces to it.")
                  ""]]
        line)
      (when gated
        ["## Spec check" ""
         (if (:passed? gated)
           "The spec check passed."
           (str "The spec check found " (count (:findings gated)) " finding"
                (when (not= 1 (count (:findings gated))) "s") "."))
         ""])))))

(defn mkdocs-yml
  "The site builder's configuration: the home page first, a section per
  bounded context in the model's order (its page, then its components'),
  the kernel's, then the wiki's."
  [{:keys [site-name site-dir ctxs wiki-nav]}]
  (let [entry (fn [indent title path] (str indent "- " (yaml-str title) ": " (yaml-str path)))]
    (str/join
     "\n"
     (concat
      [(str "site_name: " (yaml-str site-name))
       "docs_dir: \"docs\""
       (str "site_dir: " (yaml-str site-dir))
       "strict: true"
       "use_directory_urls: true"
       "theme:"
       "  name: \"mkdocs\""
       "validation:"
       "  links:"
       "    not_found: \"warn\""
       "markdown_extensions:"
       "  - \"footnotes\""
       "  - \"tables\""
       "  - \"attr_list\""
       "  - \"toc\""
       "nav:"
       (entry "  " "Home" "index.md")]
      (for [{:keys [context components]} ctxs
            line (concat [(str "  - " (yaml-str (:name context)) ":")
                          (entry "      " "Overview" (context-page context))]
                         (for [comp components]
                           (entry "      " (:name comp) (component-page context comp))))]
        line)
      (when (seq wiki-nav)
        (concat ["  - \"wiki\":"]
                (for [[title path] wiki-nav] (entry "      " title path))))
      [""]))))

;; ---------------------------------------------------------------------------
;; Reading

(defn- read-model
  "The model as data, or a finding."
  [req]
  (let [rel (command/config-value req :model)
        f (command/path req rel)]
    (if-not (fs/regular-file? f)
      {:finding (command/finding rel nil "site-no-model" "there is no model to publish the site from")}
      (let [m (try (edn/read-string (slurp (str f))) (catch Exception e e))]
        (if (map? m)
          {:model m}
          {:finding (command/finding rel nil "site-model-unreadable"
                                     (str "the model is not readable EDN, so no site was written"
                                          (when (instance? Exception m) (str ": " (ex-message m)))))})))))

(defn- inside?
  "True when the relative path `rel` stays inside the project and is not the
  project itself."
  [rel]
  (let [parts (str/split (str rel) #"[/\\]+")]
    (and (not (str/blank? (str rel)))
         (not (fs/absolute? (str rel)))
         (not-any? #{".."} parts)
         (seq (remove #{"."} parts)))))

(defn- site-config
  [req]
  (let [{:keys [dir source builder] :as site} (command/config-value req :site)]
    (when-not (and (map? site) (inside? dir) (inside? source) (seq builder))
      (command/cannot-run! (str "the configuration " command/config-file " :site must give :dir and :source,"
                                " each a directory inside the project, and :builder, the command that builds")))
    (when (some #(str/starts-with? (str (fs/normalize %)) "dhf") [dir source])
      (command/cannot-run! "the configuration's :site directories must lie outside dhf/, which the site never writes"))
    site))

(defn- results-rel
  "The results directory relative to the project, or nil when it is outside."
  [req]
  (when-let [d (:results req)]
    (let [r (command/rel req (command/path req d))]
      (when-not (or (fs/absolute? r) (str/starts-with? r "..")) r))))

(defn- wiki-files
  "The wiki: {rel-under-wiki abs} of every file under the wiki directory,
  the pages being those KnowledgeIndexed names (when it was published) and
  the index."
  [req]
  (let [wiki-rel (str/replace (or (get-in req [:config :wiki-dir]) "dhf/wiki") #"/+$" "")
        dir (command/path req wiki-rel)
        indexed (event req :KnowledgeIndexed)]
    (if-not (fs/directory? dir)
      {}
      (into (sorted-map)
            (for [f (fs/glob dir "**")
                  :when (fs/regular-file? f)
                  :let [rel (str/replace (str (fs/relativize dir f)) "\\" "/")]
                  :when (or (not (str/ends-with? rel ".md"))
                            (= "index.md" rel)
                            (nil? indexed)
                            (some #{(str wiki-rel "/" rel)} (:pages indexed)))]
              [rel (str f)])))))

(defn- wiki-order
  "The wiki's Markdown pages in the nav's order: the index, then the pages
  in the order the index lists them, then any other, by path."
  [files]
  (let [index (get files "index.md")
        listed (when index
                 (for [[_ target] (re-seq #"\]\(\s*<?([^)\s>#]+)" (slurp index))
                       :let [t (str/replace target #"^\./" "")]
                       :when (contains? files t)]
                   t))
        mds (filter #(str/ends-with? % ".md") (keys files))]
    (vec (distinct (concat (when index ["index.md"]) listed (sort mds))))))

;; ---------------------------------------------------------------------------
;; Writing and building

(defn- write-page! [pages rel text] (assoc pages rel text))

(defn- build-pages
  "{rel text} of every Markdown page, {rel abs} of every file copied, and the
  nav's wiki entries."
  [req model]
  (let [parsed (or (event req :SpecParsed)
                   (command/cannot-run! "the spec was not read: no SpecParsed event reached the site"))
        derived (event req :VerificationDerived)
        status {:derived? (boolean derived) :results-rel (results-rel req)}
        statuses (:statuses derived)
        banner (status-banner status)
        scenarios (vec (:scenarios parsed))
        ctxs (contexts model)
        views (vec (:views model))
        views-dir (str/replace (or (get-in req [:config :views-dir]) "dhf/views") #"/+$" "")
        spec-dir (str/replace (or (get-in req [:config :spec-dir]) "dhf/spec") #"/+$" "")
        repositories (into {} (for [s (:sources (event req :KnowledgeIndexed))] [(:id s) (:repository s)]))
        wiki (wiki-files req)
        wiki-md (wiki-order wiki)
        pages (atom {})
        copies (atom {})]
    (doseq [v views
            :let [f (command/path req (str views-dir "/" (:key v) ".svg"))]
            :when (fs/regular-file? f)]
      (swap! copies assoc (str "views/" (:key v) ".svg") (str f)))
    (swap! pages write-page! "index.md"
           (home-markdown {:model model :views views :ctxs ctxs :needs (:needs parsed)
                           :scenarios scenarios :gated (event req :SpecGated)
                           :wiki? (contains? wiki "index.md") :status status}))
    (doseq [{:keys [context components kernel?]} ctxs]
      (swap! pages write-page! (context-page context)
             (context-markdown {:context context :components components :kernel? kernel? :status status
                                :views (filter #(and (= :component (:level %)) (= (:id context) (:context %)))
                                               views)}))
      (doseq [comp components
              :let [prose-file (when (:spec comp) (command/path req (str spec-dir "/" (:spec comp) ".md")))]]
        (swap! pages write-page! (component-page context comp)
               (component-markdown {:context context :comp comp :status status :statuses statuses
                                    :prose (when (and prose-file (fs/regular-file? prose-file)) (slurp (str prose-file)))
                                    :scenarios (filterv #(= (:id comp) (:component %)) scenarios)}))))
    (doseq [[rel abs] wiki]
      (if (str/ends-with? rel ".md")
        (swap! pages write-page! (str "wiki/" rel) (with-banner (link-footnotes (slurp abs) repositories) banner))
        (swap! copies assoc (str "wiki/" rel) abs)))
    {:pages @pages
     :copies @copies
     :status status
     :ctxs ctxs
     :wiki-nav (vec (for [rel wiki-md]
                      [(if (= "index.md" rel) "Overview" (or (heading-text (slurp (get wiki rel))) rel))
                       (str "wiki/" rel)]))}))

(def ^:private link-warning
  #"Doc file '([^']+)' contains (?:a|an unrecognized relative) link '([^']+)'")

(defn build-findings
  "Findings from the builder's output: each broken link names the page (as
  published, relative to the project) and the link."
  [source-rel output exit]
  (let [lines (str/split-lines (str output))
        links (for [l lines :let [[_ page link] (re-find link-warning l)] :when page]
                (command/finding (str source-rel "/docs/" page) nil "site-broken-link"
                                 (str "the page links to " link ", which the site does not hold")))
        others (for [l lines
                     :when (re-find #"^(WARNING|ERROR)\b" l)
                     :when (not (re-find link-warning l))]
                 (command/finding (str source-rel "/mkdocs.yml") nil "site-build"
                                  (str/trim (str/replace l #"^(WARNING|ERROR)\s*-\s*" ""))))
        found (vec (concat links others))]
    (if (and (not (zero? exit)) (empty? found))
      [(command/finding (str source-rel "/mkdocs.yml") nil "site-build-failed"
                        (str "the site builder exited " exit ": "
                             (or (last (remove str/blank? lines)) "and said nothing")))]
      found)))

(defn publish
  "The site stage of `rdm-spec publish`: writes the Markdown and the site
  builder's configuration under the configured source directory, then has
  the configured builder build the HTML into the configured site directory."
  [req]
  (let [{:keys [dir source builder]} (site-config req)
        tool (first builder)]
    (when-not (fs/which tool)
      (command/cannot-run! (str "the site builder " tool " is not installed (not on PATH), so no HTML was written")))
    (let [{:keys [model finding]} (read-model req)]
      (if finding
        {:result {:site {:written? false}} :findings [finding]}
        (let [source-dir (command/path req source)
              docs (fs/file source-dir "docs")
              html (command/path req dir)
              {:keys [pages copies status ctxs wiki-nav]} (build-pages req model)
              site-dir (relative-to (str source "/x") (str dir))]
          ;; the source is generated: nothing of an earlier site survives
          (fs/delete-tree docs)
          (fs/delete-if-exists (fs/file source-dir "mkdocs.yml"))
          (doseq [[rel text] pages
                  :let [f (fs/file docs rel)]]
            (fs/create-dirs (fs/parent f))
            (spit (str f) text))
          (doseq [[rel abs] copies
                  :let [f (fs/file docs rel)]]
            (fs/create-dirs (fs/parent f))
            (fs/copy abs f {:replace-existing true}))
          (spit (str (fs/file source-dir "mkdocs.yml"))
                (mkdocs-yml {:site-name (or (get-in model [:system :name]) "spec")
                             :site-dir site-dir :ctxs ctxs :wiki-nav wiki-nav}))
          (let [r (apply p/shell {:dir (str (:root req)) :out :string :err :string :continue true :in ""
                                  :env (:env req)}
                         builder)
                findings (build-findings source (str (:out r) "\n" (:err r)) (:exit r))
                built? (and (zero? (:exit r)) (empty? findings))]
            (when-not built?
              ;; a failed build leaves no HTML behind
              (fs/delete-tree html))
            {:result {:site {:source source
                             :dir dir
                             :pages (vec (sort (keys pages)))
                             :status-derived? (:derived? status)
                             :built? built?}}
             :findings findings}))))))
