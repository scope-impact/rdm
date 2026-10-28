(ns steps.kernel-shared
  "The kernel's shared steps: the step texts the features of more than one
  context use, and the helpers every step file may call. Every scenario makes
  its own project, a fixture under the run's temporary directory, fenced from
  any enclosing repository by GIT_CEILING_DIRECTORIES, and removed after it.

  Context keys these steps set, and the only ones another step file reads:
    :base    the scenario's own directory (its fixtures' parent)
    :dir     the project's root, the directory rdm-spec runs in
    :env     extra environment for the scenario's commands
    :ph      placeholders of a command line (\"ROOM\", \"DIR\", \"OUT\", ...) to paths
    :exit :out :err :result   the last command's exit, output, error stream, and output read as EDN
    :runs    every command run so far, oldest first
    :spec-before  the spec's files before the last command ran
    :bindings     what a docstring's upper-case symbols matched"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail! doc-string data-table attach!]]))

;; ------------------------------------------------------------------ the run's fixtures

(def room (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*))))))
(def src (str room "/src"))

(defn tmp-base []
  (let [t (System/getenv "TMPDIR")] (if (and t (not (str/blank? t))) t (System/getProperty "java.io.tmpdir"))))

(defonce run-base (atom nil))
(defonce made (atom []))
(defonce procs (atom []))

(defn base-dir []
  (or @run-base
      (reset! run-base (str (fs/create-temp-dir {:dir (tmp-base) :prefix "rdm-fixtures-"})))))

(defn scratch!
  "A new directory for the running scenario, removed after it."
  [prefix]
  (let [d (str (fs/create-temp-dir {:dir (base-dir) :prefix prefix}))]
    (swap! made conj d)
    d))

(defn track-process! [proc] (swap! procs conj proc) proc)

(defn cleanup!
  "Stops every process the scenario started and removes every fixture it made."
  []
  (doseq [pr @procs] (try (p/destroy-tree pr) (catch Throwable _)))
  (reset! procs [])
  (doseq [d @made] (try (fs/delete-tree d) (catch Throwable _)))
  (reset! made []))

(defn cleanup-all! []
  (cleanup!)
  (when @run-base (fs/delete-tree @run-base) (reset! run-base nil)))

;; ------------------------------------------------------------------ files and git

(defn path
  "REL inside the scenario's project; refuses a context with no project, so
  a step can never write outside its fixture."
  [ctx rel]
  (let [d (:dir ctx)]
    (when-not (and d (fs/absolute? d) (str/starts-with? (str d) (str (base-dir))))
      (throw (ex-info (str "no fixture project in this context to hold " rel) {})))
    (str (fs/path d rel))))

(defn write! [ctx rel text]
  (let [f (path ctx rel)] (fs/create-dirs (fs/parent f)) (spit f (str text)) ctx))

(defn slurp-rel [ctx rel] (let [f (path ctx rel)] (when (fs/exists? f) (slurp f))))

(defn delete! [ctx rel] (fs/delete-tree (path ctx rel)) ctx)

(def git-identity {"GIT_AUTHOR_NAME" "rdm-spec tests" "GIT_AUTHOR_EMAIL" "tests@rdm-spec.invalid"
                   "GIT_COMMITTER_NAME" "rdm-spec tests" "GIT_COMMITTER_EMAIL" "tests@rdm-spec.invalid"})

(defn base-env
  "The environment of a scenario's commands: the tests' own, except RESULTS_DIR."
  [ctx]
  (merge (dissoc (into {} (System/getenv)) "RESULTS_DIR")
         git-identity
         {"PYTHONDONTWRITEBYTECODE" "1"
          "GIT_CEILING_DIRECTORIES" (str (or (:base ctx) (fs/parent (:dir ctx))))}
         (:env ctx)))

(defn sh [ctx dir & args]
  (let [r @(p/process (vec (map str args)) {:dir (str dir) :env (base-env ctx) :out :string :err :string :in ""})]
    r))

(defn git! [ctx & args]
  (let [dir (if (map? (first args)) (:in (first args)) (:dir ctx))
        args (if (map? (first args)) (rest args) args)
        r (apply sh ctx dir "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "git " (str/join " " args) ": " (:err r)) {})))
    (str/trim (:out r))))

(defn commit! [ctx msg]
  (git! ctx "add" "-A" "-f" "--" ".")
  (git! ctx "commit" "-q" "--allow-empty" "--no-verify" "-m" msg)
  ctx)

(defn tag! [ctx t] (git! ctx "tag" "-f" t) ctx)

(defn commit-tag! [ctx t] (-> ctx (commit! (str "cut " t)) (tag! t)))

;; ------------------------------------------------------------------ running rdm-spec

(def default-ph ["ROOM" "OUT2" "OUT" "DIR" "R1"])

(defn ph [ctx s]
  (reduce (fn [s [k v]] (str/replace s (re-pattern (str "\\b" k "\\b")) (str v))) s
          (sort-by (comp - count key)
                   (merge (into {} (for [k default-ph] [k (str (:base ctx) "/" (str/lower-case k))])) (:ph ctx)))))

(defn words
  "Splits a command line into words, keeping 'quoted words' whole."
  [s]
  (mapv (fn [[_ a b]] (or a b)) (re-seq #"'([^']*)'|(\S+)" s)))

(defn read-edn [s] (try (edn/read-string {:default (fn [_ v] v)} (str s)) (catch Exception _ ::unreadable)))

(defn spec-files [ctx]
  (let [d (path ctx "dhf/spec")]
    (when (fs/directory? d)
      (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)] [(str (fs/relativize d f)) (slurp (str f))])))))

(declare new-project-dir own-project-copy!)

(defn run-rdm
  "Runs rdm-spec with ARGS (a string or a vector) in the project, in process,
  as the command runs it; records :exit :out :err :result."
  [ctx args & [{:keys [dir env]}]]
  (let [ctx (if (:dir ctx) ctx (own-project-copy! ctx))
        argv (if (string? args) (words (ph ctx args)) args)
        run (requiring-resolve 'rdm-spec.kernel.command/run)
        before (spec-files ctx)
        r (run {:dir (str (or dir (:dir ctx))) :env (merge (base-env ctx) env) :args argv})
        res (read-edn (:out r))]
    (attach! (str "rdm-spec " (str/join " " argv)) (str "exit " (:exit r) "\n--- out\n" (:out r) "\n--- err\n" (:err r)))
    (-> ctx
        (assoc :exit (:exit r) :out (:out r) :err (:err r) :result res :spec-before before)
        (update :runs (fnil conj []) (assoc r :args argv)))))

(defn rdm-command-line
  "The words that start this build of rdm-spec as a process, from its sources."
  []
  ["bb" "--classpath" src "-m" "rdm-spec.kernel.command"])

(defn run-rdm-process
  "Runs rdm-spec as a process, from its sources."
  [ctx args & [{:keys [dir env]}]]
  (let [argv (if (string? args) (words (ph ctx args)) args)
        r @(p/process (into (rdm-command-line) argv) {:dir (str (or dir (:dir ctx))) :env (merge (base-env ctx) env)
                                                       :out :string :err :string :in ""})]
    (assoc ctx :exit (:exit r) :out (:out r) :err (:err r) :result (read-edn (:out r)))))

;; ------------------------------------------------------------------ assertions

(defn check! [ok? & msg] (when-not ok? (fail! (apply str msg))) true)

(defn err-lines [ctx] (vec (remove str/blank? (str/split-lines (str (:err ctx))))))

(defn shows [ctx] (str "exit " (:exit ctx) "; out: " (:out ctx) "; err: " (:err ctx)))

(defn exits! [ctx n] (check! (= n (:exit ctx)) "expected exit " n ", got " (shows ctx)) ctx)

(defn err-has! [ctx & parts]
  (doseq [p parts] (check! (str/includes? (str (:err ctx)) (str p)) "the error stream does not name " (pr-str p) ": " (shows ctx)))
  ctx)

(defn err-line-has!
  "Some one line of the error stream holds every part."
  [ctx & parts]
  (check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) (err-lines ctx))
          "no line of the error stream names all of " (pr-str parts) ": " (shows ctx))
  ctx)

(defn out-has! [ctx & parts]
  (doseq [p parts] (check! (str/includes? (str (:out ctx)) (str p)) "the output does not hold " (pr-str p) ": " (shows ctx)))
  ctx)

(defn matches-shape
  "Whether ACTUAL is EXPECTED, upper-case symbols in EXPECTED matching any
  value (the same value each time): returns the bindings, or nil."
  ([expected actual] (matches-shape expected actual {}))
  ([e a b]
   (cond
     (nil? b) nil
     (and (symbol? e) (re-matches #"[A-Z][A-Z0-9]*" (name e)))
     (if (contains? b e) (when (= (b e) a) b) (assoc b e a))
     (and (map? e) (map? a)) (when (= (set (keys e)) (set (keys a)))
                               (reduce (fn [b k] (matches-shape (get e k) (get a k) b)) b (keys e)))
     (and (sequential? e) (sequential? a)) (when (= (count e) (count a))
                                             (reduce (fn [b [x y]] (matches-shape x y b)) b (map vector e a)))
     :else (when (= e a) b))))

;; ------------------------------------------------------------------ projects

(defn new-project-dir
  "A new scenario directory, and the path of a project inside it (not yet made)."
  [ctx]
  (let [b (scratch! "scenario-")]
    (assoc ctx :base b :dir (str b "/project"))))

(defn project!
  "A project set up by rdm-spec: rdm-spec init, in process, into a new directory."
  [ctx & [{:keys [prefix]}]]
  (let [ctx (new-project-dir ctx)
        run (requiring-resolve 'rdm-spec.kernel.command/run)
        r (run {:dir (:base ctx) :env (base-env ctx)
                :args (cond-> ["init" "project"] prefix (into ["--id-prefix" prefix]))})]
    (when-not (zero? (:exit r)) (throw (ex-info (str "init failed: " (:err r)) {})))
    ctx))

(defn read-config [ctx] (edn/read-string (slurp-rel ctx "dhf/harness/config.edn")))

(defn write-edn! [ctx rel v] (write! ctx rel (with-out-str (clojure.pprint/pprint v))))

(defn config! [ctx f & args]
  (write-edn! ctx "dhf/harness/config.edn" (apply f (read-config ctx) args)))

(def demo-sources
  {:demo {:repository "https://example.com/demo" :record "demo.md" :pins ["abc1234"] :fenced []}})

(def demo-needs
  {"UN-001" {:text "A team keeps its requirements." :sources ["demo:README.md@abc1234"]}
   "UN-002" {:text "A team is stopped before a broken spec." :sources ["demo:README.md@abc1234"]}
   "UN-003" {:text "A team sees each scenario's status." :sources ["demo:README.md@abc1234"]}})

(def kernel-model
  "A model whose one component is the kernel's: no context, so no view to draw."
  {:system {:id :demo :name "demo" :description "A demo project"}
   :kernel [{:id :kernel/thing :name "Thing" :spec "demo/thing" :description "Does the thing"}]})

(defn context-model
  "A model of one container whose contexts are CTXS: {context-kw [[component-kw name] ...]}, in order."
  [ctxs]
  {:system {:id :demo-system :name "demo" :description "A demo project"}
   :people [{:id :user :name "User" :description "Uses it"}]
   :containers [{:id :app :name "app" :kind :runs :technology "a program" :description "The program"
                 :contexts (vec (for [[ctx comps] ctxs]
                                  {:id ctx :name (name ctx) :description (str "The " (name ctx) " context")
                                   :components (vec (for [[c n spec] comps]
                                                      {:id (keyword (name ctx) (name c)) :name n
                                                       :spec (or spec (str (name ctx) "/" (name c)))
                                                       :description (str n " does its part")}))}))}]
   :relationships [{:from :user :to :app :description "Uses"}]
   :views (vec (concat [{:key "context" :level :context :description "The demo and its users"}]
                       (for [[ctx _] ctxs]
                         {:key (str (name ctx) "-components") :level :component :context ctx
                          :description (str "The " (name ctx) " components")})))})

(defn scenario-text
  "A scenario's Gherkin: {:id :title :tags [..] :level :wip? :steps [..] :outline rows}."
  [{:keys [id title tags level wip? steps examples]}]
  (str "\n  " (str/join " " (concat (when id [(str "@" id)]) [(str "@" (or level "e2e"))] (when wip? ["@wip"]) tags)) "\n"
       "  Scenario" (when examples " Outline") ": " title "\n"
       (str/join (for [s (or steps ["Given a thing" "When it runs" "Then it holds"])] (str "    " s "\n")))
       (when examples
         (str "\n    Examples:\n"
              (str/join (for [r examples] (str "      | " (str/join " | " r) " |\n")))))))

(defn feature-text
  "A feature file: NAME, its need tags and its scenarios."
  [name needs scenarios]
  (str (when (seq needs) (str (str/join " " (map #(str "@" %) needs)) "\n"))
       "Feature: " name "\n"
       (str/join (map scenario-text scenarios))))

(defn issued-of [features]
  (reduce max 0 (for [[_ t] features m (re-seq #"@[a-z]+-(\d{4,})" t)] (parse-long (second m)))))

(defn spec-project!
  "A project set up by rdm-spec whose spec passes every check: MODEL (the
  kernel-only one by default), FEATURES {path text}, the demo needs and a
  source they cite; views drawn when the model has any. With :git, committed."
  [ctx & [{:keys [model features needs ledger prefix git] :or {needs demo-needs}}]]
  (let [ctx (project! ctx {:prefix prefix})
        model (or model kernel-model)
        features (or features {"dhf/spec/demo/thing.feature"
                               (feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "The thing works"}])})]
    (write-edn! ctx "dhf/spec/model.edn" model)
    (write-edn! ctx "dhf/spec/needs.edn" needs)
    (write-edn! ctx "dhf/raw/sources.edn" demo-sources)
    (write! ctx "dhf/raw/demo.md" "# Source: demo\n\nWhat the demo source is evidence for.\n")
    (doseq [[f t] features] (write! ctx f t))
    (write! ctx "dhf/spec/ids.edn" (str "{:issued " (or ledger (issued-of features)) "}\n"))
    (when (seq (:views model))
      (let [r ((requiring-resolve 'rdm-spec.kernel.command/run) {:dir (:dir ctx) :env (base-env ctx) :args ["views"]})]
        (when-not (zero? (:exit r)) (throw (ex-info (str "views failed: " (:err r)) {})))))
    (cond-> ctx git (commit! "the spec"))))

(defn result-json!
  "Writes an Allure result into DIR (relative to the project, or absolute):
  {:spec :status :name :parameters ...}; returns its file name."
  [ctx dir {:keys [spec status] :as m}]
  (let [uuid (str (random-uuid))
        d (if (fs/absolute? dir) dir (path ctx dir))
        r (merge {:uuid uuid :name (or (:name m) (str "result of " spec)) :status (or status "passed") :stage "finished"
                  :labels (cond-> [] spec (conj {:name "spec" :value spec}))
                  :steps [] :attachments [] :links [] :parameters []}
                 (dissoc m :spec :status))]
    (fs/create-dirs d)
    (spit (str d "/" uuid "-result.json") (json/generate-string r))
    (str uuid "-result.json")))

(defn own-project-copy!
  "A copy of rdm-spec's own project, as a clean room holds it: the paths its
  configuration keeps, its plugins (the room's oracle) left out."
  [ctx]
  (let [ctx (new-project-dir ctx)
        cfg (edn/read-string (slurp (str room "/dhf/harness/config.edn")))]
    (fs/create-dirs (:dir ctx))
    (doseq [k (get-in cfg [:clean-room :keep])
            :let [from (str room "/" k)]
            :when (fs/exists? from)]
      (if (fs/directory? from)
        (fs/copy-tree from (path ctx k))
        (fs/copy from (path ctx k))))
    (config! ctx dissoc :plugins)
    ctx))

;; ------------------------------------------------------------------ shared steps

;; sets :base :dir
(defstep #"a project set up by rdm-spec" (fn [ctx] (project! ctx)))

;; sets :exit :out :err :result :runs :spec-before
(defstep #"\"rdm-spec ([^\"]*)\" is run" (fn [ctx line] (run-rdm ctx line)))

(defstep #"it exits (\d)" (fn [ctx n] (exits! ctx (parse-long n))))

(defstep #"it passes" (fn [ctx] (exits! ctx 0)))

;; sets :dir :base; the configured stack words gain "Cobalt"
(defstep #"the configured stack words include \"Cobalt\""
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx (spec-project! ctx))]
      (config! ctx update :stack-words (fnil conj []) "Cobalt")
      ctx)))

;; sets :base :dir (a project of the cut spec-3 with a cycle 03)
(defn cut-project!
  "A project whose commit tagged spec-3 holds a spec of FEATURES, and whose
  dhf/cycles/03/cycle.edn names the cut spec-3."
  [ctx & [opts]]
  (let [ctx (if (:dir ctx) ctx (spec-project! ctx opts))]
    (commit-tag! ctx "spec-3")
    (write-edn! ctx "dhf/cycles/03/cycle.edn"
                {:cut "spec-3" :scope :project
                 :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
                 :spec-owner {:name "Sami" :actor :human}})
    ctx))

(declare verify-project!)

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx] (if (:dir ctx) (cut-project! ctx) (verify-project! ctx))))

;; the project's commit tagged spec-3 holds an unstamped scenario on line 4 of dhf/spec/demo/thing.feature
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (verify-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                     "@UN-001\nFeature: Thing\n\n  @e2e\n  Scenario: Not stamped\n    Given a thing\n"}})))

;; sets :dir :base: rdm-spec's own project, copied, with no results directory
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx] (own-project-copy! ctx)))

;; sets :bindings
(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx]
    (let [expected (edn/read-string (doc-string))
          b (matches-shape expected (:result ctx))]
      (check! b "standard output is not as stated:\n" (pr-str (:result ctx)) "\nexpected:\n" (pr-str expected))
      (assoc ctx :bindings b))))

;; sets :dir :base: a project whose spec holds the outline @spec-0001 with three rows
(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (spec-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                   (feature-text "Thing" ["UN-001"]
                                                 [{:id "spec-0001" :title "An outline of <n>"
                                                   :steps ["Given the value <n>" "Then it holds"]
                                                   :examples [["n"] ["1"] ["2"] ["3"]]}])}})))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (check! (some? (:spec-before ctx)) "no command ran")
    (check! (= (:spec-before ctx) (spec-files ctx)) "a file in the spec changed")
    ctx))

;; a project whose context view records another model's hash
(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx
                  (spec-project! ctx {:model (context-model [[:demo [[:thing "Thing"]]]])}))
          f (path ctx "dhf/views/context.mmd")]
      (spit f (str/replace-first (slurp f) #"model-sha256: [0-9a-f]+" (str "model-sha256: " (apply str (repeat 64 "0")))))
      ctx)))

;; ------------------------------------------------------------------ a cycle to verify

(def catch-all-steps
  "(ns fixture.any (:require [rdm-spec.kernel.runner :refer [defstep]]))\n(defstep #\".*\" (fn [ctx] ctx))\n")

(defn verify-project!
  "A project whose commit tagged spec-3 holds a spec (FEATURES, or one
  scenario), step definitions that pass every step, and a :tests check
  running its scenarios with rdm-spec run; dhf/cycles/03/cycle.edn names
  spec-3, and ROOM holds an implementation. CFG-FN changes the configuration
  before the cut."
  [ctx & [{:keys [features model cfg-fn room-files files needs]}]]
  (let [ctx (spec-project! ctx (cond-> {:features features :model model} needs (assoc :needs needs)))]
    (config! ctx (fn [c]
                   (cond-> (assoc c :steps-dir "dhf/steps" :implementation ["src"]
                                  :code-layout {:context ["src/{context}/"] :component ["src/{context}/{component}.clj"]}
                                  :verify [{:kind :tests :command (conj (rdm-command-line) "run")}])
                     cfg-fn cfg-fn)))
    (write! ctx "dhf/steps/any.clj" catch-all-steps)
    (write! ctx "src/kernel/thing.clj" "(ns kernel.thing)\n")
    (doseq [[f t] files] (write! ctx f t))
    (commit-tag! ctx "spec-3")
    (write-edn! ctx "dhf/cycles/03/cycle.edn"
                {:cut "spec-3" :scope :project
                 :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
                 :spec-owner {:name "Sami" :actor :human}})
    (let [room (str (:base ctx) "/room")]
      (doseq [[f t] (merge {"src/kernel/thing.clj" "(ns kernel.thing)\n"} room-files)]
        (write! (assoc ctx :dir room) f t))
      (assoc-in ctx [:ph "ROOM"] room))))

(defn verify-passes! [ctx]
  (let [ctx (run-rdm ctx "verify ROOM 03")]
    (check! (= 0 (:exit ctx)) "verify did not pass: " (shows ctx))
    ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx] (verify-passes! (if (:dir ctx) ctx (verify-project! ctx)))))
