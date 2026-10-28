(ns rdm-spec.kernel.runner
  "The Gherkin runner: runs scenarios against step definitions written with
  one small API, and writes one Allure result per scenario (per outline row)
  in Allure's own model (dhf/spec/05-verify-by-results/runner.feature).

  The step API, a public contract:
    (defstep #\"pattern\" (fn [ctx & captured] ctx'))
    (attach! name content) / (attach! name content type)
    (fail! message)        ; an assertion that does not hold, as clojure's assert
  A step with a doc string or a data table receives it after its captured
  groups: the doc string as a string, the table as a vector of rows of cells."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; ------------------------------------------------------------------ the step API

(defonce registry (atom []))

(defn register! [re f where]
  (swap! registry (fn [defs] (conj (vec (remove #(and (= (str (:re %)) (str re)) (= (:where %) where)) defs))
                                   {:re re :fn f :where where})))
  nil)

(defmacro defstep
  "Defines a step by a pattern (matched against the whole step text) and a function of the context and the captured text."
  [re f]
  `(register! ~re ~f ~(str *file* ":" (:line (meta &form)))))

(def ^:dynamic *step* nil)
(def ^:dynamic *results-dir* nil)

(defn attach!
  "Attaches evidence to the running step, as an Allure attachment."
  ([name content] (attach! name content "text/plain"))
  ([name content type]
   (when (and *step* *results-dir*)
     (let [ext (case type "application/json" "json" "text/html" "html" "image/png" "png" "txt")
           source (str (random-uuid) "-attachment." ext)]
       (fs/create-dirs *results-dir*)
       (spit (str (fs/path *results-dir* source)) (str content))
       (swap! *step* update :attachments conj {"name" name "source" source "type" type})))
   nil))

(defn fail!
  "Fails the step: an assertion that did not hold."
  [message]
  (throw (AssertionError. (str message))))

;; ------------------------------------------------------------------ running

(defn- now [] (System/currentTimeMillis))

(defn- substitute [text values]
  (reduce (fn [t [k v]] (str/replace t (str "<" k ">") (str v))) text values))

(defn- matches [defs text]
  (keep (fn [d] (when-let [m (re-matches (:re d) text)]
                  (assoc d :captured (if (string? m) [] (vec (rest m))))))
        defs))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw))
    (let [t (str sw)] (if (> (count t) 4000) (subs t 0 4000) t))))

(defn- run-step
  "Runs one step; returns [step-result ctx outcome] where outcome is :passed, :failed or :broken."
  [defs ctx {kw :keyword text :text arg :arg} effective]
  (let [start (now)
        step (atom {:attachments []})
        name (str kw " " text)
        ms (matches defs text)
        done (fn [status details ctx']
               [(cond-> {"name" name "status" status "stage" "finished" "start" start "stop" (now)
                         "attachments" (:attachments @step) "steps" [] "parameters" []}
                  details (assoc "statusDetails" details))
                ctx' (keyword status)])]
    (cond
      (empty? ms)
      (done "broken" {"message" (str "no step definition matches: " name)} ctx)
      (> (count ms) 1)
      (done "broken" {"message" (str "the step \"" name "\" matches " (count ms) " definitions: "
                                     (str/join ", " (map #(str "#\"" (:re %) "\" at " (:where %)) ms)))}
            ctx)
      :else
      (let [{f :fn captured :captured} (first ms)
            args (cond-> captured
                   (:doc-string arg) (conj (:doc-string arg))
                   (:table arg) (conj (:table arg)))]
        (try
          (let [r (binding [*step* step] (apply f ctx args))]
            (done "passed" nil (if (map? r) r ctx)))
          (catch AssertionError e
            (if (= "Then" effective)
              (done "failed" {"message" (str (ex-message e)) "trace" (trace-of e)} ctx)
              (done "broken" {"message" (str "an assertion in a " effective " step: " (ex-message e)) "trace" (trace-of e)} ctx)))
          (catch Throwable e
            (done "broken" {"message" (str (or (ex-message e) (class e))) "trace" (trace-of e)} ctx)))))))

(defn- effective-keywords [steps]
  (rest (reductions (fn [prev {:keys [keyword]}] (if (#{"And" "But"} keyword) (or prev "Given") keyword)) nil steps)))

(defn- labels [sc]
  (let [ctx (some-> (:context sc) name)
        comp (:component sc)]
    (vec (concat
          [{"name" "feature" "value" (str (:feature sc))}]
          (when (:rule sc) [{"name" "story" "value" (:rule sc)}])
          (when ctx [{"name" "epic" "value" ctx} {"name" "suite" "value" ctx}])
          (when comp [{"name" "subSuite" "value" (str (namespace comp) "/" (name comp))}])
          (for [t (:tags sc)] {"name" "tag" "value" t})
          [{"name" "framework" "value" "gherkin"}]
          (when (:id sc) [{"name" "spec" "value" (:id sc)}])
          (when ctx [{"name" "context" "value" ctx}])
          (when (:component-name sc) [{"name" "component" "value" (:component-name sc)}])
          (when (:wip? sc) [{"name" "planned" "value" "true"}])))))

(defn- run-one
  "Runs one scenario (or one outline row); writes its result; returns its status."
  [defs needs-path results-dir sc row after-scenario]
  (let [values (:values row)
        steps (vec (for [s (concat (:background sc) (:steps sc))]
                     (update s :text substitute values)))
        effs (effective-keywords steps)
        start (now)
        uuid (str (random-uuid))
        [step-results status details]
        (if (:wip? sc)
          [(mapv (fn [s] {"name" (str (:keyword s) " " (:text s)) "status" "skipped" "stage" "finished"
                          "start" start "stop" start "attachments" [] "steps" [] "parameters" []}) steps)
           "skipped" {"message" "planned: tagged @wip, not run"}]
          (binding [*results-dir* results-dir]
            (try
              (loop [ss steps es effs ctx {} acc [] status "passed" details nil]
                (if (empty? ss)
                  [acc status details]
                  (if (not= status "passed")
                    (recur (rest ss) (rest es) ctx
                           (conj acc {"name" (str (:keyword (first ss)) " " (:text (first ss))) "status" "skipped"
                                      "stage" "finished" "start" (now) "stop" (now) "attachments" [] "steps" [] "parameters" []})
                           status details)
                    (let [[r ctx' outcome] (run-step defs ctx (first ss) (first es))]
                      (recur (rest ss) (rest es) ctx' (conj acc r)
                             (name outcome)
                             (when (not= outcome :passed)
                               (update (get r "statusDetails") "message"
                                       #(str "step " (inc (count acc)) ", \"" (get r "name") "\": " %))))))))
              (finally (when after-scenario (after-scenario))))))
        result (cond-> {"uuid" uuid
                        "historyId" (if row (str/join "|" (cons (:id sc) (map values (:order row)))) (str (:id sc)))
                        "name" (:title sc)
                        "fullName" (str (:file sc) ":" (:title sc))
                        "status" status "stage" "finished"
                        "start" start "stop" (now)
                        "steps" step-results
                        "parameters" (if row (vec (for [h (:order row)] {"name" h "value" (get values h)})) [])
                        "labels" (labels sc)
                        "links" (vec (for [n (:needs sc)] {"type" "issue" "name" n "url" (str needs-path "#" n)}))
                        "attachments" []}
                 details (assoc "statusDetails" details))]
    (fs/create-dirs results-dir)
    (spit (str (fs/path results-dir (str uuid "-result.json"))) (json/generate-string result {:pretty true}))
    result))

(defn run
  "Runs the scenarios (SpecParsed's shape) against the step definitions;
  writes one Allure result per scenario and per outline row into results-dir.
  Returns {:scenarios :results :passed :failed :broken :skipped}."
  [{:keys [scenarios results-dir steps needs after-scenario on-result]}]
  (let [defs (or steps @registry)
        statuses (doall
                  (for [sc scenarios
                        row (if (and (:outline? sc) (seq (:rows sc))) (:rows sc) [nil])]
                    (let [r (run-one defs (or needs "dhf/spec/needs.edn") results-dir sc row after-scenario)]
                      (when on-result (on-result sc row r))
                      (get r "status"))))
        fr (frequencies statuses)]
    {:scenarios (count scenarios) :results (count statuses)
     :passed (fr "passed" 0) :failed (fr "failed" 0) :broken (fr "broken" 0) :skipped (fr "skipped" 0)}))

(defn load-steps!
  "Loads every step definition file under dir (sorted), afresh."
  [dir]
  (reset! registry [])
  (doseq [f (sort (map str (fs/glob dir "**.clj")))]
    (load-file f))
  @registry)

;; ------------------------------------------------------------------ the run task

(defn refuse-empty!
  "A run that finds no scenario is refused: a suite found by looking that finds nothing is no suite."
  [scenarios]
  (when (empty? scenarios)
    (throw (ex-info "no scenario was found: the spec holds no feature file with a scenario, so nothing was run"
                    {:rdm-spec/exit 2}))))

(defn run-task
  "rdm-spec run: discovers every feature file of the spec, loads the step
  definitions of the configured step directory, runs every scenario and writes
  the results into --results, else RESULTS_DIR, else the configured directory."
  [task]
  (let [root (:root task) cfg (:cfg task)
        reading ((requiring-resolve 'rdm-spec.spec.parser/read-specs) root cfg)
        scenarios (vec (mapcat :scenarios (:specs reading)))
        _ (refuse-empty! scenarios)
        dir ((requiring-resolve 'rdm-spec.kernel.command/results-dir) task)
        abs (str (fs/absolutize (fs/path root dir)))
        steps-dir (fs/path root (:steps-dir cfg))
        defs (if (fs/directory? steps-dir) (load-steps! (str steps-dir)) [])
        summary (run {:scenarios scenarios :results-dir abs :steps defs :needs (:needs cfg)})]
    {:result {:run (assoc summary :results dir)}
     :exit (if (pos? (+ (:failed summary) (:broken summary))) 1 0)
     :findings (vec (for [[k n] [[:failed (:failed summary)] [:broken (:broken summary)]] :when (pos? n)]
                      {:file nil :line nil :rule (str "results-" (name k)) :message (str n " results " (name k))}))}))
