(ns rdm-spec.kernel.runner
  "Gherkin runner (dhf/spec/kernel/runner.feature): runs every scenario of a
  spec against the project's step definitions and writes one Allure result per
  scenario (one per example row of an outline), labelled with the scenario's
  id, component and context.

  The step API, a public contract (dhf/harness/stack.md):

    (require '[rdm-spec.kernel.runner :refer [defstep]])
    (defstep #\"the spec holds (\\d+) features\" (fn [ctx n] ctx'))

  A step's function receives the scenario's context and the pattern's
  captured groups, then the step's data table (a vector of rows, header
  first) or doc string when it has one. Its return is the next step's context;
  nil (or anything not a map) leaves the context as it was. Each scenario
  starts with an empty context.

  A scenario's result is:
    passed   every step passed
    failed   a step threw; the result names the step and the error, and the
             steps after it are reported as not run (skipped)
    broken   a step no definition matches, a step two or more definitions
             match (naming each), or a step that threw an ex-info whose data
             holds ::broken (it cannot be verified yet)
    skipped  a planned scenario (@wip): none of its steps is run, and its
             result is labelled planned=true

  Feature files are discovered under the spec directory, never listed; a run
  that finds none is refused and writes nothing.

  While a scenario runs, processes it starts through babashka.process do not
  inherit RESULTS_DIR: the run's results directory is the runner's, and a
  scenario that needs the variable sets it itself."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; The step API

(def ^:dynamic *registry*
  "Every step definition loaded: an atom of [{:pattern :fn :file :line}], in
  the order defined. Definitions are told apart by file and line: defining
  again at the same place replaces the earlier one."
  (atom []))

(defn- canonical-file [file]
  (let [s (str file)]
    (try (if (and (not (str/blank? s)) (fs/exists? s)) (str (fs/canonicalize s)) s)
         (catch Exception _ s))))

(defn register!
  "Registers the step definition `pattern` -> `f`, defined at `file`:`line`."
  [pattern f file line]
  (let [file (canonical-file file)
        d {:pattern pattern :fn f :file file :line line}]
    (swap! *registry*
           (fn [defs]
             (let [i (first (keep-indexed (fn [i x] (when (and (= file (:file x)) (= line (:line x))) i)) defs))]
               (if i (assoc (vec defs) i d) (conj (vec defs) d)))))
    nil))

(defmacro defstep
  "Defines a step: `pattern` (a regular expression the whole step text must
  match) and `f`, a function of the context and the captured groups."
  [pattern f]
  `(register! ~pattern ~f ~(str *file*) ~(:line (meta &form))))

(defn matching
  "The definitions whose pattern matches the whole of `text`."
  [text]
  (filterv #(re-matches (:pattern %) text) @*registry*))

;; ---------------------------------------------------------------------------
;; The project: feature files, the model, step files

(defn feature-files
  "Every .feature file under `dir`, sorted by path; [] when there is none."
  [dir]
  (if (and dir (fs/directory? dir))
    (vec (sort (map str (fs/glob dir "**.feature"))))
    []))

(defn read-model
  "The C4 model in the file `f`, or nil when absent or unreadable."
  [f]
  (when (and f (fs/exists? f))
    (try (let [m (edn/read-string (slurp (str f)))] (when (map? m) m))
         (catch Exception _ nil))))

(defn owner-of
  "The component owning the feature file at `spec-path` (\"architecture/lint\")
  in the model: {:component \"Lint\" :context \"architecture\"}; a kernel
  component's context is \"kernel\". nil when the model names no owner; with
  no model at all, read from the path: {:component \"lint\" :context
  \"architecture\"}."
  [model spec-path]
  (if (nil? model)
    (let [parts (str/split (str spec-path) #"/")]
      (when (>= (count parts) 2)
        {:component (last parts) :context (first parts)}))
   (first
   (concat
    (for [c (:containers model) ctx (:contexts c) comp (:components ctx)
          :when (= spec-path (:spec comp))]
      {:component (:name comp) :context (or (:name ctx) (some-> (:id ctx) name))})
    (for [comp (:kernel model) :when (= spec-path (:spec comp))]
      {:component (:name comp) :context "kernel"})))))

(defn- spec-path [spec-root file]
  (-> (str (fs/relativize (fs/absolutize spec-root) (fs/absolutize file)))
      (str/replace "\\" "/")
      (str/replace #"\.feature$" "")))

(defn load-step-files!
  "Loads every .clj file under `steps-dir`, in path order, after emptying the
  registry. Returns [{:file :message}] for the files that could not be loaded."
  [steps-dir]
  (reset! *registry* [])
  (when (and steps-dir (fs/directory? steps-dir))
    (let [parent (fs/parent (fs/absolutize steps-dir))]
      (when parent (cp/add-classpath (str parent)))))
  (vec
   (for [f (if (and steps-dir (fs/directory? steps-dir))
             (sort (map str (fs/glob steps-dir "**.clj")))
             [])
         :let [err (try (binding [*ns* (create-ns 'rdm-spec.kernel.runner.steps)]
                          (eval '(clojure.core/refer-clojure))
                          (load-file f))
                        nil
                        (catch Throwable e e))]
         :when err]
     {:file f :message (str (.getName (class err)) ": " (ex-message err))})))

;; ---------------------------------------------------------------------------
;; Allure results

(defn clean-results!
  "Removes earlier Allure files (results, containers, attachments) from `dir`."
  [dir]
  (when (fs/directory? dir)
    (doseq [f (fs/list-dir dir)
            :when (and (fs/regular-file? f)
                       (re-find #"-(result|container|attachment)(\.[A-Za-z0-9]+)?$|-result\.json$|-container\.json$"
                                (str (fs/file-name f))))]
      (fs/delete f))))

(defn write-result!
  "Writes one Allure result (a map, :uuid included) as <uuid>-result.json."
  [dir result]
  (fs/create-dirs dir)
  (let [uuid (or (:uuid result) (str (java.util.UUID/randomUUID)))
        f (fs/file dir (str uuid "-result.json"))]
    (spit f (json/generate-string (assoc result :uuid uuid) {:pretty true}))
    (str f)))

(defn summarize
  "{:scenarios n :passed n :failed n :broken n :skipped n} over run results."
  [results]
  (merge {:scenarios (count results) :passed 0 :failed 0 :broken 0 :skipped 0}
         (frequencies (map (comp keyword :status) results))))

;; ---------------------------------------------------------------------------
;; Running one scenario

(defn- step-name [st] (str (:keyword st) " " (:text st)))

(defn- where [d] (str (:file d) ":" (:line d)))

(defn- no-results-dir-env []
  (into {} (remove (fn [[k _]] (= "RESULTS_DIR" k)) (System/getenv))))

(defn- run-step
  "Runs one step: {:status :ctx :message}."
  [ctx st]
  (let [text (:text st)
        defs (matching text)]
    (cond
      (empty? defs)
      {:status "broken" :ctx ctx
       :message (str "no step definition matches the step \"" (step-name st) "\" (line " (:line st) ")")}

      (> (count defs) 1)
      {:status "broken" :ctx ctx
       :message (str "the step \"" (step-name st) "\" is matched by " (count defs) " step definitions: "
                     (str/join ", " (map #(str (where %) " " (pr-str (str (:pattern %)))) defs)))}

      :else
      (let [d (first defs)
            m (re-matches (:pattern d) text)
            captured (if (string? m) [] (vec (rest m)))
            extra (cond (:data-table st) [(:data-table st)]
                        (:doc-string st) [(:doc-string st)]
                        :else [])]
        (try
          (let [out (binding [process/*defaults* (assoc process/*defaults* :env (no-results-dir-env))]
                      (apply (:fn d) ctx (concat captured extra)))]
            {:status "passed" :ctx (if (map? out) out ctx)})
          (catch Throwable e
            (let [broken? (::broken (ex-data e))]
              {:status (if broken? "broken" "failed") :ctx ctx
               :message (str "the step \"" (step-name st) "\" (line " (:line st) ") "
                             (if broken? "is broken" "failed") ": "
                             (or (ex-message e) (.getName (class e))))})))))))

(defn- run-steps
  "Runs `steps` in order from an empty context: {:status :message :steps}."
  [steps]
  (loop [sts steps ctx {} out [] status "passed" message nil]
    (if-let [[st & more] (seq sts)]
      (if (not= "passed" status)
        (recur more ctx (conj out {:name (step-name st) :status "skipped" :start 0 :stop 0
                                   :statusDetails {:message "not run: an earlier step did not pass"}})
               status message)
        (let [start (System/currentTimeMillis)
              {s :status c :ctx msg :message} (run-step ctx st)
              stop (System/currentTimeMillis)]
          (recur more c (conj out (cond-> {:name (step-name st) :status s :start start :stop stop}
                                    msg (assoc :statusDetails {:message msg})))
                 s msg)))
      {:status status :message message :steps out})))

(defn- labels [{:keys [id owner feature planned? sp]}]
  (let [[ctx-name comp-name] (if owner
                               [(:context owner) (:component owner)]
                               (let [parts (str/split sp #"/")]
                                 [(first parts) (last parts)]))]
    (cond-> []
      id (conj {:name "spec" :value id})
      comp-name (conj {:name "component" :value comp-name})
      ctx-name (conj {:name "context" :value ctx-name})
      feature (conj {:name "feature" :value feature})
      true (conj {:name "suite" :value "scenarios"} {:name "framework" :value "rdm-spec"})
      planned? (conj {:name "planned" :value "true"}))))

(defn run-scenario
  "Runs one concrete scenario (an outline's row, or a scenario), writes its
  result into `results-dir` and returns the run's view of it."
  [{:keys [results-dir id-prefix]} {:keys [file feature owner sp]} sc]
  (let [id-re (re-pattern (str "@" (java.util.regex.Pattern/quote (or id-prefix "spec")) "-\\d+"))
        ids (vec (distinct (map #(subs % 1) (filter #(re-matches id-re %) (:tags sc)))))
        id (when (= 1 (count ids)) (first ids))
        planned? (boolean (some #{"@wip"} (:all-tags sc)))
        values (get-in sc [:example :values])
        header (get-in sc [:example :header])
        row-text (when values (str/join ", " (map #(str % "=" (get values %)) header)))
        name (str (:name sc) (when row-text (str " [" row-text "]")))
        steps (vec (concat (:background sc) (:steps sc)))
        start (System/currentTimeMillis)
        {:keys [status message] step-results :steps}
        (if planned?
          {:status "skipped" :message "planned (@wip): not run"
           :steps (mapv #(hash-map :name (step-name %) :status "skipped" :start 0 :stop 0) steps)}
          (run-steps steps))
        stop (System/currentTimeMillis)
        uuid (str (java.util.UUID/randomUUID))
        result (cond-> {:uuid uuid
                        :historyId (str file ":" (:line sc) (when row-text (str "[" row-text "]")))
                        :name name
                        :fullName (str file ":" (:line sc) " " name)
                        :status status
                        :statusDetails (if message {:message message} {})
                        :stage "finished" :start start :stop stop
                        :labels (labels {:id id :owner owner :feature feature :planned? planned? :sp sp})
                        :steps step-results}
                 values (assoc :parameters (mapv #(hash-map :name % :value (get values %)) header)))]
    (write-result! results-dir result)
    {:status status :ids ids :id id :file file :line (or (get-in sc [:example :line]) (:line sc))
     :name name :message message :planned? planned?}))

;; ---------------------------------------------------------------------------
;; Running a spec

(defn run-features
  "Runs every scenario of the spec. `opts`:
    :root         the project root
    :spec-dir     the spec directory (relative to :root)
    :steps-dir    the step definitions' directory (relative to :root)
    :results-dir  where results go (relative to :root, or absolute)
    :id-prefix    the scenario id prefix
    :model        the C4 model file (relative to :root), for the labels
    :clean?       remove earlier results first
  Returns {:results [{:status :ids :file :line :name :message}]
           :refused nil-or-why :refusals [{:file :line :rule :message}]}.
  Refused (nothing written) when no feature file is found."
  [{:keys [root spec-dir steps-dir results-dir id-prefix model clean?]}]
  (let [at #(when % (let [s (str %)] (if (fs/absolute? s) s (str (fs/file root s)))))
        spec-abs (at spec-dir)
        files (feature-files spec-abs)]
    (if (empty? files)
      {:results [] :refusals []
       :refused (str "no scenario was found: the spec directory " spec-dir " holds no feature file")}
      (let [results-abs (at results-dir)
            _ (fs/create-dirs results-abs)
            _ (when clean? (clean-results! results-abs))
            load-errors (load-step-files! (at steps-dir))
            mdl (read-model (at model))
            opts {:results-dir results-abs :id-prefix id-prefix}
            parsed (for [f files]
                     (let [rel (str/replace (str (fs/relativize (fs/absolutize root) (fs/absolutize f))) "\\" "/")]
                       (assoc (gherkin/parse-feature rel (slurp f)) :sp (spec-path spec-abs f))))
            out (vec (for [{:keys [file feature scenarios sp]} parsed
                           sc scenarios
                           concrete (gherkin/expand sc)]
                       (run-scenario opts {:file file :feature (:name feature) :sp sp :owner (owner-of mdl sp)}
                                     concrete)))]
        {:results out
         :refused nil
         :refusals (vec (concat
                         (for [{:keys [file message]} load-errors]
                           {:file file :line nil :rule "step-file" :message (str "the step file could not be loaded: " message)})
                         (mapcat :refusals parsed)))}))))

(defn run
  "The run task: `rdm-spec run [--results DIR]`. Runs every scenario of the
  project's spec against its step definitions (the configured :steps-dir),
  writing results into the results directory after clearing earlier ones."
  [req]
  (let [config (:config req)
        cfg (fn [k] (or (get config k)
                        (throw (ex-info (str "the configuration sets no " k)
                                        {:rdm-spec.kernel.command/cannot-run true}))))
        results (or (:results req) (cfg :results-dir))
        out (run-features {:root (:root req) :spec-dir (cfg :spec-dir) :steps-dir (cfg :steps-dir)
                           :results-dir results :id-prefix (or (:id-prefix config) "spec")
                           :model (:model config) :clean? true})]
    (when (:refused out)
      (throw (ex-info (:refused out) {:rdm-spec.kernel.command/cannot-run true})))
    (let [rs (:results out)
          rel-file (fn [f] f)]
      {:result {:results results
                :summary (summarize rs)
                :scenarios (mapv #(-> (select-keys % [:id :name :file :line :status])
                                      (update :file rel-file))
                                 rs)}
       :findings (vec (concat
                       (for [r (:refusals out)]
                         {:file (:file r) :line (:line r) :rule (:rule r) :message (:message r) :severity :error})
                       (for [r rs :when (#{"failed" "broken"} (:status r))]
                         {:file (:file r) :line (:line r) :rule (str "scenario-" (:status r))
                          :message (str (or (:id r) "an unstamped scenario") " \"" (:name r) "\": " (:message r))
                          :severity :error})))})))
