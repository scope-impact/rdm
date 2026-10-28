(ns rdm-spec.publishing.site
  "Publishes the spec, its views, its status and the domain wiki as one site:
  Markdown and the site builder's configuration under the source directory,
  built strictly into HTML by the configured site builder
  (dhf/spec/09-publish/site.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.spec.parser :as parser]
            [rdm-spec.verification.results :as results]
            [rdm-spec.verification.status :as status]))

(def no-status "No status was derived because no results were given.")

(defn status-text [st] (if st (name st) no-status))

(defn component-page [c] (str (namespace (:id c)) "/" (name (:id c)) ".md"))

(defn- ctx-dir [ctx-id] (name ctx-id))

(defn- y [s] (str "'" (str/replace (str s) "'" "''") "'"))

(defn nav-yaml
  "The navigation, following the model: home, each bounded context with its components, the kernel, the wiki."
  [model wiki-pages]
  (let [ctxs (mapcat :contexts (:containers model))
        section (fn [title idx comps]
                  (str "  - " (y title) ":\n"
                       "    - " idx "\n"
                       (str/join (for [c comps] (str "    - " (y (:name c)) ": " (component-page c) "\n")))))]
    (str "nav:\n"
         "  - Home: index.md\n"
         (str/join (for [c ctxs] (section (:name c (name (:id c))) (str (ctx-dir (:id c)) "/index.md") (:components c))))
         (when (seq (:kernel model)) (section "kernel" "kernel/index.md" (:kernel model)))
         (when (seq wiki-pages)
           (str "  - Wiki:\n" (str/join (for [p wiki-pages] (str "    - " p "\n"))))))))

(defn citation-url [repo commit path]
  (str (str/replace (str repo) #"(\.git)?/?$" "") "/blob/" commit "/" path))

(defn link-footnotes
  "Links every citation of a footnote definition to its source at its pin."
  [text idx]
  (str/join "\n"
            (for [line (str/split-lines (str text))]
              (if (re-find #"^\s*\[\^[^\]]+\]:" line)
                (str/replace line #"`?([A-Za-z0-9][A-Za-z0-9._-]*):([^@\s`'\"]+)@([0-9a-fA-F]{7,40})`?"
                             (fn [[m source path commit]]
                               (if-let [repo (get-in idx [(keyword source) :repository])]
                                 (str "[`" source ":" path "@" commit "`](" (citation-url repo commit path) ")")
                                 m)))
                line))))

(defn- steps-block [sc]
  (str "```gherkin\n" (str/join "\n" (for [s (concat (:background sc) (:steps sc))] (str (:keyword s) " " (:text s)))) "\n```\n"))

(defn- scenario-section [sc statuses derived?]
  (let [st (if derived? (status-text (get statuses (:id sc))) (if (:wip? sc) "planned" "no status"))]
  (str "### " (or (:id sc) "(unstamped)") ": " (:title sc) " (" (some-> (:level sc) name) ", " st ")\n\n"
       "Level: " (some-> (:level sc) name) ". Status: " (if (or derived? (:wip? sc)) st no-status) "\n\n"
       (steps-block sc) "\n")))

(defn- prose-of [root spec comp]
  (let [dir (:dir spec)
        cands (if (= :slices (:layout spec))
                (for [f (project/files-under root dir "md")
                      :let [r (subs f (inc (count dir))) parts (str/split r #"/")]
                      :when (and (= 2 (count parts)) (parser/slice-folder? (first parts)) (= (str (name (:id comp)) ".md") (second parts)))]
                  f)
                (when (:spec comp) [(str dir "/" (:spec comp) ".md")]))]
    (str/join "\n\n" (for [f cands :when (fs/exists? (fs/path root f))] (slurp (str (fs/path root f)))))))

(defn- note [derived?] (if derived? "" (str "\n> " no-status "\n")))

(defn- write! [docs rel text]
  (let [f (fs/path docs rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) text)))

(defn write-site!
  "Writes the site's Markdown and the builder's configuration; returns the pages written."
  [root cfg spec idx statuses derived? wiki-dir]
  (let [src (fs/path root (get-in cfg [:site :source] "site-src"))
        docs (fs/path src "docs")
        model (or (:model spec) {})
        scs (:scenarios spec)
        by-comp (group-by :component scs)
        ctxs (vec (mapcat :contexts (:containers model)))
        views-dir (:views-dir cfg)
        view-img (fn [v up] (let [f (fs/path root views-dir (str (:key v) ".svg"))]
                              (when (fs/exists? f)
                                (fs/create-dirs (fs/path docs "views"))
                                (fs/copy f (fs/path docs "views" (str (:key v) ".svg")) {:replace-existing true})
                                (str "![" (:key v) "](" up "views/" (:key v) ".svg)\n\n"))))
        comp-page (fn [c ctx-name]
                    (str "# " (namespace (:id c)) "/" (name (:id c)) ": " (:name c) "\n\n"
                         "Bounded context: " ctx-name ".\n\n"
                         (:description c) "\n\n"
                         (let [p (prose-of root spec c)] (when-not (str/blank? p) (str p "\n\n")))
                         "## Scenarios\n\n"
                         (str/join (for [sc (sort-by :line (get by-comp (:id c)))] (scenario-section sc statuses derived?)))
                         (note derived?)))
        ctx-page (fn [title desc comps view]
                   (str "# " title "\n\n" desc "\n\n"
                        (when view (view-img view "../"))
                        "## Components\n\n"
                        (str/join (for [c comps] (str "- [" (:name c) "](" (name (:id c)) ".md): " (:description c) "\n")))
                        (note derived?)))
        wiki-files (if (fs/directory? (fs/path root wiki-dir))
                     (vec (filter #(str/ends-with? % ".md") (project/files-under root wiki-dir)))
                     [])
        wiki-rel (fn [f] (str "wiki/" (subs f (inc (count wiki-dir)))))
        wiki-pages (vec (concat (when (some #{(str wiki-dir "/index.md")} wiki-files) ["wiki/index.md"])
                                (sort (remove #{"wiki/index.md"} (map wiki-rel wiki-files)))))]
    (when (fs/exists? src) (fs/delete-tree src))
    (fs/create-dirs docs)
    ;; home
    (write! docs "index.md"
            (str "# " (get-in model [:system :name] "The spec") "\n\n"
                 (get-in model [:system :description]) "\n"
                 (note derived?) "\n"
                 "## Views\n\n"
                 (str/join (for [v (:views model) :when (#{:context :container} (:level v))] (view-img v "")))
                 "## Bounded contexts\n\n"
                 (str/join (for [c ctxs] (str "- [" (:name c (name (:id c))) "](" (ctx-dir (:id c)) "/index.md): " (:description c) "\n")))
                 (when (seq (:kernel model)) "- [kernel](kernel/index.md): shared code in no bounded context\n")
                 "\n## User needs\n\n"
                 (str/join (for [n (sort-by :id (:needs spec))]
                             (str "### " (:id n) "\n\n" (:text n) "\n\n"
                                  (str/join (for [sc (filter #(and (:id %) (some #{(:id n)} (:needs %))) scs)
                                                  :let [c (some #(when (= (:component sc) (:id %)) %) (parser/components model))]]
                                              (str "- " (if c (str "[" (:id sc) "](" (component-page c) ")") (:id sc)) ": " (:title sc)
                                                   ", " (if derived? (status-text (get statuses (:id sc))) (if (:wip? sc) "planned" "no status")) "\n")))
                                  "\n")))
                 (when (seq wiki-pages) "## Wiki\n\n- [The domain wiki](wiki/index.md)\n")))
    (doseq [c ctxs]
      (write! docs (str (ctx-dir (:id c)) "/index.md")
              (ctx-page (:name c (name (:id c))) (:description c) (:components c)
                        (some #(when (and (= :component (:level %)) (= (:id c) (:context %))) %) (:views model))))
      (doseq [comp (:components c)] (write! docs (component-page comp) (comp-page comp (:name c (name (:id c)))))))
    (when (seq (:kernel model))
      (write! docs "kernel/index.md" (ctx-page "kernel" "Shared code every bounded context may use, in none of them." (:kernel model) nil))
      (doseq [comp (:kernel model)] (write! docs (component-page comp) (comp-page comp "kernel"))))
    (doseq [f wiki-files]
      (write! docs (wiki-rel f) (str (link-footnotes (slurp (str (fs/path root f))) idx) "\n" (note derived?))))
    (spit (str (fs/path src "mkdocs.yml"))
          (str "site_name: " (y (get-in model [:system :name] "The spec")) "\n"
               "docs_dir: docs\n"
               "site_dir: ../" (get-in cfg [:site :dir] "site") "\n"
               "use_directory_urls: false\n"
               (nav-yaml model wiki-pages)))
    {:source (str src)}))

(defn- remove-html! [root cfg]
  (let [d (fs/path root (get-in cfg [:site :dir] "site"))]
    (when (fs/exists? d) (fs/delete-tree d))))

(defn publish [run]
  (let [root (:root run) cfg (:cfg run)
        gate ((requiring-resolve 'rdm-spec.architecture.views/freshness) run)]
    (if (seq (:findings gate))
      {:findings (:findings gate) :result {:publish {:built false :says "the views are older than the model: nothing was published"}}}
      (let [reading (parser/read-specs root cfg)
            _ (bus/publish! :SpecParsed :spec/parser (parser/spec-parsed-event :working-tree reading))
            spec (first (:specs reading))
            idx (let [f (fs/path root (:raw-dir cfg) "sources.edn")] (or (try (edn/read-string (slurp (str f))) (catch Exception _ nil)) {}))
            given? ((requiring-resolve 'rdm-spec.kernel.command/results-given?) run)
            dir ((requiring-resolve 'rdm-spec.kernel.command/results-dir) run)
            exists? (fs/directory? (fs/path root dir))
            _ (when (and given? (not exists?)) (project/cannot-run! (str "the results directory " dir " does not exist")))
            derived? (and exists? (seq (fs/glob (fs/path root dir) "*-result.json")))
            statuses (when derived?
                       (let [{rs :results} (results/read-dir (str (fs/path root dir)) dir)
                             d (status/derive-statuses (:scenarios spec) rs)]
                         (bus/publish! :VerificationDerived :verification/status
                                       {:revision :working-tree :statuses (:statuses d) :unknown-results (:unknown d) :unreadable-files []})
                         (:statuses d)))
            {:keys [source]} (write-site! root cfg spec idx statuses derived? (:wiki-dir cfg))
            builder (get-in cfg [:site :builder])
            r (project/run-cmd builder {:dir root :seconds 600})
            rel-src (get-in cfg [:site :source] "site-src")]
        (cond
          (:not-found? r)
          (do (remove-html! root cfg)
              (project/cannot-run! (str "the site builder " (first builder) " is not installed: no HTML was written")))
          (not (zero? (:exit r)))
          (let [out (str (:out r) "\n" (:err r))
                broken (distinct (re-seq #"Doc file '([^']+)' contains a link '([^']+)'" out))]
            (remove-html! root cfg)
            {:exit 1
             :findings (vec (if (seq broken)
                              (for [[_ page link] broken]
                                (project/finding (str rel-src "/docs/" page) "site-link-broken" (str "the page links " link ", which the site does not hold: no HTML was written")))
                              [(project/finding (str rel-src "/mkdocs.yml") "site-build-failed"
                                                (str "the site builder failed: " (project/one-line (last (remove str/blank? (str/split-lines out))))))]))
             :result {:publish {:built false}}})
          :else
          {:result {:publish {:built true :source rel-src :site (get-in cfg [:site :dir] "site")
                              :status (if derived? :derived :not-derived)}}})))))
