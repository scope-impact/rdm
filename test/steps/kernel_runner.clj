(ns steps.kernel-runner
  "Step definitions for dhf/spec/kernel/runner.feature. Each scenario builds a
  small project of its own (the room's configuration, a spec, step definition
  files in the configured step directory) and runs `rdm-spec run` in it with
  the shared When. A step function written into the project records each call
  it receives in the project's calls.edn, one EDN value per line, so the
  scenario can see what the runner handed it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The project

(defn- config [] (s/room-config))
(defn- steps-dir [] (:steps-dir (config)))
(defn- spec-dir [] (:spec-dir (config)))
(defn- results-of [dir] (str (fs/file dir (:results-dir (config)))))

(defn- project! [] (s/project!))

(defn- calls-file [dir] (str (fs/file dir "calls.edn")))

(defn- recorder
  "Clojure source of an expression that records `form` (evaluated in the step
  function) in the project's calls.edn."
  [dir form]
  (str "(spit " (pr-str (calls-file dir)) " (str (pr-str " form ") \"\\n\") :append true)"))

(defn- calls [dir]
  (let [f (calls-file dir)]
    (if (fs/exists? f)
      (mapv edn/read-string (remove str/blank? (str/split-lines (slurp f))))
      [])))

(defn- write-steps! [dir name body]
  (s/write! dir (str (steps-dir) "/" name ".clj") (s/step-file body)))

(defn- write-feature! [dir spec-path body]
  (s/write! dir (str (spec-dir) "/" spec-path ".feature") (s/feature body)))

(defn- scenario
  "Scenario text: `tags`, `title` and the step lines."
  [tags title & steps]
  (str "    " tags "\n    Scenario: " title "\n"
       (str/join (for [st steps] (str "      " st "\n")))))

(defn- rule [& scenarios]
  (str "  Rule: The demo rule\n\n" (str/join "\n" scenarios)))

(defn- results [ctx] (s/read-results (results-of (:dir ctx))))

(defn- the-result
  "The one result the run wrote."
  [ctx]
  (let [rs (results ctx)]
    (s/check! (= 1 (count rs)) (str "the run wrote " (count rs) " results\n" (s/show (:run ctx))))
    (first rs)))

(defn- message [result] (str (get-in result [:statusDetails :message])))

(defn- run-run [ctx]
  (kc/run-line ctx "run"))

;; ---------------------------------------------------------------------------
;; Step definitions are written against one small API

(defstep #"a step definition file in the project's configured step directory that defines a step by the pattern \"the spec holds \(\\d\+\) features\" and a function of the context and the captured text, through the runner's step API"
  (fn [ctx]
    (let [dir (project!)]
      (write-steps! dir "demo"
                    (str "(defstep #\"the spec holds (\\d+) features\"\n"
                         "  (fn [ctx n] " (recorder dir "{:ctx ctx :n n}") " (assoc ctx :n n)))"))
      (assoc ctx :dir dir))))

(defstep #"\"rdm-spec run\" runs a scenario with the step \"Given ([^\"]+)\""
  (fn [ctx text]
    (write-feature! (:dir ctx) "demo/alpha" (rule (scenario "@spec-0001 @e2e" "One step" (str "Given " text))))
    (run-run ctx)))

(defstep #"the function is called with the scenario's context and \"(\d+)\""
  (fn [ctx n]
    (let [cs (calls (:dir ctx))]
      (s/check! (= [{:ctx {} :n n}] cs) (str "the function was called with " (pr-str cs) "\n" (s/show (:run ctx)))))))

(defstep #"two steps whose functions each add a key to the context they are given"
  (fn [ctx]
    (let [dir (project!)]
      (write-steps! dir "demo"
                    (str "(defstep #\"the first step\"\n"
                         "  (fn [ctx] " (recorder dir "[:first ctx]") " (assoc ctx :first 1)))\n"
                         "(defstep #\"the second step\"\n"
                         "  (fn [ctx] " (recorder dir "[:second ctx]") " (assoc ctx :second 2)))"))
      (assoc ctx :dir dir))))

(defstep #"\"rdm-spec run\" runs a scenario with both steps"
  (fn [ctx]
    ;; the scenario, then the next one, which starts again from its first step
    (write-feature! (:dir ctx) "demo/alpha"
                    (rule (scenario "@spec-0001 @e2e" "Both steps" "Given the first step" "And the second step")
                          (scenario "@spec-0002 @e2e" "The next scenario" "Given the first step")))
    (run-run ctx)))

(defstep #"the second function receives the key the first added"
  (fn [ctx]
    (let [cs (calls (:dir ctx))]
      (s/check! (= [:first {}] (first cs)) (str "the calls were " (pr-str cs) "\n" (s/show (:run ctx))))
      (s/check! (= [:second {:first 1}] (second cs)) (str "the calls were " (pr-str cs))))))

(defstep #"the next scenario's first step receives an empty context"
  (fn [ctx]
    (let [cs (calls (:dir ctx))]
      (s/check! (= 3 (count cs)) (str "the calls were " (pr-str cs)))
      (s/check! (= [:first {}] (nth cs 2)) (str "the next scenario's first step received " (pr-str (nth cs 2)))))))

(defstep #"two step definitions whose patterns both match \"Given ([^\"]+)\""
  (fn [ctx text]
    (let [dir (project!)]
      (write-steps! dir "one" "(defstep #\"a spec\" (fn [ctx] ctx))")
      (write-steps! dir "two" "(defstep #\"a (\\w+)\" (fn [ctx _] ctx))")
      (assoc ctx :dir dir :step (str "Given " text)
             :definitions [(str (fs/file dir (steps-dir) "one.clj")) (str (fs/file dir (steps-dir) "two.clj"))]))))

(defstep #"\"rdm-spec run\" runs a scenario with that step"
  (fn [ctx]
    (write-feature! (:dir ctx) "demo/alpha" (rule (scenario "@spec-0001 @e2e" "An ambiguous step" (:step ctx))))
    (run-run ctx)))

(defstep #"its result is broken and names both definitions"
  (fn [ctx]
    (let [r (the-result ctx)
          msg (message r)]
      (s/check! (= "broken" (:status r)) (str "the result is " (:status r)))
      (doseq [d (:definitions ctx)]
        (s/check! (str/includes? msg (str (fs/file-name d))) (str "the result does not name " d ": " msg))))))

;; ---------------------------------------------------------------------------
;; Every scenario run leaves one result, labelled with what it verifies

(def ^:private demo-steps
  "(defstep #\"a step that passes\" (fn [ctx] ctx))\n(defstep #\"another step that passes\" (fn [ctx] ctx))")

(defstep #"the scenario @(\S+) in the component (\S+) of the bounded context (\S+)"
  (fn [ctx id component context]
    (let [dir (project!)
          comp-id (keyword context (str/lower-case component))
          model {:system {:id :demo :name "Demo" :description "A demo"}
                 :containers [{:id :demo-c :name "demo" :kind :runs :description "d" :technology "t"
                               :contexts [{:id (keyword context) :name context :description "d"
                                           :components [{:id comp-id :name component :description "d"
                                                         :spec (str context "/" (str/lower-case component))}]}]}]
                 :kernel [{:id :kernel/command :name "Command" :description "d" :spec "kernel/command"}]}]
      (s/write! dir (:model (config)) (pr-str model))
      (write-feature! dir (str context "/" (str/lower-case component))
                      (rule (scenario (str "@" id " @e2e") "The labelled scenario"
                                      "Given a step that passes" "Then another step that passes")))
      (write-feature! dir "kernel/command"
                      (rule (scenario "@spec-0900 @e2e" "A kernel scenario" "Given a step that passes")))
      (write-steps! dir "demo" demo-steps)
      (assoc ctx :dir dir :id id :component component :context context))))

(defn- by-spec [ctx id] (filter #(= [id] (s/labels % "spec")) (results ctx)))

(defstep #"it writes one Allure result labelled spec=(\S+), component=(\S+) and context=(\S+)"
  (fn [ctx id component context]
    (let [rs (by-spec ctx id)]
      (s/check! (= 1 (count rs)) (str (count rs) " results are labelled spec=" id "\n" (s/show (:run ctx))))
      (s/check! (= [component] (s/labels (first rs) "component")) (str "component " (s/labels (first rs) "component")))
      (s/check! (= [context] (s/labels (first rs) "context")) (str "context " (s/labels (first rs) "context"))))))

(defstep #"a kernel scenario's result is labelled context=kernel"
  (fn [ctx]
    (let [rs (by-spec ctx "spec-0900")]
      (s/check! (= 1 (count rs)) (str (count rs) " results for the kernel scenario"))
      (s/check! (= ["kernel"] (s/labels (first rs) "context")) (str "context " (s/labels (first rs) "context")))
      (s/check! (= ["Command"] (s/labels (first rs) "component")) (str "component " (s/labels (first rs) "component"))))))

(defstep #"each step is a step of the result, with its own status"
  (fn [ctx]
    (let [r (first (by-spec ctx (:id ctx)))]
      (s/check! (= [{:name "Given a step that passes" :status "passed"}
                    {:name "Then another step that passes" :status "passed"}]
                   (mapv #(select-keys % [:name :status]) (:steps r)))
                (str "the steps are " (pr-str (:steps r)))))))

(defstep #"a stamped scenario outline with three example rows"
  (fn [ctx]
    (let [dir (project!)]
      (write-feature! dir "demo/alpha"
                      (str "  Rule: The demo rule\n\n"
                           "    @spec-0001 @e2e\n"
                           "    Scenario Outline: An outline over <n>\n"
                           "      Given the value <n>\n\n"
                           "      Examples:\n"
                           "        | n |\n        | 1 |\n        | 2 |\n        | 3 |\n"))
      (write-steps! dir "demo" "(defstep #\"the value (\\d+)\" (fn [ctx n] (assoc ctx :n n)))")
      (assoc ctx :dir dir :id "spec-0001"))))

(defstep #"it writes three Allure results, each labelled with the outline's id and naming its row"
  (fn [ctx]
    (let [rs (results ctx)]
      (s/check! (= 3 (count rs)) (str "the run wrote " (count rs) " results\n" (s/show (:run ctx))))
      (s/check! (every? #(= [(:id ctx)] (s/labels % "spec")) rs) "a result is not labelled with the outline's id")
      (s/check! (= #{"1" "2" "3"} (set (for [r rs p (:parameters r) :when (= "n" (:name p))] (:value p))))
                "the rows are not the results' parameters")
      (doseq [n ["1" "2" "3"]]
        (s/check! (some #(str/ends-with? (:name %) (str "n=" n "]")) rs) (str "no result's name ends with the row n=" n))))))

(defstep #"a scenario whose second step's definition throws"
  (fn [ctx]
    (let [dir (project!)]
      (write-feature! dir "demo/alpha"
                      (rule (scenario "@spec-0001 @e2e" "A failing scenario"
                                      "Given a step that passes" "When a step that throws" "Then a step after it")))
      (write-steps! dir "demo"
                    (str "(defstep #\"a step that passes\" (fn [ctx] ctx))\n"
                         "(defstep #\"a step that throws\" (fn [ctx] (throw (ex-info \"the demo error\" {}))))\n"
                         "(defstep #\"a step after it\" (fn [ctx] ctx))"))
      (assoc ctx :dir dir))))

(defstep #"the result is failed and names the second step and the error"
  (fn [ctx]
    (let [r (the-result ctx)
          msg (message r)]
      (s/check! (= "failed" (:status r)) (str "the result is " (:status r)))
      (s/check! (str/includes? msg "When a step that throws") (str "the result does not name the step: " msg))
      (s/check! (str/includes? msg "the demo error") (str "the result does not name the error: " msg))
      (s/check! (= ["passed" "failed"] (mapv :status (take 2 (:steps r)))) (pr-str (:steps r))))))

(defstep #"the steps after it are reported as not run"
  (fn [ctx]
    (let [r (the-result ctx)
          after (drop 2 (:steps r))]
      (s/check! (= ["Then a step after it"] (mapv :name after)) (pr-str (:steps r)))
      (s/check! (every? #(= "skipped" (:status %)) after) (pr-str after)))))

(defstep #"a stamped scenario with a step no step definition matches"
  (fn [ctx]
    (let [dir (project!)]
      (write-feature! dir "demo/alpha"
                      (rule (scenario "@spec-0001 @e2e" "An undefined step"
                                      "Given a step that passes" "When a step nobody defined")))
      (write-steps! dir "demo" "(defstep #\"a step that passes\" (fn [ctx] ctx))")
      (assoc ctx :dir dir :unmatched "When a step nobody defined"))))

(defstep #"the scenario's Allure result is broken"
  (fn [ctx]
    (let [r (the-result ctx)]
      (s/check! (= "broken" (:status r)) (str "the result is " (:status r) "\n" (s/show (:run ctx)))))))

(defstep #"the result names the unmatched step"
  (fn [ctx]
    (let [msg (message (the-result ctx))]
      (s/check! (str/includes? msg (:unmatched ctx)) (str "the result does not name the step: " msg)))))

(defstep #"a stamped scenario tagged @wip, with step definitions for its steps"
  (fn [ctx]
    (let [dir (project!)]
      (write-feature! dir "demo/alpha"
                      (rule (scenario "@spec-0001 @e2e @wip" "Planned work" "Given a planned step" "Then another planned step")))
      (write-steps! dir "demo"
                    (str "(defstep #\"a planned step\" (fn [ctx] " (recorder dir ":ran") " ctx))\n"
                         "(defstep #\"another planned step\" (fn [ctx] " (recorder dir ":ran") " ctx))"))
      (assoc ctx :dir dir :id "spec-0001"))))

(defstep #"the scenario's steps are not run"
  (fn [ctx]
    (s/check! (empty? (calls (:dir ctx))) (str "the steps ran\n" (s/show (:run ctx))))))

(defstep #"its result is skipped and labelled planned"
  (fn [ctx]
    (let [r (the-result ctx)]
      (s/check! (= "skipped" (:status r)) (str "the result is " (:status r)))
      (s/check! (= ["true"] (s/labels r "planned")) (str "planned is " (s/labels r "planned")))
      (s/check! (= [(:id ctx)] (s/labels r "spec")) (str "spec is " (s/labels r "spec"))))))

;; ---------------------------------------------------------------------------
;; The runner discovers, and refuses to find nothing

(defstep #"a spec holding twenty feature files"
  (fn [ctx]
    (let [dir (project!)]
      (doseq [i (range 1 21)]
        (write-feature! dir (format "demo/part%02d" i)
                        (rule (scenario (format "@spec-%04d @e2e" i) (str "Part " i) "Given a step that passes"))))
      (write-steps! dir "demo" "(defstep #\"a step that passes\" (fn [ctx] ctx))")
      (assoc ctx :dir dir))))

(defstep #"it runs the scenarios of all twenty"
  (fn [ctx]
    (let [rs (results ctx)
          ids (set (mapcat #(s/labels % "spec") rs))]
      (s/check! (zero? (:exit (:run ctx))) (s/show (:run ctx)))
      (s/check! (= 20 (count rs)) (str "the run wrote " (count rs) " results"))
      (s/check! (= (set (for [i (range 1 21)] (format "spec-%04d" i))) ids) (str "the ids are " (sort ids)))
      (s/check! (every? #(= "passed" (:status %)) rs) "a result did not pass"))))

(defstep #"a spec directory holding no feature file"
  (fn [ctx]
    (let [dir (project!)]
      (s/write! dir (str (spec-dir) "/README.md") "No feature file here.\n")
      (write-steps! dir "demo" "(defstep #\"a step that passes\" (fn [ctx] ctx))")
      (assoc ctx :dir dir))))

(defstep #"it exits 2, says no scenario was found, and writes no result"
  (fn [ctx]
    (let [r (:run ctx)]
      (s/check! (= 2 (:exit r)) (s/show r))
      (s/check! (str/includes? (:err r) "no scenario was found") (s/show r))
      (s/check! (empty? (results ctx)) "a result was written"))))
