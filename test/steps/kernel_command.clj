(ns steps.kernel-command
  "Step definitions of dhf/spec/kernel/command.feature, the kernel's shared
  steps (a step text used by the features of more than one context), and the
  helpers every step file uses: fixtures, running rdm-spec by path, reading
  what it printed.

  Every fixture lives in a directory the scenario makes under the tests'
  temporary directory, removed when the scenario ends, however it ends.

  Context keys the shared steps and helpers set, and what each holds:
    :base       the scenario's own directory (k/base!)
    :dir        the project the scenario's commands run in
    :ph         {placeholder path}: ROOM, OUT, DIR, R1, ... as made or named
    :names      what a Then expects the error stream to name (k/names+)
    :env        {variable value} the scenario's commands add; nil unsets one
    :exit :out :err :last-args :started :seconds   the last command run (k/rdm):
                its exit, output, arguments, start (ms) and how long it took
    :runs       the runs of a When run twice, in order
    :spec-before  the spec's files before the When (k/spec-snapshot)
    :bindings   {symbol value} bound by \"standard output, read as EDN, is exactly:\"
    :pins       {written real}: a pin as a step writes it, and the commit it stands for"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep fail! attach! defer!]]))

;; ---------------------------------------------------------------------------
;; Where things are

(def room-root
  "The repository the tests run in, whose src/ is the code under test."
  (str (fs/canonicalize (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))))

(def tmp-root
  "The tests' temporary directory: every fixture is made under it."
  (str (fs/canonicalize (System/getProperty "java.io.tmpdir"))))

(defn guard!
  "The path, once it is known to be inside the tests' temporary directory."
  [path]
  (when (nil? path) (throw (ex-info "no path: the scenario made no project" {})))
  (let [p (str (fs/absolutize path))]
    (when-not (str/starts-with? p (str tmp-root "/"))
      (throw (ex-info (str p " is outside the tests' temporary directory " tmp-root) {})))
    path))

(defn base!
  "The scenario's own directory, made once and removed when the scenario ends."
  [ctx]
  (if (:base ctx)
    ctx
    (let [d (str (fs/canonicalize (fs/create-temp-dir {:dir tmp-root :prefix "rdm-spec-test-"})))]
      (defer! #(fs/delete-tree d))
      (assoc ctx :base d))))

(defn path-in [ctx name] (str (fs/path (guard! (:base ctx)) name)))

(defn placeholder!
  "A new path standing for NAME in the scenario's own directory, made as a
  directory unless create? is false."
  ([ctx name] (placeholder! ctx name true))
  ([ctx name create?]
   (let [ctx (base! ctx)
         p (path-in ctx (str "ph-" (str/lower-case (str/replace name "/" "-"))))]
     (when create? (fs/create-dirs p))
     (assoc-in ctx [:ph name] p))))

(defn names+ [ctx & names] (update ctx :names (fnil into []) names))

;; ---------------------------------------------------------------------------
;; Processes: every one is stopped, also when a step gives up on it

(def base-env
  "The tests' environment, but RESULTS_DIR (dhf/spec/README.md)."
  (dissoc (into {} (System/getenv)) "RESULTS_DIR"))

(def git-identity
  {"GIT_AUTHOR_NAME" "rdm-spec tests" "GIT_AUTHOR_EMAIL" "tests@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "rdm-spec tests" "GIT_COMMITTER_EMAIL" "tests@rdm-spec.invalid"
   "GIT_CONFIG_NOSYSTEM" "1"})

(defn env-for
  "The environment of a command run in dir: the tests', git fenced at the
  fixture's parent, plus extra (a nil value unsets a variable)."
  [dir extra]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v))))
             (merge base-env git-identity
                    {"GIT_CEILING_DIRECTORIES" (str (fs/parent (fs/absolutize dir)))})
             (or extra {})))

(def limit-seconds 600)

(defn run-process
  "Runs cmd in dir, its output kept; stopped, with everything it started,
  past `limit-seconds`."
  [dir env cmd & [{:keys [in seconds]}]]
  (try
    (let [pr (p/process (mapv str cmd) {:dir (str dir) :env env :in (or in "") :out :string :err :string})
          r (deref pr (* 1000 (or seconds limit-seconds)) ::timeout)]
      (if (= r ::timeout)
        (do (p/destroy-tree pr)
            (let [d (deref pr 5000 nil)]
              {:exit -1 :out (str (:out d)) :err (str (:err d) "\n(stopped after " (or seconds limit-seconds) " seconds)")
               :timed-out true}))
        {:exit (:exit r) :out (:out r) :err (:err r)}))
    (catch java.io.IOException e
      {:exit 127 :out "" :err (ex-message e) :not-found true})))

(defn sh
  "Runs a command in dir with the tests' environment plus env: {:exit :out :err}."
  [dir env & cmd]
  (run-process dir (env-for dir env) cmd))

(defn git
  "Runs git in dir; its output, trimmed. A git that fails breaks the step."
  [dir & args]
  (let [r (apply sh dir {} "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" args)]
    (when-not (= 0 (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed in " dir ": " (:err r)) r)))
    (str/trim (:out r))))

(defn git-config [dir k] (str/trim (:out (sh dir {} "git" "config" "--get" k))))

(defn git-init!
  "A git repository at d, its hooks off."
  [d]
  (fs/create-dirs d)
  (git d "init" "-q")
  (git d "config" "core.hooksPath" "/dev/null")
  (git d "config" "commit.gpgsign" "false")
  (git d "config" "user.name" "rdm-spec tests")
  (git d "config" "user.email" "tests@rdm-spec.invalid")
  d)

(defn commit!
  "Commits everything in d, hooks off; the commit."
  [d msg]
  (git d "add" "-A")
  (git d "commit" "-q" "--no-verify" "--allow-empty" "-m" msg)
  (git d "rev-parse" "HEAD"))

(defn tag-cut!
  "Commits the project and tags the commit `tag`, moving the tag if it was set."
  [ctx tag]
  (commit! (guard! (:dir ctx)) (str "the cut " tag))
  (git (:dir ctx) "tag" "-f" tag)
  ctx)

;; ---------------------------------------------------------------------------
;; Running rdm-spec, by path

(defn rdm-command
  "The command line that runs the code under test, by path."
  []
  ["bb" "-cp" (str (fs/path room-root "src")) "-m" "rdm-spec.kernel.command"])

(defn rdm
  "Runs rdm-spec with args in the project (or dir); the context with the
  run's :exit, :out, :err and :last-args."
  ([ctx args] (rdm ctx (:dir ctx) args))
  ([ctx dir args]
   (let [dir (guard! dir)
         t0 (System/currentTimeMillis)
         r (run-process dir (env-for dir (:env ctx)) (concat (rdm-command) args))
         seconds (/ (- (System/currentTimeMillis) t0) 1000.0)]
     (attach! "command" (str "rdm-spec " (str/join " " args) "\nin " dir "\nexit " (:exit r)))
     (attach! "stdout" (str (:out r)))
     (attach! "stderr" (str (:err r)))
     (-> ctx (dissoc :runs) (assoc :exit (:exit r) :out (:out r) :err (:err r) :last-args (vec args)
                                   :started t0 :seconds seconds)))))

(def placeholder-names #{"ROOM" "OUT" "OUT2" "DIR" "R1"})

(defn split-line
  "The words of a command line, '...' quoting a word with spaces."
  [line]
  (map #(or (second %) (nth % 2)) (re-seq #"'([^']*)'|(\S+)" line)))

(defn rdm-line
  "Runs a command line as a When writes it, \"rdm-spec task ARGS\", each
  placeholder (ROOM, OUT, DIR/...) standing for its path: the one made for it,
  else a new one that does not exist."
  [ctx line]
  (let [ctx (base! ctx)
        words (rest (split-line line))
        ph-of (fn [ctx w]
                (let [[head & more] (str/split w #"/" 2)]
                  (if (or (contains? (:ph ctx) head) (placeholder-names head))
                    (let [ctx (if (contains? (:ph ctx) head) ctx (placeholder! ctx head false))]
                      [ctx (str (get-in ctx [:ph head]) (when more (str "/" (first more))))])
                    [ctx w])))
        [ctx args] (reduce (fn [[c out] w] (let [[c a] (ph-of c w)] [c (conj out a)])) [ctx []] words)]
    (rdm ctx args)))

;; ---------------------------------------------------------------------------
;; Reading what it printed

(defn edn-out
  "The standard output, read as EDN."
  [ctx]
  (try (edn/read-string (:out ctx))
       (catch Exception e (fail! (str "the standard output is not EDN (" (ex-message e) "): " (:out ctx) (:err ctx))))))

(defn err-lines [ctx] (remove str/blank? (str/split-lines (str (:err ctx)))))

(defn check-exit
  "Asserts the last command exited n; the context."
  [ctx n]
  (when-not (= n (:exit ctx))
    (fail! (str "it exited " (:exit ctx) ", not " n "\nstdout: " (:out ctx) "\nstderr: " (:err ctx))))
  ctx)

(defn err-has [ctx s]
  (when-not (str/includes? (str (:err ctx)) (str s))
    (fail! (str "the error stream does not name " s ":\n" (:err ctx))))
  ctx)

(defn err-line-with
  "Asserts one line of the error stream holds every one of ss; the context."
  [ctx & ss]
  (when-not (some (fn [l] (every? #(str/includes? l (str %)) ss)) (err-lines ctx))
    (fail! (str "no line of the error stream holds " (pr-str ss) ":\n" (:err ctx))))
  ctx)

(defn finding-rules
  "The RULE of every finding line of the error stream."
  [ctx]
  (for [l (err-lines ctx)
        :when (not (re-find #"^(warning|event|cannot-run): " l))
        :when (not (str/includes? l ": warning: "))
        :let [parts (str/split l #": ")
              rule (first (filter #(re-matches #"[a-z][a-z0-9-]*" %) parts))]
        :when rule]
    rule))

;; ---------------------------------------------------------------------------
;; Writing fixtures

(defn pr-edn [x] (with-out-str (pp/pprint x)))

(defn write-files!
  "Writes {relative-path text} under dir; dir."
  [dir files]
  (doseq [[rel text] files]
    (let [f (fs/path (guard! dir) (str rel))]
      (fs/create-dirs (fs/parent f))
      (spit (fs/file f) text)))
  dir)

(defn update-edn!
  "Applies f (and args) to the EDN file rel of the project; the context."
  [ctx rel f & args]
  (let [file (fs/file (guard! (:dir ctx)) rel)
        v (when (fs/exists? file) (edn/read-string (slurp file)))]
    (spit file (pr-edn (apply f v args)))
    ctx))

(defn real-pin
  "s with every pin a step wrote replaced by the commit it stands for."
  [ctx s]
  (reduce (fn [s [k v]] (str/replace s k v)) s (:pins ctx)))

(defn spec-snapshot
  "Every file of the project's spec, with its text."
  [ctx]
  (let [d (fs/path (guard! (:dir ctx)) "dhf/spec")]
    (into (sorted-map) (for [f (fs/glob d "**") :when (fs/regular-file? f)]
                         [(str (fs/relativize d f)) (slurp (fs/file f))]))))

(defn sc
  "A stamped scenario at the level @e2e: its id n, its title and its steps."
  ([n title] (sc n title ["Given a step"]))
  ([n title steps] {:tags [(format "spec-%04d" n) "e2e"] :title title :steps (vec steps)}))

(defn- tags-line [tags] (str/join " " (map #(str "@" %) tags)))

(defn scenario-text
  "A scenario (k/sc), indented under a feature or a rule, and a blank line."
  ([s] (scenario-text s "  "))
  ([{:keys [tags title steps examples]} ind]
   (let [sind (str ind "  ")]
     (str (when (seq tags) (str ind (tags-line tags) "\n"))
          ind (if examples "Scenario Outline: " "Scenario: ") title "\n"
          (str/join (map #(str sind % "\n") steps))
          (when examples
            (str "\n" sind "Examples:\n"
                 (str/join (map (fn [row] (str sind "  | " (str/join " | " row) " |\n")) examples))))
          "\n"))))

(defn feature-text
  "A feature file: {:name :tags :scenarios :rules [{:name :tags :scenarios}]}."
  [{:keys [name tags scenarios rules]}]
  (str (when (seq tags) (str (tags-line tags) "\n"))
       "Feature: " name "\n\n"
       (str/join (map scenario-text scenarios))
       (str/join (for [r rules]
                   (str (when (seq (:tags r)) (str "  " (tags-line (:tags r)) "\n"))
                        "  Rule: " (:name r) "\n\n"
                        (str/join (map #(scenario-text % "    ") (:scenarios r))))))))

(defn line-of [text s]
  (inc (or (first (keep-indexed (fn [i l] (when (str/includes? l s) i)) (str/split-lines text)))
           (throw (ex-info (str s " is not in the text") {})))))

(defn doc-text
  "A controlled document: its frontmatter (id, revision, title, as given) and its body."
  [{:keys [id revision title]} body]
  (str "---\n"
       (when id (str "id: " id "\n"))
       (when revision (str "revision: " revision "\n"))
       (when title (str "title: " title "\n"))
       "---\n" body))

(defn model
  "A model of the demo system: one container, app, holding the bounded
  contexts [[context [component ...]] ...], the kernel's components, a
  component view per context and the views given."
  [{:keys [contexts kernel views]}]
  (let [comp (fn [c k] {:id (keyword c k) :name (str/capitalize k) :description (str "The " k " component")
                        :spec (str c "/" k)})]
    {:system {:id :demo :name "demo" :description "A demo"}
     :people [{:id :user :name "User" :description "Uses it" :actor #{:human}}]
     :external []
     :containers [{:id :app :name "app" :kind :runs :technology "A program" :description "The program"
                   :contexts (vec (for [[c ks] contexts]
                                    {:id (keyword c) :name c :description (str "The " c " context")
                                     :components (mapv #(comp c %) ks)}))}]
     :kernel (mapv #(comp "kernel" %) kernel)
     :relationships [{:from :user :to :app :description "Uses"}]
     :views (vec (concat views
                         (for [[c _] contexts]
                           {:key (str c "-components") :level :component :context (keyword c)
                            :description (str "The " c " components")})))}))

(def code-layout
  {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
   :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
               "test/steps/{context}_{component}.clj"]})

(def default-config
  "A fixture's configuration: no oracle and no plugin, unless its scenario sets one."
  {:id-prefix "spec"
   :spec-dir "dhf/spec" :ledger "dhf/spec/ids.edn" :model "dhf/spec/model.edn"
   :events "dhf/spec/events.edn" :needs "dhf/spec/needs.edn"
   :views-dir "dhf/views" :raw-dir "dhf/raw" :wiki-dir "dhf/wiki"
   :stack-words ["Babashka" "Clojure"]
   :steps-dir "test/steps"
   :implementation ["src" "test" "test.clj"]
   :code-layout code-layout
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"]
                       :generated ["site" "site-src" "dhf/allure-results"]}
                :keep [".gitignore" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views"
                       "dhf/decisions" "dhf/clauses" "dhf/documents"]}
   :verify [{:kind :tests :command ["bb" "test.clj"]}]
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :c4 {:view-element-limit 20 :description-max-chars 45}})

(def default-needs {"UN-001" {:text "A user need" :sources ["demo:README.md@abc1234"]}})

(def core-feature "dhf/spec/kernel/core.feature")

(defn- needs-text [needs] (pr-edn (into (sorted-map) needs)))

(defn project!
  "The scenario's project: made by the first Given that asks for one (a git
  repository, hooks off, whose spec passes every check), then built on.
  opts: :features {path text}, :model-spec, :needs {id need}, :issued,
  :config (merged into the configuration), :extra {path text}."
  ([ctx] (project! ctx {}))
  ([ctx {:keys [features model-spec needs issued config extra]}]
   (if (::project ctx)
     (let [d (:dir ctx)]
       (when model-spec (write-files! d {"dhf/spec/model.edn" (pr-edn (model model-spec))}))
       (when needs (write-files! d {"dhf/spec/needs.edn" (needs-text needs)}))
       (when issued (write-files! d {"dhf/spec/ids.edn" (pr-edn {:issued issued})}))
       (when features (write-files! d features))
       (when config (update-edn! ctx "dhf/harness/config.edn" merge config))
       (when extra (write-files! d extra))
       ctx)
     (let [ctx (base! ctx)
           d (path-in ctx "project")]
       (git-init! d)
       (write-files! d (merge {"dhf/harness/config.edn" (pr-edn (merge default-config config))
                               "dhf/spec/model.edn" (pr-edn (model (or model-spec {:kernel ["core"]})))
                               "dhf/spec/events.edn" "{}\n"
                               "dhf/spec/needs.edn" (needs-text (or needs default-needs))
                               "dhf/spec/ids.edn" (pr-edn {:issued (or issued 100)})
                               "dhf/raw/sources.edn" (pr-edn {:demo {:repository "https://example.org/demo" :record "demo.md"
                                                                     :pins ["abc1234"] :fenced []}
                                                              :loyly {:repository "https://github.com/skallinen/loyly"
                                                                      :record "loyly.md" :pins ["17d68d2"] :fenced []}})
                               "dhf/raw/demo.md" "# Source: demo\n"
                               "dhf/raw/loyly.md" "# Source: loyly\n"
                               "dhf/wiki/index.md" "# Wiki\n\nThe domain, distilled from the raw sources.\n\n"
                               core-feature (feature-text {:name "Core" :tags ["UN-001"] :scenarios [(sc 1 "One")]})}
                              features
                              extra))
       (assoc ctx :dir d ::project true)))))

(defn init-project!
  "A project set up by rdm-spec init, its hooks off."
  [ctx]
  (let [ctx (base! ctx)
        r (rdm ctx (:base ctx) ["init" "project"])]
    (check-exit r 0)
    (let [d (path-in ctx "project")]
      (git d "config" "core.hooksPath" "/dev/null")
      (assoc ctx :dir d ::project true))))

(defn views!
  "Generates the project's views; the context."
  [ctx]
  (check-exit (rdm ctx ["views"]) 0)
  ctx)

(defn wiki-page!
  "A wiki page at path, linked from the wiki's index; the context."
  [ctx path text]
  (let [ctx (project! ctx)
        index (fs/file (:dir ctx) "dhf/wiki/index.md")
        rel (subs path (count "dhf/wiki/"))]
    (write-files! (:dir ctx) {path text})
    (spit index (str (slurp index) "- [" (fs/file-name path) "](" rel ")\n"))
    ctx))

(defn write-result!
  "An Allure result in dir: {:id :status :params [[name value] ...]}; its file's name."
  [dir {:keys [id status params]}]
  (let [uuid (str (random-uuid))
        file (str uuid "-result.json")]
    (fs/create-dirs dir)
    (spit (fs/file dir file)
          (json/generate-string {"uuid" uuid "name" (str "About " id) "status" (or status "passed") "stage" "finished"
                                 "labels" (if id [{"name" "spec" "value" id}] [])
                                 "parameters" (mapv (fn [[n v]] {"name" n "value" v}) params)}))
    file))

(defn pass-all!
  "A passing result for each id in the configured results directory; the context."
  [ctx ids]
  (doseq [id ids] (write-result! (fs/path (guard! (:dir ctx)) "dhf/allure-results") {:id id}))
  ctx)

(def roles
  {:builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn cycle!
  "dhf/cycles/n/cycle.edn holding decl; the context."
  [ctx n decl]
  (write-files! (:dir ctx) {(str "dhf/cycles/" n "/cycle.edn") (pr-edn decl)})
  ctx)

(def room-test
  "A room's test command: the kernel's runner over the checkout's spec, every
  step passing, its results where RESULTS_DIR says."
  (str "(require '[babashka.classpath :refer [add-classpath]])\n"
       "(add-classpath " (pr-str (str (fs/path room-root "src"))) ")\n"
       "(require '[rdm-spec.kernel.runner :as r])\n"
       "(System/exit (if (r/run-suite! {:root \".\" :paths [] :parallel 1\n"
       "                                :results-dir (or (System/getenv \"RESULTS_DIR\") \"dhf/allure-results\")})\n"
       "               0 1))\n"))

(def passing-steps
  "(require '[rdm-spec.kernel.runner :refer [defstep]])\n(defstep #\".*\" (fn [ctx & _] ctx))\n")

(defn room!
  "ROOM, a room whose implementation's tests pass every scenario of the spec
  they are run on, unless the scenario made ROOM already; the context."
  [ctx]
  (if (get-in ctx [:ph "ROOM"])
    ctx
    (let [ctx (placeholder! ctx "ROOM")]
      (write-files! (get-in ctx [:ph "ROOM"]) {"test.clj" room-test
                                               "test/steps/all.clj" passing-steps
                                               "src/app.clj" "(ns app)\n"})
      ctx)))

(defn- tagged? [ctx tag] (= 0 (:exit (sh (:dir ctx) {} "git" "rev-parse" "-q" "--verify" (str "refs/tags/" tag)))))

(defn cycle-ready!
  "Cycle 03 of the cut spec-3 (tagged now unless it is), declared with its
  roles, and a room ROOM to verify; the context."
  [ctx]
  (let [ctx (-> (project! ctx) room! (cycle! "03" (merge {:cut "spec-3" :scope :project} roles)))]
    (if (tagged? ctx "spec-3") ctx (tag-cut! ctx "spec-3"))))

(defn regulated!
  "A project under the regulated profile: the clause list dhf/clauses/std.txt
  (:clauses), the documents (:docs, by default a plan naming STD:5.1) and the
  project's opts (:opts)."
  ([ctx] (regulated! ctx {}))
  ([ctx {:keys [clauses docs opts]}]
   (let [ctx (project! ctx (update opts :config merge {:regulated {:clause-lists ["dhf/clauses/std.txt"]
                                                                  :documents "dhf/documents"}}))]
     (write-files! (:dir ctx)
                   (merge {"dhf/clauses/std.txt" (or clauses "STD:5.1 Development plan\n")}
                          (or docs {"dhf/documents/plan.md"
                                    (doc-text {:id "PLAN-001" :revision "1" :title "Development plan"}
                                              "# Plan\n\nWe plan [[STD:5.1]].\n")})))
     ctx)))

;; ---------------------------------------------------------------------------
;; The kernel's shared steps: Whens

(defstep #"\"(rdm-spec [^\"]*)\" is run( twice| with no --results| with that (?:results )?directory as DIR)?"
  (fn [ctx line how]
    (let [ctx (if (:dir ctx) ctx (project! ctx))]
      (if (= how " twice")
        (let [a (rdm-line ctx line) b (rdm-line a line)] (assoc b :runs [a b]))
        (rdm-line ctx line)))))

;; ---------------------------------------------------------------------------
;; The kernel's shared steps: Givens

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (init-project! ctx)))

(defstep #"a spec that passes every check"
  (fn [ctx] (project! ctx)))

(defstep #"the configured stack words include \"(.+)\""
  (fn [ctx w] (update-edn! (project! ctx) "dhf/harness/config.edn" update :stack-words (fnil conj []) w)))

(def known-repositories {"loyly" "https://github.com/skallinen/loyly" "rdm" "https://github.com/scope-impact/rdm"})

(defstep #"a wiki page whose footnote cites \"(.+)\""
  (fn [ctx citation]
    (let [ctx (project! ctx)
          c (real-pin ctx citation)
          [_ src commit] (re-matches #"([^:]+):.*@(\S+)" c)
          sources (edn/read-string (slurp (fs/file (:dir ctx) "dhf/raw/sources.edn")))
          page "dhf/wiki/pages/footnoted.md"]
      (when-not (contains? sources (keyword src))
        (update-edn! ctx "dhf/raw/sources.edn" assoc (keyword src)
                     {:repository (get known-repositories src (str "https://example.org/" src))
                      :record (str src ".md") :pins [commit] :fenced []})
        (write-files! (:dir ctx) {(str "dhf/raw/" src ".md") (str "# Source: " src "\n")}))
      (-> (wiki-page! ctx page (str "# Footnoted\n\nA claim.[^1]\n\n[^1]: `" c "`, \"A quote\".\n"))
          (names+ page c)))))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [ctx (-> (project! ctx {:model-spec {:contexts [["shop" ["till"]]]}
                                 :features {"dhf/spec/shop/till.feature"
                                            (feature-text {:name "Till" :tags ["UN-001"] :scenarios [(sc 1 "Takes payments")]})}})
                  views!)]
      (fs/delete-if-exists (fs/path (:dir ctx) core-feature))
      (-> (update-edn! ctx "dhf/spec/model.edn" assoc-in [:system :description] "Another demo")
          (names+ "dhf/views/shop-components" "stale")))))

(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (project! ctx {:features {core-feature
                              (feature-text {:name "Core" :tags ["UN-001"]
                                             :scenarios [{:tags ["spec-0001" "contract"] :title "Rows"
                                                          :steps ["Given <name> is <n>"]
                                                          :examples [["name" "n"] ["a" "1"] ["b" "2"] ["c" "3"]]}]})}
                   :extra {"test/steps/core.clj" passing-steps}})))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [ctx (base! ctx)
          d (path-in ctx "project")
          config (dissoc (edn/read-string (slurp (fs/file room-root "dhf/harness/config.edn"))) :plugins :oracle)]
      (git-init! d)
      (doseq [p ["dhf/spec" "dhf/raw" "dhf/wiki" "dhf/views"]]
        (fs/copy-tree (fs/path room-root p) (fs/path d p)))
      (write-files! d {"dhf/harness/config.edn" (pr-edn config)})
      (assert (not (fs/exists? (fs/path d (:results-dir config "dhf/allure-results")))))
      (assoc ctx :dir d ::project true))))

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx] (cycle-ready! ctx)))

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3 and the scope :project"
  (fn [ctx] (cycle! (project! ctx) "03" (merge {:cut "spec-3" :scope :project} roles))))

(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [text (feature-text {:name "Core" :tags ["UN-001"]
                              :scenarios [(sc 1 "One") {:tags ["e2e"] :title "Unstamped" :steps ["Given a step"]}]})
          ctx (project! ctx {:features {core-feature text}})]
      (-> (cycle-ready! ctx)
          (names+ (str core-feature ":" (line-of text "Scenario: Unstamped")))))))

;; ---------------------------------------------------------------------------
;; The kernel's shared steps: Thens

(defstep #"it exits (\d+)"
  (fn [ctx n] (check-exit ctx (parse-long n))))

(defstep #"it passes"
  (fn [ctx] (check-exit ctx 0)))

(defstep #"(?:it|the check|the citation) fails and names .+"
  (fn [ctx]
    (check-exit ctx 1)
    (when (empty? (:names ctx)) (fail! "the scenario named nothing to look for"))
    (doseq [n (:names ctx)] (err-has ctx n))
    ctx))

(defstep #"it fails rule (\S+) and names .+"
  (fn [ctx rule]
    (check-exit ctx 1)
    (apply err-line-with ctx rule (remove #{rule} (:names ctx)))))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (when-not (:spec-before ctx) (fail! "the spec was not kept aside before the When"))
    (assert (= (:spec-before ctx) (spec-snapshot ctx)) "a file in the spec changed")
    ctx))

(defn unify
  "Binds every upper-case symbol of pattern to the value at its place: the
  bindings, or nil when value does not match."
  [pattern value bindings]
  (cond
    (nil? bindings) nil
    (and (symbol? pattern) (re-matches #"[A-Z][A-Z0-9]*" (name pattern)))
    (if (contains? bindings pattern)
      (when (= (bindings pattern) value) bindings)
      (assoc bindings pattern value))
    (map? pattern)
    (when (and (map? value) (= (set (keys pattern)) (set (keys value))))
      (reduce (fn [b k] (unify (get pattern k) (get value k) b)) bindings (keys pattern)))
    (sequential? pattern)
    (when (and (sequential? value) (= (count pattern) (count value)))
      (reduce (fn [b [x y]] (unify x y b)) bindings (map vector pattern value)))
    :else (when (= pattern value) bindings)))

(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (check-exit ctx 0)
    (let [b (unify (edn/read-string doc) (edn-out ctx) {})]
      (when-not b (fail! (str "the standard output is not\n" doc "\nbut\n" (:out ctx))))
      (assoc ctx :bindings b))))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature

(def tasks
  "The tasks in the order the help lists them."
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist"
   "status" "trace" "release" "score" "scoreboard" "oracle" "plugin" "publish" "audit" "observe"])

(defstep #"its standard output, read as EDN, is \{:help \[\{:task T :usage U :does D\} \.\.\.\]\}, one map per task in the order below, T the task's name as a string"
  (fn [ctx]
    (check-exit ctx 0)
    (let [h (:help (edn-out ctx))]
      (assert (vector? h) (:out ctx))
      (assert (every? #(and (map? %) (= #{:task :usage :does} (set (keys %))) (every? string? (vals %))) h) (:out ctx))
      (assert (= tasks (map :task h)) (pr-str (map :task h)))
      ctx)))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [h (first (filter #(= task (:task %)) (:help (edn-out ctx))))]
      (assert h (str "no task " task))
      (assert (= usage (:usage h)) (pr-str h))
      (assert (= does (:does h)) (pr-str h))
      ctx)))

(defn- results-in [ctx dir id]
  (write-result! dir {:id id})
  ctx)

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (project! ctx)]
      (assert (= "dhf/allure-results" (:results-dir (edn/read-string (slurp (fs/file (:dir ctx) "dhf/harness/config.edn"))))))
      (-> ctx
          (results-in (fs/path (:dir ctx) "dhf/allure-results") "spec-0001")
          (results-in (fs/path (:dir ctx) "other") "spec-0001")))))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= v "unset")
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [ctx (placeholder! ctx "R1")
            d (get-in ctx [:ph "R1"])]
        (results-in ctx d "spec-0001")
        (assoc-in ctx [:env "RESULTS_DIR"] d)))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (check-exit ctx 0)
    (let [want (get-in ctx [:ph dir] dir)
          got (get-in (edn-out ctx) [:status :results])]
      (assert (= want got) (str "it read " got ", not " want))
      ctx)))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [ctx (-> (project! ctx {:model-spec {:contexts [["shop" ["till"]]]}
                                 :features {"dhf/spec/shop/till.feature"
                                            (feature-text {:name "Till" :tags ["UN-001"]
                                                           :scenarios [{:tags ["spec-0001"] :title "No level" :steps ["Given a step"]}]})}})
                  views!)]
      (fs/delete-if-exists (fs/path (:dir ctx) core-feature))
      (update-edn! ctx "dhf/spec/model.edn" assoc-in [:system :description] "Another demo"))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (check-exit ctx 1)
    (err-line-with ctx "dhf/spec/shop/till.feature" "level-missing")
    (err-line-with ctx "dhf/views/shop-components" "stale")))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (project! ctx {:features {core-feature (feature-text {:name "Core" :tags ["UN-001"]
                                                          :scenarios [{:tags ["spec-0001"] :title "No level" :steps ["Given a step"]}]})}})))

(def finding-line #"(?:[^\s:]+(?::\d+)?: )?[a-z][a-z0-9-]*: \S.*")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [ls (remove #(or (str/starts-with? % "event: ") (str/includes? % "warning: ")) (err-lines ctx))]
      (assert (seq ls) "no finding was printed")
      (doseq [l ls] (assert (re-matches finding-line l) (str "not a finding line: " l)))
      (err-line-with ctx (str core-feature ":") "level-missing"))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (java.io.PushbackReader. (java.io.StringReader. (str (:out ctx))))
          v (edn/read {:eof ::eof} r)
          more (edn/read {:eof ::eof} r)]
      (assert (map? v) (str "the result is not a map: " (:out ctx)))
      (assert (= ::eof more) (str "the standard output holds more than the result: " (:out ctx)))
      ctx)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (check-exit ctx 2) (err-has ctx "dhf/allure-results")))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (check-exit ctx 2)
    (err-has ctx "frobnicate")
    (doseq [t tasks] (err-has ctx t))
    ctx))
