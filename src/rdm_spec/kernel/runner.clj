(ns rdm-spec.kernel.runner
  "The kernel component Gherkin runner (dhf/spec/kernel/runner.feature):
  runs the scenarios' steps against their step definitions and writes one
  Allure result per scenario, and per row of an outline, in Allure's own
  model, filled from the Gherkin scenario (dhf/spec/glossary.md, \"Allure
  result\").

  The step API, a public contract (spec-0168):

    (require '[rdm-spec.kernel.runner :refer [defstep attach!]])
    (defstep #\"the spec holds (\\d+) features\" (fn [ctx n] (assoc ctx :n n)))
    (attach! \"response\" \"GET / answered 200\")   ; evidence, in a step

  A step's function gets the context and the pattern's captures (and its
  doc string or data table, rows of cells, when it has one); what it
  returns is the next step's context, nil keeping it. Each scenario starts
  with {}. A step no definition matches, or two match, breaks the result
  (broken); a step that throws fails it (failed), and the steps after it
  are skipped. A scenario tagged @wip is not run: its result is skipped and
  labelled planned=true.

  The result: uuid, historyId (the id; an outline row's is the id and the
  row's values, `spec-0013[plan, 3]`), name (the title, an outline's with
  the row's values), fullName (FILE:TITLE), status, statusDetails
  ({message trace}, when it did not pass), stage, start, stop, steps (one
  per Gherkin step, the background's first: name KEYWORD TEXT, status,
  stage, start, stop, attachments), parameters (the row's values, by the
  table's headings), labels (feature, story = the rule, suite = the
  context, subSuite = the component, a tag per Gherkin tag, framework
  gherkin, spec, context, component, planned), links (an issue per need)
  and attachments. An attachment is a file <uuid>-attachment.txt beside
  the result.

  Used by test.clj (plan, step-files, load-step-files!, run-pickles!,
  shadow-warnings) and by the task `rdm-spec run [--results DIR]` (stage
  `run`), which runs the project's scenarios against the step files of its
  configured :steps-dir, found by looking: no feature file is cannot-run
  (exit 2) and writes no result; a scenario that did not pass is a finding
  (scenario-failed, scenario-broken)."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; The step registry

(defonce ^:private registry (atom {}))   ; pattern -> {:re :fn :file :line}
(defonce ^:private shadowed (atom []))

(defn register!
  "Registers the step definition `re` -> `f`, defined at `file`:`line`."
  [re f file line]
  (let [k (str re)
        old (get @registry k)]
    (when (and old (not= [(:file old) (:line old)] [file line]))
      (swap! shadowed conj {:pattern k :file file :line line :was (str (:file old) ":" (:line old))}))
    (swap! registry assoc k {:re re :fn f :file file :line line})
    nil))

(defmacro defstep
  "Defines a step: its pattern and a function of the context and the
  captured text."
  [re f]
  `(register! ~re ~f ~*file* ~(:line (meta &form))))

(defn definitions
  "Every step definition registered."
  []
  (vec (vals @registry)))

(defn shadow-warnings
  "A warning for each pattern defined twice: the later one is used."
  []
  (vec (for [{:keys [pattern file line was]} @shadowed]
         (command/warning file line "step-shadowed"
                          (str "the step #\"" pattern "\" is defined again here; the definition at " was " is not used")))))

(defn step-files
  "Every step definition file under `dir`, sorted."
  [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/glob dir "**.clj"))))
    []))

(defn load-step-files!
  "Loads the step files `files`, in order."
  [files]
  (doseq [f files] (load-file (str f))))

;; ---------------------------------------------------------------------------
;; Evidence

(def ^:dynamic *results-dir* nil)
(def ^:dynamic *attachments* nil)

(defn- extension [type]
  (case type "text/plain" "txt" "application/json" "json" "text/html" "html" "image/png" "png" "bin"))

(defn attach!
  "Attaches `text` to the running step as evidence named `name` (of the
  type `type`, text/plain by default): a file beside the result. Nothing
  outside a run."
  ([name text] (attach! name text "text/plain"))
  ([name text type]
   (when (and *results-dir* *attachments*)
     (let [src (str (random-uuid) "-attachment." (extension type))]
       (fs/create-dirs *results-dir*)
       (spit (str (fs/file *results-dir* src)) (str text))
       (swap! *attachments* conj {:name (str name) :type type :source src})))
   nil))

;; ---------------------------------------------------------------------------
;; Pickles: one per scenario, one per row of an outline

(defn owners
  "{spec-path {:context-name :component-name}} of the model `m`, the
  kernel's under the context kernel."
  [m]
  (into {}
        (concat
         (for [c (when (map? m) (:containers m)) :when (map? c)
               x (:contexts c) :when (map? x)
               comp (:components x) :when (and (map? comp) (string? (:spec comp)))]
           [(:spec comp) {:context-name (or (:name x) (some-> (:id x) name))
                          :component-name (or (:name comp) (some-> (:id comp) name))}])
         (for [comp (when (map? m) (:kernel m)) :when (and (map? comp) (string? (:spec comp)))]
           [(:spec comp) {:context-name "kernel" :component-name (or (:name comp) (some-> (:id comp) name))}]))))

(defn- id-re [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}")))

(defn- sub [s values] (if (seq values) (gherkin/substitute s (into {} values)) s))

(defn- step-of [values st]
  (cond-> {:keyword (:keyword st) :text (sub (:text st) values) :line (:line st)
           :name (str (:keyword st) " " (sub (:text st) values))}
    (contains? st :doc-string) (assoc :arg (sub (:doc-string st) values))
    (contains? st :data-table) (assoc :arg (mapv (fn [row] (mapv #(sub % values) row)) (:data-table st)))))

(defn pickles-of
  "The pickles of the feature file `rel` holding `text`: [{:id :title
  :display-name :file :line :row :parameters :tags :needs :wip? :feature
  :rule :steps :context-name :component-name}]. `opts`: :owners, :spec-dir,
  :prefix."
  [rel text {:keys [owners spec-dir prefix]}]
  (let [{:keys [feature scenarios]} (gherkin/parse-feature rel text)
        sp (let [d (str (str/replace (str spec-dir) #"/+$" "") "/")]
             (when (str/starts-with? rel d) (str/replace (subs rel (count d)) #"\.feature$" "")))
        owner (merge {:context-name nil :component-name nil} (get owners sp))]
    (vec
     (for [sc scenarios
           :let [tags (vec (:all-tags sc))
                 ids (distinct (for [t (:tags sc) :when (re-matches (id-re prefix) t)] (subs t 1)))
                 rows (if (= "Scenario Outline" (:keyword sc))
                        (for [ex (:examples sc) row (:rows ex)]
                          {:line (:line row) :values (mapv vector (:header ex) (:cells row))})
                        [nil])]
           row rows
           :let [values (:values row)]]
       (merge owner
              {:id (when (= 1 (count ids)) (first ids))
               :title (:name sc)
               :display-name (sub (:name sc) values)
               :file rel
               :line (:line sc)
               :row (when row {:line (:line row)})
               :parameters values
               :tags tags
               :needs (vec (distinct (for [t tags :when (re-matches #"@UN-\d+" t)] (subs t 1))))
               :wip? (boolean (some #{"@wip"} tags))
               :feature (:name feature)
               :rule (get-in sc [:rule :name])
               :steps (mapv #(step-of values %) (concat (:background sc) (:steps sc)))})))))

(defn plan
  "The scenarios of the project `root`: {:pickles :refusals :features},
  :features the number of feature files found. `opts`: :spec-dir
  :id-prefix :model (paths relative to root) and :only (paths limiting the
  feature files)."
  [root {:keys [spec-dir id-prefix model only]}]
  (let [spec-dir (str/replace (or spec-dir "dhf/spec") #"/+$" "")
        d (fs/file root spec-dir)
        m (let [f (fs/file root (or model "dhf/spec/model.edn"))]
            (when (fs/exists? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil))))
        os (owners m)
        under? (fn [rel] (or (empty? only)
                             (some (fn [o]
                                     (let [o (str/replace (str (if (fs/absolute? o) (fs/relativize (fs/absolutize root) o) o)) #"/+$" "")]
                                       (or (= rel o) (str/starts-with? rel (str o "/")))))
                                   only)))
        files (if (fs/directory? d)
                (vec (sort (for [f (fs/glob d "**.feature")
                                 :let [rel (command/rel root f)]
                                 :when (under? rel)]
                             rel)))
                [])
        parsed (for [rel files :let [text (slurp (str (fs/file root rel)))]]
                 {:pickles (pickles-of rel text {:owners os :spec-dir spec-dir :prefix id-prefix})
                  :refusals (:refusals (gherkin/parse-feature rel text))})]
    {:pickles (vec (mapcat :pickles parsed))
     :refusals (vec (mapcat :refusals parsed))
     :features (count files)}))

;; ---------------------------------------------------------------------------
;; Running

(defn- now [] (System/currentTimeMillis))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw))
    (str sw)))

(defn- where [d] (str (:file d) ":" (:line d)))

(defn run-step
  "Runs the step `st` against `defs` with the context `ctx`: {:status :ctx
  :message :trace}."
  [defs ctx st]
  (let [ms (keep (fn [d] (when-let [m (re-matches (:re d) (:text st))] [d (if (string? m) [] (vec (rest m)))])) defs)]
    (cond
      (empty? ms)
      {:status "broken" :ctx ctx :message (str "no step definition matches \"" (:name st) "\"")}

      (< 1 (count ms))
      {:status "broken" :ctx ctx
       :message (str "\"" (:name st) "\" is matched by " (count ms) " step definitions: "
                     (str/join ", " (for [[d] ms] (str "#\"" (:re d) "\" at " (where d)))))}

      :else
      (let [[d caps] (first ms)
            args (cond-> (into [ctx] caps) (contains? st :arg) (conj (:arg st)))]
        (try
          (let [out (apply (:fn d) args)]
            {:status "passed" :ctx (if (nil? out) ctx out)})
          (catch Throwable e
            {:status "failed" :ctx ctx :message (str "\"" (:name st) "\" failed: " (or (ex-message e) (str (class e))))
             :trace (trace-of e)}))))))

(defn- labels-of [pk]
  (vec (concat
        [{:name "feature" :value (str (:feature pk))}]
        (when (:rule pk) [{:name "story" :value (:rule pk)}])
        (when (:context-name pk) [{:name "suite" :value (:context-name pk)}])
        (when (:component-name pk) [{:name "subSuite" :value (:component-name pk)}])
        (for [t (:tags pk)] {:name "tag" :value t})
        [{:name "framework" :value "gherkin"}]
        (when (:id pk) [{:name "spec" :value (:id pk)}])
        (when (:context-name pk) [{:name "context" :value (:context-name pk)}])
        (when (:component-name pk) [{:name "component" :value (:component-name pk)}])
        (when (:wip? pk) [{:name "planned" :value "true"}]))))

(defn result-of
  "The Allure result of the pickle `pk` given its run `ran` ({:status
  :message :trace :start :stop :steps})."
  [pk {:keys [status message trace start stop steps]}]
  (let [values (:parameters pk)
        history (str (or (:id pk) (str (:file pk) ":" (:line pk)))
                     (when (seq values) (str "[" (str/join ", " (map second values)) "]")))]
    (cond-> {:uuid (str (random-uuid))
             :historyId history
             :name (:display-name pk)
             :fullName (str (:file pk) ":" (:title pk))
             :status status
             :stage "finished"
             :start start
             :stop stop
             :steps steps
             :parameters (vec (for [[n v] values] {:name n :value v}))
             :labels (labels-of pk)
             :links (vec (for [n (:needs pk)] {:type "issue" :name n}))
             :attachments []}
      (not= "passed" status) (assoc :statusDetails (cond-> {:message (str message)} trace (assoc :trace trace))))))

(defn- run-steps [defs dir pk]
  (loop [[st & more] (:steps pk) ctx {} out [] failure nil]
    (if-not st
      {:steps out :failure failure}
      (if failure
        (let [t (now)]
          (recur more ctx (conj out {:name (:name st) :status "skipped" :stage "finished" :start t :stop t :attachments []})
                 failure))
        (let [start (now)
              atts (atom [])
              r (binding [*results-dir* dir *attachments* atts] (run-step defs ctx st))
              step (cond-> {:name (:name st) :status (:status r) :stage "finished" :start start :stop (now)
                            :attachments @atts}
                     (:message r) (assoc :statusDetails (cond-> {:message (:message r)} (:trace r) (assoc :trace (:trace r)))))]
          (recur more (:ctx r) (conj out step) (when (not= "passed" (:status r)) r)))))))

(defn run-pickle
  "Runs the pickle `pk` against `defs`, writing its result into `dir`;
  returns the result."
  [defs dir pk]
  (let [start (now)
        ran (if (:wip? pk)
              {:status "skipped" :message "planned: the scenario is tagged @wip, so it is not run"
               :steps (vec (for [st (:steps pk)] {:name (:name st) :status "skipped" :stage "finished"
                                                  :start start :stop start :attachments []}))}
              (let [{:keys [steps failure]} (run-steps defs dir pk)]
                (merge {:status "passed" :steps steps} (select-keys failure [:status :message :trace]))))
        r (result-of pk (assoc ran :start start :stop (now)))]
    (fs/create-dirs dir)
    (spit (str (fs/file dir (str (:uuid r) "-result.json"))) (json/generate-string r {:pretty true}))
    r))

(defn run-pickles!
  "Runs every pickle against the registered definitions, writing results
  into `dir` and calling (on-result pickle result) after each: [[pickle
  result]]."
  [pickles dir on-result]
  (let [defs (definitions)]
    (vec (for [pk pickles]
           (let [r (run-pickle defs (str dir) pk)]
             (when on-result (on-result pk r))
             [pk r])))))

;; ---------------------------------------------------------------------------
;; The task: rdm-spec run

(defn run
  "The stage of `rdm-spec run [--results DIR]`."
  [req]
  (let [root (:root req)
        spec-dir (command/config-value req :spec-dir)
        {:keys [pickles refusals features]} (plan root {:spec-dir spec-dir
                                                        :id-prefix (command/config-value req :id-prefix)
                                                        :model (command/config-value req :model)})
        _ (when (zero? features)
            (command/cannot-run! (str "no scenario was found: " spec-dir " holds no feature file, and a run that"
                                      " finds nothing is refused")))
        steps-dir (command/path req (command/config-value req :steps-dir))
        out (str (command/path req (or (not-empty (get-in req [:opts :results]))
                                      (not-empty (get (:env req) "RESULTS_DIR"))
                                      (command/config-value req :results-dir))))]
    (when (fs/directory? steps-dir)
      (cp/add-classpath (str (fs/parent (fs/absolutize steps-dir)))))
    (load-step-files! (step-files steps-dir))
    (let [runs (run-pickles! pickles out nil)
          by (frequencies (map (comp :status second) runs))]
      {:result {:run {:results out :scenarios (count runs) :statuses by}}
       :findings (vec (concat
                       (for [r refusals] (command/finding (:file r) (:line r) (:rule r) (:message r)))
                       (for [[pk r] runs :when (#{"failed" "broken"} (:status r))]
                         (command/finding (:file pk) (or (get-in pk [:row :line]) (:line pk))
                                          (str "scenario-" (:status r))
                                          (str (or (:id pk) "an unstamped scenario") " \"" (:display-name pk) "\" "
                                               (:status r) ": " (get-in r [:statusDetails :message]))))))})))
