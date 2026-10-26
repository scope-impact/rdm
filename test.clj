;; Runs every test of the implementation: the unit tests under test/ and the
;; scenarios of dhf/spec through the kernel's Gherkin runner, which writes
;; Allure results into $RESULTS_DIR (else the configured results directory).
;;   bb test.clj [--no-unit] [--no-scenarios] [PATH ...]
(require '[babashka.classpath :refer [add-classpath]]
         '[babashka.fs :as fs]
         '[clojure.string :as str])
(add-classpath "src")
(add-classpath "test")
(require '[clojure.test :as t])

(def args *command-line-args*)
(def no-unit? (some #{"--no-unit"} args))
(def no-scenarios? (some #{"--no-scenarios"} args))
(def paths (remove #(str/starts-with? % "--") args))

(defn unit-namespaces []
  (->> (fs/glob "test/rdm_spec" "**_test.clj")
       (map #(-> (str (fs/relativize "test" %))
                 (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-") symbol))
       sort))

(def unit-ok?
  (if (or no-unit? (seq paths))
    true
    (let [nss (unit-namespaces)]
      (apply require nss)
      (let [{:keys [test pass fail error]} (apply t/run-tests nss)]
        (println (format "unit: %d tests, %d assertions, %d failures, %d errors" test (+ pass fail error) fail error))
        (zero? (+ fail error))))))

(def scenarios-ok?
  (if no-scenarios?
    true
    (do (require 'rdm-spec.kernel.runner)
        ((resolve 'rdm-spec.kernel.runner/run-suite!)
         {:root "." :paths paths
          :results-dir (or (System/getenv "RESULTS_DIR") "dhf/allure-results")
          :parallel (parse-long (or (System/getenv "TEST_THREADS") "4"))}))))

(System/exit (if (and unit-ok? scenarios-ok?) 0 1))
