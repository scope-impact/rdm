(ns steps.kernel-command
  "The kernel's step definitions: the composition root's scenarios
  (dhf/spec/kernel/command.feature) and every step text the features of more
  than one context use, with the helpers they share.

  CONTEXT KEYS, the kernel's (another step file reads only these):
    :base  the scenario's own directory; every fixture lives under it
    :dir   the project the scenario's commands run in (its root)
    :ph    {\"ROOM\" path ...}: what each upper-case placeholder of a step names
    :env   environment variables a step set for the commands (a nil value unsets)
    :exit :out :err   the last command's exit, standard output and error stream
    :runs  every command run by a step that runs one more than once, in order
    :names strings a Then that says the command \"names\" something must find"
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core]
            [clojure.edn :as edn]
            [clojure.pprint]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep defer! attach! fail!]]))

;; ---------------------------------------------------------------------------
;; Helpers every step file may use

(def room
  "The root the tests run in: never a scenario's project."
  (str (fs/absolutize (System/getProperty "user.dir"))))

(def tmp-root
  "The temporary directory: TMPDIR when it is set, else the runtime's."
  (str (fs/canonicalize (let [t (System/getenv "TMPDIR")] (if (and t (fs/directory? t)) t (System/getProperty "java.io.tmpdir"))))))

(defn guard!
  "Every fixture path is under the temporary directory, never the room."
  [dir]
  (when (or (nil? dir) (not (str/starts-with? (str (fs/canonicalize (fs/absolutize dir) {:nofollow-links true})) tmp-root)))
    (throw (ex-info (str "a fixture write outside the temporary directory: " (pr-str dir)) {})))
  dir)

(defn base!
  "The scenario's own directory, made once and removed when it ends."
  [ctx]
  (if (:base ctx)
    ctx
    (let [d (str (fs/create-temp-dir {:dir tmp-root :prefix "rdm-spec-fixture-"}))]
      (defer! #(fs/delete-tree d))
      (assoc ctx :base d))))

(defn path-in
  "A new path under the scenario's directory."
  [ctx name]
  (str (fs/path (:base ctx) name)))

(defn write-files!
  "Writes {relative-path text} under dir, a fixture directory."
  [dir files]
  (guard! dir)
  (doseq [[path text] files]
    (let [f (fs/file dir path)]
      (fs/create-dirs (fs/parent f))
      (spit f text)))
  dir)

(def git-identity
  {"GIT_AUTHOR_NAME" "Fixture" "GIT_AUTHOR_EMAIL" "fixture@example.org"
   "GIT_COMMITTER_NAME" "Fixture" "GIT_COMMITTER_EMAIL" "fixture@example.org"
   "GIT_CONFIG_NOSYSTEM" "1" "GIT_TERMINAL_PROMPT" "0"})

(defn sh
  "Runs a command in dir with the tests' environment (minus RESULTS_DIR) plus env."
  [dir env & args]
  (guard! dir)
  (let [e (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v))))
                     (dissoc (into {} (System/getenv)) "RESULTS_DIR")
                     (merge git-identity
                            {"GIT_CEILING_DIRECTORIES" (str (fs/parent (fs/absolutize dir)))}
                            env))
        proc (p/process (mapv str args) {:dir (str dir) :env e :out :string :err :string :in ""})
        r (deref proc 180000 ::timeout)]
    (if (= ::timeout r)
      (do (p/destroy-tree proc) (fail! (str "timed out: " (str/join " " args))))
      (select-keys r [:exit :out :err]))))

(defn git
  "Runs git in dir, hooks off, fenced from any repository above it."
  [dir & args]
  (let [r (apply sh dir {} "git" "-c" "core.hooksPath=/dev/null" "-c" "init.defaultBranch=main"
                 "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false" args)]
    (when-not (= 0 (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed: " (:err r)) r)))
    (str/trim (:out r))))

(defn commit!
  "Commits everything in dir; returns the commit."
  [dir message]
  (git dir "add" "-A")
  (git dir "commit" "--no-verify" "--allow-empty" "-q" "-m" message)
  (git dir "rev-parse" "HEAD"))

(defn git-init! [dir]
  (fs/create-dirs dir)
  (git dir "init" "-q")
  (git dir "config" "core.hooksPath" "/dev/null")
  dir)

(defn rdm-command [] ["bb" "-cp" (str room "/src") "-m" "rdm-spec.kernel.command"])

(defn subst
  "Replaces each upper-case placeholder word the context names."
  [ctx s]
  (reduce-kv (fn [s k v] (str/replace s (re-pattern (str "\\b" k "\\b")) (str v))) s (or (:ph ctx) {})))

(defn split-words
  "Splits a command line into words, single quotes grouping."
  [s]
  (map #(str/replace % #"^'|'$" "") (re-seq #"'[^']*'|\S+" s)))

(defn rdm
  "Runs rdm-spec by path in the context's project (or dir). Returns the
  context with :exit :out :err."
  ([ctx args] (rdm ctx (:dir ctx) args))
  ([ctx dir args]
   (let [r (apply sh dir (:env ctx) (concat (rdm-command) args))]
     (attach! (str "rdm-spec " (str/join " " args))
              (str "exit " (:exit r) "\n--- stdout\n" (:out r) "\n--- stderr\n" (:err r)))
     (merge ctx r))))

(declare placeholder!)

(defn rdm-line
  "Runs the words of a command line `rdm-spec ...`, placeholders replaced: a
  placeholder no Given named (ROOM, OUT, OUT2, DIR) is a new path that does
  not exist yet. Sets :last-args."
  [ctx line]
  (let [words (split-words (str/replace-first line #"^rdm-spec\s*" ""))
        ctx (reduce (fn [c w] (if (and (#{"ROOM" "OUT" "OUT2" "DIR"} w) (not (get-in c [:ph w])))
                                (placeholder! c w false) c))
                    ctx words)
        args (map #(subst ctx %) words)]
    (assoc (rdm ctx args) :last-args (vec args))))

(defn edn-out [ctx]
  (try (edn/read-string (:out ctx))
       (catch Exception e (fail! (str "standard output is not EDN: " (:out ctx))))))

(defn err-lines [ctx] (remove str/blank? (str/split-lines (str (:err ctx)))))

(defn check-exit [ctx n]
  (when-not (= n (:exit ctx))
    (fail! (str "expected exit " n ", got " (:exit ctx) "; stderr: " (:err ctx) " stdout: " (:out ctx))))
  ctx)

(defn err-has
  "Asserts the error stream holds each string."
  [ctx & ss]
  (doseq [s ss]
    (when-not (str/includes? (str (:err ctx)) (str s))
      (fail! (str "the error stream does not name " s ": " (:err ctx)))))
  ctx)

(defn err-line-with
  "Asserts one line of the error stream holds every string."
  [ctx & ss]
  (when-not (some (fn [l] (every? #(str/includes? l (str %)) ss)) (err-lines ctx))
    (fail! (str "no line of the error stream holds all of " (pr-str ss) ": " (:err ctx))))
  ctx)

(defn finding-rules
  "The RULE of every finding line (not warnings, not events)."
  [ctx]
  (keep (fn [l] (when-not (or (str/starts-with? l "event: ") (re-find #"(^|: )warning: " l))
                  (second (re-find #"^(?:[^\s:]+(?::\d+)?: )?([a-z0-9][a-z0-9-]*):" l))))
        (err-lines ctx)))

(defn pr-edn [x] (with-out-str (clojure.pprint/pprint x)))

;; ---------------------------------------------------------------------------
;; Projects

(def base-config
  {:id-prefix "spec"
   :spec-dir "dhf/spec" :ledger "dhf/spec/ids.edn" :model "dhf/spec/model.edn"
   :events "dhf/spec/events.edn" :needs "dhf/spec/needs.edn" :views-dir "dhf/views"
   :raw-dir "dhf/raw" :wiki-dir "dhf/wiki" :stack-words []
   :steps-dir "test/steps"
   :implementation ["src" "test" "test.clj"]
   :code-layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
                 :component ["src/rdm_spec/{context}/{component}.clj"
                             "test/rdm_spec/{context}/{component}_test.clj"
                             "test/steps/{context}_{component}.clj"]}
   :clean-room {:deny {:implementation true :measurements ["dhf/cycles"]
                       :generated ["site" "site-src" "dhf/allure-results"]}
                :keep [".gitignore" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]}
   :verify [{:kind :tests :command ["bb" "test.clj"]}]
   :results-dir "dhf/allure-results"
   :cycles-dir "dhf/cycles"
   :site {:dir "site" :source "site-src" :builder ["mkdocs" "build" "--strict" "-f" "site-src/mkdocs.yml"]}
   :c4 {:view-element-limit 20 :description-max-chars 45}})

(defn model
  "A model: the system, one person, and the given contexts ([[ctx [comp ...]] ...])
  and kernel components, each component owning dhf/spec/<ctx>/<comp>.feature,
  each context with its component view."
  [{:keys [contexts kernel views] :or {kernel []}}]
  {:system {:id :demo :name "demo" :description "A demo system"}
   :people [{:id :user :name "User" :description "Uses the demo" :actor #{:human}}]
   :external []
   :containers (if (seq contexts)
                 [{:id :app :name "app" :kind :runs :technology "a program" :description "The demo program"
                   :contexts (vec (for [[c comps] contexts]
                                    {:id (keyword c) :name c :description (str "The " c " context")
                                     :components (vec (for [k comps]
                                                        {:id (keyword c k) :name (str/capitalize k)
                                                         :description (str "The " k " component") :spec (str c "/" k)}))}))}]
                 [])
   :kernel (vec (for [k kernel] {:id (keyword "kernel" k) :name (str/capitalize k)
                                 :description (str "The kernel's " k) :spec (str "kernel/" k)}))
   :relationships [{:from :user :to :demo :description "Uses"}]
   :views (vec (concat (or views [])
                       (for [[c comps] contexts :when (seq comps)]
                         {:key (str c "-components") :level :component :context (keyword c)
                          :description (str "The " c " context")})))})

(defn scenario-text
  "A scenario: {:tags [..] :title :steps [..] :keyword :examples [[h ...] [v ...] ...]}."
  [{:keys [tags title steps keyword examples]}]
  (str (when (seq tags) (str "  " (str/join " " (map #(str "@" %) tags)) "\n"))
       "  " (or keyword (if examples "Scenario Outline" "Scenario")) ": " title "\n"
       (str/join (map #(str "    " % "\n") steps))
       (when examples
         (str "\n    Examples:\n"
              (str/join (map #(str "      | " (str/join " | " %) " |\n") examples))))
       "\n"))

(defn feature-text
  "A feature file: {:name :tags [..] :scenarios [..] :rules [{:name :tags :scenarios}]}."
  [{:keys [name tags scenarios rules]}]
  (str (when (seq tags) (str (str/join " " (map #(str "@" %) tags)) "\n"))
       "Feature: " (or name "Demo") "\n\n"
       (str/join (map scenario-text scenarios))
       (str/join (map (fn [r] (str (when (seq (:tags r)) (str "  " (str/join " " (map #(str "@" %) (:tags r))) "\n"))
                                   "  Rule: " (:name r) "\n\n"
                                   (str/join (map #(str/replace (scenario-text %) #"(?m)^" "  ") (:scenarios r)))))
                      rules))))

(defn sc
  "A stamped scenario at the level e2e, tracing to UN-001 through its feature."
  ([n title] (sc n title ["Given a step"]))
  ([n title steps] {:tags [(format "spec-%04d" n) "e2e"] :title title :steps steps}))

(defn project-files
  "The files of a project: the base configuration and spec, with overrides."
  [{:keys [config model-spec features needs issued sources extra]}]
  (merge
   {"dhf/harness/config.edn" (pr-edn (merge base-config config))
    "dhf/spec/model.edn" (pr-edn (model (or model-spec {:kernel ["core"]})))
    "dhf/spec/events.edn" "{}\n"
    "dhf/spec/needs.edn" (pr-edn (or needs {"UN-001" {:text "A user need" :sources ["demo:README.md@abc1234"]}}))
    "dhf/spec/ids.edn" (str "{:issued " (or issued 100) "}\n")
    "dhf/raw/sources.edn" (pr-edn (or sources {:demo {:repository "https://example.org/demo" :record "demo.md"
                                                      :pins ["abc1234"] :fenced []}
                                               :loyly {:repository "https://github.com/skallinen/loyly" :record "loyly.md"
                                                       :pins ["17d68d2"] :fenced []}}))
    "dhf/raw/demo.md" "# Source: demo\n"
    "dhf/raw/loyly.md" "# Source: loyly\n"
    "dhf/wiki/index.md" "# Wiki\n\n## Pages\n"
    "dhf/spec/kernel/core.feature" (feature-text {:name "Core" :tags ["UN-001"]
                                                  :scenarios [(sc 1 "The core works")]})}
   features
   extra))

(defn project!
  "Makes the scenario's project, a git repository with its hooks off, unless
  an earlier Given made one. opts as project-files, plus :commit."
  ([ctx] (project! ctx {}))
  ([ctx opts]
   (if (:dir ctx)
     ctx
     (let [ctx (base! ctx)
           dir (path-in ctx "project")]
       (git-init! dir)
       (write-files! dir (project-files opts))
       (when (:commit opts) (commit! dir "the project"))
       (assoc ctx :dir dir)))))

(defn update-edn!
  "Rewrites an EDN file of the project through f."
  [ctx path f & args]
  (let [file (fs/file (guard! (:dir ctx)) path)]
    (spit file (pr-edn (apply f (edn/read-string (slurp file)) args)))
    ctx))

(defn views!
  "Generates the project's views with rdm-spec views."
  [ctx]
  (let [r (rdm ctx ["views"])]
    (when-not (= 0 (:exit r)) (throw (ex-info (str "views failed: " (:err r)) r)))
    ctx))

(defn init-project!
  "A project set up by rdm-spec: init in the scenario's directory, hooks off."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [ctx (base! ctx)
          dir (path-in ctx "project")
          r (rdm ctx (:base ctx) ["init" "project"])]
      (when-not (= 0 (:exit r)) (throw (ex-info (str "init failed: " (:err r)) r)))
      (git dir "config" "core.hooksPath" "/dev/null")
      (assoc ctx :dir dir))))

(def kept-paths
  "What a clean room of rdm-spec's own project holds: the kept paths."
  [".gitignore" "AGENTS.md" "CLAUDE.md" "LICENSE.txt" "README.md" "docs"
   "dhf/decisions" "dhf/harness" "dhf/raw" "dhf/spec" "dhf/storm" "dhf/views" "dhf/wiki"])

(defn own-copy!
  "A copy of rdm-spec's own project: the paths its configuration keeps, no
  oracle named, no results directory, a git repository of its own."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [ctx (base! ctx)
          dir (path-in ctx "project")]
      (fs/create-dirs dir)
      (doseq [p kept-paths]
        (let [src (fs/path room p) dst (fs/path dir p)]
          (when (fs/exists? src)
            (fs/create-dirs (fs/parent dst))
            (if (fs/directory? src) (fs/copy-tree src dst) (fs/copy src dst)))))
      (let [cf (fs/file dir "dhf/harness/config.edn")]
        (spit cf (str/replace (slurp cf) #"(?m)^\s*:oracle \{:command \[[^\]]*\]\}\s*$" "")))
      (fs/delete-tree (fs/path dir "dhf/allure-results"))
      (git-init! dir)
      (assoc ctx :dir dir))))

(defn placeholder!
  "Names a new path for an upper-case placeholder (made as a directory when mk?)."
  ([ctx k] (placeholder! ctx k true))
  ([ctx k mk?]
   (let [ctx (base! ctx)]
     (if (get-in ctx [:ph k])
       ctx
       (let [p (path-in ctx (str "ph-" k))]
         (when mk? (fs/create-dirs p))
         (assoc-in ctx [:ph k] p))))))

(defn result-json
  "An Allure result as rdm-spec's runner writes one."
  [{:keys [id status params name]}]
  (cheshire.core/generate-string
   (cond-> {"uuid" (str (random-uuid)) "name" (or name id) "status" (or status "passed") "stage" "finished"
            "labels" (cond-> [] id (conj {"name" "spec" "value" id}))
            "parameters" (vec (for [[k v] params] {"name" k "value" v}))}
     (seq params) (assoc "historyId" (str/join "|" (cons id (map second params)))))))

(defn write-result!
  "Writes one Allure result into dir; returns its file name."
  [dir opts]
  (fs/create-dirs dir)
  (let [n (str (random-uuid) "-result.json")]
    (spit (fs/file dir n) (result-json opts))
    n))

;; ---------------------------------------------------------------------------
;; Shared steps: running the command

;; Sets :exit :out :err (and :dir, through a base project, when no Given made one).
(defstep #"\"(rdm-spec [^\"]+)\" is run"
  (fn [ctx line] (rdm-line (project! ctx) line)))

;; Sets :exit :out :err.
(defstep #"\"(rdm-spec [^\"]+)\" is run with no --results"
  (fn [ctx line] (rdm-line (project! ctx) line)))

;; Sets :runs and :exit :out :err (the last).
(defstep #"\"(rdm-spec [^\"]+)\" is run twice"
  (fn [ctx line]
    (let [ctx (project! ctx)
          a (rdm-line ctx line) b (rdm-line ctx line)]
      (assoc b :runs [(select-keys a [:exit :out :err]) (select-keys b [:exit :out :err])]))))

(defstep #"it exits (\d)"
  (fn [ctx n] (check-exit ctx (parse-long n))))

(defstep #"it passes"
  (fn [ctx] (check-exit ctx 0)))

;; ---------------------------------------------------------------------------
;; Shared steps: projects

;; Sets :base :dir.
(defstep #"a project set up by rdm-spec"
  (fn [ctx] (init-project! ctx)))

;; Sets :base :dir.
(defstep #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx] (own-copy! ctx)))

;; Sets :base :dir; the configured directory holds a passing result for the project's scenario.
(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx]
    (let [ctx (project! ctx)]
      (update-edn! ctx "dhf/harness/config.edn" assoc :results-dir "dhf/allure-results")
      (write-result! (fs/path (guard! (:dir ctx)) "dhf/allure-results") {:id "spec-0001"})
      (write-result! (fs/path (guard! (:dir ctx)) "other") {:id "spec-0001"})
      ctx)))

;; Sets :base :dir :names; the stack words of the configuration include Cobalt.
(defstep #"the configured stack words include \"Cobalt\""
  (fn [ctx]
    (let [ctx (project! ctx)]
      (update-edn! ctx "dhf/harness/config.edn" update :stack-words (fnil conj []) "Cobalt")
      (update ctx :names (fnil conj []) "Cobalt"))))

;; Sets :base :dir; the project's one outline is @spec-0001, rows (a 1) (b 2) (c 3),
;; with step definitions that pass.
(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (project! ctx {:features {"dhf/spec/kernel/core.feature"
                              (feature-text {:name "Core" :tags ["UN-001"]
                                             :scenarios [{:tags ["spec-0001" "e2e"] :title "Rows"
                                                          :steps ["Given the row <name> <n>"]
                                                          :examples [["name" "n"] ["a" "1"] ["b" "2"] ["c" "3"]]}]})}
                   :extra {"test/steps/core.clj"
                           "(require '[rdm-spec.kernel.runner :refer [defstep]])\n(defstep #\"the row (\\w+) (\\d+)\" (fn [ctx a b] ctx))\n"}})))

;; Sets :base :dir.
(defstep #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [ctx (project! ctx {:model-spec {:contexts [["shop" ["till"]]]}
                             :features {"dhf/spec/shop/till.feature"
                                        (feature-text {:name "Till" :tags ["UN-001"] :scenarios [(sc 2 "The till works")]})}})]
      (views! ctx)
      (doseq [ext ["mmd" "svg"]]
        (let [f (fs/file (guard! (:dir ctx)) (str "dhf/views/shop-components." ext))]
          (spit f (str/replace (slurp f) #"model-sha256: [0-9a-f]+" (str "model-sha256: " (apply str (repeat 64 "0")))))))
      (update ctx :names (fnil conj []) "shop-components"))))

;; Sets :spec-before: the spec's files and their bytes, to compare.
(defn spec-snapshot [ctx]
  (into {} (for [f (fs/glob (fs/path (guard! (:dir ctx)) "dhf/spec") "**") :when (fs/regular-file? f)]
             [(str f) (slurp (fs/file f))])))

(defstep #"no file in the spec changes"
  (fn [ctx]
    (when-not (:spec-before ctx) (fail! "no spec was kept aside before the command"))
    (when-not (= (:spec-before ctx) (spec-snapshot ctx))
      (fail! "a file in the spec changed"))
    ctx))

;; Shared by measurement and verification: cycle 03 names the cut spec-3.
;; Sets :base :dir; the project is committed and tagged spec-3 unless it was.
(defn cycle! [ctx cycle edn]
  (write-files! (:dir ctx) {(str "dhf/cycles/" cycle "/cycle.edn") (pr-edn edn)})
  ctx)

(def roles {:builder {:name "builder-a" :actor :agent}
            :grader {:name "grader-b" :actor :agent}
            :spec-owner {:name "Sami" :actor :human}})

(defn tag-cut!
  "Commits whatever the project holds and tags HEAD with the cut, moving the
  tag when an earlier Given set it."
  [ctx cut]
  (when (seq (str/trim (git (:dir ctx) "status" "--porcelain")))
    (commit! (:dir ctx) (str "cut " cut)))
  (git (:dir ctx) "tag" "-f" cut)
  ctx)

(def fixture-test-clj
  "The room's test command: runs the scenarios with rdm-spec run by path, then
  does what test/fixture.edn says to the results it wrote."
  "(require '[babashka.process :as p] '[babashka.fs :as fs] '[cheshire.core :as json] '[clojure.edn :as edn])
(def fx (edn/read-string (slurp \"test/fixture.edn\")))
(def rdir (System/getenv \"RESULTS_DIR\"))
(when-let [f (:record-env fx)] (spit f (str rdir)))
(when-let [f (:ran fx)] (spit f \"ran\"))
(def r (if (:no-results fx) {:exit 0} @(p/process (:run fx) {:inherit true})))
(when (:no-results fx) (println \"no tests\"))
(defn label [j n] (some #(when (= n (get % \"name\")) (get % \"value\")) (get j \"labels\")))
(doseq [f (when rdir (fs/glob rdir \"*-result.json\"))]
  (let [j (json/parse-string (slurp (str f)))
        ops (get (:mutate fx) (label j \"spec\"))]
    (if (some #{[:drop]} ops)
      (fs/delete f)
      (let [j (reduce (fn [j [op a b]]
                        (case op
                          :status (assoc j \"status\" a)
                          :step-name (assoc-in j [\"steps\" a \"name\"] b)
                          :drop-step (update j \"steps\" (comp vec butlast))
                          :label (update j \"labels\" (fn [ls] (mapv #(if (= a (get % \"name\")) (assoc % \"value\" b) %) ls)))
                          :no-links (assoc j \"links\" [])
                          :no-attachments (update j \"steps\" (fn [ss] (mapv #(assoc % \"attachments\" []) ss)))
                          j))
                      j ops)]
        (spit (str f) (json/generate-string j))))))
(System/exit (or (:exit fx) (:exit r)))
")

(defn room!
  "ROOM: an implementation whose test command runs the project's scenarios
  through rdm-spec run, every step passing and attaching a browser-trace.
  fixture is merged into test/fixture.edn."
  ([ctx] (room! ctx {}))
  ([ctx fixture]
   (let [ctx (placeholder! ctx "ROOM")
         r (get-in ctx [:ph "ROOM"])]
     (write-files! r {"test.clj" fixture-test-clj
                      "test/steps/all.clj" "(require '[rdm-spec.kernel.runner :refer [defstep attach!]])\n(defstep #\".*\" (fn [ctx & _] (attach! \"browser-trace\" \"a trace\") ctx))\n"
                      "test/fixture.edn" (pr-str (merge {:run (conj (rdm-command) "run")} fixture))
                      "src/app/core.clj" "(ns app.core)\n"})
     ctx)))

(defn fixture!
  "Merges into the room's test/fixture.edn."
  [ctx m]
  (let [f (fs/file (guard! (get-in ctx [:ph "ROOM"])) "test/fixture.edn")]
    (spit f (pr-str (merge-with (fn [a b] (if (map? a) (merge-with into a b) b)) (edn/read-string (slurp f)) m)))
    ctx))

(defn cycle-ready!
  "Cycle 03 of the cut spec-3, with a room to verify."
  [ctx]
  (let [ctx (room! (project! ctx))]
    (cycle! ctx "03" (merge {:cut "spec-3" :scope :project} roles))
    (tag-cut! ctx "spec-3")))

(defstep #"dhf/cycles/03/cycle.edn names the cut spec-3(?: and the scope :project)?"
  (fn [ctx] (cycle-ready! ctx)))

;; Sets :base :dir; the spec at the cut spec-3 holds a scenario with no id, on line 5 of dhf/spec/kernel/core.feature.
(defstep #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [ctx (project! ctx)]
      (write-files! (:dir ctx) {"dhf/spec/kernel/core.feature"
                                (str "@UN-001\nFeature: Core\n\n  @e2e\n  Scenario: Unstamped\n    Given a step\n")})
      (-> (cycle-ready! ctx) (assoc :names ["dhf/spec/kernel/core.feature:5"])))))

;; ---------------------------------------------------------------------------
;; The command's own scenarios

(def task-order ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify"
                 "checklist" "status" "trace" "release" "score" "scoreboard" "oracle" "publish" "audit" "observe"])

(defstep #"its standard output, read as EDN, is \{:help \[\{:task T :usage U :does D\} \.\.\.\]\}, one map per task in the order below, T the task's name as a string"
  (fn [ctx]
    (check-exit ctx 0)
    (let [h (:help (edn-out ctx))]
      (assert (vector? h))
      (assert (every? #(= #{:task :usage :does} (set (keys %))) h) (pr-str h))
      (assert (= task-order (mapv :task h)) (pr-str (map :task h)))
      (assoc ctx :help h))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [e (first (filter #(= task (:task %)) (:help ctx)))]
      (assert e (str "no task " task))
      (assert (= (str/trim usage) (:usage e)) (pr-str (:usage e)))
      (assert (= (str/trim does) (:does e)) (pr-str (:does e)))
      ctx)))

(defstep #"it reads (.+)"
  (fn [ctx dir]
    (let [want (get-in ctx [:ph dir] dir)
          got (get-in (edn-out ctx) [:status :results])]
      (assert (= want got) (str "read " (pr-str got) ", not " want "; " (:err ctx)))
      ctx)))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [ctx (placeholder! ctx "R1")]
        (write-result! (get-in ctx [:ph "R1"]) {:id "spec-0001"})
        (assoc-in ctx [:env "RESULTS_DIR"] (get-in ctx [:ph "R1"]))))))

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [ctx (project! ctx {:features {"dhf/spec/kernel/core.feature"
                                        (feature-text {:name "Core" :tags ["UN-001"]
                                                       :scenarios [{:tags ["spec-0001"] :title "No level" :steps ["Given a step"]}]})}
                             :model-spec {:kernel ["core"] :views [{:key "context" :level :context :description "The demo"}]}})]
      (views! ctx)
      (update-edn! ctx "dhf/spec/model.edn" assoc-in [:system :description] "A changed system")
      ctx)))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (check-exit ctx 1)
    (err-line-with ctx "spec-0001" "level")
    (err-line-with ctx "context" "stale")))

(defstep #"a spec that passes every check"
  (fn [ctx] (project! ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (project! ctx {:features {"dhf/spec/kernel/core.feature"
                              (feature-text {:name "Core" :tags ["UN-001"]
                                             :scenarios [(sc 1 "Levelled") {:tags ["spec-0002"] :title "No level" :steps ["Given a step"]}]})}})))

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [ls (err-lines ctx)
          findings (remove #(or (str/starts-with? % "event: ") (re-find #"(^|: )warning: " %)) ls)]
      (assert (seq findings) "no finding line")
      (doseq [l findings]
        (assert (re-matches #"(?:[^\s:]+(?::\d+)?: )?[a-z0-9][a-z0-9-]*: .+" l) (str "not a finding line: " l)))
      (assert (some #(re-matches #"dhf/spec/kernel/core\.feature:\d+: [a-z0-9-]+: .*spec-0002.*" %) findings)
              (str "no finding names the scenario's file, line and id: " (:err ctx)))
      ctx)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (java.io.PushbackReader. (java.io.StringReader. (:out ctx)))
          v (edn/read {:eof ::eof} r)
          more (edn/read {:eof ::eof} r)]
      (assert (map? v) (str "not one EDN map: " (:out ctx)))
      (assert (= ::eof more) "more than one form on standard output")
      ctx)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (check-exit ctx 2)
    (err-has ctx "dhf/allure-results")
    (assert (str/blank? (:out ctx)) "a task that cannot run prints nothing on standard output")
    ctx))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (check-exit ctx 2)
    (apply err-line-with ctx "frobnicate" task-order)))

;; ---------------------------------------------------------------------------
;; Shared step: a finding that names what the Givens set in :names.
;; Reads :names; asserts exit 1, the rule (when the step names one) on a
;; finding line, and every name on the error stream.
(defstep #"(?:it|the check|the citation) fails(?: rule ([a-z0-9-]+))? and names (.+)"
  (fn [ctx rule _]
    (check-exit ctx 1)
    (when rule
      (assert (some #{rule} (finding-rules ctx)) (str "no finding of the rule " rule ": " (:err ctx))))
    (when (and rule (seq (:names ctx)))
      (apply err-line-with ctx rule (take 1 (:names ctx))))
    (apply err-has ctx (:names ctx))
    ctx))

(defn names+
  "Adds strings to :names."
  [ctx & ss]
  (update ctx :names (fnil into []) ss))

;; ---------------------------------------------------------------------------
;; Shared steps: placeholders, pins and the wiki

;; Sets :exit :out :err; DIR is what an earlier Given named.
(defstep #"\"(rdm-spec [^\"]+)\" is run with that (?:results )?directory as DIR"
  (fn [ctx line]
    (when-not (get-in ctx [:ph "DIR"]) (fail! "no Given named DIR"))
    (rdm-line (project! ctx) line)))

(defn real-pin
  "A citation with each pin a step wrote replaced by the commit a fixture
  checkout really holds (:pins {written actual})."
  [ctx s]
  (reduce-kv (fn [s k v] (str/replace s k v)) s (or (:pins ctx) {})))

(defn wiki-page!
  "Writes a wiki page and lists it in the index."
  [ctx path text]
  (let [f (fs/file (guard! (:dir ctx)) "dhf/wiki/index.md")
        rel (str/replace-first path #"^dhf/wiki/" "")]
    (write-files! (:dir ctx) {path text})
    (spit f (str (slurp f) "- [" rel "](" rel ")\n"))
    ctx))

;; Sets :base :dir :names (the page and the citation as written to it).
(defstep #"a wiki page whose footnote cites \"(.+)\""
  (fn [ctx citation]
    (let [ctx (project! ctx)
          c (real-pin ctx citation)]
      (-> (wiki-page! ctx "dhf/wiki/pages/footnoted.md"
                      (str "# A page\n\nA claim from a source.[^1]\n\n[^1]: `" c "`, \"A section\".\n"))
          (names+ "dhf/wiki/pages/footnoted.md" c)))))

(defn git-config
  "A git configuration value of the repository at dir, read without overrides."
  [dir k]
  (str/trim (:out (sh dir {} "git" "config" k))))

;; ---------------------------------------------------------------------------
;; The regulated profile, shared by compliance and verification

(defn doc-text
  "A controlled document: frontmatter (id revision title, any of them nil to
  leave it out) and a body."
  [{:keys [id revision title]} body]
  (str "---\n" (when id (str "id: " id "\n")) (when revision (str "revision: " revision "\n"))
       (when title (str "title: " title "\n")) "---\n" body))

(defn regulated!
  "A regulated project: :regulated with the clause list dhf/clauses/std.txt
  (`clauses`, its text) and the documents directory dhf/documents (`docs`,
  {name text}); plan.md names STD:5.1 unless docs say otherwise."
  ([ctx] (regulated! ctx {}))
  ([ctx {:keys [clauses docs opts]}]
   (project! ctx (merge-with merge
                             {:config {:regulated {:clause-lists ["dhf/clauses/std.txt"] :documents "dhf/documents"}
                                       :clean-room {:deny {:implementation true :measurements ["dhf/cycles"]
                                                           :generated ["site" "site-src" "dhf/allure-results"]}
                                                    :keep [".gitignore" "dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views"
                                                           "dhf/decisions" "dhf/clauses" "dhf/documents"]}}
                              :extra (merge {"dhf/clauses/std.txt" (or clauses "STD:5.1 Development plan\n")}
                                            (or docs {"dhf/documents/plan.md"
                                                      (doc-text {:id "PLAN-001" :revision "1" :title "Development plan"}
                                                                "# Development plan\n\nWe plan the work [[STD:5.1]].\n")}))}
                             opts))))

(defn pass-all!
  "A passing result in the configured results directory for every stamped
  scenario of the project's core feature not tagged @wip: ids given."
  [ctx ids]
  (doseq [id ids] (write-result! (fs/path (guard! (:dir ctx)) "dhf/allure-results") {:id id}))
  ctx)

;; ---------------------------------------------------------------------------
;; Shared step: an exact EDN result. A symbol in the expected text (M12) stands
;; for any value, bound in :bindings for a later step.
(defn match-edn
  "Matches got against want, symbols binding; returns bindings or nil."
  [want got bindings]
  (cond
    (and (symbol? want) (not= '... want)) (assoc bindings want got)
    (map? want) (when (and (map? got) (= (set (keys want)) (set (keys got))))
                  (reduce (fn [b k] (if-let [b' (match-edn (get want k) (get got k) b)] b' (reduced nil)))
                          bindings (keys want)))
    (vector? want) (when (and (sequential? got) (= (count want) (count got)))
                     (reduce (fn [b [w g]] (if-let [b' (match-edn w g b)] b' (reduced nil))) bindings (map vector want got)))
    :else (when (= want got) bindings)))

;; Sets :bindings.
(defstep #"standard output, read as EDN, is exactly:"
  (fn [ctx doc]
    (check-exit ctx 0)
    (let [want (edn/read-string doc)
          got (edn-out ctx)
          b (match-edn want got {})]
      (when-not b (fail! (str "standard output differs:\n" (pr-str got) "\nwanted:\n" (pr-str want))))
      (assoc ctx :bindings b))))
