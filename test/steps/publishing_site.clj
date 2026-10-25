(ns steps.publishing-site
  "Step definitions for dhf/spec/publishing/site.feature.

  Most scenarios publish a small project of their own: a copy of the room's
  raw sources and wiki, with a spec, a model and views written by the
  scenario. Its views record the model hash as the glossary defines it (the
  model read as EDN, printed back with map keys and set elements sorted by
  their printed form, SHA-256 in lowercase hex), computed here, independently
  of the implementation.

  Shared wording: \"a wiki page whose footnote cites ...\" means what
  dhf/spec/knowledge/wiki.feature means by it (test/steps/knowledge_wiki.clj
  reads the page from :page): a fresh copy of the room whose wiki holds one
  page, listed in the index, with one footnote citing it. Here its views are
  also given the current model hash, so a publish of the copy is not refused
  for stale views."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def site-ns ['rdm-spec.publishing.site])

(defn- needs-site! [] (apply s/needs-built! site-ns))

(def site-cfg (:site (s/room-config)))
(def docs-rel (str (:source site-cfg) "/docs"))

;; ---------------------------------------------------------------------------
;; The model hash, as the glossary defines it

(defn- printed [x]
  (cond (map? x) (str "{" (str/join ", " (map (fn [[k v]] (str (printed k) " " (printed v)))
                                              (sort-by (comp printed key) x))) "}")
        (set? x) (str "#{" (str/join " " (sort (map printed x))) "}")
        (vector? x) (str "[" (str/join " " (map printed x)) "]")
        (seq? x) (str "(" (str/join " " (map printed x)) ")")
        :else (pr-str x)))

(defn- sha256 [^String t]
  (apply str (map #(format "%02x" (bit-and % 0xff))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes t "UTF-8")))))

(defn model-hash [dir]
  (sha256 (printed (edn/read-string (slurp (str (fs/file dir "dhf/spec/model.edn")))))))

(defn- refresh-views!
  "Rewrites the first line of every view in `dir` to record the current
  model hash, as a freshly drawn view would."
  [dir]
  (let [h (model-hash dir)]
    (doseq [f (fs/glob (fs/file dir "dhf/views") "*.{mmd,svg}")
            :let [text (slurp (str f))]]
      (spit (str f) (str/replace-first text #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " h))))
    dir))

;; ---------------------------------------------------------------------------
;; A project of the scenario's own

(defn- scenario-text [{:keys [id title level wip? steps]}]
  (str "    @" id " @" (name (or level :e2e)) (when wip? " @wip") "\n"
       "    Scenario: " title "\n"
       (str/join (for [st (or steps ["Given a thing" "When \"rdm-spec check\" is run" "Then it passes"])]
                   (str "      " st "\n")))))

(defn- feature-text [name scenarios]
  (str "@UN-001\nFeature: " name "\n  What " name " does.\n\n  Rule: It works\n\n"
       (str/join "\n" (map scenario-text scenarios))))

(defn- slug [s] (str/lower-case (str/replace s #"[^A-Za-z0-9]+" "-")))

(defn- svg [h k]
  (str "<!-- model-sha256: " h "; view \"" k "\" -->\n"
       "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><title>" k "</title></svg>\n"))

(defn project!
  "A project: a copy of the room whose spec is replaced. `contexts` is
  [[context-name [{:name :scenarios [..] :prose text}]]]; `kernel` the same
  list of components, or nil for a model with no kernel. Each context gets a
  component view; the model a context and a container view. Returns the
  directory."
  [contexts kernel]
  (needs-site!)
  (let [dir (s/copy-of-room!)
        n (atom 0)
        next-id #(format "spec-%04d" (+ 9000 (swap! n inc)))
        comp-of (fn [cx {:keys [name]}]
                  {:id (keyword cx (slug name)) :name name :spec (str cx "/" (slug name))
                   :description (str name " does one thing")})
        model {:system {:id :sys :name "Demo" :description "A demo system"}
               :people [{:id :engineer :name "Engineer" :actor #{:human :agent} :description "Writes the spec"}]
               :external []
               :containers [{:id :app :name "App" :kind :runs :technology "A program" :description "The program"
                             :contexts (vec (for [[cx comps] contexts]
                                              {:id (keyword cx) :name cx :description (str "The " cx " context")
                                               :components (mapv #(comp-of cx %) comps)}))}]
               :kernel (when kernel (mapv #(comp-of "kernel" %) kernel))
               :relationships [{:from :engineer :to :app :description "Uses"}]
               :views (vec (concat [{:key "context" :level :context :description "The system"}
                                    {:key "containers" :level :container :description "The containers"}]
                                   (for [[cx _] contexts]
                                     {:key (str cx "-components") :level :component :context (keyword cx)
                                      :description (str "The " cx " components")})))}
        model (if kernel model (dissoc model :kernel))]
    (fs/delete-tree (fs/file dir "dhf/spec"))
    (fs/delete-tree (fs/file dir "dhf/views"))
    (doseq [f ["needs.edn" "events.edn" "ids.edn"]]
      (fs/create-dirs (fs/file dir "dhf/spec"))
      (fs/copy (fs/file s/room-root "dhf/spec" f) (fs/file dir "dhf/spec" f)))
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (doseq [[cx comps] (concat contexts (when kernel [["kernel" kernel]]))
            c comps
            :let [spec (:spec (comp-of cx c))
                  scs (or (:scenarios c) [{:title (str (:name c) " works")}])]]
      (s/write! dir (str "dhf/spec/" spec ".feature")
                (feature-text (:name c) (map #(update % :id (fn [id] (or id (next-id)))) scs)))
      (when (:prose c) (s/write! dir (str "dhf/spec/" spec ".md") (:prose c))))
    (let [h (model-hash dir)]
      (doseq [v (:views model)]
        (s/write! dir (str "dhf/views/" (:key v) ".mmd") (str "%% model-sha256: " h "\nC4Component\n"))
        (s/write! dir (str "dhf/views/" (:key v) ".svg") (svg h (:key v)))))
    dir))

(defn- results! [statuses]
  (let [d (s/tmp-dir)]
    (doseq [[id st] statuses]
      (s/write! d (str (java.util.UUID/randomUUID) "-result.json")
                (str "{\"uuid\":\"" (java.util.UUID/randomUUID) "\",\"name\":\"a test of " id "\",\"status\":\"" st "\","
                     "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}],\"steps\":[]}")))
    d))

;; ---------------------------------------------------------------------------
;; Reading the site

(defn- page [ctx rel]
  (let [f (fs/file (:dir ctx) docs-rel rel)]
    (s/check! (fs/exists? f) (str "the site holds no page " rel "\n" (some-> (:run ctx) s/show)))
    (slurp (str f))))

(defn- pages
  "Every Markdown page of the site, relative to its docs directory."
  [ctx]
  (let [d (fs/file (:dir ctx) docs-rel)]
    (if (fs/directory? d)
      (sort (map #(str (fs/relativize d %)) (fs/glob d "**.md")))
      [])))

(defn- plain [s] (str/replace s #"[*_`]" ""))

(defn- scenario-block
  "The part of `text` about scenario `id`: from its heading to the next."
  [text id]
  (let [lines (str/split-lines text)
        start (first (keep-indexed #(when (and (str/starts-with? %2 "#") (str/includes? %2 id)) %1) lines))]
    (s/check! start (str "no heading names " id))
    (str/join "\n" (cons (nth lines start)
                         (take-while #(not (str/starts-with? % "#")) (drop (inc start) lines))))))

(defn- shows-status! [ctx id status]
  (let [heading? (fn [t] (some #(and (str/starts-with? % "#") (str/includes? % id)) (str/split-lines t)))
        hits (keep #(let [t (page ctx %)] (when (heading? t) (scenario-block t id))) (pages ctx))]
    (s/check! (seq hits) (str "no page shows " id "\n" (some-> (:run ctx) s/show)))
    (doseq [b hits]
      (s/check! (re-find (re-pattern (str "(?i)status: " status)) (plain b))
                (str id " does not show " status ":\n" b)))))

(defn- image-refs
  "The images a page shows, resolved to paths relative to the docs directory."
  [rel text]
  (let [base (fs/parent (fs/path rel))]
    (set (for [[_ target] (re-seq #"!\[[^\]]*\]\(([^)\s]+)" text)]
           (str (fs/normalize (if base (fs/path base target) (fs/path target))))))))

(defn- links [rel text]
  (let [base (fs/parent (fs/path rel))]
    (set (for [[_ target] (re-seq #"(?<!!)\[[^\]]*\]\(([^)\s#]+)" text)
               :when (not (re-find #"^[a-z]+:" target))]
           (str (fs/normalize (if base (fs/path base target) (fs/path target))))))))

(defn- shows-view! [ctx rel k]
  (let [imgs (image-refs rel (page ctx rel))
        want (some #(when (str/ends-with? % (str k ".svg")) %) imgs)]
    (s/check! want (str rel " shows no view " k "; it shows " (pr-str imgs)))
    (s/check! (fs/exists? (fs/file (:dir ctx) docs-rel want)) (str "the view " want " is not in the site"))))

(defn- run! [ctx] (or (:run ctx) (s/fail! "no command was run")))

;; ---------------------------------------------------------------------------
;; The site follows the model

(defstep #"a model with two bounded contexts holding five components, and no kernel"
  (fn [ctx]
    (assoc ctx :dir (project! [["alpha" [{:name "One"} {:name "Two"} {:name "Three"}]]
                               ["beta" [{:name "Four"} {:name "Five"}]]]
                              nil)
           :components [["alpha" "one" "One"] ["alpha" "two" "Two"] ["alpha" "three" "Three"]
                        ["beta" "four" "Four"] ["beta" "five" "Five"]])))

(defstep #"it holds five component pages, each named for its bounded context and component"
  (fn [ctx]
    (let [r (run! ctx)
          comp-pages (remove #(or (= "index.md" (fs/file-name %)) (str/starts-with? % "wiki/")) (pages ctx))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (= (sort (map (fn [[cx c]] (str cx "/" c ".md")) (:components ctx))) (sort comp-pages))
                (str "the component pages are " (pr-str comp-pages)))
      (doseq [[cx c nm] (:components ctx)
              :let [title (first (filter #(str/starts-with? % "# ") (str/split-lines (page ctx (str cx "/" c ".md")))))]]
        (s/check! (and title (str/includes? title nm) (str/includes? title cx))
                  (str cx "/" c ".md's title names not both " nm " and " cx ": " title))))))

(def lint-prose
  "# Lint\n\nThe lint holds the model to the quality checklist, one rule at a time.\n")

(defstep #"the component Lint with design prose beside its feature file and its scenarios"
  (fn [ctx]
    (let [scs [{:id "spec-9101" :title "A view over the limit fails" :level :e2e
                :steps ["Given a view with too many elements" "When \"rdm-spec check\" is run"
                        "Then it fails rule c4-view-size"]}
               {:id "spec-9102" :title "The rule names are stable" :level :contract
                :steps ["Given the lint's rules" "Then each has a stable name"]}]]
      (assoc ctx :dir (project! [["architecture" [{:name "Lint" :prose lint-prose :scenarios scs}]]] nil)
             :scenarios scs))))

(defstep #"Lint's page holds its description, its prose and every scenario with its id, level and steps"
  (fn [ctx]
    (let [r (run! ctx)
          _ (s/check! (= 0 (:exit r)) (s/show r))
          text (page ctx "architecture/lint.md")]
      (s/check! (str/includes? text "Lint does one thing") (str "no description:\n" text))
      (s/check! (str/includes? text "one rule at a time") (str "no prose:\n" text))
      (doseq [{:keys [id title level steps]} (:scenarios ctx)
              :let [b (plain (scenario-block text id))]]
        (s/check! (str/includes? b title) (str id " has no title:\n" b))
        (s/check! (re-find (re-pattern (str "(?i)level: " (name level))) b) (str id " has no level:\n" b))
        (doseq [st steps
                :let [[kw & more] (str/split st #" ")]]
          (s/check! (str/includes? b (str kw " " (str/join " " more))) (str id " lacks the step " st ":\n" b)))))))

(defstep #"the bounded context architecture and its rendered component view"
  (fn [ctx]
    (assoc ctx :dir (project! [["architecture" [{:name "Model"} {:name "Lint"} {:name "Views"}]]] nil))))

(defstep #"the architecture page shows the view and links to each of its components' pages"
  (fn [ctx]
    (let [r (run! ctx)
          _ (s/check! (= 0 (:exit r)) (s/show r))
          rel "architecture/index.md"
          ls (links rel (page ctx rel))]
      (shows-view! ctx rel "architecture-components")
      (doseq [c ["model" "lint" "views"]]
        (s/check! (contains? ls (str "architecture/" c ".md")) (str rel " does not link " c ": " (pr-str ls)))))))

(defstep #"the home page shows the context view, the container view and every user need with the scenarios tracing to it"
  (fn [ctx]
    (let [r (run! ctx)
          _ (s/check! (= 0 (:exit r)) (s/show r))
          text (page ctx "index.md")
          needs (edn/read-string (slurp (str (fs/file (:dir ctx) "dhf/spec/needs.edn"))))
          tracing (fn [need]
                    ;; every stamped scenario under a feature, rule or scenario tagged with the need
                    (let [out (:out (s/rdm-spec! (:dir ctx) "trace" need))
                          v (try (edn/read-string out) (catch Exception _ nil))]
                      (set (keep :id (:scenarios v)))))]
      (shows-view! ctx "index.md" "context")
      (shows-view! ctx "index.md" "containers")
      (doseq [[need _] needs
              :let [i (str/index-of text need)]]
        (s/check! i (str "the home page does not show " need))
        (doseq [id (tracing need)]
          (s/check! (str/includes? text id) (str "the home page does not trace " id " to " need)))))))

;; ---------------------------------------------------------------------------
;; The site shows status, and never decides it

(defstep #"Allure results in which spec-0012 passed and spec-0013 failed"
  (fn [ctx]
    (assoc ctx
           :dir (project! [["architecture" [{:name "Lint" :scenarios [{:id "spec-0012" :title "One"}
                                                                      {:id "spec-0013" :title "Two"}]}]]]
                          nil)
           :results-dir (results! [["spec-0012" "passed"] ["spec-0013" "failed"]]))))

(defstep #"\"rdm-spec (publish[^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:results-dir ctx) "no results directory was given to stand for DIR")
    (let [args (mapv #(if (= "DIR" %) (:results-dir ctx) %) (s/split-args line))]
      (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args)))))

(defstep #"(spec-\d+) shows (verified|failed|planned|unverified) and (spec-\d+) shows (verified|failed|planned|unverified)"
  (fn [ctx a sa b sb]
    (shows-status! ctx a sa)
    (shows-status! ctx b sb)))

(defstep #"the scenario @spec-0014 tagged @wip"
  (fn [ctx]
    (assoc ctx :dir (project! [["architecture" [{:name "Lint" :scenarios [{:id "spec-0014" :title "Later" :wip? true}
                                                                          {:id "spec-0015" :title "Now"}]}]]]
                              nil))))

(defstep #"(spec-\d+) shows (verified|failed|planned|unverified)"
  (fn [ctx id st] (shows-status! ctx id st)))

(defstep #"every page says no status was derived because no results were given"
  (fn [ctx]
    (let [ps (remove #(str/starts-with? % "wiki/") (pages ctx))]
      (s/check! (seq ps) (str "no page was published\n" (some-> (:run ctx) s/show)))
      (doseq [p ps]
        (s/check! (re-find #"(?i)no status was derived.*no results were given" (plain (page ctx p)))
                  (str p " does not say no status was derived because no results were given"))))))

;; ---------------------------------------------------------------------------
;; The site is a reading of the spec

(defstep #"it exits 1 and names the stale view"
  (fn [ctx]
    (let [r (run! ctx)
          k (or (:stale-view ctx) (s/fail! "no view was made stale"))]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (some #(str/includes? % (str k ".svg")) (s/err-lines r))
                (str "no finding names the stale view " k "\n" (s/show r))))))

(defn- tree [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (vec (fs/read-all-bytes f))])))

(defstep #"no file under dhf/spec changes"
  (fn [ctx]
    (let [r (run! ctx)
          got (tree (fs/file (:dir ctx) "dhf/spec"))
          want (tree (fs/file s/room-root "dhf/spec"))]
      (s/check! (#{0 1} (:exit r)) (s/show r))
      (s/check! (= (keys want) (keys got)) "files were added to or removed from dhf/spec")
      (doseq [[k v] want]
        (s/check! (= v (get got k)) (str "dhf/spec/" k " changed"))))))

(defstep #"a spec, its views and a results directory"
  (fn [ctx]
    (-> ctx
        (assoc
           :dir (project! [["alpha" [{:name "One" :scenarios [{:id "spec-0012" :title "One"}
                                                              {:id "spec-0013" :title "Two"}]}]]
                           ["beta" [{:name "Two"}]]]
                          [{:name "Command"}])
           :env-results-dir (results! [["spec-0012" "passed"] ["spec-0013" "failed"]]))
        ;; the kernel's shared When passes a scenario's environment from :env
        (as-> c (assoc c :env (assoc (:env c) "RESULTS_DIR" (:env-results-dir c)))))))

(defn- run-cli
  "Runs `rdm-spec args...` in `dir`, with RESULTS_DIR set to `env-dir` when
  given. Local to this file: another scope's step helpers are not a contract."
  [dir args env-dir]
  (if env-dir
    (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true
                            :extra-env {"RESULTS_DIR" env-dir}}
                   s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" args)]
      {:exit (:exit r) :out (:out r) :err (:err r) :args (vec args)})
    (apply s/rdm-spec! dir args)))

(defn- site-source [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir (:source site-cfg)) "**")
              :when (and (fs/regular-file? f) (re-find #"\.(md|yml)$" (str f)))]
          [(str (fs/relativize (fs/file dir (:source site-cfg)) f)) (slurp (str f))])))

(defstep #"both sites' Markdown and site builder configuration are byte for byte the same"
  (fn [ctx]
    (let [[a b] (or (:runs ctx) (s/fail! "the command was not run twice"))
          _ (s/check! (= 0 (:exit a) (:exit b)) (str (s/show a) "\n" (s/show b)))
          second-site (site-source (:dir ctx))
          ;; The first run's site was overwritten by the second, so the same
          ;; inputs are published once more in a copy elsewhere and compared.
          other (s/tmp-dir)
          _ (fs/copy-tree (fs/file (:dir ctx) "dhf") (fs/file other "dhf"))
          third (run-cli other ["publish"] (:env-results-dir ctx))
          third-site (site-source other)]
      (s/check! (= 0 (:exit third)) (s/show third))
      (s/check! (seq second-site) "no site was written")
      (s/check! (some #(str/ends-with? % ".yml") (keys second-site)) "no site builder configuration was written")
      (s/check! (= (keys second-site) (keys third-site)) "the two sites hold different files")
      (doseq [[k v] second-site]
        (s/check! (= v (get third-site k)) (str k " differs between the two sites"))))))

;; ---------------------------------------------------------------------------
;; The domain wiki

(def ^:private footnoted-paragraph
  "The code is built from the spec, and can be deleted and built again.[^1]")

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx citation]
    (s/needs-built! 'rdm-spec.knowledge.sources 'rdm-spec.knowledge.wiki)
    (let [dir (refresh-views! (s/copy-of-room!))
          rel "regeneration.md"]
      (s/write! dir (str "dhf/wiki/" rel)
                (str "# Regeneration\n\n" footnoted-paragraph "\n\n[^1]: `" citation "`, \"What to steal\" 1.\n"))
      (spit (str (fs/file dir "dhf/wiki/index.md")) (str "\n- [A page](" rel ")\n") :append true)
      (assoc ctx :dir dir :page (str "dhf/wiki/" rel) :page-text nil :citation citation :footnote "[^1]"))))

(defstep #"the page is published and the footnote links to (\S+) at (\S+) in (\S+)"
  (fn [ctx path commit repo]
    (let [r (run! ctx)
          _ (s/check! (= 0 (:exit r)) (s/show r))
          rel (str "wiki/" (subs (:page ctx) (count "dhf/wiki/")))
          text (page ctx rel)
          url (str "https://github.com/" repo "/blob/" commit "/" path)
          fn-line (first (filter #(str/starts-with? % "[^1]:") (str/split-lines text)))]
      (s/check! (str/includes? text footnoted-paragraph) (str "the page's text is not published:\n" text))
      (s/check! (and fn-line (str/includes? fn-line (str "](" url ")")))
                (str "the footnote does not link " url ": " fn-line)))))

;; ---------------------------------------------------------------------------
;; The site builder

(defstep #"a model with the bounded contexts spec and architecture, in that order, each with its components"
  (fn [ctx]
    (assoc ctx :dir (project! [["spec" [{:name "Parser"} {:name "Stamper"}]]
                               ["architecture" [{:name "Model"} {:name "Lint"} {:name "Views"}]]]
                              [{:name "Command"} {:name "Bus"}])
           :sections [["spec" ["spec/index.md" "spec/parser.md" "spec/stamper.md"]]
                      ["architecture" ["architecture/index.md" "architecture/model.md" "architecture/lint.md"
                                       "architecture/views.md"]]
                      ["kernel" ["kernel/index.md" "kernel/command.md" "kernel/bus.md"]]])))

(defn- builder-config [ctx]
  (let [f (fs/file (:dir ctx) (:source site-cfg) "mkdocs.yml")]
    (s/check! (fs/exists? f) (str "no site builder configuration was written\n" (some-> (:run ctx) s/show)))
    (yaml/parse-string (slurp (str f)) :keywords false)))

(defn- entry [e] (first (seq e)))

(defstep #"the site builder's configuration is written with the home page first, then a section for spec and one for architecture, then one for the kernel"
  (fn [ctx]
    (let [nav (get (builder-config ctx) "nav")
          [home & sections] nav]
      (s/check! (= "index.md" (val (entry home))) (str "the first entry is not the home page: " (pr-str home)))
      (s/check! (= ["spec" "architecture" "kernel"] (take 3 (map (comp key entry) sections)))
                (str "the sections are " (pr-str (map (comp key entry) sections))))
      ctx)))

(defstep #"each section lists its context's page and then its components' pages, in the model's order"
  (fn [ctx]
    (let [nav (get (builder-config ctx) "nav")
          by-title (into {} (map (comp (juxt key val) entry) (rest nav)))]
      (doseq [[title want] (:sections ctx)
              :let [got (map (comp val entry) (get by-title title))]]
        (s/check! (= want got) (str "the section " title " lists " (pr-str got) ", not " (pr-str want)))))))

(defstep #"a published site whose pages all link to pages that exist"
  (fn [ctx]
    (assoc ctx :dir (project! [["alpha" [{:name "One" :prose "# One\n\nSee [Two](two.md).\n"} {:name "Two"}]]] nil))))

(defstep #"the configured site builder builds it, failing on any broken link, and the HTML is written"
  (fn [ctx]
    (let [r (run! ctx)
          site (fs/file (:dir ctx) (:dir site-cfg))]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (some #{"--strict"} (:builder site-cfg)) "the configured builder does not fail on broken links")
      (doseq [p ["index.html" "alpha/index.html" "alpha/one/index.html" "alpha/two/index.html"]]
        (s/check! (fs/exists? (fs/file site p)) (str "no HTML " p " was written"))))))

(defstep #"a component page that links to a page the site does not hold"
  (fn [ctx]
    (assoc ctx :dir (project! [["alpha" [{:name "One" :prose "# One\n\nSee [the plan](nowhere-plan.md).\n"}]]] nil)
           :bad-page "alpha/one.md" :bad-link "nowhere-plan.md")))

(defstep #"it exits 1 and names the page and the link"
  (fn [ctx]
    (let [r (run! ctx)]
      (s/check! (= 1 (:exit r)) (s/show r))
      (s/check! (some #(and (str/includes? % (:bad-page ctx)) (str/includes? % (:bad-link ctx))) (s/err-lines r))
                (str "no finding names " (:bad-page ctx) " and " (:bad-link ctx) "\n" (s/show r))))))

(def missing-builder "rdm-spec-test-no-such-site-builder")

(defstep #"the configured site builder is not installed"
  (fn [ctx]
    (let [dir (project! [["alpha" [{:name "One"}]]] nil)
          f (fs/file dir "dhf/harness/config.edn")
          c (edn/read-string (slurp (str f)))]
      (s/check! (nil? (fs/which missing-builder)) (str missing-builder " is installed"))
      (spit (str f) (pr-str (assoc-in c [:site :builder] [missing-builder "build"])))
      (assoc ctx :dir dir))))

(defstep #"it exits 2, names the missing site builder, and says no HTML was written"
  (fn [ctx]
    (let [r (run! ctx)
          err (str/join "\n" (s/err-lines r))]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? err missing-builder) (str "the missing builder is not named\n" (s/show r)))
      (s/check! (re-find #"(?i)no HTML was written" err) (str "it does not say no HTML was written\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file (:dir ctx) (:dir site-cfg) "index.html"))) "HTML was written"))))
