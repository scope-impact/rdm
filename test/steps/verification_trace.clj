(ns steps.verification-trace
  "Step definitions for dhf/spec/verification/trace.feature: `rdm-spec
  trace ID [--results DIR]`, run as its own process, in a small project with
  its own spec (needs traced) or in a copy of rdm-spec's own project (a
  scenario traced). The shared When and `it exits N` are the kernel's
  (steps.kernel-command); so is the Given of a copy of rdm-spec's own project
  whose configured results directory does not exist (spec-0108)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- trace-of
  "The trace the run printed (its standard output, as EDN), after checking
  it exited 0."
  [ctx]
  (let [r (the-run ctx)]
    (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
    (let [t (kc/stdout-edn r)]
      (s/check! (map? t) (str "the trace is not an EDN map\n" (s/show r)))
      t)))

(defn- level-name [l] (when l (str/replace (if (keyword? l) (name l) (str l)) #"^@" "")))

(defn- cannot-run-lines [r]
  (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))

(defn- exits-2-naming! [ctx word]
  (let [r (the-run ctx)]
    (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "a task that cannot run printed on standard output\n" (s/show r)))
    (s/check! (some #(str/includes? % word) (cannot-run-lines r))
              (str "no cannot-run line names " word "\n" (s/show r)))))

(defn- room-scenario
  "The parsed scenario `id` of the project `dir` (the kernel's Gherkin
  reader), or nil."
  [dir id]
  (let [cfg (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))]
    (first (for [f (sort (map str (fs/glob (fs/file dir (:spec-dir cfg "dhf/spec")) "**.feature")))
                 sc (:scenarios ((requiring-resolve 'rdm-spec.kernel.gherkin/parse-feature) f (slurp f)))
                 :when (some #{(str "@" id)} (:tags sc))]
             (assoc sc :file f)))))

(defn- the-scenario
  "The traced scenario `id` in the trace."
  [trace id]
  (let [sc (first (filter #(= id (:id %)) (:scenarios trace)))]
    (s/check! sc (str id " is not in the trace: " (pr-str trace)))
    sc))

;; ---------------------------------------------------------------------------
;; A need traces to its scenarios

(def ^:private by-feature ["spec-0001" "spec-0002"])
(def ^:private by-rule ["spec-0003" "spec-0004"])
(def ^:private by-scenario ["spec-0006"])
(def ^:private untraced ["spec-0005" "spec-0007"])

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx]
    (let [sc (fn [id & tags] (fx/scenario-text {:id id :name (str "Scenario " id) :tags tags
                                                 :level (if (= id "spec-0004") "@contract" "@e2e")}))
          dir (fx/project!
               {"demo/alpha" (fx/feature-text {:tags ["@UN-003"] :name "Alpha"
                                               :rules [{:name "Whole feature" :body (str (sc "spec-0001") "\n" (sc "spec-0002"))}]})
                "demo/beta" (fx/feature-text {:tags ["@UN-001"] :name "Beta"
                                              :rules [{:name "Traced rule" :tags ["@UN-003"]
                                                       :body (str (sc "spec-0003") "\n" (sc "spec-0004"))}
                                                      {:name "Other rule" :body (sc "spec-0005")}]})
                "demo/gamma" (fx/feature-text {:tags ["@UN-001"] :name "Gamma"
                                               :rules [{:name "One scenario"
                                                        :body (str (sc "spec-0006" "@UN-003") "\n" (sc "spec-0007"))}]})})]
      (assoc ctx :dir dir
             :expected {"spec-0001" ["Alpha" "e2e"] "spec-0002" ["Alpha" "e2e"]
                        "spec-0003" ["Beta" "e2e"] "spec-0004" ["Beta" "contract"]
                        "spec-0006" ["Gamma" "e2e"]}))))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (let [t (trace-of ctx)
          ids (set (map :id (:scenarios t)))]
      (s/check! (= (set (concat by-feature by-rule by-scenario)) ids)
                (str "the trace lists " (sort ids) "\n" (s/show (:run ctx))))
      (s/check! (not-any? (set untraced) ids) (str "the trace lists a scenario not tracing to UN-003: " (sort ids))))))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (doseq [[id [component level]] (:expected ctx)
              :let [sc (the-scenario t id)]]
        (s/check! (= (str "Scenario " id) (:title sc)) (str id "'s title is " (pr-str (:title sc))))
        (s/check! (= component (:component sc)) (str id "'s component is " (pr-str (:component sc)) ", not " component))
        (s/check! (= level (level-name (:level sc))) (str id "'s level is " (pr-str (:level sc)) ", not " level))))))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [ctx]
    (let [dir (fx/one-feature (str (fx/scenario-text {:id "spec-0001"}) "\n" (fx/scenario-text {:id "spec-0002"})))]
      (s/check! (contains? fx/demo-needs "UN-007") "the fixture needs do not declare UN-007")
      (s/check! (empty? (fx/scenario-ids dir #(some #{"@UN-007"} (:all-tags %)))) "a scenario traces to UN-007")
      (assoc ctx :dir dir))))

(defstep #"the trace says no scenario traces to (UN-\d+)"
  (fn [ctx need]
    (let [t (trace-of ctx)
          said (str "no scenario traces to " need)]
      (s/check! (empty? (:scenarios t)) (str "the trace lists " (mapv :id (:scenarios t))))
      (s/check! (str/includes? (:out (:run ctx)) said) (str "the trace does not say " (pr-str said) "\n" (s/show (:run ctx)))))))

;; ---------------------------------------------------------------------------
;; A scenario traces to its need and its evidence

(defstep #"the scenario @(spec-\d+) in the component (\S+) of the bounded context (\S+), under a feature tagged (UN-\d+)"
  (fn [ctx id component context need]
    (let [dir (s/copy-of-room!)
          sc (room-scenario dir id)]
      (s/check! sc (str "rdm-spec's own spec holds no scenario tagged @" id))
      (s/check! (str/ends-with? (str (:file sc)) (str "dhf/spec/" context "/" (str/lower-case component) ".feature"))
                (str id " is in " (:file sc)))
      (s/check! (some #{(str "@" need)} (:all-tags sc)) (str id " does not trace to " need))
      (assoc ctx :dir dir :id id
             :expected {:needs [need] :component component :context context
                        :level (level-name (first (filter #{"@e2e" "@contract"} (:tags sc))))}))))

(defstep #"the trace names (UN-\d+), the component (\S+) and the bounded context (\S+), and the scenario's level"
  (fn [ctx need component context]
    (let [sc (the-scenario (trace-of ctx) (:id ctx))]
      (s/check! (some #{need} (:needs sc)) (str "the trace names the needs " (pr-str (:needs sc))))
      (s/check! (= component (:component sc)) (str "the trace names the component " (pr-str (:component sc))))
      (s/check! (= context (:context sc)) (str "the trace names the bounded context " (pr-str (:context sc))))
      (s/check! (= (get-in ctx [:expected :level]) (level-name (:level sc)))
                (str "the trace names the level " (pr-str (:level sc)))))))

(defstep #"an Allure results directory with two results labelled spec=(spec-\d+), one passed and one failed"
  (fn [ctx id]
    (let [dir (or (:dir ctx) (s/copy-of-room!))
          results (str (fs/file (s/tmp-dir) "results"))
          files (into {} (for [st ["passed" "failed"]]
                           [(str (fs/file-name (fx/write-result! results (fx/allure-result id st)))) st]))]
      (assoc ctx :dir dir :id id :sources-dir results :result-files files))))

(defstep #"\"rdm-spec (trace[^\"]*)\" is run with that results directory as DIR"
  (fn [ctx line]
    (s/check! (:sources-dir ctx) "no results directory was given to stand for DIR")
    (kc/run-line ctx line)))

(defstep #"the trace gives the status (verified|failed|unverified|planned)"
  (fn [ctx want]
    (let [t (trace-of ctx)
          sc (the-scenario t (:id ctx))]
      (s/check! (= want (some-> (:status sc) name)) (str (:id ctx) "'s status is " (pr-str (:status sc)))))))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [sc (the-scenario (trace-of ctx) (:id ctx))
          listed (:results sc)
          by-name (into {} (for [r listed] [(str (fs/file-name (str (:file r)))) (:status r)]))]
      (s/check! (= 2 (count listed)) (str "the trace lists " (count listed) " results: " (pr-str listed)))
      (doseq [[n want] (:result-files ctx)]
        (s/check! (contains? by-name n) (str "the trace does not list " n ": " (pr-str listed)))
        (s/check! (= want (some-> (get by-name n) name)) (str n " is listed as " (pr-str (get by-name n)) ", not " want))))))

;; ---------------------------------------------------------------------------
;; The trace refuses what it cannot read

(defstep #"no scenario tagged @(spec-\d+) and no need (UN-\d+)"
  (fn [ctx id need]
    (let [dir (s/copy-of-room!)
          needs (edn/read-string (slurp (str (fs/file dir "dhf/spec/needs.edn"))))
          declared (if (map? needs) (set (map str (keys needs))) (set (map :id needs)))]
      (s/check! (nil? (room-scenario dir id)) (str "a scenario is tagged @" id))
      (s/check! (empty? (fx/scenario-ids dir #(some #{(str "@" need)} (:all-tags %)))) (str "a scenario traces to " need))
      (s/check! (not (declared need)) (str need " is declared"))
      (assoc ctx :dir dir))))

(defstep #"it exits 2 and names (spec-\d+)"
  (fn [ctx id] (exits-2-naming! ctx id)))

(defstep #"\"rdm-spec (trace[^\"]*)\" is run with a DIR that does not exist"
  (fn [ctx line]
    (let [missing (str (fs/file (s/tmp-dir) "no-such-results"))]
      (s/check! (not (fs/exists? missing)) (str missing " exists"))
      (kc/run-line (assoc ctx :sources-dir missing) line))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx]
    (s/check! (:sources-dir ctx) "no directory stood for DIR")
    (exits-2-naming! ctx (:sources-dir ctx))))

;; spec-0108's Given is the kernel's (steps.kernel-command); it leaves the
;; copy of rdm-spec's own project in :dir.

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [r (the-run ctx)
          id (last (filter #(re-matches #"spec-\d+" %) (:args r)))
          sc (room-scenario (:dir ctx) id)
          _ (s/check! sc (str "rdm-spec's own spec holds no scenario " id))
          traced (the-scenario (trace-of ctx) id)
          own-needs (set (keep #(second (re-matches #"@(UN-\d+)" %)) (:all-tags sc)))]
      (s/check! (seq (:needs traced)) (str "the trace names no need for " id))
      (s/check! (= own-needs (set (:needs traced))) (str "the trace names the needs " (pr-str (:needs traced))
                                                         ", not " (pr-str own-needs)))
      (s/check! (not (str/blank? (str (:component traced)))) (str "the trace names no component for " id))
      (s/check! (= (level-name (first (filter #{"@e2e" "@contract"} (:tags sc)))) (level-name (:level traced)))
                (str "the trace names the level " (pr-str (:level traced)))))))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (let [t (trace-of ctx)
          said "no status was derived because no results were given"]
      (s/check! (false? (:derived? t)) (str "the trace does not say it derived nothing: " (pr-str (:derived? t))))
      (s/check! (str/includes? (:out (:run ctx)) said) (str "the trace does not say " (pr-str said) "\n" (s/show (:run ctx))))
      (s/check! (every? #(nil? (:status %)) (:scenarios t)) "the trace gives a status"))))
