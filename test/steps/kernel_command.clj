(ns steps.kernel-command
  "Steps of the kernel component Command (01-start-a-project/command.feature,
  11-project-in-a-folder/command.feature)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(def task-order
  ["help" "init" "adopt" "stamp" "check" "views" "cut" "scopes" "fence" "run" "verify" "checklist" "status"
   "trace" "release" "score" "scoreboard" "oracle" "plugin" "publish" "audit" "observe"])

(defstep #"its standard output, read as EDN, is \{:help \[\{:task T :usage U :does D\} \.\.\.\]\}, one map per task in the order below, T the task's name as a string"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [h (:help (:edn ctx))]
      (k/expect! (and (vector? h) (every? #(= #{:task :usage :does} (set (keys %))) h)) ctx "not {:help [{:task :usage :does} ...]}")
      (k/expect! (= task-order (map :task h)) ctx "the tasks are not in the order of the table"))
    ctx))

(defstep #"it lists the task (\S+) with its usage (.+), described as (.+)"
  (fn [ctx task usage does]
    (let [e (some #(when (= task (:task %)) %) (:help (:edn ctx)))]
      (k/expect! (= (str/trim usage) (:usage e)) ctx (str "the usage of " task " is " (pr-str (:usage e))))
      (k/expect! (= (str/trim does) (:does e)) ctx (str task " is described as " (pr-str (:does e)))))
    ctx))

;; ------------------------------------------------------------------ the results directory

(defn- status-project
  "A project whose results directory dhf/allure-results, R1 and other each hold a result."
  []
  (let [ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})})
        d (:dir ctx)]
    (k/result! (k/path d "dhf/allure-results") "spec-0001" "passed")
    (k/result! (k/path d "other") "spec-0001" "passed")
    ctx))

(defstep #"the configured results directory is dhf/allure-results"
  (fn [_] (let [ctx (status-project)]
            (k/expect! (= "dhf/allure-results" (:results-dir (k/config (:dir ctx)))) ctx "not configured")
            ctx)))

(defstep #"the environment variable RESULTS_DIR is (unset|R1)"
  (fn [ctx v]
    (if (= v "unset")
      ctx
      (let [r1 (k/path (:top ctx) "r1")]
        (k/result! r1 "spec-0001" "passed")
        (-> ctx (assoc-in [:vars "R1"] r1) (assoc-in [:env "RESULTS_DIR"] r1))))))

(defstep #"it reads (dhf/allure-results|R1|other)"
  (fn [ctx dir]
    (k/exits! ctx 0)
    (let [expected (get (:vars ctx) dir dir)]
      (k/expect! (= expected (get-in ctx [:edn :status :results])) ctx (str "it did not read " expected)))
    ctx))

;; ------------------------------------------------------------------ a gate stops the task

(defstep #"a project whose spec has a scenario with no level and a view older than the model"
  (fn [_]
    (let [ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001" :level nil}]})
                                "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"}]})})]
      (k/write! (:dir ctx) "dhf/views/shop-components.mmd" "%% model-sha256: 0000\nC4Component\n")
      ctx)))

(defstep #"it exits 1 and names both the missing level and the stale view"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/line-has! ctx "dhf/spec/shop/cart.feature:" "spec-0001")
    (k/line-has! ctx "shop-components" "stale")
    ctx))

;; ------------------------------------------------------------------ how every task reports

(defstep #"a spec that passes every check"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})
                            "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"}]})})))

(defstep #"a feature file in which one scenario carries no level tag"
  (fn [_] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id "spec-0002" :level nil}]})
                            "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0003"}]})})))

(defstep #"the error stream holds one line per finding: \"FILE:LINE: RULE: what is wrong\", FILE and LINE left out where none applies"
  (fn [ctx]
    (k/finding-shape! ctx)
    (k/expect! (some #(re-matches #"dhf/spec/shop/cart\.feature:\d+: [a-z-]+: .*spec-0002.*" %) (k/finding-lines ctx)) ctx
               "no FILE:LINE: RULE: what line names spec-0002")
    ctx))

(defstep #"the standard output holds only the task's result, as EDN"
  (fn [ctx]
    (k/expect! (map? (:edn ctx)) ctx "standard output is not one EDN map")
    ctx))

(defstep #"it exits 2 and names the missing directory"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "dhf/allure-results") (k/expect! (str/blank? (:out ctx)) ctx "printed a result") ctx))

(defstep #"it exits 2, names frobnicate, and lists every task"
  (fn [ctx]
    (k/exits! ctx 2)
    (k/err-has! ctx "frobnicate")
    (apply k/err-has! ctx task-order)
    ctx))

;; ------------------------------------------------------------------ 11: a project in a folder

(defstep #"a repository whose project is its folder wallet/, holding wallet/dhf/harness/config.edn, whose commits tagged spec-2 and spec-3 change one component's scenarios, and whose dhf/cycles/03/cycle.edn names the cut spec-3"
  (fn [_] (k/wallet-repo!)))

(defstep #"files outside wallet/ at the same paths a project at the root would use: dhf/harness/config.edn and dhf/spec/model.edn"
  (fn [ctx]
    (k/expect! (fs/exists? (k/path (:repo ctx) "dhf/harness/config.edn")) ctx "no decoy configuration")
    ctx))

(defstep #"\"rdm-spec ([^\"]+)\" is run in wallet/"
  (fn [ctx cmd]
    (let [ctx (cond-> ctx (re-find #"ROOM" cmd) (k/var-dir! "ROOM"))
          ctx (if (re-find #"verify" cmd)
                (let [room (k/path (:top ctx) "vroom")]
                  (k/write! room "src/shop/cart.clj" "(ns cart)\n")
                  (k/write! room "test/steps/all.clj" k/catch-all-steps)
                  (assoc-in ctx [:vars "ROOM"] room))
                ctx)
          before (k/tree-snapshot (:repo ctx))]
      (assoc (k/rdm! ctx cmd) :repo-before before))))

(defstep #"it exits as it would for the same project at a repository's root, and (.+)"
  (fn [ctx reads]
    (k/exits! ctx 0)
    (let [w (:dir ctx)]
      (cond
        (str/starts-with? reads "ROOM holds")
        (let [room (get-in ctx [:vars "ROOM"])]
          (k/expect! (fs/exists? (k/path room "dhf/harness/config.edn")) ctx "ROOM does not hold wallet/'s configuration at its root")
          (k/expect! (fs/exists? (k/path room "dhf/spec/shop/till.feature")) ctx "ROOM does not hold wallet/'s spec")
          (k/expect! (not (fs/exists? (k/path room "src"))) ctx "ROOM holds denied implementation")
          (k/expect! (not (fs/exists? (k/path room "wallet"))) ctx "ROOM holds the folder, not its contents"))
        (str/starts-with? reads "its checkout")
        (let [b (k/path w "dhf/cycles/03/build")]
          (k/expect! (fs/exists? (k/path b "dhf/harness/config.edn")) ctx "the checkout is not wallet/ at its root")
          (k/expect! (str/includes? (slurp (k/path b "dhf/spec/shop/till.feature")) "spec-0003") ctx "the checkout is not spec-3"))
        (str/starts-with? reads "the checklist holds")
        (let [j (k/json-file (k/path w "dhf/cycles/03/checklist.json"))]
          (k/expect! (= ["spec-0001" "spec-0002" "spec-0003"] (map #(get % "id") (get j "items"))) ctx "not wallet/'s scenarios at spec-3"))
        (str/starts-with? reads "it names the one component")
        (k/expect! (= [{:component :shop/till}] (get-in ctx [:edn :scopes :scopes])) ctx "not the one changed component")
        :else (fail! (str "unknown outcome " reads))))
    ctx))

(defstep #"it reads nothing outside wallet/"
  (fn [ctx]
    (k/expect! (not (str/includes? (str (:out ctx) (:err ctx)) "decoy")) ctx "it read a decoy")
    (let [after (k/tree-snapshot (:repo ctx))
          outside (fn [m] (into {} (remove #(str/starts-with? (key %) "wallet/") m)))]
      (k/expect! (= (outside (:repo-before ctx)) (outside after)) ctx "something outside wallet/ changed"))
    ctx))
