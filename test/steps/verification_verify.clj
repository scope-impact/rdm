(ns steps.verification-verify
  "Step definitions for dhf/spec/verification/verify.feature: `rdm-spec
  verify ROOM 03` in a cycle project (the kernel's: a copy of the room
  whose spec holds the lint's feature file, its :verify the tests check,
  cut spec-3, cycle 03 naming it) with a room whose test.clj the scenario
  writes. A room's tests copy the result files a step gives into
  RESULTS_DIR, print what a step says and exit as it says.

  Context keys set here: :dir, :room, :expected-files ({rel text} the
  checkout must hold), :room-tree (H), :port, :pid-file (where an app a
  check starts writes its process id), :started (a file the app writes when
  it starts), :held (a socket holding a port), :env-file (where the tests
  write the RESULTS_DIR they were given), :named (the words a finding must
  hold)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :as runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Fixtures

(def ^:private cycle-rel "dhf/cycles/03")

(defn- room-with!
  "A room whose test.clj writes `results` ({file-name text}) into
  RESULTS_DIR, prints `out`, writes RESULTS_DIR to `env-file` when given,
  and exits `code`."
  [{:keys [results out code env-file]}]
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (doseq [[n text] results] (s/write! r (str "test/fixture-results/" n) text))
    (s/write! r "test.clj"
              (str "(require '[babashka.fs :as fs])\n"
                   "(def results-dir (System/getenv \"RESULTS_DIR\"))\n"
                   (when env-file (str "(spit " (pr-str env-file) " (str results-dir))\n"))
                   "(doseq [f (fs/glob \"test/fixture-results\" \"*\")]\n"
                   "  (fs/copy f (fs/file results-dir (fs/file-name f)) {:replace-existing true}))\n"
                   (when out (str "(println " (pr-str out) ")\n"))
                   "(System/exit " (or code 0) ")\n"))
    r))

(defn- result-json [spec status]
  (json/generate-string {:uuid (str (random-uuid)) :name spec :status status :steps []
                         :labels [{:name "spec" :value spec}]}))

(defn- results-for [statuses]
  (into {} (map-indexed (fn [i [spec st]] [(str "r" i "-result.json") (result-json spec st)]) statuses)))

(defn- project!
  "ctx with :dir, a cycle project at spec-3 whose cycle 03 names the cut,
  its configuration changed by `cfg-f` before the cut."
  ([ctx] (project! ctx identity))
  ([ctx cfg-f] (project! ctx cfg-f kc/default-scenarios))
  ([ctx cfg-f scenarios]
   (let [dir (s/copy-of-room!)]
     (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
     (s/write! dir kc/lint-rel (kc/lint-text scenarios))
     (s/write! dir "dhf/harness/config.edn"
               (str (pr-str (cfg-f (assoc (kc/config-of dir)
                                          :verify [{:kind :tests :command ["bb" "test.clj"]}]
                                          :allure {:labels {"spec" :scenario-id}}))) "\n"))
     (kc/cut! dir "spec-3")
     (kc/write-cycle! dir)
     (assoc ctx :dir dir))))

(defn- recut!
  "Writes `cfg-f` of the project's configuration and cuts spec-3 again."
  [ctx cfg-f]
  (let [dir (:dir ctx)]
    (s/write! dir "dhf/harness/config.edn" (str (pr-str (cfg-f (kc/config-of dir))) "\n"))
    (kc/cut! dir "spec-3")
    ctx))

(defn- ensure-project [ctx] (if (:dir ctx) ctx (project! ctx)))

(defn- passing [ctx] (assoc ctx :room (room-with! {:results (results-for [["spec-0012" "passed"] ["spec-0014" "passed"]])})))

(defn- verify-out [r] (:verify (kc/stdout-edn r)))

(defn- check-named [r nm] (first (filter #(= nm (:name %)) (:checks (verify-out r)))))

(defn- ratified [ctx] (let [f (fs/file (:dir ctx) cycle-rel "ratified.edn")] (when (fs/exists? f) (edn/read-string (slurp (str f))))))

(defn- files-under [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [r (str/replace (str (fs/relativize dir f)) "\\" "/")]
              :when (and (fs/regular-file? f) (not (str/starts-with? r ".git/")))]
          [r (slurp (str f))])))

(defn- files-at [dir rev]
  (into (sorted-map)
        (for [r (remove str/blank? (str/split-lines (:out (p/shell {:dir (str dir) :out :string :err :string}
                                                                   "git" "ls-tree" "-r" "--name-only" rev))))]
          [r (:out (p/shell {:dir (str dir) :out :string :err :string} "git" "show" (str rev ":" r)))])))

(defn- impl? [cfg rel] (some #(or (= rel %) (str/starts-with? rel (str (str/replace % #"/+$" "") "/"))) (:implementation cfg)))

(defn- tree-hash
  "The git tree hash of the room's implementation paths, as verify records it."
  [room cfg]
  (let [g (s/tmp-dir)
        git (fn [& args] (apply p/shell {:dir (str room) :out :string :err :string :continue true
                                         :extra-env {"GIT_DIR" (str g) "GIT_WORK_TREE" (str room)}}
                                "git" args))
        paths (filter #(fs/exists? (fs/file room %)) (:implementation cfg))]
    (git "init" "-q")
    (when (seq paths) (apply git "add" "-f" "--" paths))
    (str/trim (:out (git "write-tree")))))

(defn- finding-with! [ctx code parts]
  (let [r (kc/exits! ctx code)
        ls (if (= 2 code) (kc/cannot-run-lines r) (kc/finding-lines r))]
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) ls)
              (str "no line names all of " (pr-str parts) "\n" (s/show r)))
    r))

(defn- free-port []
  (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss)))

(defn- alive? [pid] (let [h (java.lang.ProcessHandle/of pid)] (and (.isPresent h) (.isAlive (.get h)))))

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          build (fs/file (:dir ctx) cycle-rel "build")
          cfg (kc/config-of (:dir ctx))
          at-cut (into {} (remove (fn [[k _]] (impl? cfg k)) (files-at (:dir ctx) "spec-3")))
          built (into {} (remove (fn [[k _]] (impl? cfg k)) (files-under build)))]
      (s/check! (fs/directory? build) (str "no checkout at " build "\n" (s/show r)))
      (s/check! (not (fs/exists? (fs/file build ".git"))) "the checkout has a history")
      (s/check! (= at-cut built) (str "the checkout's files are not spec-3's: "
                                      (pr-str (take 5 (remove #(= (get at-cut %) (get built %)) (distinct (concat (keys at-cut) (keys built))))))))
      nil)))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (let [ctx (passing (project! ctx))]
      (fs/delete (fs/file (:dir ctx) cycle-rel "cycle.edn"))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (kc/refused-naming! ctx (str cycle-rel "/cycle.edn")) nil))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (passing ctx)
          dir (s/copy-of-room!)]
      (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
      (s/write! dir kc/lint-rel (kc/lint-text kc/default-scenarios))
      (s/write! dir "src/rdm_spec/old.clj" "(ns rdm-spec.old)\n")
      (s/write! dir "dhf/harness/config.edn"
                (str (pr-str (assoc (kc/config-of dir) :verify [{:kind :tests :command ["bb" "test.clj"]}]
                                    :allure {:labels {"spec" :scenario-id}})) "\n"))
      (kc/cut! dir "spec-3")
      (kc/write-cycle! dir)
      (assoc ctx :dir dir))))

(defstep #"the checkout it verifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (not (fs/exists? (fs/file (:dir ctx) cycle-rel "build/src/rdm_spec/old.clj"))) "the checkout holds old.clj")
    nil))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [cfg (kc/config-of (:dir ctx))
          build (fs/file (:dir ctx) cycle-rel "build")
          of (fn [d] (into {} (filter (fn [[k _]] (impl? cfg k)) (files-under d))))]
      (s/check! (= (of (:room ctx)) (of build))
                (str "the checkout's implementation is " (pr-str (keys (of build))) ", the room's " (pr-str (keys (of (:room ctx))))))
      nil)))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (passing (project! ctx))
          room (:room ctx)]
      (s/write! room "dhf/harness/config.edn" (pr-str {:verify [{:kind :tests :command ["bb" "-e" "(System/exit 0)"]}]}))
      (s/write! room kc/lint-rel "Feature: Lint\n  Edited by the builder.\n")
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [build (fs/file (:dir ctx) cycle-rel "build")
          at-cut (files-at (:dir ctx) "spec-3")]
      (doseq [rel ["dhf/harness/config.edn" kc/lint-rel]]
        (s/check! (= (get at-cut rel) (slurp (str (fs/file build rel)))) (str rel " in the checkout is not spec-3's"))))
    nil))

;; ---------------------------------------------------------------------------
;; The tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx]
    (let [ctx (project! ctx)
          env-file (str (fs/file (s/tmp-dir) "results-dir.txt"))
          ctx (assoc ctx :env-file env-file
                     :room (room-with! {:results (results-for [["spec-0012" "passed"] ["spec-0014" "passed"]])
                                        :env-file env-file}))]
      (kc/run-line ctx "verify ROOM 03"))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          f (:env-file ctx)]
      (s/check! (fs/exists? f) (str "the tests never ran\n" (s/show r)))
      (s/check! (= (str (fs/file (:dir ctx) cycle-rel "results")) (slurp f))
                (str "RESULTS_DIR was " (pr-str (slurp f))))
      nil)))

(defstep #"the :tests check's command exits 0"
  (fn [ctx] (passing (ensure-project ctx))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (s/check! (:room ctx) "no room") ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] (passing ctx)))

(defstep #"the tests check passes"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          c (check-named r "tests")]
      (s/check! (= :passed (:outcome c)) (str "the tests check is " (pr-str c)))
      nil)))

(defstep #"the :tests check's command exits 1"
  (fn [ctx]
    (assoc (ensure-project ctx) :room (room-with! {:out "a test failed: boom" :code 1}))))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx] (finding-with! ctx 1 ["test command" "exited 1"]) nil))

(defstep #"the test command's output is in dhf/cycles/03/test\.log, which the refusal names"
  (fn [ctx]
    (finding-with! ctx 1 [(str cycle-rel "/test.log")])
    (let [f (fs/file (:dir ctx) cycle-rel "test.log")]
      (s/check! (and (fs/exists? f) (str/includes? (slurp (str f)) "a test failed: boom")) "test.log does not hold the output"))
    nil))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (assoc (project! ctx) :room (room-with! {}))))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (finding-with! ctx 1 ["no results is not a run"]) nil))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx] (project! ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (assoc ctx :room (room-with! {:results (results-for [["spec-0014" "passed"]])}))))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx] (finding-with! ctx 1 ["spec-0012"]) nil))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (assoc ctx :room (room-with! {:results (results-for [["spec-0012" "failed"] ["spec-0014" "passed"]])}))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (finding-with! ctx 1 ["spec-0012" "failed"]) nil))

;; ---------------------------------------------------------------------------
;; Verify records what it verified

(def ^:private answers
  {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:ok true}\")"]
   :expect {:exit 0 :stdout-starts "{:ok"} :budget {:seconds 30}})

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (passing (project! ctx #(update % :verify conj answers)))]
      (assoc ctx :room-tree (tree-hash (:room ctx) (kc/config-of (:dir ctx)))))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx]
    (let [ctx (kc/run-line ctx "verify ROOM 03")]
      (kc/exits! ctx 0)
      ctx)))

(defstep #"dhf/cycles/03/ratified\.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (kc/the-run ctx)
          want {:cut "spec-3" :room-tree (:room-tree ctx) :outcome :passed :checkout (str cycle-rel "/build")
                :checks (:checks (verify-out r))}]
      (s/check! (= 40 (count (:room-tree ctx))) (str "H is " (:room-tree ctx)))
      (s/check! (= want (ratified ctx)) (str "ratified.edn holds\n" (pr-str (ratified ctx)) "\nnot\n" (pr-str want)))
      nil)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (let [b (fs/file (:dir ctx) cycle-rel "build")]
      (s/check! (= (slurp (str (fs/file (:room ctx) "test.clj"))) (slurp (str (fs/file b "test.clj"))))
                "the checkout does not hold the room's implementation")
      (s/check! (fs/exists? (fs/file b kc/lint-rel)) "the checkout does not hold the cut's spec")
      nil)))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx]
    (let [ctx (assoc (project! ctx) :room (room-with! {:out "boom" :code 1}))]
      (kc/run-line ctx "verify ROOM 03"))))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [rat (ratified ctx)]
      (s/check! (= :refused (:outcome rat)) (str "ratified.edn is " (pr-str rat)))
      (s/check! (and (string? (:reason rat)) (str/includes? (:reason rat) "exited 1")) (str "the reason is " (pr-str (:reason rat))))
      nil)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)]
      (finding-with! ctx 1 [(str file ":" line ": ")])
      nil)))

;; ---------------------------------------------------------------------------
;; Declared checks

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (project! ctx #(update % :verify conj answers))))

(defstep #"both pass"
  (fn [ctx] (passing ctx)))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify \{:cycle \"03\" .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          out (kc/stdout-edn r)
          [t a] (get-in out [:verify :checks])]
      (s/check! (= {:cycle "03" :cut "spec-3" :outcome :passed} (dissoc (:verify out) :checks)) (str "the result is " (pr-str out)))
      (s/check! (= [:verify] (keys out)) (str "the result is " (pr-str out)))
      (s/check! (= 2 (count (get-in out [:verify :checks]))) (str "the checks are " (pr-str (get-in out [:verify :checks]))))
      (s/check! (= #{:name :kind :outcome :seconds :scenarios} (set (keys t))) (str "the tests check is " (pr-str t)))
      (s/check! (= ["tests" :tests :passed 2] [(:name t) (:kind t) (:outcome t) (:scenarios t)]) (str "the tests check is " (pr-str t)))
      (s/check! (number? (:seconds t)) (str "S1 is " (pr-str (:seconds t))))
      (s/check! (= #{:name :kind :outcome :seconds :budget :margin} (set (keys a))) (str "the runs check is " (pr-str a)))
      (s/check! (= ["answers" :runs :passed 30] [(:name a) (:kind a) (:outcome a) (:budget a)]) (str "the runs check is " (pr-str a)))
      (s/check! (and (number? (:seconds a)) (number? (:margin a)) (< (Math/abs (double (- (+ (:seconds a) (:margin a)) 30))) 0.02))
                (str "M is not B less S2: " (pr-str a)))
      nil)))

(def ^:private serves-ok
  {:kind :serves :name "serves" :start ["bb" "-e" "(System/exit 0)" "{port}"] :port 0
   :budget {:seconds 2 :polls 4} :probe ["bb" "-e" "(System/exit 0)" "{url}"]})

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx]
    (let [ctx (project! ctx #(update % :verify into [(assoc serves-ok :port (free-port)) answers]))]
      (assoc ctx :room (room-with! {:out "boom" :code 1})))))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          cs (:checks (verify-out r))]
      (s/check! (= [["tests" :failed] ["serves" :not-run] ["answers" :not-run]] (mapv (juxt :name :outcome) cs))
                (str "the checks are " (pr-str cs)))
      nil)))

(def ^:private untrusted
  {"declares no :tests check" #(assoc % :verify [answers])
   "declares a check of the kind :renders" #(update % :verify conj {:kind :renders :name "renders"})
   "declares a :serves check whose probe is under src/" #(update % :verify conj (assoc serves-ok :probe ["bb" "src/probe.clj" "{url}"]))
   "is absent" #(dissoc % :verify)
   "declares a :tests check with no command" #(assoc % :verify [{:kind :tests}])})

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx is]
    (passing (project! ctx (get untrusted is)))))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (kc/refused-naming! ctx why)
    (s/check! (not (fs/exists? (fs/file (:dir ctx) cycle-rel "test.log"))) "a check ran: test.log was written")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) cycle-rel "ratified.edn"))) "ratified.edn was written")
    nil))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx]
    (passing (project! ctx #(update % :verify conj (assoc answers :command ["rdm-spec-no-such-command-x"]))))))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (kc/refused-naming! ctx "answers" "rdm-spec-no-such-command-x") nil))

;; --- :serves ---------------------------------------------------------------------

(defn- app-start
  "The start command of an app: bb, writing its process id to `pid-file`
  and `started`, then `body` (source using port p)."
  [pid-file body]
  ["bb" "-e" (str "(def p (parse-long (first *command-line-args*)))"
                  "(spit " (pr-str pid-file) " (str (.pid (java.lang.ProcessHandle/current))))"
                  body)
   "{port}"])

(def ^:private http-app
  "(Thread/sleep 250) (require '[org.httpkit.server :as hs]) (hs/run-server (fn [_] {:status 200 :body \"ok\"}) {:port p}) @(promise)")

(def ^:private silent-app
  "(with-open [ss (java.net.ServerSocket. p)] (loop [cs []] (recur (conj cs (.accept ss)))))")

(def ^:private sleeping-app "(Thread/sleep 60000)")

(defn- serves! [ctx {:keys [seconds polls body probe]}]
  (let [port (free-port)
        pid-file (str (fs/file (s/tmp-dir) "app.pid"))
        check {:kind :serves :name "serves" :start (app-start pid-file body) :port port
               :budget {:seconds seconds :polls polls}
               :probe (or probe ["bb" "-e" "(System/exit 0)" "{url}"])}]
    (assoc (passing (project! ctx #(update % :verify conj check))) :port port :pid-file pid-file)))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx] (serves! ctx {:seconds 10 :polls 20 :body http-app})))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          c (check-named r "serves")]
      (s/check! (= [:passed 2 20 18] [(:outcome c) (:answered-on-poll c) (:polls c) (:margin c)]) (str "the check is " (pr-str c)))
      nil)))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (let [f (:pid-file ctx)]
      (s/check! (fs/exists? f) "the app never started")
      (let [pid (parse-long (str/trim (slurp f)))]
        (when (alive? pid) (Thread/sleep 500))
        (when (alive? pid) (.destroyForcibly (.get (java.lang.ProcessHandle/of pid))))
        (s/check! (not (alive? pid)) (str "the app " pid " is still running")))
      nil)))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx] (serves! ctx {:seconds 10 :polls 20 :body sleeping-app})))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (\d+) seconds"
  (fn [ctx n]
    (let [r (finding-with! ctx 1 ["no answer came within " (str n " seconds")])]
      (s/check! (= :failed (:outcome (check-named r "serves"))) (str "the check is " (pr-str (check-named r "serves"))))
      nil)))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [ctx (serves! ctx {:seconds 2 :polls 4 :body http-app})
          held (java.net.ServerSocket. (int (:port ctx)))]
      (assoc ctx :held held))))

(defstep #"it exits 2, names the port, and starts nothing"
  (fn [ctx]
    (try
      (kc/refused-naming! ctx (str (:port ctx)))
      (s/check! (not (fs/exists? (:pid-file ctx))) "the app was started")
      (finally (some-> ^java.net.ServerSocket (:held ctx) .close)))
    nil))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx]
    (serves! ctx {:seconds 10 :polls 20 :body http-app
                  :probe ["bb" "-e" "(println \"this is not the application\") (System/exit 1)" "{url}"]})))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (let [r (finding-with! ctx 1 ["this is not the application"])]
      (s/check! (= :failed (:outcome (check-named r "serves"))) "the check did not fail")
      nil)))

(defstep #"a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
  (fn [ctx] (serves! ctx {:seconds 2 :polls 4 :body silent-app})))

;; --- :runs -------------------------------------------------------------------------

(def ^:private expectations
  {"the exit 0" {:expect {:exit 0} :budget {:seconds 30}}
   "standard output starting \"{:help\"" {:expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}}
   "an answer within 10 seconds" {:expect {:exit 0} :budget {:seconds 10}}})

(def ^:private doings
  {"exits 3" [["bb" "-e" "(System/exit 3)"] ["exited 3" "0 was expected"]]
   "prints \"usage\" and exits 0" [["bb" "-e" "(println \"usage\")"] ["usage"]]
   "is still running after 10 seconds" [["bb" "-e" "(Thread/sleep 30000)"] ["10 seconds"]]})

(defstep #"a :runs check expecting (.+)"
  (fn [ctx expected]
    (assoc ctx :expected (or (get expectations expected) (s/fail! (str "no such expectation: " expected))))))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (let [[cmd named] (get doings does)
          check (merge {:kind :runs :name "answers" :command cmd} (:expected ctx))]
      (assoc (passing (project! ctx #(update % :verify conj check))) :named named))))

(defstep #"it exits 1, the check is :failed, and it names (?:the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx]
    (let [r (finding-with! ctx 1 (:named ctx))]
      (s/check! (= :failed (:outcome (check-named r "answers"))) (str "the check is " (pr-str (check-named r "answers"))))
      nil)))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def ^:private mapped-scenarios
  "The lint feature file of the mapping's scenarios: a rule, the need
  UN-001, spec-0003 at @e2e."
  (str "# A fixture feature file of verify's steps.\n\n@UN-001\nFeature: Lint\n  A fixture.\n\n"
       "  Rule: The lint names what it finds\n\n"
       "    @spec-0003 @e2e\n    Scenario: A view with no description fails the lint\n"
       "      Given a view with no description\n      When \"rdm-spec check\" is run\n      Then it fails rule c4-scope\n\n"
       "    @spec-0004 @contract\n    Scenario: A container with no description fails the lint\n"
       "      Given a container with no description\n      When \"rdm-spec check\" is run\n      Then it fails\n"))

(defn- mapped-results
  "{file text}: a result per scenario of the project `dir`, filled through
  `m` as a faithful test would, each with a browser-trace attachment, then
  changed by `f` for spec-0003."
  [dir m f]
  (let [cfg (kc/config-of dir)
        {:keys [pickles]} (runner/plan dir {:spec-dir (:spec-dir cfg) :id-prefix (:id-prefix cfg) :model (:model cfg)})]
    (into {}
          (for [[i pk] (map-indexed vector pickles)
                :let [outcome {:status "passed" :start 1 :stop 2 :uuid (str "u" i)
                               :steps (mapv (fn [st] {:name (runner/step-name st) :status "passed"}) (:steps pk))
                               :attachments [{:name "browser-trace" :source "t.zip" :type "application/zip"}]}
                      r (runner/allure-result pk outcome m)]]
            [(str "u" i "-result.json") (json/generate-string (if (= "spec-0003" (:id pk)) (f r) r))]))))

(defn- mapped-project!
  "ctx with :dir (the mapping's scenarios, `m` as its :allure, cut spec-3)
  and :room (results through `result-m`, spec-0003's changed by `f`)."
  [ctx m result-m f]
  (let [dir (s/copy-of-room!)]
    (doseq [x (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete x))
    (s/write! dir kc/lint-rel mapped-scenarios)
    (s/write! dir "dhf/harness/config.edn"
              (str (pr-str (cond-> (assoc (kc/config-of dir) :verify [{:kind :tests :command ["bb" "test.clj"]}])
                             true (dissoc :allure)
                             m (assoc :allure m))) "\n"))
    (kc/cut! dir "spec-3")
    (kc/write-cycle! dir)
    (assoc ctx :dir dir :room (room-with! {:results (mapped-results dir result-m f)}))))

(defn- relabel [r n f] (update r :labels (fn [ls] (vec (keep #(if (= n (:name %)) (f %) %) ls)))))

(def ^:private wrong-results
  {"a second step reworded from the scenario's line"
   [nil #(assoc-in % [:steps 1 :name] "When \"rdm-spec lint\" is run") ["spec-0003" "steps-differ-from-scenario"]]
   "one step fewer than the scenario's lines"
   [nil #(update % :steps pop) ["spec-0003" "steps-differ-from-scenario"]]
   "the story \"Something else\", not the scenario's rule"
   [nil #(relabel % "story" (fn [l] (assoc l :value "Something else"))) ["spec-0003" "mapping-differs: story"]]
   "the epic of another bounded context"
   [nil #(relabel % "epic" (fn [l] (assoc l :value "verification"))) ["spec-0003" "mapping-differs: epic"]]
   "no issue link to UN-001, the need the scenario traces to"
   [nil #(assoc % :links []) ["spec-0003" "mapping-differs: links"]]
   "no browser-trace attachment, where :evidence requires one for @e2e"
   [{:e2e ["browser-trace"]} #(assoc % :attachments []) ["spec-0003" "evidence-missing: browser-trace"]]})

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (assoc ctx :mapping runner/default-mapping)))

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [[evidence f named] (or (get wrong-results wrong) (s/fail! (str "no such result: " wrong)))
          m (cond-> (:mapping ctx) evidence (assoc :evidence evidence))]
      (assoc (mapped-project! ctx m m f) :named named))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003 and (?:.+)"
  (fn [ctx]
    (let [r (finding-with! ctx 1 (:named ctx))]
      (s/check! (= :failed (:outcome (check-named r "tests"))) "the tests check did not fail")
      nil)))

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [ctx is]
    (let [m (case is
              "maps :epic to :aggregate" (assoc runner/default-mapping :epic :aggregate)
              "maps :colour to :context" (assoc runner/default-mapping :colour :context)
              "maps no label \"spec\"" (update runner/default-mapping :labels dissoc "spec"))]
      (mapped-project! ctx m runner/default-mapping identity))))

(defstep #"a configuration that declares no :allure"
  (fn [ctx] (mapped-project! ctx nil runner/default-mapping identity)))

(defstep #"each result is held to (\{.+\})"
  (fn [ctx doc]
    (s/check! (= runner/default-mapping (edn/read-string doc)) "the default mapping is not the one the scenario states")
    (kc/exits! ctx 0)
    ;; a result that breaks the default mapping, its component label dropped, fails
    (let [ctx (mapped-project! (dissoc ctx :dir :room) nil runner/default-mapping
                               (fn [r] (update r :labels (fn [ls] (vec (remove #(= "component" (:name %)) ls))))))
          ctx (kc/run-line ctx "verify ROOM 03")]
      (finding-with! ctx 1 ["spec-0003" "mapping-differs: component"])
      nil)))
