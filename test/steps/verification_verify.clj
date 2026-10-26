(ns steps.verification-verify
  "Step definitions for dhf/spec/verification/verify.feature: `rdm-spec
  verify ROOM 03` in a cut project of the scenario's own.

  The cut project (`cut!`): a copy of the room's kept paths whose spec holds
  one feature file, dhf/spec/architecture/lint.feature (`lint-text`: under
  @UN-001 and the rule \"Every view is described\", @spec-0003 and @spec-0012
  at the level @e2e and @spec-0013 tagged @wip), its :verify the tests check
  [\"bb\" \"test.clj\"], committed and tagged spec-3, with an uncommitted
  dhf/cycles/03/cycle.edn naming the cut.

  The room (`room!`): src/rdm_spec/demo.clj and a test.clj that writes the
  RESULTS_DIR it was given into verify-env.txt (in its working directory,
  the checkout), prints a line, copies the results it holds under
  test/results/ into RESULTS_DIR and exits as asked. Its results follow
  rdm-spec's default Allure mapping (`result-of`) unless a step breaks one.

  Context keys set here: :dir (the cut project), :room, :scenarios ({id
  result} the room's results), :tree (the room's tree hash), :held (a socket
  holding a port), :pid-file (where an app writes its pid), :check (the check
  a Then is about). The kernel's Givens set :dir, :room, :cycle and
  :unstamped."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; The cut project

(def ^:private lint-file "dhf/spec/architecture/lint.feature")

(def ^:private scenario-steps ["Given a model" "When it is linted" "Then it names the view"])

(def lint-text
  (str "# A fixture feature file of verify's steps.\n\n@UN-001\nFeature: Lint\n  A fixture.\n\n"
       "  Rule: Every view is described\n\n"
       "    @spec-0003 @e2e\n    Scenario: Lint names the view\n"
       (str/join (map #(str "      " % "\n") scenario-steps))
       "\n    @spec-0012 @e2e\n    Scenario: A view with no description fails the lint\n"
       (str/join (map #(str "      " % "\n") scenario-steps))
       "\n    @spec-0013 @e2e @wip\n    Scenario: Planned\n      Given a model\n"))

(def ^:private stamped
  {"spec-0003" "Lint names the view"
   "spec-0012" "A view with no description fails the lint"})

(def ^:private git-env
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn- git! [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in "" :extra-env git-env}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(def tests-check {:kind :tests :command ["bb" "test.clj"]})

(def ^:private cycle-03
  {:cut "spec-3" :scope :project
   :builder {:name "builder-a" :actor :agent} :grader {:name "grader-b" :actor :agent}
   :spec-owner {:name "Sami" :actor :human}})

(defn- config-of [dir] (kc/config-of dir))

(defn cut!
  "A cut project: `config-fn` changes its configuration, `files` ({rel
  text}) are committed beside the spec; cycle.edn unless `no-cycle?`."
  [{:keys [config-fn files no-cycle?] :or {config-fn identity}}]
  (let [dir (s/copy-of-room!)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-file lint-text)
    (doseq [[rel text] files] (s/write! dir rel text))
    (s/write! dir "dhf/harness/config.edn"
              (str (pr-str (config-fn (assoc (config-of dir) :verify [tests-check]))) "\n"))
    (git! dir "init" "-q")
    (git! dir "config" "commit.gpgsign" "false")
    (s/hooks-off! dir)
    (git! dir "add" "-A" "-f" "--" ".")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "spec-3")
    (when-not no-cycle?
      (s/write! dir "dhf/cycles/03/cycle.edn" (str (pr-str cycle-03) "\n")))
    dir))

;; ---------------------------------------------------------------------------
;; The room and its results

(defn result-of
  "A result for the stamped scenario `id` as rdm-spec's default mapping
  fills it, with `status`."
  [id status]
  {:uuid (str (random-uuid)) :historyId id :name (get stamped id)
   :fullName (str lint-file ":" (get stamped id)) :status status :stage "finished"
   :start 1700000000000 :stop 1700000000100
   :labels [{:name "feature" :value "Lint"} {:name "story" :value "Every view is described"}
            {:name "epic" :value "architecture"} {:name "suite" :value "architecture"}
            {:name "subSuite" :value "architecture/lint"}
            {:name "tag" :value "UN-001"} {:name "tag" :value id} {:name "tag" :value "e2e"}
            {:name "framework" :value "gherkin"} {:name "spec" :value id} {:name "context" :value "architecture"}
            {:name "component" :value "Lint"}]
   :links [{:type "issue" :name "UN-001" :url "dhf/spec/needs.edn#UN-001"}]
   :parameters [] :attachments []
   :steps (vec (for [n scenario-steps] {:name n :status status :start 1700000000000 :stop 1700000000100}))})

(defn- test-clj [exit]
  (str "(require '[babashka.fs :as fs])\n"
       "(let [d (System/getenv \"RESULTS_DIR\")]\n"
       "  (spit \"verify-env.txt\" (str d))\n"
       "  (println \"the room's tests ran\")\n"
       "  (when (and d (fs/directory? \"test/results\"))\n"
       "    (fs/create-dirs d)\n"
       "    (doseq [f (fs/glob \"test/results\" \"*.json\")]\n"
       "      (fs/copy f (fs/file d (fs/file-name f)) {:replace-existing true}))))\n"
       "(System/exit " exit ")\n"))

(defn write-room!
  "Writes the room's test.clj (exiting `exit`) and its results `scenarios`
  ({id result}) into `room`."
  [room exit scenarios]
  (s/write! room "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
  (s/write! room "test.clj" (test-clj exit))
  (fs/delete-tree (fs/file room "test/results"))
  (doseq [[id r] scenarios]
    (s/write! room (str "test/results/" id "-result.json") (json/generate-string r {:pretty true})))
  room)

(defn- all-passing [] {"spec-0003" (result-of "spec-0003" "passed") "spec-0012" (result-of "spec-0012" "passed")})

(defn- project!
  "ctx with a cut project and a room, unless an earlier Given made them."
  ([ctx] (project! ctx {}))
  ([ctx opts]
   (if (and (:dir ctx) (:room ctx))
     ctx
     (let [scs (or (:scenarios ctx) (all-passing))]
       (assoc ctx :dir (cut! opts) :room (write-room! (s/tmp-dir) (:exit opts 0) scs) :scenarios scs)))))

(defn- rewrite-room!
  "ctx after the room's results are `f` of what they were."
  [ctx f]
  (let [scs (f (:scenarios ctx))]
    (write-room! (:room ctx) (or (:exit ctx) 0) scs)
    (assoc ctx :scenarios scs)))

(defn- with-config!
  "Rewrites the cut's configuration with `f` and re-tags spec-3."
  [ctx f]
  (let [dir (:dir ctx)]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str (f (config-of dir))) "\n"))
    (git! dir "add" "-A" "-f" "--" "dhf/harness")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3, again")
    (git! dir "tag" "-f" "spec-3")
    ctx))

(defn- cycle-file [ctx f] (fs/file (:dir ctx) "dhf/cycles/03" f))

(defn- verify-out [ctx] (:verify (kc/stdout-edn (kc/the-run ctx))))

(defn- check-named [ctx nm]
  (let [out (verify-out ctx)]
    (or (first (filter #(= nm (:name %)) (:checks out)))
        (s/fail! (str "standard output names no check " nm "\n" (s/show (kc/the-run ctx)))))))

(defn- line-naming! [ctx & parts]
  (let [r (kc/the-run ctx)]
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) (kc/finding-lines r))
              (str "no finding names all of " (pr-str parts) "\n" (s/show r)))))

(defn- ran-room-tests? [ctx] (fs/exists? (cycle-file ctx "build/verify-env.txt")))

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defn- tree-files
  "{path text} under `dir`, without .git."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [rel (str (fs/relativize dir f))]
              :when (and (fs/regular-file? f) (not (str/starts-with? rel ".git/")))]
          [rel (slurp (str f))])))

(defn- impl-path? [cfg rel]
  (some #(or (= rel %) (str/starts-with? rel (str % "/"))) (:implementation cfg)))

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          build (cycle-file ctx "build")
          dir (:dir ctx)
          cfg (config-of dir)]
      (s/check! (#{0 1} (:exit r)) (str "verify did not run\n" (s/show r)))
      (s/check! (fs/directory? build) "verify wrote no dhf/cycles/03/build")
      (s/check! (not (fs/exists? (fs/file build ".git"))) "the checkout has a history")
      (let [at-cut (set (remove str/blank? (str/split-lines (git! dir "ls-tree" "-r" "--name-only" "spec-3"))))
            want (set (remove #(impl-path? cfg %) at-cut))
            have (set (remove #(or (impl-path? cfg %) (= "verify-env.txt" %)) (keys (tree-files build))))]
        (s/check! (= want have) (str "the checkout holds " (count have) " files outside the implementation, the cut "
                                     (count want) "; missing " (take 5 (remove have want)) ", extra " (take 5 (remove want have))))
        (s/check! (= (:verify (kc/stdout-edn r)) (:verify (kc/stdout-edn r))) "no result"))
      (s/check! (= "spec-3" (:cut (verify-out ctx))) (str "it verified " (pr-str (:cut (verify-out ctx)))))
      nil)))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (assoc ctx :dir (cut! {:no-cycle? true}) :room (write-room! (s/tmp-dir) 0 (all-passing)))))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (kc/refused-naming! ctx "dhf/cycles/03/cycle.edn") nil))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (project! ctx {:files {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"}})]
      (s/check! (not (fs/exists? (fs/file (:room ctx) "src/rdm_spec/old.clj"))) "the room holds old.clj")
      ctx)))

(defstep #"the checkout it verifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (fs/directory? (cycle-file ctx "build")) "there is no checkout")
    (s/check! (not (fs/exists? (cycle-file ctx "build/src/rdm_spec/old.clj"))) "the checkout holds src/rdm_spec/old.clj")
    nil))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [cfg (config-of (:dir ctx))
          build (str (cycle-file ctx "build"))
          of (fn [d] (into {} (filter #(impl-path? cfg (key %))) (tree-files d)))]
      (s/check! (= (of (:room ctx)) (of build)) (str "the checkout's implementation is "
                                                     (keys (of build)) ", the room's " (keys (of (:room ctx)))))
      nil)))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (project! ctx)
          room (:room ctx)]
      (s/write! room "dhf/harness/config.edn"
                (pr-str (assoc (config-of (:dir ctx)) :verify [{:kind :tests :command ["bb" "-e" "(System/exit 0)"]}])))
      (s/write! room lint-file (str/replace lint-text "Lint names the view" "Edited by the builder"))
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [dir (:dir ctx)
          at-cut (fn [rel] (git! dir "show" (str "spec-3:" rel)))
          build (fn [rel] (slurp (str (cycle-file ctx (str "build/" rel)))))]
      (s/check! (= (str (at-cut "dhf/harness/config.edn") "\n") (build "dhf/harness/config.edn"))
                "the checkout's configuration is not the cut's")
      (s/check! (= (str/trim (at-cut lint-file)) (str/trim (build lint-file))) "the checkout's feature file is not the cut's")
      (s/check! (ran-room-tests? ctx) "the cut's tests check did not run the room's test.clj")
      nil)))

;; ---------------------------------------------------------------------------
;; The tests check passes only when the tests pass and every scenario passed

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx] (kc/run-line (project! ctx) "verify ROOM 03")))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [got (slurp (str (cycle-file ctx "build/verify-env.txt")))
          want (str (fs/canonicalize (cycle-file ctx "results")))]
      (s/check! (fs/absolute? got) (str "RESULTS_DIR was " (pr-str got) ", not absolute"))
      (s/check! (= want (str (fs/canonicalize got))) (str "RESULTS_DIR was " got ", not " want))
      nil)))

(defstep #"the :tests check's command exits (\d)"
  (fn [ctx code]
    (let [n (parse-long code)]
      (project! (assoc ctx :exit n) {:exit n}))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx]
    (s/check! (seq (:scenarios ctx)) "the room writes no result")
    ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx]
    (s/check! (= (set (keys stamped)) (set (keys (:scenarios ctx)))) "the room does not name every stamped scenario")
    (s/check! (every? #(= "passed" (:status %)) (vals (:scenarios ctx))) "a result did not pass")
    ctx))

(defstep #"the tests check passes"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [c (check-named ctx "tests")]
      (s/check! (= :passed (:outcome c)) (str "the tests check is " (pr-str c)))
      nil)))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx "test command failed" "exit status 1")
    nil))

(defstep #"the test command's output is in dhf/cycles/03/test\.log, which the refusal names"
  (fn [ctx]
    (let [log (cycle-file ctx "test.log")]
      (s/check! (fs/exists? log) "there is no dhf/cycles/03/test.log")
      (s/check! (str/includes? (slurp (str log)) "the room's tests ran") "test.log does not hold the command's output")
      (line-naming! ctx "dhf/cycles/03/test.log")
      nil)))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (project! (assoc ctx :scenarios {}))))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx "a green exit with no results is not a run")
    nil))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (let [ctx (project! ctx)]
      (s/check! (re-find #"@spec-0012 @e2e\n" (slurp (str (fs/file (:dir ctx) lint-file)))) "spec-0012 is tagged @wip")
      ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (rewrite-room! ctx #(dissoc % "spec-0012"))))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx "spec-0012")
    nil))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (rewrite-room! ctx #(assoc % "spec-0012" (result-of "spec-0012" "failed")))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx]
    (kc/exits! ctx 1)
    (line-naming! ctx "spec-0012" "failed")
    nil))

;; ---------------------------------------------------------------------------
;; Verify records what it verified

(defn tree-hash
  "The git tree of the implementation paths `paths` of `dir`, computed in a
  repository of its own."
  [dir paths]
  (let [g (s/tmp-dir)]
    (git! g "init" "-q")
    (doseq [pth paths :let [src (fs/file dir pth)] :when (fs/exists? src)]
      (if (fs/directory? src)
        (fs/copy-tree src (fs/file g pth))
        (do (fs/create-dirs (fs/parent (fs/file g pth))) (fs/copy src (fs/file g pth)))))
    (git! g "add" "-A" "-f" "--" ".")
    (git! g "write-tree")))

(def answers-check
  {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:help {:tasks []}}\")"]
   :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (project! ctx {:config-fn #(assoc % :verify [tests-check answers-check])})]
      (assoc ctx :tree (tree-hash (:room ctx) (:implementation (config-of (:dir ctx))))))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx]
    (let [ctx (kc/run-line ctx "verify ROOM 03")]
      (kc/exits! ctx 0)
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [rat (edn/read-string (slurp (str (cycle-file ctx "ratified.edn"))))
          want {:cut "spec-3" :room-tree (:tree ctx) :outcome :passed :checkout "dhf/cycles/03/build"
                :checks (:checks (verify-out ctx))}]
      (s/check! (= 40 (count (:tree ctx))) "the tree hash is not in full")
      (s/check! (= want rat) (str "ratified.edn is\n" (pr-str rat) "\nnot\n" (pr-str want)))
      (s/check! (= ["tests" "answers"] (mapv :name (:checks rat))) "ratified.edn does not hold both checks")
      nil)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (s/check! (fs/regular-file? (cycle-file ctx "build/src/rdm_spec/demo.clj")) "the checkout holds no room code")
    (s/check! (fs/regular-file? (cycle-file ctx (str "build/" lint-file))) "the checkout holds no spec")
    nil))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx]
    (let [ctx (project! (assoc ctx :exit 1) {:exit 1})]
      (kc/run-line ctx "verify ROOM 03"))))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [rat (edn/read-string (slurp (str (cycle-file ctx "ratified.edn"))))]
      (s/check! (= :refused (:outcome rat)) (str "ratified.edn is " (pr-str rat)))
      (s/check! (and (string? (:reason rat)) (str/includes? (:reason rat) "exit status 1"))
                (str "ratified.edn gives no reason: " (pr-str rat)))
      nil)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [{:keys [file line]} (:unstamped ctx)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (kc/finding-lines (kc/the-run ctx)))
                (str "no finding names " file ":" line "\n" (s/show (kc/the-run ctx))))
      nil)))

;; ---------------------------------------------------------------------------
;; A project declares its checks, and verify runs every one the same way

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (project! ctx {:config-fn #(assoc % :verify [tests-check answers-check])})))

(defstep #"both pass"
  (fn [ctx] ctx))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          want '{:verify {:cycle "03" :cut "spec-3" :outcome :passed
                          :checks [{:name "tests" :kind :tests :outcome :passed :seconds S1 :scenarios N}
                                   {:name "answers" :kind :runs :outcome :passed :seconds S2 :budget B :margin M}]}}
          m (kc/match-edn want (kc/stdout-edn r))
          {:strs [S1 S2 N B M]} (:bound m)]
      (s/check! (not (:at m)) (str "standard output differs at " (pr-str (:at m)) "\n" (s/show r)))
      (s/check! (and (number? S1) (number? S2) (<= 0 S1) (<= 0 S2)) "the seconds are not numbers of seconds")
      (s/check! (= 2 N) (str "the tests check names " N " scenarios, not 2"))
      (s/check! (= 30 B) (str "the budget is " B))
      (s/check! (and (number? M) (< (Math/abs (double (- M (- B S2)))) 0.01)) (str "the margin is " M ", not " (- B S2)))
      nil)))

(defn- serves-check [port & {:keys [app probe seconds polls]}]
  {:kind :serves :name "app" :start ["bb" "dhf/harness/app.clj" "{port}" (or app "answer")]
   :port port :budget {:seconds (or seconds 10) :polls (or polls 20)}
   :probe ["bb" "dhf/harness/probe.clj" "{url}" (or probe "pass")]})

(def ^:private app-clj
  "The fixture app: writes its pid beside it, then answers on its port
  (answer), never (never), or after a while (late)."
  (str "(let [[port how] *command-line-args*]\n"
       "  (spit (str \"app-\" port \".pid\") (str (.pid (java.lang.ProcessHandle/current))))\n"
       "  (case how\n"
       "    \"never\" (Thread/sleep 60000)\n"
       "    (let [ss (java.net.ServerSocket. (parse-long port))]\n"
       "      (loop [] (with-open [s (.accept ss)] (.write (.getOutputStream s) (.getBytes \"HTTP/1.0 200 OK\\r\\n\\r\\nok\"))) (recur)))))\n"))

(def ^:private probe-clj
  (str "(let [[url how] *command-line-args*]\n"
       "  (if (= how \"pass\") (System/exit 0)\n"
       "    (do (println \"the probe found another app at\" url) (System/exit 1))))\n"))

(defn- free-port []
  (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss)))

(defn- serving-files [] {"dhf/harness/app.clj" app-clj "dhf/harness/probe.clj" probe-clj})

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx]
    (project! (assoc ctx :exit 1)
              {:exit 1 :files (serving-files)
               :config-fn #(assoc % :verify [tests-check (serves-check (free-port)) answers-check])})))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [cs (:checks (verify-out ctx))]
      (s/check! (= [[:tests :failed] [:serves :not-run] [:runs :not-run]] (mapv (juxt :kind :outcome) cs))
                (str "the checks are " (pr-str cs)))
      nil)))

(def ^:private config-problems
  {"declares no :tests check" #(assoc % :verify [answers-check])
   "declares a check of the kind :renders" #(assoc % :verify [tests-check {:kind :renders :name "page"}])
   "declares a :serves check whose probe is under src/"
   #(assoc % :verify [tests-check (assoc (serves-check 8080) :probe ["bb" "src/rdm_spec/probe.clj" "{url}"])])
   "is absent" #(dissoc % :verify)
   "declares a :tests check with no command" #(assoc % :verify [{:kind :tests}])})

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx is] (project! ctx {:config-fn (get config-problems is)})))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (kc/refused-naming! ctx why)
    (s/check! (not (ran-room-tests? ctx)) "a check ran")
    (s/check! (not (fs/exists? (cycle-file ctx "ratified.edn"))) "verify wrote ratified.edn")
    nil))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx]
    (project! ctx {:config-fn #(assoc % :verify [tests-check (assoc answers-check :command ["rdm-spec-no-such-command" "help"])])})))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (kc/refused-naming! ctx "answers" "rdm-spec-no-such-command") nil))

;; ---------------------------------------------------------------------------
;; A :serves check proves the app comes up and is this application

(defn- serves-project! [ctx check-opts]
  (let [port (free-port)
        ctx (project! ctx {:files (serving-files)
                           :config-fn #(assoc % :verify [tests-check (apply serves-check port (mapcat identity check-opts))])})]
    (assoc ctx :port port :pid-file (str (cycle-file ctx (str "build/app-" port ".pid"))))))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx] (serves-project! ctx {:app "answer"})))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [c (check-named ctx "app")]
      (s/check! (= [:passed 2 20 18] [(:outcome c) (:answered-on-poll c) (:polls c) (:margin c)])
                (str "the check is " (pr-str c)))
      nil)))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (let [f (:pid-file ctx)]
      (s/check! (fs/exists? f) "the app never started")
      (let [pid (parse-long (str/trim (slurp f)))
            alive? (fn [] (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) (.isAlive)))]
        (when (alive?)
          (Thread/sleep 500))
        (when (alive?)
          (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) (.destroyForcibly))
          (s/fail! (str "the app " pid " is still running")))
        nil))))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx] (serves-project! ctx {:app "never"})))

(defstep #"it exits 1, the check is :failed, and it says no answer came within 10 seconds"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "app"))) "the check did not fail")
    (line-naming! ctx "no answer came within 10 seconds")
    nil))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [ctx (serves-project! ctx {:app "answer"})
          ss (java.net.ServerSocket. (int (:port ctx)))]
      (assoc ctx :held ss))))

(defstep #"it exits 2, names the port, and starts nothing"
  (fn [ctx]
    (try
      (kc/refused-naming! ctx (str (:port ctx)))
      (s/check! (not (fs/exists? (:pid-file ctx))) "the app was started")
      nil
      (finally (.close ^java.net.ServerSocket (:held ctx))))))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx] (serves-project! ctx {:app "answer" :probe "fail"})))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "app"))) "the check did not fail")
    (line-naming! ctx "the probe found another app at")
    nil))

;; ---------------------------------------------------------------------------
;; A :runs check proves a command answers as the project expects

(def ^:private expectations
  {"the exit 0" {:exit 0}
   "standard output starting \"{:help\"" {:stdout-starts "{:help"}
   "an answer within 10 seconds" {:budget 10}})

(def ^:private doings
  {"exits 3" ["bb" "-e" "(System/exit 3)"]
   "prints \"usage\" and exits 0" ["bb" "-e" "(println \"usage\") (println \"more\")"]
   "is still running after 10 seconds" ["bb" "-e" "(Thread/sleep 30000)"]})

(defstep #"a :runs check expecting (the exit 0|standard output starting \"\{:help\"|an answer within 10 seconds)"
  (fn [ctx e] (assoc ctx :expectation (get expectations e))))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (let [{:keys [exit stdout-starts budget]} (:expectation ctx)
          check {:kind :runs :name "answers" :command (get doings does)
                 :expect (cond-> {} exit (assoc :exit exit) stdout-starts (assoc :stdout-starts stdout-starts))
                 :budget {:seconds (or budget 30)}}]
      (project! ctx {:config-fn #(assoc % :verify [tests-check check])}))))

(def ^:private differences
  {"the exit 3 where 0 was expected" ["the exit 3 where 0 was expected"]
   "the first line it printed" ["usage"]
   "the budget of 10 seconds" ["the budget of 10 seconds"]})

(defstep #"it exits 1, the check is :failed, and it names (the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx d]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "answers"))) "the check did not fail")
    (apply line-naming! ctx "answers" (get differences d))
    nil))

;; ---------------------------------------------------------------------------
;; The tests check holds every result to the project's Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (assoc ctx :allure default-mapping)))

(defn- relabel [r n v] (update r :labels (fn [ls] (mapv #(if (= n (:name %)) (assoc % :value v) %) ls))))

(def ^:private wrongs
  {"a second step reworded from the scenario's line" #(assoc-in % [:steps 1 :name] "When it is linted again")
   "one step fewer than the scenario's lines" #(update % :steps pop)
   "the story \"Something else\", not the scenario's rule" #(relabel % "story" "Something else")
   "the epic of another bounded context" #(relabel % "epic" "verification")
   "no issue link to UN-001, the need the scenario traces to" #(assoc % :links [])
   "no browser-trace attachment, where :evidence requires one for @e2e" identity})

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [f (or (get wrongs wrong) (s/fail! (str "no fixture for " wrong)))
          m (cond-> (:allure ctx) (str/starts-with? wrong "no browser-trace") (assoc :evidence {:e2e ["browser-trace"]}))
          scs (update (all-passing) "spec-0003" f)]
      (project! (assoc ctx :scenarios scs) {:config-fn #(assoc % :allure m)}))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003 and (.+)"
  (fn [ctx finding]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "tests"))) "the tests check did not fail")
    (line-naming! ctx "spec-0003" finding)
    nil))

(def ^:private mapping-problems
  {"maps :epic to :aggregate" #(assoc % :epic :aggregate)
   "maps :colour to :context" #(assoc % :colour :context)
   "maps no label \"spec\"" #(update % :labels dissoc "spec")})

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [ctx is]
    (project! ctx {:config-fn #(assoc % :allure ((get mapping-problems is) default-mapping))})))

(defstep #"a configuration that declares no :allure"
  (fn [ctx]
    (let [wrong (-> (result-of "spec-0003" "passed")
                    (assoc :name "Another title" :links [])
                    (update :steps pop)
                    (relabel "epic" "verification") (relabel "feature" "Other") (relabel "story" "Other")
                    (relabel "component" "Other") (relabel "tag" "other"))
          ctx (project! (assoc ctx :scenarios (assoc (all-passing) "spec-0003" wrong))
                        {:config-fn #(dissoc % :allure)})]
      (s/check! (not (contains? (config-of (:dir ctx)) :allure)) "the configuration declares :allure")
      ctx)))

(defstep #"each result is held to \{:epic :context .*\}"
  (fn [ctx]
    (kc/exits! ctx 1)
    (doseq [parts [["steps-differ-from-scenario"] ["mapping-differs: epic"] ["mapping-differs: feature"]
                   ["mapping-differs: story"] ["mapping-differs: name"] ["mapping-differs: component"]
                   ["mapping-differs: tag"] ["mapping-differs: links"]]]
      (apply line-naming! ctx "spec-0003" parts))
    (s/check! (not-any? #(str/includes? % "spec-0012") (kc/finding-lines (kc/the-run ctx)))
              "a result that follows the mapping was refused")
    nil))
