(ns steps.kernel-runner
  "Step definitions for dhf/spec/kernel/runner.feature: `rdm-spec run` in a
  project the scenario makes (a fixture): its configuration (the room's,
  without :oracle), a model, feature files and a step definition file under
  its configured step directory, written for the runner's step API. Each
  definition the fixture writes appends what it was given to a log, so the
  scenario sees which steps ran and with what.

  Shared: \"a stamped scenario outline with three example rows\" (the
  verification context's status.feature uses it too) sets
    :dir  the project: one component, demo/alpha, holding spec-0001, an
          outline over n = 1, 2, 3 (\"An outline over <n>\"), its steps defined
    :id   \"spec-0001\"
  and :log (the log), :defs (the definitions written).

  Other context keys set here: :defs [{:pattern :name :throw :adds :line}],
  :scenarios [[step-line ..] ..] the scenarios the When writes, :log."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The fixture project

(def ^:private steps-rel "fixture_steps.clj")

(def ^:private model
  {:system {:id :demo :name "Demo" :description "A demo system"}
   :containers [{:id :app :name "App" :kind :runs :description "The demo app" :technology "a language"
                 :contexts [{:id :architecture :name "architecture" :description "The C4 model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "The lint"}]}
                            {:id :demo :name "demo" :description "A demo context"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha"
                                           :description "The demo component"}]}]}]
   :kernel [{:id :kernel/runner :name "Gherkin runner" :spec "kernel/runner" :description "The runner"}]})

(defn- project!
  "ctx with :dir, a project with the room's configuration, a model, needs
  and a log, and no feature file yet."
  [ctx]
  (if (:dir ctx)
    ctx
    (let [dir (s/project!)]
      (s/write! dir "dhf/spec/model.edn" (pr-str model))
      (s/write! dir "dhf/spec/needs.edn" (pr-str {"UN-001" {:text "A need." :sources ["demo:README.md@abc1234"]}}))
      (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
      (assoc ctx :dir dir :log (str (fs/file dir "steps.log"))))))

(defn- steps-dir [dir] (or (:steps-dir (kc/config-of dir)) "test/steps"))

(defn- defs-text
  "The step definition file for `defs`, each {:pattern :name [:throw msg]
  [:adds k]}, logging to `log`; with each def's :line."
  [log defs]
  (let [head ["(ns steps.fixture-steps"
              "  (:require [rdm-spec.kernel.runner :refer [defstep]]))"
              ""
              (str "(defn- log! [x] (spit " (pr-str log) " (str (pr-str x) \"\\n\") :append true))")
              ""]
        [lines defs] (reduce (fn [[lines out] d]
                               (let [line (inc (count lines))
                                     body [(str "(defstep (re-pattern " (pr-str (:pattern d)) ")")
                                           (str "  (fn [ctx & args]")
                                           (str "    (log! {:step " (pr-str (:name d)) " :ctx ctx :args (vec args)})")
                                           (if (:throw d)
                                             (str "    (throw (ex-info " (pr-str (:throw d)) " {}))))")
                                             (str "    " (if (:adds d) (str "(assoc ctx " (:adds d) " true)") "ctx") "))"))
                                           ""]]
                                 [(into lines body) (conj out (assoc d :line line))]))
                             [head []] defs)]
    {:text (str/join "\n" lines) :defs defs}))

(defn- with-defs!
  "ctx with the step definitions `defs` written into the project's step directory."
  [ctx defs]
  (let [ctx (project! ctx)
        {:keys [text defs]} (defs-text (:log ctx) defs)]
    (s/write! (:dir ctx) (str (steps-dir (:dir ctx)) "/" steps-rel) text)
    (assoc ctx :defs defs)))

(defn- scenario-text [{:keys [tags title steps]}]
  (str "\n    " tags "\n    Scenario: " title "\n"
       (str/join (map #(str "      " % "\n") steps))))

(defn- feature!
  "Writes the feature file `spec-path` holding `scenarios` under one rule."
  [dir spec-path feature-name scenarios]
  (s/write! dir (str "dhf/spec/" spec-path ".feature")
            (str "@UN-001\nFeature: " feature-name "\n  A fixture of the runner's steps.\n\n  Rule: The fixture's rule\n"
                 (str/join (map scenario-text scenarios)))))

(defn- log-of [ctx]
  (let [f (:log ctx)]
    (if (and f (fs/exists? f))
      (mapv edn/read-string (remove str/blank? (str/split-lines (slurp f))))
      [])))

(defn- results
  "Every Allure result in the project's configured results directory."
  [ctx]
  (let [d (fs/file (:dir ctx) (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results"))]
    (if (fs/directory? d)
      (vec (for [f (sort (map str (fs/glob d "*-result.json")))] (json/parse-string (slurp f) true)))
      [])))

(defn- label [r k] (some #(when (= (name k) (:name %)) (:value %)) (:labels r)))

(defn- results-for [ctx id] (filterv #(= id (label % :spec)) (results ctx)))

(defn- the-result
  "The one result of the scenario `id`."
  [ctx id]
  (let [rs (results-for ctx id)]
    (s/check! (= 1 (count rs)) (str (count rs) " results are labelled spec=" id "\n" (s/show (kc/the-run ctx))))
    (first rs)))

;; ---------------------------------------------------------------------------
;; Step definitions are written against one small API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"([^\"]+)\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx pattern]
    (with-defs! ctx [{:pattern pattern :name "holds"}])))

(defstep #"\"rdm-spec run\" runs a scenario with (?:the step \"([^\"]+)\"|both steps|that step)"
  (fn [ctx step]
    (let [ctx (project! ctx)
          scs (cond
                step [[step]]
                (:scenarios ctx) (:scenarios ctx)
                :else (s/fail! "no scenario was planned"))]
      (feature! (:dir ctx) "demo/alpha" "Alpha"
                (map-indexed (fn [i steps] {:tags (format "@spec-%04d @e2e" (inc i)) :title (str "Scenario " (inc i))
                                            :steps steps})
                             scs))
      (kc/run-line ctx "run"))))

(defstep #"the function is called with the scenario's context and \"([^\"]*)\""
  (fn [ctx arg]
    (let [log (log-of ctx)]
      (s/check! (= [{:step "holds" :ctx {} :args [arg]}] log)
                (str "the function was called with " (pr-str log) "\n" (s/show (kc/the-run ctx))))
      nil)))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (-> ctx
        (with-defs! [{:pattern "the first step" :name "first" :adds :first}
                     {:pattern "the second step" :name "second" :adds :second}])
        (assoc :scenarios [["Given the first step" "And the second step"] ["Given the first step"]]))))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (let [log (log-of ctx)]
      (s/check! (= {:step "second" :ctx {:first true} :args []} (second log))
                (str "the second function was given " (pr-str (second log)) "\n" (s/show (kc/the-run ctx))))
      nil)))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (let [log (log-of ctx)]
      (s/check! (= 3 (count log)) (str "the log holds " (pr-str log)))
      (s/check! (= {:step "first" :ctx {} :args []} (nth log 2))
                (str "the next scenario's first step was given " (pr-str (nth log 2))))
      nil)))

(defstep #"two step definitions whose patterns both match \"(?:Given|When|Then) ([^\"]+)\""
  (fn [ctx text]
    (-> ctx
        (with-defs! [{:pattern text :name "exact"} {:pattern "a (\\w+)" :name "any"}])
        (assoc :scenarios [[(str "Given " text)]]))))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (the-result ctx "spec-0001")
          msg (str (get-in r [:statusDetails :message]))]
      (s/check! (= "broken" (:status r)) (str "the result is " (:status r)))
      (doseq [d (:defs ctx)]
        (s/check! (str/includes? msg (str steps-rel ":" (:line d)))
                  (str "the result does not name " steps-rel ":" (:line d) ": " msg)))
      nil)))

;; ---------------------------------------------------------------------------
;; Every scenario run leaves one result, labelled with what it verifies

(defstep #"the scenario @(spec-\d+) in the component (\S+) of the bounded context (\S+)"
  (fn [ctx id component context]
    (let [ctx (with-defs! ctx [{:pattern "a passing step" :name "passing"}
                               {:pattern "another passing step" :name "another"}])
          comp (first (for [c (:containers model) x (:contexts c) k (:components x)
                            :when (and (= context (:name x)) (= component (:name k)))]
                        k))]
      (s/check! comp (str "the fixture's model has no component " component " in " context))
      (feature! (:dir ctx) (:spec comp) component
                [{:tags (str "@" id " @e2e") :title "A scenario of the component"
                  :steps ["Given a passing step" "Then another passing step"]}])
      (feature! (:dir ctx) "kernel/runner" "Gherkin runner"
                [{:tags "@spec-0020 @contract" :title "A kernel scenario" :steps ["Given a passing step"]}])
      (assoc ctx :id id :component component :context context :kernel-id "spec-0020"))))

(defstep #"it writes one Allure result labelled spec=(\S+), component=(\S+) and context=(\S+)"
  (fn [ctx id component context]
    (kc/exits! ctx 0)
    (let [r (the-result ctx id)]
      (s/check! (= [component context] [(label r :component) (label r :context)])
                (str "the result is labelled " (pr-str (:labels r))))
      nil)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx]
    (let [r (the-result ctx (:kernel-id ctx))]
      (s/check! (= "kernel" (label r :context)) (str "the kernel scenario is labelled " (pr-str (:labels r))))
      nil)))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [r (the-result ctx (:id ctx))]
      (s/check! (= [["Given a passing step" "passed"] ["Then another passing step" "passed"]]
                   (map (juxt :name :status) (:steps r)))
                (str "the result's steps are " (pr-str (:steps r))))
      nil)))

;; Shared with dhf/spec/verification/status.feature. Sets :dir (when unset), :id, :log, :defs.
(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (let [ctx (with-defs! ctx [{:pattern "the number (\\d+)" :name "number"}
                               {:pattern "it is counted" :name "counted"}])]
      (s/write! (:dir ctx) "dhf/spec/demo/alpha.feature"
                (str "@UN-001\nFeature: Alpha\n  A fixture of the kernel's shared steps.\n\n"
                     "  @spec-0001 @contract\n  Scenario Outline: An outline over <n>\n"
                     "    Given the number <n>\n    Then it is counted\n\n"
                     "    Examples:\n      | n |\n      | 1 |\n      | 2 |\n      | 3 |\n"))
      (assoc ctx :id "spec-0001"))))

(defstep #"it writes three Allure results, each labelled with the outline's id and naming its row"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rs (results-for ctx (:id ctx))]
      (s/check! (= 3 (count rs)) (str (count rs) " results are labelled " (:id ctx)))
      (s/check! (= #{"1" "2" "3"} (set (map #(second (re-find #"\[n=(\d+)\]$" (str (:name %)))) rs)))
                (str "the results are named " (pr-str (map :name rs))))
      (s/check! (= #{[{:name "n" :value "1"}] [{:name "n" :value "2"}] [{:name "n" :value "3"}]}
                   (set (map :parameters rs)))
                (str "the results' parameters are " (pr-str (map :parameters rs))))
      nil)))

(defstep #"a scenario whose second step's definition throws"
  (fn [ctx]
    (let [ctx (with-defs! ctx [{:pattern "a first step" :name "first"}
                               {:pattern "a second step" :name "second" :throw "the second step broke"}
                               {:pattern "a third step" :name "third"}])]
      (feature! (:dir ctx) "demo/alpha" "Alpha"
                [{:tags "@spec-0001 @e2e" :title "A scenario that fails"
                  :steps ["Given a first step" "When a second step" "Then a third step"]}])
      (assoc ctx :id "spec-0001"))))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [r (the-result ctx (:id ctx))
          msg (str (get-in r [:statusDetails :message]))]
      (s/check! (= "failed" (:status r)) (str "the result is " (:status r)))
      (s/check! (and (str/includes? msg "When a second step") (str/includes? msg "the second step broke"))
                (str "the result says " (pr-str msg)))
      nil)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [r (the-result ctx (:id ctx))]
      (s/check! (= ["passed" "failed" "skipped"] (mapv :status (:steps r)))
                (str "the steps are " (pr-str (map (juxt :name :status) (:steps r)))))
      (s/check! (not-any? #(= "third" (:step %)) (log-of ctx)) "the third step ran")
      nil)))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx]
    (let [ctx (with-defs! ctx [{:pattern "a defined step" :name "defined"}])]
      (feature! (:dir ctx) "demo/alpha" "Alpha"
                [{:tags "@spec-0001 @e2e" :title "A scenario with an undefined step"
                  :steps ["Given a defined step" "When a step nobody defined"]}])
      (assoc ctx :id "spec-0001" :unmatched "a step nobody defined"))))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx]
    (let [r (the-result ctx (:id ctx))]
      (s/check! (= "broken" (:status r)) (str "the result is " (:status r)))
      nil)))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (let [msg (str (get-in (the-result ctx (:id ctx)) [:statusDetails :message]))]
      (s/check! (str/includes? msg (:unmatched ctx)) (str "the result says " (pr-str msg)))
      nil)))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (let [ctx (with-defs! ctx [{:pattern "a planned step" :name "planned"}])]
      (feature! (:dir ctx) "demo/alpha" "Alpha"
                [{:tags "@spec-0001 @e2e @wip" :title "Planned work" :steps ["Given a planned step"]}])
      (assoc ctx :id "spec-0001"))))

(defstep #"the scenario's steps are not run"
  (fn [ctx]
    (kc/the-run ctx)
    (s/check! (empty? (log-of ctx)) (str "steps ran: " (pr-str (log-of ctx))))
    nil))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (the-result ctx (:id ctx))]
      (s/check! (= "skipped" (:status r)) (str "the result is " (:status r)))
      (s/check! (= "true" (label r :planned)) (str "the result is labelled " (pr-str (:labels r))))
      nil)))

;; ---------------------------------------------------------------------------
;; The runner discovers, and refuses to find nothing

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (let [ctx (with-defs! ctx [{:pattern "a step of feature (\\d+)" :name "step"}])
          ids (vec (for [i (range 1 21)] (format "spec-%04d" i)))]
      (doseq [[i id] (map-indexed vector ids)]
        (feature! (:dir ctx) (format "demo/feature_%02d" (inc i)) (str "Feature " (inc i))
                  [{:tags (str "@" id " @e2e") :title (str "Scenario of feature " (inc i))
                    :steps [(str "Given a step of feature " (inc i))]}]))
      (assoc ctx :ids ids))))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rs (results ctx)
          ids (set (keep #(label % :spec) rs))]
      (s/check! (= (set (:ids ctx)) ids) (str "results name " (pr-str (sort ids))))
      (s/check! (every? #(= "passed" (:status %)) rs) "a scenario did not pass")
      (s/check! (= 20 (count (log-of ctx))) (str (count (log-of ctx)) " steps ran"))
      nil)))

(defstep #"a spec directory holding no feature file"
  (fn [ctx]
    (let [ctx (with-defs! ctx [{:pattern "a step" :name "step"}])]
      (s/check! (empty? (fs/glob (fs/file (:dir ctx) "dhf/spec") "**.feature")) "the spec holds a feature file")
      ctx)))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (kc/refused-naming! ctx "no scenario was found")
    (s/check! (empty? (results ctx)) "a result was written")
    nil))
