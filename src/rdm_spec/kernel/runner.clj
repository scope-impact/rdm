(ns rdm-spec.kernel.runner
  "The Gherkin runner (dhf/spec/kernel/runner.feature): runs every scenario
  the spec holds, found by looking, against the step definitions in the
  configured step directory, and writes one Allure result per scenario (per
  outline row), in Allure's own model, filled from the Gherkin scenario.

  The step API, a public contract:
    (defstep #\"pattern\" (fn [ctx & captured] ctx'))
    (attach! name content) or (attach! name content type)
    (fail! message), or assert: a Then that does not hold is failed;
    any other error is broken.
  Each scenario starts with an empty context; a step's return is the next
  step's context, unless it is not a map. (cleanup! f) runs f when the
  scenario ends, however it ends."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

;; ---------------------------------------------------------------------------
;; The step API

(defonce registry (atom []))

(defn register!
  "Adds a step definition; the same pattern from the same namespace replaces it."
  [pattern f where]
  (swap! registry (fn [defs]
                    (let [k [(str pattern) where]]
                      (conj (vec (remove #(= k [(str (:pattern %)) (:where %)]) defs))
                            {:pattern pattern :f f :where where})))))

(defmacro defstep [pattern f]
  `(register! ~pattern ~f ~(str (ns-name *ns*) (when *file* (str " (" (fs/file-name *file*) ")")))))

(defn fail!
  "Says a Then's assertion did not hold."
  [message]
  (throw (ex-info (str message) {::failed true})))

(def ^:dynamic *step* nil)
(def ^:dynamic *scenario* nil)

(def ^:private extensions {"text/plain" "txt" "application/json" "json" "text/html" "html" "image/png" "png"
                           "image/svg+xml" "svg" "text/csv" "csv" "application/zip" "zip"})

(defn attach!
  "Attaches evidence to the running step, as a file beside the result."
  ([name content] (attach! name content "text/plain"))
  ([name content type]
   (when-let [{:keys [dir attachments]} *step*]
     (let [f (str (java.util.UUID/randomUUID) "-attachment." (get extensions type "txt"))]
       (fs/create-dirs dir)
       (if (bytes? content)
         (fs/write-bytes (fs/path dir f) content)
         (spit (fs/file dir f) (str content)))
       (swap! attachments conj {"name" (str name) "source" f "type" type})))
   nil))

(defn cleanup!
  "Runs f when the running scenario ends."
  [f]
  (when-let [c (:cleanups *scenario*)] (swap! c conj f))
  nil)

;; ---------------------------------------------------------------------------
;; Matching and judging

(defn matches
  "The definitions whose pattern matches the whole step text, with what they captured."
  [defs text]
  (for [d defs
        :let [m (re-matches (:pattern d) text)]
        :when m]
    (assoc d :captured (if (string? m) [] (vec (rest m))))))

(defn kinds
  "Each step's kind: And and But take the kind of the step before."
  [keywords]
  (rest (reductions (fn [prev k] (if (#{"And" "But" "*"} k) prev k)) "Given" keywords)))

(defn- assertion? [e]
  (or (instance? AssertionError e) (::failed (ex-data e))))

(defn status-of-error
  "failed for a Then whose assertion did not hold; broken for anything else."
  [kind e]
  (if (and (= "Then" kind) (assertion? e)) "failed" "broken"))

(defn parameters [headings row]
  (mapv (fn [h v] {"name" h "value" v}) headings row))

(defn history-id [id row]
  (str/join "|" (cons id row)))

(defn fill
  "A text with each <heading> replaced by the row's value."
  [text values]
  (when text
    (reduce (fn [t [h v]] (str/replace t (str "<" h ">") v)) text values)))

(defn- trace-of [^Throwable e]
  (let [sw (java.io.StringWriter.)]
    (.printStackTrace e (java.io.PrintWriter. sw))
    (str sw)))

(defn- now [] (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; Running a scenario

(defn- run-step
  "Runs one step: [ctx' allure-step error-status error-message error]."
  [defs ctx {kw :keyword :keys [text doc table]} kind dir]
  (let [start (now)
        attachments (atom [])
        name (str kw " " text)
        found (matches defs text)
        done (fn [status & [msg e]]
               [ctx {"name" name "status" status "stage" "finished" "start" start "stop" (now)
                     "attachments" @attachments "steps" [] "parameters" []}
                (when-not (= "passed" status) status) msg e])]
    (cond
      (empty? found)
      (done "broken" (str "no step definition matches the step \"" name "\""))
      (> (count found) 1)
      (done "broken" (str "the step \"" name "\" is matched by " (count found) " step definitions: "
                          (str/join " and " (map #(str "#\"" (:pattern %) "\" in " (:where %)) found))))
      :else
      (let [d (first found)
            args (cond-> (:captured d) doc (conj doc) table (conj table))]
        (try
          (let [r (binding [*step* {:dir dir :attachments attachments}] (apply (:f d) ctx args))
                ctx' (if (map? r) r ctx)]
            (assoc (done "passed") 0 ctx'))
          (catch Throwable e
            (let [st (status-of-error kind e)]
              (done st (str name ": " (ex-message e)) e))))))))

(defn needs-file [config] (:needs config "dhf/spec/needs.edn"))

(defn labels-of
  "The Allure labels of a scenario."
  [s]
  (let [ctx-id (some-> (:context s) name)
        comp-id (some-> (:component s) str (subs 1))]
    (vec (concat
          [{"name" "feature" "value" (str (:feature s))}]
          (when (:rule s) [{"name" "story" "value" (:rule s)}])
          (when ctx-id [{"name" "epic" "value" ctx-id} {"name" "suite" "value" ctx-id}])
          (when comp-id [{"name" "subSuite" "value" comp-id}])
          (for [t (:all-tags s)] {"name" "tag" "value" t})
          [{"name" "framework" "value" "gherkin"}]
          (when (:id s) [{"name" "spec" "value" (:id s)}])
          (when ctx-id [{"name" "context" "value" ctx-id}])
          (when (:component-name s) [{"name" "component" "value" (:component-name s)}])
          (when (:wip? s) [{"name" "planned" "value" "true"}])))))

(defn links-of [s config]
  (vec (for [n (:needs s)] {"type" "issue" "name" n "url" (str (needs-file config) "#" n)})))

(defn rows-of
  "Each run of a scenario: [nil] for a scenario, [{:headings :row}] for each outline row."
  [s]
  (if (:outline? s)
    (vec (for [ex (:examples s) row (:rows ex)] {:headings (:headings ex) :row row}))
    [nil]))

(defn run-scenario
  "Runs one scenario, or one row of an outline: its Allure result."
  [defs s row config dir]
  (let [uuid (str (java.util.UUID/randomUUID))
        values (when row (zipmap (:headings row) (:row row)))
        steps (for [st (concat (:background s) (:steps s))]
                (cond-> (update st :text fill values)
                  (:doc st) (update :doc fill values)
                  (:table st) (update :table (fn [t] (mapv #(mapv (fn [c] (fill c values)) %) t)))))
        kinds (kinds (map :keyword steps))
        start (now)
        cleanups (atom [])
        [status details allure-steps]
        (if (:wip? s)
          ["skipped" nil (vec (for [st steps] {"name" (str (:keyword st) " " (:text st)) "status" "skipped" "stage" "finished"
                                               "attachments" [] "steps" [] "parameters" []}))]
          (binding [*scenario* {:cleanups cleanups}]
            (try
              (loop [ctx {} todo (map vector steps kinds) out [] bad nil]
                (if-let [[st kind] (first todo)]
                  (if bad
                    (recur ctx (rest todo) (conj out {"name" (str (:keyword st) " " (:text st)) "status" "skipped" "stage" "finished"
                                                      "start" (now) "stop" (now) "attachments" [] "steps" [] "parameters" []})
                           bad)
                    (let [[ctx' a status msg e] (run-step defs ctx st kind dir)]
                      (recur ctx' (rest todo) (conj out a)
                             (when status [status (cond-> {"message" msg} e (assoc "trace" (trace-of e)))]))))
                  [(or (first bad) "passed") (second bad) out]))
              (finally
                (doseq [f (reverse @cleanups)] (try (f) (catch Throwable _ nil)))))))]
    (cond-> {"uuid" uuid
             "historyId" (history-id (or (:id s) (str (:file s) ":" (:title s))) (:row row))
             "name" (:title s)
             "fullName" (str (:file s) ":" (:title s))
             "status" status
             "stage" "finished"
             "start" start
             "stop" (now)
             "steps" allure-steps
             "attachments" []
             "parameters" (if row (parameters (:headings row) (:row row)) [])
             "labels" (labels-of s)
             "links" (links-of s config)}
      details (assoc "statusDetails" details))))

;; ---------------------------------------------------------------------------
;; A suite

(defn load-steps!
  "Loads every step definition file in the step directory."
  [root steps-dir]
  (let [d (fs/absolutize (fs/path root steps-dir))]
    (when (fs/directory? d)
      (cp/add-classpath (str (fs/parent d)))
      (doseq [f (sort (map str (fs/glob d "*.clj")))]
        (let [form (try (first (edn/read-string {:default tagged-literal} (str "[" (slurp f) "]"))) (catch Exception _ nil))]
          (if (and (seq? form) (= 'ns (first form)))
            (require (second form))
            (load-file f)))))))

(defn- parser [f] (requiring-resolve (symbol "rdm-spec.spec.parser" (name f))))

(defn scenarios
  "Every scenario of the spec (each spec the configuration keeps), the
  feature files under paths only when paths are given."
  [root config paths]
  (let [norm #(str/replace (str %) #"^\./|/$" "")
        ps (map norm paths)]
    (vec (for [sc (cmd/spec-configs config)
               s (:scenarios ((parser 'read-spec) root nil sc))
               :when (or (empty? ps) (some #(or (= % (:file s)) (str/starts-with? (:file s) (str % "/"))) ps))]
           (assoc s ::config sc)))))

(defn run-scenarios
  "Runs the scenarios on `parallel` threads, writing a result for each into
  dir: the results, as {:file :line :id :status :message}."
  [defs ss dir parallel]
  (fs/create-dirs dir)
  (let [jobs (for [s ss row (rows-of s)] [s row])
        pool (java.util.concurrent.Executors/newFixedThreadPool (max 1 (or parallel 1)))
        run (fn [[s row]]
              (let [r (run-scenario defs s row (::config s) (str dir))]
                (spit (fs/file dir (str (get r "uuid") "-result.json")) (json/generate-string r))
                {:file (:file s) :line (:line s) :id (:id s) :title (:title s) :status (get r "status")
                 :message (get-in r ["statusDetails" "message"])}))]
    (try
      (let [futures (mapv (fn [job] (.submit pool ^java.util.concurrent.Callable (fn [] (run job)))) jobs)]
        (mapv #(.get ^java.util.concurrent.Future %) futures))
      (finally (.shutdown pool)))))

(defn- read-config [root]
  (let [f (fs/file root "dhf/harness/config.edn")]
    (if (fs/exists? f) (edn/read-string (slurp f)) {})))

(defn- run! [root config results-dir paths parallel]
  (let [ss (scenarios root config paths)]
    (when (empty? ss)
      (cmd/cannot-run! (str "no scenario was found: the spec " (:spec-dir config "dhf/spec") " holds no feature file with a scenario")))
    (reset! registry [])
    (load-steps! root (:steps-dir config "test/steps"))
    (run-scenarios @registry ss (fs/absolutize (fs/path root results-dir)) parallel)))

(defn- counts [outcomes]
  (merge {:passed 0 :failed 0 :broken 0 :skipped 0} (frequencies (map (comp keyword :status) outcomes))))

(defn run-suite!
  "Runs the suite for the tests: prints each scenario that did not pass and a
  summary line; true when none failed or broke."
  [{:keys [root paths results-dir parallel]}]
  (let [root (str (fs/canonicalize root))
        config (read-config root)
        outcomes (try (run! root config results-dir paths parallel)
                      (catch clojure.lang.ExceptionInfo e
                        (binding [*out* *err*] (println (str "cannot-run: " (ex-message e))))
                        nil))]
    (if (nil? outcomes)
      false
      (let [c (counts outcomes)]
        (doseq [o outcomes :when (#{"failed" "broken"} (:status o))]
          (binding [*out* *err*]
            (println (str (:file o) ":" (:line o) ": " (:status o) ": " (or (:id o) "unstamped") " " (:title o) ": "
                          (cmd/one-line (:message o))))))
        (println (format "scenarios: %d run, %d passed, %d failed, %d broken, %d planned"
                         (count outcomes) (:passed c) (:failed c) (:broken c) (:skipped c)))
        (zero? (+ (:failed c) (:broken c)))))))

(defn stage
  "rdm-spec run: the project's scenarios against its step definitions."
  [{:keys [root config] :as ctx}]
  (let [dir (cmd/results-dir ctx)
        outcomes (run! root config dir nil 1)
        c (counts outcomes)]
    {:result {:run {:results dir :scenarios (count outcomes) :passed (:passed c) :failed (:failed c)
                    :broken (:broken c) :planned (:skipped c)}}
     :findings (vec (for [o outcomes :when (#{"failed" "broken"} (:status o))]
                      (cmd/finding (:file o) (:line o) (str "scenario-" (:status o))
                                   (str (or (:id o) "an unstamped scenario") " " (:status o) ": " (:message o)))))}))
