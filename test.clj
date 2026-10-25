;; The builder's tests (dhf/harness/config.edn :test-command, `bb test.clj`).
;;
;; Runs (a) every unit test namespace under test/ (files *_test.clj, found by
;; looking, never listed; test/steps excluded) and (b) every scenario of the
;; spec with the Gherkin runner against the step definitions under the
;; configured :steps-dir. Writes Allure results into $RESULTS_DIR, else the
;; configured :results-dir, after clearing earlier results there. Unit test
;; results carry no spec label, so they verify no scenario. Exits 0 only when
;; every unit test passed and no scenario failed or is broken (a planned,
;; @wip scenario is skipped and fails nothing); a scenario whose steps have no
;; definition yet is broken.
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

(def root (str (fs/parent (fs/absolutize *file*))))
(cp/add-classpath (str (fs/file root "src") java.io.File/pathSeparator (fs/file root "test")))

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :as t]
         '[rdm-spec.kernel.command :as command]
         '[rdm-spec.kernel.runner :as runner])

(def config (command/read-config root))
(def results-dir
  (str (command/path root (or (not-empty (System/getenv "RESULTS_DIR")) (:results-dir config)))))
(fs/create-dirs results-dir)
(runner/clean-results! results-dir)

;; --- (a) unit tests -------------------------------------------------------

(defn unit-test-namespaces []
  (let [test-dir (fs/file root "test")
        steps (str (fs/absolutize (command/path root (:steps-dir config))))]
    (->> (fs/glob test-dir "**_test.clj")
         (map fs/absolutize)
         (remove #(str/starts-with? (str %) steps))
         (map #(-> (str (fs/relativize test-dir %))
                   (str/replace #"\.clj$" "")
                   (str/replace "/" ".")
                   (str/replace "_" "-")
                   symbol))
         sort)))

(defn run-unit-tests []
  (let [outcomes (atom [])
        current (atom nil)]
    (doseq [n (unit-test-namespaces)]
      (require n)
      (binding [t/report (fn [m]
                           (case (:type m)
                             :begin-test-var (reset! current {:var (:var m) :start (System/currentTimeMillis)
                                                              :fails []})
                             :end-test-var (swap! outcomes conj (assoc @current :stop (System/currentTimeMillis)))
                             (:fail :error) (do (swap! current update :fails conj m)
                                                (binding [*out* *err*] (t/with-test-out (println "FAIL" (:var @current)))
                                                  (println (pr-str (dissoc m :actual)) " actual:" (pr-str (:actual m)))))
                             nil))]
        (t/test-ns n)))
    (doseq [{:keys [var start stop fails]} @outcomes]
      (let [m (meta var)
            status (cond (some #(= :error (:type %)) fails) "broken"
                         (seq fails) "failed"
                         :else "passed")]
        (runner/write-result!
         results-dir
         {:uuid (str (java.util.UUID/randomUUID))
          :historyId (str (:ns m) "/" (:name m))
          :name (str (:name m))
          :fullName (str (:ns m) "/" (:name m))
          :status status
          :statusDetails (if (seq fails)
                           {:message (str/join "\n" (map #(str (or (:message %) "") " expected: " (pr-str (:expected %))
                                                               " actual: " (pr-str (:actual %))) fails))}
                           {})
          :stage "finished" :start start :stop stop
          :labels [{:name "suite" :value "unit"} {:name "package" :value (str (:ns m))}
                   {:name "framework" :value "clojure.test"}]
          :steps []})))
    @outcomes))

;; --- (b) scenarios --------------------------------------------------------

(defn run-scenarios []
  (runner/run-features {:root root
                        :spec-dir (:spec-dir config)
                        :steps-dir (:steps-dir config)
                        :results-dir results-dir
                        :id-prefix (:id-prefix config)
                        :model (:model config)
                        :clean? false}))

(let [units (run-unit-tests)
      unit-failed (filter #(seq (:fails %)) units)
      sc (run-scenarios)
      results (:results sc)
      summary (runner/summarize results)]
  (println)
  (println "Unit tests:" (count units) "run," (count unit-failed) "failed")
  (doseq [u unit-failed] (println "  FAILED" (:var u)))
  (when (:refused sc) (println "Scenarios refused:" (:refused sc)))
  (doseq [r (:refusals sc)] (println "  REFUSED" (str (:file r) ":" (:line r) ": " (:rule r) ": " (:message r))))
  (println "Scenarios:" summary)
  (doseq [r results :when (#{"failed" "broken"} (:status r))]
    (println (str "  " (str/upper-case (:status r)) " " (str/join "," (:ids r)) " " (:file r) ":" (:line r) " " (:name r))
             (str "\n      " (:message r))))
  (println "Results written to" results-dir)
  (System/exit (if (or (seq unit-failed) (:refused sc) (seq (:refusals sc))
                       (pos? (:failed summary 0)) (pos? (:broken summary 0)))
                 1 0)))
