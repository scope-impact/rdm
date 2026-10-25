(ns steps.ratification-ratify
  "Step definitions for dhf/spec/ratification/ratify.feature.

  Context keys set here: :dir (the cycle project), :room (the room), :cycle
  (\"03\"), :tests (what the room's test.clj does: {:exit :results}),
  :room-tree (the tree hash H of the room's implementation), :room-edits
  (the files the builder edited in the room). The shared steps
  \"dhf/cycles/03/cycle.edn names the cut spec-3\" and \"the spec at cut
  spec-3 holds a scenario with no id\" set :dir :room :cycle (:unstamped)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.ratification-support :as rf]
            [steps.support :as s]))

(def ^:private build "dhf/cycles/03/build")
(def ^:private results "dhf/cycles/03/results")

(defn- impl-paths [ctx] (:implementation (kc/config-of (:dir ctx))))

(defn- project!
  "ctx with a cycle project (unless it has one) and a room whose tests do
  `tests` (merged into what they did before)."
  [ctx tests]
  (let [dir (or (:dir ctx) (rf/cycle-project!))
        tests (merge {:exit 0 :results []} (:tests ctx) tests)
        room (or (:room ctx) (rf/room! tests))]
    (rf/write-tests! room tests)
    (assoc ctx :dir dir :room room :cycle "03" :tests tests)))

(defn- ratified [ctx]
  (let [f (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn")]
    (s/check! (fs/exists? f) (str "no dhf/cycles/03/ratified.edn was written\n" (s/show (kc/the-run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- exits-naming!
  "Checks the run exited 1 with a finding line matching every one of `res`."
  [ctx & res]
  (let [r (kc/exits! ctx 1)]
    (s/check! (some (fn [l] (every? #(re-find % l) res)) (kc/finding-lines r))
              (str "no finding line matches " (str/join ", " (map pr-str res)) "\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------
;; Ratify trusts only the cut

(defstep #"it ratifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (let [dir (:dir ctx)
          b (fs/file dir build)
          impl (impl-paths ctx)
          at-cut (into (sorted-map)
                       (for [r (str/split-lines (rf/git! dir "ls-tree" "-r" "--name-only" "spec-3"))
                             :when (not (rf/implementation? impl r))]
                         [r (rf/git! dir "show" (str "spec-3:" r))]))
          got (into (sorted-map) (remove (fn [[r _]] (rf/implementation? impl r)) (rf/files-under b)))]
      (s/check! (fs/directory? b) (str "no checkout at " build "\n" (s/show (kc/the-run ctx))))
      (s/check! (not (fs/exists? (fs/file b ".git"))) (str build " holds history (.git)"))
      (s/check! (= (set (keys at-cut)) (set (keys got)))
                (str "the checkout's files differ from spec-3's: extra " (pr-str (remove (set (keys at-cut)) (keys got)))
                     ", missing " (pr-str (remove (set (keys got)) (keys at-cut)))))
      (doseq [[r t] at-cut] (s/check! (= t (str/trim (get got r))) (str r " differs from spec-3's")))
      nil)))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (let [ctx (project! ctx {})]
      (fs/delete (fs/file (:dir ctx) "dhf/cycles/03/cycle.edn"))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (kc/refused-naming! ctx "dhf/cycles/03/cycle.edn") nil))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (let [dir (rf/cycle-project! (fn [d] (s/write! d "src/rdm_spec/old.clj" "(ns rdm-spec.old)\n")))
          ctx (project! (assoc ctx :dir dir) {:results (map #(vector % "passed") rf/stamped-not-planned)})]
      (s/check! (not (fs/exists? (fs/file (:room ctx) "src/rdm_spec/old.clj"))) "the room holds old.clj")
      ctx)))

(defstep #"the checkout it ratifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (s/check! (fs/directory? (fs/file (:dir ctx) build)) (str "no checkout at " build "\n" (s/show (kc/the-run ctx))))
    (s/check! (not (fs/exists? (fs/file (:dir ctx) build "src/rdm_spec/old.clj"))) "the checkout holds src/rdm_spec/old.clj")
    nil))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [impl (impl-paths ctx)
          ;; what the room's tests wrote into their working directory is theirs
          room (into (sorted-map) (filter (fn [[r _]] (rf/implementation? impl r)) (rf/files-under (:room ctx))))
          got (into (sorted-map) (filter (fn [[r _]] (rf/implementation? impl r)) (rf/files-under (fs/file (:dir ctx) build))))]
      (s/check! (seq impl) "the configuration names no implementation")
      (s/check! (= room got) (str "the checkout's implementation differs from the room's: "
                                  (pr-str (keys got)) " vs " (pr-str (keys room))))
      nil)))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (project! ctx {:results (map #(vector % "passed") rf/stamped-not-planned)})
          room (:room ctx)
          cfg (kc/config-of (:dir ctx))]
      (s/write! room "dhf/harness/config.edn"
                (pr-str (assoc cfg :test-command ["bb" "-e" "(System/exit 0)"] :implementation ["src"])))
      (s/write! room rf/lint-feature (str (rf/feature-text) "\n  @spec-0099 @e2e\n  Scenario: A scenario the builder added\n    Then it passes\n"))
      (assoc ctx :room-edits ["dhf/harness/config.edn" rf/lint-feature]))))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (let [b (fs/file (:dir ctx) build)]
      (s/check! (fs/directory? b) (str "no checkout at " build "\n" (s/show (kc/the-run ctx))))
      (doseq [r (:room-edits ctx)]
        (s/check! (= (rf/git! (:dir ctx) "show" (str "spec-3:" r)) (str/trim (slurp (str (fs/file b r)))))
                  (str r " in the checkout is not spec-3's")))
      ;; the cut's test command ran: the room's tests wrote their results
      (s/check! (fs/exists? (fs/file b "ratify-env.txt")) "the cut's test command did not run")
      (kc/exits! ctx 0)
      nil)))

;; ---------------------------------------------------------------------------
;; The tests and their results

(defstep #"\"rdm-spec ratify ROOM 03\" runs the declared test command"
  (fn [ctx]
    (kc/run-line (project! ctx {:results (map #(vector % "passed") rf/stamped-not-planned)}) "ratify ROOM 03")))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec ratify was run in"
  (fn [ctx]
    (let [f (fs/file (:dir ctx) build "ratify-env.txt")
          _ (s/check! (fs/exists? f) (str "the test command did not run\n" (s/show (kc/the-run ctx))))
          v (slurp (str f))
          want (fs/file (:dir ctx) results)]
      (s/check! (fs/absolute? v) (str "RESULTS_DIR is not absolute: " (pr-str v)))
      (s/check! (= (str (fs/canonicalize want)) (str (fs/canonicalize v)))
                (str "RESULTS_DIR is " (pr-str v) ", not " want))
      nil)))

(defstep #"the declared test command exits (\d+)( without writing any Allure result)?"
  (fn [ctx code none]
    (let [ctx (project! ctx {:exit (parse-long code) :results []})]
      (s/check! (= ["bb" "test.clj"] (:test-command (kc/config-of (:dir ctx))))
                "the declared test command is not the room's test.clj")
      (assoc ctx :no-results? (boolean none)))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (project! ctx {:results [["spec-0012" "passed"]]})))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] (project! ctx {:results (map #(vector % "passed") rf/stamped-not-planned)})))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (exits-naming! ctx #"test command" #"failed" (re-pattern (str "\\b" (get-in ctx [:tests :exit]) "\\b")))
    nil))

(defstep #"the test command's output is in dhf/cycles/03/test\.log, which the refusal names"
  (fn [ctx]
    (let [f (fs/file (:dir ctx) "dhf/cycles/03/test.log")]
      (s/check! (fs/exists? f) "no dhf/cycles/03/test.log")
      (s/check! (str/includes? (slurp (str f)) "the fixture's tests ran") "test.log does not hold the test command's output")
      (exits-naming! ctx #"test command" #"dhf/cycles/03/test\.log")
      nil)))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (exits-naming! ctx #"(?i)a green exit with no results is not a run") nil))

(defstep #"the stamped scenario @(spec-\d+) is not tagged @wip"
  (fn [ctx id]
    (let [ctx (project! ctx {})]
      (s/check! (re-find (re-pattern (str "@" id " @(?:e2e|contract)\\n")) (rf/feature-text)) (str id " is not stamped, or is @wip"))
      (assoc ctx :unnamed id))))

(defstep #"no result in dhf/cycles/03/results is labelled spec=(spec-\d+)"
  (fn [ctx id]
    (project! ctx {:results (for [i rf/stamped-not-planned :when (not= i id)] [i "passed"])})))

(defstep #"it exits 1 and names (spec-\d+)"
  (fn [ctx id] (exits-naming! ctx (re-pattern (str "\\b" id "\\b"))) nil))

(defstep #"a result in dhf/cycles/03/results labelled spec=(spec-\d+) is (failed|broken)"
  (fn [ctx id status]
    (assoc (project! ctx {:results (for [i rf/stamped-not-planned] [i (if (= i id) status "passed")])})
           :bad-status status)))

(defstep #"it exits 1 and names (spec-\d+) and its status"
  (fn [ctx id]
    (exits-naming! ctx (re-pattern (str "\\b" id "\\b")) (re-pattern (str "\\b" (:bad-status ctx) "\\b")))
    nil))

;; ---------------------------------------------------------------------------
;; What ratify records

(defstep #"a room whose implementation has a tree hash H"
  (fn [ctx]
    (let [ctx (project! ctx {:results (map #(vector % "passed") rf/stamped-not-planned)})]
      (s/write! (:room ctx) "test/rdm_spec/demo_test.clj" "(ns rdm-spec.demo-test)\n")
      (assoc ctx :room-tree (rf/tree-hash (:room ctx) (impl-paths ctx))))))

(defstep #"\"rdm-spec ratify ROOM 03\" is run and passes"
  (fn [ctx]
    (let [ctx (kc/run-line ctx "ratify ROOM 03")]
      (kc/exits! ctx 0)
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed and :checkout \"dhf/cycles/03/build\""
  (fn [ctx]
    (let [r (ratified ctx)]
      (s/check! (re-matches #"[0-9a-f]{40}" (str (:room-tree ctx))) "H is no full tree hash")
      (s/check! (= {:cut "spec-3" :room-tree (:room-tree ctx) :outcome :passed :checkout build} r)
                (str "ratified.edn holds " (pr-str r)))
      nil)))

(defstep #"dhf/cycles/03/build is the ratified checkout, which the grader grades"
  (fn [ctx]
    (let [b (fs/file (:dir ctx) build)]
      (s/check! (fs/directory? b) "no dhf/cycles/03/build")
      (s/check! (= (rf/tree-hash b (impl-paths ctx)) (:room-tree ctx))
                "the checkout's implementation is not the tree ratified")
      nil)))

(defstep #"\"rdm-spec ratify ROOM 03\" is run and the test command exits 1"
  (fn [ctx]
    (kc/run-line (project! ctx {:exit 1 :results (map #(vector % "passed") rf/stamped-not-planned)}) "ratify ROOM 03")))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [r (ratified ctx)]
      (s/check! (= :refused (:outcome r)) (str "ratified.edn holds " (pr-str r)))
      (s/check! (and (string? (:reason r)) (re-find #"test command" (:reason r)))
                (str "ratified.edn gives no reason: " (pr-str r)))
      nil)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)]
      (s/check! (and file line) "no unstamped scenario was set up")
      (exits-naming! ctx (re-pattern (str "^" (java.util.regex.Pattern/quote (str file ":" line ": ")))))
      nil)))
