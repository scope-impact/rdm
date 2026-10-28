(ns rdm-spec.publishing.site
  "Component Site (publishing context): the spec, its architecture, its
  wiki and its status as one site. Markdown read from the spec (the
  scenarios and needs of SpecParsed, the sources and wiki pages of
  KnowledgeIndexed, the statuses of VerificationDerived), one page per
  component and per context, built by the configured site builder from a
  configuration written in the model's order. It shows status and never
  decides it, and writes nothing in the spec."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.kernel.bus :as bus]))

(def no-status "no status was derived because no results were given")

(defn status-text
  "A scenario's status as the site shows it: planned whatever the results, else derived, else said not derived."
  [statuses sc]
  (cond (:wip? sc) "planned"
        statuses (name (get statuses (:id sc) :unverified))
        :else no-status))

(defn note [statuses]
  (when-not statuses (str "*Status: " no-status ".*\n\n")))

(defn ctx-dir [c] (if (= :kernel (:context c)) "kernel" (name (:context c))))

(defn page-of [c] (str (ctx-dir c) "/" (name (:id c)) ".md"))

(defn yaml-key [s]
  (let [s (str s)]
    (if (re-find #"[:#\[\]{},&*!|>'\"%@`]|^[-?\s]|\s$" s) (str "'" (str/replace s "'" "''") "'") s)))

(defn scenario-md [sc statuses]
  (str "### " (:id sc) ": " (:title sc) "\n\n"
       "Level: " (:level sc) " · Status: " (status-text statuses sc)
       (when (seq (:needs sc)) (str " · Needs: " (str/join ", " (:needs sc))))
       (when (:rule sc) (str " · Rule: " (:rule sc)))
       "\n\n"
       (str/join (for [st (concat (:background sc) (:steps sc))] (str "- " (:keyword st) " " (:text st) "\n")))
       (when (:outline? sc)
         (str "\nExamples: " (str/join "; " (map #(str/join ", " (map (fn [[k v]] (str k " = " v)) %)) (:examples sc))) "\n"))
       "\n"))

(defn component-md [c scs prose statuses]
  (str "# " (:name c) "\n\n" (:description c) "\n\n" (note statuses)
       (when prose (str "## Design\n\n" (str/trim (str/replace prose #"(?m)\A#\s+[^\n]*\n" "")) "\n\n"))
       "## Scenarios\n\n"
       (if (seq scs) (str/join (map #(scenario-md % statuses) scs)) "No scenario yet.\n")))

(defn link-citations
  "A wiki text with every footnote's citations linked to the cited file at its commit."
  [text sources]
  (str/join "\n"
            (for [line (str/split-lines text)]
              (if (re-find #"^\s*\[\^[^\]]+\]:" line)
                (str/replace line #"`?([A-Za-z0-9][A-Za-z0-9_.-]*):([^\s`@\"'(),]+)@([0-9a-fA-F]{7,40})`?"
                             (fn [[whole src path commit]]
                               (if-let [repo (get-in sources [(keyword src) :repository])]
                                 (str "[" whole "](" (str/replace repo #"(\.git)?/*$" "") "/blob/" commit "/" path ")")
                                 whole)))
                line))))

(defn mkdocs-yml [title nav]
  (str "site_name: " (yaml-key title) "\n"
       "docs_dir: docs\n"
       "site_dir: ../site\n"
       "strict: true\n"
       "use_directory_urls: false\n"
       "nav:\n"
       (str/join (for [[k v] nav]
                   (if (string? v)
                     (str "  - " (yaml-key k) ": " v "\n")
                     (str "  - " (yaml-key k) ":\n"
                          (str/join (for [[k2 v2] v] (str "      - " (yaml-key k2) ": " v2 "\n")))))))))

(defn write-site!
  "Writes the site's Markdown and the builder's configuration into SOURCE; returns the pages written."
  [cfg]
  (let [root (project/root)
        spec (first (project/specs cfg))
        model (or (project/read-model root spec) {})
        parsed (bus/latest :SpecParsed)
        know (bus/latest :KnowledgeIndexed)
        statuses (:statuses (bus/latest :VerificationDerived))
        sources (into {} (for [s (:sources know)] [(:id s) s]))
        scenarios (filter :id (:scenarios parsed))
        source (:source (:site cfg) "site-src")
        docs (project/abs (str source "/docs"))
        pages (atom [])
        write! (fn [rel text]
                 (let [f (str docs "/" rel)] (fs/create-dirs (fs/parent f)) (spit f text) (swap! pages conj rel)))
        comps (project/components model)
        by-comp (group-by :component scenarios)
        contexts (project/contexts model)
        kernel (filter #(= :kernel (:context %)) comps)
        views (filter map? (:views model))
        view-of (fn [level & [ctx]] (first (filter #(and (= level (:level %)) (or (nil? ctx) (= ctx (:context %)))) views)))
        views-dir (project/abs (:views-dir cfg))
        wiki (:wiki-dir cfg)
        wiki-files (concat (when (fs/regular-file? (project/abs (str wiki "/index.md"))) [(str wiki "/index.md")]) (:pages know))
        title (or (get-in model [:system :name]) "The spec")]
    (when (fs/exists? docs) (fs/delete-tree docs))
    (fs/create-dirs docs)
    ;; views
    (doseq [v views :let [f (str views-dir "/" (:key v) ".svg")] :when (fs/exists? f)]
      (fs/create-dirs (str docs "/views"))
      (fs/copy f (str docs "/views/" (:key v) ".svg") {:replace-existing true}))
    (let [img (fn [v prefix] (when (and v (fs/exists? (str views-dir "/" (:key v) ".svg")))
                               (str "![" (:description v) "](" prefix "views/" (:key v) ".svg)\n\n")))]
      ;; home
      (write! "index.md"
              (str "# " title "\n\n" (get-in model [:system :description]) "\n\n" (note statuses)
                   (when-let [v (view-of :context)] (str "## Context\n\n" (img v "")))
                   (when-let [v (view-of :container)] (str "## Containers\n\n" (img v "")))
                   (when (or (seq contexts) (seq kernel))
                     (str "## Bounded contexts\n\n"
                          (str/join (for [c contexts] (str "- [" (:name c) "](" (name (:id c)) "/index.md): " (:description c) "\n")))
                          (when (seq kernel) "- [Kernel](kernel/index.md): shared code in no bounded context\n")
                          "\n"))
                   "## User needs\n\n"
                   (str/join (for [n (:needs parsed)
                                   :let [tracing (filter #(some #{(:id n)} (:needs %)) scenarios)]]
                               (str "### " (:id n) "\n\n" (:text n) "\n\n"
                                    (if (seq tracing)
                                      (str/join (for [sc tracing :let [c (first (filter #(= (:component sc) (:id %)) comps))]]
                                                  (str "- " (if c (str "[" (:id sc) "](" (page-of c) ")") (:id sc)) ": " (:title sc)
                                                       " (" (status-text statuses sc) ")\n")))
                                      "No scenario traces to it yet.\n")
                                    "\n")))
                   (when (seq wiki-files) "## Wiki\n\nThe domain the spec rests on: [the domain wiki](wiki/index.md).\n")))
      ;; contexts and components
      (doseq [ctx contexts]
        (write! (str (name (:id ctx)) "/index.md")
                (str "# " (:name ctx) "\n\n" (:description ctx) "\n\n" (note statuses)
                     (img (view-of :component (:id ctx)) "../")
                     "## Components\n\n"
                     (str/join (for [c (:components ctx)] (str "- [" (:name c) "](" (name (:id c)) ".md): " (:description c) "\n"))))))
      (when (seq kernel)
        (write! "kernel/index.md"
                (str "# Kernel\n\nShared code every bounded context may use, in none of them.\n\n" (note statuses)
                     "## Components\n\n"
                     (str/join (for [c kernel] (str "- [" (:name c) "](" (name (:id c)) ".md): " (:description c) "\n"))))))
      (doseq [c comps]
        (let [prose (when (string? (:spec c)) (project/read-text root (str (:dir spec) "/" (:spec c) ".md")))]
          (write! (page-of c) (component-md c (get by-comp (:id c)) prose statuses))))
      ;; the wiki
      (doseq [f wiki-files]
        (let [rel (project/rel (project/abs wiki) (project/abs f))
              text (project/read-text root f)]
          (write! (str "wiki/" rel) (str (link-citations text sources) "\n\n" (note statuses)))))
      ;; the builder's configuration
      (let [nav (concat [["Home" "index.md"]]
                        (for [ctx contexts]
                          [(:name ctx) (into [[(:name ctx) (str (name (:id ctx)) "/index.md")]]
                                             (for [c (:components ctx)] [(:name c) (str (name (:id ctx)) "/" (name (:id c)) ".md")]))])
                        (when (seq kernel)
                          [["Kernel" (into [["Kernel" "kernel/index.md"]] (for [c kernel] [(:name c) (page-of c)]))]])
                        (when (seq wiki-files)
                          [["Wiki" (vec (for [f wiki-files :let [rel (project/rel (project/abs wiki) (project/abs f))]]
                                          [(if (= rel "index.md") "Wiki"
                                               (or (some->> (project/read-text root f) (re-find #"(?m)^#\s+(.+)$") second str/trim)
                                                   (fs/file-name rel)))
                                           (str "wiki/" rel)]))]]))]
        (spit (project/abs (str source "/mkdocs.yml")) (mkdocs-yml title nav))))
    @pages))

(defn build!
  "Builds the site with the configured builder; a broken link or a missing
  builder leaves no HTML behind."
  [cfg]
  (let [site (project/abs (:dir (:site cfg) "site"))
        builder (:builder (:site cfg))
        r (project/sh builder)
        clean! #(when (fs/exists? site) (fs/delete-tree site))]
    (cond
      (nil? (:exit r))
      (do (clean!) (project/cannot-run! (str "the site builder " (first builder) " is not installed (" (:error r) "): no HTML was written")))
      (not= 0 (:exit r))
      (do (clean!)
          (let [out (str (:out r) "\n" (:err r))
                links (for [[_ page link] (re-seq #"Doc file '([^']+)' contains a link '([^']+)'" out)]
                        (project/finding (str (:source (:site cfg) "site-src") "/docs/" page) "site-link-broken"
                                         (str "the page " page " links " link ", which the site does not hold")))
                other (for [l (str/split-lines out) :when (re-find #"^(WARNING|ERROR)" l) :when (not (re-find #"contains a link" l))]
                        (project/finding (str (:source (:site cfg) "site-src") "/mkdocs.yml") "site-build-failed" (project/one-line l)))
                fs (concat links other)]
            (if (seq fs) fs [(project/finding (str (:source (:site cfg) "site-src") "/mkdocs.yml") "site-build-failed"
                                              (str "the site builder exited " (:exit r) ": " (project/one-line out)))])))
      :else [])))

(defn publish-stage [{:keys [cfg]}]
  (let [pages (write-site! cfg)
        findings (build! cfg)]
    {:result {:publish {:pages (count pages) :source (:source (:site cfg)) :site (:dir (:site cfg))
                        :status (if (bus/latest :VerificationDerived) :derived no-status)}}
     :findings (vec findings)
     :warnings []}))
