(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature.

  The site's scenarios run `rdm-spec publish` in a copy of the room's
  allow-listed paths, or in a small project of their own (a model, a feature
  file per component and views recording the model's hash), and read back
  what it wrote: the Markdown under the configured :site :source (its docs/
  and the site builder's configuration) and the HTML under :site :dir.

  One step is shared in wording with dhf/spec/knowledge/wiki.feature and
  defined here, with the one meaning both features give it: \"a wiki page
  whose footnote cites C\" makes a fresh copy of the room whose wiki holds
  the page dhf/wiki/regeneration.md, listed in the index, its one footnote
  [^1] citing C; ctx :dir, :page (the page's path), :citation, :footnote.
  \"a rendered view whose recorded hash differs from the model's\" is the
  architecture context's (it sets :dir and :stale-view); the runs are the
  kernel's.

  Context keys: :dir the project, :run the last run, :results a results
  directory, :twin an untouched copy of the project's inputs, :broken-page
  and :broken-link, :builder."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def site-ns ['rdm-spec.publishing.site 'rdm-spec.knowledge.sources 'rdm-spec.spec.check
              'rdm-spec.spec.parser 'rdm-spec.verification.status])

(defn- needs-site! [] (apply s/needs-built! site-ns))

;; ---------------------------------------------------------------------------
;; Where the site is

(defn- site-config [] (:site (s/room-config)))
(defn- source-dir [ctx] (fs/file (:dir ctx) (:source (site-config))))
(defn- docs-dir [ctx] (fs/file (source-dir ctx) "docs"))
(defn- html-dir [ctx] (fs/file (:dir ctx) (:dir (site-config))))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- md-files
  "Every Markdown page the site holds, relative to its docs directory, sorted."
  [ctx]
  (let [d (docs-dir ctx)]
    (if (fs/directory? d)
      (vec (sort (for [f (fs/glob d "**.md")] (str/replace (str (fs/relativize d f)) "\\" "/"))))
      [])))

(defn- page-text [ctx rel]
  (let [f (fs/file (docs-dir ctx) rel)]
    (s/check! (fs/regular-file? f) (str "the site holds no page " rel "\n" (s/show (the-run ctx))))
    (slurp (str f))))

(defn- published! [ctx]
  (let [r (the-run ctx)]
    (s/check! (#{0 1} (:exit r)) (str "publish did not run\n" (s/show r)))
    (s/check! (seq (md-files ctx)) (str "publish wrote no page\n" (s/show r)))
    r))

(defn- links
  "Every link and image target of the page `rel`, resolved against its
  directory (anchors dropped); URLs stay as written."
  [ctx rel]
  (let [text (page-text ctx rel)
        dir (fs/parent (fs/path rel))]
    (set (for [[_ t] (re-seq #"\]\(([^)\s]+)\)" text)
               :let [t (str/replace t #"#.*$" "")]
               :when (not (str/blank? t))]
           (if (re-find #"^[a-z]+:" t)
             t
             (str/replace (str (fs/normalize (if dir (fs/path dir t) (fs/path t)))) "\\" "/"))))))

(defn- html-files [ctx]
  (let [d (html-dir ctx)]
    (if (fs/directory? d) (vec (fs/glob d "**.html")) [])))

(defn- tree
  "{relative-path bytes-as-string} of every file under `dir`."
  [dir]
  (if (fs/directory? dir)
    (into (sorted-map)
          (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
            [(str (fs/relativize dir f)) (vec (fs/read-all-bytes f))]))
    {}))

;; ---------------------------------------------------------------------------
;; Reading feature files back, independently of the implementation

(def ^:private step-re #"^\s*(Given|When|Then|And|But)\s+(.*)$")

(defn- tags-of [l] (vec (re-seq #"@[\w.-]+" l)))

(defn read-feature
  "The scenarios of a feature text: [{:id :level :needs #{} :wip? :title :steps [text]}].
  Needs and @wip are inherited from the feature and the rule."
  [text]
  (loop [[l & more] (str/split-lines text) pending [] feature-tags [] rule-tags [] cur nil out []]
    (let [flush (fn [out] (if cur (conj out cur) out))]
      (if (nil? l)
        (flush out)
        (let [t (str/trim l)]
          (cond
            (str/starts-with? t "@") (recur more (into pending (tags-of t)) feature-tags rule-tags cur out)
            (str/starts-with? t "Feature:") (recur more [] pending [] nil (flush out))
            (str/starts-with? t "Rule:") (recur more [] feature-tags pending nil (flush out))
            (re-find #"^(Scenario|Scenario Outline):" t)
            (let [all (concat feature-tags rule-tags pending)]
              (recur more [] feature-tags rule-tags
                     {:id (some #(second (re-matches #"@(spec-\d{4,})" %)) pending)
                      :level (some #(second (re-matches #"@(e2e|contract)" %)) pending)
                      :needs (set (keep #(second (re-matches #"@(UN-\d+)" %)) all))
                      :wip? (boolean (some #{"@wip"} all))
                      :title (str/trim (second (str/split t #":" 2)))
                      :steps []}
                     (flush out)))
            (re-find #"^(Background|Examples):" t) (recur more pending feature-tags rule-tags
                                                          (when (and cur (str/starts-with? t "Examples")) (assoc cur ::done true))
                                                          out)
            :else
            (if-let [[_ _ st] (and cur (not (::done cur)) (re-matches step-re l))]
              (recur more pending feature-tags rule-tags (update cur :steps conj st) out)
              (recur more pending feature-tags rule-tags cur out))))))))

(defn- room-scenarios
  "Every scenario of the spec in `dir`."
  [dir]
  (vec (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
             sc (read-feature (slurp f))]
         (assoc sc :file f))))

;; ---------------------------------------------------------------------------
;; The model hash (glossary: Model hash), computed here independently

(defn- printed [x]
  (binding [*print-namespace-maps* false *print-meta* false *print-length* nil *print-level* nil]
    (pr-str x)))

(defn- sorted-form [x]
  (cond
    (map? x) (let [es (sort-by (comp printed first) (map (fn [[k v]] [(sorted-form k) (sorted-form v)]) x))]
               (str "{" (str/join ", " (map (fn [[k v]] (str k " " v)) es)) "}"))
    (set? x) (str "#{" (str/join " " (sort (map sorted-form x))) "}")
    (vector? x) (str "[" (str/join " " (map sorted-form x)) "]")
    (seq? x) (str "(" (str/join " " (map sorted-form x)) ")")
    :else (printed x)))

(defn- hash-of [m]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes ^String (sorted-form m) "UTF-8")))))

;; ---------------------------------------------------------------------------
;; A small project of the scenario's own

(defn- fresh-views!
  "Writes a diagram source and a rendering of every view of `model`, each
  recording the model's hash on its first line."
  [dir model]
  (let [h (hash-of model)
        vd (or (:views-dir (s/room-config)) "dhf/views")]
    (when (fs/exists? (fs/file dir vd)) (fs/delete-tree (fs/file dir vd)))
    (doseq [{k :key} (:views model)]
      (s/write! dir (str vd "/" k ".mmd") (str "%% model-sha256: " h "; a fixture view\nC4Context\ntitle " k "\n"))
      (s/write! dir (str vd "/" k ".svg")
                (str "<!-- model-sha256: " h "; a fixture view -->\n"
                     "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><title>" k "</title></svg>\n")))))

(defn- small-project!
  "A copy of the room whose spec is replaced by a model of `contexts`
  ([[context-name [component-name ...]] ...]) and `kernel` ([component-name
  ...]), one feature file per component holding one stamped scenario, and
  fresh views. Returns {:dir :model}."
  [contexts kernel]
  (let [dir (s/copy-of-room!)
        _ (fs/delete-tree (fs/file dir "dhf/spec"))
        comp (fn [ctx c] {:id (keyword ctx c) :name (str/capitalize c) :spec (str ctx "/" c)
                          :description (str "Does the " c " of " ctx)})
        model {:system {:id :demo :name "Demo" :description "A demonstration"}
               :people [{:id :user :name "User" :description "Uses the demo" :actor #{:human}}]
               :external []
               :containers [{:id :app :name "App" :kind :runs :technology "a program" :description "The demo's program"
                             :contexts (vec (for [[ctx cs] contexts]
                                              {:id (keyword ctx) :name ctx :description (str "The " ctx " context")
                                               :components (mapv #(comp ctx %) cs)}))}]
               :kernel (mapv #(comp "kernel" %) kernel)
               :relationships [{:from :user :to :app :description "Uses"}]
               :views (vec (concat [{:key "context" :level :context :description "The demo and its user"}
                                    {:key "containers" :level :container :description "The program"}]
                                   (for [[ctx] contexts]
                                     {:key (str ctx "-components") :level :component :context (keyword ctx)
                                      :description (str "The " ctx " context")})))}
        comps (concat (for [[ctx cs] contexts c cs] [ctx c]) (for [c kernel] ["kernel" c]))]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/events.edn" "{}\n")
    (s/write! dir "dhf/spec/needs.edn"
              (pr-str {"UN-001" {:text "A user reads the demo's spec." :sources ["loyly:README.md@17d68d2"]}}))
    (s/write! dir "dhf/spec/ids.edn" (pr-str {:issued (count comps)}))
    (doseq [[i [ctx c]] (map-indexed vector comps)]
      (s/write! dir (str "dhf/spec/" ctx "/" c ".feature")
                (str "@UN-001\nFeature: " (str/capitalize c) "\n  What " c " does.\n\n"
                     "  @" (format "spec-%04d" (inc i)) " @e2e\n"
                     "  Scenario: The " c " works\n"
                     "    Given a demo\n    When the " c " is used\n    Then it works\n")))
    (fresh-views! dir model)
    {:dir dir :model model}))

(defn- result-file! [dir id status]
  (fs/create-dirs dir)
  (spit (str (fs/file dir (str id "-" status "-result.json")))
        (json/generate-string (cond-> {:uuid (str id "-" status) :name (str "the scenario " id) :status status
                                       :stage "finished" :labels [{:name "spec" :value id}] :steps []}
                                (not= "passed" status) (assoc :statusDetails {:message "it did not pass"})))))

(defn- status-shown
  "The status the site shows for the scenario `id`: the status on the line
  that names it on its component's page."
  [ctx id]
  (let [hits (for [rel (md-files ctx)
                   :when (not (str/ends-with? rel "index.md"))
                   l (str/split-lines (page-text ctx rel))
                   :when (str/includes? l (str "`" id "`"))
                   :let [[_ st] (re-find #"status: \*\*([^*]+)\*\*" l)]
                   :when st]
               st)]
    (s/check! (= 1 (count hits)) (str "the site shows " id " " (count hits) " times, not once: " (pr-str hits)))
    (first hits)))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (needs-site!)
    (let [{:keys [dir model]} (small-project! [["orders" ["cart" "pay" "refund"]] ["stock" ["count" "reorder"]]] [])]
      (assoc ctx :dir dir :model model))))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (let [r (published! ctx)
          pages (remove #(or (str/ends-with? % "index.md") (str/starts-with? % "wiki/")) (md-files ctx))]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (= #{"orders/cart.md" "orders/pay.md" "orders/refund.md" "stock/count.md" "stock/reorder.md"} (set pages))
                (str "the component pages are " (pr-str pages))))))

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          prose "The lint holds the model to the C4 checklist.\n\nIt names every element it refuses, one finding each.\n"]
      (s/check! (fs/exists? (fs/file dir "dhf/spec/architecture/lint.feature")) "the room has no Lint feature")
      (s/write! dir "dhf/spec/architecture/lint.md" (str "## How the lint reads\n\n" prose))
      (assoc ctx :dir dir :prose prose))))

(defstep #"Lint's page holds its description, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (published! ctx)
    (let [text (page-text ctx "architecture/lint.md")
          unesc #(str/replace % "\\|" "|")
          model (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/model.edn"))))
          desc (some #(when (= :architecture/lint (:id %)) (:description %))
                     (for [cn (:containers model) c (:contexts cn) comp (:components c)] comp))
          scs (read-feature (slurp (str (fs/file (:dir ctx) "dhf/spec/architecture/lint.feature"))))
          lines (mapv unesc (str/split-lines text))]
      (s/check! (str/includes? text desc) (str "the page does not hold the description " desc))
      (doseq [p (remove str/blank? (str/split-lines (:prose ctx)))]
        (s/check! (str/includes? text p) (str "the page does not hold the prose " p)))
      (s/check! (seq scs) "the Lint feature holds no scenario")
      (doseq [{:keys [id level steps]} scs
              :let [at (first (keep-indexed (fn [i l] (when (str/includes? l (str "`" id "`")) i)) lines))]]
        (s/check! at (str "the page does not hold " id))
        (s/check! (str/includes? (nth lines at) level) (str "the page does not give " id " its level " level))
        (let [section (take-while #(not (re-find #"`spec-\d{4,}`" %)) (drop (inc at) lines))]
          (doseq [st steps]
            (s/check! (some #(str/includes? % st) section) (str "the page does not hold " id "'s step: " st))))))))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          vd (or (:views-dir (s/room-config)) "dhf/views")]
      (s/check! (fs/exists? (fs/file dir vd "architecture-components.svg")) "the room has no rendered architecture view")
      (assoc ctx :dir dir))))

(defn- components-of [dir ctx-id]
  (let [model (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn"))))]
    (vec (for [cn (:containers model) c (:contexts cn) :when (= ctx-id (:id c)) comp (:components c)] comp))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (published! ctx)
    (let [ls (links ctx "architecture/index.md")
          text (page-text ctx "architecture/index.md")
          vd (or (:views-dir (s/room-config)) "dhf/views")]
      (s/check! (re-find #"!\[[^\]]*\]\([^)]*architecture-components\.svg\)" text) "the page shows no image of the view")
      (s/check! (contains? ls "views/architecture-components.svg") (str "the view's image does not resolve: " (pr-str ls)))
      (s/check! (= (vec (fs/read-all-bytes (fs/file (:dir ctx) vd "architecture-components.svg")))
                   (vec (fs/read-all-bytes (fs/file (docs-dir ctx) "views/architecture-components.svg"))))
                "the site's view is not the rendered one")
      (doseq [c (components-of (:dir ctx) :architecture)]
        (s/check! (contains? ls (str (:spec c) ".md")) (str "the page does not link " (:spec c) ".md: " (pr-str ls)))
        (s/check! (fs/exists? (fs/file (docs-dir ctx) (str (:spec c) ".md"))) (str "no page " (:spec c) ".md"))))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (published! ctx)
    (let [dir (:dir ctx)
          model (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn"))))
          needs (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn"))))
          text (page-text ctx "index.md")
          ls (links ctx "index.md")
          scs (room-scenarios dir)
          blocks (into {} (for [b (str/split text #"\n(?=#+ )")
                                :let [[_ id] (re-find #"^#+ (UN-\d+)" b)] :when id]
                            [id b]))]
      (doseq [lvl [:context :container]
              :let [v (some #(when (= lvl (:level %)) %) (:views model))]]
        (s/check! v (str "the model has no " lvl " view"))
        (s/check! (contains? ls (str "views/" (:key v) ".svg")) (str "the home page does not show the view " (:key v))))
      (s/check! (seq needs) "the spec declares no need")
      (doseq [[id {need-text :text}] needs
              :let [b (get blocks id)]]
        (s/check! b (str "the home page does not show the need " id))
        (s/check! (str/includes? b need-text) (str "the home page does not give " id "'s text"))
        (doseq [sc scs :when (and (:id sc) (contains? (:needs sc) id))]
          (s/check! (str/includes? b (:id sc)) (str "the home page does not list " (:id sc) " under " id)))))))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which (\S+) passed and (\S+) failed"
  (fn [ctx passed failed]
    (needs-site!)
    (let [dir (str (fs/file (s/tmp-dir) "results"))]
      (result-file! dir passed "passed")
      (result-file! dir failed "failed")
      (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)) :results dir))))

(defstep #"\"rdm-spec (publish[^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results ctx) "the scenario made no results directory")
    ;; DIR is this scenario's results directory; the kernel's When fills DIR
    ;; only with a --sources directory
    (kc/run-line ctx (str/replace line "DIR" (str (:results ctx))))))

(defstep #"(\S+) shows (\w+) and (\S+) shows (\w+)"
  (fn [ctx id1 st1 id2 st2]
    (published! ctx)
    (doseq [[id st] [[id1 st1] [id2 st2]]]
      (let [shown (status-shown ctx id)]
        (s/check! (= st shown) (str id " shows " shown ", not " st))))))

(defstep #"the scenario @(\S+) tagged @wip"
  (fn [ctx id]
    (needs-site!)
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          tag-line (re-pattern (str "(?m)^(\\s*(?:@\\S+\\s+)*@" id ")(?!\\d)"))
          f (or (first (for [f (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))
                             :when (re-find tag-line (slurp f))]
                         f))
                (s/fail! (str "no feature file tags a scenario @" id)))
          text (slurp f)]
      (spit f (str/replace-first text tag-line "$1 @wip"))
      (assoc ctx :dir dir :planned id))))

(defstep #"(\S+) shows (\w+)"
  (fn [ctx id st]
    (published! ctx)
    (let [shown (status-shown ctx id)]
      (s/check! (= st shown) (str id " shows " shown ", not " st)))))

(defstep #"every page, the wiki's included, says no status was derived because no results were given"
  (fn [ctx]
    (published! ctx)
    (let [pages (md-files ctx)]
      (s/check! (some #(str/starts-with? % "wiki/") pages) (str "the site holds no wiki page: " (pr-str pages)))
      (doseq [p pages
              :let [t (str/lower-case (page-text ctx p))]]
        (s/check! (and (str/includes? t "no status was derived") (str/includes? t "no results were given"))
                  (str "the page " p " does not say no status was derived because no results were given"))))))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (the-run ctx)
          k (:stale-view ctx)
          lines (remove #(or (str/starts-with? % "event: ") (str/includes? % "warning: ")) (s/err-lines r))]
      (s/check! k "the scenario made no stale view")
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (pr-str k)) (str/includes? % "stale")) lines)
                (str "no finding names the view " k " as stale\n" (s/show r)))
      (s/check! (every? #(str/includes? % (pr-str k)) (filter #(str/includes? % "stale") lines))
                (str "other views than " k " are named stale\n" (s/show r))))))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (published! ctx)
    (s/check! (= (tree (fs/file s/room-root "dhf/spec")) (tree (fs/file (:dir ctx) "dhf/spec")))
              "a file under dhf/spec changed, appeared or went away")))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          rd (fs/file dir (:results-dir (s/room-config)))
          twin (s/tmp-dir)]
      (result-file! rd "spec-0012" "passed")
      (result-file! rd "spec-0013" "failed")
      (fs/copy-tree dir twin)
      (assoc ctx :dir dir :twin twin))))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (let [[r1 r2] (:runs ctx)
          twin-run (kc/run! ctx (:twin ctx) ["publish"])
          mine (tree (source-dir ctx))
          theirs (tree (source-dir (assoc ctx :dir (:twin ctx))))]
      (s/check! (and r1 r2) "publish was not run twice")
      (doseq [r [r1 r2 twin-run]] (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r))))
      (s/check! (seq mine) "publish wrote no site")
      (s/check! (some #(str/ends-with? % ".yml") (keys mine)) "publish wrote no site builder configuration")
      (s/check! (= (keys mine) (keys theirs))
                (str "the sites hold different files: " (pr-str (keys mine)) " " (pr-str (keys theirs))))
      (doseq [k (keys mine)]
        (s/check! (= (get mine k) (get theirs k)) (str "the sites differ in " k))))))

;; ---------------------------------------------------------------------------
;; The domain wiki

(def ^:private footnoted-paragraph
  "The code is built from the spec, and can be deleted and built again.[^1]")

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  ;; Shared in wording with dhf/spec/knowledge/wiki.feature, the same meaning there.
  (fn [ctx citation]
    (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.knowledge.wiki)
    (let [dir (s/copy-of-room!)
          wiki (or (:wiki-dir (s/room-config)) "dhf/wiki")
          rel "regeneration.md"]
      (s/write! dir (str wiki "/" rel)
                (str "# Regeneration\n\n" footnoted-paragraph "\n\n[^1]: `" citation "`, \"What to steal\" 1.\n"))
      (spit (str (fs/file dir wiki "index.md")) (str "\n- [A page](" rel ")\n") :append true)
      (assoc ctx :dir dir :page (str wiki "/" rel) :citation citation :footnote "[^1]"))))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (published! ctx)
    (let [wiki (or (:wiki-dir (s/room-config)) "dhf/wiki")
          rel (str "wiki/" (subs (:page ctx) (inc (count wiki))))
          text (page-text ctx rel)
          fn-line (or (first (filter #(str/starts-with? (str/trim %) "[^1]:") (str/split-lines text)))
                      (s/fail! (str "the published page has no footnote [^1]:\n" text)))
          urls (map second (re-seq #"\]\(([^)\s]+)\)" fn-line))]
      (s/check! (some #(and (str/includes? % (str "/" repo "/")) (str/includes? % (str "/" commit "/"))
                            (str/ends-with? % (str "/" path)))
                      urls)
                (str "the footnote links to " (pr-str urls) ", not " path " at " commit " in " repo)))))

;; ---------------------------------------------------------------------------
;; The site builder builds the site

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components, and one kernel component"
  (fn [ctx]
    (needs-site!)
    (let [{:keys [dir model]} (small-project! [["spec" ["parser" "stamper" "check"]]
                                               ["architecture" ["model" "lint" "views"]]]
                                              ["bus"])]
      (assoc ctx :dir dir :model model))))

(defn- nav [ctx]
  (let [f (fs/file (source-dir ctx) "mkdocs.yml")]
    (s/check! (fs/exists? f) (str "no site builder configuration " f "\n" (s/show (the-run ctx))))
    (:nav (yaml/parse-string (slurp (str f))))))

(defn- entry [m] (let [[k v] (first m)] [(name k) v]))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (published! ctx)
    (let [n (nav ctx)
          heads (map (comp first entry) n)]
      (s/check! (= ["Home" "index.md"] (entry (first n))) (str "the first entry is " (pr-str (first n))))
      (s/check! (= ["spec" "architecture" "kernel"] (take 3 (rest heads)))
                (str "the sections are " (pr-str heads))))))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [n (into {} (map entry (rest (nav ctx))))
          model (:model ctx)
          sections (concat (for [cn (:containers model) c (:contexts cn)] [(:name c) (name (:id c)) (:components c)])
                           [["kernel" "kernel" (:kernel model)]])]
      (doseq [[sname key comps] sections
              :let [items (map entry (get n sname))]]
        (s/check! (= (cons (str key "/index.md") (map #(str (:spec %) ".md") comps)) (map second items))
                  (str "the section " sname " lists " (pr-str items)))))))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx]
    (needs-site!)
    (assoc ctx :dir (s/copy-of-room!))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (let [r (published! ctx)
          builder (:builder (site-config))]
      (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
      (s/check! (some #{"--strict"} builder) (str "the configured builder does not fail on a broken link: " builder))
      (doseq [p (md-files ctx)
              :let [h (fs/file (html-dir ctx) (str/replace p #"\.md$" ".html"))
                    h2 (fs/file (html-dir ctx) (str/replace p #"(index)?\.md$" "") "index.html")]]
        (s/check! (or (fs/exists? h) (fs/exists? h2)) (str "no HTML for " p))))))

(defn- old-html! [ctx]
  (s/write! (:dir ctx) (str (:dir (site-config)) "/old.html") "<html>an earlier build</html>\n"))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (needs-site!)
    (let [ctx (assoc ctx :dir (s/copy-of-room!))]
      (s/write! (:dir ctx) "dhf/spec/architecture/lint.md"
                "The lint's checklist is explained on [a page that is not there](no-such-page.md).\n")
      (old-html! ctx)
      (assoc ctx :broken-page "architecture/lint.md" :broken-link "no-such-page.md"))))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (the-run ctx)]
      (s/check! (= 1 (:exit r)) (str "expected exit 1\n" (s/show r)))
      (s/check! (some #(and (str/includes? % (:broken-page ctx)) (str/includes? % (:broken-link ctx))) (s/err-lines r))
                (str "no line names " (:broken-page ctx) " and " (:broken-link ctx) "\n" (s/show r))))))

(defstep #"no HTML is left in the site directory"
  (fn [ctx]
    (s/check! (empty? (html-files ctx)) (str "HTML is left: " (pr-str (map str (html-files ctx)))))))

(defstep #"the configured site builder is not installed"
  (fn [ctx]
    (needs-site!)
    (let [dir (s/copy-of-room!)
          cfg (fs/file dir "dhf/harness/config.edn")
          tool "rdm-spec-absent-site-builder"]
      (s/check! (nil? (fs/which tool)) (str tool " is installed"))
      (spit (str cfg) (pr-str (assoc-in (s/room-config) [:site :builder] [tool "build" "--strict"])))
      (let [ctx (assoc ctx :dir dir :builder tool)]
        (old-html! ctx)
        ctx))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (let [r (the-run ctx)
          line (some #(when (str/starts-with? % "cannot-run:") %) (s/err-lines r))]
      (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
      (s/check! (and line (str/includes? line (:builder ctx))) (str "no line names " (:builder ctx) "\n" (s/show r)))
      (s/check! (re-find #"(?i)no HTML" line) (str "it does not say no HTML was written\n" (s/show r)))
      (s/check! (empty? (html-files ctx)) (str "HTML is left: " (pr-str (map str (html-files ctx))))))))
