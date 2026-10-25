(ns rdm-spec.spec.parser
  "The Parser component of the spec context (dhf/spec/spec/parser.feature):
  reads the spec, at a revision or in the working tree, into data. Every
  feature file under the configured :spec-dir is read with the kernel's reader
  of the declared Gherkin subset (rdm-spec.kernel.gherkin), which refuses,
  never skips, a construct outside it; the declared needs are read from the
  configured :needs file.

  The spec is read either as it is in the working tree, or as it was at a
  revision (a cut, a tag or a commit): then the configuration, the feature
  files, the needs and the model are all read from git at that revision,
  never from the working tree. A task that names a cut (`rdm-spec checklist
  CYCLE`, through the cycle's cycle.edn) reads the spec at that cut.

  `read-spec` returns
    {:revision  \"<commit>\" or :working-tree
     :spec-dir  \"dhf/spec\"
     :files     [\"dhf/spec/a/b.feature\" ...]        ; sorted
     :features  [{:file :line :name :tags [\"@UN-001\"] :description
                  :rules [{:name :line :tags}]} ...]
     :scenarios [scenario ...]                        ; path order, then file order
     :needs     [{:id \"UN-001\" :text :sources [citation] :line n} ...] ; by id
     :needs-file \"dhf/spec/needs.edn\" :needs-error \"why\" or nil
     :refusals  [{:file :line :rule :message} ...]}
  where a scenario is the SpecParsed shape (dhf/spec/events.edn) with more:
    {:id \"spec-0001\" (its one id; nil when it has none or several)
     :ids [\"spec-0001\"]  :title :name  :file :line :keyword
     :level :e2e | :contract | nil   :levels [\"@e2e\" ...]
     :tags [own tags] :feature-tags [..] :rule-tags [..] :all-tags [..]
     :needs [\"UN-001\" ...]  (own, rule and feature need tags)
     :wip? bool  :outline? bool  :rows [{:line :values {header value}}]
     :examples [the reader's Examples blocks]  :rule \"rule name\" or nil
     :background [step]  :steps [{:keyword :text :line ...}]
     :spec-path \"spec/parser\"  :context :spec  :component :spec/parser}
  :context and :component name the model's owner of the file (:kernel and the
  kernel component for kernel features); nil when the model names none.

  `parse` is the stage the status, trace, release and checklist tasks run
  first: it reads the spec and publishes SpecParsed."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; Where the spec is read from: the working tree, or git at a revision

(defn- git
  "Runs git in `root`: {:exit :out :err}."
  [root & args]
  (let [r (apply p/shell {:dir (str root) :out :string :err :string :continue true} "git" args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn commit-of
  "The commit `revision` names in the repository at `root`, or nil."
  [root revision]
  (let [r (git root "rev-parse" "--verify" "--quiet" (str revision "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn- source
  "How files are read: from the working tree under `root`, or from git at
  `revision`. {:exists? (fn [rel]) :text (fn [rel]) :features (fn [dir])}."
  [root revision]
  (if (nil? revision)
    {:text (fn [rel] (let [f (command/path root rel)] (when (fs/regular-file? f) (slurp (str f)))))
     :features (fn [dir]
                 (let [d (command/path root dir)]
                   (if (fs/directory? d)
                     (vec (sort (map #(command/rel root %) (fs/glob d "**.feature"))))
                     [])))
     :files (fn [dir]
              (let [d (command/path root dir)]
                (if (fs/directory? d)
                  (vec (sort (for [f (fs/glob d "**") :when (fs/regular-file? f)] (command/rel root f))))
                  [])))}
    (let [show (fn [rel] (let [r (git root "show" (str revision ":./" rel))]
                           (when (zero? (:exit r)) (:out r))))
          ls (fn [dir] (let [r (git root "ls-tree" "-r" "--name-only" revision "--" (str dir))]
                         (if (zero? (:exit r))
                           (vec (sort (remove str/blank? (str/split-lines (:out r)))))
                           (command/cannot-run! (str "cannot list " dir " at " revision ": " (str/trim (:err r)))))))]
      {:text show
       :features (fn [dir] (filterv #(str/ends-with? % ".feature") (ls dir)))
       :files ls})))

(defn- read-edn-text [text]
  (try (edn/read-string text) (catch Exception _ ::unreadable)))

(defn config-at
  "The harness configuration at `revision` (nil: the working tree's), or
  `fallback` when the revision holds none."
  [root revision fallback]
  (let [t ((:text (source root revision)) command/config-file)
        c (when t (read-edn-text t))]
    (if (map? c) c fallback)))

;; ---------------------------------------------------------------------------
;; The model: who owns a feature file

(defn- spec-path
  "\"spec/parser\" for dhf/spec/spec/parser.feature under the spec dir `dir`."
  [dir file]
  (let [d (str (str/replace (str dir) #"/+$" "") "/")]
    (-> (if (str/starts-with? file d) (subs file (count d)) file)
        (str/replace #"\.feature$" ""))))

(defn owners
  "{spec-path {:context kw :component kw :context-name :component-name}} from a
  C4 model (nil: {}); the names are the model's :name, for display."
  [model]
  (if-not (map? model)
    {}
    (into {}
          (concat
           (for [c (:containers model) ctx (:contexts c) comp (:components ctx)
                 :when (string? (:spec comp))]
             [(:spec comp) {:context (:id ctx) :component (:id comp)
                            :context-name (:name ctx) :component-name (:name comp)}])
           (for [comp (:kernel model) :when (string? (:spec comp))]
             [(:spec comp) {:context :kernel :component (:id comp)
                            :context-name "kernel" :component-name (:name comp)}])))))

;; ---------------------------------------------------------------------------
;; Tags

(defn id-pattern
  "An id tag, @<prefix>-NNNN (four or more digits)."
  [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}")))

(def level-tags #{"@e2e" "@contract"})
(def need-pattern #"@UN-\d+")
(def wip-tag "@wip")

(defn- needs-in [tags] (vec (keep #(when (re-matches need-pattern %) (subs % 1)) tags)))

;; ---------------------------------------------------------------------------
;; One scenario

(defn- scenario
  [{:keys [prefix dir owners]} file feature sc]
  (let [id-re (id-pattern prefix)
        own (vec (:tags sc))
        ftags (vec (:tags feature))
        rtags (vec (get-in sc [:rule :tags]))
        ids (vec (distinct (map #(subs % 1) (filter #(re-matches id-re %) own))))
        levels (vec (distinct (filter level-tags own)))
        sp (spec-path dir file)
        owner (get owners sp)
        outline? (= "Scenario Outline" (:keyword sc))]
    {:id (when (= 1 (count ids)) (first ids))
     :ids ids
     :title (:name sc)
     :name (:name sc)
     :file file
     :line (:line sc)
     :keyword (:keyword sc)
     :level (when (= 1 (count levels)) (keyword (subs (first levels) 1)))
     :levels levels
     :tags own
     :feature-tags ftags
     :rule-tags rtags
     :all-tags (vec (:all-tags sc))
     :needs (vec (distinct (concat (needs-in own) (needs-in rtags) (needs-in ftags))))
     :wip? (boolean (some #{wip-tag} own))
     :outline? outline?
     :rows (when outline?
             (vec (for [ex (:examples sc) row (:rows ex)]
                    {:line (:line row) :values (zipmap (:header ex) (:cells row))})))
     :examples (:examples sc)
     :rule (get-in sc [:rule :name])
     :rule-line (get-in sc [:rule :line])
     :background (vec (:background sc))
     :steps (vec (:steps sc))
     :spec-path sp
     :context (:context owner)
     :component (:component owner)
     :context-name (:context-name owner)
     :component-name (:component-name owner)}))

;; ---------------------------------------------------------------------------
;; The needs

(defn- line-of [text re]
  (some (fn [[i l]] (when (re-find re l) (inc i)))
        (map-indexed vector (str/split-lines (or text "")))))

(defn read-needs
  "The declared needs in `text` (the needs file `file`):
  {:needs [{:id :text :sources :line}] :error why-or-nil}."
  [file text]
  (if (nil? text)
    {:needs [] :error (str "no needs file " file)}
    (let [v (read-edn-text text)]
      (if-not (map? v)
        {:needs [] :error (str file " is not an EDN map of needs")}
        {:needs (vec (sort-by :id
                              (for [[k n] v
                                    :let [id (if (keyword? k) (name k) (str k))]]
                                {:id id
                                 :text (when (map? n) (:text n))
                                 :sources (if (and (map? n) (sequential? (:sources n))) (vec (:sources n)) [])
                                 :line (line-of text (re-pattern (str "\"" (java.util.regex.Pattern/quote id) "\"")))})))
         :error nil}))))

;; ---------------------------------------------------------------------------
;; The spec

(defn read-spec
  "Reads the spec of the project at `root`. `opts`:
    :spec-dir  the spec directory (default dhf/spec)
    :id-prefix the id prefix (default spec)
    :model     the C4 model file, to name each file's owner (optional)
    :needs     the needs file (default <spec-dir>/needs.edn)
    :revision  a revision to read at (a tag or commit); nil: the working tree
  See the namespace doc for what it returns."
  [root {:keys [spec-dir id-prefix model needs revision]}]
  (let [spec-dir (str/replace (or spec-dir "dhf/spec") #"/+$" "")
        src (source root revision)
        model-v (when model (some-> ((:text src) model) read-edn-text))
        files ((:features src) spec-dir)
        parsed (vec (for [f files] (gherkin/parse-feature f (or ((:text src) f) ""))))
        cx {:prefix id-prefix :dir spec-dir :owners (owners (when (map? model-v) model-v))}
        needs-file (or needs (str spec-dir "/needs.edn"))
        {needs-v :needs needs-error :error} (read-needs needs-file ((:text src) needs-file))]
    {:revision (if revision (or (commit-of root revision) (str revision)) :working-tree)
     :spec-dir spec-dir
     :files files
     :features (vec (for [{:keys [file feature scenarios]} parsed :when feature]
                      (assoc feature :file file
                             :rules (vec (distinct (keep :rule scenarios))))))
     :scenarios (vec (for [{:keys [file feature scenarios]} parsed
                           sc scenarios]
                       (scenario cx file feature sc)))
     :needs needs-v
     :needs-file needs-file
     :needs-error needs-error
     :refusals (vec (mapcat :refusals parsed))}))

(defn read-spec-at
  "The spec at `revision` (nil: the working tree), read with the configuration
  at that revision, falling back to `config`."
  [root revision config]
  (let [c (if revision (config-at root revision config) config)]
    (read-spec root {:spec-dir (:spec-dir c) :id-prefix (:id-prefix c) :model (:model c)
                     :needs (:needs c) :revision revision})))

(defn text-files
  "Every file under the spec directory at the spec's revision (the working
  tree's or a commit's): [{:file :text}]."
  [root spec]
  (let [rev (when (string? (:revision spec)) (:revision spec))
        src (source root rev)]
    (vec (for [f ((:files src) (:spec-dir spec))
               :let [t ((:text src) f)]
               :when t]
           {:file f :text t}))))

(defn refusal-findings
  "The parser's refusals as findings (exit 1)."
  [spec]
  (mapv (fn [{:keys [file line rule message]}] (command/finding file line rule message))
        (:refusals spec)))

(defn payload
  "The SpecParsed payload of `spec` (dhf/spec/events.edn)."
  [spec]
  {:revision (:revision spec)
   :scenarios (:scenarios spec)
   :needs (mapv #(dissoc % :line) (:needs spec))
   :refusals (:refusals spec)})

(defn publish!
  "Publishes SpecParsed for `spec` on the run's bus."
  [req spec]
  (command/publish! req :spec/parser :SpecParsed (payload spec)))

;; ---------------------------------------------------------------------------
;; The stage

(defn- named-cut
  "The cut a task names, or nil: `checklist CYCLE` names its cycle.edn's :cut."
  [req]
  (when (= "checklist" (:task req))
    (let [cycles (get-in req [:config :cycles-dir])
          cycle (first (:args req))]
      (when (and cycles (string? cycle) (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle))
        (let [f (command/path req (str cycles "/" cycle "/cycle.edn"))
              c (when (fs/exists? f) (read-edn-text (slurp (str f))))]
          (when (and (map? c) (string? (:cut c)) (not (str/blank? (:cut c))))
            (:cut c)))))))

(defn parse
  "The parser's stage: reads the spec (at the cut the task names, else in the
  working tree), keeps it in the run's state as ::spec and publishes
  SpecParsed. In the working tree its refusals are its findings; a spec read
  at a cut is the reading task's to judge (a cut that does not exist is left
  to the task, which cannot run without it)."
  [req]
  (let [cut (named-cut req)]
    (if (and cut (nil? (commit-of (:root req) cut)))
      {:result {} :findings []}
      (let [spec (read-spec-at (:root req) cut (:config req))]
        (when-let [st (:state req)] (swap! st assoc ::spec spec))
        (publish! req spec)
        {:result {}
         :findings (if cut [] (refusal-findings spec))}))))
