(ns rdm-spec.publishing.site
  "The site (dhf/spec/publishing/site.feature): one Markdown page per
  component and bounded context, a home page, the domain wiki, and the site
  builder's configuration, written under the configured source directory and
  built to HTML by the configured site builder. It reads the scenarios
  (SpecParsed), the check (SpecGated), the statuses (VerificationDerived) and
  the sources (KnowledgeIndexed) from the bus; it shows status, never decides it."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.command :as cmd]))

(def no-status "No status was derived: no results were given.")

(def citation-re #"`?([A-Za-z0-9][\w.-]*):([^\s`@\"'()\[\]]+)@([0-9a-fA-F]{4,40})`?")

(defn link-citations
  "Each citation of a source whose repository is known becomes a link to the cited file at its commit."
  [text repos]
  (str/replace text citation-re
               (fn [[m src path commit]]
                 (if-let [repo (get repos src)]
                   (str "[" src ":" path "@" commit "](" (str/replace repo #"/$" "") "/blob/" commit "/" path ")")
                   m))))

(defn broken-links
  "[[page link] ...] from the site builder's warnings about links it could not resolve."
  [output source]
  (for [[_ page link] (re-seq #"Doc file '([^']+)' contains a link '([^']+)', but the target[^\n]*" (str output))]
    [(str source "/docs/" page) link]))

(defn- yq [s] (str "\"" (str/replace (str s) "\"" "\\\"") "\""))

(defn mkdocs-yml
  "The site builder's configuration: the home page first, then a section per
  bounded context and one for the kernel, each its index then its
  components, then the wiki."
  [site-name sections wiki-pages]
  (str "site_name: " (yq site-name) "\n"
       "docs_dir: docs\n"
       "site_dir: ../site\n"
       "use_directory_urls: false\n"
       "nav:\n"
       "  - Home: index.md\n"
       (str/join (for [[title pages] sections]
                   (str "  - " (yq title) ":\n"
                        (str/join (for [[p label] pages]
                                    (if label (str "      - " (yq label) ": " p "\n") (str "      - " p "\n")))))))
       (when (seq wiki-pages)
         (str "  - Wiki:\n" (str/join (for [p wiki-pages] (str "      - " p "\n")))))))

(defn- status-line [statuses id]
  (if statuses (str "**" (name (get statuses id :unverified)) "**") "*no status derived*"))

(defn- scenario-md [s statuses]
  (str "### " (:id s) ": " (:title s) "\n\n"
       "*" (some-> (:level s) name) "*" (when (:wip? s) " · planned (@wip)") " · status: " (status-line statuses (:id s)) "\n\n"
       (str/join (for [st (concat (:background s) (:steps s))] (str "- " (:keyword st) " " (:text st) "\n")))
       (when (:outline? s)
         (str "\nExamples: " (str/join "; " (map #(str/join ", " %) (:rows s))) "\n"))
       "\n"))

(defn stage [{:keys [root config no-results]}]
  (let [{:keys [scenarios needs]} (bus/latest :SpecParsed)
        gated (bus/latest :SpecGated)
        derived (when-not no-results (bus/latest :VerificationDerived))
        statuses (:statuses derived)
        knowledge (bus/latest :KnowledgeIndexed)
        repos (into {} (for [s (:sources knowledge)] [(:id s) (:repository s)]))
        model (try (cmd/read-edn (cmd/read-at root nil (:model config "dhf/spec/model.edn"))) (catch Exception _ nil))
        spec-dir (:spec-dir config "dhf/spec")
        views-dir (:views-dir config "dhf/views")
        wiki-dir (:wiki-dir config "dhf/wiki")
        source (get-in config [:site :source] "site-src")
        site (get-in config [:site :dir] "site")
        docs (fs/path root source "docs")
        banner (when-not statuses (str "> " no-status "\n\n"))
        pages (atom {})
        page! (fn [p text] (swap! pages assoc p text))
        by-file (group-by :file scenarios)
        contexts (for [c (:containers model) cx (:contexts c)] cx)
        sections (concat (for [cx contexts] [(name (:id cx)) (:name cx) (:description cx) (:components cx)])
                         (when (seq (:kernel model)) [["kernel" "kernel" "Shared code no bounded context owns" (:kernel model)]]))
        view-of (fn [level ctx-id] (first (filter #(and (= level (:level %)) (or (nil? ctx-id) (= ctx-id (:context %)))) (:views model))))
        img (fn [v prefix] (when v (str "![" (:key v) "](" prefix "views/" (:key v) ".svg)\n\n")))]
    ;; components and their contexts
    (doseq [[dir title desc comps] sections]
      (page! (str dir "/index.md")
             (str "# " title "\n\n" banner (or desc "") "\n\n"
                  (img (view-of :component (keyword dir)) "../")
                  "## Components\n\n"
                  (str/join (for [k comps]
                              (str "- [" (:name k) "](" (last (str/split (:spec k) #"/")) ".md): " (:description k) "\n")))))
      (doseq [k comps]
        (let [feature (str spec-dir "/" (:spec k) ".feature")
              prose (cmd/read-at root nil (str spec-dir "/" (:spec k) ".md"))]
          (page! (str dir "/" (last (str/split (:spec k) #"/")) ".md")
                 (str "# " (:name k) "\n\n" banner (:description k) "\n\n"
                      (when prose (str prose "\n\n"))
                      "## Scenarios\n\n"
                      (str/join (for [s (sort-by :line (by-file feature)) :when (:id s)] (scenario-md s statuses))))))))
    ;; the home page
    (page! "index.md"
           (str "# " (get-in model [:system :name] "Spec") "\n\n" banner
                (get-in model [:system :description] "") "\n\n"
                (when gated (str "The spec check " (if (:passed? gated) "passes" (str "has " (count (:findings gated)) " findings")) ".\n\n"))
                (when-let [v (view-of :context nil)] (str "## Context\n\n" (img v "")))
                (when-let [v (view-of :container nil)] (str "## Containers\n\n" (img v "")))
                "## Bounded contexts\n\n"
                (str/join (for [[dir title] sections] (str "- [" title "](" dir "/index.md)\n")))
                "\n## The domain wiki\n\n- [The wiki's index](wiki/index.md)\n\n"
                "## User needs\n\n"
                (str/join (for [n needs]
                            (str "### " (:id n) "\n\n" (:text n) "\n\n"
                                 (str/join (for [s scenarios :when (and (:id s) (some #{(:id n)} (:needs s)))
                                                 :let [k (first (filter #(= (:component s) (:id %)) (mapcat #(nth % 3) sections)))
                                                       dir (if (= :kernel (:context s)) "kernel" (some-> (:context s) name))]]
                                             (str "- [" (:id s) "](" dir "/" (last (str/split (str (:spec k)) #"/")) ".md): "
                                                  (:title s) " (" (if statuses (name (get statuses (:id s) :unverified)) "no status") ")\n")))
                                 "\n")))))
    ;; the wiki
    (let [index (cmd/read-at root nil (str wiki-dir "/index.md"))
          wpages (filter #(str/ends-with? % ".md") (cmd/files-at root nil (str wiki-dir "/pages")))]
      (page! "wiki/index.md" (str banner (link-citations (or index "# Wiki\n") repos)))
      (doseq [p wpages]
        (page! (str "wiki/" (subs p (inc (count wiki-dir)))) (str banner (link-citations (cmd/read-at root nil p) repos))))
      ;; write it all afresh
      (fs/delete-tree (fs/path root source))
      (doseq [[p text] (sort @pages)]
        (fs/create-dirs (fs/parent (fs/path docs p)))
        (spit (fs/file docs p) text))
      (when (fs/directory? (fs/path root views-dir))
        (fs/create-dirs (fs/path docs "views"))
        (doseq [f (sort (map str (fs/glob (fs/path root views-dir) "*.svg")))]
          (fs/copy f (fs/path docs "views" (fs/file-name f)) {:replace-existing true})))
      (spit (fs/file root source "mkdocs.yml")
            (mkdocs-yml (get-in model [:system :name] "Spec")
                        (for [[dir title _ comps] sections]
                          [title (cons [(str dir "/index.md") nil]
                                       (for [k comps] [(str dir "/" (last (str/split (:spec k) #"/")) ".md") (:name k)]))])
                        (cons "wiki/index.md" (for [p wpages] (str "wiki/" (subs p (inc (count wiki-dir)))))))))
    ;; build it
    (let [builder (get-in config [:site :builder] ["mkdocs" "build" "--strict" "-f" (str source "/mkdocs.yml")])
          r (apply cmd/sh {:dir root} builder)
          clean! #(fs/delete-tree (fs/path root site))]
      (cond
        (:not-found r)
        (do (clean!) (cmd/cannot-run! (str "the site builder " (first builder) " is not installed: no HTML was written")))
        (not= 0 (:exit r))
        (let [out (str (:out r) (:err r))
              broken (broken-links out source)]
          (clean!)
          {:findings (if (seq broken)
                       (vec (for [[p l] broken] (cmd/finding p nil "broken-link" (str "the page links " l ", which the site does not hold: no HTML was written"))))
                       [(cmd/finding nil nil "site-build-failed" (str "the site builder failed, so no HTML was written: " out))])})
        :else
        {:result {:publish {:source source :site site :pages (count @pages)}}}))))
