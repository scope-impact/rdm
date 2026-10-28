(ns steps.kernel-command
  "Steps of the kernel component Command (dhf/spec/01-start-a-project/command.feature)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-shared :as k]))

(def tasks ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist"
            "status" "trace" "release" "score" "scoreboard" "oracle" "plugin" "publish" "audit" "observe"])

(defstep #"its standard output, read as EDN, is \{:help \[\{:task T :usage U :does D\} \.\.\.\]\}, one map per task in the order below, T the task's name as a string"
  (fn [ctx]
    (let [h (:help (:result ctx))]
      (k/check! (vector? h) "no :help vector: " (k/shows ctx))
      (k/check! (every? #(= #{:task :usage :does} (set (keys %))) h) "not every entry is {:task :usage :does}")
      (k/check! (= tasks (mapv :task h)) "the tasks are " (pr-str (mapv :task h)))
      ctx)))

(defstep #"it lists the task (\S+) with its usage (.+?), described as (.+)"
  (fn [ctx task usage does]
    (let [e (first (filter #(= task (:task %)) (:help (:result ctx))))]
      (k/check! (= usage (:usage e)) "the usage of " task " is " (pr-str (:usage e)))
      (k/check! (= does (:does e)) "the description of " task " is " (pr-str (:does e)))
      ctx)))

;; ------------------------------------------------------------------ results directory

(defn results-project! [ctx]
  (let [ctx (k/spec-project! ctx)]
    (k/result-json! ctx "dhf/allure-results" {:spec "spec-0001"})
    (k/result-json! ctx "other" {:spec "spec-0001"})
    ctx))

(defstep #"the configured results directory is dhf/allure-results"
  (fn [ctx] (results-project! ctx)))

(defstep #"\"rdm-spec status\" is run with no --results"
  (fn [ctx] (k/run-rdm ctx "status")))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= v "unset")
      ctx
      (let [d (str (:base ctx) "/r1")]
        (k/result-json! ctx d {:spec "spec-0001"})
        (-> ctx (assoc-in [:env "RESULTS_DIR"] d) (assoc-in [:ph "R1"] d))))))

(defstep #"it reads (dhf/allure-results|R1|other)"
  (fn [ctx d]
    (k/exits! ctx 0)
    (let [expected (if (= d "R1") (get-in ctx [:ph "R1"]) d)]
      (k/check! (= expected (get-in ctx [:result :status :results])) "it read " (pr-str (get-in ctx [:result :status :results])))
      ctx)))

;; ------------------------------------------------------------------ a project in a folder

(defn thing-feature [n]
  (k/feature-text "Thing" ["UN-001"]
                  (for [i (range 1 (inc n))] {:id (format "spec-%04d" i) :title (str "The thing works " i)})))

(def catch-all-steps
  "(ns steps.any (:require [rdm-spec.kernel.runner :refer [defstep]]))\n(defstep #\".*\" (fn [ctx] ctx))\n")

(defn wallet!
  "A repository whose project is its folder PREFIX (or its root when nil): a
  kernel-only spec whose scenarios change between spec-2 and spec-3, a cycle
  03 of spec-3, tests that run the scenarios, and outside the folder a broken
  configuration and model at the paths a root project would use."
  [ctx prefix]
  (let [b (k/scratch! "repo-")
        repo (str b "/repo")
        dir (if prefix (str repo "/" prefix) repo)
        ctx (assoc ctx :base b :dir dir :repo repo)]
    (fs/create-dirs dir)
    (k/git! ctx {:in repo} "init" "-q")
    (let [cfg {:id-prefix "spec" :spec-dir "dhf/spec" :steps-dir "dhf/steps" :results-dir "dhf/allure-results"
               :implementation ["src"]
               :code-layout {:context ["src/{context}/"] :component ["src/{context}/{component}.clj"]}
               :clean-room {:deny {:implementation true :measurements ["dhf/cycles"] :generated ["dhf/allure-results"]}
                            :keep ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/steps" "dhf/views" "dhf/decisions"]}
               :verify [{:kind :tests :command (conj (k/rdm-command-line) "run")}]}]
      (k/write-edn! ctx "dhf/harness/config.edn" cfg))
    (k/write-edn! ctx "dhf/spec/model.edn" k/kernel-model)
    (k/write-edn! ctx "dhf/spec/needs.edn" k/demo-needs)
    (k/write-edn! ctx "dhf/raw/sources.edn" k/demo-sources)
    (k/write! ctx "dhf/raw/demo.md" "# demo\n")
    (k/write! ctx "dhf/wiki/index.md" "# Wiki\n\n## Pages\n")
    (k/write! ctx "dhf/spec/ids.edn" "{:issued 3}\n")
    (k/write! ctx "dhf/steps/any.clj" catch-all-steps)
    (k/write! ctx "src/kernel/thing.clj" "(ns kernel.thing)\n")
    (k/write! ctx "dhf/spec/demo/thing.feature" (thing-feature 2))
    (k/git! ctx {:in repo} "add" "-A")
    (k/git! ctx {:in repo} "commit" "-q" "-m" "spec-2")
    (k/git! ctx {:in repo} "tag" "spec-2")
    (k/write! ctx "dhf/spec/demo/thing.feature" (thing-feature 3))
    (k/git! ctx {:in repo} "add" "-A")
    (k/git! ctx {:in repo} "commit" "-q" "-m" "spec-3")
    (k/git! ctx {:in repo} "tag" "spec-3")
    (k/write-edn! ctx "dhf/cycles/03/cycle.edn"
                  {:cut "spec-3" :scope :project :builder {:name "b" :actor :agent}
                   :grader {:name "g" :actor :agent} :spec-owner {:name "o" :actor :human}})
    (assoc-in ctx [:ph "ROOM"] (str b "/room"))))

(defn room-for! [ctx line]
  (when (str/starts-with? line "verify")
    (k/write! (assoc ctx :dir (get-in ctx [:ph "ROOM"])) "src/kernel/thing.clj" "(ns kernel.thing)\n"))
  ctx)

(defstep #"a repository whose project is its folder wallet/, holding wallet/dhf/harness/config.edn, whose commits tagged spec-2 and spec-3 change one component's scenarios, and whose dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [ctx] (wallet! ctx "wallet")))

(defstep #"files outside wallet/ at the same paths a project at the root would use: dhf/harness/config.edn and dhf/spec/model.edn"
  (fn [ctx]
    (let [outside (assoc ctx :dir (:repo ctx))]
      (k/write! outside "dhf/harness/config.edn" "{:broken")
      (k/write! outside "dhf/spec/model.edn" "{:broken")
      ctx)))

(defstep #"\"rdm-spec ([^\"]*)\" is run in wallet/"
  (fn [ctx line]
    (let [ctx (k/run-rdm (room-for! ctx line) line)
          root-ctx (room-for! (wallet! (dissoc ctx :ph) nil) line)
          root-run (k/run-rdm root-ctx line)]
      (assoc ctx :at-root root-run))))

(defstep #"it exits as it would for the same project at a repository's root, and (.+)"
  (fn [ctx reads]
    (k/check! (= (:exit (:at-root ctx)) (:exit ctx))
              "in wallet/ it exits " (:exit ctx) ", at a root " (:exit (:at-root ctx)) ": " (k/shows ctx) " / " (k/shows (:at-root ctx)))
    (k/exits! ctx 0)
    (let [room (get-in ctx [:ph "ROOM"])]
      (cond
        (str/starts-with? reads "ROOM holds")
        (do (k/check! (fs/exists? (str room "/dhf/spec/demo/thing.feature")) "the room holds no dhf/spec at its root")
            (k/check! (not (fs/exists? (str room "/wallet"))) "the room holds wallet/")
            (k/check! (not (fs/exists? (str room "/src"))) "the room holds the implementation")
            (k/check! (not (fs/exists? (str room "/dhf/cycles"))) "the room holds the measurements"))
        (str/starts-with? reads "its checkout")
        (let [b (k/path ctx "dhf/cycles/03/build")]
          (k/check! (fs/exists? (str b "/dhf/harness/config.edn")) "the checkout holds no configuration at its root")
          (k/check! (= 3 (count (re-seq #"@spec-" (slurp (str b "/dhf/spec/demo/thing.feature"))))) "the checkout is not spec-3")
          (k/check! (not (fs/exists? (str b "/wallet"))) "the checkout holds wallet/"))
        (str/starts-with? reads "the checklist")
        (let [cl (cheshire.core/parse-string (k/slurp-rel ctx "dhf/cycles/03/checklist.json"))]
          (k/check! (= ["spec-0001" "spec-0002" "spec-0003"] (map #(get % "id") (get cl "items"))) "the checklist is " (pr-str cl)))
        :else
        (k/check! (= [{:component :kernel/thing}] (get-in ctx [:result :scopes :scopes])) "the scopes are " (pr-str (:result ctx))))
      ctx)))

(defstep #"it reads nothing outside wallet/"
  (fn [ctx] (k/check! (not (str/includes? (str (:err ctx)) "broken")) "it read outside wallet/: " (k/shows ctx)) ctx))

(defstep #"a project in the folder wallet/ whose working tree has an uncommitted change to a feature file and an untracked file, and whose HEAD is not the commit tagged spec-3"
  (fn [ctx]
    (let [ctx (wallet! ctx "wallet")]
      (k/write! ctx "dhf/spec/demo/thing.feature" (thing-feature 1))
      (k/git! ctx "add" "-A")
      (k/git! ctx "commit" "-q" "-m" "after the cut")
      (k/write! ctx "dhf/spec/demo/thing.feature" (str (thing-feature 1) "\n# changed\n"))
      (k/write! ctx "notes-untracked.md" "untracked\n"))))

(defstep #"ROOM holds the feature file as committed at spec-3, and neither the change nor the untracked file"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [room (get-in ctx [:ph "ROOM"])]
      (k/check! (= (thing-feature 3) (slurp (str room "/dhf/spec/demo/thing.feature"))) "the room's feature file is not spec-3's")
      (k/check! (not (fs/exists? (str room "/notes-untracked.md"))) "the untracked file reached the room")
      ctx)))

;; ------------------------------------------------------------------ reporting

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx {:model (k/context-model [[:demo [[:thing "Thing"]]]])
                                    :features {"dhf/spec/demo/thing.feature"
                                               "@UN-001\nFeature: Thing\n\n  @spec-0001\n  Scenario: No level\n    Given a thing\n"}
                                    :ledger 1})
          f (k/path ctx "dhf/views/context.mmd")]
      (spit f (str/replace-first (slurp f) #"model-sha256: [0-9a-f]+" (str "model-sha256: " (apply str (repeat 64 "0")))))
      ctx)))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/err-line-has! ctx "dhf/spec/demo/thing.feature:5" "level")
    (k/err-line-has! ctx "context" "stale")
    ctx))

(defstep #"a spec that passes every check" (fn [ctx] (k/spec-project! ctx)))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [ctx]
    (k/spec-project! ctx {:features {"dhf/spec/demo/thing.feature"
                                     "@UN-001\nFeature: Thing\n\n  @spec-0001\n  Scenario: No level\n    Given a thing\n"}})))

(def line-re #"^(?:(?:\S+?:(?:\d+:)? )?(?:warning: )?[a-z][a-z0-9-]*: .+|cannot-run: .+|event: \S+ published-by \S+)$")

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (k/check! (seq (k/err-lines ctx)) "the error stream is empty")
    (doseq [l (k/err-lines ctx)] (k/check! (re-matches line-re l) "a line not in the shape: " (pr-str l)))
    (k/err-line-has! ctx "dhf/spec/demo/thing.feature:5: ")
    ctx))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx] (k/check! (map? (:result ctx)) "standard output is not an EDN map: " (pr-str (:out ctx))) ctx))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "dhf/allure-results") ctx))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (k/exits! ctx 2)
    (apply k/err-has! ctx "frobnicate" tasks)
    ctx))
