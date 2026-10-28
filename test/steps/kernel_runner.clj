(ns steps.kernel-runner
  "Steps of the kernel component Gherkin runner (05-verify-by-results/runner.feature).
  Each scenario's project holds its own step definitions, in its configured
  step directory, written against the runner's step API."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def lint-model
  {:system {:id :s :name "S" :description "d"} :people [{:id :p :name "P" :actor #{:human} :description "d"}]
   :containers [{:id :c :name "C" :kind :runs :technology "t" :description "d"
                 :contexts [{:id :architecture :name "architecture" :description "d"
                             :components [{:id :architecture/lint :name "Lint" :description "d" :spec "architecture/lint"}]}]}]
   :kernel [{:id :kernel/bus :name "Event bus" :description "d" :spec "kernel/bus"}]
   :relationships [{:from :p :to :c :description "uses"}]
   :views [{:key "architecture-components" :level :component :context :architecture :description "d"}]})

(def require-api "(require '[rdm-spec.kernel.runner :refer [defstep attach! fail!]])\n")

(defn run-project!
  "A project whose spec is features {rel text} and whose step definitions are steps (text)."
  [features steps & {:keys [model]}]
  (let [ctx (k/spec-project! features :model (or model lint-model) :no-till true)]
    (k/write! (:dir ctx) "test/steps/steps.clj" (str require-api steps))
    ctx))

(defn results
  "Every result the last run wrote, parsed, with :file."
  [ctx & [dir]]
  (let [d (or dir (k/path (:dir ctx) "dhf/allure-results"))]
    (if (fs/exists? d)
      (vec (for [f (sort (map str (fs/glob d "*-result.json")))] (assoc (k/json-file f) :file f)))
      [])))

(defn labels [r n] (vec (for [l (get r "labels") :when (= n (get l "name"))] (get l "value"))))
(defn label [r n] (first (labels r n)))
(defn by-spec [ctx id] (first (filter #(= id (label % "spec")) (results ctx))))

(defn one-feature [id title steps & {:keys [tags]}]
  {"architecture/lint.feature" (k/feature-text {:name "Lint" :tags (or tags ["UN-001"]) :scenarios [{:id id :title title :steps steps}]})})

;; ------------------------------------------------------------------ the step API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [_]
    (run-project! (one-feature "spec-0001" "Holds" ["Given the spec holds 20 features"])
                  "(defstep #\"the spec holds (\\d+) features\" (fn [ctx n] (spit \"called.edn\" (pr-str [ctx n])) ctx))\n")))

(defstep #"\"rdm-spec run\" runs a scenario with (?:the step \"Given the spec holds 20 features\"|both steps|that step)"
  (fn [ctx] (k/rdm! ctx "run")))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (k/expect! (= [{} "20"] (edn/read-string (str (k/slurp-in (:dir ctx) "called.edn")))) ctx "not called with {} and \"20\"")
    ctx))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [_]
    (run-project! {"architecture/lint.feature"
                   (k/feature-text {:name "Lint" :scenarios [{:id "spec-0001" :title "First" :steps ["Given the first adds :a" "And the second adds :b" "And a step returns nil" "Then it records"]}
                                                             {:id "spec-0002" :title "Second" :steps ["Given the first adds :a"]}]})}
                  (str "(defstep #\"the first adds :a\" (fn [ctx] (spit \"seen.edn\" (pr-str ctx) :append true) (spit \"seen.edn\" \"\\n\" :append true) (assoc ctx :a 1)))\n"
                       "(defstep #\"the second adds :b\" (fn [ctx] (spit \"second.edn\" (pr-str ctx)) (assoc ctx :b 2)))\n"
                       "(defstep #\"a step returns nil\" (fn [ctx] nil))\n"
                       "(defstep #\"it records\" (fn [ctx] (spit \"last.edn\" (pr-str ctx)) ctx))\n"))))

(defstep #"the second function receives the key the first added"
  (fn [ctx] (k/expect! (= {:a 1} (edn/read-string (k/slurp-in (:dir ctx) "second.edn"))) ctx "the second did not receive :a") ctx))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (k/expect! (= [{} {}] (map edn/read-string (str/split-lines (k/slurp-in (:dir ctx) "seen.edn")))) ctx "a scenario did not start empty")
    ctx))

(defstep #"a step whose function returns anything but a map leaves the context as it was"
  (fn [ctx] (k/expect! (= {:a 1 :b 2} (edn/read-string (k/slurp-in (:dir ctx) "last.edn"))) ctx "nil changed the context") ctx))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [_] (run-project! (one-feature "spec-0001" "Ambiguous" ["Given a spec"])
                        "(defstep #\"a spec\" (fn [ctx] ctx))\n(defstep #\"a .*\" (fn [ctx] ctx))\n")))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (by-spec ctx "spec-0001") msg (get-in r ["statusDetails" "message"])]
      (k/expect! (= "broken" (get r "status")) ctx "not broken")
      (k/expect! (and (str/includes? msg "#\"a spec\"") (str/includes? msg "#\"a .*\"")) ctx (str "does not name both: " msg)))
    ctx))

;; ------------------------------------------------------------------ one result per scenario

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [_]
    (run-project! {"architecture/lint.feature" (k/feature-text {:name "Lint" :scenarios [{:id "spec-0012" :steps ["Given a thing" "Then it holds"]}]})
                   "kernel/bus.feature" (k/feature-text {:name "Event bus" :scenarios [{:id "spec-0013"}]})}
                  "(defstep #\".*\" (fn [ctx] ctx))\n")))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (let [rs (filter #(= "spec-0012" (label % "spec")) (results ctx))]
      (k/expect! (= 1 (count rs)) ctx "not one result")
      (k/expect! (= ["Lint" "architecture"] [(label (first rs) "component") (label (first rs) "context")]) ctx "not labelled Lint, architecture"))
    ctx))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx] (k/expect! (= "kernel" (label (by-spec ctx "spec-0013") "context")) ctx "the kernel scenario is not context=kernel") ctx))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [steps (get (by-spec ctx "spec-0012") "steps")]
      (k/expect! (= [["Given a thing" "passed"] ["Then it holds" "passed"]] (map (juxt #(get % "name") #(get % "status")) steps)) ctx "not one step per Gherkin step"))
    ctx))

(defstep #"it writes three Allure results, each labelled with the outline's id, sharing its name, and told apart by its parameters and historyId"
  (fn [ctx]
    (let [rs (filter #(= "spec-0013" (label % "spec")) (results ctx))]
      (k/expect! (= 3 (count rs)) ctx "not three results")
      (k/expect! (= 1 (count (distinct (map #(get % "name") rs)))) ctx "the names differ")
      (k/expect! (= 3 (count (distinct (map #(get % "historyId") rs)))) ctx "the historyIds do not differ")
      (k/expect! (= #{[{"name" "x" "value" "a"}] [{"name" "x" "value" "b"}] [{"name" "x" "value" "c"}]} (set (map #(get % "parameters") rs))) ctx "the parameters are not the rows"))
    ctx))

(def failing-steps
  (str "(defstep #\"a thing\" (fn [ctx] ctx))\n"
       "(defstep #\"it does not hold\" (fn [ctx] (assert (= 1 2) \"no such view\") ctx))\n"
       "(defstep #\"it fails with no such view\" (fn [ctx] (fail! \"no such view\")))\n"
       "(defstep #\"it holds\" (fn [ctx] ctx))\n"))

(defstep #"a scenario whose second step, a Then, finds what it asserts does not hold"
  (fn [_] (run-project! (one-feature "spec-0001" "Fails" ["Given a thing" "Then it does not hold" "And it holds"]) failing-steps)))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (let [r (by-spec ctx "spec-0001") msg (get-in r ["statusDetails" "message"])]
      (k/expect! (= "failed" (get r "status")) ctx "not failed")
      (k/expect! (and (str/includes? msg "Then it does not hold") (str/includes? msg "no such view")) ctx (str "the message: " msg)))
    ctx))

(defstep #"the steps after it are reported as not run"
  (fn [ctx] (k/expect! (= "skipped" (get (nth (get (by-spec ctx "spec-0001") "steps") 2) "status")) ctx "the third step ran") ctx))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [_] (run-project! (one-feature "spec-0001" "Unmatched" ["Given a thing" "When nobody defined this"]) failing-steps)))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx] (k/expect! (= "broken" (get (by-spec ctx "spec-0001") "status")) ctx "not broken") ctx))

(defstep #"the result names the unmatched step"
  (fn [ctx] (k/expect! (str/includes? (get-in (by-spec ctx "spec-0001") ["statusDetails" "message"]) "nobody defined this") ctx "the step is not named") ctx))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [_]
    (run-project! {"architecture/lint.feature" (k/feature-text {:name "Lint" :scenarios [{:id "spec-0001" :wip true :steps ["Given a planned step"]}]})}
                  "(defstep #\"a planned step\" (fn [ctx] (spit \"ran.txt\" \"ran\") ctx))\n")))

(defstep #"the scenario's steps are not run"
  (fn [ctx] (k/expect! (not (fs/exists? (k/path (:dir ctx) "ran.txt"))) ctx "a planned step ran") ctx))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (by-spec ctx "spec-0001")]
      (k/expect! (= ["skipped" "true"] [(get r "status") (label r "planned")]) ctx "not skipped and planned"))
    ctx))

;; ------------------------------------------------------------------ discovering

(defstep #"a spec holding twenty feature files"
  (fn [_]
    (let [comps (for [i (range 20)] {:id (keyword "architecture" (str "c" i)) :name (str "C" i) :description "d" :spec (str "architecture/c" i)})
          m (assoc-in lint-model [:containers 0 :contexts 0 :components] (vec comps))]
      (run-project! (into {} (for [i (range 20)] [(str "architecture/c" i ".feature")
                                                   (k/feature-text {:name (str "C" i) :scenarios [{:id (format "spec-%04d" (inc i))}]})]))
                    "(defstep #\".*\" (fn [ctx] ctx))\n" :model m))))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (k/expect! (= (set (for [i (range 1 21)] (format "spec-%04d" i))) (set (map #(label % "spec") (results ctx)))) ctx "not all twenty ran")
    ctx))

(defstep #"a spec directory holding no feature file"
  (fn [_] (run-project! {} "(defstep #\".*\" (fn [ctx] ctx))\n")))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "no scenario was found") (k/expect! (empty? (results ctx)) ctx "a result was written") ctx))

;; ------------------------------------------------------------------ Allure's own model

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint.feature, owned by the component Lint of the bounded context architecture"
  (fn [_]
    (run-project! {"architecture/lint.feature"
                   (k/feature-text {:name "Lint" :tags [] :rule "Every view is described"
                                    :scenarios [{:id "spec-0012" :title "Lint names the view" :tags ["UN-002"] :steps ["Given a view" "Then it is named"]}]})}
                  "(defstep #\".*\" (fn [ctx] ctx))\n")))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx] (let [ctx (k/rdm! ctx "run")] (k/exits! ctx 0) ctx)))

(defn- ms? [x] (and (number? x) (> x 1.6e12)))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element value]
    (let [r (by-spec ctx "spec-0012")
          ok (case element
               "name" (= "Lint names the view" (get r "name"))
               "fullName" (= "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName"))
               "historyId" (= "spec-0012" (get r "historyId"))
               "status and stage" (= ["passed" "finished"] [(get r "status") (get r "stage")])
               "start and stop" (and (ms? (get r "start")) (ms? (get r "stop")) (<= (get r "start") (get r "stop")))
               "steps" (and (= ["Given a view" "Then it is named"] (map #(get % "name") (get r "steps")))
                            (every? #(and (= "passed" (get % "status")) (ms? (get % "start")) (ms? (get % "stop"))) (get r "steps")))
               "the label feature" (= ["Lint"] (labels r "feature"))
               "the label story" (= ["Every view is described"] (labels r "story"))
               "the label epic" (= ["architecture"] (labels r "epic"))
               "the label suite" (= ["architecture"] (labels r "suite"))
               "the label subSuite" (= ["architecture/lint"] (labels r "subSuite"))
               "the labels tag" (= ["spec-0012" "e2e" "UN-002"] (labels r "tag"))
               "the labels spec, context and component" (= [["spec-0012"] ["architecture"] ["Lint"]] [(labels r "spec") (labels r "context") (labels r "component")])
               "the label framework" (= ["gherkin"] (labels r "framework"))
               "links" (= [{"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}] (get r "links")))]
      (k/expect! ok ctx (str element " is " (pr-str (select-keys r ["name" "fullName" "historyId" "status" "labels" "links" "steps"]))))
      ctx)))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [_]
    (run-project! {"architecture/lint.feature"
                   (k/feature-text {:name "Lint" :scenarios [{:id "spec-0013" :title "Keeps <written>" :steps ["Given <written> is kept <kept> times"]
                                                              :examples [["written" "kept"] ["plan" "3"]]}]})}
                  "(defstep #\".*\" (fn [ctx] ctx))\n")))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx]
    (k/expect! (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] (get (by-spec ctx "spec-0013") "parameters")) ctx "not the row's parameters")
    ctx))

(defstep #"its historyId is \"spec-0013\|plan\|3\", the id and the row's values joined by \|, so each row has its own history"
  (fn [ctx] (k/expect! (= "spec-0013|plan|3" (get (by-spec ctx "spec-0013") "historyId")) ctx "not spec-0013|plan|3") ctx))

(defstep #"a scenario whose second step, a Then, fails its assertion with the message \"no such view\""
  (fn [_] (run-project! (one-feature "spec-0001" "Fails" ["Given a thing" "Then it fails with no such view" "And it holds"]) failing-steps)))

(defstep #"its result's status is \"failed\", its statusDetails message holds \"no such view\", and its statusDetails hold the trace"
  (fn [ctx]
    (let [r (by-spec ctx "spec-0001")]
      (k/expect! (= "failed" (get r "status")) ctx "not failed")
      (k/expect! (str/includes? (get-in r ["statusDetails" "message"]) "no such view") ctx "the message")
      (k/expect! (not (str/blank? (get-in r ["statusDetails" "trace"]))) ctx "no trace"))
    ctx))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx] (k/expect! (= "skipped" (get (nth (get (by-spec ctx "spec-0001") "steps") 2) "status")) ctx "not skipped") ctx))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [_] (run-project! (one-feature "spec-0001" "Attaches" ["Given a response"])
                        "(defstep #\"a response\" (fn [ctx] (attach! \"response\" \"GET / answered 200\") ctx))\n")))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (let [a (first (get (first (get (by-spec ctx "spec-0001") "steps")) "attachments"))]
      (k/expect! (= ["response" "text/plain"] [(get a "name") (get a "type")]) ctx "no response attachment")
      (assoc ctx :attachment a))))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (k/expect! (= "GET / answered 200" (k/slurp-in (:dir ctx) (str "dhf/allure-results/" (get (:attachment ctx) "source")))) ctx "the source does not hold the text")
    ctx))

(defstep #"a scenario whose (Then step|Given step|When step) (finds what it asserts does not hold|throws an error|throws an error that is not an assertion)"
  (fn [_ step wrong]
    (let [kw (first (str/split step #" "))
          steps (case kw
                  "Then" ["Given a thing" (str "Then " (if (str/starts-with? wrong "finds") "it does not hold" "it throws"))]
                  "Given" ["Given it throws"]
                  "When" ["Given a thing" "When it throws"])]
      (run-project! (one-feature "spec-0001" "Goes wrong" steps) (str failing-steps "(defstep #\"it throws\" (fn [ctx] (throw (ex-info \"boom\" {}))))\n")))))

(defstep #"its result's status is \"(failed|broken)\""
  (fn [ctx st] (k/expect! (= st (get (by-spec ctx "spec-0001") "status")) ctx (str "not " st)) ctx))

(defstep #"a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset"
  (fn [_] (run-project! (one-feature "spec-0001" "Passes" ["Given a thing"]) failing-steps)))

(defstep #"the results are written into dhf/allure-results"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (by-spec ctx "spec-0001") ctx "no result in dhf/allure-results") ctx))

;; ------------------------------------------------------------------ inherited tags

(defstep #"a feature tagged @UN-001, a rule under it tagged @UN-002, and under the rule the stamped scenario @spec-0003 tagged @e2e"
  (fn [_]
    (run-project! {"architecture/lint.feature"
                   "@UN-001\nFeature: Lint\n\n  @UN-002\n  Rule: Inherits\n\n    @spec-0003 @e2e\n    Scenario: Tagged\n      Given a thing\n"}
                  "(defstep #\".*\" (fn [ctx] ctx))\n")))

(defstep #"its result's tag labels are \"UN-001\", \"UN-002\", \"spec-0003\" and \"e2e\", in that order, each once"
  (fn [ctx] (k/expect! (= ["UN-001" "UN-002" "spec-0003" "e2e"] (labels (by-spec ctx "spec-0003") "tag")) ctx "not the four tags in order") ctx))

(defstep #"verify's tests check holds a result to exactly these tags, under the default mapping's \"tag\" :gherkin-tags"
  (fn [ctx]
    ;; The project's own run, as its cycle's :tests check, verified; then one tag dropped from a result fails it.
    (let [d (:dir ctx)]
      (k/edit-config! d #(assoc % :implementation ["test"] :verify [{:kind :tests :command (conj (k/rdm-argv) "run")}]))
      (k/write! d "dhf/cycles/03/cycle.edn" (pr-str {:cut "spec-3" :scope :project :builder {:name "b" :actor :agent}
                                                      :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}}))
      (k/git! d "init" "-q")
      (k/commit-all! d "cut" "spec-3")
      (let [room (k/path (:top ctx) "room")]
        (fs/copy-tree (k/path d "test") (k/path room "test"))
        (let [ok (k/rdm! ctx ["verify" room "03"])]
          (k/exits! ok 0))
        ;; a step definition that rewrites its own result's tags after the run cannot pass
        (k/write! room "test/steps/steps.clj"
                  (str require-api "(defstep #\".*\" (fn [ctx] ctx))\n"))
        (k/edit-config! d #(assoc % :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run && for f in \"$RESULTS_DIR\"/*-result.json; do sed -i 's/\"UN-002\"/\"UN-009\"/' \"$f\"; done")]}]))
        (k/commit-all! d "cut" "spec-4")
        (k/write! d "dhf/cycles/04/cycle.edn" (pr-str {:cut "spec-4" :scope :project :builder {:name "b" :actor :agent}
                                                        :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}}))
        (let [bad (k/rdm! ctx ["verify" room "04"])]
          (k/exits! bad 1)
          (k/line-has! bad "spec-0003" "tag"))))
    ctx))
