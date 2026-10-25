(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature.

  Each scenario publishes a copy of the room's allow-list (steps.support),
  run through the command as its own process. The shared steps are defined
  elsewhere: `\"rdm-spec ...\" is run` and `... is run with no --results`
  (kernel_command), `... is run twice` (kernel_bus), `the configured results
  directory does not exist` (kernel_command) and `a rendered view whose
  recorded hash differs from the model's` (architecture_views).

  The views a scenario needs fresh are written here as fixtures, each output's
  first line recording the model hash as the glossary defines it (the
  SHA-256 of the model read as EDN and printed back with every map's keys
  sorted), computed here independently of the implementation."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The project and its fixtures

(def model-rel "dhf/spec/model.edn")

(defn- config [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- source-dir [dir] (fs/file dir (get-in (config dir) [:site :source])))
(defn- docs-dir [dir] (fs/file (source-dir dir) "docs"))
(defn- html-dir [dir] (fs/file dir (get-in (config dir) [:site :dir])))
(defn- views-dir [dir] (fs/file dir (or (:views-dir (config dir)) "dhf/views")))

(defn- needs-site! []
  (s/needs-built! 'rdm-spec.publishing.site 'rdm-spec.spec.check 'rdm-spec.spec.parser
                  'rdm-spec.knowledge.sources))

(defn read-model [dir] (edn/read-string (slurp (str (fs/file dir model-rel)))))

(defn write-model! [dir m] (s/write! dir model-rel (pr-str m)))

(defn- sha256-hex [^String s]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8")))))

(defn model-hash
  "The model hash of the glossary: the model read as EDN, printed back with
  every map's keys sorted."
  [dir]
  (let [cmp (fn [a b] (try (compare a b) (catch Exception _ (compare (pr-str a) (pr-str b)))))
        sorted (walk/postwalk #(if (map? %) (into (sorted-map-by cmp) %) %) (read-model dir))]
    (sha256-hex (binding [*print-namespace-maps* false *print-meta* false *print-length* nil]
                  (pr-str sorted)))))

(defn fresh-views!
  "Writes every view of the project's model as a fixture: its diagram source
  and its rendered SVG, each recording the current model hash on its first line."
  [dir]
  (let [h (model-hash dir)
        vd (views-dir dir)]
    (fs/create-dirs vd)
    (doseq [{k :key level :level} (:views (read-model dir))]
      (spit (str (fs/file vd (str k ".mmd")))
            (str "%% model-sha256: " h "; a fixture of the view " k "\nC4Context\ntitle " k "\n"))
      (spit (str (fs/file vd (str k ".svg")))
            (str "<!-- model-sha256: " h " -->\n"
                 "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"20\">"
                 "<text x=\"0\" y=\"15\">" k " (" (name level) ")</text></svg>\n")))
    dir))

(defn project!
  "A copy of the room whose model is `(f model)`, with fresh views."
  ([] (project! identity))
  ([f]
   (needs-site!)
   (let [dir (s/copy-of-room!)]
     (write-model! dir (f (read-model dir)))
     (fresh-views! dir))))

(defn- contexts-of [m] (vec (mapcat :contexts (:containers m))))

(defn- keep-contexts
  "The model with only the bounded contexts `ids`, in that order, and only
  the views of those contexts (and the views of no context)."
  [m ids]
  (let [by-id (into {} (map (juxt :id identity)) (contexts-of m))]
    (-> m
        (update :containers (fn [cs] (mapv #(if (:contexts %) (assoc % :contexts (mapv by-id ids)) %) cs)))
        (update :views (fn [vs] (filterv #(or (nil? (:context %)) (some #{(:context %)} ids)) vs))))))

;; ---------------------------------------------------------------------------
;; Reading the published site

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- passed! [ctx]
  (let [r (the-run ctx)]
    (s/check! (zero? (:exit r)) (str "publish did not pass\n" (s/show r)))
    r))

(defn page
  "The text of the published page `rel` (under the site's docs), or a failure."
  [ctx rel]
  (let [f (fs/file (docs-dir (:dir ctx)) rel)]
    (s/check! (fs/exists? f) (str "the site holds no page " rel "\n" (s/show (the-run ctx))))
    (slurp (str f))))

(defn pages
  "Every published Markdown page: {rel text}."
  [dir]
  (let [d (docs-dir dir)]
    (if (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**.md")]
                           [(str (fs/relativize d f)) (slurp (str f))]))
      {})))

(defn- scenario-section
  "The section of the published site about scenario `id`: from its heading to
  the next heading of its level or above. [page-rel text] or nil."
  [dir id]
  (first
   (for [[rel text] (pages dir)
         :let [m (re-find (re-pattern (str "(?ms)^(#{2,6}) [^\\n]*\\b" id "\\b[^\\n]*$")) text)]
         :when m
         :let [[whole hashes] m
               start (str/index-of text whole)
               rest-text (subs text (+ start (count whole)))
               end (some->> (re-find (re-pattern (str "(?m)^#{1," (count hashes) "} ")) rest-text)
                            (str/index-of rest-text))]]
     [rel (str whole (if end (subs rest-text 0 end) rest-text))])))

(defn- shows-status! [ctx id status]
  (passed! ctx)
  (let [[rel sec] (scenario-section (:dir ctx) id)]
    (s/check! sec (str "no page shows " id))
    (s/check! (re-find (re-pattern (str "(?i)\\bstatus\\b[^\\n]*\\b" status "\\b")) sec)
              (str id " on " rel " does not show " status ":\n" sec))))

(defn- feature-steps
  "The step lines of a feature file, trimmed."
  [text]
  (vec (for [l (str/split-lines text)
             :let [t (str/trim l)]
             :when (re-find #"^(Given|When|Then|And|But) " t)]
         t)))

(defn- feature-ids [text]
  (vec (distinct (map second (re-seq #"@(spec-\d{4,})" text)))))

(defn- traces
  "{need-id [scenario-id]}: every stamped scenario of the spec under `dir`, by
  the needs it traces to (on its feature, its rule or itself)."
  [dir]
  (let [files (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))]
    (reduce
     (fn [acc f]
       (loop [ls (str/split-lines (slurp f)) pending [] feat [] rule [] acc acc]
         (if-let [[l & more] (seq ls)]
           (let [t (str/trim l)
                 tags (when (str/starts-with? t "@") (re-seq #"@\S+" t))
                 needs-of (fn [ts] (filterv #(re-matches #"@UN-\d+" %) ts))]
             (cond
               tags (recur more (into pending tags) feat rule acc)
               (str/starts-with? t "Feature:") (recur more [] (needs-of pending) [] acc)
               (str/starts-with? t "Rule:") (recur more [] feat (needs-of pending) acc)
               (re-find #"^Scenario( Outline)?:" t)
               (let [id (some #(second (re-matches #"@(spec-\d{4,})" %)) pending)
                     ns* (distinct (concat feat rule (needs-of pending)))]
                 (recur more [] feat rule
                        (if id (reduce (fn [a n] (update a (subs n 1) (fnil conj []) id)) acc ns*) acc)))
               :else (recur more pending feat rule acc)))
           acc)))
     {} files)))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (let [dir (project! #(-> % (keep-contexts [:spec :knowledge]) (assoc :kernel [])))
          comps (mapcat :components (contexts-of (read-model dir)))]
      (s/check! (= 5 (count comps)) (str "the fixture holds " (count comps) " components"))
      (assoc ctx :dir dir :components (vec comps)))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (passed! ctx)
    (let [m (read-model (:dir ctx))
          ps (pages (:dir ctx))
          component-pages (filterv (fn [[rel _]] (re-matches #"[^/]+/[^/]+\.md" rel)) ps)
          component-pages (remove (fn [[rel _]] (or (str/ends-with? rel "/index.md") (str/starts-with? rel "wiki/")))
                                  component-pages)]
      (s/check! (= 5 (count component-pages))
                (str "the site holds " (count component-pages) " component pages: " (pr-str (map first component-pages))))
      (s/check! (not (some #(str/starts-with? % "kernel/") (keys ps))) "the site holds a kernel page")
      (doseq [c (contexts-of m) comp (:components c)
              :let [rel (str (:spec comp) ".md")
                    [ctx-dir _] (str/split (:spec comp) #"/")
                    text (page ctx rel)
                    title (first (filter #(str/starts-with? % "# ") (str/split-lines text)))]]
        (s/check! (= (:name c) ctx-dir) (str rel " is not under its context's name " (:name c)))
        (s/check! (and title (str/includes? title (:name comp)) (str/includes? title (:name c)))
                  (str rel "'s title does not name " (:name comp) " and " (:name c) ": " title))))))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [dir (project!)
          prose (str "# Lint\n\nThe lint reads the model once and reports every finding at its line.\n\n"
                     "It never stops at the first finding.\n")]
      (s/write! dir "dhf/spec/architecture/lint.md" prose)
      (assoc ctx :dir dir :prose prose))))

(defstep #"Lint's page holds its description, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          text (page ctx "architecture/lint.md")
          lint (first (for [c (contexts-of (read-model dir)) comp (:components c)
                            :when (= :architecture/lint (:id comp))] comp))
          feature (slurp (str (fs/file dir "dhf/spec/architecture/lint.feature")))]
      (s/check! (str/includes? text (:description lint)) "the page does not hold Lint's description")
      (doseq [l ["The lint reads the model once and reports every finding at its line."
                 "It never stops at the first finding."]]
        (s/check! (str/includes? text l) (str "the page does not hold the prose: " l)))
      (s/check! (seq (feature-ids feature)) "lint.feature holds no scenario")
      (doseq [id (feature-ids feature)
              :let [[_ sec] (scenario-section dir id)
                    level (second (re-find (re-pattern (str "@" id " @(e2e|contract)")) feature))]]
        (s/check! sec (str "the page does not show " id))
        (s/check! (re-find (re-pattern (str "(?i)\\blevel\\b[^\\n]*\\b" level "\\b")) sec)
                  (str id " does not show its level " level ":\n" sec)))
      (doseq [st (feature-steps feature)]
        (s/check! (str/includes? text st) (str "the page does not hold the step: " st))))))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (let [dir (project!)
          v (first (filter #(and (= :component (:level %)) (= :architecture (:context %))) (:views (read-model dir))))]
      (s/check! v "the model has no component view of architecture")
      (s/check! (fs/exists? (fs/file (views-dir dir) (str (:key v) ".svg"))) "the view is not rendered")
      (assoc ctx :dir dir :view (:key v)))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (passed! ctx)
    (let [text (page ctx "architecture/index.md")
          img (re-find (re-pattern (str "!\\[[^\\]]*\\]\\(([^)]*" (:view ctx) "\\.svg)\\)")) text)]
      (s/check! img (str "the architecture page shows no image of " (:view ctx) ":\n" text))
      (s/check! (fs/exists? (fs/file (docs-dir (:dir ctx)) "architecture" (second img)))
                (str "the image " (second img) " is not in the site"))
      (doseq [c ["lint" "model" "views"]]
        (s/check! (re-find (re-pattern (str "\\]\\(" c "\\.md\\)")) text)
                  (str "the architecture page does not link to " c ".md:\n" text))))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (passed! ctx)
    (let [dir (:dir ctx)
          text (page ctx "index.md")
          m (read-model dir)
          needs (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn"))))
          tr (traces dir)]
      (doseq [lvl [:context :container]
              :let [v (first (filter #(= lvl (:level %)) (:views m)))]]
        (s/check! v (str "the model has no " (name lvl) " view"))
        (s/check! (re-find (re-pattern (str "!\\[[^\\]]*\\]\\(views/" (:key v) "\\.svg\\)")) text)
                  (str "the home page does not show the " (name lvl) " view " (:key v))))
      (let [sections (into {} (for [sec (rest (str/split text #"(?m)^### "))
                                    :let [id (re-find #"^UN-\d+" sec)] :when id]
                                [id sec]))]
        (doseq [[k _] needs
                :let [id (name k) sec (get sections id)]]
          (s/check! sec (str "the home page shows no need " id))
          (doseq [sid (get tr id)]
            (s/check! (re-find (re-pattern (str "\\b" sid "\\b")) sec)
                      (str "the need " id " does not show " sid " tracing to it"))))))))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defn- result-json [id status]
  (str "{\"uuid\":\"" (java.util.UUID/randomUUID) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
       (when (not= "passed" status) "\"statusDetails\":{\"message\":\"it did not pass\"},")
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}],\"steps\":[]}"))

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (let [dir (project!)
          results (str (fs/file (s/tmp-dir) "results"))]
      (s/write! results "a-result.json" (result-json "spec-0012" "passed"))
      (s/write! results "b-result.json" (result-json "spec-0013" "failed"))
      (assoc ctx :dir dir :results-dir results))))

;; Only publish: verification_trace.clj defines the same When for trace.
(defstep #"\"rdm-spec (publish(?: [^\"]*)?)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results-dir ctx) "no results directory was given to stand for DIR")
    (let [args (mapv #(if (= "DIR" %) (:results-dir ctx) %) (s/split-args line))]
      (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args)))))

(defstep #"(spec-\d+) shows verified and (spec-\d+) shows failed"
  (fn [ctx a b]
    (shows-status! ctx a "verified")
    (shows-status! ctx b "failed")))

(defstep #"the scenario @(spec-\d+) tagged @wip"
  (fn [ctx id]
    (let [dir (project!)
          ;; the scenario's tag line, never a step that mentions the id
          tag-line (re-pattern (str "(?m)^(\\s*@" id ")(?=\\s)"))
          f (first (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
                         :when (re-find tag-line (slurp f))] f))]
      (s/check! f (str "no feature file holds " id))
      (spit f (str/replace-first (slurp f) tag-line (str "$1 @wip")))
      (assoc ctx :dir dir))))

(defstep #"(spec-\d+) shows planned"
  (fn [ctx id] (shows-status! ctx id "planned")))

(defstep #"every page says no status was derived because no results were given"
  (fn [ctx]
    (passed! ctx)
    (let [ps (pages (:dir ctx))]
      (s/check! (seq ps) "the site holds no page")
      (doseq [[rel text] ps]
        (s/check! (re-find #"(?i)no status was derived[^\n]*no results were given" text)
                  (str rel " does not say no status was derived because no results were given"))))))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec, never a copy that can drift

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (the-run ctx)
          view (or (:stale-view ctx) (s/fail! "the scenario names no stale view"))
          lines (filter #(and (str/includes? % (str "/" view "."))
                              (re-find (re-pattern (str "\\b" view "\\b[^\\n]*(older|stale)")) %))
                        (s/err-lines r))]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (seq lines) (str "no line names the stale view " view "\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file (docs-dir (:dir ctx)) "index.md")))
                "the site was written from stale views"))))

(defn- tree
  "{rel bytes-as-vector} of every file under `d`."
  [d]
  (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (vec (fs/read-all-bytes f))])))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (#{0 1} (:exit r)) (str "publish did not run\n" (s/show r)))
      (s/check! (= (tree (fs/file s/room-root "dhf/spec")) (tree (fs/file (:dir ctx) "dhf/spec")))
                "a file under dhf/spec changed"))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (let [dir (project!)
          results (fs/file dir (:results-dir (config dir)))]
      (s/write! results "a-result.json" (result-json "spec-0012" "passed"))
      (s/write! results "b-result.json" (result-json "spec-0013" "failed"))
      (assoc ctx :dir dir))))

(defn- written
  "What a run says it wrote: {rel sha256} of the site's Markdown and the
  builder's configuration."
  [r]
  (let [out (try (edn/read-string (:out r)) (catch Exception _ nil))]
    (get-in out [:site :files])))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (let [[r1 r2] (:runs ctx)
          dir (:dir ctx)]
      (s/check! (and r1 r2) "publish was not run twice")
      (s/check! (and (zero? (:exit r1)) (zero? (:exit r2))) (str (s/show r1) "\n" (s/show r2)))
      (let [w1 (written r1) w2 (written r2)
            on-disk (into (sorted-map)
                          (for [f (fs/glob (source-dir dir) "**")
                                :when (and (fs/regular-file? f)
                                           (re-find #"\.(md|yml)$" (str f)))]
                            [(str (fs/relativize (source-dir dir) f))
                             (apply str (map #(format "%02x" (bit-and % 0xff))
                                             (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                                      (fs/read-all-bytes f))))]))]
        (s/check! (seq w1) (str "the first run does not say what it wrote\n" (s/show r1)))
        (s/check! (some #(str/ends-with? % "mkdocs.yml") (keys on-disk)) "no site builder configuration")
        (s/check! (= w1 w2) "the two runs wrote different files")
        (s/check! (= on-disk (into (sorted-map) (filter #(re-find #"\.(md|yml)$" (key %)) w2)))
                  (str "what the second run says it wrote is not what is on disk:\n"
                       (pr-str on-disk) "\n" (pr-str w2)))))))

;; ---------------------------------------------------------------------------
;; The site publishes the domain wiki beside the spec

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (let [dir (project!)
          wiki (:wiki-dir (config dir))]
      (s/write! dir (str wiki "/pages/regeneration.md")
                (str "# Regeneration\n\nThe code is a build product, regenerated from the spec.[^1]\n\n"
                     "[^1]: `" citation "`, the opening section.\n"))
      (spit (str (fs/file dir wiki "index.md"))
            (str (slurp (str (fs/file dir wiki "index.md")))
                 "\n- [Regeneration](pages/regeneration.md): the code as a build product.\n"))
      ;; :page is the page in the project, as the wiki's own steps name it
      (assoc ctx :dir dir :wiki-page "wiki/pages/regeneration.md" :citation citation
             :page (str wiki "/pages/regeneration.md")))))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (passed! ctx)
    (let [text (page ctx (:wiki-page ctx))
          links (map second (re-seq #"\]\((https?://[^)\s]+)\)" text))]
      (s/check! (str/includes? text (:citation ctx)) "the page does not show the citation")
      (s/check! (some #(and (str/includes? % repo) (str/includes? % (str "/" commit "/"))
                            (str/ends-with? % (str "/" path)))
                      links)
                (str "no link to " path " at " commit " in " repo ": " (pr-str links)))
      (s/check! (fs/exists? (fs/file (html-dir (:dir ctx)) "wiki/pages/regeneration/index.html"))
                "the page was not built to HTML"))))

;; ---------------------------------------------------------------------------
;; The site builder builds the site, from a configuration the model writes

(defn- nav-entry
  "[title target] of a nav item: a page (target a string) or a section
  (target a vector of items)."
  [item]
  (let [[k v] (first item)] [(name k) v]))

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components"
  (fn [ctx]
    (let [dir (project! #(keep-contexts % [:spec :architecture]))]
      (s/check! (seq (:kernel (read-model dir))) "the fixture's model has no kernel")
      (assoc ctx :dir dir))))

(defn- builder-config [dir]
  (yaml/parse-string (slurp (str (fs/file (source-dir dir) "mkdocs.yml"))) :keywords false))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (passed! ctx)
    (let [nav (mapv nav-entry (get (builder-config (:dir ctx)) "nav"))]
      (s/check! (= "index.md" (second (first nav))) (str "the home page is not first: " (pr-str nav)))
      (s/check! (= ["spec" "architecture" "kernel"] (mapv first (subvec nav 1 (min 4 (count nav)))))
                (str "the sections are " (pr-str (map first nav))))
      ;; Only the domain wiki may follow; no other bounded context does.
      (s/check! (every? #(= "wiki" (first %)) (drop 4 nav)) (str "the sections are " (pr-str (map first nav))))
      (assoc ctx :nav nav))))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [m (read-model (:dir ctx))
          by-name (merge (into {} (for [c (contexts-of m)] [(:name c) (mapv :spec (:components c))]))
                         {"kernel" (mapv :spec (:kernel m))})]
      (doseq [[section items] (subvec (:nav ctx) 1 4)
              :let [targets (mapv (comp second nav-entry) items)]]
        (s/check! (= (str section "/index.md") (first targets))
                  (str section " does not list its context's page first: " (pr-str targets)))
        (s/check! (= (mapv #(str % ".md") (get by-name section)) (vec (rest targets)))
                  (str section " lists " (pr-str (rest targets)) ", not its components in the model's order"))))))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx] (assoc ctx :dir (project!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (let [r (passed! ctx)
          builder (get-in (config (:dir ctx)) [:site :builder])
          out (edn/read-string (:out r))]
      (s/check! (some #{"--strict"} builder) "the configured site builder is not strict")
      (s/check! (= builder (get-in out [:site :builder])) (str "the site was not built by " (pr-str builder) "\n" (s/show r)))
      (doseq [f ["index.html" "architecture/index.html" "architecture/lint/index.html"]]
        (s/check! (fs/exists? (fs/file (html-dir (:dir ctx)) f)) (str "no HTML " f))))))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (let [dir (project!)]
      (s/write! dir "dhf/spec/architecture/lint.md"
                "# Lint\n\nIts rules are listed on [the rules page](no-such-rules-page.md).\n")
      (assoc ctx :dir dir :page "architecture/lint.md" :link "no-such-rules-page.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (:page ctx)) (str/includes? % (:link ctx))) (s/err-lines r))
                (str "no line names " (:page ctx) " and " (:link ctx) "\n" (s/show r))))))

(def missing-builder "rdm-spec-test-no-such-site-builder")

(defstep #"the configured site builder is not installed"
  (fn [ctx]
    (let [dir (project!)
          c (config dir)]
      (s/check! (nil? (fs/which missing-builder)) (str missing-builder " is installed"))
      (s/write! dir "dhf/harness/config.edn"
                (pr-str (assoc-in c [:site :builder] (into [missing-builder] (rest (get-in c [:site :builder]))))))
      (assoc ctx :dir dir))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (s/err-lines r)]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (str/blank? (:out r)) "a task that cannot run printed a result")
      (s/check! (some #(and (str/includes? % missing-builder) (re-find #"(?i)no HTML was written" %)) lines)
                (str "no line names " missing-builder " and says no HTML was written\n" (s/show r)))
      (s/check! (not (fs/exists? (html-dir (:dir ctx)))) "HTML was written"))))
