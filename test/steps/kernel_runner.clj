(ns steps.kernel-runner
  "Steps of the kernel component Gherkin runner (dhf/spec/05-verify-by-results/runner.feature).
  Each scenario makes a project with its own step definitions, written
  through the runner's step API, and runs \"rdm-spec run\" on it."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn results
  "The Allure results the project's run wrote, read as JSON."
  [ctx]
  (let [d (k/path ctx "dhf/allure-results")]
    (if (fs/directory? d)
      (vec (for [f (sort (map str (fs/glob d "*-result.json")))] (assoc (json/parse-string (slurp f)) "file" f)))
      [])))

(defn label [r n] (some #(when (= n (get % "name")) (get % "value")) (get r "labels")))
(defn labels [r n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get r "labels"))))

(defn by-spec [ctx id] (filterv #(= id (label % "spec")) (results ctx)))

(defn one-result [ctx id]
  (let [rs (by-spec ctx id)]
    (k/check! (= 1 (count rs)) "expected one result labelled spec=" id ", found " (count rs) "; " (k/shows ctx))
    (first rs)))

(defn steps-file!
  "Writes step definitions into the project's configured step directory,
  BODY a string of defstep forms; RECORD in it names a file of the project."
  [ctx body]
  (let [ns-name (str "fixture.steps-" (subs (str (random-uuid)) 0 8))
        record (k/path ctx "record.edn")]
    (k/write! ctx (str "test/steps/" (str/replace ns-name #"[.-]" "_") ".clj")
              (str "(ns " ns-name " (:require [rdm-spec.kernel.runner :refer [defstep attach! fail!]]))\n"
                   "(def record-file " (pr-str record) ")\n"
                   "(defn record! [x] (spit record-file (str (pr-str x) \"\\n\") :append true))\n"
                   body "\n"))
    ctx))

(defn records [ctx]
  (let [t (k/slurp-rel ctx "record.edn")]
    (if t (mapv edn/read-string (str/split-lines t)) [])))

(defn run-project!
  "A project whose spec is FEATURES (kernel-only model unless MODEL), and whose step definitions are STEPS."
  [ctx {:keys [features model steps]}]
  (-> (k/spec-project! ctx {:features features :model model})
      (steps-file! (or steps ""))))

(defn one-feature [scenarios] {"dhf/spec/demo/thing.feature" (k/feature-text "Thing" ["UN-001"] scenarios)})

;; ------------------------------------------------------------------ the step API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (assoc ctx ::steps "(defstep #\"the spec holds (\\d+) features\" (fn [ctx n] (record! [ctx n]) ctx))")))

(defstep #"\"rdm-spec run\" runs a scenario with the step \"Given the spec holds 20 features\""
  (fn [ctx]
    (-> (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Twenty" :steps ["Given the spec holds 20 features"]}])
                           :steps (::steps ctx)})
        (k/run-rdm "run"))))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (k/check! (= [[{} "20"]] (records ctx)) "the function was called with " (pr-str (records ctx)) "; " (k/shows ctx))
    ctx))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (assoc ctx ::steps (str "(defstep #\"step one\" (fn [ctx] (record! [:one ctx]) (assoc ctx :a 1)))\n"
                            "(defstep #\"step two\" (fn [ctx] (record! [:two ctx]) (assoc ctx :b 2)))\n"
                            "(defstep #\"step three\" (fn [ctx] (record! [:three ctx]) :not-a-map))\n"
                            "(defstep #\"step four\" (fn [ctx] (record! [:four ctx]) ctx))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with both steps"
  (fn [ctx]
    (-> (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "First" :steps ["Given step one" "When step two" "Then step three" "And step four"]}
                                                   {:id "spec-0002" :title "Second" :steps ["Given step one"]}])
                           :steps (::steps ctx)})
        (k/run-rdm "run"))))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (k/check! (= [:two {:a 1}] (second (records ctx))) "the second step received " (pr-str (records ctx)) "; " (k/shows ctx))
    ctx))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (k/check! (= [:one {}] (nth (records ctx) 4 nil)) "the next scenario's first step received " (pr-str (records ctx)))
    ctx))

(defstep #"a step whose function returns anything but a map leaves the context as it was"
  (fn [ctx]
    (k/check! (= [:four {:a 1 :b 2}] (nth (records ctx) 3 nil)) "after a step returning no map: " (pr-str (records ctx)))
    ctx))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [ctx]
    (assoc ctx ::steps (str "(defstep #\"a spec\" (fn [ctx] ctx))\n(defstep #\"a .*\" (fn [ctx] ctx))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with that step"
  (fn [ctx]
    (-> (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Ambiguous" :steps ["Given a spec"]}]) :steps (::steps ctx)})
        (k/run-rdm "run"))))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (one-result ctx "spec-0001")
          msg (get-in r ["statusDetails" "message"])]
      (k/check! (= "broken" (get r "status")) "the result is " (get r "status"))
      (k/check! (and (str/includes? (str msg) "a spec") (str/includes? (str msg) "a .*")) "the result names " (pr-str msg))
      ctx)))

;; ------------------------------------------------------------------ results and their labels

(def all-pass "(defstep #\".*\" (fn [ctx] ctx))")

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [ctx]
    (let [model (-> (k/context-model [[:architecture [[:lint "Lint"]]]])
                    (assoc :kernel [{:id :kernel/thing :name "Thing" :spec "kernel/thing" :description "Does the thing"}]))]
      (run-project! ctx {:model model
                         :features {"dhf/spec/architecture/lint.feature"
                                    (k/feature-text "Lint" ["UN-001"] [{:id "spec-0012" :title "Lint names the view"
                                                                       :steps ["Given a view" "Then it is named"]}])
                                    "dhf/spec/kernel/thing.feature"
                                    (k/feature-text "Thing" ["UN-001"] [{:id "spec-0013" :title "The thing"}])}
                         :steps all-pass}))))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (let [r (one-result ctx "spec-0012")]
      (k/check! (= ["Lint" "architecture"] [(label r "component") (label r "context")]) "the labels are " (pr-str (get r "labels")))
      ctx)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx] (k/check! (= "kernel" (label (one-result ctx "spec-0013") "context")) "the kernel's scenario is not context=kernel") ctx))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [r (one-result ctx "spec-0012")]
      (k/check! (= [["Given a view" "passed"] ["Then it is named" "passed"]]
                   (map (juxt #(get % "name") #(get % "status")) (get r "steps")))
                "the result's steps are " (pr-str (get r "steps")))
      ctx)))

(defstep #"it writes three Allure results, each labelled with the outline's id, sharing its name, and told apart by its parameters and historyId"
  (fn [ctx]
    (let [rs (by-spec ctx "spec-0001")]
      (k/check! (= 3 (count rs)) "the results are " (count rs) "; " (k/shows ctx))
      (k/check! (= 1 (count (distinct (map #(get % "name") rs)))) "the names differ")
      (k/check! (= 3 (count (distinct (map #(get % "parameters") rs)))) "the parameters do not tell them apart")
      (k/check! (= 3 (count (distinct (map #(get % "historyId") rs)))) "the historyIds do not tell them apart")
      ctx)))

(defn failing-project! [ctx]
  (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Fails"
                                              :steps ["Given a view" "Then it is drawn" "And it is named"]}])
                     :steps (str "(defstep #\"a view\" (fn [ctx] ctx))\n"
                                 "(defstep #\"it is drawn\" (fn [ctx] (fail! \"no such view\")))\n"
                                 "(defstep #\"it is named\" (fn [ctx] (record! :third-ran) ctx))\n")}))

(defstep #"a scenario whose second step, a Then, finds what it asserts does not hold"
  (fn [ctx] (failing-project! ctx)))

(defstep #"a scenario whose second step, a Then, fails its assertion with the message \"no such view\""
  (fn [ctx] (failing-project! ctx)))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (let [r (one-result ctx "spec-0001") msg (get-in r ["statusDetails" "message"])]
      (k/check! (= "failed" (get r "status")) "the result is " (get r "status"))
      (k/check! (and (str/includes? msg "Then it is drawn") (str/includes? msg "no such view")) "it says " (pr-str msg))
      ctx)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [r (one-result ctx "spec-0001")]
      (k/check! (= "skipped" (get-in r ["steps" 2 "status"])) "the third step is " (get-in r ["steps" 2 "status"]))
      (k/check! (empty? (records ctx)) "the third step ran")
      ctx)))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx]
    (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Unmatched" :steps ["Given nothing defines this"]}])})))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx] (k/check! (= "broken" (get (one-result ctx "spec-0001") "status")) "the result is not broken") ctx))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (k/check! (str/includes? (str (get-in (one-result ctx "spec-0001") ["statusDetails" "message"])) "Given nothing defines this")
              "the result does not name the step")
    ctx))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Planned" :wip? true :steps ["Given a planned step"]}])
                       :steps "(defstep #\"a planned step\" (fn [ctx] (record! :ran) ctx))"})))

(defstep #"the scenario's steps are not run"
  (fn [ctx] (k/check! (empty? (records ctx)) "a planned step ran") ctx))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (one-result ctx "spec-0001")]
      (k/check! (= ["skipped" "true"] [(get r "status") (label r "planned")]) "the result is " (pr-str r))
      ctx)))

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (run-project! ctx {:features (into {} (for [i (range 1 21)]
                                            [(format "dhf/spec/demo/f%02d.feature" i)
                                             (k/feature-text (str "F" i) ["UN-001"] [{:id (format "spec-%04d" i) :title (str "S" i)}])]))
                       :model {:system {:id :demo :name "demo" :description "A demo"}
                               :kernel (vec (for [i (range 1 21)]
                                              {:id (keyword "kernel" (format "f%02d" i)) :name (str "F" i)
                                               :spec (format "demo/f%02d" i) :description "A part"}))}
                       :steps all-pass})))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (set (for [i (range 1 21)] (format "spec-%04d" i))) (set (map #(label % "spec") (results ctx))))
              "the results name " (pr-str (sort (map #(label % "spec") (results ctx)))))
    ctx))

(defstep #"a spec directory holding no feature file"
  (fn [ctx] (k/project! ctx)))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (k/exits! ctx 2)
    (k/err-has! ctx "no scenario")
    (k/check! (empty? (results ctx)) "a result was written")
    ctx))

;; ------------------------------------------------------------------ Allure's own model

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint.feature, owned by the component Lint of the bounded context architecture"
  (fn [ctx]
    (run-project! ctx {:model (k/context-model [[:architecture [[:lint "Lint"]]]])
                       :features {"dhf/spec/architecture/lint.feature"
                                  (str "Feature: Lint\n\n  Rule: Every view is described\n\n"
                                       "    @spec-0012 @e2e @UN-002\n    Scenario: Lint names the view\n"
                                       "      Given a view\n      When the lint runs\n      Then it names the view\n")}
                       :steps all-pass})))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx] (let [ctx (k/run-rdm ctx "run")] (k/exits! ctx 0))))

(defn holds [r element]
  (case element
    "name" (= "Lint names the view" (get r "name"))
    "fullName" (= "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName"))
    "historyId" (= "spec-0012" (get r "historyId"))
    "status and stage" (= ["passed" "finished"] [(get r "status") (get r "stage")])
    "start and stop" (let [{s "start" e "stop"} r] (and (integer? s) (integer? e) (<= s e) (> s 1600000000000)))
    "steps" (and (= ["Given a view" "When the lint runs" "Then it names the view"] (map #(get % "name") (get r "steps")))
                 (every? #(and (= "passed" (get % "status")) (integer? (get % "start")) (integer? (get % "stop"))) (get r "steps")))
    "the label feature" (= "Lint" (label r "feature"))
    "the label story" (= "Every view is described" (label r "story"))
    "the label epic" (= "architecture" (label r "epic"))
    "the label suite" (= "architecture" (label r "suite"))
    "the label subSuite" (= "architecture/lint" (label r "subSuite"))
    "the labels tag" (= #{"spec-0012" "e2e" "UN-002"} (set (labels r "tag")))
    "the labels spec, context and component" (= ["spec-0012" "architecture" "Lint"] [(label r "spec") (label r "context") (label r "component")])
    "the label framework" (= "gherkin" (label r "framework"))
    "links" (= [{"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}]
               (map #(select-keys % ["type" "name" "url"]) (get r "links")))))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element _]
    (let [r (one-result ctx "spec-0012")]
      (k/check! (holds r element) element " is not as stated: " (pr-str (dissoc r "file")))
      ctx)))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [ctx]
    (run-project! ctx {:features (one-feature [{:id "spec-0013" :title "Written <written>, kept <kept>"
                                                :steps ["Given <written> is written" "Then <kept> are kept"]
                                                :examples [["written" "kept"] ["plan" "3"] ["note" "4"]]}])
                       :steps all-pass})))

(defn row-result [ctx]
  (first (filter #(= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] (get % "parameters")) (by-spec ctx "spec-0013"))))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx] (k/check! (row-result ctx) "no result holds those parameters: " (pr-str (results ctx))) ctx))

(defstep #"its historyId is \"spec-0013\|plan\|3\", the id and the row's values joined by \|, so each row has its own history"
  (fn [ctx] (k/check! (= "spec-0013|plan|3" (get (row-result ctx) "historyId")) "the historyId is " (get (row-result ctx) "historyId")) ctx))

(defstep #"its result's status is \"failed\", its statusDetails message holds \"no such view\", and its statusDetails hold the trace"
  (fn [ctx]
    (let [r (one-result ctx "spec-0001")]
      (k/check! (= "failed" (get r "status")) "the status is " (get r "status"))
      (k/check! (str/includes? (get-in r ["statusDetails" "message"]) "no such view") "the message is " (get-in r ["statusDetails" "message"]))
      (k/check! (not (str/blank? (get-in r ["statusDetails" "trace"]))) "no trace")
      ctx)))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx] (k/check! (= "skipped" (get-in (one-result ctx "spec-0001") ["steps" 2 "status"])) "the third step is not skipped") ctx))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [ctx]
    (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Evidence" :steps ["Given the app answers"]}])
                       :steps "(defstep #\"the app answers\" (fn [ctx] (attach! \"response\" \"GET / answered 200\") ctx))"})))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (let [a (get-in (one-result ctx "spec-0001") ["steps" 0 "attachments" 0])]
      (k/check! (= ["response" "text/plain"] [(get a "name") (get a "type")]) "the step's attachment is " (pr-str a))
      ctx)))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (let [a (get-in (one-result ctx "spec-0001") ["steps" 0 "attachments" 0])
          f (k/path ctx (str "dhf/allure-results/" (get a "source")))]
      (k/check! (and (fs/exists? f) (= "GET / answered 200" (slurp f))) "no file " f " holds the text")
      ctx)))

(defstep #"a scenario whose (Then step|Given step|When step) (finds what it asserts does not hold|throws an error|throws an error that is not an assertion)"
  (fn [ctx step goes-wrong]
    (let [kw (first (str/split step #" "))
          body (if (= goes-wrong "finds what it asserts does not hold")
                 "(assert (= 1 2) \"it does not hold\")"
                 "(throw (ex-info \"something went wrong\" {}))")
          steps (case kw
                  "Given" ["Given it goes wrong" "When it runs" "Then it holds"]
                  "When" ["Given a thing" "When it goes wrong" "Then it holds"]
                  "Then" ["Given a thing" "When it runs" "Then it goes wrong"])]
      (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Goes wrong" :steps steps}])
                         :steps (str "(defstep #\"it goes wrong\" (fn [ctx] " body "))\n"
                                     "(defstep #\"a thing\" (fn [ctx] ctx))\n(defstep #\"it runs\" (fn [ctx] ctx))\n"
                                     "(defstep #\"it holds\" (fn [ctx] ctx))\n")}))))

(defstep #"its result's status is \"(failed|broken)\""
  (fn [ctx status]
    (k/check! (= status (get (one-result ctx "spec-0001") "status")) "the status is " (get (one-result ctx "spec-0001") "status"))
    ctx))

(defstep #"a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset"
  (fn [ctx]
    (run-project! ctx {:features (one-feature [{:id "spec-0001" :title "Runs"}]) :steps all-pass})))

(defstep #"the results are written into dhf/allure-results"
  (fn [ctx] (k/exits! ctx 0) (k/check! (= 1 (count (results ctx))) "dhf/allure-results holds " (count (results ctx)) " results") ctx))
