(ns steps.kernel-runner
  "Step definitions for dhf/spec/kernel/runner.feature: `rdm-spec run` in a
  runner project the scenario makes: the room's configuration (without
  :oracle), a model with the bounded context architecture (its component
  Lint, spec architecture/lint) and the kernel component Gadget (spec
  kernel/gadget), the feature files a step gives, and step definition
  files in the configured step directory, written through the runner's
  step API. A step definition records what it was given in log.edn in the
  project, one EDN form a line.

  Results are read from the project's configured results directory.

  Context keys set here: :dir, :log (the log file); the shared Given \"a
  stamped scenario outline with three example rows\" also :id and :rows."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The runner project

(def model
  {:system {:id :demo-system :name "Demo" :description "A demo"}
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo"
                 :contexts [{:id :architecture :name "architecture" :description "The model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "The lint"}]}]}]
   :kernel [{:id :kernel/gadget :name "Gadget" :spec "kernel/gadget" :description "A gadget"}]
   :relationships [] :views []})

(defn- log-form [dir] (str "(fn [x] (spit " (pr-str (str (fs/file dir "log.edn"))) " (str (pr-str x) \"\\n\") :append true))"))

(defn step-file
  "A step definition file: `defs` [[pattern body]], body a function form
  that may call (log x)."
  [dir defs]
  (str "(require '[rdm-spec.kernel.runner :refer [defstep attach!]])\n"
       "(def log " (log-form dir) ")\n"
       (str/join (for [[pat body] defs]
                   (str "(defstep #\"" pat "\"\n  " body ")\n")))))

(defn feature
  "A feature file named `fname` (tags `tags`) holding scenario texts `scs`."
  [fname tags & scs]
  (str (when tags (str tags "\n")) "Feature: " fname "\n  A fixture.\n" (str/join scs)))

(defn scenario
  "A scenario's text: its tag line, title and steps (each a full step line)."
  [tags title & steps]
  (str "\n  " tags "\n  Scenario: " title "\n" (str/join (map #(str "    " % "\n") steps))))

(defn project!
  "ctx with :dir: a runner project holding `features` ({spec-path text}) and
  the step files `steps` ({name [[pattern body]]})."
  [ctx features steps]
  (let [dir (s/project!)]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (doseq [[sp text] features] (s/write! dir (str "dhf/spec/" sp ".feature") text))
    (doseq [[n defs] steps] (s/write! dir (str "test/steps/" n ".clj") (step-file dir defs)))
    (assoc ctx :dir dir :log (str (fs/file dir "log.edn")))))

(defn- logged [ctx]
  (let [f (:log ctx)]
    (if (and f (fs/exists? f))
      (mapv edn/read-string (remove str/blank? (str/split-lines (slurp f))))
      [])))

(defn- results-dir [ctx]
  (str (fs/file (:dir ctx) (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results"))))

(defn results
  "Every Allure result the run wrote, read (string keys)."
  [ctx]
  (let [d (results-dir ctx)]
    (if (fs/directory? d)
      (vec (for [f (sort (map str (fs/glob d "*-result.json")))]
             (assoc (json/parse-string (slurp f)) ::file f)))
      [])))

(defn- the-result [ctx]
  (let [rs (results ctx)]
    (s/check! (= 1 (count rs)) (str "the run wrote " (count rs) " results, not one\n" (s/show (kc/the-run ctx))))
    (first rs)))

(defn labels
  "The values of the label `n` of the result `r`."
  [r n]
  (vec (for [l (get r "labels") :when (= n (get l "name"))] (get l "value"))))

(defn- label [r n]
  (let [vs (labels r n)]
    (s/check! (= 1 (count vs)) (str "the result has " (count vs) " labels " n ": " (pr-str vs)))
    (first vs)))

(defn- run! [ctx] (kc/run-line ctx "run"))

;; ---------------------------------------------------------------------------
;; Step definitions are written against one small API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (assoc ctx ::steps {"holds" [["the spec holds (\\d+) features" "(fn [ctx n] (log [ctx n]) ctx)"]]})))

(defstep #"\"rdm-spec run\" runs a scenario with the step \"Given ([^\"]+)\""
  (fn [ctx text]
    (-> (project! ctx {"architecture/lint" (feature "Lint" "@UN-001"
                                                     (scenario "@spec-0012 @e2e" "One step" (str "Given " text)))}
                  (::steps ctx))
        run!)))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= [[{} "20"]] (logged ctx)) (str "the function was called with " (pr-str (logged ctx))))
    nil))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (assoc ctx ::steps {"keys" [["the first adds a key" "(fn [ctx] (log [:first ctx]) (assoc ctx :first 1))"]
                                ["the second adds a key" "(fn [ctx] (log [:second ctx]) (assoc ctx :second 2))"]]})))

(defstep #"\"rdm-spec run\" runs a scenario with both steps"
  (fn [ctx]
    (-> (project! ctx {"architecture/lint"
                       (feature "Lint" "@UN-001"
                                (scenario "@spec-0012 @e2e" "Both steps" "Given the first adds a key" "Then the second adds a key")
                                (scenario "@spec-0014 @e2e" "The next scenario" "Given the second adds a key"))}
                  (::steps ctx))
        run!)))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= [[:first {}] [:second {:first 1}]] (take 2 (logged ctx))) (str "the log is " (pr-str (logged ctx))))
    nil))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (s/check! (= [:second {}] (nth (logged ctx) 2 nil)) (str "the log is " (pr-str (logged ctx))))
    nil))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [ctx]
    (assoc ctx ::steps {"one" [["a spec" "(fn [ctx] ctx)"]]
                        "two" [["a (\\w+)" "(fn [ctx _] ctx)"]]})))

(defstep #"\"rdm-spec run\" runs a scenario with that step"
  (fn [ctx]
    (-> (project! ctx {"architecture/lint" (feature "Lint" "@UN-001" (scenario "@spec-0012 @e2e" "Ambiguous" "Given a spec"))}
                  (::steps ctx))
        run!)))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (the-result ctx)
          msg (get-in r ["statusDetails" "message"])]
      (s/check! (= "broken" (get r "status")) (str "the result is " (get r "status")))
      (doseq [part ["a spec" "a (\\w+)" "one.clj" "two.clj"]]
        (s/check! (str/includes? (str msg) part) (str "the result does not name " part ": " msg)))
      nil)))

;; ---------------------------------------------------------------------------
;; Every scenario run leaves one result, labelled with what it verifies

(def ^:private passing {"pass" [["a step that passes" "(fn [ctx] ctx)"]
                                ["another step that passes" "(fn [ctx] ctx)"]]})

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001"
                                                (scenario "@spec-0012 @e2e" "Lint names the view"
                                                          "Given a step that passes" "Then another step that passes"))
                   "kernel/gadget" (feature "Gadget" "@UN-001"
                                            (scenario "@spec-0020 @contract" "The gadget works" "Given a step that passes"))}
              passing)))

(defn- result-of [ctx id]
  (let [rs (filter #(some #{id} (labels % "spec")) (results ctx))]
    (s/check! (= 1 (count rs)) (str (count rs) " results are labelled spec=" id "\n" (s/show (kc/the-run ctx))))
    (first rs)))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [r (result-of ctx "spec-0012")]
      (s/check! (= ["Lint" "architecture"] [(label r "component") (label r "context")])
                (str "the labels are " (pr-str (get r "labels"))))
      nil)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx]
    (let [r (result-of ctx "spec-0020")]
      (s/check! (= ["kernel" "Gadget"] [(label r "context") (label r "component")]) (str "the labels are " (pr-str (get r "labels"))))
      nil)))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [r (result-of ctx "spec-0012")
          steps (get r "steps")]
      (s/check! (= [["Given a step that passes" "passed"] ["Then another step that passes" "passed"]]
                   (map (juxt #(get % "name") #(get % "status")) steps))
                (str "the steps are " (pr-str steps)))
      nil)))

(defn- outline [id title & rows]
  (str "\n  @" id " @e2e\n  Scenario Outline: " title "\n    Given a row with <written> and <kept>\n\n"
       "    Examples:\n      | written | kept |\n"
       (str/join (for [[a b] rows] (str "      | " a " | " b " |\n")))))

(def ^:private row-steps {"rows" [["a row with (\\S+) and (\\S+)" "(fn [ctx a b] ctx)"]]})

;; Shared by runner.feature and status.feature, so the kernel's: a runner
;; project whose one outline, spec-0013, has three rows, with step
;; definitions for its steps. Sets :dir, :log, :id "spec-0013" and :rows
;; (each row's values, [[heading value] ..]).
(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (-> (project! ctx {"architecture/lint" (feature "Lint" "@UN-001" (outline "spec-0013" "Rows" ["plan" "3"] ["draft" "4"] ["note" "5"]))}
                  row-steps)
        (assoc :id "spec-0013"
               :rows [[["written" "plan"] ["kept" "3"]] [["written" "draft"] ["kept" "4"]] [["written" "note"] ["kept" "5"]]]))))

(defstep #"it writes three Allure results, each labelled with the outline's id and naming its row"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [rs (results ctx)]
      (s/check! (= 3 (count rs)) (str "the run wrote " (count rs) " results"))
      (s/check! (every? #(= ["spec-0013"] (labels % "spec")) rs) "a result is not labelled spec=spec-0013")
      (s/check! (= #{"plan" "draft" "note"}
                   (set (for [r rs p (get r "parameters") :when (= "written" (get p "name"))] (get p "value"))))
                (str "the rows named are " (pr-str (map #(get % "parameters") rs))))
      nil)))

(defstep #"a scenario whose second step's definition throws"
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001"
                                                (scenario "@spec-0012 @e2e" "Throws" "Given a step that passes"
                                                          "When a step that throws" "Then a step that passes"))}
              {"throws" [["a step that passes" "(fn [ctx] ctx)"]
                         ["a step that throws" "(fn [ctx] (throw (ex-info \"no such view\" {})))"]]})))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [r (the-result ctx)
          msg (str (get-in r ["statusDetails" "message"]))]
      (s/check! (= "failed" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (and (str/includes? msg "a step that throws") (str/includes? msg "no such view"))
                (str "the result says " msg))
      nil)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [steps (get (the-result ctx) "steps")]
      (s/check! (= ["passed" "failed" "skipped"] (map #(get % "status") steps)) (str "the steps are " (pr-str steps)))
      nil)))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001"
                                                (scenario "@spec-0012 @e2e" "Unmatched" "Given a step that passes"
                                                          "When nothing defines this step"))}
              passing)))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= "broken" (get (the-result ctx) "status")) (str "the result is " (get (the-result ctx) "status")))
    nil))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (let [msg (str (get-in (the-result ctx) ["statusDetails" "message"]))]
      (s/check! (str/includes? msg "nothing defines this step") (str "the result says " msg))
      nil)))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001"
                                                (scenario "@spec-0012 @e2e @wip" "Planned" "Given a logged step"))}
              {"wip" [["a logged step" "(fn [ctx] (log :ran) ctx)"]]})))

(defstep #"the scenario's steps are not run"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (empty? (logged ctx)) (str "a step ran: " (pr-str (logged ctx))))
    nil))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (the-result ctx)]
      (s/check! (= "skipped" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (= ["true"] (labels r "planned")) (str "the labels are " (pr-str (get r "labels"))))
      nil)))

;; ---------------------------------------------------------------------------
;; The runner discovers, and refuses to find nothing

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (project! ctx (into {} (for [i (range 20)]
                             [(format "architecture/part%02d" i)
                              (feature (str "Part " i) "@UN-001"
                                       (scenario (format "@spec-%04d @e2e" (+ 100 i)) (str "Part " i) "Given a step that passes"))]))
              passing)))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [ids (set (mapcat #(labels % "spec") (results ctx)))]
      (s/check! (= (set (for [i (range 20)] (format "spec-%04d" (+ 100 i)))) ids) (str "the run wrote " (sort ids)))
      nil)))

(defstep #"a spec directory holding no feature file"
  (fn [ctx] (project! ctx {} passing)))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (kc/refused-naming! ctx "no scenario was found")
    (s/check! (empty? (results ctx)) "a result was written")
    nil))

;; ---------------------------------------------------------------------------
;; Every result is written in Allure's own model, from its Gherkin scenario

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint\.feature, owned by the component Lint of the bounded context architecture"
  (fn [ctx]
    (project! ctx {"architecture/lint"
                   (str "Feature: Lint\n  A fixture.\n\n  Rule: Every view is described\n"
                        "\n    @spec-0012 @e2e @UN-002\n    Scenario: Lint names the view\n"
                        "      Given a step that passes\n      Then another step that passes\n")}
              passing)))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx]
    (let [before (System/currentTimeMillis)
          ctx (run! ctx)]
      (kc/exits! ctx 0)
      (s/check! (= "passed" (get (the-result ctx) "status")) "the scenario did not pass")
      (assoc ctx ::window [before (System/currentTimeMillis)]))))

(defn- millis? [ctx v]
  (let [[a b] (::window ctx)]
    (and (integer? v) (<= a v b))))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element value]
    (let [r (the-result ctx)
          is! (fn [ok] (s/check! ok (str "the result does not hold " element " as " value ":\n" (json/generate-string r {:pretty true}))))]
      (case element
        "name" (is! (= "Lint names the view" (get r "name")))
        "fullName" (is! (= "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName")))
        "historyId" (is! (= "spec-0012" (get r "historyId")))
        "status and stage" (is! (= ["passed" "finished"] [(get r "status") (get r "stage")]))
        "start and stop" (is! (and (millis? ctx (get r "start")) (millis? ctx (get r "stop")) (<= (get r "start") (get r "stop"))))
        "steps" (is! (and (= ["Given a step that passes" "Then another step that passes"] (map #(get % "name") (get r "steps")))
                          (every? #(and (= "passed" (get % "status")) (millis? ctx (get % "start")) (millis? ctx (get % "stop")))
                                  (get r "steps"))))
        "the label feature" (is! (= ["Lint"] (labels r "feature")))
        "the label story" (is! (= ["Every view is described"] (labels r "story")))
        "the label suite" (is! (= ["architecture"] (labels r "suite")))
        "the label subSuite" (is! (= ["Lint"] (labels r "subSuite")))
        "the labels tag" (is! (= #{"@spec-0012" "@e2e" "@UN-002"} (set (labels r "tag"))))
        "the labels spec, context and component" (is! (= [["spec-0012"] ["architecture"] ["Lint"]]
                                                          [(labels r "spec") (labels r "context") (labels r "component")]))
        "the label framework" (is! (= ["gherkin"] (labels r "framework")))
        "links" (is! (= [{"type" "issue" "name" "UN-002"}] (get r "links")))
        (s/fail! (str "no such element: " element))))
    nil))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001" (outline "spec-0013" "Rows" ["plan" "3"] ["draft" "4"]))}
              row-steps)))

(defn- row-result [ctx v]
  (let [rs (filter (fn [r] (some #(= ["written" v] [(get % "name") (get % "value")]) (get r "parameters"))) (results ctx))]
    (s/check! (= 1 (count rs)) (str (count rs) " results name the row " v))
    (first rs)))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [ps (get (row-result ctx "plan") "parameters")]
      (s/check! (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] ps) (str "the parameters are " (pr-str ps)))
      nil)))

(defstep #"its historyId is \"spec-0013\" followed by the row's values, so each row has its own history"
  (fn [ctx]
    (let [a (get (row-result ctx "plan") "historyId")
          b (get (row-result ctx "draft") "historyId")]
      (s/check! (and (str/starts-with? a "spec-0013") (str/includes? a "plan") (str/includes? a "3"))
                (str "the historyId is " (pr-str a)))
      (s/check! (not= a b) (str "two rows share the history " a))
      nil)))

(defstep #"a scenario whose second step throws the error \"no such view\""
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001"
                                                (scenario "@spec-0012 @e2e" "Throws" "Given a step that passes"
                                                          "When a step that throws" "Then a step that passes"))}
              {"throws" [["a step that passes" "(fn [ctx] ctx)"]
                         ["a step that throws" "(fn [ctx] (throw (ex-info \"no such view\" {})))"]]})))

(defstep #"its result's status is \"failed\" and its statusDetails hold the message \"no such view\" and the trace"
  (fn [ctx]
    (let [r (the-result ctx)
          d (get r "statusDetails")]
      (s/check! (= "failed" (get r "status")) (str "the status is " (get r "status")))
      (s/check! (str/includes? (str (get d "message")) "no such view") (str "the message is " (get d "message")))
      (s/check! (not (str/blank? (get d "trace"))) "statusDetails hold no trace")
      nil)))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx]
    (s/check! (= "skipped" (get (nth (get (the-result ctx) "steps") 2 nil) "status")) "the third step is not skipped")
    nil))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [ctx]
    (project! ctx {"architecture/lint" (feature "Lint" "@UN-001" (scenario "@spec-0012 @e2e" "Evidence" "Given a step with evidence"))}
              {"evidence" [["a step with evidence" "(fn [ctx] (attach! \"response\" \"GET / answered 200\") ctx)"]]})))

(defn- the-attachment [ctx]
  (let [a (first (get (first (get (the-result ctx) "steps")) "attachments"))]
    (s/check! a (str "the step holds no attachment\n" (s/show (kc/the-run ctx))))
    a))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [a (the-attachment ctx)]
      (s/check! (= ["response" "text/plain"] [(get a "name") (get a "type")]) (str "the attachment is " (pr-str a)))
      nil)))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (let [a (the-attachment ctx)
          f (fs/file (results-dir ctx) (str (get a "source")))]
      (s/check! (and (not (str/includes? (str (get a "source")) "/")) (fs/regular-file? f)) (str "no attachment file " f))
      (s/check! (= "GET / answered 200" (slurp (str f))) (str "the attachment holds " (pr-str (slurp (str f)))))
      nil)))
