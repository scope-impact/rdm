(ns steps.kernel-command
  "The kernel's step definitions: dhf/spec/kernel/command.feature, and the
  shared steps and helpers every other step file builds on.

  A scenario's project is one it makes, a fixture under the tests' TMPDIR,
  never the room. Its commands run the code under test by path, with
  GIT_CEILING_DIRECTORIES set above the fixture, RESULTS_DIR removed, and its
  git hooks off unless a step says otherwise.

  Context keys the kernel sets, which other step files may read:
    :base   the scenario's own directory (k/base!), removed when it ends
    :dir    the project's root (k/project!, k/init-project!)
    :ph     {\"ROOM\" path ...}: the upper-case placeholders a step named
    :env    extra environment for the scenario's commands
    :exit :out :err   the last command's outcome (k/rdm), :runs every one
    :names  what a Then \"... fails ... and names ...\" looks for (k/names+)
    :pins   {written-pin real-pin} (k/real-pin)
    :spec-before  the spec's files before the first command (k/spec-snapshot)"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep fail!]]))

;; ---------------------------------------------------------------------------
;; Where things are

(def room-root
  "The repository the tests run in: the code under test."
  (str (fs/canonicalize ".")))

(def tmp-root
  "The tests' temporary directory: every fixture lives under it."
  (str (fs/canonicalize (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))))

(defn guard!
  "A fixture path: asserts it lies under the tests' temporary directory, never
  in the room itself."
  [path]
  (when-not path (fail! "no fixture path: the scenario made no project"))
  (let [c (str (fs/canonicalize path))]
    (when-not (str/starts-with? c (str tmp-root "/"))
      (fail! (str c " is not a fixture under " tmp-root)))
    path))

(defn rdm-command
  "The command line that runs the code under test, by path."
  []
  ["bb" (str "-Djava.io.tmpdir=" tmp-root) "-cp" (str (fs/path room-root "src")) "-m" "rdm-spec.kernel.command"])

;; ---------------------------------------------------------------------------
;; Running things

(def git-identity
  {"GIT_AUTHOR_NAME" "fixture" "GIT_AUTHOR_EMAIL" "fixture@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "fixture" "GIT_COMMITTER_EMAIL" "fixture@rdm-spec.invalid"})

(defn- ceiling [dir base]
  (str (or base (fs/parent (fs/absolutize dir)))))

(defn sh
  "Runs args in dir with env added: {:exit :out :err}. Git never finds a
  repository above the fixture."
  [dir env & args]
  (let [base (get env ::base)
        env (dissoc env ::base)
        e (-> (into {} (System/getenv))
              (dissoc "RESULTS_DIR")
              (merge git-identity {"GIT_CEILING_DIRECTORIES" (ceiling dir base)})
              (merge env))]
    (try
      (let [r @(p/process (vec args) {:dir (str dir) :env e :out :string :err :string :in ""})]
        {:exit (:exit r) :out (:out r) :err (:err r)})
      (catch java.io.IOException ex
        {:exit 127 :out "" :err (str "not found: " (ex-message ex))}))))

(defn git
  "Runs git in dir, hooks off, and asserts it passed; its output, trimmed."
  [dir & args]
  (let [r (apply sh dir {} "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (when-not (= 0 (:exit r)) (fail! (str "git " (str/join " " args) " in " dir ": " (:err r))))
    (str/trim (:out r))))

(defn git-config [dir k]
  (let [r (sh dir {} "git" "config" "--get" k)]
    (when (= 0 (:exit r)) (str/trim (:out r)))))

(defn git-init! [d]
  (fs/create-dirs d)
  (git d "init" "-q")
  d)

(defn commit!
  "Commits everything in dir, hooks off; the commit."
  [dir msg]
  (git dir "add" "-A" "-f" "--" ".")
  (git dir "commit" "-q" "--allow-empty" "--no-verify" "-m" msg)
  (git dir "rev-parse" "HEAD"))

;; ---------------------------------------------------------------------------
;; The scenario's directory

(defn base!
  "The scenario's own directory under TMPDIR, made once and removed when the
  scenario ends."
  [ctx]
  (if (:base ctx)
    ctx
    (let [d (str (fs/canonicalize (fs/create-temp-dir {:dir tmp-root :prefix "scenario-"})))]
      (runner/cleanup! #(fs/delete-tree d))
      (assoc ctx :base d))))

(defn path-in
  "A path in the scenario's directory."
  [ctx name]
  (when-not (:base ctx) (fail! "the scenario has no directory yet"))
  (str (fs/path (:base ctx) name)))

(defn placeholder!
  "Makes the directory a placeholder (DIR, W1, OUT ...) names."
  [ctx name]
  (let [ctx (base! ctx)
        d (path-in ctx name)]
    (fs/create-dirs d)
    (assoc-in ctx [:ph name] d)))

(defn write-files!
  "Writes {path text} under dir."
  [dir files]
  (doseq [[p text] files]
    (let [f (fs/file dir p)]
      (fs/create-dirs (fs/parent f))
      (spit f text)))
  dir)

(defn pr-edn [x]
  (binding [*print-namespace-maps* false]
    (with-out-str (pp/pprint x))))

(defn update-edn!
  "Rewrites the EDN file at path in the project with (apply f value args)."
  [ctx path f & args]
  (let [file (fs/file (guard! (:dir ctx)) path)
        v (when (fs/exists? file) (edn/read-string (slurp file)))]
    (write-files! (:dir ctx) {path (pr-edn (apply f v args))})
    ctx))

(defn line-of
  "The line (from 1) of text that first holds s."
  [text s]
  (some (fn [[i l]] (when (str/includes? l s) (inc i))) (map-indexed vector (str/split-lines (str text)))))

(defn real-pin
  "s with each pin a step wrote replaced by the commit it stands for."
  [ctx s]
  (reduce (fn [acc [w r]] (str/replace acc w r)) s (sort-by (comp - count key) (:pins ctx))))

(defn names+
  "Adds to what the scenario's Then must find named."
  [ctx & names]
  (update ctx :names (fnil into []) names))

;; ---------------------------------------------------------------------------
;; Writing a spec

(def core-feature "dhf/spec/kernel/core.feature")

(defn sc
  "A stamped @e2e scenario n."
  ([n title] (sc n title nil))
  ([n title steps]
   {:tags [(format "spec-%04d" n) "e2e"] :title title
    :steps (or steps ["Given a step" "When it runs" "Then it passes"])}))

(defn- tag-line [indent tags]
  (when (seq tags) (str indent (str/join " " (map #(str "@" %) tags)) "\n")))

(defn- scenario-text [indent {:keys [tags title steps outline examples]}]
  (str "\n" (tag-line indent tags)
       indent (if (or outline examples) "Scenario Outline: " "Scenario: ") title "\n"
       (str/join (for [s steps] (str indent "  " s "\n")))
       (when examples
         (str "\n" indent "  Examples:\n"
              (str/join (for [row examples] (str indent "    | " (str/join " | " row) " |\n")))))))

(defn feature-text
  "A feature file: {:name :tags :description :background :scenarios
  :rules [{:name :tags :scenarios}]}."
  [{:keys [name tags description background scenarios rules]}]
  (str (tag-line "" tags)
       "Feature: " name "\n"
       (when description (str "  " description "\n"))
       (when (seq background)
         (str "\n  Background:\n" (str/join (for [s background] (str "    " s "\n")))))
       (str/join (map #(scenario-text "  " %) scenarios))
       (str/join (for [r rules]
                   (str "\n" (tag-line "  " (:tags r)) "  Rule: " (:name r) "\n"
                        (str/join (map #(scenario-text "    " %) (:scenarios r))))))))

(defn doc-text
  "A controlled document: frontmatter then body."
  [fm body]
  (str "---\n" (str/join (for [k [:id :revision :title] :when (contains? fm k)] (str (name k) ": " (get fm k) "\n")))
       "---\n" body))

(defn model
  "A C4 model: the system demo, the person user, the container app holding
  the bounded contexts [[name [component ...]] ...], the kernel's components,
  the views given and a component view per context."
  [{:keys [contexts kernel views]}]
  {:system {:id :demo :name "demo" :description "A demo system"}
   :people [{:id :user :name "User" :actor #{:human} :description "Uses the demo"}]
   :external []
   :containers [{:id :app :name "app" :kind :runs :technology "A program" :description "The demo program"
                 :contexts (vec (for [[c comps] contexts]
                                  {:id (keyword c) :name c :description (str "The " c " context")
                                   :components (vec (for [k comps]
                                                      {:id (keyword c k) :name (str/capitalize k)
                                                       :description (str "The " k " component") :spec (str c "/" k)}))}))}]
   :kernel (vec (for [k kernel]
                  {:id (keyword "kernel" k) :name (str/capitalize k) :description (str "The " k " component")
                   :spec (str "kernel/" k)}))
   :relationships [{:from :user :to :app :description "Uses"}]
   :views (vec (concat views
                       (for [[c _] contexts]
                         {:key (str c "-components") :level :component :context (keyword c)
                          :description (str "The " c " context's components")})))})

(def roles
  {:builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(def default-config
  {:id-prefix "spec"
   :spec-dir "dhf/spec" :ledger "dhf/spec/ids.edn" :model "dhf/spec/model.edn"
   :events "dhf/spec/events.edn" :needs "dhf/spec/needs.edn"
   :views-dir "dhf/views" :raw-dir "dhf/raw" :wiki-dir "dhf/wiki"
   :stack-words []
   :steps-dir "test/steps"
   :implementation ["src" "test"]
   :code-layout {:context ["src/{context}/" "test/{context}/"]
                 :component ["src/{context}/{component}.clj" "test/{context}/{component}_test.clj"]}
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["site" "site-src" "dhf/allure-results"]}
                :keep [".gitignore" "README.md" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"
                       "dhf/clauses" "dhf/documents"]}
   :verify [{:kind :tests :command ["sh" "-c" "exit 0"]}]
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :c4 {:view-element-limit 20 :description-max-chars 45}})

(defn default-files []
  {"dhf/spec/model.edn" (pr-edn (model {:kernel ["core"]}))
   "dhf/spec/events.edn" "{}\n"
   "dhf/spec/needs.edn" (pr-edn {"UN-001" {:text "A user needs a demo." :sources ["demo:README.md@abc1234"]}})
   "dhf/spec/ids.edn" "{:issued 999}\n"
   "dhf/spec/glossary.md" "# Glossary\n"
   core-feature (feature-text {:name "Core" :tags ["UN-001"] :scenarios [(sc 1 "A scenario")]})
   "dhf/raw/sources.edn" (pr-edn {:demo {:repository "https://example.org/demo" :record "demo.md" :pins ["abc1234"] :fenced []}
                                  :loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md" :pins ["17d68d2"] :fenced []}})
   "dhf/raw/demo.md" "# Source: demo\n"
   "dhf/raw/loyly.md" "# Source: loyly\n"
   "dhf/wiki/index.md" "# Wiki\n\n## Pages\n"})

(defn project!
  "The scenario's project, made by its first Given and built on by the rest:
  a spec that passes every check (one kernel component, core, owning one
  stamped scenario), at the scenario's directory's \"project\", a git
  repository with no commit. opts: :config (merged into the configuration),
  :features and :extra ({path text}), :model-spec (a k/model spec)."
  ([ctx] (project! ctx {}))
  ([ctx {:keys [config features extra model-spec]}]
   (let [fresh? (nil? (:dir ctx))
         ctx (base! ctx)
         ctx (if fresh? (assoc ctx :dir (path-in ctx "project")) ctx)
         d (:dir ctx)]
     (when fresh?
       (git-init! d)
       (write-files! d (assoc (default-files) "dhf/harness/config.edn" (pr-edn (merge default-config config)))))
     (when (and (not fresh?) config)
       (update-edn! ctx "dhf/harness/config.edn" merge config))
     (when model-spec (write-files! d {"dhf/spec/model.edn" (pr-edn (model model-spec))}))
     (write-files! d (merge features extra))
     ctx)))

(defn rdm
  "Runs the code under test in dir (the project's root unless given): the
  context with :exit, :out and :err, the run added to :runs."
  ([ctx args] (rdm ctx (:dir ctx) args))
  ([ctx dir args]
   (let [ctx (cond-> ctx
               (and (:dir ctx) (nil? (:spec-before ctx)) (fs/directory? (fs/path (:dir ctx) "dhf/spec")))
               (assoc :spec-before (into {} (for [f (fs/glob (fs/path (:dir ctx) "dhf/spec") "**") :when (fs/regular-file? f)]
                                              [(str (fs/relativize (:dir ctx) f)) (slurp (fs/file f))]))))
         start (System/nanoTime)
         r (apply sh dir (assoc (:env ctx) ::base (:base ctx)) (concat (rdm-command) args))
         r (assoc r :seconds (/ (- (System/nanoTime) start) 1e9))]
     (-> ctx (merge (select-keys r [:exit :out :err :seconds])) (update :runs (fnil conj []) r)))))

(defn spec-snapshot
  "Every file of the project's spec, with its text."
  [ctx]
  (let [d (fs/path (guard! (:dir ctx)) "dhf/spec")]
    (into {} (for [f (fs/glob d "**") :when (fs/regular-file? f)]
               [(str (fs/relativize (:dir ctx) f)) (slurp (fs/file f))]))))

(def placeholders
  "The upper-case words a step's command line uses for paths the scenario makes."
  #{"ROOM" "ROOM2" "DIR" "OUT" "OUT2" "R" "R1" "W1" "T"})

(defn- words
  "A command line's words; a quoted part is one word."
  [line]
  (map (fn [[_ a b c]] (or a b c)) (re-seq #"'([^']*)'|\"([^\"]*)\"|(\S+)" line)))

(defn resolve-placeholders
  "The words with each placeholder named in :ph replaced by its path, and
  each new one given a path in the scenario's directory that does not exist."
  [ctx ws]
  (reduce (fn [[ctx out] w]
            (if (or (placeholders w) (get-in ctx [:ph w]))
              (let [ctx (base! ctx)
                    ctx (if (get-in ctx [:ph w]) ctx (assoc-in ctx [:ph w] (path-in ctx w)))]
                [ctx (conj out (get-in ctx [:ph w]))])
              [ctx (conj out w)]))
          [ctx []] ws))

(defn rdm-line
  "Runs a command line as a step writes it, \"rdm-spec check --sources DIR\"."
  ([ctx line] (rdm-line ctx (:dir ctx) line))
  ([ctx dir line]
   (let [ws (words line)
         ws (if (= "rdm-spec" (first ws)) (rest ws) ws)
         [ctx args] (resolve-placeholders ctx ws)]
     (rdm ctx dir args))))

(defn init-project!
  "A project set up by rdm-spec: rdm-spec init, its hook turned off."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [ctx (base! ctx)
          r (rdm ctx (:base ctx) ["init" "project"])]
      (when-not (= 0 (:exit r)) (fail! (str "init failed: " (:err r))))
      (let [d (path-in ctx "project")]
        (git d "config" "core.hooksPath" "/dev/null")
        (assoc ctx :dir d)))))

(defn views!
  "Generates the project's views; the context as it was."
  [ctx]
  (let [r (rdm ctx ["views"])]
    (when-not (= 0 (:exit r)) (fail! (str "views failed: " (:err r))))
    ctx))

(defn tag-cut!
  "Commits the project and tags the commit."
  [ctx tag]
  (commit! (:dir ctx) (str "cut " tag))
  (git (:dir ctx) "tag" "-f" tag)
  ctx)

(defn cycle!
  "Writes dhf/cycles/<n>/cycle.edn."
  [ctx n m]
  (write-files! (guard! (:dir ctx)) {(str "dhf/cycles/" n "/cycle.edn") (pr-edn m)})
  ctx)

(defn wiki-page!
  "A wiki page, listed in the index."
  [ctx path text]
  (let [ctx (project! ctx)
        idx (fs/file (:dir ctx) "dhf/wiki/index.md")
        rel (subs path (count "dhf/wiki/"))]
    (write-files! (:dir ctx) {path text})
    (spit idx (str (slurp idx) "- [" (fs/file-name path) "](" rel ")\n"))
    ctx))

(defn regulated!
  "A regulated project: the clause list dhf/clauses/std.txt (opts :clauses)
  and the documents under dhf/documents (opts :docs), each with frontmatter.
  opts :opts goes to project!."
  ([ctx] (regulated! ctx {}))
  ([ctx {:keys [clauses docs opts]}]
   (let [ctx (project! ctx (update opts :config merge {:regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"}}))]
     (write-files! (:dir ctx) (merge {"dhf/clauses/std.txt" (or clauses "STD:5.1 Development plan\n")}
                                     (or docs {"dhf/documents/plan.md"
                                               (doc-text {:id "PLAN-001" :revision "1" :title "Development plan"}
                                                         "# Plan\n\nWe plan [[STD:5.1]].\n")})))
     ctx)))

(defn write-result!
  "An Allure result in dir for the scenario id: {:id :status :parameters
  :labels :name}; its file's name."
  [dir {:keys [id status parameters labels name]}]
  (let [uuid (str (java.util.UUID/randomUUID))
        f (str uuid "-result.json")]
    (fs/create-dirs dir)
    (spit (fs/file dir f)
          (json/generate-string (cond-> {"uuid" uuid "name" (or name id) "status" (or status "passed") "stage" "finished"
                                         "labels" (into [{"name" "spec" "value" id}] labels)}
                                  parameters (assoc "parameters" parameters))))
    f))

(defn pass-all!
  "A passing result in the project's results directory for each id."
  [ctx ids]
  (doseq [id ids] (write-result! (fs/path (guard! (:dir ctx)) "dhf/allure-results") {:id id}))
  ctx)

(defn copy-of-rdm-spec!
  "A copy of rdm-spec's own project, as a clean room holds it: the paths its
  configuration keeps, never the denied ones, and no oracle."
  [ctx]
  (let [ctx (base! ctx)
        d (path-in ctx "project")
        cfg (edn/read-string (slurp (fs/file room-root "dhf/harness/config.edn")))]
    (fs/create-dirs d)
    (doseq [k (get-in cfg [:clean-room :keep])
            :let [src (fs/path room-root k)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/path d k))
        (do (fs/create-dirs (fs/parent (fs/path d k))) (fs/copy src (fs/path d k)))))
    (write-files! d {"dhf/harness/config.edn" (pr-edn (dissoc cfg :plugins))})
    (fs/delete-tree (fs/path d (:results-dir cfg)))
    (assoc ctx :dir d)))

;; ---------------------------------------------------------------------------
;; Reading what a command did

(defn err-lines [ctx] (vec (remove str/blank? (str/split-lines (str (:err ctx))))))

(defn edn-out [ctx]
  (try (edn/read-string (:out ctx))
       (catch Exception e (fail! (str "standard output is not EDN: " (ex-message e) ": " (:out ctx) (:err ctx))))))

(defn check-exit [ctx code]
  (when-not (= code (:exit ctx))
    (fail! (str "exited " (:exit ctx) " where " code " was expected\nout: " (:out ctx) "\nerr: " (:err ctx))))
  ctx)

(defn err-line-with
  "Asserts one line of the error stream holds every part."
  [ctx & parts]
  (when-not (some (fn [l] (every? #(str/includes? l (str %)) parts)) (err-lines ctx))
    (fail! (str "no line of the error stream holds " (pr-str parts) ":\n" (:err ctx))))
  ctx)

(defn err-has [ctx s]
  (when-not (str/includes? (str (:err ctx)) (str s))
    (fail! (str "the error stream does not name " s ":\n" (:err ctx))))
  ctx)

;; ---------------------------------------------------------------------------
;; Shared steps

;; :exit :out :err :runs
(defstep #"\"(rdm-spec [^\"]*)\" is run( twice| with no --results| with that results directory as DIR| with that directory as DIR| and passes)?"
  (fn [ctx line how]
    (let [ctx (project! ctx)]
      (if (= how " twice")
        (-> ctx (rdm-line line) (rdm-line line))
        (rdm-line ctx line)))))

(defstep #"it exits (\d)"
  (fn [ctx n] (check-exit ctx (parse-long n))))

(defstep #"it passes"
  (fn [ctx] (check-exit ctx 0)))

;; reads :names
(defstep #"(?:it|the check|the citation) fails(?: rule (\S+))? and names .+"
  (fn [ctx rule]
    (check-exit ctx 1)
    (doseq [n (cond->> (:names ctx) rule (cons rule))]
      (err-has ctx n))
    ctx))

(defn match
  "The bindings under which got is want, an upper-case symbol in want (M12,
  T) standing for any value, bound to it; nil when it is not."
  ([want got] (match want got {}))
  ([want got bs]
   (cond
     (nil? bs) nil
     (and (symbol? want) (re-matches #"[A-Z][A-Z0-9]*" (name want)))
     (if (and (contains? bs want) (not= got (bs want))) nil (assoc bs want got))
     (and (map? want) (map? got))
     (when (= (set (keys want)) (set (keys got)))
       (reduce (fn [b k] (match (get want k) (get got k) b)) bs (keys want)))
     (and (sequential? want) (sequential? got))
     (when (= (count want) (count got)) (reduce (fn [b [w g]] (match w g b)) bs (map vector want got)))
     :else (when (= want got) bs))))

;; :bindings, each upper-case symbol of the expected EDN with the value it stood for
(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (let [want (edn/read-string doc) got (edn-out ctx)
          bs (match want got)]
      (when-not bs (fail! (str "standard output is\n" (pr-edn got) "not\n" (pr-edn want))))
      (update ctx :bindings merge bs))))

;; reads :spec-before, which k/rdm keeps
(defstep #"no file in the spec changes"
  (fn [ctx]
    (when-not (#{0 1} (:exit ctx)) (fail! (str "the command did not run: it exited " (:exit ctx) "\n" (:err ctx))))
    (when-not (:spec-before ctx) (fail! "nothing kept the spec before the command"))
    (let [now (spec-snapshot ctx)]
      (when-not (= (:spec-before ctx) now)
        (fail! (str "the spec changed: " (pr-str (remove #(= (get now %) (get (:spec-before ctx) %))
                                                         (distinct (concat (keys now) (keys (:spec-before ctx)))))))))
      ctx)))

(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx] (copy-of-rdm-spec! ctx)))

(defstep #"a project set up by rdm-spec"
  (fn [ctx] (init-project! ctx)))

(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [ctx (project! ctx {:model-spec {:contexts [["shop" ["till"]]] :kernel ["core"]}
                             :features {"dhf/spec/shop/till.feature"
                                        (feature-text {:name "Till" :tags ["UN-001"] :scenarios [(sc 2 "Takes a payment")]})}
                             :extra {"dhf/spec/ids.edn" "{:issued 2}\n"}})]
      (views! ctx)
      (update-edn! ctx "dhf/spec/model.edn" assoc-in [:system :description] "Another demo")
      (names+ ctx "shop-components" "stale"))))

(defstep #"a wiki page whose footnote cites \"([^\"]+)\""
  (fn [ctx c]
    (-> (wiki-page! ctx "dhf/wiki/pages/footnoted.md" (str "# Footnoted\n\nA claim.[^1]\n\n[^1]: `" (real-pin ctx c) "`, \"A\".\n"))
        (names+ "dhf/wiki/pages/footnoted.md"))))

(defstep #"the configured stack words include \"([^\"]+)\""
  (fn [ctx w] (project! ctx {:config {:stack-words [w]}})))

(declare verifiable!)

;; :dir, :ph "ROOM": a project verify passes at spec-3 (k/verifiable!)
(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx] (verifiable! ctx)))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature

(def tasks
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist" "status"
   "trace" "release" "score" "scoreboard" "oracle" "plugin" "publish" "audit" "observe"])

(defstep #"its standard output, read as EDN, is \{:help \[\{:task T :usage U :does D\} \.\.\.\]\}, one map per task in the order below, T the task's name as a string"
  (fn [ctx]
    (check-exit ctx 0)
    (let [h (:help (edn-out ctx))]
      (assert (vector? h) (:out ctx))
      (assert (every? #(= #{:task :usage :does} (set (keys %))) h) (:out ctx))
      (assert (= tasks (map :task h)) (pr-str (map :task h)))
      ctx)))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [t (first (filter #(= task (:task %)) (:help (edn-out ctx))))]
      (assert t (str "no task " task))
      (assert (= usage (:usage t)) (pr-str (:usage t)))
      (assert (= does (:does t)) (pr-str (:does t)))
      ctx)))

(defn- results-in! [ctx dir]
  (write-result! dir {:id "spec-0001"})
  ctx)

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (project! ctx {:config {:results-dir "dhf/allure-results"}})]
      (results-in! ctx (fs/path (:dir ctx) "dhf/allure-results"))
      (results-in! ctx (fs/path (:dir ctx) "other")))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      ctx
      (let [ctx (placeholder! ctx v)]
        (results-in! ctx (get-in ctx [:ph v]))
        (assoc-in ctx [:env "RESULTS_DIR"] (get-in ctx [:ph v]))))))

(defstep #"it reads (\S+)"
  (fn [ctx dir]
    (check-exit ctx 0)
    (let [want (get-in ctx [:ph dir] dir)
          got (get-in (edn-out ctx) [:status :results])]
      (assert (= want got) (str "it read " (pr-str got) ", not " want))
      ctx)))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [ctx (project! ctx {:model-spec {:contexts [["shop" ["till"]]] :kernel ["core"]}
                             :features {"dhf/spec/shop/till.feature"
                                        (feature-text {:name "Till" :tags ["UN-001"]
                                                       :scenarios [(assoc (sc 2 "No level") :tags ["spec-0002"])]})}
                             :extra {"dhf/spec/ids.edn" "{:issued 2}\n"}})]
      (views! ctx)
      (update-edn! ctx "dhf/spec/model.edn" assoc-in [:system :description] "Another demo"))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (check-exit ctx 1)
    (err-line-with ctx "spec-0002" "e2e" "contract")
    (err-line-with ctx "shop-components" "stale")))

(defstep #"a spec that passes every check"
  (fn [ctx] (project! ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (project! ctx {:features {core-feature (feature-text {:name "Core" :tags ["UN-001"]
                                                          :scenarios [(assoc (sc 1 "No level") :tags ["spec-0001"])]})}})))

(def finding-re #"^(?:\S+?(?::\d+)?: )?[a-z][a-z0-9-]*: .+$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [ls (remove #(str/includes? % "warning:") (err-lines ctx))]
      (assert (seq ls) (:err ctx))
      (doseq [l ls] (assert (re-matches finding-re l) (str "not a finding line: " l)))
      (err-line-with ctx "dhf/spec/kernel/core.feature:" "spec-0001"))))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [forms (edn/read-string (str "[" (:out ctx) "]"))]
      (assert (= 1 (count forms)) (:out ctx))
      (assert (map? (first forms)) (:out ctx))
      ctx)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (check-exit ctx 2) (err-line-with ctx "cannot-run:" "dhf/allure-results")))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (check-exit ctx 2)
    (apply err-line-with ctx "frobnicate" tasks)))

;; ---------------------------------------------------------------------------
;; A project may be one folder of a larger repository

(defn- wallet-feature [title]
  (feature-text {:name "Till" :tags ["UN-001"] :scenarios [(sc 2 title)]}))

(defn wallet!
  "A repository whose project is its folder wallet/: spec-2, then spec-3
  changing shop/till's scenarios; decoys at the root a project would use."
  [ctx]
  (let [ctx (base! ctx)
        repo (path-in ctx "repo")
        d (str (fs/path repo "wallet"))
        ctx (assoc ctx :repo repo :dir d)]
    (git-init! repo)
    (write-files! d (assoc (default-files)
                           "dhf/harness/config.edn" (pr-edn (merge default-config
                                                                   {:verify [{:kind :tests :command ["bb" "-cp" (str (fs/path room-root "src")) "-m" "rdm-spec.kernel.command" "run"]}]}))
                           "dhf/spec/model.edn" (pr-edn (model {:contexts [["shop" ["till"]]] :kernel ["core"]}))
                           "dhf/spec/shop/till.feature" (wallet-feature "Takes a payment")
                           "dhf/spec/ids.edn" "{:issued 2}\n"
                           "src/shop/till.clj" "(ns shop.till)\n"
                           "test/steps/all.clj" "(require '[rdm-spec.kernel.runner :refer [defstep]])\n(defstep #\".*\" (fn [ctx] ctx))\n"))
    (write-files! repo {"dhf/harness/config.edn" "{:spec-dir \"nowhere\" :broken\n"
                        "dhf/spec/model.edn" "{:not a model\n"
                        "README.md" "# The monorepo\n"})
    (commit! repo "spec-2")
    (git repo "tag" "spec-2")
    (write-files! d {"dhf/spec/shop/till.feature" (wallet-feature "Takes a payment by card")})
    (commit! repo "spec-3")
    (git repo "tag" "spec-3")
    (write-files! d {"dhf/cycles/03/cycle.edn" (pr-edn (merge {:cut "spec-3" :scope :project} roles))})
    ctx))

(defstep #"a repository whose project is its folder wallet/, holding wallet/dhf/harness/config.edn, whose commits tagged spec-2 and spec-3 change one component's scenarios, and whose dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx] (wallet! ctx)))

(defstep #"files outside wallet/ at the same paths a project at the root would use: dhf/harness/config.edn and dhf/spec/model.edn"
  (fn [ctx]
    (assert (fs/exists? (fs/path (:repo ctx) "dhf/harness/config.edn")))
    (assert (fs/exists? (fs/path (:repo ctx) "dhf/spec/model.edn")))
    ctx))

(defn- room-from-cut!
  "ROOM: the implementation wallet/ committed at spec-3, as a builder leaves it."
  [ctx]
  (let [ctx (placeholder! ctx "ROOM")
        room (get-in ctx [:ph "ROOM"])]
    (doseq [p ["src" "test"]]
      (fs/copy-tree (fs/path (:dir ctx) p) (fs/path room p)))
    ctx))

(defstep #"\"(rdm-spec [^\"]+)\" is run in wallet/"
  (fn [ctx line]
    (let [ctx (if (str/includes? line "verify") (room-from-cut! ctx) ctx)
          before (git (:repo ctx) "status" "--porcelain" "--untracked-files=all" "--" ":!wallet")]
      (-> (rdm-line ctx line) (assoc ::line line ::outside before)))))

(defn- tree [d]
  (->> (fs/glob d "**" {:hidden true})
       (filter #(or (fs/regular-file? % {:nofollow-links true}) (fs/sym-link? %)))
       (map #(str (fs/relativize d %)))
       (remove #(str/starts-with? % ".git/"))
       sort vec))

(defn- committed-at [repo rev prefix]
  (->> (str/split-lines (git repo "ls-tree" "-r" "--name-only" rev "--" prefix))
       (map #(subs % (count prefix)))
       sort vec))

(defstep #"it exits as it would for the same project at a repository's root, and (.+)"
  (fn [ctx reads]
    (check-exit ctx 0)
    (let [line (::line ctx)]
      (cond
        (str/includes? line "fence")
        (let [want (vec (remove #(or (str/starts-with? % "src/") (str/starts-with? % "test/") (str/starts-with? % "dhf/cycles/"))
                                (committed-at (:repo ctx) "spec-3" "wallet/")))]
          (assert (= want (tree (get-in ctx [:ph "ROOM"]))) (pr-str (tree (get-in ctx [:ph "ROOM"])))))
        (str/includes? line "verify")
        (let [co (fs/path (:dir ctx) "dhf/cycles/03/build")]
          (assert (fs/exists? (fs/path co "dhf/harness/config.edn")) (pr-str (tree co)))
          (assert (str/includes? (slurp (fs/file co "dhf/spec/shop/till.feature")) "by card"))
          (assert (not (fs/exists? (fs/path co "wallet")))))
        (str/includes? line "checklist")
        (let [cl (json/parse-string (slurp (fs/file (:dir ctx) "dhf/cycles/03/checklist.json")))]
          (assert (= ["spec-0001" "spec-0002"] (map #(get % "id") (get cl "items"))) (pr-str cl)))
        (str/includes? line "scopes")
        (assert (= [{:component :shop/till}] (get-in (edn-out ctx) [:scopes :scopes])) (:out ctx))
        :else (fail! (str "no such task: " line " (" reads ")")))
      ctx)))

(defstep #"it reads nothing outside wallet/"
  (fn [ctx]
    (assert (= (::outside ctx) (git (:repo ctx) "status" "--porcelain" "--untracked-files=all" "--" ":!wallet"))
            "the task changed the repository outside wallet/")
    ctx))

(defstep #"a project in the folder wallet/ whose working tree has an uncommitted change to a feature file and an untracked file, and whose HEAD is not the commit tagged spec-3"
  (fn [ctx]
    (let [ctx (wallet! ctx)]
      (write-files! (:dir ctx) {"dhf/spec/shop/till.feature" (wallet-feature "Changed, not committed")
                                "notes.md" "untracked\n"})
      (write-files! (:repo ctx) {"README.md" "# Later\n"})
      (git (:repo ctx) "add" "README.md")
      (git (:repo ctx) "commit" "-q" "--no-verify" "-m" "after the cut")
      ctx)))

(defstep #"ROOM holds the feature file as committed at spec-3, and neither the change nor the untracked file"
  (fn [ctx]
    (check-exit ctx 0)
    (let [room (get-in ctx [:ph "ROOM"])]
      (assert (str/includes? (slurp (fs/file room "dhf/spec/shop/till.feature")) "by card"))
      (assert (not (fs/exists? (fs/path room "notes.md"))))
      ctx)))

;; ---------------------------------------------------------------------------
;; Shared by the runner's and status's features

(def catch-all-steps
  "A step definition file whose one step matches every step and passes."
  "(require '[rdm-spec.kernel.runner :refer [defstep]])\n(defstep #\".*\" (fn [ctx] ctx))\n")

(def outline
  {:tags ["spec-0001" "e2e"] :title "An outline" :outline true
   :steps ["Given the value <n>" "Then it holds"]
   :examples [["n"] ["1"] ["2"] ["3"]]})

;; :dir; the outline is spec-0001 in dhf/spec/kernel/core.feature, its rows n = 1, 2 and 3
(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (project! ctx {:features {core-feature (feature-text {:name "Core" :tags ["UN-001"] :scenarios [outline]})}
                   :extra {"test/steps/all.clj" catch-all-steps}})))

(defn room!
  "ROOM: a builder's room holding the project's implementation as it stands."
  [ctx]
  (let [ctx (placeholder! ctx "ROOM")
        room (get-in ctx [:ph "ROOM"])
        cfg (edn/read-string (slurp (fs/file (:dir ctx) "dhf/harness/config.edn")))]
    (doseq [p (:implementation cfg)
            :let [src (fs/path (:dir ctx) p)]
            :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/path room p) {:replace-existing true})
        (do (fs/create-dirs (fs/parent (fs/path room p))) (fs/copy src (fs/path room p) {:replace-existing true}))))
    ctx))

(defn tests-command
  "The tests of a verifiable project: rdm-spec run, by path."
  []
  (conj (rdm-command) "run"))

(defn verifiable!
  "A project verify passes: its implementation test/ holds step definitions
  that pass every step, its :tests check runs them with rdm-spec run, cycle
  03 names the cut spec-3, and ROOM holds the implementation. opts go to
  project!; config is merged."
  ([ctx] (verifiable! ctx {}))
  ([ctx opts]
   (let [ctx (project! ctx (-> opts
                               (update :config #(merge {:implementation ["test"]
                                                        :verify [{:kind :tests :command (tests-command)}]} %))
                               (update :extra merge {"test/steps/all.clj" catch-all-steps})))]
     (-> ctx
         (cycle! "03" (merge {:cut "spec-3" :scope :project} roles))
         (tag-cut! "spec-3")
         room!))))

;; :dir, :ph "ROOM", :names (the unstamped scenario's FILE:LINE)
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (-> (verifiable! ctx {:features {core-feature (str (feature-text {:name "Core" :tags ["UN-001"] :scenarios [(sc 1 "A scenario")]})
                                                       "\n  @e2e\n  Scenario: Unstamped\n    Given a step\n")}})
        (names+ (str core-feature ":11")))))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx] (check-exit ctx 1) (apply err-line-with ctx (:names ctx))))
