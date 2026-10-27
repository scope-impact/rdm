(ns steps.kernel-runner
  "Step definitions of dhf/spec/kernel/runner.feature: each scenario's
  project holds its own step definitions, which \"rdm-spec run\" runs."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def header "(require '[rdm-spec.kernel.runner :refer [defstep fail! attach!]])\n")

(defn run-project!
  "A project whose core feature is `feature` (a k/feature-text map) and whose
  step definitions are `steps` (Clojure source)."
  [ctx feature steps & [extra]]
  (k/project! ctx {:features {k/core-feature (k/feature-text feature)}
                   :extra (merge {"test/steps/steps.clj" (str header steps)} extra)}))

(defn results
  "Every result \"rdm-spec run\" wrote, as JSON maps with :file."
  ([ctx] (results ctx "dhf/allure-results"))
  ([ctx dir]
   (let [d (fs/path (k/guard! (:dir ctx)) dir)]
     (if (fs/directory? d)
       (vec (for [f (sort (map str (fs/glob d "*-result.json")))]
              (assoc (json/parse-string (slurp f)) :file f)))
       []))))

(defn label [r n] (some #(when (= n (get % "name")) (get % "value")) (get r "labels")))
(defn labels [r n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get r "labels"))))

(defn the-result [ctx]
  (let [rs (results ctx)]
    (when-not (= 1 (count rs)) (fail! (str (count rs) " results were written: " (:err ctx))))
    (first rs)))

(defn file-text [ctx f] (let [p (fs/file (:dir ctx) f)] (when (fs/exists? p) (slurp p))))

;; ---------------------------------------------------------------------------
;; Step definitions are written against one small API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (assoc ctx ::steps "(defstep #\"the spec holds (\\d+) features\" (fn [ctx n] (spit \"called.edn\" (pr-str [ctx n])) ctx))\n")))

(defstep #"\"rdm-spec run\" runs a scenario with the step \"(.+)\""
  (fn [ctx step]
    (-> (run-project! ctx {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Counts" [step])]} (::steps ctx))
        (k/rdm ["run"]))))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [{} "20"] (edn/read-string (file-text ctx "called.edn"))) (file-text ctx "called.edn"))
    ctx))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (assoc ctx ::steps (str "(defstep #\"the first adds a key\" (fn [ctx] (assoc ctx :first 1)))\n"
                            "(defstep #\"nothing is returned\" (fn [ctx] (spit \"nil-step.edn\" (pr-str ctx)) nil))\n"
                            "(defstep #\"the second adds a key\" (fn [ctx] (spit \"second.edn\" (pr-str ctx)) (assoc ctx :second 2)))\n"
                            "(defstep #\"the context is recorded\" (fn [ctx] (spit \"recorded.edn\" (pr-str ctx)) ctx))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with both steps"
  (fn [ctx]
    (-> (run-project! ctx {:name "Core" :tags ["UN-001"]
                           :scenarios [(k/sc 1 "Both" ["Given the first adds a key" "When nothing is returned" "Then the second adds a key"])
                                       (k/sc 2 "Next" ["Given the context is recorded"])]}
                      (::steps ctx) {"dhf/spec/ids.edn" "{:issued 2}\n"})
        (k/rdm ["run"]))))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= {:first 1} (edn/read-string (file-text ctx "second.edn"))) (file-text ctx "second.edn"))
    ctx))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx] (assert (= {} (edn/read-string (file-text ctx "recorded.edn"))) (file-text ctx "recorded.edn")) ctx))

(defstep #"a step whose function returns anything but a map leaves the context as it was"
  (fn [ctx]
    (assert (= {:first 1} (edn/read-string (file-text ctx "nil-step.edn"))))
    (assert (= {:first 1} (edn/read-string (file-text ctx "second.edn"))))
    ctx))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [ctx]
    (assoc ctx ::steps (str "(defstep #\"a spec\" (fn [ctx] ctx))\n"
                            "(defstep #\"a (\\w+)\" (fn [ctx w] ctx))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with that step"
  (fn [ctx]
    (-> (run-project! ctx {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Ambiguous" ["Given a spec"])]} (::steps ctx))
        (k/rdm ["run"]))))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [r (the-result ctx) msg (get-in r ["statusDetails" "message"])]
      (assert (= "broken" (get r "status")) (pr-str r))
      (assert (and (str/includes? msg "a spec") (str/includes? msg "a (\\w+)")) msg)
      ctx)))

;; ---------------------------------------------------------------------------
;; Every scenario run leaves one result, labelled with what it verifies

(def passing (str "(defstep #\".*\" (fn [ctx] ctx))\n"))

(defn lint-project!
  "The scenario @spec-0012 in Lint of architecture, and @spec-0001 in the kernel's core."
  [ctx]
  (k/project! ctx {:model-spec {:contexts [["architecture" ["lint"]]] :kernel ["core"]}
                   :features {"dhf/spec/architecture/lint.feature"
                              (k/feature-text {:name "Lint"
                                               :rules [{:name "Every view is described"
                                                        :scenarios [(assoc (k/sc 12 "Lint names the view") :tags ["spec-0012" "e2e" "UN-002"])]}]})}
                   :extra {"test/steps/steps.clj" (str header passing)
                           "dhf/spec/ids.edn" "{:issued 12}\n"
                           "dhf/spec/needs.edn" (k/pr-edn {"UN-001" {:text "One" :sources ["demo:README.md@abc1234"]}
                                                           "UN-002" {:text "Two" :sources ["demo:README.md@abc1234"]}})}}))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [ctx] (lint-project! ctx)))

(defn result-of [ctx id]
  (or (first (filter #(= id (label % "spec")) (results ctx))) (fail! (str "no result for " id ": " (:err ctx)))))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [rs (filter #(= "spec-0012" (label % "spec")) (results ctx)) r (first rs)]
      (assert (= 1 (count rs)))
      (assert (= ["Lint" "architecture"] [(label r "component") (label r "context")]) (pr-str (get r "labels")))
      ctx)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx] (assert (= "kernel" (label (result-of ctx "spec-0001") "context"))) ctx))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [steps (get (result-of ctx "spec-0012") "steps")]
      (assert (= ["Given a step" "When it runs" "Then it passes"] (map #(get % "name") steps)) (pr-str steps))
      (assert (every? #(= "passed" (get % "status")) steps))
      ctx)))

(defstep #"it writes three Allure results, each labelled with the outline's id, sharing its name, and told apart by its parameters and historyId"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [rs (results ctx)]
      (assert (= 3 (count rs)) (pr-str rs))
      (assert (every? #(= "spec-0001" (label % "spec")) rs))
      (assert (= #{"An outline"} (set (map #(get % "name") rs))))
      (assert (= 3 (count (set (map #(get % "parameters") rs)))))
      (assert (= #{"spec-0001|1" "spec-0001|2" "spec-0001|3"} (set (map #(get % "historyId") rs))))
      ctx)))

(def failing-then
  (str "(defstep #\"a view\" (fn [ctx] ctx))\n"
       "(defstep #\"the view is named\" (fn [ctx] (assert false \"no such view\") ctx))\n"
       "(defstep #\"nothing else runs\" (fn [ctx] (spit \"ran.txt\" \"ran\") ctx))\n"))

(def failing-scenario
  {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Fails" ["Given a view" "Then the view is named" "And nothing else runs"])]})

(defstep #"a scenario whose second step, a Then, finds what it asserts does not hold"
  (fn [ctx] (run-project! ctx failing-scenario failing-then)))

(defstep #"a scenario whose second step, a Then, fails its assertion with the message \"no such view\""
  (fn [ctx] (run-project! ctx failing-scenario failing-then)))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [r (the-result ctx) msg (get-in r ["statusDetails" "message"])]
      (assert (= "failed" (get r "status")))
      (assert (and (str/includes? msg "the view is named") (str/includes? msg "no such view")) msg)
      ctx)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [steps (get (the-result ctx) "steps")]
      (assert (= ["passed" "failed" "skipped"] (map #(get % "status") steps)) (pr-str steps))
      (assert (nil? (file-text ctx "ran.txt")))
      ctx)))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx]
    (run-project! ctx {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Unmatched" ["Given nothing defines this step"])]}
                  "(defstep #\"something else\" (fn [ctx] ctx))\n")))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx] (k/check-exit ctx 1) (assert (= "broken" (get (the-result ctx) "status"))) ctx))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (assert (str/includes? (get-in (the-result ctx) ["statusDetails" "message"]) "Given nothing defines this step"))
    ctx))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (run-project! ctx {:name "Core" :tags ["UN-001"] :scenarios [(update (k/sc 1 "Planned" ["Given it is planned"]) :tags conj "wip")]}
                  "(defstep #\"it is planned\" (fn [ctx] (spit \"ran.txt\" \"ran\") ctx))\n")))

(defstep #"the scenario's steps are not run"
  (fn [ctx] (k/check-exit ctx 0) (assert (nil? (file-text ctx "ran.txt"))) ctx))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (the-result ctx)]
      (assert (= "skipped" (get r "status")))
      (assert (= "true" (label r "planned")) (pr-str (get r "labels")))
      ctx)))

;; ---------------------------------------------------------------------------
;; The runner discovers, and refuses to find nothing

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (let [comps (map #(str "part" %) (range 1 21))
          ctx (k/project! ctx {:model-spec {:contexts [["shop" comps]]}
                       :features (into {} (map-indexed (fn [i c] [(str "dhf/spec/shop/" c ".feature")
                                                                  (k/feature-text {:name c :tags ["UN-001"] :scenarios [(k/sc (inc i) (str "About " c))]})])
                                                       comps))
                       :extra {"test/steps/steps.clj" (str header passing) "dhf/spec/ids.edn" "{:issued 20}\n"}})]
      (fs/delete (fs/path (:dir ctx) k/core-feature))
      ctx)))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (set (map #(format "spec-%04d" %) (range 1 21))) (set (map #(label % "spec") (results ctx)))))
    ctx))

(defstep #"a spec directory holding no feature file"
  (fn [ctx]
    (let [ctx (k/project! ctx {:extra {"test/steps/steps.clj" (str header passing)}})]
      (fs/delete (fs/path (:dir ctx) k/core-feature))
      ctx)))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "no scenario")
    (assert (empty? (results ctx)))
    ctx))

;; ---------------------------------------------------------------------------
;; Every result is written in Allure's own model

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint.feature, owned by the component Lint of the bounded context architecture"
  (fn [ctx] (lint-project! ctx)))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx] (-> (k/project! ctx) (k/rdm ["run"]) (k/check-exit 0))))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element value]
    (let [r (result-of ctx "spec-0012")
          is (fn [want got] (when-not (= want got) (fail! (str element ": " (pr-str got) ", not " (pr-str want)))))]
      (case element
        "name" (is "Lint names the view" (get r "name"))
        "fullName" (is "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName"))
        "historyId" (is "spec-0012" (get r "historyId"))
        "status and stage" (is ["passed" "finished"] [(get r "status") (get r "stage")])
        "start and stop" (let [now (System/currentTimeMillis)]
                           (assert (<= (- now 600000) (get r "start") (get r "stop") now) (pr-str [(get r "start") (get r "stop")])))
        "steps" (do (is ["Given a step" "When it runs" "Then it passes"] (map #(get % "name") (get r "steps")))
                    (doseq [s (get r "steps")]
                      (is "passed" (get s "status"))
                      (assert (<= (get s "start") (get s "stop")))))
        "the label feature" (is "Lint" (label r "feature"))
        "the label story" (is "Every view is described" (label r "story"))
        "the label epic" (is "architecture" (label r "epic"))
        "the label suite" (is "architecture" (label r "suite"))
        "the label subSuite" (is "architecture/lint" (label r "subSuite"))
        "the labels tag" (is #{"spec-0012" "e2e" "UN-002"} (set (labels r "tag")))
        "the labels spec, context and component" (is ["spec-0012" "architecture" "Lint"]
                                                      [(label r "spec") (label r "context") (label r "component")])
        "the label framework" (is "gherkin" (label r "framework"))
        "links" (is [{"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}] (get r "links"))
        (fail! (str "no such element: " element)))
      ctx)))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [ctx]
    (run-project! ctx {:name "Core" :tags ["UN-001"]
                       :scenarios [{:tags ["spec-0013" "e2e"] :title "Rows" :outline true
                                    :steps ["Given <written> is written" "Then <kept> are kept"]
                                    :examples [["written" "kept"] ["plan" "3"]]}]}
                  passing {"dhf/spec/ids.edn" "{:issued 13}\n"})))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] (get (the-result ctx) "parameters")))
    ctx))

(defstep #"its historyId is \"spec-0013\|plan\|3\", the id and the row's values joined by \|, so each row has its own history"
  (fn [ctx] (assert (= "spec-0013|plan|3" (get (the-result ctx) "historyId"))) ctx))

(defstep #"its result's status is \"failed\", its statusDetails message holds \"no such view\", and its statusDetails hold the trace"
  (fn [ctx]
    (let [r (the-result ctx)]
      (assert (= "failed" (get r "status")))
      (assert (str/includes? (get-in r ["statusDetails" "message"]) "no such view"))
      (assert (not (str/blank? (get-in r ["statusDetails" "trace"]))))
      ctx)))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx] (assert (= "skipped" (get-in (the-result ctx) ["steps" 2 "status"]))) ctx))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [ctx]
    (run-project! ctx {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Attaches" ["Given the app answers"])]}
                  "(defstep #\"the app answers\" (fn [ctx] (attach! \"response\" \"GET / answered 200\") ctx))\n")))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [a (get-in (the-result ctx) ["steps" 0 "attachments" 0])]
      (assert (= ["response" "text/plain"] [(get a "name") (get a "type")]) (pr-str a))
      ctx)))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (let [a (get-in (the-result ctx) ["steps" 0 "attachments" 0])]
      (assert (= "GET / answered 200" (slurp (fs/file (:dir ctx) "dhf/allure-results" (get a "source")))))
      ctx)))

(def going-wrong
  {["Then" "finds what it asserts does not hold"] "(assert (= 1 2) \"one is not two\")"
   ["Given" "throws an error"] "(throw (ex-info \"boom\" {}))"
   ["When" "throws an error"] "(throw (ex-info \"boom\" {}))"
   ["Then" "throws an error that is not an assertion"] "(/ 1 0)"})

(defstep #"a scenario whose (Given|When|Then) step (.+)"
  (fn [ctx kw what]
    (let [body (or (going-wrong [kw what]) (fail! (str "no such case: " kw " " what)))
          steps (case kw
                  "Given" ["Given it goes wrong" "When it runs" "Then it holds"]
                  "When" ["Given it is set up" "When it goes wrong" "Then it holds"]
                  "Then" ["Given it is set up" "When it runs" "Then it goes wrong"])]
      (run-project! ctx {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Goes wrong" steps)]}
                    (str "(defstep #\"it goes wrong\" (fn [ctx] " body " ctx))\n"
                         "(defstep #\"it is set up|it runs|it holds\" (fn [ctx] ctx))\n")))))

(defstep #"its result's status is \"(\w+)\""
  (fn [ctx status] (assert (= status (get (the-result ctx) "status")) (pr-str (the-result ctx))) ctx))

(defstep #"a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset"
  (fn [ctx]
    (let [ctx (k/project! ctx {:config {:results-dir "dhf/allure-results"} :extra {"test/steps/steps.clj" (str header passing)}})]
      (assert (nil? (get-in ctx [:env "RESULTS_DIR"])))
      ctx)))

(defstep #"the results are written into dhf/allure-results"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= ["spec-0001"] (map #(label % "spec") (results ctx))))
    ctx))
