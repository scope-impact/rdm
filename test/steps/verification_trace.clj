(ns steps.verification-trace
  "Step definitions for dhf/spec/verification/trace.feature: `rdm-spec trace`
  in a small project with its own spec, or in a copy of the room's spec where
  the scenario names one of its scenarios (spec-0012, in Lint)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

(defn- trace-of
  "The trace the last run printed, after checking it ran."
  [ctx]
  (let [r (:run ctx)]
    (s/check! (not (str/includes? (:err r) "cannot-run")) (s/show r))
    (s/check! (#{0 1} (:exit r)) (s/show r))
    (fx/report ctx)))

(defn- entry [t id] (first (filter #(= id (:id %)) (:scenarios t))))

;; --- A need traces to its scenarios ---------------------------------------

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx]
    (let [sc fx/scenario-text
          dir (fx/project!
               {;; the whole feature traces to UN-003
                "demo/alpha" (fx/feature-text {:tags ["@UN-003"] :name "Alpha"
                                               :rules [{:name "One" :body (str (sc {:id "spec-0001" :name "Alpha one"}) "\n"
                                                                               (sc {:id "spec-0002" :name "Alpha two" :level "@contract"}))}]})
                ;; one rule of it traces to UN-003, the other does not
                "demo/beta" (fx/feature-text {:tags ["@UN-001"] :name "Beta"
                                              :rules [{:name "Traced" :tags ["@UN-003"]
                                                       :body (str (sc {:id "spec-0003" :name "Beta one"}) "\n"
                                                                  (fx/outline-text "spec-0004" 2))}
                                                      {:name "Not traced" :body (sc {:id "spec-0005" :name "Beta three"})}]})
                ;; one scenario of it traces to UN-003
                "demo/gamma" (fx/feature-text {:tags ["@UN-001"] :name "Gamma"
                                               :rules [{:name "Mixed"
                                                        :body (str (sc {:id "spec-0006" :name "Gamma one" :tags ["@UN-003"]}) "\n"
                                                                   (sc {:id "spec-0007" :name "Gamma two"}))}]})})]
      (assoc ctx :dir dir
             :expected {"spec-0001" {:name "Alpha one" :component "Alpha" :level "e2e"}
                        "spec-0002" {:name "Alpha two" :component "Alpha" :level "contract"}
                        "spec-0003" {:name "Beta one" :component "Beta" :level "e2e"}
                        "spec-0004" {:name "An outline over <n>" :component "Beta" :level "contract"}
                        "spec-0006" {:name "Gamma one" :component "Gamma" :level "e2e"}}))))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (s/check! (= (set (keys (:expected ctx))) (set (map :id (:scenarios t))))
                (str "listed " (mapv :id (:scenarios t)) ", expected " (sort (keys (:expected ctx))) "\n" (s/show (:run ctx)))))))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (doseq [[id {:keys [name component level]}] (:expected ctx)
              :let [e (entry t id)]]
        (s/check! (= name (:name e)) (str id ": title " (pr-str (:name e)) ", expected " (pr-str name)))
        (s/check! (= component (:component e)) (str id ": component " (pr-str (:component e)) ", expected " component))
        (s/check! (= level (some-> (:level e) clojure.core/name)) (str id ": level " (pr-str (:level e)) ", expected " level))))))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [ctx]
    (assoc ctx :dir (fx/one-feature (fx/scenario-text {:id "spec-0001"})))))

(defstep #"the trace says no scenario traces to UN-007"
  (fn [ctx]
    (let [r (:run ctx)
          t (trace-of ctx)]
      (s/check! (= 0 (:exit r)) (s/show r))
      (s/check! (empty? (:scenarios t)) (s/show r))
      (s/check! (str/includes? (:out r) "no scenario traces to UN-007") (s/show r)))))

;; --- A scenario traces to its need and its evidence -----------------------

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [ctx]
    (let [dir (s/copy-of-room!)
          text (slurp (str (fs/file dir "dhf/spec/architecture/lint.feature")))]
      (s/check! (str/includes? text "@spec-0012") "lint.feature holds no @spec-0012")
      (s/check! (re-find #"(?m)^@UN-005\b" text) "lint.feature's feature is not tagged @UN-005")
      (assoc ctx :dir dir))))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (s/check! (= 0 (:exit (:run ctx))) (s/show (:run ctx)))
      (s/check! (= "spec-0012" (:id t)) (s/show (:run ctx)))
      (s/check! (some #{"UN-005"} (:needs t)) (str "needs " (pr-str (:needs t))))
      (s/check! (= "Lint" (:component t)) (str "component " (pr-str (:component t))))
      (s/check! (= "architecture" (:context t)) (str "context " (pr-str (:context t))))
      (s/check! (= "e2e" (some-> (:level t) name)) (str "level " (pr-str (:level t)))))))

(defstep #"an Allure results directory with two results labelled spec=spec-0012, one passed and one failed"
  (fn [ctx]
    (let [results (s/tmp-dir)
          f1 (fx/write-result! results (fx/allure-result "spec-0012" "passed"))
          f2 (fx/write-result! results (fx/allure-result "spec-0012" "failed"))]
      (assoc ctx :dir (s/copy-of-room!) :results results :result-files [f1 f2]))))

(defstep #"\"rdm-spec trace ([^\"]*)\" is run with that results directory as DIR"
  (fn [ctx command-line]
    (let [args (mapv #(if (= "DIR" %) (:results ctx) %) (s/split-args (str "trace " command-line)))]
      (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args)))))

(defstep #"the trace gives the status failed"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (s/check! (= "failed" (some-> (:status t) name)) (str "status " (pr-str (:status t)) "\n" (s/show (:run ctx)))))))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [t (trace-of ctx)
          listed (set (map (fn [r] [(some-> (:status r) name) (fs/file-name (str (:file r)))]) (:results t)))]
      (s/check! (= 2 (count (:results t))) (str "lists " (count (:results t)) " results\n" (s/show (:run ctx))))
      (s/check! (= #{["passed" (fs/file-name (first (:result-files ctx)))]
                     ["failed" (fs/file-name (second (:result-files ctx)))]}
                   listed)
                (str "listed " listed)))))

;; --- The trace refuses what it cannot read --------------------------------

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [ctx]
    (let [dir (s/copy-of-room!)]
      (s/check! (not (some #{"spec-0999"} (fx/scenario-ids dir any?))) "a scenario is tagged @spec-0999")
      (s/check! (not (contains? (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn")))) "UN-999"))
                "UN-999 is declared")
      (assoc ctx :dir dir))))

(defstep #"it exits 2 and names spec-0999"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) "spec-0999") (s/show r)))))

(defstep #"\"rdm-spec trace ([^\"]*)\" is run with a DIR that does not exist"
  (fn [ctx command-line]
    (let [missing (str (fs/file (s/tmp-dir) "no-such-results"))
          dir (or (:dir ctx) (s/copy-of-room!))
          args (mapv #(if (= "DIR" %) missing %) (s/split-args (str "trace " command-line)))]
      (assoc ctx :dir dir :missing missing :run (apply s/rdm-spec! dir args)))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) (:missing ctx)) (s/show r)))))

(defn- with-room-spec
  "The run, in a project holding the room's spec. The shared Given (steps.
  kernel-command, \"the configured results directory does not exist\") makes a
  project holding only the configuration, so there is no spec-0012 to trace:
  the room's allow-listed paths are copied in beside it, which leaves the
  configured results directory absent, and the same command is run again."
  [ctx]
  (let [dir (:dir ctx)]
    (if (fs/exists? (fs/file dir "dhf/spec/architecture/lint.feature"))
      ctx
      (let [room (s/copy-of-room!)]
        (doseq [a (get-in (s/room-config) [:clean-room :allow])
                :when (not= a "dhf/harness")
                :let [src (fs/file room a)]
                :when (fs/exists? src)]
          (if (fs/directory? src)
            (fs/copy-tree src (fs/file dir a) {:replace-existing true})
            (do (fs/create-dirs (fs/parent (fs/file dir a)))
                (fs/copy src (fs/file dir a) {:replace-existing true}))))
        (s/check! (not (fs/exists? (fs/file dir (:results-dir (s/room-config))))) "the results directory exists")
        (assoc ctx :run (apply s/rdm-spec! dir (:args (:run ctx))))))))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [ctx (with-room-spec ctx)
          t (trace-of ctx)]
      (s/check! (= 0 (:exit (:run ctx))) (s/show (:run ctx)))
      (s/check! (some #{"UN-005"} (:needs t)) (str "needs " (pr-str (:needs t)) "\n" (s/show (:run ctx))))
      (s/check! (= "Lint" (:component t)) (str "component " (pr-str (:component t))))
      (s/check! (= "e2e" (some-> (:level t) name)) (str "level " (pr-str (:level t))))
      ctx)))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (let [ctx (with-room-spec ctx)
          t (trace-of ctx)]
      (s/check! (nil? (:status t)) (str "a status was given: " (pr-str (:status t))))
      (s/check! (str/includes? (:out (:run ctx)) "no status was derived because no results were given")
                (s/show (:run ctx))))))
