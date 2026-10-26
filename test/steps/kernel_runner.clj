(ns steps.kernel-runner
  "Step definitions for dhf/spec/kernel/runner.feature: `rdm-spec run` on a
  small project the scenario makes, whose feature files and step
  definition file (in its configured step directory, written against the
  runner's step API) a step writes. A fixture step records each call in the
  project's calls.edn, one EDN form per call, so a Then can see what the
  runner called and with what.

  The fixture project: the room's configuration without :oracle; a model
  with the bounded context architecture (Lint, spec architecture/lint) and
  the kernel component Event bus (kernel/bus); the needs UN-002 and UN-003.

  Shared step defined here (the verification context's status.feature uses
  it too): Given a stamped scenario outline with three example rows; it sets
  :dir (the project), :id (the outline's id, spec-0066) and :rows (each
  row's values, {\"n\" value}, in order).

  Context keys set here: :dir, :id, :rows, :calls-file."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The fixture project

(def model
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                 :contexts [{:id :architecture :name "architecture" :description "The C4 model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "The lint"}]}]}]
   :kernel [{:id :kernel/bus :name "Event bus" :spec "kernel/bus" :description "The bus"}]
   :relationships [] :views []})

(def needs
  {"UN-002" {:text "A need." :sources ["demo:README.md@abc1234"]}
   "UN-003" {:text "Another need." :sources ["demo:README.md@abc1234"]}})

(defn- calls-file [dir] (str (fs/file dir "calls.edn")))

(def step-header
  "The head of a fixture step file: its namespace and the call log."
  "(ns steps.fixture
  (:require [rdm-spec.kernel.runner :as runner :refer [defstep]]))
(defn log! [x] (spit (str (System/getProperty \"user.dir\") \"/calls.edn\") (str (pr-str x) \"\\n\") :append true))
(defstep #\"a step\" (fn [ctx] ctx))
(defstep #\"a step with (\\S+)\" (fn [ctx n] ctx))
(defstep #\"it holds\" (fn [ctx] ctx))
")

(defn project!
  "ctx with :dir, a project holding `features` ({rel text}) and the step
  definitions `steps` (Clojure forms, as text, after the header)."
  [ctx features steps]
  (let [dir (s/project!)]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/needs.edn" (pr-str needs))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (doseq [[rel text] features] (s/write! dir rel text))
    (s/write! dir "test/steps/fixture.clj" (str step-header steps "\n"))
    (assoc ctx :dir dir :calls-file (calls-file dir))))

(declare label labels)

(defn calls
  "Every call the fixture steps recorded, in order."
  [ctx]
  (let [f (:calls-file ctx)]
    (if (fs/exists? f) (edn/read-string (str "[" (slurp f) "]")) [])))

(defn feature
  "A feature file named `nm` holding `body` (scenarios, indented two)."
  [nm body]
  (str "Feature: " nm "\n  A fixture.\n" body))

(defn results
  "Every result the run wrote into the project's configured results directory."
  [ctx]
  (let [d (fs/file (:dir ctx) (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results"))]
    (vec (for [f (sort (map str (fs/glob d "*-result.json")))]
           (assoc (json/parse-string (slurp f)) ::file f)))))

(defn the-result
  "The one result the run wrote, or the one labelled `id`."
  ([ctx] (let [rs (results ctx)]
           (s/check! (= 1 (count rs)) (str "the run wrote " (count rs) " results, not one\n" (s/show (kc/the-run ctx))))
           (first rs)))
  ([ctx id] (let [rs (filter #(= id (label % "spec")) (results ctx))]
              (s/check! (= 1 (count rs)) (str "the run wrote " (count rs) " results labelled " id "\n" (s/show (kc/the-run ctx))))
              (first rs))))

(defn label
  "The value of the label `nm` of the result `r` (the first, when several)."
  [r nm]
  (some #(when (= nm (get % "name")) (get % "value")) (get r "labels")))

(defn labels
  "Every value of the label `nm` of the result `r`."
  [r nm]
  (vec (keep #(when (= nm (get % "name")) (get % "value")) (get r "labels"))))

(defn- message [r] (str (get-in r ["statusDetails" "message"])))

(defn- run! [ctx] (kc/run-line ctx "run"))

;; ---------------------------------------------------------------------------
;; Step definitions are written against one small API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" "\n  @spec-0001 @e2e @UN-002\n  Scenario: Twenty\n    Given the spec holds 20 features\n")}
              "(defstep #\"the spec holds (\\d+) features\" (fn [ctx n] (log! {:ctx ctx :n n}) ctx))")))

(defstep #"\"rdm-spec run\" runs a scenario with (?:the step \"Given the spec holds 20 features\"|both steps|that step)"
  (fn [ctx] (run! ctx)))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= [{:ctx {} :n "20"}] (calls ctx)) (str "the calls were " (pr-str (calls ctx))))
    nil))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" (str "\n  @spec-0001 @e2e @UN-002\n  Scenario: First\n    Given the first adds a key\n    And the second adds a key\n"
                                        "\n  @spec-0002 @e2e @UN-002\n  Scenario: Second\n    Given the first adds a key\n    And the second adds a key\n"))}
              (str "(defstep #\"the first adds a key\" (fn [ctx] (log! {:step :first :ctx ctx}) (assoc ctx :first 1)))\n"
                   "(defstep #\"the second adds a key\" (fn [ctx] (log! {:step :second :ctx ctx}) (assoc ctx :second 2)))"))))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= {:step :second :ctx {:first 1}} (second (calls ctx))) (str "the calls were " (pr-str (calls ctx))))
    nil))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (s/check! (= {:step :first :ctx {}} (nth (calls ctx) 2 nil)) (str "the calls were " (pr-str (calls ctx))))
    nil))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" "\n  @spec-0001 @e2e @UN-002\n  Scenario: Ambiguous\n    Given a spec\n")}
              (str "(defstep #\"a spec\" (fn [ctx] ctx))\n"
                   "(defstep #\"a (s\\w+)\" (fn [ctx _] ctx))"))))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [r (the-result ctx)]
      (s/check! (= "broken" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (and (str/includes? (message r) "a spec") (str/includes? (message r) "a (s\\w+)"))
                (str "the message does not name both definitions: " (message r)))
      nil)))

;; ---------------------------------------------------------------------------
;; Every scenario run leaves one result, labelled with what it verifies

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" "\n  @spec-0012 @e2e @UN-002\n  Scenario: Lint names the view\n    Given a step\n    Then it holds\n")
                   "dhf/spec/kernel/bus.feature"
                   (feature "Event bus" "\n  @spec-0020 @e2e @UN-003\n  Scenario: The bus delivers\n    Given a step\n")}
              "")))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [r (the-result ctx "spec-0012")]
      (s/check! (= ["Lint" "architecture"] [(label r "component") (label r "context")])
                (str "the labels are " (pr-str (get r "labels"))))
      nil)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx]
    (let [r (the-result ctx "spec-0020")]
      (s/check! (= ["kernel" "Event bus"] [(label r "context") (label r "component")])
                (str "the labels are " (pr-str (get r "labels"))))
      nil)))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [r (the-result ctx "spec-0012")]
      (s/check! (= [["Given a step" "passed"] ["Then it holds" "passed"]]
                   (mapv (juxt #(get % "name") #(get % "status")) (get r "steps")))
                (str "the steps are " (pr-str (get r "steps"))))
      nil)))

(def ^:private outline-text
  (feature "Lint" (str "\n  @spec-0066 @contract @UN-002\n  Scenario Outline: Counts <n>\n    Given a step with <n>\n    Then it holds\n\n"
                       "    Examples:\n      | n |\n      | 1 |\n      | 2 |\n      | 3 |\n")))

(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (-> (project! ctx {"dhf/spec/architecture/lint.feature" outline-text} "")
        (assoc :id "spec-0066" :rows [{"n" "1"} {"n" "2"} {"n" "3"}]))))

(defstep #"it writes three Allure results, each labelled with the outline's id, sharing its name, and told apart by its parameters and historyId"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rs (results ctx)]
      (s/check! (= 3 (count rs)) (str "the run wrote " (count rs) " results"))
      (s/check! (every? #(= ["spec-0066"] (labels % "spec")) rs) "a result is not labelled spec-0066")
      (s/check! (= #{"Counts <n>"} (set (map #(get % "name") rs))) (str "the names are " (pr-str (map #(get % "name") rs))))
      (s/check! (= #{[{"name" "n" "value" "1"}] [{"name" "n" "value" "2"}] [{"name" "n" "value" "3"}]}
                   (set (map #(get % "parameters") rs)))
                (str "the parameters are " (pr-str (map #(get % "parameters") rs))))
      (s/check! (= #{"spec-0066|1" "spec-0066|2" "spec-0066|3"} (set (map #(get % "historyId") rs)))
                (str "the historyIds are " (pr-str (map #(get % "historyId") rs))))
      nil)))

;; --- a failing step, a missing definition, a planned scenario ------------------

(def ^:private failing-steps
  (str "(defstep #\"it finds what it asserts does not hold\" (fn [ctx] (runner/fail! \"no such view\")))\n"
       "(defstep #\"it fails its assertion with the message \\\"([^\\\"]+)\\\"\" (fn [ctx m] (runner/fail! m)))\n"
       "(defstep #\"nothing more\" (fn [ctx] (log! :ran) ctx))\n"
       "(defstep #\"it throws an error\" (fn [ctx] (throw (ex-info \"boom\" {}))))\n"
       "(defstep #\"it throws an error that is not an assertion\" (fn [ctx] (/ 1 0)))"))

(defn- one-scenario! [ctx lines]
  (project! ctx {"dhf/spec/architecture/lint.feature"
                 (feature "Lint" (str "\n  @spec-0012 @e2e @UN-002\n  Scenario: Lint names the view\n"
                                      (str/join (map #(str "    " % "\n") lines))))}
            failing-steps))

(defstep #"a scenario whose second step, a Then, finds what it asserts does not hold"
  (fn [ctx] (one-scenario! ctx ["Given a step" "Then it finds what it asserts does not hold" "And nothing more"])))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [r (the-result ctx)]
      (s/check! (= "failed" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (str/includes? (message r) "Then it finds what it asserts does not hold") (str "the message names no step: " (message r)))
      (s/check! (str/includes? (message r) "no such view") (str "the message names no error: " (message r)))
      (s/check! (= "failed" (get-in r ["steps" 1 "status"])) (str "the second step is " (get-in r ["steps" 1 "status"])))
      nil)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [r (the-result ctx)]
      (s/check! (= "skipped" (get-in r ["steps" 2 "status"])) (str "the third step is " (get-in r ["steps" 2 "status"])))
      (s/check! (empty? (calls ctx)) "a step after the failure ran")
      nil)))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx] (one-scenario! ctx ["Given a step" "When nobody defined this step" "Then it holds"])))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= "broken" (get (the-result ctx) "status")) (str "the result is " (get (the-result ctx) "status")))
    nil))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (s/check! (str/includes? (message (the-result ctx)) "When nobody defined this step")
              (str "the message names no step: " (message (the-result ctx))))
    nil))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" "\n  @spec-0012 @e2e @wip @UN-002\n  Scenario: Planned\n    Given nothing more\n")}
              failing-steps)))

(defstep #"the scenario's steps are not run"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (empty? (calls ctx)) (str "steps ran: " (pr-str (calls ctx))))
    nil))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (the-result ctx)]
      (s/check! (= "skipped" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (= "true" (label r "planned")) (str "the labels are " (pr-str (get r "labels"))))
      nil)))

;; ---------------------------------------------------------------------------
;; The runner discovers, and refuses to find nothing

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (project! ctx (into {} (for [i (range 1 21)]
                             [(format "dhf/spec/demo/c%02d.feature" i)
                              (feature (str "C" i) (format "\n  @spec-%04d @e2e @UN-002\n  Scenario: Number %d\n    Given a step\n" i i))]))
              "")))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [ids (set (mapcat #(labels % "spec") (results ctx)))]
      (s/check! (= (set (for [i (range 1 21)] (format "spec-%04d" i))) ids) (str "the results name " (pr-str (sort ids))))
      nil)))

(defstep #"a spec directory holding no feature file"
  (fn [ctx] (project! ctx {} "")))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (kc/refused-naming! ctx "no scenario")
    (s/check! (empty? (results ctx)) "a result was written")
    nil))

;; ---------------------------------------------------------------------------
;; Every result is written in Allure's own model

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint\.feature, owned by the component Lint of the bounded context architecture"
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" (str "\n  Rule: Every view is described\n\n    @spec-0012 @e2e @UN-002\n"
                                        "    Scenario: Lint names the view\n      Given a step\n      Then it holds\n"))}
              "")))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx]
    (let [ctx (run! ctx)]
      (kc/exits! ctx 0)
      (s/check! (= "passed" (get (the-result ctx) "status")) "the scenario did not pass")
      (assoc ctx :ran-between [(:started ctx) (System/currentTimeMillis)]))))

(defn- element! [ctx element value]
  (let [r (the-result ctx)
        is! (fn [want got] (s/check! (= want got) (str element " is " (pr-str got) ", not " (pr-str want))))]
    (case element
      "name" (is! "Lint names the view" (get r "name"))
      "fullName" (is! "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName"))
      "historyId" (is! "spec-0012" (get r "historyId"))
      "status and stage" (is! ["passed" "finished"] [(get r "status") (get r "stage")])
      "start and stop" (let [{:strs [start stop]} r
                             now (System/currentTimeMillis)]
                         (s/check! (and (integer? start) (integer? stop) (<= start stop) (< (- now 600000) start) (<= stop now))
                                   (str "start and stop are " start " and " stop)))
      "steps" (do (is! [["Given a step" "passed"] ["Then it holds" "passed"]]
                       (mapv (juxt #(get % "name") #(get % "status")) (get r "steps")))
                  (s/check! (every? #(and (integer? (get % "start")) (integer? (get % "stop"))) (get r "steps"))
                            "a step has no start or stop"))
      "the label feature" (is! "Lint" (label r "feature"))
      "the label story" (is! "Every view is described" (label r "story"))
      "the label epic" (is! "architecture" (label r "epic"))
      "the label suite" (is! "architecture" (label r "suite"))
      "the label subSuite" (is! "architecture/lint" (label r "subSuite"))
      "the labels tag" (is! #{"spec-0012" "e2e" "UN-002"} (set (labels r "tag")))
      "the labels spec, context and component" (is! ["spec-0012" "architecture" "Lint"]
                                                     [(label r "spec") (label r "context") (label r "component")])
      "the label framework" (is! "gherkin" (label r "framework"))
      "links" (is! [{"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}] (get r "links"))
      (s/fail! (str "no such element: " element)))
    nil))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element value] (element! ctx element value)))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" (str "\n  @spec-0013 @contract @UN-002\n  Scenario Outline: Keeps <written>\n"
                                        "    Given a step with <written>\n    Then it holds\n\n"
                                        "    Examples:\n      | written | kept |\n      | plan    | 3    |\n"))}
              "")))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [r (the-result ctx "spec-0013")]
      (s/check! (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] (get r "parameters"))
                (str "the parameters are " (pr-str (get r "parameters"))))
      nil)))

(defstep #"its historyId is \"spec-0013\|plan\|3\", the id and the row's values joined by \|, so each row has its own history"
  (fn [ctx]
    (let [h (get (the-result ctx "spec-0013") "historyId")]
      (s/check! (= "spec-0013|plan|3" h) (str "the historyId is " (pr-str h)))
      nil)))

(defstep #"a scenario whose second step, a Then, fails its assertion with the message \"no such view\""
  (fn [ctx] (one-scenario! ctx ["Given a step" "Then it fails its assertion with the message \"no such view\"" "And nothing more"])))

(defstep #"its result's status is \"failed\", its statusDetails message holds \"no such view\", and its statusDetails hold the trace"
  (fn [ctx]
    (let [r (the-result ctx)]
      (s/check! (= "failed" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (str/includes? (message r) "no such view") (str "the message is " (message r)))
      (s/check! (not (str/blank? (get-in r ["statusDetails" "trace"]))) "the statusDetails hold no trace")
      nil)))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx]
    (let [st (get-in (the-result ctx) ["steps" 2 "status"])]
      (s/check! (= "skipped" st) (str "the third step is " (pr-str st)))
      nil)))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [ctx]
    (project! ctx {"dhf/spec/architecture/lint.feature"
                   (feature "Lint" "\n  @spec-0012 @e2e @UN-002\n  Scenario: Evidence\n    Given it attaches its evidence\n")}
              "(defstep #\"it attaches its evidence\" (fn [ctx] (runner/attach! \"response\" \"GET / answered 200\") ctx))")))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [as (get-in (the-result ctx) ["steps" 0 "attachments"])]
      (s/check! (= [["response" "text/plain"]] (mapv (juxt #(get % "name") #(get % "type")) as))
                (str "the step's attachments are " (pr-str as)))
      nil)))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (let [r (the-result ctx)
          src (get-in r ["steps" 0 "attachments" 0 "source"])
          f (fs/file (fs/parent (::file r)) (str src))]
      (s/check! (and src (not (str/includes? src "/")) (fs/regular-file? f)) (str "no file " src " beside the result"))
      (s/check! (= "GET / answered 200" (slurp (str f))) (str "the attachment holds " (pr-str (slurp (str f)))))
      nil)))

(defstep #"a scenario whose (Then|Given|When) step (finds what it asserts does not hold|throws an error|throws an error that is not an assertion)"
  (fn [ctx kw what]
    (let [text (case what
                 "finds what it asserts does not hold" "it finds what it asserts does not hold"
                 "throws an error" "it throws an error"
                 "throws an error that is not an assertion" "it throws an error that is not an assertion")
          lines (case kw
                  "Given" [(str "Given " text) "Then it holds"]
                  "When" ["Given a step" (str "When " text) "Then it holds"]
                  "Then" ["Given a step" (str "Then " text)])]
      (one-scenario! ctx lines))))

(defstep #"its result's status is \"(failed|broken)\""
  (fn [ctx st]
    (kc/exits! ctx 1)
    (let [got (get (the-result ctx) "status")]
      (s/check! (= st got) (str "the result is " (pr-str got) ", not " st "\n" (s/show (kc/the-run ctx))))
      nil)))

(defstep #"a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset"
  (fn [ctx]
    (let [ctx (project! ctx {"dhf/spec/architecture/lint.feature"
                             (feature "Lint" "\n  @spec-0012 @e2e @UN-002\n  Scenario: Here\n    Given a step\n")}
                        "")]
      (s/check! (= "dhf/allure-results" (:results-dir (kc/config-of (:dir ctx)))) "the results directory is another")
      (assoc-in ctx [:env "RESULTS_DIR"] nil))))

(defstep #"the results are written into dhf/allure-results"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= 1 (count (fs/glob (fs/file (:dir ctx) "dhf/allure-results") "*-result.json")))
              "dhf/allure-results holds no result")
    nil))
