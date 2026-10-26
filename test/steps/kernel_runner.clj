(ns steps.kernel-runner
  "Step definitions for dhf/spec/kernel/runner.feature, and the kernel's
  shared Given of a stamped outline (also read by
  dhf/spec/verification/status.feature).

  Each scenario makes a fixture project of its own (`fixture!`): the room's
  configuration without :oracle (so :steps-dir test/steps and :results-dir
  dhf/allure-results), a model with the bounded contexts architecture (Lint)
  and demo (Alpha) and the kernel component Gherkin runner, needs, a ledger,
  the feature files the step gives, and one step definition file,
  test/steps/fixture_steps.clj, written against the runner's step API. Each
  fixture step appends what it was called with to a log file, one EDN form a
  line. `rdm-spec run` is the kernel's shared When; it runs with RESULTS_DIR
  unset, so results go to the fixture's configured results directory.

  The shared step, with the context keys it sets:

    a stamped scenario outline with three example rows
        :dir, :id \"spec-0013\", :rows ([[heading value] ..] of each row), :log

  Other keys set here: :log (the fixture's log file), :file (the feature
  file a Then is about)."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The fixture project

(def ^:private model
  {:system {:id :demo-system :name "Demo" :description "A demo system"}
   :people [] :external []
   :containers [{:id :demo-app :name "demo" :kind :runs :technology "Any" :description "The demo program"
                 :contexts [{:id :architecture :name "architecture" :description "The C4 model"
                             :components [{:id :architecture/lint :name "Lint" :spec "architecture/lint"
                                           :description "The lint"}]}
                            {:id :demo :name "demo" :description "The demo context"
                             :components [{:id :demo/alpha :name "Alpha" :spec "demo/alpha" :description "Alpha"}]}]}]
   :kernel [{:id :kernel/runner :name "Gherkin runner" :spec "kernel/runner" :description "The runner"}]
   :relationships [] :views []})

(def ^:private needs
  {"UN-001" {:text "A user needs the first thing." :sources ["demo:README.md@abc1234"]}
   "UN-002" {:text "A user needs a lint." :sources ["demo:README.md@abc1234"]}})

(defn- log-fn
  "The step definition source of a helper that logs `form` to the log."
  [log]
  (str "(defn- log! [x] (spit " (pr-str log) " (str (pr-str x) \"\\n\") :append true))\n"))

(defn- steps-source
  "A step definition file: the ns form, the log helper, then `defs` (source)."
  [log defs]
  (str "(ns steps.fixture-steps\n  (:require [rdm-spec.kernel.runner :refer [defstep attach!]]))\n\n"
       (log-fn log) "\n" defs "\n"))

(def ^:private plain-steps
  "Definitions for the steps every plain fixture scenario uses."
  (str "(defstep #\"a model\" (fn [ctx] (log! [:given (vec (keys ctx))]) (assoc ctx :model true)))\n"
       "(defstep #\"it is linted\" (fn [ctx] (log! [:when]) ctx))\n"
       "(defstep #\"it names the view\" (fn [ctx] (log! [:then]) ctx))\n"
       "(defstep #\"the value (\\S+)\" (fn [ctx v] (log! [:value v]) ctx))\n"
       "(defstep #\"it keeps (\\S+)\" (fn [ctx v] (log! [:kept v]) ctx))\n"))

(defn- scenario-src
  "A scenario: tags, title and steps (each a string with its keyword)."
  [{:keys [tags title steps outline examples]}]
  (str "\n  " (str/join " " tags) "\n"
       "  " (if outline "Scenario Outline: " "Scenario: ") title "\n"
       (str/join (map #(str "    " % "\n") steps))
       (when examples
         (str "\n    Examples:\n"
              (str/join (map #(str "      | " (str/join " | " %) " |\n") examples))))))

(defn feature-src
  "A feature file: its name, optional rule name, and scenarios."
  [{:keys [fname rule scenarios]}]
  (str "# A fixture feature file of the kernel runner's steps.\n\n"
       "Feature: " (or fname "Lint") "\n  A fixture.\n"
       (when rule (str "\n  Rule: " rule "\n"))
       (str/join (map scenario-src scenarios))))

(defn fixture!
  "ctx with :dir and :log: a new project holding `features` ({spec-rel text},
  spec-rel as architecture/lint) and the step definition source `defs`."
  [ctx features defs]
  (let [dir (s/project!)
        log (str (fs/file dir "steps.log"))]
    (s/write! dir "dhf/spec/model.edn" (pr-str model))
    (s/write! dir "dhf/spec/needs.edn" (pr-str needs))
    (s/write! dir "dhf/spec/ids.edn" "{:issued 9999}\n")
    (s/write! dir "dhf/spec/events.edn" "{}\n")
    (doseq [[rel text] features]
      (s/write! dir (str "dhf/spec/" rel ".feature") text))
    (s/write! dir "test/steps/fixture_steps.clj" (steps-source log defs))
    (spit log "")
    (assoc ctx :dir dir :log log)))

(defn- log-of [ctx]
  (vec (for [l (str/split-lines (slurp (:log ctx))) :when (not (str/blank? l))] (edn/read-string l))))

(defn- results
  "The results in the fixture's configured results directory, read, with
  :file each one's file."
  [ctx]
  (let [d (fs/file (:dir ctx) (or (:results-dir (kc/config-of (:dir ctx))) "dhf/allure-results"))]
    (if (fs/directory? d)
      (vec (for [f (sort (map str (fs/glob d "*-result.json")))]
             (assoc (json/parse-string (slurp f)) :file f)))
      [])))

(defn- label [r n] (vec (keep #(when (= n (get % "name")) (get % "value")) (get r "labels"))))

(defn- ran!
  "Checks the last run ran (exit 0 or 1); returns the results."
  [ctx]
  (let [r (kc/the-run ctx)]
    (s/check! (#{0 1} (:exit r)) (str "rdm-spec run did not run\n" (s/show r)))
    (results ctx)))

(defn- one-result!
  "The one result of the last run, labelled spec=`id` when given."
  ([ctx] (one-result! ctx nil))
  ([ctx id]
   (let [rs (cond->> (ran! ctx) id (filter #(= [id] (label % "spec"))))]
     (s/check! (= 1 (count rs)) (str "it wrote " (count rs) " results" (when id (str " labelled spec=" id))
                                     ", not one\n" (s/show (kc/the-run ctx))))
     (first rs))))

;; ---------------------------------------------------------------------------
;; Step definitions are written against one small API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (assoc ctx :defs
           (str "(defstep #\"the spec holds (\\d+) features\"\n"
                "  (fn [ctx n] (log! [:called ctx n]) ctx))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with the step \"(Given [^\"]+)\""
  (fn [ctx step]
    (let [ctx (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                                       :scenarios [{:tags ["@spec-0001" "@e2e"] :title "Holds"
                                                                    :steps [step]}]})}
                        (:defs ctx))]
      (kc/run-line ctx "run"))))

(defstep #"the function is called with the scenario's context and \"20\""
  (fn [ctx]
    (one-result! ctx)
    (s/check! (= [[:called {} "20"]] (log-of ctx)) (str "the function was called as " (pr-str (log-of ctx))))
    nil))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (assoc ctx :defs
           (str "(defstep #\"the first adds a key\" (fn [ctx] (log! [:first ctx]) (assoc ctx :first 1)))\n"
                "(defstep #\"the second adds a key\" (fn [ctx] (log! [:second ctx]) (assoc ctx :second 2)))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with both steps"
  (fn [ctx]
    (let [sc (fn [id t] {:tags [(str "@" id) "@e2e"] :title t
                         :steps ["Given the first adds a key" "When the second adds a key"]})
          ctx (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                                       :scenarios [(sc "spec-0001" "One") (sc "spec-0002" "Two")]})}
                        (:defs ctx))]
      (kc/run-line ctx "run"))))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (ran! ctx)
    (let [lg (log-of ctx)]
      (s/check! (= [:second {:first 1}] (second lg)) (str "the calls were " (pr-str lg)))
      nil)))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (let [lg (log-of ctx)]
      (s/check! (= [:first {}] (nth lg 2 nil)) (str "the calls were " (pr-str lg)))
      nil)))

(defstep #"two step definitions whose patterns both match \"Given a spec\""
  (fn [ctx]
    (assoc ctx :defs
           (str "(defstep #\"a spec\" (fn [ctx] ctx))\n"
                "(defstep #\"a (\\w+)\" (fn [ctx _] ctx))\n"))))

(defstep #"\"rdm-spec run\" runs a scenario with that step"
  (fn [ctx]
    (let [ctx (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                                       :scenarios [{:tags ["@spec-0001" "@e2e"] :title "Ambiguous"
                                                                    :steps ["Given a spec"]}]})}
                        (:defs ctx))]
      (kc/run-line ctx "run"))))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (one-result! ctx)
          msg (str (get-in r ["statusDetails" "message"]))]
      (s/check! (= "broken" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (and (str/includes? msg "a spec") (str/includes? msg "a (\\w+)"))
                (str "the result does not name both definitions: " msg))
      nil)))

;; ---------------------------------------------------------------------------
;; Every scenario run leaves one result, labelled with what it verifies

(defn- lint-scenario [extra]
  (merge {:tags ["@spec-0012" "@e2e" "@UN-002"] :title "Lint names the view"
          :steps ["Given a model" "When it is linted" "Then it names the view"]}
         extra))

(defstep #"the scenario @spec-0012 in the component Lint of the bounded context architecture"
  (fn [ctx]
    (fixture! ctx {"architecture/lint" (feature-src {:fname "Lint" :scenarios [(lint-scenario {})]})
                   "kernel/runner" (feature-src {:fname "Gherkin runner"
                                                 :scenarios [{:tags ["@spec-0020" "@contract"] :title "Runs"
                                                              :steps ["Given a model"]}]})}
              plain-steps)))

(defstep #"it writes one Allure result labelled spec=spec-0012, component=Lint and context=architecture"
  (fn [ctx]
    (let [r (one-result! ctx "spec-0012")]
      (s/check! (= [["Lint"] ["architecture"]] [(label r "component") (label r "context")])
                (str "the result is labelled " (pr-str (get r "labels"))))
      nil)))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx]
    (let [r (one-result! ctx "spec-0020")]
      (s/check! (= ["kernel"] (label r "context")) (str "the result is labelled " (pr-str (get r "labels"))))
      nil)))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [r (one-result! ctx "spec-0012")
          steps (get r "steps")]
      (s/check! (= ["Given a model" "When it is linted" "Then it names the view"] (mapv #(get % "name") steps))
                (str "the result's steps are " (pr-str steps)))
      (s/check! (every? #(= "passed" (get % "status")) steps) (str "the steps' statuses are " (pr-str steps)))
      nil)))

(def ^:private outline-rows [["plan"] ["build"] ["ship"]])

(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (let [ctx (fixture! ctx {"demo/alpha"
                             (feature-src {:fname "Alpha"
                                           :scenarios [{:tags ["@spec-0013" "@e2e" "@UN-001"] :outline true
                                                        :title "Rows <written>"
                                                        :steps ["Given the value <written>"]
                                                        :examples (into [["written"]] outline-rows)}]})}
                        plain-steps)]
      (assoc ctx :id "spec-0013" :rows (mapv (fn [[v]] [["written" v]]) outline-rows)))))

(defstep #"it writes three Allure results, each labelled with the outline's id and naming its row"
  (fn [ctx]
    (let [rs (ran! ctx)]
      (s/check! (= 3 (count rs)) (str "it wrote " (count rs) " results"))
      (s/check! (every? #(= ["spec-0013"] (label % "spec")) rs) "a result is not labelled spec=spec-0013")
      (s/check! (= (set (map (fn [[[_ v]]] [{"name" "written" "value" v}]) (:rows ctx)))
                   (set (map #(get % "parameters") rs)))
                (str "the results name the rows " (pr-str (map #(get % "parameters") rs))))
      nil)))

(def ^:private failing-steps
  (str plain-steps
       "(defstep #\"it does not hold\" (fn [ctx] (assert false \"no such view\") ctx))\n"
       "(defstep #\"it throws\" (fn [ctx] (throw (RuntimeException. \"it broke\"))))\n"
       "(defstep #\"after it\" (fn [ctx] (log! [:after]) ctx))\n"))

(defn- failing-fixture! [ctx steps]
  (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                           :scenarios [{:tags ["@spec-0001" "@e2e"] :title "Fails"
                                                        :steps steps}]})}
            failing-steps))

(defstep #"a scenario whose second step's definition throws"
  (fn [ctx] (failing-fixture! ctx ["Given a model" "Then it does not hold" "And after it"])))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (let [r (one-result! ctx)
          msg (str (get-in r ["statusDetails" "message"]))]
      (s/check! (= "failed" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (and (str/includes? msg "Then it does not hold") (str/includes? msg "no such view"))
                (str "the message does not name the step and the error: " msg))
      (s/check! (= "failed" (get-in r ["steps" 1 "status"])) "the second step is not failed")
      nil)))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [r (one-result! ctx)]
      (s/check! (= "skipped" (get-in r ["steps" 2 "status"])) (str "the third step is " (get-in r ["steps" 2 "status"])))
      (s/check! (not-any? #{[:after]} (log-of ctx)) "the step after it ran")
      nil)))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx] (failing-fixture! ctx ["Given a model" "When nothing defines this step"])))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx]
    (let [r (one-result! ctx)]
      (s/check! (= "broken" (get r "status")) (str "the result is " (get r "status")))
      nil)))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (let [msg (str (get-in (one-result! ctx) ["statusDetails" "message"]))]
      (s/check! (str/includes? msg "When nothing defines this step") (str "the message is " msg))
      nil)))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                             :scenarios [{:tags ["@spec-0001" "@e2e" "@wip"] :title "Planned"
                                                          :steps ["Given a model" "When it is linted"]}]})}
              plain-steps)))

(defstep #"the scenario's steps are not run"
  (fn [ctx]
    (ran! ctx)
    (s/check! (empty? (log-of ctx)) (str "steps ran: " (pr-str (log-of ctx))))
    nil))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (one-result! ctx)]
      (s/check! (= "skipped" (get r "status")) (str "the result is " (get r "status")))
      (s/check! (= ["true"] (label r "planned")) (str "the result is labelled " (pr-str (get r "labels"))))
      nil)))

;; ---------------------------------------------------------------------------
;; The runner discovers, and refuses to find nothing

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (let [ctx (fixture! ctx {} plain-steps)]
      (doseq [i (range 1 21)
              :let [id (format "spec-%04d" i)]]
        (s/write! (:dir ctx) (format "dhf/spec/demo/f%02d.feature" i)
                  (feature-src {:fname (str "F" i) :scenarios [{:tags [(str "@" id) "@e2e"] :title (str "S" i)
                                                                :steps ["Given a model"]}]})))
      ctx)))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (let [rs (ran! ctx)]
      (s/check! (= (set (map #(format "spec-%04d" %) (range 1 21))) (set (mapcat #(label % "spec") rs)))
                (str "it ran " (sort (mapcat #(label % "spec") rs))))
      (s/check! (= 20 (count rs)) (str "it wrote " (count rs) " results"))
      nil)))

(defstep #"a spec directory holding no feature file"
  (fn [ctx] (fixture! ctx {} plain-steps)))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (let [r (kc/exits! ctx 2)]
      (s/check! (some #(str/includes? % "no scenario was found") (kc/cannot-run-lines r))
                (str "it does not say no scenario was found\n" (s/show r)))
      (s/check! (empty? (results ctx)) "it wrote results")
      nil)))

;; ---------------------------------------------------------------------------
;; Every result is written in Allure's own model

(defstep #"the stamped scenario @spec-0012 \"Lint names the view\", tagged @e2e and @UN-002, under the rule \"Every view is described\" of the feature \"Lint\" in dhf/spec/architecture/lint\.feature, owned by the component Lint of the bounded context architecture"
  (fn [ctx]
    (fixture! ctx {"architecture/lint" (feature-src {:fname "Lint" :rule "Every view is described"
                                                     :scenarios [(lint-scenario {})]})}
              plain-steps)))

(defstep #"\"rdm-spec run\" is run and the scenario passes"
  (fn [ctx]
    (let [ctx (assoc ctx :started (System/currentTimeMillis))
          ctx (kc/run-line ctx "run")]
      (kc/exits! ctx 0)
      (assoc ctx :stopped (System/currentTimeMillis)))))

(defn- holds! [ok r what]
  (s/check! ok (str "the result does not hold " what ":\n" (json/generate-string (dissoc r :file) {:pretty true}))))

(defstep #"its Allure result holds (.+?) as (.+)"
  (fn [ctx element _]
    (let [r (one-result! ctx "spec-0012")
          ms? #(and (integer? %) (<= (:started ctx) % (:stopped ctx)))]
      (case element
        "name" (holds! (= "Lint names the view" (get r "name")) r element)
        "fullName" (holds! (= "dhf/spec/architecture/lint.feature:Lint names the view" (get r "fullName")) r element)
        "historyId" (holds! (= "spec-0012" (get r "historyId")) r element)
        "status and stage" (holds! (= ["passed" "finished"] [(get r "status") (get r "stage")]) r element)
        "start and stop" (holds! (and (ms? (get r "start")) (ms? (get r "stop")) (<= (get r "start") (get r "stop")))
                                 r element)
        "steps" (let [st (get r "steps")]
                  (holds! (and (= ["Given a model" "When it is linted" "Then it names the view"] (mapv #(get % "name") st))
                               (every? #(and (= "passed" (get % "status")) (ms? (get % "start")) (ms? (get % "stop"))) st))
                          r element))
        "the label feature" (holds! (= ["Lint"] (label r "feature")) r element)
        "the label story" (holds! (= ["Every view is described"] (label r "story")) r element)
        "the label epic" (holds! (= ["architecture"] (label r "epic")) r element)
        "the label suite" (holds! (= ["architecture"] (label r "suite")) r element)
        "the label subSuite" (holds! (= ["architecture/lint"] (label r "subSuite")) r element)
        "the labels tag" (holds! (= ["spec-0012" "e2e" "UN-002"] (label r "tag")) r element)
        "the labels spec, context and component" (holds! (= [["spec-0012"] ["architecture"] ["Lint"]]
                                                            [(label r "spec") (label r "context") (label r "component")])
                                                         r element)
        "the label framework" (holds! (= ["gherkin"] (label r "framework")) r element)
        "links" (holds! (= [{"type" "issue" "name" "UN-002" "url" "dhf/spec/needs.edn#UN-002"}] (get r "links")) r element)
        (s/fail! (str "no check for the element " element)))
      nil)))

(defstep #"a stamped scenario outline @spec-0013 whose example row has the values plan and 3"
  (fn [ctx]
    (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                             :scenarios [{:tags ["@spec-0013" "@contract"] :outline true
                                                          :title "Keeps <written>"
                                                          :steps ["Given the value <written>" "Then it keeps <kept>"]
                                                          :examples [["written" "kept"] ["plan" "3"]]}]})}
              plain-steps)))

(defstep #"that row's result holds the parameters \[\{\"name\" \"written\" \"value\" \"plan\"\} \{\"name\" \"kept\" \"value\" \"3\"\}\], named by the table's headings"
  (fn [ctx]
    (let [r (one-result! ctx "spec-0013")]
      (holds! (= [{"name" "written" "value" "plan"} {"name" "kept" "value" "3"}] (get r "parameters")) r "the parameters")
      nil)))

(defstep #"its historyId is \"spec-0013\" followed by the row's values, so each row has its own history"
  (fn [ctx]
    (let [r (one-result! ctx "spec-0013")
          h (str (get r "historyId"))]
      (holds! (and (str/starts-with? h "spec-0013") (str/includes? h "plan") (str/includes? h "3")
                   (< (count "spec-0013") (count h)))
              r "a historyId of the id and the row's values")
      nil)))

(defstep #"a scenario whose second step throws the error \"no such view\""
  (fn [ctx] (failing-fixture! ctx ["Given a model" "Then it does not hold" "And after it"])))

(defstep #"its result's status is \"failed\" and its statusDetails hold the message \"no such view\" and the trace"
  (fn [ctx]
    (let [r (one-result! ctx)]
      (holds! (= "failed" (get r "status")) r "the status failed")
      (holds! (str/includes? (str (get-in r ["statusDetails" "message"])) "no such view") r "the message")
      (holds! (str/includes? (str (get-in r ["statusDetails" "trace"])) "at ") r "the trace")
      nil)))

(defstep #"its third step's status is \"skipped\""
  (fn [ctx]
    (let [r (one-result! ctx)]
      (holds! (= "skipped" (get-in r ["steps" 2 "status"])) r "a skipped third step")
      nil)))

(defstep #"a step definition that attaches the text \"GET / answered 200\" as evidence named \"response\""
  (fn [ctx]
    (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                             :scenarios [{:tags ["@spec-0001" "@e2e"] :title "Answers"
                                                          :steps ["When it is requested"]}]})}
              (str "(defstep #\"it is requested\" (fn [ctx] (attach! \"response\" \"GET / answered 200\") ctx))\n"))))

(defstep #"the result's step holds an attachment named \"response\" of the type \"text/plain\""
  (fn [ctx]
    (let [r (one-result! ctx)
          a (first (get-in r ["steps" 0 "attachments"]))]
      (holds! (= ["response" "text/plain"] [(get a "name") (get a "type")]) r "the attachment")
      (assoc ctx :attachment a :result-file (:file r)))))

(defstep #"its source is a file beside the result in the results directory, holding that text"
  (fn [ctx]
    (let [src (get (:attachment ctx) "source")
          f (fs/file (fs/parent (:result-file ctx)) (str src))]
      (s/check! (and (string? src) (not (str/includes? src "/")) (fs/regular-file? f))
                (str "the source " (pr-str src) " is not a file beside the result"))
      (s/check! (= "GET / answered 200" (slurp (str f))) "the attachment does not hold the text")
      nil)))

(def ^:private goes-wrong
  {["Then step" "finds what it asserts does not hold"] "Then it does not hold"
   ["Given step" "throws an error"] "Given it throws"
   ["When step" "throws an error"] "When it throws"
   ["Then step" "throws an error that is not an assertion"] "Then it throws"})

(defstep #"a scenario whose (Then step|Given step|When step) (.+)"
  (fn [ctx step what]
    (let [line (or (get goes-wrong [step what]) (s/fail! (str "no fixture for a " step " that " what)))]
      (failing-fixture! ctx (if (str/starts-with? line "Given") [line] ["Given a model" line])))))

(defstep #"its result's status is \"(\w+)\""
  (fn [ctx st]
    (let [r (one-result! ctx)]
      (holds! (= st (get r "status")) r (str "the status " st))
      nil)))

(defstep #"a configuration whose :results-dir is dhf/allure-results, and RESULTS_DIR unset"
  (fn [ctx]
    (let [ctx (fixture! ctx {"demo/alpha" (feature-src {:fname "Alpha"
                                                       :scenarios [{:tags ["@spec-0001" "@e2e"] :title "One"
                                                                    :steps ["Given a model"]}]})}
                        plain-steps)]
      (kc/config-of (:dir ctx))
      (s/write! (:dir ctx) "dhf/harness/config.edn"
                (pr-str (assoc (kc/config-of (:dir ctx)) :results-dir "dhf/allure-results")))
      (update ctx :env assoc "RESULTS_DIR" nil))))

(defstep #"the results are written into dhf/allure-results"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [fs* (fs/glob (fs/file (:dir ctx) "dhf/allure-results") "*-result.json")]
      (s/check! (= 1 (count fs*)) (str "dhf/allure-results holds " (count fs*) " results"))
      nil)))
