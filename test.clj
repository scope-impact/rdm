#!/usr/bin/env bb
;; Runs every test of rdm-spec: the unit tests under test/rdm_spec/ and the
;; scenarios of the spec, through the kernel's Gherkin runner, against the step
;; definitions under test/steps/, writing Allure results to $RESULTS_DIR (else
;; the configured results directory).
;;
;;   bb test.clj                         everything
;;   bb test.clj --no-unit DIR ...       only the scenarios of the feature files under DIR
;;   bb test.clj --unit-only             only the unit tests
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.test :as t])

(def root (str (fs/parent (fs/absolutize *file*))))
(cp/add-classpath (str root "/src" java.io.File/pathSeparator root "/test"))

(def args *command-line-args*)
(def no-unit? (some #{"--no-unit"} args))
(def unit-only? (some #{"--unit-only"} args))
(def only (vec (remove #(str/starts-with? % "--") args)))

(defn run-unit []
  (let [files (sort (map str (fs/glob root "test/rdm_spec/**_test.clj")))
        nss (doall (for [f files]
                     (let [rel (str (fs/relativize (fs/path root "test") f))]
                       (symbol (-> rel (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-"))))))]
    (doseq [n nss] (require n))
    (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
      (println (format "unit: %d tests, %d assertions, %d failures, %d errors" test (+ pass fail error) fail error))
      (zero? (+ fail error)))))

(defn run-scenarios []
  (require 'rdm-spec.kernel.project 'rdm-spec.spec.parser 'rdm-spec.kernel.runner)
  (let [cfg ((resolve 'rdm-spec.kernel.project/read-config) root)
        reading ((resolve 'rdm-spec.spec.parser/read-specs) root cfg)
        scenarios (for [sc (mapcat :scenarios (:specs reading))
                        :when (or (empty? only) (some #(str/starts-with? (:file sc) (str/replace % #"/$" "")) only))]
                    sc)
        results (or (System/getenv "RESULTS_DIR") (str root "/" (:results-dir cfg)))
        defs ((resolve 'rdm-spec.kernel.runner/load-steps!) (str root "/test/steps"))
        _ (when-let [r (resolve 'steps.kernel-steps/root)] (reset! @r root))
        cleanup (resolve 'steps.kernel-steps/cleanup!)
        summary ((resolve 'rdm-spec.kernel.runner/run)
                 {:scenarios scenarios :results-dir results :steps defs :needs (:needs cfg)
                  :after-scenario (when cleanup @cleanup)
                  :on-result (fn [sc _ r]
                               (when-not (#{"passed" "skipped"} (get r "status"))
                                 (println (str "  " (get r "status") ": " (:id sc) " " (:file sc) ":" (:line sc) " "
                                               (get r "historyId") "\n      " (get-in r ["statusDetails" "message"])))))})]
    (println (format "scenarios: %d scenarios, %d results: %d passed, %d failed, %d broken, %d skipped"
                     (:scenarios summary) (:results summary) (:passed summary) (:failed summary) (:broken summary) (:skipped summary)))
    (zero? (+ (:failed summary) (:broken summary)))))

(let [unit-ok (if no-unit? true (run-unit))
      scen-ok (if unit-only? true (run-scenarios))]
  (shutdown-agents)
  (System/exit (if (and unit-ok scen-ok) 0 1)))
