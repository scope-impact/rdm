(ns steps.kernel-runner
  "Step definitions of dhf/spec/kernel/runner.feature: each scenario makes a
  project with its own step definitions and runs it with rdm-spec run."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def preamble "(require '[rdm-spec.kernel.runner :refer [defstep attach! fail!]])\n")

(defn steps-file
  "A step definition file holding the given forms (strings)."
  [& forms]
  (str preamble (str/join "\n" forms) "\n"))

(defn runner-project!
  "A project whose kernel component core owns `feature` and whose step
  directory holds `steps`; more files in `extra`."
  ([ctx feature steps] (runner-project! ctx feature steps {} nil))
  ([ctx feature steps extra model-spec]
   (k/project! ctx {:features {"dhf/spec/kernel/core.feature" feature}
                    :model-spec model-spec
                    :extra (merge {"test/steps/core.clj" steps} extra)})))

(defn results
  "Every Allure result in the project's configured results directory, by historyId."
  ([ctx] (results ctx "dhf/allure-results"))
  ([ctx dir]
   (->> (fs/glob (fs/path (k/guard! (:dir ctx)) dir) "*-result.json")
        (map #(assoc (json/parse-string (slurp (fs/file %))) ::file (str %)))
        (sort-by #(get % "historyId")))))

(defn label [r n] (map #(get % "value") (filter #(= n (get % "name")) (get r "labels"))))

(defn only-result [ctx]
  (let [rs (results ctx)]
    (when-not (= 1 (count rs)) (fail! (str (count rs) " results, not one; " (:err ctx))))
    (first rs)))

(def passing "(defstep #\".*\" (fn [ctx & _] ctx))")

;; --- the step API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (assoc ctx ::steps (steps-file "(defstep #\"the spec holds (\\d+) features\" (fn [ctx n] (spit \"called.edn\" (pr-str [ctx n])) ctx))"))))

(defstep #"\"rdm-spec run\" runs a scenario with the step \"Given the spec holds 20 features\""
  (fn [ctx]
    (let [ctx (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"]
                                                   :scenarios [(k/sc 1 "Holds" ["Given the spec holds 20 features"])]})
                               (::steps ctx))]
      (k/rdm ctx ["run"]))))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (let [f (fs/file (k/guard! (:dir ctx)) "called.edn")]
      (assert (fs/exists? f) (str "the function was not called: " (:err ctx)))
      (assert (= [{} "20"] (edn/read-string (slurp f))) (slurp f))
      ctx)))

(def logging-steps
  (steps-file
   "(defn log! [n ctx] (spit \"calls.edn\" (str (pr-str [n ctx]) \"\\n\") :append true))"
   "(defstep #\"the first adds a key\" (fn [ctx] (log! :first ctx) (assoc ctx :first 1)))"
   "(defstep #\"the second adds a key\" (fn [ctx] (log! :second ctx) (assoc ctx :second 2)))"
   "(defstep #\"a step returns no map\" (fn [ctx] (log! :nothing ctx) :not-a-map))"
   "(defstep #\"the context is shown\" (fn [ctx] (log! :shown ctx) ctx))"))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx] (assoc ctx ::steps logging-steps)))

(defstep #"\"rdm-spec run\" runs a scenario with both steps"
  (fn [ctx]
    (let [ctx (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"]
                                                   :scenarios [(k/sc 1 "Both" ["Given the first adds a key" "And the second adds a key"
                                                                                "And a step returns no map" "Then the context is shown"])
                                                               (k/sc 2 "Next" ["Given the first adds a key"])]})
                               (::steps ctx))]
      (k/rdm ctx ["run"]))))

(defn calls [ctx]
  (let [f (fs/file (k/guard! (:dir ctx)) "calls.edn")]
    (assert (fs/exists? f) (str "no step was called: " (:err ctx)))
    (mapv edn/read-string (str/split-lines (slurp f)))))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (assert (= [:second {:first 1}] (second (calls ctx))) (pr-str (calls ctx)))
    ctx))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (assert (= [:first {}] (nth (calls ctx) 4)) (pr-str (calls ctx)))
    ctx))

(defstep #"a step whose function returns anything but a map leaves the context as it was"
  (fn [ctx]
    (assert (= [:shown {:first 1 :second 2}] (nth (calls ctx) 3)) (pr-str (calls ctx)))
    ctx))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [ctx] (assoc ctx ::steps (steps-file "(defstep #\"a spec\" (fn [ctx] ctx))" "(defstep #\"a (\\w+)\" (fn [ctx w] ctx))"))))

(defstep #"\"rdm-spec run\" runs a scenario with that step"
  (fn [ctx]
    (let [ctx (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Ambiguous" ["Given a spec"])]})
                               (::steps ctx))]
      (k/rdm ctx ["run"]))))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (only-result ctx)
          m (get-in r ["statusDetails" "message"])]
      (assert (= "broken" (get r "status")) (get r "status"))
      (assert (and (str/includes? m "a spec") (str/includes? m "a (\\w+)")) m)
      ctx)))

;; --- results and their labels

(def lint-model {:contexts [["architecture" ["lint"]]] :kernel ["core"]})

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Kernel scenario")]})
                     (steps-file passing)
                     {"dhf/spec/architecture/lint.feature"
                      (k/feature-text {:name "Lint" :tags ["UN-001"]
                                       :scenarios [(k/sc 12 "Lint names the view" ["Given a view" "When it is linted" "Then it is named"])]})}
                     lint-model)))

(defn result-of [ctx id]
  (let [rs (filter #(some #{id} (label % "spec")) (results ctx))]
    (when (empty? rs) (fail! (str "no result for " id "; " (:err ctx))))
    (first rs)))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (let [rs (filter #(some #{"spec-0012"} (label % "spec")) (results ctx))
          r (first rs)]
      (assert (= 1 (count rs)) (str (count rs) " results"))
      (assert (= ["Lint"] (label r "component")))
      (assert (= ["architecture"] (label r "context")))
      ctx)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx]
    (assert (= ["kernel"] (label (result-of ctx "spec-0001") "context")))
    ctx))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [steps (get (result-of ctx "spec-0012") "steps")]
      (assert (= ["Given a view" "When it is linted" "Then it is named"] (map #(get % "name") steps)))
      (assert (every? #(= "passed" (get % "status")) steps))
      ctx)))

(defstep #"it writes three Allure results, each labelled with the outline's id, sharing its name, and told apart by its parameters and historyId"
  (fn [ctx]
    (let [rs (results ctx)]
      (assert (= 3 (count rs)) (str (count rs) " results; " (:err ctx)))
      (assert (every? #(= ["spec-0001"] (label % "spec")) rs))
      (assert (apply = (map #(get % "name") rs)))
      (assert (= 3 (count (set (map #(get % "parameters") rs)))))
      (assert (= 3 (count (set (map #(get % "historyId") rs)))))
      ctx)))

;; --- what went wrong

(defstep #"a scenario whose second step, a Then, finds what it asserts does not hold"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"]
                                          :scenarios [(k/sc 1 "Fails" ["Given a view" "Then the view is drawn" "And it is fresh"])]})
                     (steps-file "(defstep #\"a view\" (fn [ctx] ctx))"
                                 "(defstep #\"the view is drawn\" (fn [ctx] (assert false \"the view is missing\")))"
                                 "(defstep #\"it is fresh\" (fn [ctx] ctx))"))))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (let [r (only-result ctx) m (get-in r ["statusDetails" "message"])]
      (assert (= "failed" (get r "status")))
      (assert (str/includes? m "Then the view is drawn") m)
      (assert (str/includes? m "the view is missing") m)
      ctx)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (assert (= ["passed" "failed" "skipped"] (map #(get % "status") (get (only-result ctx) "steps"))))
    ctx))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Unmatched" ["Given nothing defines this"])]})
                     (steps-file "(defstep #\"a view\" (fn [ctx] ctx))"))))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx] (assert (= "broken" (get (only-result ctx) "status"))) ctx))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (assert (str/includes? (get-in (only-result ctx) ["statusDetails" "message"]) "Given nothing defines this"))
    ctx))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"]
                                          :scenarios [{:tags ["spec-0001" "e2e" "wip"] :title "Planned" :steps ["Given it runs"]}]})
                     (steps-file "(defstep #\"it runs\" (fn [ctx] (spit \"ran\" \"yes\") ctx))"))))

(defstep #"the scenario's steps are not run"
  (fn [ctx]
    (assert (not (fs/exists? (fs/file (k/guard! (:dir ctx)) "ran"))) "a planned scenario's step ran")
    ctx))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (only-result ctx)]
      (assert (= "skipped" (get r "status")))
      (assert (= ["true"] (label r "planned")))
      ctx)))

;; --- discovery

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "One")]})
                     (steps-file passing)
                     (into {} (for [i (range 2 21)]
                                [(str "dhf/spec/more/f" i ".feature")
                                 (k/feature-text {:name (str "F" i) :tags ["UN-001"] :scenarios [(k/sc i (str "Scenario " i))]})]))
                     nil)))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (let [rs (results ctx)]
      (assert (= 20 (count rs)) (str (count rs) " results; " (:err ctx)))
      (assert (= 20 (count (set (mapcat #(label % "spec") rs)))))
      ctx)))

(defstep #"a spec directory holding no feature file"
  (fn [ctx]
    (let [ctx (runner-project! ctx "" (steps-file passing))]
      (fs/delete (fs/path (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature"))
      ctx)))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-has ctx "no scenario was found")
    (assert (empty? (results ctx)))
    ctx))

;; --- Allure's own model

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint.feature, owned by the component Lint of the bounded context architecture"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Kernel")]})
                     (steps-file passing)
                     {"dhf/spec/architecture/lint.feature"
                      (k/feature-text {:name "Lint"
                                       :rules [{:name "Every view is described"
                                                :scenarios [{:tags ["spec-0012" "e2e" "UN-002"] :title "Lint names the view"
                                                             :steps ["Given a view with no description" "When the model is linted" "Then the view is named"]}]}]})}
                     lint-model)))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx]
    (let [ctx (k/rdm ctx ["run"])]
      (assert (= "passed" (get (result-of ctx "spec-0012") "status")) (:err ctx))
      ctx)))

(defn- check-element [ctx element]
  (let [r (result-of ctx "spec-0012")
        one (fn [n v] (assert (= [v] (label r n)) (str n ": " (pr-str (label r n)))))]
    (case element
      "name" (assert (= "Lint names the view" (get r "name")))
      "fullName" (assert (= "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName")))
      "historyId" (assert (= "spec-0012" (get r "historyId")))
      "status and stage" (assert (= ["passed" "finished"] [(get r "status") (get r "stage")]))
      "start and stop" (let [s (get r "start") e (get r "stop") now (System/currentTimeMillis)]
                         (assert (and (integer? s) (integer? e) (<= s e) (< (- now s) 600000))))
      "steps" (let [ss (get r "steps")]
                (assert (= ["Given a view with no description" "When the model is linted" "Then the view is named"]
                           (map #(get % "name") ss)))
                (assert (every? #(and (= "passed" (get % "status")) (integer? (get % "start")) (integer? (get % "stop"))) ss)))
      "the label feature" (one "feature" "Lint")
      "the label story" (one "story" "Every view is described")
      "the label epic" (one "epic" "architecture")
      "the label suite" (one "suite" "architecture")
      "the label subSuite" (one "subSuite" "architecture/lint")
      "the labels tag" (assert (= #{"spec-0012" "e2e" "UN-002"} (set (label r "tag"))) (pr-str (label r "tag")))
      "the labels spec, context and component" (do (one "spec" "spec-0012") (one "context" "architecture") (one "component" "Lint"))
      "the label framework" (one "framework" "gherkin")
      "links" (assert (= [{"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}] (get r "links"))
                      (pr-str (get r "links")))
      (fail! (str "no such element: " element)))
    ctx))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element _] (check-element ctx (str/trim element))))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"]
                                          :scenarios [{:tags ["spec-0013" "contract"] :title "Rows"
                                                       :steps ["Given <written> is written" "Then <kept> are kept"]
                                                       :examples [["written" "kept"] ["plan" "3"] ["spec" "4"]]}]})
                     (steps-file passing))))

(defn row-result [ctx v]
  (or (first (filter #(= v (get-in % ["parameters" 0 "value"])) (results ctx)))
      (fail! (str "no result for the row " v "; " (:err ctx)))))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx]
    (assert (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] (get (row-result ctx "plan") "parameters")))
    ctx))

(defstep #"its historyId is \"spec-0013\|plan\|3\", the id and the row's values joined by \|, so each row has its own history"
  (fn [ctx]
    (assert (= "spec-0013|plan|3" (get (row-result ctx "plan") "historyId")))
    (assert (= "spec-0013|spec|4" (get (row-result ctx "spec") "historyId")))
    ctx))

(defstep #"a scenario whose second step, a Then, fails its assertion with the message \"no such view\""
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"]
                                          :scenarios [(k/sc 1 "Fails" ["Given a view" "Then the view exists" "And it is fresh"])]})
                     (steps-file "(defstep #\"a view\" (fn [ctx] ctx))"
                                 "(defstep #\"the view exists\" (fn [ctx] (fail! \"no such view\")))"
                                 "(defstep #\"it is fresh\" (fn [ctx] ctx))"))))

(defstep #"its result's status is \"failed\", its statusDetails message holds \"no such view\", and its statusDetails hold the trace"
  (fn [ctx]
    (let [r (only-result ctx)]
      (assert (= "failed" (get r "status")))
      (assert (str/includes? (get-in r ["statusDetails" "message"]) "no such view"))
      (assert (not (str/blank? (get-in r ["statusDetails" "trace"]))))
      ctx)))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx]
    (assert (= "skipped" (get-in (only-result ctx) ["steps" 2 "status"])))
    ctx))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [ctx]
    (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Attaches" ["Given the app answers"])]})
                     (steps-file "(defstep #\"the app answers\" (fn [ctx] (attach! \"response\" \"GET / answered 200\") ctx))"))))

(defn attachment [ctx]
  (let [r (only-result ctx)
        a (first (get-in r ["steps" 0 "attachments"]))]
    (when-not a (fail! (str "no attachment on the step: " (pr-str r))))
    [r a]))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (let [[_ a] (attachment ctx)]
      (assert (= "response" (get a "name")))
      (assert (= "text/plain" (get a "type")))
      ctx)))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (let [[r a] (attachment ctx)
          f (fs/file (fs/parent (::file r)) (get a "source"))]
      (assert (fs/exists? f) (str f))
      (assert (= "GET / answered 200" (slurp f)))
      ctx)))

(def going-wrong
  {"finds what it asserts does not hold" "(assert (= 1 2) \"it does not hold\")"
   "throws an error" "(throw (Exception. \"an error\"))"
   "throws an error that is not an assertion" "(throw (IllegalStateException. \"not an assertion\"))"})

(defstep #"a scenario whose (Then|Given|When) step (.+)"
  (fn [ctx kw wrong]
    (let [body (or (going-wrong wrong) (fail! (str "unknown: " wrong)))
          steps ["Given a view" "When the view is drawn" "Then the view is fresh"]]
      (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "Goes wrong" steps)]})
                       (steps-file (str "(defstep #\"a view\" (fn [ctx] " (if (= kw "Given") body "ctx") "))")
                                   (str "(defstep #\"the view is drawn\" (fn [ctx] " (if (= kw "When") body "ctx") "))")
                                   (str "(defstep #\"the view is fresh\" (fn [ctx] " (if (= kw "Then") body "ctx") "))"))))))

(defstep #"its result's status is \"(\w+)\""
  (fn [ctx status]
    (assert (= status (get (only-result ctx) "status")) (get (only-result ctx) "status"))
    ctx))

(defstep #"a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset"
  (fn [ctx]
    (-> (runner-project! ctx (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "One")]}) (steps-file passing))
        (assoc-in [:env "RESULTS_DIR"] nil))))

(defstep #"the results are written into dhf/allure-results"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= 1 (count (results ctx "dhf/allure-results"))))
    ctx))
