(ns rdm-spec.publishing.site
  "Site (dhf/spec/publishing/site.feature), the one component of the
  publishing context: `rdm-spec publish [--results DIR]`.

  It writes the site as Markdown, with the site builder's configuration,
  under the configured :site :source (docs/ and mkdocs.yml), then runs the
  configured :site :builder, which writes the HTML to :site :dir. It never
  writes anything in the spec.

  What it reads, and from where:
    the scenarios and the needs   SpecParsed (spec/parser), on the bus
    each scenario's status        VerificationDerived (verification/status),
                                  when results were given; planned (@wip)
                                  comes from the scenario itself
    the sources' repositories     KnowledgeIndexed (knowledge/sources), to link
                                  the wiki's citations
    the model, the views, the     the repository: the configured :model,
    prose, the wiki pages         :views-dir, :spec-dir and :wiki-dir

  Views are read as dhf/spec/architecture/views.feature (@spec-0017) records
  them: each output (dhf/views/<key>.mmd and .svg) carries, on its first
  line, `model-hash: ` and the SHA-256 of model.edn's bytes as lowercase hex.
  A view whose output records another hash, or none, is stale; publishing
  refuses (rule view-stale, exit 1) and writes nothing. A view the model names
  with no output is view-missing, also exit 1.

  The pages, under docs/:
    index.md                    home: the context and container views (and any
                                dynamic view), the bounded contexts, every
                                user need with the scenarios tracing to it, the wiki
    <context>/index.md          a bounded context: its component view, its components
    <spec-path>.md              a component (its :spec, e.g. architecture/lint.md):
                                its description, its prose (<spec-path>.md beside
                                the feature file), every scenario with its id,
                                level, status and steps
    kernel/index.md, kernel/*   the kernel's components, the same way
    wiki/**                     every wiki page, its citations linked to their source
    views/<key>.svg             every view's render
  Every page says how status was derived, or that none was.

  The navigation is the home page, then one section per bounded context in
  the model's order (its page, then its components' pages in order), then the
  kernel's. Wiki pages are linked from the home page, not the navigation.

  A missing site builder cannot run (exit 2, naming it, saying no HTML was
  written); a build that fails is a finding (exit 1): a broken link names
  the page and the link (rule site-broken-link), anything else is site-build."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Events

(defn subscriptions
  "Keeps the run's SpecParsed, KnowledgeIndexed and VerificationDerived."
  [req]
  (let [keep! (fn [k] (fn [payload] (swap! (:state req) assoc k payload) nil))]
    [[:SpecParsed (keep! ::spec)]
     [:KnowledgeIndexed (keep! ::knowledge)]
     [:VerificationDerived (keep! ::statuses)]]))

;; ---------------------------------------------------------------------------
;; Hashes and views

(defn sha256-hex
  "The SHA-256 of `bs` (bytes), as lowercase hex."
  [^bytes bs]
  (let [d (.digest (java.security.MessageDigest/getInstance "SHA-256") bs)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) d))))

(defn first-line [^String text]
  (first (str/split-lines (or text ""))))

(defn recorded-hash
  "The model hash a view's output records on its first line, or nil."
  [text]
  (second (re-find #"model-(?:hash|sha256): ([0-9a-f]{64})(?![0-9a-f])" (or (first-line text) ""))))

(defn view-findings
  "A finding for every view of `model` whose outputs are missing or record
  another hash than `hash`. `read-output` gives an output's text, or nil when
  it does not exist; `views-dir` names them."
  [model hash views-dir read-output]
  (vec (for [{k :key} (:views model)
             ext ["mmd" "svg"]
             :let [rel (str views-dir "/" k "." ext)
                   text (read-output rel)
                   h (when text (recorded-hash text))]
             :when (not= h hash)]
         (cond
           (nil? text) (command/finding rel nil "view-missing"
                                        (str "the view \"" k "\" has no " ext " output: run rdm-spec views"))
           (nil? h) (command/finding rel 1 "view-stale"
                                     (str "the view \"" k "\" records no model hash on its first line, so it is older than"
                                          " the model (" hash "): run rdm-spec views"))
           :else (command/finding rel 1 "view-stale"
                                  (str "the view \"" k "\" records the model hash " h ", not the model's " hash
                                       ": run rdm-spec views"))))))

;; ---------------------------------------------------------------------------
;; Markdown helpers

(defn escape
  "Text made safe for a Markdown heading, list item or paragraph."
  [s]
  (-> (str s) (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn fence
  "A code fence longer than any run of backticks in `text`."
  [text]
  (let [longest (reduce max 0 (map count (re-seq #"`+" (or text ""))))]
    (apply str (repeat (max 3 (inc longest)) "`"))))

(defn demote-headings
  "Markdown with every ATX heading one level deeper, code blocks untouched."
  [text]
  (loop [[l & more :as ls] (str/split-lines (or text "")) in-code nil out []]
    (if (empty? ls)
      (str/join "\n" out)
      (let [opener (second (re-find #"^\s*(`{3,}|~{3,})" l))]
        (cond
          (and in-code opener (str/starts-with? opener in-code)) (recur more nil (conj out l))
          in-code (recur more in-code (conj out l))
          opener (recur more opener (conj out l))
          (re-find #"^#{1,5} " l) (recur more nil (conj out (str "#" l)))
          :else (recur more nil (conj out l)))))))

(defn relative
  "The link from the page `from` to the page `to`, both relative to docs/."
  [from to]
  (let [from-parts (vec (butlast (str/split from #"/")))
        to-parts (str/split to #"/")
        common (count (take-while true? (map = from-parts to-parts)))]
    (str/join "/" (concat (repeat (- (count from-parts) common) "..") (drop common to-parts)))))

;; ---------------------------------------------------------------------------
;; The model's pages

(defn- slug [s] (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" "")))

(defn context-name [c] (or (:name c) (some-> (:id c) name)))

(defn component-page
  "A component's page, relative to docs/: its :spec, else its context and name."
  [ctx-name comp]
  (if (:spec comp)
    (str (:spec comp) ".md")
    (str (slug ctx-name) "/" (slug (:name comp)) ".md")))

(defn sections
  "The site's sections, in the model's order, the kernel last when it has
  components: [{:name :description :page :view :components [{.. :page}]}]."
  [model]
  (let [views (:views model)
        view-of (fn [ctx-id] (first (filter #(and (= :component (:level %)) (= ctx-id (:context %))) views)))]
    (vec (concat
          (for [c (:containers model) ctx (:contexts c)
                :let [n (context-name ctx)]]
            {:name n :id (:id ctx) :description (:description ctx)
             :page (str (slug n) "/index.md")
             :view (view-of (:id ctx))
             :components (mapv #(assoc % :page (component-page n %)) (:components ctx))})
          (when (seq (:kernel model))
            [{:name "kernel" :id :kernel
              :description "Shared code every bounded context may use and none of them owns."
              :page "kernel/index.md"
              :view (view-of :kernel)
              :components (mapv #(assoc % :page (component-page "kernel" %)) (:kernel model))}])))))

(defn- yaml-str [s] (json/generate-string (str s)))

(defn mkdocs-yml
  "The site builder's configuration: the site's name, where its Markdown is
  and where the HTML goes, the Markdown extensions the pages use, and the
  navigation, which follows the model."
  [{:keys [site-name site-dir sections]}]
  (str/join
   "\n"
   (concat
    ["# Written by rdm-spec publish from the spec and its model; do not edit."
     (str "site_name: " (yaml-str site-name))
     "docs_dir: \"docs\""
     (str "site_dir: " (yaml-str site-dir))
     "use_directory_urls: true"
     "markdown_extensions:"
     "  - \"toc\""
     "  - \"tables\""
     "  - \"fenced_code\""
     "  - \"footnotes\""
     "nav:"
     (str "  - " (yaml-str "Home") ": " (yaml-str "index.md"))]
    (mapcat (fn [{:keys [name page components]}]
              (concat [(str "  - " (yaml-str name) ":")
                       (str "      - " (yaml-str name) ": " (yaml-str page))]
                      (for [c components]
                        (str "      - " (yaml-str (:name c)) ": " (yaml-str (:page c))))))
            sections)
    [""])))

;; ---------------------------------------------------------------------------
;; Status

(defn status-of
  "What the site shows as a scenario's status: planned when tagged @wip,
  else what was derived from the results, else nil (nothing derived)."
  [statuses sc]
  (cond (:wip? sc) "planned"
        (nil? statuses) nil
        (nil? (:id sc)) "unverified"
        :else (name (get statuses (:id sc) :unverified))))

(defn status-notice
  "The line every page carries about how status was derived."
  [{:keys [derived? results]}]
  (if derived?
    (str "*Status derived from the Allure results in `" results "`; the site shows it and never decides it.*")
    "*No status was derived because no results were given.*"))

;; ---------------------------------------------------------------------------
;; Pages

(defn- view-image [from view]
  (when view
    (str "![" (escape (str (:key view) ": " (:description view))) "](" (relative from (str "views/" (:key view) ".svg")) ")")))

(defn scenario-md
  "One scenario on its component's page: an anchor, its id and name, its
  level, status and needs, and its steps."
  [sc status]
  (let [steps (concat (when (seq (:background sc)) [["Background:" (:background sc)]])
                      [[nil (:steps sc)]])
        step-lines (fn [st]
                     (concat [(str (:keyword st) " " (:text st))]
                             (when-let [t (:data-table st)] (map #(str "  | " (str/join " | " %) " |") t))
                             (when-let [d (:doc-string st)] (concat ["  \"\"\""] (map #(str "  " %) (str/split-lines (str d))) ["  \"\"\""]))))
        lines (concat
               (mapcat (fn [[head sts]]
                         (concat (when head [head]) (map #(str (when head "  ") %) (mapcat step-lines sts))))
                       steps)
               (for [ex (:examples sc)
                     l (concat ["" "Examples:" (str "  | " (str/join " | " (:header ex)) " |")]
                               (map #(str "  | " (str/join " | " (:cells %)) " |") (:rows ex)))]
                 l))
        body (str/join "\n" lines)
        f (fence body)]
    (str/join "\n"
              [(str "<a id=\"" (or (:id sc) (str "line-" (:line sc))) "\"></a>")
               ""
               (str "#### " (or (:id sc) "(no id)") ": " (escape (:name sc)))
               ""
               (str "**Level:** " (if (:level sc) (name (:level sc)) "none")
                    " · **Status:** " (or status "not derived")
                    (when (seq (:needs sc)) (str " · **Needs:** " (str/join ", " (:needs sc))))
                    (when (:wip? sc) " · planned (@wip)"))
               ""
               (str f "gherkin")
               body
               f
               ""])))

(defn component-md
  "A component's page."
  [{:keys [section comp prose scenarios statuses notice]}]
  (str/join
   "\n"
   (concat
    [(str "# " (escape (:name section)) ": " (escape (:name comp)))
     ""
     (escape (:description comp))
     ""
     notice
     ""
     (str "Bounded context: [" (escape (:name section)) "](" (relative (:page comp) (:page section)) ")")
     ""]
    (when (some? prose)
      ["## Design" "" (demote-headings (str/trim prose)) ""])
    ["## Scenarios" ""]
    (if (empty? scenarios)
      ["No scenario is specified for this component." ""]
      (loop [[sc & more :as scs] scenarios rule ::none out []]
        (if (empty? scs)
          out
          (let [r (:rule sc)
                head (when (not= r rule) (if r [(str "### Rule: " (escape r)) ""] []))]
            (recur more r (into (into out head) [(scenario-md sc (status-of statuses sc))]))))))
    [""])))

(defn- counts-line [scenarios statuses]
  (let [fr (frequencies (map #(or (status-of statuses %) "not derived") scenarios))]
    (if (empty? scenarios)
      "no scenario"
      (str (count scenarios) " scenario" (when (not= 1 (count scenarios)) "s") ": "
           (str/join ", " (for [k ["verified" "failed" "unverified" "planned" "not derived"] :when (fr k)]
                            (str (fr k) " " k)))))))

(defn context-md
  "A bounded context's page (or the kernel's)."
  [{:keys [section by-page statuses notice]}]
  (let [page (:page section)]
    (str/join
     "\n"
     (concat
      [(str "# " (escape (:name section))) "" (escape (:description section)) "" notice ""]
      (when-let [img (view-image page (:view section))]
        ["## Component view" "" img ""])
      ["## Components" ""]
      (for [c (:components section)]
        (str "- [" (escape (:name c)) "](" (relative page (:page c)) "): " (escape (:description c))
             " (" (counts-line (get by-page (:page c)) statuses) ")"))
      [""]))))

(defn- citation-link
  "A citation as a link to its source at its pin, or nil when the source is
  unknown or its repository is no URL."
  [repos citation]
  (when-let [[_ src path commit] (re-matches #"([A-Za-z0-9][\w.-]*):([^\s@`]+)@([0-9a-fA-F]{4,40})" citation)]
    (when-let [repo (get repos src)]
      (when (re-find #"^https?://" repo)
        (str (-> repo (str/replace #"/+$" "") (str/replace #"\.git$" "")) "/blob/" commit "/" path)))))

(def ^:private citation-src
  "A citation inside prose: source:path@commit (a URL is none)."
  "([A-Za-z0-9][\\w.-]*):(?!/)[^\\s`'\"@()\\[\\]<>]+@[0-9a-fA-F]{7,40}(?![\\w])")

(def ^:private linkable-re
  (re-pattern (str "\\[[^\\]\\n]*\\]\\([^)\\n]*\\)"           ; a link already: left alone
                   "|`(" citation-src ")`"                         ; a citation in code
                   "|(?<![\\w/.@`-])(" citation-src ")")))        ; a bare citation

(defn link-citations
  "Wiki Markdown with every citation of a known source linked to the cited
  file at its pin, in code or not. `repos` maps a source id to its repository."
  [text repos]
  (str/replace (or text "") linkable-re
               (fn [[whole in-code _ bare]]
                 (let [c (or in-code bare)
                       u (when c (citation-link repos c))]
                   (if u (str "[" whole "](" u ")") whole)))))

(defn home-md
  "The home page."
  [{:keys [model sections spec statuses notice by-id wiki-pages repos]}]
  (let [views (:views model)
        of-level (fn [lvl] (filter #(= lvl (:level %)) views))
        scenarios (:scenarios spec)
        declared (let [n (:needs spec)] (if (map? n) n (into {} (map (juxt :id identity)) n)))
        need-ids (sort (distinct (concat (keys declared) (mapcat :needs scenarios))))]
    (str/join
     "\n"
     (concat
      [(str "# " (escape (or (get-in model [:system :name]) "The spec")))
       ""
       (escape (get-in model [:system :description]))
       ""
       notice
       ""]
      (mapcat (fn [[title lvl]]
                (when-let [vs (seq (of-level lvl))]
                  (concat [(str "## " title) ""] (mapcat #(vector (view-image "index.md" %) "") vs))))
              [["Context view" :context] ["Container view" :container] ["Dynamic views" :dynamic]])
      ["## Bounded contexts" ""]
      (for [s sections] (str "- [" (escape (:name s)) "](" (:page s) "): " (escape (:description s))))
      ["" "## User needs" ""]
      (mapcat (fn [n]
                (let [d (get declared n)
                      tracing (filter #(some #{n} (:needs %)) scenarios)]
                  (concat
                   [(str "### " n) ""
                    (if d (escape (:text d)) "*Not declared in the needs file.*") ""]
                   (when (seq (:sources d))
                     [(link-citations (str "Sources: " (str/join ", " (map #(str "`" % "`") (:sources d)))) repos) ""])
                   (if (seq tracing)
                     (concat
                      (for [sc tracing
                            :let [page (by-id sc)]]
                        (str "- " (if (and page (:id sc))
                                    (str "[" (:id sc) "](" page "#" (:id sc) ")")
                                    (or (:id sc) "(no id)"))
                             ": " (escape (:name sc))
                             " (" (or (status-of statuses sc) "not derived") ")"))
                      [""])
                     ["No scenario traces to this need." ""]))))
              need-ids)
      ["## Domain wiki" ""]
      (if (seq wiki-pages)
        (for [p wiki-pages] (str "- [" (escape (:title p)) "](wiki/" (:path p) ")"))
        ["The wiki holds no page."])
      [""]))))

;; ---------------------------------------------------------------------------
;; Reading

(defn- read-edn [f]
  (try (edn/read-string (slurp (str f))) (catch Exception _ nil)))

(defn- page-title [text fallback]
  (or (second (re-find #"(?m)^# (.+)$" (or text ""))) fallback))

(defn- wiki-pages [root wiki-dir]
  (let [d (command/path root wiki-dir)]
    (if (fs/directory? d)
      (vec (for [f (sort-by str (fs/glob d "**"))
                 :when (fs/regular-file? f)
                 :let [rel (str/replace (str (fs/relativize d f)) "\\" "/")]]
             {:path rel :file f :md? (str/ends-with? rel ".md")}))
      [])))

;; ---------------------------------------------------------------------------
;; Writing

(defn- write-file! [root rel content]
  (let [f (command/path root rel)]
    (fs/create-dirs (fs/parent f))
    (if (bytes? content)
      (fs/write-bytes f content)
      (spit f content))
    rel))

(defn- digest [root rel] (sha256-hex (fs/read-all-bytes (command/path root rel))))

(def broken-link-re #"Doc file '([^']+)' contains a link '([^']+)'(?:, but the target(?: '[^']*')? is not found)?")

(defn build-findings
  "Findings from the site builder's output (its exit code and text), naming
  the Markdown under `docs-rel`."
  [exit output docs-rel]
  (let [warnings (filter #(re-find #"^(WARNING|ERROR)\b" %) (str/split-lines (or output "")))
        fs (vec (for [w warnings]
                  (if-let [[_ page link] (re-find broken-link-re w)]
                    (command/finding (str docs-rel "/" page) nil "site-broken-link"
                                     (str "the page " page " links to " link ", which the site does not hold"))
                    (command/finding "site-build" (str/trim (str/replace w #"^(WARNING|ERROR)\s*-\s*" ""))))))]
    (cond
      (zero? exit) fs
      (seq fs) fs
      :else [(command/finding "site-build"
                              (str "the site builder exited " exit ": "
                                   (str/join " | " (take-last 5 (remove str/blank? (str/split-lines (or output "")))))))])))

(defn- run-builder! [root builder]
  (let [exe (first builder)
        found (when exe (fs/which exe))]
    (when-not found
      (command/cannot-run!
       (str "the site builder " (or exe "(none configured)") " is not installed (configured as :site :builder in "
            command/config-file "): no HTML was written; the Markdown and the builder's configuration were")))
    (let [r (apply p/shell {:dir (str root) :out :string :err :string :continue true} (str found) (rest builder))]
      {:exit (:exit r) :output (str (:out r) "\n" (:err r))})))

;; ---------------------------------------------------------------------------
;; The stage

(defn publish
  "The publish task's own stage: writes the site, then builds it."
  [req]
  (let [root (:root req)
        site (command/config-value req :site)
        source (or (:source site) (command/cannot-run! (str command/config-file " sets no :site :source")))
        html-dir (or (:dir site) (command/cannot-run! (str command/config-file " sets no :site :dir")))
        builder (:builder site)
        model-rel (command/config-value req :model)
        views-dir (command/config-value req :views-dir)
        spec-dir (command/config-value req :spec-dir)
        wiki-dir (get-in req [:config :wiki-dir])
        model-file (command/path root model-rel)
        _ (when-not (fs/regular-file? model-file)
            (command/cannot-run! (str "the model " model-rel " does not exist")))
        model (let [m (read-edn model-file)]
                (if (map? m) m (command/cannot-run! (str "the model " model-rel " is not readable EDN"))))
        hash (sha256-hex (fs/read-all-bytes model-file))
        read-output (fn [rel] (let [f (command/path root rel)] (when (fs/regular-file? f) (slurp f))))
        stale (view-findings model hash views-dir read-output)]
    (if (seq stale)
      {:result {:site {:written? false :model-hash hash}} :findings stale}
      (let [state @(:state req)
            spec (or (::spec state)
                     (command/cannot-run! "no SpecParsed was published in this run: the spec context is not built, so the site has no scenarios"))
            derived (::statuses state)
            statuses (:statuses derived)
            notice (status-notice {:derived? (some? derived) :results (:results req)})
            repos (into {} (for [s (:sources (::knowledge state))] [(str (:id s)) (:repository s)]))
            secs (sections model)
            all-comps (for [s secs c (:components s)] [s c])
            page-of-spec (into {} (for [[_ c] all-comps :when (:spec c)] [(:spec c) (:page c)]))
            by-page (group-by #(get page-of-spec (:spec-path %)) (:scenarios spec))
            by-id (fn [sc] (get page-of-spec (:spec-path sc)))
            docs (str source "/docs")
            wiki (if wiki-dir (wiki-pages root wiki-dir) [])
            wiki-md (vec (for [w wiki :when (:md? w)]
                           (assoc w :title (page-title (slurp (str (:file w))) (:path w)))))
            files (atom [])
            emit! (fn [rel content] (swap! files conj (write-file! root rel content)))]
        ;; a fresh source directory: the site is written whole every time
        (fs/delete-tree (command/path root docs))
        (emit! (str docs "/index.md")
               (home-md {:model model :sections secs :spec spec :statuses statuses :notice notice
                         :by-id by-id :wiki-pages wiki-md :repos repos}))
        (doseq [s secs]
          (emit! (str docs "/" (:page s)) (context-md {:section s :by-page by-page :statuses statuses :notice notice}))
          (doseq [c (:components s)
                  :let [prose-f (when (:spec c) (command/path root (str spec-dir "/" (:spec c) ".md")))]]
            (emit! (str docs "/" (:page c))
                   (component-md {:section s :comp c :statuses statuses :notice notice
                                  :prose (when (and prose-f (fs/regular-file? prose-f)) (slurp prose-f))
                                  :scenarios (get by-page (:page c))}))))
        (doseq [{k :key} (:views model)]
          (emit! (str docs "/views/" k ".svg") (fs/read-all-bytes (command/path root (str views-dir "/" k ".svg")))))
        (doseq [w wiki]
          (emit! (str docs "/wiki/" (:path w))
                 (if (:md? w)
                   (str (str/trimr (link-citations (slurp (str (:file w))) repos)) "\n\n" notice "\n")
                   (fs/read-all-bytes (:file w)))))
        (let [cfg-rel (str source "/mkdocs.yml")
              site-dir (str/replace (str (fs/relativize (fs/absolutize (command/path root source))
                                                        (fs/absolutize (command/path root html-dir))))
                                    "\\" "/")]
          (emit! cfg-rel (mkdocs-yml {:site-name (or (get-in model [:system :name]) "Spec")
                                      :site-dir site-dir :sections secs}))
          (let [written (into (sorted-map) (for [rel @files] [rel (digest root rel)]))
                result {:site {:written? true :source source :config cfg-rel :dir html-dir
                               :pages (count (filter #(str/ends-with? % ".md") @files))
                               :status-derived? (some? derived)
                               :model-hash hash
                               :files written}}
                {:keys [exit output]} (run-builder! root builder)
                findings (build-findings exit output docs)]
            {:result (assoc-in result [:site :built?] (and (zero? exit) (empty? findings)))
             :findings findings}))))))
