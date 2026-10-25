(ns rdm-spec.publishing.site
  "The Site component of the publishing context
  (dhf/spec/publishing/site.feature): one page per component and context,
  the home page, the domain wiki, and the site builder's configuration,
  written as Markdown under the configured :site :source (default site-src)
  and built to HTML under :site :dir (default site) by the configured
  :site :builder, which fails on a broken link.

  The last stage of `rdm-spec publish`, after the views' freshness gate and
  the stages that publish what the site reads; it reads no other context's
  code, only the events of the run (`subscriptions`):

    KnowledgeIndexed     the sources (for footnote links) and the wiki's citations
    SpecParsed           every scenario, with its id, level, steps and owner; the needs
    SpecGated            whether the spec passed its check
    VerificationDerived  each scenario's status; absent when no results were given

  and the files it publishes: the model (for the pages and the navigation),
  each component's prose (<spec-dir>/<spec>.md), the rendered views and the
  wiki. It decides no status: without VerificationDerived every page says no
  status was derived. It writes nothing in the spec, and the same inputs give
  the same Markdown and configuration, naming no path outside the project.

  Layout: docs/index.md (home), docs/<context>/index.md and
  docs/<context>/<component>.md (names lower-cased, anything but letters
  and digits a hyphen), docs/kernel/, docs/wiki/ (as under the wiki
  directory), docs/views/<key>.svg; mkdocs.yml beside docs/.

  Rules: site-link-broken (a page linking a page the site does not hold),
  site-build-failed (the builder failed otherwise). A builder that is not
  installed cannot run (exit 2), and no HTML is left either way."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; What the run published

(defn subscriptions
  "The events the site reads, kept in the run's state."
  [req]
  (for [e [:KnowledgeIndexed :SpecParsed :SpecGated :VerificationDerived]]
    [e (fn [payload] (when-let [st (:state req)] (swap! st assoc-in [::events e] payload)))]))

(defn- event [req e] (some-> (:state req) deref (get-in [::events e])))

;; ---------------------------------------------------------------------------
;; Names and text

(defn slug [s] (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn- yq [s] (json/generate-string (str s)))

(def no-status-banner
  "> No status was derived: no results were given (rdm-spec publish --results DIR)."
  )

(def status-banner "> Each scenario's status is derived from the Allure results given.")

(defn- with-banner
  "`text` with `banner` after its first top heading (or first, when it has none)."
  [text banner]
  (let [ls (str/split-lines (str text))
        i (first (keep-indexed (fn [i l] (when (re-find #"^# " l) i)) ls))]
    (if i
      (str (str/join "\n" (concat (take (inc i) ls) ["" banner ""] (drop (inc i) ls))) "\n")
      (str banner "\n\n" text))))

(defn- demote
  "Markdown with every heading outside fenced code one level deeper."
  [text]
  (loop [[l & more] (str/split-lines (str text)) fence? false out []]
    (if (nil? l)
      (str/join "\n" out)
      (let [f? (re-find #"^\s*(```|~~~)" l)]
        (recur more (if f? (not fence?) fence?)
               (conj out (if (and (not fence?) (not f?) (re-find #"^#{1,5} " l)) (str "#" l) l)))))))

(defn- fence-for [text]
  (let [longest (reduce max 2 (map count (re-seq #"`+" (str text))))]
    (apply str (repeat (inc longest) "`"))))

;; ---------------------------------------------------------------------------
;; Scenarios

(defn- status-of [statuses sc]
  (cond
    statuses (if-let [st (get statuses (:id sc))] (name st) (if (:wip? sc) "planned" "unverified"))
    (:wip? sc) "planned"
    :else "no status derived"))

(defn- step-lines [st]
  (concat [(str "  " (:keyword st) " " (:text st))]
          (when (contains? st :doc-string)
            (concat ["    \"\"\""] (map #(str "    " %) (str/split-lines (str (:doc-string st)))) ["    \"\"\""]))
          (when-let [t (:data-table st)]
            (for [row t] (str "    | " (str/join " | " row) " |")))))

(defn- scenario-md [sc statuses]
  (let [lines (concat (mapcat step-lines (concat (:background sc) (:steps sc)))
                      (for [ex (:examples sc)
                            l (concat ["" "  Examples:"
                                       (str "    | " (str/join " | " (:header ex)) " |")]
                                      (for [row (:rows ex)] (str "    | " (str/join " | " (:cells row)) " |")))]
                        l))
        body (str/join "\n" lines)
        fence (fence-for body)]
    (str "### " (or (:id sc) "(no id)") ": " (:title sc) "\n\n"
         "Level: " (if (:level sc) (name (:level sc)) "none")
         " · Status: " (status-of statuses sc)
         (when (seq (:needs sc)) (str " · Needs: " (str/join ", " (:needs sc))))
         "\n\n"
         fence "gherkin\n" (str (:keyword sc) ": " (:title sc)) "\n" body "\n" fence "\n")))

;; ---------------------------------------------------------------------------
;; The pages

(defn- read-model [req]
  (let [f (command/path req (command/config-value req :model))]
    (try (let [m (edn/read-string (slurp (str f)))] (when (map? m) m))
         (catch Exception _ nil))))

(defn- site-contexts
  "The bounded contexts as the site lays them out, the kernel last when it
  has components: [{:id :name :description :dir :components [{:id :name
  :description :spec :file}]}]."
  [m]
  (let [ctxs (for [c (:containers m) x (:contexts c) :when (map? x)]
               {:id (:id x) :name (or (:name x) (some-> (:id x) name)) :description (:description x)
                :components (vec (filter map? (:components x)))})
        kern (filter map? (:kernel m))
        all (concat ctxs (when (seq kern)
                           [{:id :kernel :name "kernel" :kernel? true
                             :description "Shared code every bounded context may use, in none of them"
                             :components (vec kern)}]))]
    (vec (for [x all
               :let [d (slug (:name x))]]
           (assoc x :dir d
                  :components (vec (for [c (:components x)]
                                     (assoc c :file (str d "/" (slug (or (:name c) (some-> (:id c) name))) ".md")))))))))

(defn- view-link [prefix v]
  (str "![" (str/replace (str (:description v)) #"[\[\]]" "") "](" prefix "views/" (:key v) ".svg)"))

(defn- component-page [req x c scenarios statuses banner]
  (let [spec-dir (command/config-value req :spec-dir)
        prose-f (when (string? (:spec c)) (command/path req (str spec-dir "/" (:spec c) ".md")))
        prose (when (and prose-f (fs/regular-file? prose-f)) (slurp (str prose-f)))
        mine (filter #(= (:id c) (:component %)) scenarios)
        by-rule (partition-by :rule mine)]
    (str "# " (:name c) " (" (:name x) ")\n\n"
         banner "\n\n"
         (:description c) "\n\n"
         (when (string? (:spec c)) (str "Its scenarios are `" spec-dir "/" (:spec c) ".feature`.\n\n"))
         (when prose (str (str/trim (demote prose)) "\n\n"))
         "## Scenarios\n\n"
         (if (empty? mine)
           "No scenario.\n"
           (str/join "\n"
                     (for [group by-rule
                           :let [rule (:rule (first group))]]
                       (str (when rule (str "## Rule: " rule "\n\n"))
                            (str/join "\n" (map #(scenario-md % statuses) group)))))))))

(defn- context-page [x views banner]
  (str "# " (:name x) "\n\n"
       banner "\n\n"
       (:description x) "\n\n"
       (str/join (for [v views] (str (view-link "../" v) "\n\n")))
       "## Components\n\n"
       (str/join (for [c (:components x)]
                   (str "- [" (:name c) "](" (subs (:file c) (inc (count (:dir x)))) "): " (:description c) "\n")))))

(defn- home-page [m contexts views needs scenarios statuses banner wiki?]
  (let [file-of (into {} (for [x contexts c (:components x)] [(:id c) (:file c)]))]
    (str "# " (get-in m [:system :name] "The spec") "\n\n"
         banner "\n\n"
         (get-in m [:system :description]) "\n\n"
         "## Architecture\n\n"
         (str/join (for [v views :when (#{:context :container} (:level v))] (str (view-link "" v) "\n\n")))
         "## Bounded contexts\n\n"
         (str/join (for [x contexts] (str "- [" (:name x) "](" (:dir x) "/index.md): " (:description x) "\n")))
         "\n"
         (when wiki? "## Wiki\n\nThe domain the spec rests on: [the domain wiki](wiki/index.md).\n\n")
         "## User needs\n\n"
         (str/join
          (for [n needs
                :let [tracing (filter #(some #{(:id n)} (:needs %)) scenarios)]]
            (str "### " (:id n) "\n\n" (:text n) "\n\n"
                 (when (seq (:sources n))
                   (str "Sources: " (str/join ", " (map #(str "`" % "`") (:sources n))) "\n\n"))
                 (if (empty? tracing)
                   "No scenario traces to it.\n\n"
                   (str (str/join (for [sc tracing]
                                    (str "- " (if-let [f (file-of (:component sc))]
                                                (str "[" (or (:id sc) "(no id)") "](" f ")")
                                                (or (:id sc) "(no id)"))
                                         ": " (:title sc) " (" (status-of statuses sc) ")\n")))
                        "\n"))))))))

;; ---------------------------------------------------------------------------
;; The wiki

(defn- source-url [repository commit path]
  (str (str/replace (str/replace (str repository) #"/+$" "") #"\.git$" "") "/blob/" commit "/" path))

(defn- link-citations
  "`text` (the wiki file `file`) with each citation KnowledgeIndexed found on
  a line linked to its source at its commit."
  [text file citations repos]
  (let [on-line (group-by :line (filter #(= file (:file %)) citations))]
    (str (str/join "\n"
                   (map-indexed
                    (fn [i l]
                      (reduce (fn [l {:keys [citation source path commit]}]
                                (if-let [repo (get repos source)]
                                  (let [url (source-url repo commit path)
                                        ticked (str "`" citation "`")]
                                    (cond
                                      (str/includes? l (str "](" url ")")) l
                                      (str/includes? l ticked) (str/replace l ticked (str "[" ticked "](" url ")"))
                                      :else (str/replace l citation (str "[" citation "](" url ")"))))
                                  l))
                              l (distinct (map #(select-keys % [:citation :source :path :commit]) (get on-line (inc i))))))
                    (str/split-lines (str text))))
         "\n")))

(defn- title-of [text fallback]
  (or (some #(second (re-matches #"#\s+(.+?)\s*" %)) (str/split-lines (str text))) fallback))

(defn- wiki-files
  "The wiki's index and pages: [{:file (relative to the project) :rel
  (relative to the wiki directory) :text}], the index first."
  [req]
  (let [wiki-dir (str/replace (command/config-value req :wiki-dir) #"/+$" "")
        d (command/path req wiki-dir)]
    (when (fs/regular-file? (fs/file d "index.md"))
      (let [rel #(str/replace (str (fs/relativize d %)) "\\" "/")
            files (sort-by rel (filter fs/regular-file? (fs/glob d "**.md")))
            index (fs/file d "index.md")]
        (vec (for [f (cons index (remove #(= (fs/normalize %) (fs/normalize index)) files))]
               {:file (command/rel req f) :rel (rel f) :text (slurp (str f))}))))))

;; ---------------------------------------------------------------------------
;; The builder's configuration

(defn- nav-yaml [contexts wiki]
  (str "nav:\n"
       "  - \"Home\": index.md\n"
       (str/join
        (for [x contexts]
          (str "  - " (yq (:name x)) ":\n"
               "      - " (yq (:name x)) ": " (:dir x) "/index.md\n"
               (str/join (for [c (:components x)] (str "      - " (yq (:name c)) ": " (:file c) "\n"))))))
       (when (seq wiki)
         (str "  - \"wiki\":\n"
              (str/join (for [{:keys [rel text]} wiki]
                          (str "      - " (yq (if (= "index.md" rel) "Wiki" (title-of text rel))) ": wiki/" rel "\n")))))))

(defn- mkdocs-yml [m site-dir-rel contexts wiki]
  (str "# Written by rdm-spec publish from the model; do not edit.\n"
       "site_name: " (yq (get-in m [:system :name] "The spec")) "\n"
       "docs_dir: docs\n"
       "site_dir: " (yq site-dir-rel) "\n"
       "use_directory_urls: false\n"
       "markdown_extensions:\n  - footnotes\n  - tables\n"
       (nav-yaml contexts wiki)))

;; ---------------------------------------------------------------------------
;; Building

(defn- inside? [root f]
  (let [r (fs/normalize (fs/absolutize root)) a (fs/normalize (fs/absolutize f))]
    (and (not= r a) (fs/starts-with? a r))))

(defn- remove-html!
  "Empties the site directory, so no HTML of an earlier build is left."
  [site-dir]
  (when (fs/exists? site-dir) (fs/delete-tree site-dir)))

(defn- build-findings
  "The findings of a failed build's output: a broken link names the page
  (under `docs-rel`) and the link."
  [out docs-rel]
  (let [ls (str/split-lines (str out))
        broken (for [l ls
                     :let [m (re-find #"Doc file '([^']+)' contains a link '([^']+)', but the target(?: '([^']+)')? is not found" l)]
                     :when m]
                 (command/finding (str docs-rel "/" (nth m 1)) "site-link-broken"
                                  (str "the page links " (nth m 2) ", which the site does not hold, so the site was not built")))]
    (if (seq broken)
      (vec (distinct broken))
      [(command/finding "site-build-failed"
                        (str "the site builder failed, so no HTML was written: "
                             (str/join " | " (take 5 (filter #(re-find #"(?i)error|warning|aborted" %) ls)))))])))

(defn publish
  "The site's stage: writes the Markdown and the builder's configuration,
  then builds the HTML."
  [req]
  (let [site (merge {:dir "site" :source "site-src"} (get-in req [:config :site]))
        source-rel (str/replace (str (:source site)) #"/+$" "")
        site-rel (str/replace (str (:dir site)) #"/+$" "")
        builder (or (not-empty (vec (map str (:builder site))))
                    ["mkdocs" "build" "--strict" "-f" (str source-rel "/mkdocs.yml")])
        root (:root req)
        source-dir (command/path req source-rel)
        site-dir (command/path req site-rel)
        _ (doseq [[k d] [[":site :source" source-dir] [":site :dir" site-dir]]]
            (when-not (inside? root d)
              (command/cannot-run! (str "the configured " k " " (command/rel req d)
                                        " is not a directory inside the project"))))
        _ (when-not (fs/which (first builder))
            (remove-html! site-dir)
            (command/cannot-run! (str "the configured site builder " (first builder)
                                      " is not installed, so no HTML was written (" site-rel "/ holds none)")))
        m (or (read-model req) (command/cannot-run! "the model cannot be read, so no site was written"))
        parsed (event req :SpecParsed)
        derived (event req :VerificationDerived)
        knowledge (event req :KnowledgeIndexed)
        statuses (:statuses derived)
        banner (if derived status-banner no-status-banner)
        scenarios (vec (:scenarios parsed))
        contexts (site-contexts m)
        views (vec (filter map? (:views m)))
        wiki (wiki-files req)
        repos (into {} (for [s (:sources knowledge)] [(:id s) (:repository s)]))
        docs-dir (fs/file source-dir "docs")
        views-dir (command/path req (command/config-value req :views-dir))
        pages (concat
               [["index.md" (home-page m contexts views (:needs parsed) scenarios statuses banner (seq wiki))]]
               (for [x contexts]
                 [(str (:dir x) "/index.md")
                  (context-page x (filter #(and (= :component (:level %)) (= (:id x) (:context %))) views) banner)])
               (for [x contexts c (:components x)]
                 [(:file c) (component-page req x c scenarios statuses banner)])
               (for [{:keys [file rel text]} wiki]
                 [(str "wiki/" rel) (with-banner (link-citations text file (:citations knowledge) repos) banner)]))]
    (when (fs/exists? docs-dir) (fs/delete-tree docs-dir))
    (fs/create-dirs docs-dir)
    (doseq [[rel text] pages]
      (let [f (fs/file docs-dir rel)]
        (fs/create-dirs (fs/parent f))
        (spit (str f) (if (str/ends-with? text "\n") text (str text "\n")))))
    (doseq [v views
            :let [svg (fs/file views-dir (str (:key v) ".svg"))]
            :when (fs/regular-file? svg)]
      (fs/create-dirs (fs/file docs-dir "views"))
      (fs/copy svg (fs/file docs-dir "views" (str (:key v) ".svg")) {:replace-existing true}))
    (spit (str (fs/file source-dir "mkdocs.yml"))
          (mkdocs-yml m (str/replace (str (fs/relativize (fs/absolutize source-dir) (fs/absolutize site-dir))) "\\" "/")
                      contexts wiki))
    (remove-html! site-dir)
    (let [r (try (apply p/shell {:dir (str root) :out :string :err :string :continue true :in ""} builder)
                 (catch Exception e
                   (command/cannot-run! (str "the configured site builder " (first builder) " could not be run, so no HTML was written: "
                                             (ex-message e)))))
          ok? (and (zero? (:exit r)) (fs/exists? (fs/file site-dir "index.html")))
          findings (when-not ok?
                     (remove-html! site-dir)
                     (build-findings (str (:err r) "\n" (:out r)) (str source-rel "/docs")))]
      {:result {:publish {:source source-rel :site site-rel :pages (count pages) :built? ok?
                          :status-derived? (boolean derived)}}
       :findings (vec findings)})))
