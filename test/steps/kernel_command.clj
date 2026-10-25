(ns steps.kernel-command
  "Step definitions for dhf/spec/kernel/command.feature, and the kernel's
  shared steps: every step text the features of more than one bounded
  context use (the kernel counting as one) is defined here, once, when this
  file loads, with the one meaning the features give it (dhf/spec/README.md,
  \"Step definitions are the scope's own\").

  The shared steps (`owned`):
    \"rdm-spec LINE\" is run                      the command, run as its own process
    \"rdm-spec LINE\" is run with no --results    the same; LINE gives no --results
    \"rdm-spec LINE\" is run with that [results ]directory as DIR
                                                DIR is the directory a Given made
    it exits N / it passes / it exits 2 and names X
    a copy of rdm-spec's own project in which the configured results directory does not exist
    a rendered view whose recorded hash differs from the model's
    a stamped scenario outline with three example rows
    dhf/cycles/03/cycle.edn names the cut spec-3
    the spec at cut spec-3 holds a scenario with no id
    no file in the spec changes
    the configured stack words include \"W\"

  Another step file that still defines one of these texts loses it: when it
  registers, its pattern is narrowed so that it no longer matches the spec's
  step texts an owned pattern matches (`narrow-registry!`), so a step is never
  matched twice. Its other texts keep their meaning.

  The command (helpers other step files use, a contract):
    (run! ctx dir argv)   runs `rdm-spec argv...` in dir, as its own process,
                          the implementation under test on its classpath,
                          with the scenario's environment (`env-of`):
                          {:exit :out :err :args :dir}
    (run-line ctx line)   runs the command line `line` (\"rdm-spec\" may be left
                          out) in the scenario's project (:dir; a fresh copy
                          of the room's allow-list when there is none), its
                          path placeholders filled in (`fill`); ctx with :run
    (split-line line)     the line's words; '...' or \"...\" keep spaces
    (the-run ctx) (exits! ctx n) (stdout-edn run)

  Context keys: :dir the project; :run the last run; :room ROOM;
  :sources-dir what DIR stands for (else :results); :placeholders
  {\"OUT\" path ..} the other path placeholders; :env the environment the
  steps set (a nil value unsets a variable); :spec-before a snapshot of
  dhf/spec; :stack-word, :stale-view, :id, :cycle, :unstamped as the Givens
  below say.

  Fixtures: every temporary directory made through steps.support/tmp-dir in
  this run is removed when the run ends (dhf/spec/README.md, \"A suite
  removes every fixture it makes\")."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures are removed when the run ends

(defonce ^:private fixtures (atom #{}))

(defonce ^:private wrapped (atom nil))

(defn- ensure-tracking!
  "Makes steps.support/tmp-dir record every directory it makes, again
  whenever that file is loaded anew (the runner loads every step file)."
  []
  (when-not (identical? @wrapped @#'s/tmp-dir)
    (let [make @#'s/tmp-dir
          w (fn [& args]
              (let [d (apply make args)]
                (swap! fixtures conj d)
                d))]
      (alter-var-root #'s/tmp-dir (constantly w))
      (reset! wrapped w))))

(defonce ^:private cleanup
  (do (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn []
                                             (doseq [d @fixtures]
                                               (try (fs/delete-tree d) (catch Exception _ nil))))))
      true))

(ensure-tracking!)

(defn tmp-dir
  "A new temporary directory, removed when the run ends."
  []
  (ensure-tracking!)
  (s/tmp-dir))

;; ---------------------------------------------------------------------------
;; The command

(defn split-line
  "The words of a command line; a '...' or \"...\" span is one word."
  [line]
  (vec (for [[_ sq dq w] (re-seq #"'([^']*)'|\"([^\"]*)\"|(\S+)" (str line))]
         (or sq dq w))))

(defn env-of
  "The environment a scenario's commands see: this process's, never its
  RESULTS_DIR, with what the scenario's steps set (:env; nil unsets)."
  [ctx]
  (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v))))
             (dissoc (into {} (System/getenv)) "RESULTS_DIR")
             (or (:env ctx) {})))

(defn run!
  "Runs `rdm-spec argv...` in `dir` as its own process, with the scenario's
  environment: {:exit :out :err :args :dir}."
  [ctx dir argv]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :env (env-of ctx)}
                 s/bb "-cp" (str (fs/file s/room-root "src")) "-m" "rdm-spec.kernel.command" argv)]
    {:exit (:exit r) :out (:out r) :err (:err r) :args (vec argv) :dir (str dir)}))

(def ^:private placeholder #"(ROOM|DIR|OUT\d*|R\d+)(/.*)?")

(defn- new-path
  "A path that does not exist yet, in a new temporary directory."
  [name]
  (str (fs/file (tmp-dir) (str/lower-case name))))

(defn fill
  "ctx and the words of `line` with every path placeholder filled in: ROOM
  is :room, DIR is :sources-dir (else :results, else a new directory), any
  other (OUT, OUT2, R1) the scenario's (:placeholders) or a new path that
  does not exist yet. Returns [ctx words]."
  [ctx words]
  (reduce (fn [[ctx out] w]
            (if-let [[_ ph more] (re-matches placeholder w)]
              (let [[ctx v] (case ph
                              "ROOM" (let [r (or (:room ctx) (new-path "room"))] [(assoc ctx :room r) r])
                              "DIR" (let [d (or (:sources-dir ctx) (:results ctx)
                                                (get-in ctx [:placeholders "DIR"]) (tmp-dir))]
                                      [(assoc-in ctx [:placeholders "DIR"] d) d])
                              (let [d (or (get-in ctx [:placeholders ph]) (new-path ph))]
                                [(assoc-in ctx [:placeholders ph] d) d]))]
                [ctx (conj out (str v more))])
              [ctx (conj out w)]))
          [ctx []]
          words))

(defn run-line
  "Runs the command line `line` in the scenario's project: ctx with :dir and
  :run (and the placeholders it filled)."
  [ctx line]
  (ensure-tracking!)
  (let [words (split-line (str/replace (str/trim (str line)) #"^rdm-spec\s+" ""))
        ctx (if (:dir ctx) ctx (assoc ctx :dir (s/copy-of-room!)))
        [ctx argv] (fill ctx words)]
    (assoc ctx :run (run! ctx (:dir ctx) argv))))

(defn the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn exits!
  "The last run, after checking it exited `n`."
  [ctx n]
  (let [r (the-run ctx)]
    (s/check! (= (long n) (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn stdout-edn
  "The run's standard output read as EDN, or nil when it is not."
  [r]
  (try (edn/read-string (str (:out r))) (catch Exception _ nil)))

(defn- cannot-run-lines [r]
  (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))

(defn refused-naming!
  "Checks the last run cannot run (exit 2, nothing on standard output) and
  that its cannot-run line names each of `words`."
  [ctx & words]
  (let [r (exits! ctx 2)]
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed a result\n" (s/show r)))
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) words)) (cannot-run-lines r))
              (str "no cannot-run line names " (str/join ", " words) "\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------
;; Fixture helpers (copied in, as dhf/spec/README.md asks)

(defn- room-spec-dir [] (or (:spec-dir (s/room-config)) "dhf/spec"))

(defn- config-of [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

(defn- config! [dir f]
  (s/write! dir "dhf/harness/config.edn" (pr-str (f (config-of dir)))))

(defn spec-snapshot
  "{relative path content} of every file under dhf/spec of `dir`."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob (fs/file dir "dhf/spec") "**") :when (fs/regular-file? f)]
          [(str (fs/relativize dir f)) (slurp (str f))])))

(defn- git!
  "Runs git in `dir` with an identity; fails the step on an error."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- result-json
  "An Allure result labelled spec=`id` with `status`."
  [id status]
  (let [uuid (str (java.util.UUID/randomUUID))]
    [(str uuid "-result.json")
     (json/generate-string {:uuid uuid :name (str "a test of " id) :status status :stage "finished"
                            :labels [{:name "spec" :value id}] :steps []})]))

(defn- results-dir!
  "Makes `dir` a results directory holding one passing result."
  [dir]
  (let [[n text] (result-json "spec-0177" "passed")]
    (s/write! dir n text)
    (str dir)))

(def ^:private level-tag #"(@\S+-\d{4,}) @(?:e2e|contract)\b")

(defn- drop-a-level!
  "Removes the level tag of the first stamped scenario of the project's spec
  that has one: {:file rel :line n}, the line of its Scenario keyword."
  [dir]
  (let [spec (fs/file dir (room-spec-dir))
        hit (first (for [f (sort (map str (fs/glob spec "**.feature")))
                         :let [lines (str/split-lines (slurp f))
                               i (first (keep-indexed (fn [i l] (when (re-find level-tag l) i)) lines))]
                         :when i]
                     [f lines i]))]
    (s/check! hit "the project's spec holds no scenario with a level tag")
    (let [[f lines i] hit
          scenario (or (first (keep-indexed (fn [j l] (when (and (> j i) (re-find #"^\s*Scenario" l)) j)) lines)) i)]
      (spit f (str (str/join "\n" (assoc lines i (str/replace-first (get lines i) level-tag "$1"))) "\n"))
      {:file (str (fs/relativize dir f)) :line (inc scenario)})))

(defn- views-dir [dir] (fs/file dir (or (:views-dir (config-of dir)) "dhf/views")))

(defn- stale-view!
  "Makes the view `k`'s outputs record another model hash than the model's."
  [dir k]
  (let [other (apply str (repeat 64 "0"))]
    (doseq [ext ["mmd" "svg"]
            :let [f (fs/file (views-dir dir) (str k "." ext))]]
      (s/check! (fs/regular-file? f) (str f " does not exist"))
      (let [[first-line & more] (str/split-lines (slurp (str f)))
            changed (str/replace first-line #"model-sha256: [0-9a-f]{64}" (str "model-sha256: " other))]
        (s/check! (not= first-line changed) (str f " records no model hash: " first-line))
        (spit (str f) (str (str/join "\n" (cons changed more)) "\n"))))))

;; --- cycle projects (the measurement context's meaning, copied in) ----------

(def ^:private lint-feature "dhf/spec/architecture/lint.feature")

(def ^:private default-scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def ^:private default-cycle
  {:cut "spec-3"
   :scope :project
   :builder {:name "builder-a" :actor :agent}
   :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- scenario-text [{:keys [id title level wip? no-id?]}]
  (str "\n  " (str/join " " (remove nil? [(when-not no-id? (str "@" id)) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn- feature-text [scenarios]
  (str "# A fixture feature file of the measurement steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn- cycle-project!
  "A copy of the room's allow-list whose spec holds only lint.feature with
  `text`, committed and tagged spec-3."
  [text]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature text)
    (git! dir "init" "-q")
    (git! dir "config" "user.name" "steps")
    (git! dir "config" "user.email" "steps@rdm-spec.invalid")
    (git! dir "add" "-A" "--" "." ":(exclude)dhf/cycles")
    (git! dir "commit" "-q" "--allow-empty" "-m" "the cut spec-3")
    (git! dir "tag" "-f" "spec-3")
    dir))

(defn- write-cycle! [dir]
  (s/write! dir "dhf/cycles/03/cycle.edn" (pr-str default-cycle)))

(defn- small-room!
  "A small room: an implementation whose tests exit 0."
  []
  (let [r (tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "test.clj" "(System/exit 0)\n")
    r))

(defn- line-of [text start]
  (some (fn [[i l]] (when (str/starts-with? (str/trim l) start) (inc i)))
        (map-indexed vector (str/split-lines text))))

;; ---------------------------------------------------------------------------
;; One owner per shared text: narrowing the definitions of other files

(def ^:private this-file
  (let [f (str *file*)]
    (try (if (fs/exists? f) (str (fs/canonicalize f)) f) (catch Exception _ f))))

(defonce ^:private owned (atom []))

(defn- own!
  "Remembers `pattern` as one of the kernel's shared step patterns."
  [pattern]
  (swap! owned (fn [ps] (if (some #(= (str %) (str pattern)) ps) ps (conj ps pattern))))
  pattern)

(defonce ^:private spec-texts
  (delay
    (let [dir (fs/file s/room-root (room-spec-dir))]
      (vec (distinct
            (for [f (sort (map str (fs/glob dir "**.feature")))
                  :let [parsed (try (gherkin/parse-feature f (slurp f)) (catch Exception _ nil))]
                  sc (:scenarios parsed)
                  concrete (try (gherkin/expand sc) (catch Exception _ [sc]))
                  st (concat (:background concrete) (:steps concrete))]
              (:text st)))))))

(defn- owned-texts
  "The spec's step texts an owned pattern matches."
  []
  (let [ps @owned]
    (vec (filter (fn [t] (some #(re-matches % t) ps)) @spec-texts))))

(defn- narrow
  "Definition `d`, narrowed to match none of `texts`."
  [d texts]
  (assoc d :pattern (re-pattern (str "(?!(?:" (str/join "|" (map #(java.util.regex.Pattern/quote %) texts)) ")$)"
                                     "(?:" (.pattern ^java.util.regex.Pattern (:pattern d)) ")"))
         ::narrowed texts))

(defn- narrowed
  "The registry with every other file's definition of an owned text
  narrowed; every definition seen is marked ::seen."
  [defs texts]
  (mapv (fn [d]
          (if (::seen d)
            d
            (let [hits (when (not= this-file (:file d))
                         (filter #(re-matches (:pattern d) %) texts))]
              (assoc (if (seq hits) (narrow d hits) d) ::seen true))))
        defs))

(defn narrow-registry!
  "Narrows every definition another file registered (or registers later)
  that matches a step text the kernel owns."
  []
  (let [reg runner/*registry*
        fix! (fn [_ a _ new]
               (ensure-tracking!)
               (when (some #(not (::seen %)) new)
                 (let [texts (owned-texts)]
                   (swap! a narrowed texts))))]
    (add-watch reg ::one-owner fix!)
    (fix! nil reg nil @reg)))

(defmacro ^:private defshared
  "Defines a shared step: one the kernel owns."
  [pattern f]
  `(do (own! ~pattern)
       (runner/register! ~pattern ~f ~(str *file*) ~(:line (meta &form)))))

;; ---------------------------------------------------------------------------
;; The shared When

(defshared #"\"rdm-spec ([^\"]*)\" is run"
  (fn [ctx line] (run-line ctx line)))

(defshared #"\"rdm-spec ([^\"]*)\" is run with no --results"
  (fn [ctx line]
    (s/check! (not (some #{"--results"} (split-line line))) (str "the line gives --results: " line))
    (run-line (assoc-in ctx [:env "RESULTS_DIR"] nil) line)))

(defshared #"\"rdm-spec ([^\"]*)\" is run with that (?:results )?directory as DIR"
  (fn [ctx line]
    (let [d (or (:sources-dir ctx) (:results ctx))]
      (s/check! d "no directory was given to stand for DIR")
      (run-line (assoc ctx :sources-dir d) line))))

;; ---------------------------------------------------------------------------
;; The shared Thens

(defshared #"it exits (\d)"
  (fn [ctx n] (exits! ctx (parse-long n)) nil))

(defshared #"it passes"
  (fn [ctx]
    (let [r (exits! ctx 0)]
      (s/check! (map? (stdout-edn r)) (str "standard output holds no result\n" (s/show r)))
      nil)))

(defshared #"it exits 2 and names (\S+)"
  (fn [ctx word] (refused-naming! ctx word) nil))

;; ---------------------------------------------------------------------------
;; The shared Givens

(defshared #"a copy of rdm-spec's own project in which the configured results directory does not exist"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          rd (:results-dir (config-of dir))]
      (s/check! rd "the configuration names no results directory")
      (when (fs/exists? (fs/file dir rd)) (fs/delete-tree (fs/file dir rd)))
      (-> ctx
          (assoc :dir dir :results-dir rd)
          (assoc-in [:env "RESULTS_DIR"] nil)))))

(defshared #"a rendered view whose recorded hash differs from the model's"
  (fn [ctx]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          k "containers"]
      (when (and (:dir ctx) (fs/which "mmdr"))
        (let [r (run! ctx dir ["views"])]
          (s/check! (zero? (:exit r)) (str "rdm-spec views failed\n" (s/show r)))))
      (stale-view! dir k)
      (assoc ctx :dir dir :stale-view k))))

(defshared #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (let [dir (s/project!)
          cfg (s/room-config)]
      (s/write! dir (str (:spec-dir cfg) "/demo/alpha.feature")
                (s/feature (str "  Rule: The demo rule\n\n"
                                "    @spec-0001 @e2e\n"
                                "    Scenario Outline: An outline over <n>\n"
                                "      Given the value <n>\n\n"
                                "      Examples:\n"
                                "        | n |\n        | 1 |\n        | 2 |\n        | 3 |\n")))
      (s/write! dir (str (:steps-dir cfg) "/demo.clj")
                (s/step-file "(defstep #\"the value (\\d+)\" (fn [ctx n] (assoc ctx :n n)))"))
      (assoc ctx :dir dir :id "spec-0001"))))

(defshared #"dhf/cycles/03/cycle\.edn names the cut spec-3"
  (fn [ctx]
    (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (cycle-project! (feature-text default-scenarios))))]
      (write-cycle! (:dir ctx))
      (assoc ctx :room (or (:room ctx) (small-room!)) :cycle "03"))))

(defshared #"the spec at cut spec-3 holds a scenario with no id"
  (fn [ctx]
    (let [text (feature-text (conj (vec (remove :wip? default-scenarios))
                                   {:no-id? true :title "A scenario nobody stamped"}))
          dir (cycle-project! text)]
      (write-cycle! dir)
      (assoc ctx :dir dir :room (small-room!) :cycle "03"
             :unstamped {:file lint-feature :line (line-of text "Scenario: A scenario nobody stamped")}))))

(defshared #"no file in the spec changes"
  (fn [ctx]
    (s/check! (:spec-before ctx) "no snapshot of the spec was taken")
    (s/check! (= (:spec-before ctx) (spec-snapshot (:dir ctx))) "a file under dhf/spec changed")
    nil))

(defshared #"the configured stack words include \"([^\"]+)\""
  (fn [ctx word]
    (let [ctx (assoc ctx :dir (or (:dir ctx) (s/copy-of-room!)))]
      (config! (:dir ctx) #(update % :stack-words (fn [ws] (vec (distinct (conj (vec ws) word))))))
      (assoc ctx :stack-word word))))

;; ---------------------------------------------------------------------------
;; dhf/spec/kernel/command.feature: everything runs through one command

(defn- spec-tasks
  "The task names the spec's own table lists (spec-0176's examples)."
  []
  (let [f (str (fs/file s/room-root (room-spec-dir) "kernel/command.feature"))
        sc (first (filter #(= "Scenario Outline" (:keyword %))
                          (:scenarios (gherkin/parse-feature f (slurp f)))))]
    (vec (for [concrete (gherkin/expand sc)] (get-in concrete [:example :values "task"])))))

(defstep #"a project set up by rdm-spec"
  (fn [ctx]
    (let [parent (tmp-dir)
          r (run! ctx parent ["init" "project"])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir (str (fs/file parent "project"))))))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [r (exits! ctx 0)
          row (some #(when (= task (:task %)) %) (:tasks (stdout-edn r)))]
      (s/check! row (str "help lists no task " task "\n" (s/show r)))
      (s/check! (= usage (:usage row)) (str task "'s usage is " (pr-str (:usage row)) ", not " (pr-str usage)))
      (s/check! (= does (:does row)) (str task " is described as " (pr-str (:does row)) ", not " (pr-str does)))
      nil)))

(defstep #"the configured results directory is (\S+)"
  (fn [ctx rel]
    (let [dir (s/copy-of-room!)]
      (config! dir #(assoc % :results-dir rel))
      (results-dir! (fs/file dir rel))
      (-> ctx
          (assoc :dir dir)
          (assoc-in [:env "RESULTS_DIR"] nil)))))

(defstep #"the environment variable RESULTS_DIR is (\S+)"
  (fn [ctx v]
    (if (= "unset" v)
      (assoc-in ctx [:env "RESULTS_DIR"] nil)
      (let [d (results-dir! (tmp-dir))]
        (-> ctx
            (assoc-in [:placeholders v] d)
            (assoc-in [:env "RESULTS_DIR"] d))))))

(defstep #"it reads (\S+)"
  (fn [ctx d]
    (let [r (the-run ctx)
          want (get-in ctx [:placeholders d] d)]
      (if (zero? (:exit r))
        (s/check! (= want (str (:results (stdout-edn r))))
                  (str "it read " (pr-str (:results (stdout-edn r))) ", not " want "\n" (s/show r)))
        (refused-naming! ctx want))
      nil)))

;; ---------------------------------------------------------------------------
;; A gate stops the task; every task reports the same way

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          model-rel (:model (config-of dir))
          model (edn/read-string (slurp (str (fs/file dir model-rel))))]
      (s/write! dir model-rel (pr-str (assoc-in model [:system :description] "A model changed after its views")))
      (assoc ctx :dir dir :no-level (drop-a-level! dir)))))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (let [r (exits! ctx 1)
          lines (s/err-lines r)
          {:keys [file line]} (:no-level ctx)]
      (s/check! (some #(and (str/starts-with? % (str file ":" line ":")) (str/includes? % "scenario-no-level")) lines)
                (str "no finding names the missing level at " file ":" line "\n" (s/show r)))
      (s/check! (some #(str/includes? % "view-stale") lines) (str "no finding names a stale view\n" (s/show r)))
      nil)))

(defstep #"a spec that passes every check"
  (fn [ctx] (assoc ctx :dir (s/copy-of-room!))))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (assoc ctx :dir dir :no-level (drop-a-level! dir)))))

(def ^:private finding-line
  #"(?:[^\s:][^:]*:(?:\d+:)? )?(?:warning: )?[a-z0-9][a-z0-9-]*: \S.*")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (let [r (the-run ctx)
          lines (remove #(str/starts-with? % "event: ") (s/err-lines r))
          {:keys [file line]} (:no-level ctx)]
      (s/check! (seq lines) (str "the error stream holds no finding\n" (s/show r)))
      (doseq [l lines]
        (s/check! (re-matches finding-line l) (str "not a finding line: " (pr-str l))))
      (when file
        (s/check! (some #(str/starts-with? % (str file ":" line ": scenario-no-level: ")) lines)
                  (str "no line names " file ":" line " under scenario-no-level\n" (s/show r))))
      nil)))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (let [r (the-run ctx)
          rdr (java.io.PushbackReader. (java.io.StringReader. (str (:out r))))
          forms (try (vec (take-while #(not= ::eof %) (repeatedly #(edn/read {:eof ::eof} rdr))))
                     (catch Exception e (s/fail! (str "standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
      (s/check! (= 1 (count forms)) (str "standard output holds " (count forms) " values\n" (s/show r)))
      (s/check! (map? (first forms)) (str "the result is not a map\n" (s/show r)))
      nil)))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx]
    (let [rd (or (:results-dir ctx) (:results-dir (config-of (:dir ctx))))]
      (refused-naming! ctx rd)
      nil)))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (let [r (refused-naming! ctx "frobnicate")
          err (str (:err r))]
      (doseq [t (spec-tasks)]
        (s/check! (re-find (re-pattern (str "\\b" (java.util.regex.Pattern/quote t) "\\b")) err)
                  (str "the task " t " is not listed\n" (s/show r))))
      nil)))

;; Last: the kernel's shared texts are its own from here on.
(narrow-registry!)
