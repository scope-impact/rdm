(ns steps.verification-trace
  "Step definitions for dhf/spec/verification/trace.feature: `rdm-spec
  trace` in a small project the scenario makes (`rdm-spec init`, then its own
  model, needs and feature files), or in a copy of the room where a Given of
  the kernel's makes one. Allure results are written by hand into a new
  directory, as any test run would write them.

  The trace's result is read as its contract scenario (spec-0279) states it:
  {:trace {:kind :need :id :text :scenarios [{:id :title :component :context
  :level :status} ..]}}; a scenario's trace as this component writes it:
  {:trace {:kind :scenario :id :title :component :context :level :needs
  [{:id :text}] :status :results [{:file :status}] :derived?}}.

  Context keys: :dir the project; :results the results directory; :run the
  last run; :expect the scenarios a trace must list, [{:id :title :component
  :level}]; :id, :need as the Givens say."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures (copied in: a step file uses only the kernel's and its own)

(def ^:private citation "demo:README.md@abc1234")

(defn- needs-of [ids]
  (into (sorted-map) (for [id ids] [id {:text (str "The need " id ".") :sources [citation]}])))

(defn- model-of
  "A model whose bounded contexts are `contexts`, {context-name [[spec-path component-name] ..]}."
  [contexts]
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                 :contexts (vec (for [[c comps] contexts]
                                  {:id (keyword c) :name c :description (str "The " c " context")
                                   :components (vec (for [[sp n] comps]
                                                      {:id (keyword c (last (str/split sp #"/"))) :name n :spec sp
                                                       :description (str "The " n " component")}))}))}]
   :relationships [] :views []})

(defn- scenario-text [{:keys [id title tags level]}]
  (str "\n    " (str/join " " (remove nil? [(str "@" id) (or level "@e2e") tags])) "\n"
       "    Scenario: " title "\n"
       "      Given a step of " id "\n"))

(defn- feature-text
  "A feature: {:tags :name :rules [{:name :tags :scenarios [..]}]}."
  [{:keys [tags name rules]}]
  (str (when tags (str tags "\n")) "Feature: " name "\n  A fixture of the trace's steps.\n"
       (str/join (for [{rn :name rt :tags scs :scenarios} rules]
                   (str "\n" (when rt (str "  " rt "\n")) "  Rule: " rn "\n" (str/join (map scenario-text scs)))))))

(defn- project!
  "A project set up by rdm-spec holding `model`, `needs` and `features`
  {spec-path text}; the ledger at 9999. ctx with :dir."
  [ctx {:keys [model needs features]}]
  (let [parent (kc/tmp-dir)
        r (kc/run! ctx parent ["init" "project"])
        _ (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
        dir (str (fs/file parent "project"))]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/needs.edn" (pr-str needs))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (doseq [[sp text] features] (s/write! dir (str "dhf/spec/" sp ".feature") text))
    (-> ctx (assoc :dir dir) (assoc-in [:env "RESULTS_DIR"] nil))))

(defn- result! [dir id status]
  (let [uuid (str (java.util.UUID/randomUUID))
        f (fs/file dir (str uuid "-result.json"))]
    (fs/create-dirs dir)
    (spit f (json/generate-string {:uuid uuid :name (str "a test of " id) :status status :stage "finished"
                                   :labels [{:name "spec" :value id}] :steps []}))
    (str (fs/file-name f))))

(defn- trace [ctx]
  (let [r (kc/exits! ctx 0)
        t (:trace (kc/stdout-edn r))]
    (s/check! (map? t) (str "standard output holds no :trace\n" (s/show r)))
    t))

(defn- nm [x] (when x (if (keyword? x) (name x) (str x))))

;; ---------------------------------------------------------------------------
;; A need traces to its scenarios

(defstep #"UN-003 tagged on one feature, on one rule in another feature, and on one scenario in a third"
  (fn [ctx]
    (let [model (model-of [["demo" [["demo/alpha" "Alpha"] ["demo/beta" "Beta"] ["demo/gamma" "Gamma"]]]])
          a {"demo/alpha" (feature-text {:tags "@UN-003" :name "Alpha"
                                         :rules [{:name "One" :scenarios [{:id "spec-0001" :title "Alpha one"}
                                                                          {:id "spec-0002" :title "Alpha two" :level "@contract"}]}]})
           "demo/beta" (feature-text {:tags "@UN-001" :name "Beta"
                                      :rules [{:name "Tagged" :tags "@UN-003"
                                               :scenarios [{:id "spec-0003" :title "Beta under the rule"}]}
                                              {:name "Untagged" :scenarios [{:id "spec-0004" :title "Beta elsewhere"}]}]})
           "demo/gamma" (feature-text {:tags "@UN-001" :name "Gamma"
                                       :rules [{:name "Mixed" :scenarios [{:id "spec-0005" :title "Gamma tagged" :tags "@UN-003"}
                                                                          {:id "spec-0006" :title "Gamma not"}]}]})}]
      (assoc (project! ctx {:model model :needs (needs-of ["UN-001" "UN-003"]) :features a})
             :expect [{:id "spec-0001" :title "Alpha one" :component "Alpha" :level :e2e}
                      {:id "spec-0002" :title "Alpha two" :component "Alpha" :level :contract}
                      {:id "spec-0003" :title "Beta under the rule" :component "Beta" :level :e2e}
                      {:id "spec-0005" :title "Gamma tagged" :component "Gamma" :level :e2e}]
             :absent ["spec-0004" "spec-0006"]))))

(defstep #"the trace lists every scenario under that feature, every scenario under that rule, and that scenario"
  (fn [ctx]
    (let [t (trace ctx)
          ids (set (map :id (:scenarios t)))]
      (doseq [{:keys [id]} (:expect ctx)]
        (s/check! (ids id) (str "the trace does not list " id ": " (pr-str (:scenarios t)))))
      (doseq [id (:absent ctx)]
        (s/check! (not (ids id)) (str "the trace lists " id ", which does not trace to the need")))
      nil)))

(defstep #"each with its id, title, component and level"
  (fn [ctx]
    (let [t (trace ctx)]
      (doseq [{:keys [id title component level]} (:expect ctx)
              :let [sc (some #(when (= id (:id %)) %) (:scenarios t))]]
        (s/check! (= title (:title sc)) (str id "'s title is " (pr-str (:title sc))))
        (s/check! (= component (nm (:component sc))) (str id "'s component is " (pr-str (:component sc))))
        (s/check! (= (name level) (nm (:level sc))) (str id "'s level is " (pr-str (:level sc)))))
      nil)))

(defstep #"the declared need (UN-\d+) and no scenario tracing to it"
  (fn [ctx need]
    (let [model (model-of [["demo" [["demo/alpha" "Alpha"]]]])]
      (assoc (project! ctx {:model model :needs (needs-of ["UN-001" need])
                            :features {"demo/alpha" (feature-text {:tags "@UN-001" :name "Alpha"
                                                                   :rules [{:name "One" :scenarios [{:id "spec-0001" :title "Alpha one"}]}]})}})
             :need need))))

(defstep #"the trace says no scenario traces to (UN-\d+)"
  (fn [ctx need]
    (let [r (kc/the-run ctx)
          t (trace ctx)]
      (s/check! (empty? (:scenarios t)) (str "the trace lists scenarios: " (pr-str (:scenarios t))))
      (s/check! (re-find (re-pattern (str "(?i)no scenario traces to " need)) (str (:out r) (:err r)))
                (str "the trace does not say no scenario traces to " need "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A scenario traces to its need and its evidence

(defn- lint-project! [ctx]
  (let [model (model-of [["architecture" [["architecture/lint" "Lint"]]]])]
    (project! ctx {:model model :needs (needs-of ["UN-005"])
                   :features {"architecture/lint"
                              (feature-text {:tags "@UN-005" :name "Lint"
                                             :rules [{:name "One" :scenarios [{:id "spec-0012" :title "A view with no description fails the lint"}]}]})}})))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture, under a feature tagged UN-005"
  (fn [ctx] (assoc (lint-project! ctx) :id "spec-0012")))

(defstep #"the trace names UN-005, the component Lint and the bounded context architecture, and the scenario's level"
  (fn [ctx]
    (let [t (trace ctx)]
      (s/check! (= :scenario (:kind t)) (str "not a scenario's trace: " (pr-str t)))
      (s/check! (some #(= "UN-005" (if (map? %) (:id %) %)) (:needs t)) (str "UN-005 is not named: " (pr-str (:needs t))))
      (s/check! (= "Lint" (nm (:component t))) (str "the component is " (pr-str (:component t))))
      (s/check! (= "architecture" (nm (:context t))) (str "the context is " (pr-str (:context t))))
      (s/check! (= "e2e" (nm (:level t))) (str "the level is " (pr-str (:level t))))
      nil)))

(defstep #"an Allure results directory with two results labelled spec=(\S+), one passed and one failed"
  (fn [ctx id]
    (let [ctx (lint-project! ctx)
          dir (kc/tmp-dir)
          passed (result! dir id "passed")
          failed (result! dir id "failed")]
      (assoc ctx :results dir :id id :result-files {passed "passed" failed "failed"}))))

(defstep #"the trace gives the status failed"
  (fn [ctx]
    (let [t (trace ctx)]
      (s/check! (= "failed" (nm (:status t))) (str "the status is " (pr-str (:status t))))
      nil)))

(defstep #"it lists both results with their status and file"
  (fn [ctx]
    (let [t (trace ctx)]
      (doseq [[file status] (:result-files ctx)]
        (s/check! (some #(and (str/includes? (str (:file %)) file) (= status (nm (:status %)))) (:results t))
                  (str "the trace does not list " file " as " status ": " (pr-str (:results t)))))
      nil)))

;; ---------------------------------------------------------------------------
;; The trace's result has one shape

(defstep #"UN-003 tagged on one scenario, and a results directory with a passing result for it"
  (fn [ctx]
    (let [model (model-of [["demo" [["demo/alpha" "Alpha"]]]])
          ctx (project! ctx {:model model :needs (needs-of ["UN-001" "UN-003"])
                             :features {"demo/alpha" (feature-text {:tags "@UN-001" :name "Alpha"
                                                                    :rules [{:name "One" :scenarios [{:id "spec-0001" :title "Alpha one" :tags "@UN-003"}
                                                                                                     {:id "spec-0002" :title "Alpha two"}]}]})}})
          dir (kc/tmp-dir)]
      (result! dir "spec-0001" "passed")
      (assoc ctx :results dir :need "UN-003" :id "spec-0001"))))

(defstep #"standard output is \{:trace \{:kind :need :id \"(UN-\d+)\" :text \.\.\. :scenarios \[\.\.\.\]\}\}"
  (fn [ctx need]
    (let [r (kc/exits! ctx 0)
          out (kc/stdout-edn r)
          t (:trace out)]
      (s/check! (= #{:trace} (set (keys out))) (str "standard output is not one map under :trace\n" (s/show r)))
      (s/check! (= :need (:kind t)) (str ":kind is " (pr-str (:kind t))))
      (s/check! (= need (:id t)) (str ":id is " (pr-str (:id t))))
      (s/check! (= (str "The need " need ".") (:text t)) (str ":text is " (pr-str (:text t))))
      (s/check! (vector? (:scenarios t)) (str ":scenarios is not a vector: " (pr-str (:scenarios t))))
      (s/check! (= [(:id ctx)] (mapv :id (:scenarios t))) (str ":scenarios are " (pr-str (:scenarios t))))
      nil)))

(defstep #"each of its scenarios is a map with :id, :title, :component, :context, :level and :status"
  (fn [ctx]
    (let [t (trace ctx)]
      (s/check! (seq (:scenarios t)) "the trace lists no scenario")
      (doseq [sc (:scenarios t)]
        (s/check! (map? sc) (str "not a map: " (pr-str sc)))
        (doseq [k [:id :title :component :context :level :status]]
          (s/check! (some? (get sc k)) (str "the scenario has no " k ": " (pr-str sc)))))
      (let [sc (first (:scenarios t))]
        (s/check! (= ["Alpha one" "Alpha" "demo" "e2e" "verified"]
                     (map nm ((juxt :title :component :context :level :status) sc)))
                  (str "the scenario reads " (pr-str sc))))
      nil)))

;; ---------------------------------------------------------------------------
;; The trace refuses what it cannot read

(defstep #"no scenario tagged @spec-0999 and no need UN-999"
  (fn [ctx]
    (let [model (model-of [["demo" [["demo/alpha" "Alpha"]]]])]
      (project! ctx {:model model :needs (needs-of ["UN-001"])
                     :features {"demo/alpha" (feature-text {:tags "@UN-001" :name "Alpha"
                                                            :rules [{:name "One" :scenarios [{:id "spec-0001" :title "Alpha one"}]}]})}}))))

(defstep #"\"rdm-spec ([^\"]*)\" is run with a DIR that does not exist"
  (fn [ctx line]
    (let [missing (str (fs/file (kc/tmp-dir) "no-such-results"))]
      (-> ctx
          (assoc-in [:placeholders "DIR"] missing)
          (assoc :missing missing)
          (kc/run-line line)))))

(defstep #"it exits 2 and names the directory"
  (fn [ctx] (kc/refused-naming! ctx (:missing ctx)) nil))

(defn- room-scenario
  "The room's scenario `id`, parsed: {:needs :component-spec :level}."
  [id]
  (first (for [f (sort (map str (fs/glob (fs/file s/room-root "dhf/spec") "**.feature")))
               :let [p (gherkin/parse-feature f (slurp f))]
               sc (:scenarios p)
               :when (some #{(str "@" id)} (:tags sc))]
           {:needs (vec (distinct (keep #(second (re-matches #"@(UN-\d+)" %)) (:all-tags sc))))
            :level (some #(second (re-matches #"@(e2e|contract)" %)) (:tags sc))
            :spec (str/replace (str (fs/relativize (fs/file s/room-root "dhf/spec") f)) #"\.feature$" "")})))

(defstep #"the trace lists the scenario's needs, component and level"
  (fn [ctx]
    (let [t (trace ctx)
          want (room-scenario (second (:args (kc/the-run ctx))))
          model (edn/read-string (slurp (str (fs/file s/room-root "dhf/spec/model.edn"))))
          comp-name (first (for [c (:containers model) cx (:contexts c) cp (:components cx)
                                 :when (= (:spec want) (:spec cp))]
                             (:name cp)))]
      (s/check! want "the room holds no such scenario")
      (s/check! (= (set (:needs want)) (set (map #(if (map? %) (:id %) %) (:needs t))))
                (str "the needs are " (pr-str (:needs t)) ", not " (pr-str (:needs want))))
      (s/check! (= comp-name (nm (:component t))) (str "the component is " (pr-str (:component t))))
      (s/check! (= (:level want) (nm (:level t))) (str "the level is " (pr-str (:level t))))
      nil)))

(defstep #"it says no status was derived because no results were given"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          text (str (:out r) "\n" (:err r))]
      (s/check! (re-find #"(?i)no status was derived" text) (str "it does not say no status was derived\n" (s/show r)))
      (s/check! (re-find #"(?i)no results were given" text) (str "it does not say no results were given\n" (s/show r)))
      nil)))
