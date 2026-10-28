#!/usr/bin/env bb
;; The builder's tests: every unit test under test/rdm_spec/ and every
;; scenario of the spec, run with the kernel's Gherkin runner against the step
;; definitions under test/steps/, writing Allure results into $RESULTS_DIR
;; (else the configured results directory).
;;
;;   bb test.clj [--no-unit] [--no-scenarios] [PATH ...]
;;
;; PATH limits the scenarios to feature files under it (dhf/spec/<slice>).
;; Exits 0 only when every unit test and every scenario not planned passed.
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

(def here (str (fs/parent (fs/absolutize *file*))))
(cp/add-classpath (str here "/src:" here "/test"))

(require '[clojure.string :as str]
         '[clojure.test :as t]
         '[cheshire.core :as json]
         '[rdm-spec.kernel.runner :as runner]
         '[rdm-spec.kernel.project :as project])

(def args *command-line-args*)
(def unit? (not (some #{"--no-unit"} args)))
(def scenarios? (not (some #{"--no-scenarios"} args)))
(def paths (vec (remove #(str/starts-with? % "--") args)))

(def results-dir
  (str (fs/absolutize
        (or (not-empty (System/getenv "RESULTS_DIR"))
            (str here "/" (:results-dir (project/read-config here) "dhf/allure-results"))))))
(fs/create-dirs results-dir)

(defn now [] (System/currentTimeMillis))

(defn unit-namespaces []
  (->> (fs/glob (str here "/test/rdm_spec") "**_test.clj")
       (map #(-> (str (fs/relativize (str here "/test") %))
                 (str/replace #"\.clj$" "")
                 (str/replace "/" ".")
                 (str/replace "_" "-")
                 symbol))
       sort))

(defn write-unit-result! [ns-sym start counts out]
  (let [ok? (zero? (+ (:fail counts 0) (:error counts 0)))
        uuid (str (random-uuid))]
    (spit (str results-dir "/" uuid "-result.json")
          (json/generate-string
           (cond-> {:uuid uuid :historyId (str ns-sym) :name (str ns-sym) :fullName (str ns-sym)
                    :status (if ok? "passed" "failed") :stage "finished" :start start :stop (now)
                    :labels [{:name "framework" :value "clojure.test"} {:name "suite" :value "unit"}]
                    :steps [] :attachments [] :parameters [] :links []}
             (not ok?) (assoc :statusDetails {:message out}))))))

(defn run-unit-tests []
  (reduce (fn [acc ns-sym]
            (let [start (now)
                  _ (require ns-sym)
                  out (java.io.StringWriter.)
                  counts (binding [t/*test-out* out] (t/run-tests ns-sym))]
              (write-unit-result! ns-sym start counts (str out))
              (when (pos? (+ (:fail counts 0) (:error counts 0))) (print (str out)) (flush))
              (merge-with + acc (select-keys counts [:test :pass :fail :error]))))
          {:test 0 :pass 0 :fail 0 :error 0}
          (unit-namespaces)))

(def unit (when unit? (run-unit-tests)))

(defn after-scenario []
  (when-let [f (resolve 'steps.kernel-shared/cleanup!)] (f)))

(def scen
  (when scenarios?
    (runner/run-project {:root here :steps-dir (str here "/test/steps") :results-dir results-dir
                         :paths paths :after-scenario after-scenario
                         :on-result (fn [r]
                                      (when-not (#{"passed" "skipped"} (:status r))
                                        (println (str "  " (:status r) ": " (:name r) " "
                                                      (some-> (:statusDetails r) :message (str/replace #"\s*\n\s*" " | "))))))})))

(when-let [f (resolve 'steps.kernel-shared/cleanup-all!)] (f))

(println (str "summary: "
              (if scen
                (format "scenarios %d: %d passed, %d failed, %d broken, %d skipped"
                        (:total scen) (:passed scen) (:failed scen) (:broken scen) (:skipped scen))
                "scenarios not run")
              "; "
              (if unit
                (format "unit tests %d: %d failed, %d errors" (:test unit) (:fail unit) (:error unit))
                "unit tests not run")))

(System/exit (if (and (or (nil? unit) (zero? (+ (:fail unit) (:error unit))))
                      (or (nil? scen) (and (pos? (:total scen)) (zero? (+ (:failed scen) (:broken scen))))))
               0 1))
