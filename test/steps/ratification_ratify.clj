(ns steps.ratification-ratify
  "Step definitions for dhf/spec/ratification/ratify.feature.

  A ratification fixture is a throwaway git repository (the project ratify
  runs in) whose tag spec-3 holds the room's configuration, the C4 model and
  one small feature file, with cycle 03's cycle.edn written after the cut, and
  a room (ROOM) holding a small implementation. The cut's declared test command
  is a fake whose behaviour the Givens plan in :plan:
    :exit     its exit status (0)
    :results  {id status} it writes, one Allure result each (every stamped
              scenario not tagged @wip, passed)
    :env-file a file it writes RESULTS_DIR into, when given
  Each Given changes the plan and rebuilds the fixture (`fixture!`), so Givens
  compose. Two Givens are shared with the measurement steps
  (steps.measurement-cycle): they cut a copy of the room's allow-list, whose
  test command is the real one."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.measurement-cycle :as mc]
            [steps.support :as s]))

(defn- needs-ratify! [] (s/needs-built! 'rdm-spec.ratification.ratify))

;; ---------------------------------------------------------------------------
;; Fixtures

(def scenarios
  "The cut's scenarios: two to run and one planned."
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def default-plan
  {:exit 0 :results {"spec-0012" "passed" "spec-0014" "passed"}})

(def cut-implementation
  "Implementation files at the cut, which the room's must replace."
  {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"
   "src/rdm_spec/demo.clj" "(ns rdm-spec.demo) ;; the cut's\n"
   "test.clj" ";; the cut's tests\n"})

(def room-implementation
  {"src/rdm_spec/demo.clj" "(ns rdm-spec.demo) ;; the room's\n"
   "src/rdm_spec/kernel/x.clj" "(ns rdm-spec.kernel.x)\n"
   "test/rdm_spec/demo_test.clj" "(ns rdm-spec.demo-test)\n"
   "test.clj" ";; the room's tests\n"})

(defn fake-tests
  "A test command doing what `plan` says."
  [{:keys [exit results env-file marker]}]
  [s/bb "-e"
   (str "(require '[babashka.fs :as fs] '[cheshire.core :as json])"
        "(let [d (System/getenv \"RESULTS_DIR\")]"
        (when env-file (str " (spit " (pr-str env-file) " (str d))"))
        (when marker (str " (spit " (pr-str marker) " \"the cut's test command ran\")"))
        " (fs/create-dirs d)"
        " (doseq [[id st] " (pr-str (vec results)) "]"
        "  (spit (str d \"/\" (random-uuid) \"-result.json\")"
        "        (json/generate-string (cond-> {:uuid (str (random-uuid)) :name (str \"a test of \" id) :status st"
        "                                       :stage \"finished\" :labels [{:name \"spec\" :value id}] :steps []}"
        "                                (not= \"passed\" st) (assoc :statusDetails {:message \"it did not pass\"})))))"
        " (System/exit " (long exit) "))")])

(defn- fixture!
  "The context with its project rebuilt from its :plan (and :cut-files, extra
  files at the cut), and a room holding the room's implementation unless it
  has one."
  [ctx]
  (needs-ratify!)
  (let [plan (merge default-plan (:plan ctx))
        dir (or (:dir ctx) (s/copy-of-room!))
        room (or (:room ctx) (let [r (s/tmp-dir)]
                               (doseq [[f t] room-implementation] (s/write! r f t))
                               r))]
    (when-not (:built? ctx)
      (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
      (doseq [[f t] cut-implementation] (s/write! dir f t)))
    (s/write! dir mc/lint-feature (mc/feature-text scenarios))
    (doseq [[f t] (:cut-files ctx)] (s/write! dir f t))
    (s/write! dir "dhf/harness/config.edn" (pr-str (assoc (s/room-config) :test-command (fake-tests plan))))
    (when (fs/exists? (fs/file dir "dhf/cycles")) (fs/delete-tree (fs/file dir "dhf/cycles")))
    (mc/cut! dir "spec-3")
    (mc/write-cycle! dir "03" {})
    (assoc ctx :dir dir :room room :plan plan :built? true :cycle "03")))

(defn- plan [ctx f & args]
  (fixture! (assoc ctx :plan (apply f (merge default-plan (:plan ctx)) args))))

(defn- run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- exits! [ctx code]
  (s/check! (= code (:exit (run ctx))) (str "expected exit " code "\n" (s/show (run ctx)))))

(defn- says! [ctx re what]
  (s/check! (re-find re (:err (run ctx))) (str "the error stream does not say " what "\n" (s/show (run ctx)))))

(defn- git-out [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- build [ctx] (fs/file (:dir ctx) (mc/cycle-path "03" "build")))

(defn- ratified [ctx]
  (let [f (fs/file (:dir ctx) (mc/cycle-path "03" "ratified.edn"))]
    (s/check! (fs/exists? f) (str "no " (mc/cycle-path "03" "ratified.edn") "\n" (s/show (run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- files-under
  "{relative path content} of every file under `rel` in `dir` (a file or a
  directory), outside .git."
  [dir rel]
  (let [f (fs/file dir rel)]
    (cond
      (fs/regular-file? f) {rel (slurp (str f))}
      (fs/directory? f) (into {} (for [x (fs/glob f "**" {:hidden true})
                                       :when (fs/regular-file? x)]
                                   [(str rel "/" (str/replace (str (fs/relativize f x)) "\\" "/")) (slurp (str x))]))
      :else {})))

(defn- holds-the-room!
  "Fails unless every configured implementation path of the build holds
  exactly what the room holds."
  [ctx]
  (doseq [p (:implementation (s/room-config))]
    (let [want (files-under (:room ctx) p)
          got (files-under (build ctx) p)]
      (s/check! (= want got) (str p " in the checkout holds " (pr-str (keys got)) ", the room " (pr-str (keys want))
                                  (when (= (set (keys want)) (set (keys got))) ", with other content"))))))

(defn- is-the-cut!
  "Fails unless the build is a checkout whose HEAD is the commit tagged spec-3."
  [ctx]
  (let [b (build ctx)]
    (s/check! (fs/directory? (fs/file b ".git")) (str "dhf/cycles/03/build is not a checkout\n" (s/show (run ctx))))
    (s/check! (= (git-out (:dir ctx) "rev-parse" "spec-3^{commit}") (git-out b "rev-parse" "HEAD"))
              "the checkout's HEAD is not the commit tagged spec-3")))

;; ---------------------------------------------------------------------------
;; Ratify trusts only the cut

(defstep #"it ratifies against a fresh checkout of spec-3"
  (fn [ctx]
    (needs-ratify!)
    (s/check! (#{0 1} (:exit (run ctx))) (str "ratify did not run\n" (s/show (run ctx))))
    (s/check! (= "spec-3" (:cut (ratified ctx))) (str "ratified.edn: " (pr-str (ratified ctx))))
    (is-the-cut! ctx)
    ;; fresh: nothing of the working tree after the cut, such as cycle.edn, is in it
    (s/check! (not (fs/exists? (fs/file (build ctx) (mc/cycle-path "03" "cycle.edn"))))
              "the checkout holds the working tree's cycle.edn")
    (s/check! (str/blank? (git-out (build ctx) "status" "--porcelain" "--untracked-files=no" "--" "dhf"))
              "the checkout's spec differs from the cut")))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (needs-ratify!)
    ;; a repository with no cut, and a room that does not exist: the missing
    ;; cycle.edn must be what ratify names, being read before anything else
    (let [dir (s/copy-of-room!)]
      (git-out dir "init" "-q")
      (assoc ctx :dir dir :room (str (fs/file (s/tmp-dir) "no-room"))))))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx]
    (exits! ctx 2)
    (says! ctx #"dhf/cycles/03/cycle\.edn" "dhf/cycles/03/cycle.edn")
    (s/check! (str/blank? (:out (run ctx))) (str "a refusal printed a result\n" (s/show (run ctx))))))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (fixture! ctx)]
      (s/check! (seq (git-out (:dir ctx) "ls-tree" "--name-only" "spec-3" "src/rdm_spec/old.clj"))
                "the cut holds no src/rdm_spec/old.clj")
      (s/check! (not (fs/exists? (fs/file (:room ctx) "src/rdm_spec/old.clj"))) "the room holds src/rdm_spec/old.clj")
      ctx)))

(defstep #"the checkout it ratifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (exits! ctx 0)
    (is-the-cut! ctx)
    (s/check! (not (fs/exists? (fs/file (build ctx) "src/rdm_spec/old.clj")))
              "the checkout still holds the cut's src/rdm_spec/old.clj")))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx] (holds-the-room! ctx)))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [marker (str (fs/file (s/tmp-dir) "cut-ran"))
          room-marker (str (fs/file (s/tmp-dir) "room-ran"))
          ctx (fixture! (assoc ctx :plan (assoc default-plan :marker marker)))
          room (:room ctx)]
      ;; the room's configuration runs another command and its feature adds a scenario
      (s/write! room "dhf/harness/config.edn"
                (pr-str (assoc (s/room-config) :test-command [s/bb "-e" (str "(spit " (pr-str room-marker) " \"ran\")")]
                               :implementation ["src"])))
      (s/write! room mc/lint-feature (str (mc/feature-text scenarios)
                                          "\n    @spec-0099 @e2e\n    Scenario: Added by the builder\n"
                                          "      Given a model\n      When \"rdm-spec check\" is run\n      Then it passes\n"))
      (assoc ctx :markers {:cut marker :room room-marker}))))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (exits! ctx 0)
    (is-the-cut! ctx)
    (doseq [f ["dhf/harness/config.edn" mc/lint-feature]]
      (s/check! (= (git-out (:dir ctx) "show" (str "spec-3:" f)) (str/trim (slurp (str (fs/file (build ctx) f)))))
                (str "the checkout's " f " is not the cut's")))
    (s/check! (fs/exists? (get-in ctx [:markers :cut])) "the cut's test command did not run")
    (s/check! (not (fs/exists? (get-in ctx [:markers :room]))) "the room's test command ran")
    ;; the room's configuration named only src: the cut's implementation paths were used
    (holds-the-room! ctx)))

;; ---------------------------------------------------------------------------
;; Ratify passes only when the tests pass and every scenario passed

(defstep #"\"rdm-spec ratify ROOM 03\" runs the declared test command"
  (fn [ctx]
    (let [env-file (str (fs/file (s/tmp-dir) "results-dir.txt"))
          ctx (plan ctx assoc :env-file env-file)]
      (assoc (kc/run-line ctx "ratify ROOM 03") :env-file env-file))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec ratify was run in"
  (fn [ctx]
    (exits! ctx 0)
    (let [f (:env-file ctx)]
      (s/check! (fs/exists? f) "the test command did not run")
      (let [given (slurp f)
            want (fs/file (:dir ctx) "dhf/cycles/03/results")]
        (s/check! (fs/absolute? given) (str "RESULTS_DIR is not absolute: " given))
        (s/check! (= (str (fs/canonicalize want)) (str (fs/canonicalize given)))
                  (str "RESULTS_DIR was " given ", not " want))))))

(defstep #"the declared test command exits (\d+)"
  (fn [ctx code] (plan ctx assoc :exit (parse-long code))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (plan ctx update :results #(or (not-empty %) (:results default-plan)))))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] (plan ctx assoc :results (:results default-plan))))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"test command .* failed: it exited with status 1\b" "that the test command failed with status 1")))

(defstep #"the declared test command exits 0 without writing any Allure result"
  (fn [ctx] (plan ctx assoc :exit 0 :results {})))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"a green exit with no results is not a run" "that a green exit with no results is not a run")))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (let [ctx (fixture! ctx)]
      (s/check! (some #(and (= "spec-0012" (:id %)) (not (:wip? %))) scenarios) "spec-0012 is planned")
      ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (plan ctx update :results dissoc "spec-0012")))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"(?m)^\S+lint\.feature:\d+: \S+: spec-0012 " "spec-0012, at its feature file and line")))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (plan ctx assoc-in [:results "spec-0012"] "failed")))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx]
    (exits! ctx 1)
    (says! ctx #"spec-0012.*failed" "spec-0012 and its status failed")))

;; ---------------------------------------------------------------------------
;; Ratify records what it ratified

(defstep #"the room's implementation has the tree hash ([0-9a-f]{40})"
  (fn [ctx tree]
    (let [room (s/tmp-dir)
          ctx (fixture! (assoc ctx :room room))
          ;; the hash git gives the room's implementation paths, by a separate route
          probe (s/tmp-dir)]
      (git-out probe "init" "-q")
      (doseq [p (:implementation (s/room-config)) :when (fs/exists? (fs/file room p))]
        (if (fs/directory? (fs/file room p))
          (fs/copy-tree (fs/file room p) (fs/file probe p))
          (fs/copy (fs/file room p) (fs/file probe p))))
      (git-out probe "add" "-A" "-f" ".")
      (s/check! (= tree (git-out probe "write-tree")) (str "the room's implementation does not have the tree " tree))
      ctx)))

(defstep #"\"rdm-spec ratify ROOM 03\" is run and passes"
  (fn [ctx]
    (let [ctx (kc/run-line (fixture! ctx) "ratify ROOM 03")]
      (exits! ctx 0)
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds:"
  (fn [ctx doc]
    (let [r (ratified ctx)]
      (s/check! (= (edn/read-string doc) r) (str "ratified.edn holds " (pr-str r) "\nexpected " doc)))))

(defstep #"dhf/cycles/03/build is the ratified checkout, which the grader grades"
  (fn [ctx]
    (is-the-cut! ctx)
    (holds-the-room! ctx)))

(defstep #"\"rdm-spec ratify ROOM 03\" is run and the test command exits 1"
  (fn [ctx]
    (kc/run-line (plan ctx assoc :exit 1) "ratify ROOM 03")))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (exits! ctx 1)
    (let [r (ratified ctx)]
      (s/check! (= {:cut "spec-3" :outcome :refused} (select-keys r [:cut :outcome])) (str "ratified.edn: " (pr-str r)))
      (s/check! (and (string? (:reason r)) (re-find #"test command .* failed: it exited with status 1" (:reason r)))
                (str "ratified.edn gives no reason naming the failed test command: " (pr-str r))))))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (needs-ratify!)
    (exits! ctx 1)
    (let [{:keys [file line]} (:unstamped ctx)]
      (says! ctx (re-pattern (str "(?m)^\\S*" (java.util.regex.Pattern/quote (str file ":" line ":")) " unstamped-scenario"))
             (str file ":" line))
      (s/check! (= :refused (:outcome (ratified ctx))) "ratified.edn does not record the refusal"))))
