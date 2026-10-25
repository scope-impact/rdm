(ns rdm-spec.kernel.runner
  "The Gherkin runner (dhf/spec/kernel/runner.feature): runs the scenarios of
  a spec against their step definitions and writes one Allure result per
  scenario (one per example row of an outline), labelled with what it
  verifies.

  The step API, a public contract for any project run with `rdm-spec run`:

    (defstep #\"pattern\" (fn [ctx & captured] ctx'))

  A step's text (without its keyword) must match exactly one pattern, as a
  whole. The function gets the scenario's context and the pattern's groups
  (nil for a group that took no part), then the step's doc string or data
  table when it has one. What it returns is the next step's context; a step
  that returns something other than a map (a check that returns nil, say)
  leaves the context as it was. Every scenario starts from {}.

  Results: passed; failed when a step throws (naming the step and the error;
  the steps after it are reported skipped, as not run); broken when a step
  matches no definition, or more than one (naming them); skipped and
  labelled planned=true for a scenario tagged @wip, whose steps never run.

  Registering a pattern whose source is already registered by another file
  keeps the first definition and records the later one as shadowed, so one
  step text keeps one meaning; the run names each shadowed definition as a
  warning. A file registering its own pattern again (a reload) replaces it.

  Discovery: every *.feature under the configured :spec-dir and every *.clj
  under the configured :steps-dir, found by looking, never listed. A run that
  finds no feature file is refused.

  The task `rdm-spec run [--results DIR]` writes into the results directory
  (--results, else RESULTS_DIR, else the configured :results-dir). Its
  result: {:run {:results DIR :scenarios n :passed n :failed n :broken n
  :planned n}}; each failed or broken scenario is a finding (scenario-failed,
  scenario-broken) at its file and line."
  (:require [babashka.classpath :as cp]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; The step registry

(def ^:dynamic *registry*
  "An atom holding every step definition: [{:pattern :fn :file :line}]."
  (atom []))

(def ^:dynamic *shadowed*
  "An atom holding the definitions a registration did not keep, each with
  :kept, the definition it shadows."
  (atom []))

(defn- display-file [file]
  (let [f (str file)]
    (if (and (fs/absolute? f) (fs/starts-with? f (fs/cwd)))
      (str (fs/relativize (fs/cwd) f))
      f)))

(defn register!
  "Registers the step definition `pattern` -> `f`, defined at `file` and
  `line`. Returns the definition kept for the pattern."
  [pattern f file line]
  (let [entry {:pattern pattern :fn f :file (display-file file) :line line}
        src (str pattern)
        reg @*registry*
        i (first (keep-indexed (fn [i e] (when (= src (str (:pattern e))) i)) reg))]
    (cond
      (nil? i) (do (swap! *registry* (fn [r] (conj (vec r) entry))) entry)
      (= (:file (nth reg i)) (:file entry)) (do (swap! *registry* assoc i entry) entry)
      :else (let [kept (nth reg i)]
              (when-not (some #(and (= (:file %) (:file entry)) (= (:line %) (:line entry))) @*shadowed*)
                (swap! *shadowed* conj (assoc entry :kept (dissoc kept :fn))))
              kept))))

(defmacro defstep
  "Defines a step: `pattern` (a regex matched against the whole step text,
  without its keyword) and `f`, (fn [ctx & captured] ctx')."
  [pattern f]
  `(register! ~pattern ~f ~(str *file*) ~(:line (meta &form))))

(defn matching
  "Every definition whose pattern matches the step text `text`."
  [text]
  (filterv #(re-matches (:pattern %) (str text)) @*registry*))

(defn- where [d] (str (:file d) ":" (:line d)))

(defn shadow-warnings
  "A warning for every shadowed definition."
  []
  (vec (for [d @*shadowed*]
         (command/warning (:file d) (:line d) "step-shadowed"
                          (str "the step pattern " (pr-str (str (:pattern d))) " is already defined at "
                               (where (:kept d)) ", which is the one used; this definition is not")))))

;; ---------------------------------------------------------------------------
;; Loading step definition files

(defn- ns-of-file
  "The namespace symbol a Clojure file declares, or nil."
  [f]
  (try
    (let [form (with-open [r (java.io.PushbackReader. (io/reader (str f)))]
                 (loop []
                   (let [x (read {:eof ::eof :read-cond :allow} r)]
                     (cond (= ::eof x) nil
                           (and (seq? x) (= 'ns (first x))) x
                           :else (recur)))))]
      (when form (second form)))
    (catch Exception _ nil)))

(defn- classpath-root
  "The directory `f` lies under as the namespace `ns-sym`, or nil."
  [f ns-sym]
  (let [rel (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj")
        abs (str (fs/absolutize f))]
    (when (str/ends-with? abs (str "/" rel))
      (subs abs 0 (- (count abs) (count rel) 1)))))

(defn load-step-files!
  "Loads the step definition files `files`, in order: each by its namespace
  when it declares one (its directory put on the classpath, so step files
  can require one another), else by its path."
  [files]
  (let [loaded (atom #{})]
    (doseq [f files
            :let [ns-sym (ns-of-file f)
                  root (when ns-sym (classpath-root f ns-sym))]]
      (when (and root (not (@loaded root)))
        (cp/add-classpath root)
        (swap! loaded conj root))
      (if (and ns-sym root)
        (require ns-sym)
        (load-file (str f))))))

(defn step-files
  "Every *.clj under `dir`, sorted by path."
  [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/glob dir "**.clj"))))
    []))

(defn feature-files
  "Every *.feature under `dir`, sorted by path."
  [dir]
  (if (fs/directory? dir)
    (vec (sort (map str (fs/glob dir "**.feature"))))
    []))

;; ---------------------------------------------------------------------------
;; Who owns a feature file

(defn owners
  "{spec-path {:context :component}} by the model `m`'s names; the
  kernel's components belong to the context kernel."
  [m]
  (into {}
        (concat
         (for [c (:containers m) x (:contexts c) comp (:components x) :when (string? (:spec comp))]
           [(:spec comp) {:context (or (:name x) (some-> (:id x) name))
                          :component (or (:name comp) (some-> (:id comp) name))}])
         (for [comp (:kernel m) :when (string? (:spec comp))]
           [(:spec comp) {:context "kernel" :component (or (:name comp) (some-> (:id comp) name))}]))))

(defn owner-of
  "The owner of the feature file `rel` (relative to the root) under
  `spec-dir`: from `owner-map`, else from its path."
  [owner-map spec-dir rel]
  (let [prefix (str (str/replace (str spec-dir) #"/+$" "") "/")
        sp (when (str/starts-with? rel prefix) (str/replace (subs rel (count prefix)) #"\.feature$" ""))]
    (or (get owner-map sp)
        (when sp
          (let [parts (str/split sp #"/")]
            (when (= 2 (count parts))
              {:context (first parts) :component (second parts)}))))))

;; ---------------------------------------------------------------------------
;; Scenarios to run

(defn- id-re [prefix] (re-pattern (str "@" (java.util.regex.Pattern/quote (or prefix "spec")) "-\\d{4,}")))

(defn- substituted-step [step values]
  (cond-> (update step :text gherkin/substitute values)
    (:doc-string step) (update :doc-string gherkin/substitute values)
    (:data-table step) (update :data-table (fn [t] (mapv (fn [row] (mapv #(gherkin/substitute % values) row)) t)))))

(defn pickles
  "The runnable units of a parsed feature file: one per scenario, one per
  example row of an outline. `rel` names the file."
  [rel parsed {:keys [prefix owner]}]
  (vec
   (for [sc (:scenarios parsed)
         :let [ids (vec (distinct (map #(subs % 1) (filter #(re-matches (id-re prefix) %) (:tags sc)))))
               base {:file rel :line (:line sc) :name (:name sc) :ids ids
                     :id (when (= 1 (count ids)) (first ids))
                     :wip? (boolean (some #{"@wip"} (:all-tags sc)))
                     :feature (get-in parsed [:feature :name])
                     :owner owner}
               rows (if (= "Scenario Outline" (:keyword sc))
                      (for [ex (:examples sc) row (:rows ex)]
                        {:line (:line row) :pairs (mapv vector (:header ex) (:cells row))})
                      [nil])]
         row rows
         :let [values (into {} (:pairs row))]]
     (assoc base
            :row row
            :display-name (if row
                            (str (:name sc) " [" (str/join ", " (map (fn [[k v]] (str k "=" v)) (:pairs row))) "]")
                            (:name sc))
            :steps (mapv #(substituted-step % values) (concat (:background sc) (:steps sc)))))))

;; ---------------------------------------------------------------------------
;; Running one scenario

(defn- step-name [st] (str (:keyword st) " " (:text st)))

(defn- error-text [^Throwable e]
  (let [m (ex-message e)]
    (if (and m (instance? clojure.lang.ExceptionInfo e))
      m
      (str (.getName (class e)) (when m (str ": " m))))))

(defn- now [] (System/currentTimeMillis))

(defn run-steps
  "Runs `steps` from the context {}: {:status :message :steps [{:name :status
  :message :start :stop}]}."
  [steps]
  (loop [ctx {} [st & more] steps done [] outcome nil]
    (if (nil? st)
      {:status (or (:status outcome) "passed") :message (:message outcome) :steps done}
      (let [nm (step-name st)
            start (now)]
        (if outcome
          (recur ctx more (conj done {:name nm :status "skipped" :start start :stop start
                                      :message "not run: an earlier step did not pass"})
                 outcome)
          (let [ms (matching (:text st))]
            (case (count ms)
              0 (let [msg (str "no step definition matches the step \"" nm "\" (line " (:line st) ")")]
                  (recur ctx more (conj done {:name nm :status "broken" :start start :stop (now) :message msg})
                         {:status "broken" :message msg}))
              1 (let [d (first ms)
                      m (re-matches (:pattern d) (:text st))
                      args (concat (if (string? m) [] (rest m))
                                   (cond (contains? st :doc-string) [(:doc-string st)]
                                         (contains? st :data-table) [(:data-table st)]))
                      r (try {:ok (apply (:fn d) ctx args)}
                             (catch Throwable e {:error e}))]
                  (if-let [e (:error r)]
                    (let [msg (str "the step \"" nm "\" (line " (:line st) ") failed: " (error-text e))]
                      (recur ctx more (conj done {:name nm :status "failed" :start start :stop (now) :message msg})
                             {:status "failed" :message msg}))
                    (recur (if (map? (:ok r)) (:ok r) ctx) more
                           (conj done {:name nm :status "passed" :start start :stop (now)})
                           nil)))
              (let [msg (str "the step \"" nm "\" (line " (:line st) ") is matched by " (count ms) " step definitions: "
                             (str/join " and " (map #(str (where %) " " (pr-str (str (:pattern %)))) ms)))]
                (recur ctx more (conj done {:name nm :status "broken" :start start :stop (now) :message msg})
                       {:status "broken" :message msg})))))))))

(defn allure-result
  "The Allure result of a pickle and its outcome."
  [p {:keys [status message steps start stop]}]
  (let [labels (cond-> []
                 (:id p) (conj {:name "spec" :value (:id p)})
                 (get-in p [:owner :component]) (conj {:name "component" :value (get-in p [:owner :component])})
                 (get-in p [:owner :context]) (conj {:name "context" :value (get-in p [:owner :context])})
                 (:wip? p) (conj {:name "planned" :value "true"})
                 (:feature p) (conj {:name "feature" :value (:feature p)})
                 true (conj {:name "framework" :value "rdm-spec run"}))]
    (cond-> {:uuid (str (random-uuid))
             :historyId (str (:file p) ":" (or (get-in p [:row :line]) (:line p)))
             :name (:display-name p)
             :fullName (str (:file p) ":" (or (get-in p [:row :line]) (:line p)))
             :status status
             :statusDetails (if message {:message message} {})
             :stage "finished"
             :start start
             :stop stop
             :labels labels
             :steps (vec (for [s steps]
                           (cond-> {:name (:name s) :status (:status s) :stage "finished"
                                    :start (:start s) :stop (:stop s)}
                             (:message s) (assoc :statusDetails {:message (:message s)}))))}
      (:row p) (assoc :parameters (vec (for [[k v] (get-in p [:row :pairs])] {:name k :value v}))))))

(defn run-pickle
  "Runs one pickle (a planned one is not run): its Allure result."
  [p]
  (let [start (now)]
    (if (:wip? p)
      (allure-result p {:status "skipped" :message "planned (@wip): not run"
                        :steps (for [st (:steps p)] {:name (step-name st) :status "skipped" :start start :stop start})
                        :start start :stop start})
      (let [out (run-steps (:steps p))]
        (allure-result p (assoc out :start start :stop (now)))))))

(defn write-result!
  "Writes the Allure result `r` into `dir` as <uuid>-result.json."
  [dir r]
  (fs/create-dirs dir)
  (spit (str (fs/file dir (str (:uuid r) "-result.json"))) (json/generate-string r {:pretty true})))

(defn- read-model [f]
  (try (let [m (edn/read-string (slurp (str f)))] (when (map? m) m))
       (catch Exception _ nil)))

(defn plan
  "What a run of the spec under `root` would run: {:pickles [..] :refusals
  [..] :features n}. `opts`: :spec-dir :id-prefix :model (paths relative
  to `root`), :only (path prefixes, relative to `root`, a run is limited to)."
  [root {:keys [spec-dir id-prefix model only]}]
  (let [spec-dir (str/replace (or spec-dir "dhf/spec") #"/+$" "")
        files (feature-files (fs/file root spec-dir))
        rel #(str/replace (str (fs/relativize (fs/absolutize root) (fs/absolutize %))) "\\" "/")
        files (if (seq only) (filter (fn [f] (some #(str/starts-with? (rel f) %) only)) files) files)
        owner-map (owners (read-model (fs/file root (or model (str spec-dir "/model.edn")))))
        parsed (for [f files :let [r (rel f)]]
                 [r (gherkin/parse-feature r (slurp f))])]
    {:features (count files)
     :refusals (vec (mapcat (comp :refusals second) parsed))
     :pickles (vec (mapcat (fn [[r p]] (pickles r p {:prefix id-prefix :owner (owner-of owner-map spec-dir r)}))
                           parsed))}))

(defn summarize
  "Counts of the results `rs`."
  [rs]
  (let [planned? (fn [r] (some #(= {:name "planned" :value "true"} %) (:labels r)))]
    {:scenarios (count rs)
     :passed (count (filter #(= "passed" (:status %)) rs))
     :failed (count (filter #(= "failed" (:status %)) rs))
     :broken (count (filter #(= "broken" (:status %)) rs))
     :planned (count (filter planned? rs))}))

(defn run-pickles!
  "Runs every pickle, writing each result into `results-dir`, and calls
  `on-result` with each pickle and its result. Returns [[pickle result]]."
  [pickles results-dir on-result]
  (vec (for [p pickles]
         (let [r (run-pickle p)]
           (write-result! results-dir r)
           (when on-result (on-result p r))
           [p r]))))

(defn outcome-findings
  "A finding for every scenario run that failed or broke."
  [runs]
  (vec (for [[p r] runs
             :when (#{"failed" "broken"} (:status r))]
         (command/finding (:file p) (or (get-in p [:row :line]) (:line p)) (str "scenario-" (:status r))
                          (str (or (:id p) "a scenario with no single id") " (" (:display-name p) ") is "
                               (:status r) ": " (get-in r [:statusDetails :message]))))))

;; ---------------------------------------------------------------------------
;; The task

(defn run
  "The run task's stage: `rdm-spec run [--results DIR]`."
  [req]
  (let [root (:root req)
        spec-dir (command/config-value req :spec-dir)
        steps-dir (command/config-value req :steps-dir)
        {:keys [pickles refusals features]} (plan root {:spec-dir spec-dir
                                                        :id-prefix (command/config-value req :id-prefix)
                                                        :model (command/config-value req :model)})
        _ (when (zero? features)
            (command/cannot-run! (str "no scenario was found: the spec directory " spec-dir
                                      " holds no feature file, so nothing was run and no result was written")))
        results (:results req)
        results-dir (command/path req results)]
    (binding [*registry* (atom [])
              *shadowed* (atom [])]
      (load-step-files! (step-files (command/path req steps-dir)))
      (let [runs (run-pickles! pickles results-dir nil)
            rs (map second runs)]
        {:result {:run (assoc (summarize rs) :results results)}
         :findings (vec (concat (for [{:keys [file line rule message]} refusals]
                                  (command/finding file line rule message))
                                (outcome-findings runs)
                                (shadow-warnings)))}))))
