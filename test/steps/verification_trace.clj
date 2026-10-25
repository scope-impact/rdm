(ns steps.verification-trace
  "Step definitions for dhf/spec/verification/trace.feature: `rdm-spec trace`
  in a small project with its own spec (steps.verification-fixtures), its
  Allure results written by hand as any test run would write them.

  Shared steps used, the kernel's: the When \"rdm-spec ...\" is run (and its
  variants with no --results and with that results directory as DIR), \"it
  exits 2 and names X\" and \"a copy of rdm-spec's own project in which the
  configured results directory does not exist\"."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]
            [steps.verification-fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; Helpers

(defn- trace-of
  "The trace the last run printed, after checking it passed."
  [ctx]
  (let [r (kc/the-run ctx)]
    (s/check! (zero? (:exit r)) (str "expected exit 0\n" (s/show r)))
    (or (:trace (kc/stdout-edn r))
        (s/fail! (str "standard output holds no trace\n" (s/show r))))))

(defn- sc [id & {:keys [tags]}] {:id id :name (str "Scenario " id) :tags tags})

(defn- body [& scs] (str/join "\n" (map fx/scenario-text scs)))

(def ^:private lint-model
  {:system {:id :demo :name "Demo" :description "A demo"}
   :containers [{:id :demo-c :name "demo" :kind :runs :description "d" :technology "t"
                 :contexts [{:id :architecture :name "architecture" :description "d"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "d"}]}]}]})

(defn- expected-of
  "What the fixture's own spec says of the scenario `id`: {:needs :component
  :context :level}, read from its feature file and model, independently of
  rdm-spec."
  [dir id]
  (let [cfg (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn"))))
        spec-dir (str/replace (or (:spec-dir cfg) "dhf/spec") #"/+$" "")
        model (edn/read-string (slurp (str (fs/file dir (:model cfg)))))
        hit (first (for [f (sort (map str (fs/glob (fs/file dir spec-dir) "**.feature")))
                         sc (:scenarios (gherkin/parse-feature f (slurp f)))
                         :when (some #{(str "@" id)} (:tags sc))]
                     [f sc]))
        _ (s/check! hit (str "the project's spec holds no scenario @" id))
        [f sc] hit
        sp (str/replace (str (fs/relativize (fs/file dir spec-dir) f)) #"\.feature$" "")
        owner (or (first (for [c (:containers model) cx (:contexts c) comp (:components cx)
                               :when (= sp (:spec comp))]
                           {:component (:name comp) :context (:name cx)}))
                  (first (for [comp (:kernel model) :when (= sp (:spec comp))]
                           {:component (:name comp) :context "kernel"})))]
    (merge owner
           {:needs (vec (distinct (keep #(second (re-matches #"@(UN-\d+)" %)) (:all-tags sc))))
            :level (some #(second (re-matches #"@(e2e|contract)" %)) (:tags sc))})))

;; ---------------------------------------------------------------------------
;; A need traces to its scenarios

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx]
    (let [dir (fx/project!
               {"demo/alpha" (fx/feature-text {:tags ["@UN-003"] :name "Alpha"
                                               :rules [{:name "First" :body (body (sc "spec-0001") (sc "spec-0002"))}]})
                "demo/beta" (fx/feature-text {:tags ["@UN-001"] :name "Beta"
                                              :rules [{:name "Traced" :tags ["@UN-003"]
                                                       :body (body (sc "spec-0003") (sc "spec-0004"))}
                                                      {:name "Not traced" :body (body (sc "spec-0005"))}]})
                "demo/gamma" (fx/feature-text {:tags ["@UN-001"] :name "Gamma"
                                               :rules [{:name "Mixed"
                                                        :body (body (sc "spec-0006" :tags ["@UN-003"])
                                                                    (sc "spec-0007"))}]})})]
      (assoc ctx :dir dir :expected {"spec-0001" "Alpha" "spec-0002" "Alpha"
                                     "spec-0003" "Beta" "spec-0004" "Beta"
                                     "spec-0006" "Gamma"}))))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (let [t (trace-of ctx)
          ids (map :id (:scenarios t))]
      (s/check! (= "UN-003" (:id t)) (str "the trace is of " (:id t)))
      (s/check! (= (sort (keys (:expected ctx))) (sort ids))
                (str "it lists " (vec ids) ", not " (vec (sort (keys (:expected ctx))))))
      nil)))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (doseq [e (:scenarios (trace-of ctx))]
      (s/check! (= (str "Scenario " (:id e)) (:title e)) (str (:id e) "'s title is " (pr-str (:title e))))
      (s/check! (= (get (:expected ctx) (:id e)) (:component e))
                (str (:id e) "'s component is " (pr-str (:component e))))
      (s/check! (= "e2e" (some-> (:level e) name)) (str (:id e) "'s level is " (pr-str (:level e)))))
    nil))

(defstep #"the declared need UN-007 and no scenario tracing to it"
  (fn [ctx]
    (assoc ctx :dir (fx/one-feature (body (sc "spec-0001") (sc "spec-0002"))))))

(defstep #"the trace says no scenario traces to (UN-\d+)"
  (fn [ctx need]
    (let [t (trace-of ctx)]
      (s/check! (empty? (:scenarios t)) (str "it lists " (mapv :id (:scenarios t))))
      (s/check! (str/includes? (str (:message t)) (str "no scenario traces to " need))
                (str "it says " (pr-str (:message t))))
      nil)))

;; ---------------------------------------------------------------------------
;; A scenario traces to its need and its evidence

(defstep #"the scenario @(spec-\d+) in the component Lint of the bounded context architecture, under a feature tagged (UN-\d+)"
  (fn [ctx id need]
    (let [dir (fx/project! {"architecture/lint"
                            (fx/feature-text {:tags [(str "@" need)] :name "Lint"
                                              :rules [{:name "Lint rule" :body (body (sc "spec-0001") (sc id))}]})}
                           {:needs (assoc fx/demo-needs need {:text "A need." :sources ["demo:README.md@0000000"]})})]
      (s/write! dir "dhf/spec/model.edn" (pr-str lint-model))
      (assoc ctx :dir dir :id id :need need))))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (s/check! (= (:id ctx) (:id t)) (str "the trace is of " (:id t)))
      (s/check! (= ["UN-005"] (:needs t)) (str "it names the needs " (:needs t)))
      (s/check! (= "Lint" (:component t)) (str "it names the component " (:component t)))
      (s/check! (= "architecture" (:context t)) (str "it names the context " (:context t)))
      (s/check! (= "e2e" (some-> (:level t) name)) (str "it names the level " (:level t)))
      nil)))

(defstep #"an Allure results directory with two results labelled spec=(\S+), one passed and one failed"
  (fn [ctx id]
    (let [dir (fx/one-feature (body (sc "spec-0001") (sc id)))
          results (kc/tmp-dir)
          files (mapv #(fx/write-result! results (fx/allure-result id %)) ["passed" "failed"])]
      (assoc ctx :dir dir :id id :results results :result-files files))))

(defstep #"the trace gives the status (\w+)"
  (fn [ctx want]
    (let [t (trace-of ctx)]
      (s/check! (= want (some-> (:status t) name)) (str "the trace gives the status " (pr-str (:status t))))
      nil)))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [t (trace-of ctx)
          listed (:results t)]
      (s/check! (= 2 (count listed)) (str "it lists " (count listed) " results: " (pr-str listed)))
      (s/check! (= #{"passed" "failed"} (set (map :status listed))) (str "their statuses are " (mapv :status listed)))
      (doseq [f (:result-files ctx)]
        (s/check! (some #(= (fs/file-name f) (fs/file-name (str (:file %)))) listed)
                  (str "no result names the file " (fs/file-name f) ": " (pr-str listed))))
      nil)))

;; ---------------------------------------------------------------------------
;; The trace refuses what it cannot read

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [ctx]
    (assoc ctx :dir (fx/one-feature (body (sc "spec-0001") (sc "spec-0002"))))))

(defstep #"\"rdm-spec ([^\"]*)\" is run with a DIR that does not exist"
  (fn [ctx line]
    (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (fx/one-feature (body (sc "spec-0001") (sc "spec-0012")))))
          missing (str (fs/file (kc/tmp-dir) "no-such-results"))]
      (kc/run-line (assoc-in ctx [:placeholders "DIR"] missing) line))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx]
    (kc/refused-naming! ctx (get-in ctx [:placeholders "DIR"]))
    nil))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [t (trace-of ctx)
          want (expected-of (:dir ctx) (:id t))]
      (s/check! (seq (:needs want)) (str (:id t) " traces to no need in the fixture"))
      (s/check! (= (:needs want) (:needs t)) (str "it lists the needs " (:needs t) ", not " (:needs want)))
      (s/check! (= (:component want) (:component t)) (str "it names the component " (:component t)))
      (s/check! (= (:context want) (:context t)) (str "it names the context " (:context t)))
      (s/check! (= (:level want) (some-> (:level t) name)) (str "it names the level " (:level t)))
      nil)))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (let [t (trace-of ctx)]
      (s/check! (false? (:derived? t)) (str "it derived a status: " (pr-str t)))
      (s/check! (str/includes? (str (:no-status t)) "no status was derived because no results were given")
                (str "it does not say no status was derived: " (pr-str t)))
      nil)))
