;; rdm-spec's builder's tests (dhf/harness/stack.md; the configuration's
;; :test-command): every unit test under test/ and every scenario of the spec,
;; each writing an Allure result into $RESULTS_DIR (else the configured
;; :results-dir). Exits 0 only when every unit test passed and every scenario
;; not tagged @wip passed.
;;
;;   bb test.clj                     everything
;;   bb test.clj [--no-unit] [--no-scenarios] [PATH ...]
;;                                   a part, while building: PATH limits the
;;                                   scenarios to feature files under it
;;
;; Every fixture of a run (the step definitions', the unit tests', and the
;; temporary files of the commands they run) lies under one directory, made
;; here and removed when the run ends: the tests run in a child process whose
;; temporary directory it is (bb ignores JAVA_TOOL_OPTIONS, so it is given as
;; -Djava.io.tmpdir) and which steps.support names (RDM_SPEC_FIXTURES).

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.test :as t])

(def root (str (fs/parent (fs/absolutize *file*))))

(def fixtures-var "RDM_SPEC_FIXTURES")

(defn- bb-exe []
  (or (try (let [exe (str (fs/read-link "/proc/self/exe"))] (when (re-find #"(^|/)bb$" exe) exe))
           (catch Exception _ nil))
      (some-> (fs/which "bb") str)
      "bb"))

;; ---------------------------------------------------------------------------
;; The parent: one fixture directory per run, removed when the run ends

(defn- parent! []
  (let [run-dir (str (fs/create-temp-dir {:prefix "rdm-spec-test-run-"}))
        code (try
               (-> (p/process (concat [(bb-exe) (str "-Djava.io.tmpdir=" run-dir)
                                       "-cp" (str root "/src" java.io.File/pathSeparator root "/test")
                                       (str root "/test.clj")]
                                      *command-line-args*)
                              {:inherit true :dir root
                               :extra-env {fixtures-var run-dir "TMPDIR" run-dir
                                           ;; every fixture lies under run-dir, which may lie in a git
                                           ;; repository: git never looks above a fixture (README, "A
                                           ;; fixture inside another git repository is fenced from it")
                                           "GIT_CEILING_DIRECTORIES"
                                           (str/join java.io.File/pathSeparator
                                                     (remove str/blank? [run-dir (System/getenv "GIT_CEILING_DIRECTORIES")]))}})
                   deref
                   :exit)
               (finally
                 (fs/delete-tree run-dir)))]
    (System/exit code)))

;; ---------------------------------------------------------------------------
;; The child: the tests

(defn- config []
  (let [f (fs/file root "dhf/harness/config.edn")]
    (if (fs/exists? f) (edn/read-string (slurp (str f))) {})))

(defn- results-dir [cfg]
  (let [d (or (not-empty (System/getenv "RESULTS_DIR")) (:results-dir cfg) "dhf/allure-results")]
    (str (if (fs/absolute? d) d (fs/file root d)))))

(defn- write-result! [dir r]
  (fs/create-dirs dir)
  (spit (str (fs/file dir (str (:uuid r) "-result.json"))) (json/generate-string r {:pretty true})))

(defn- say [& xs] (println (str/join " " xs)) (flush))

;; --- unit tests ------------------------------------------------------------------

(defn- unit-test-files [cfg]
  (let [steps (str (fs/file root (or (:steps-dir cfg) "test/steps")))]
    (vec (sort (for [f (map str (fs/glob (fs/file root "test") "**_test.clj"))
                     :when (not (str/starts-with? f (str steps "/")))]
                 f)))))

(defn- ns-of [f]
  (-> (str (fs/relativize (fs/file root "test") f))
      (str/replace #"\.clj$" "")
      (str/replace "/" ".")
      (str/replace "_" "-")
      symbol))

(defn- run-unit-tests!
  "Runs every unit test, writing a result for each: [{:name :status}]."
  [cfg out]
  (vec
   (for [f (unit-test-files cfg)
         :let [ns-sym (ns-of f)
               loaded (try (require ns-sym) nil (catch Throwable e e))]
         v (if loaded
             [::load-error]
             (sort-by (comp str symbol) (filter (comp :test meta) (vals (ns-interns ns-sym)))))]
     (let [start (System/currentTimeMillis)
           events (atom [])
           nm (if (= ::load-error v) (str ns-sym " (loading)") (str (symbol v)))
           _ (when-not (= ::load-error v)
               (binding [t/report (fn [m]
                                    (when (#{:fail :error} (:type m))
                                      (swap! events conj m)
                                      (binding [t/*test-out* *out*] (t/with-test-out ((get-method t/report (:type m)) m)))))]
                 (try (t/test-vars [v])
                      (catch Throwable e (swap! events conj {:type :error :actual e})))))
           evs (if loaded [{:type :error :actual loaded}] @events)
           status (cond (some #(= :error (:type %)) evs) "broken"
                        (seq evs) "failed"
                        :else "passed")
           msg (when-let [e (first evs)]
                 (str (or (:message e) "")
                      (when (contains? e :expected) (str " expected: " (pr-str (:expected e))))
                      " actual: " (let [a (:actual e)] (if (instance? Throwable a) (str (class a) ": " (ex-message a)) (pr-str a)))))]
       (write-result! out {:uuid (str (random-uuid)) :name nm :fullName (str f)
                           :status status :statusDetails (if msg {:message msg} {}) :stage "finished"
                           :start start :stop (System/currentTimeMillis)
                           :labels [{:name "framework" :value "clojure.test"} {:name "suite" :value (str ns-sym)}]
                           :steps []})
       (when-not (= "passed" status) (say status "unit" nm))
       {:name nm :status status}))))

;; --- scenarios -------------------------------------------------------------------

(defn- run-scenarios!
  "Runs the scenarios (limited to `only` when given): {:runs :refusals :shadowed}."
  [cfg out only]
  (let [runner-ns 'rdm-spec.kernel.runner
        _ (require runner-ns)
        plan (resolve (symbol (str runner-ns) "plan"))
        load! (resolve (symbol (str runner-ns) "load-step-files!"))
        step-files (resolve (symbol (str runner-ns) "step-files"))
        run! (resolve (symbol (str runner-ns) "run-pickles!"))
        shadow-warnings (resolve (symbol (str runner-ns) "shadow-warnings"))
        format-finding (requiring-resolve 'rdm-spec.kernel.command/format-finding)
        steps-dir (str (fs/file root (or (:steps-dir cfg) "test/steps")))
        kernel-first (for [n ["support" "kernel_command" "kernel_bus" "kernel_runner"]
                           :let [f (str (fs/file steps-dir (str n ".clj")))]
                           :when (fs/exists? f)]
                       f)
        files (distinct (concat kernel-first (step-files steps-dir)))
        {:keys [pickles refusals features]} (plan root {:spec-dir (:spec-dir cfg) :id-prefix (:id-prefix cfg)
                                                        :model (:model cfg) :only only})]
    (load! files)
    (doseq [f refusals] (say "refused" (format-finding (assoc f :severity :error))))
    (doseq [w (shadow-warnings)] (say (format-finding w)))
    (say "scenarios:" (count pickles) "in" features "feature files")
    {:refusals refusals
     :features features
     :runs (run! pickles out
                 (fn [pk r]
                   (say (format "%-7s %-10s %s:%s  %s" (:status r) (or (:id pk) "-") (:file pk)
                                (or (get-in pk [:row :line]) (:line pk)) (:display-name pk)))
                   (when (#{"failed" "broken"} (:status r))
                     (say "        " (get-in r [:statusDetails :message])))))}))

(defn- child! []
  (let [args *command-line-args*
        unit? (not (some #{"--no-unit"} args))
        scenarios? (not (some #{"--no-scenarios"} args))
        only (vec (remove #(str/starts-with? % "--") args))
        cfg (config)
        out (results-dir cfg)
        _ (fs/create-dirs out)
        units (when unit? (run-unit-tests! cfg out))
        {:keys [runs refusals features]} (when scenarios? (run-scenarios! cfg out only))
        bad-units (remove #(= "passed" (:status %)) units)
        statuses (frequencies (map (comp :status second) runs))
        bad-runs (filter #(#{"failed" "broken"} (:status (second %))) runs)]
    (say)
    (when unit? (say "unit tests:" (count units) "run," (count bad-units) "not passed"))
    (when scenarios? (say "scenarios:" (count runs) "run," (pr-str statuses)))
    (say "results:" out)
    (System/exit (if (and (empty? bad-units) (empty? bad-runs) (empty? refusals)
                          (or (not scenarios?) (seq only) (pos? (or features 0))))
                   0 1))))

(if (System/getenv fixtures-var)
  (child!)
  (parent!))
