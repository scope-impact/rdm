(ns steps.verification-verify
  "Step definitions for dhf/spec/verification/verify.feature: `rdm-spec
  verify ROOM 03` in a cycle project, with a room of the scenario's own.
  (The helpers once in steps.ratification-support, copied here and
  adapted: a step file's helpers are its own.)

  A cycle project is a copy of the room (its configuration without
  :oracle, its :verify the step's), every feature file removed and the
  fixture's lint feature written instead (spec-0012, spec-0014, and
  spec-0013 tagged @wip), committed and tagged spec-3, with
  dhf/cycles/03/cycle.edn written after the cut. The kernel's shared Givens
  (dhf/cycles/03/cycle.edn names the cut spec-3; the spec at cut spec-3
  holds a scenario with no id) make one too.

  A room holds src/rdm_spec/demo.clj, src/app.clj (an app a :serves check
  starts, which writes its pid to app.pid where it runs) and a test.clj
  that prints a line, writes RESULTS_DIR's value to verify-env.txt where it
  runs, writes the results it is given into RESULTS_DIR and exits with the
  code it is given. Every check runs in the checkout, dhf/cycles/03/build.

  Every process a step starts (a socket holding a port) is stopped by the
  step that checks it.

  Each Given builds what it says at once (the When is the kernel's), but
  \"a :runs check expecting E\", which \"its command C\" completes.

  Context keys set here: :dir, :room, :tests ({:exit :results}), :verify
  (the :verify written at the cut), :edit (a function of the project
  applied before the cut), :tree (the room's tree hash), :port, :holder
  (the socket holding it), :expected-name (what a refusal names)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; Helpers (once steps.ratification-support's)

(def lint-feature "dhf/spec/architecture/lint.feature")

(def scenarios
  [{:id "spec-0012" :title "A view with no description fails the lint"}
   {:id "spec-0014" :title "A view over the limit fails the lint" :level "contract"}
   {:id "spec-0013" :title "A component that owns no feature file fails the lint" :wip? true}])

(def passing [["spec-0012" "passed"] ["spec-0014" "passed"]])

(def tests-check {:kind :tests :command ["bb" "test.clj"]})

(defn git!
  "git `args` in `dir`, with an identity; fails the step on an error."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
                                      "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"}}
                 "git" (map str args))]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- scenario-text [{:keys [id title level wip?]}]
  (str "\n  " (str/join " " (remove nil? [(str "@" id) (str "@" (or level "e2e")) (when wip? "@wip")]))
       "\n  Scenario: " title
       "\n    Given a model\n    When \"rdm-spec check\" is run\n    Then it passes\n"))

(defn feature-text []
  (str "# A fixture feature file of the verify steps.\n\n@UN-005\nFeature: Lint\n  A fixture.\n"
       (str/join (map scenario-text scenarios))))

(defn cycle-project!
  "A cycle project whose cut declares `verify` (nil: no :verify), `edit!`
  applied to it before the cut. Returns its directory."
  [verify edit!]
  (let [dir (s/copy-of-room!)
        cfg (kc/config-of dir)]
    (doseq [f (fs/glob (fs/file dir "dhf/spec") "**.feature")] (fs/delete f))
    (s/write! dir lint-feature (feature-text))
    (s/write! dir "dhf/harness/config.edn" (str (pr-str (if verify (assoc cfg :verify verify) (dissoc cfg :verify))) "\n"))
    (when edit! (edit! dir))
    (git! dir "init" "-q")
    (git! dir "config" "commit.gpgsign" "false")
    (git! dir "add" "-A" "-f" "--" ".")
    (git! dir "commit" "-q" "--no-verify" "-m" "the cut spec-3")
    (git! dir "tag" "spec-3")
    (s/write! dir "dhf/cycles/03/cycle.edn"
              (pr-str {:cut "spec-3" :scope :project
                       :builder {:name "builder-a" :actor :agent}
                       :grader {:name "grader-b" :actor :agent}
                       :spec-owner {:name "Sami" :actor :human}}))
    dir))

(defn- result-json [id status]
  (str "{\"uuid\":\"" (random-uuid) "\",\"name\":\"a test of " id "\",\"status\":\"" status "\","
       "\"steps\":[],"
       (when-not (= "passed" status) "\"statusDetails\":{\"message\":\"it did not pass\"},")
       "\"labels\":[{\"name\":\"spec\",\"value\":\"" id "\"}]}"))

(defn write-tests!
  "Writes the room's test.clj: it exits `exit` after writing `results`
  ([[id status]]) into RESULTS_DIR."
  [room {:keys [exit results]}]
  (s/write! room "test.clj"
            (str ";; the fixture's tests\n"
                 "(require '[clojure.java.io :as io])\n"
                 "(println \"the fixture's tests ran, exiting " exit "\")\n"
                 "(spit \"verify-env.txt\" (str (System/getenv \"RESULTS_DIR\")))\n"
                 "(let [d (System/getenv \"RESULTS_DIR\")]\n"
                 "  (.mkdirs (io/file d))\n"
                 (str/join (for [[id status] results]
                             (str "  (spit (io/file d (str (random-uuid) \"-result.json\")) "
                                  (pr-str (result-json id status)) ")\n")))
                 ")\n"
                 "(System/exit " exit ")\n")))

(def app-text
  "An app: writes its pid to app.pid, waits `delay` seconds, then answers
  every HTTP request on the port it is given with 200 (forever); with
  delay -1 it never listens."
  (str "(require '[clojure.java.io :as io])\n"
       "(spit \"app.pid\" (str (.pid (java.lang.ProcessHandle/current))))\n"
       "(let [[port delay] *command-line-args*\n"
       "      d (Double/parseDouble delay)]\n"
       "  (if (neg? d)\n"
       "    (Thread/sleep 600000)\n"
       "    (do (Thread/sleep (long (* 1000 d)))\n"
       "        (with-open [ss (java.net.ServerSocket. (Integer/parseInt port) 50 (java.net.InetAddress/getByName \"127.0.0.1\"))]\n"
       "          (loop []\n"
       "            (with-open [s (.accept ss)]\n"
       "              (let [in (io/reader (.getInputStream s))]\n"
       "                (loop [] (let [l (.readLine in)] (when-not (str/blank? l) (recur)))))\n"
       "              (let [out (.getOutputStream s)]\n"
       "                (.write out (.getBytes \"HTTP/1.1 200 OK\\r\\nContent-Length: 12\\r\\nConnection: close\\r\\n\\r\\nthe demo app\"))\n"
       "                (.flush out)))\n"
       "            (recur))))))\n"))

(defn room!
  "A new room, its tests as `tests` ({:exit :results})."
  [tests]
  (let [r (s/tmp-dir)]
    (s/write! r "src/rdm_spec/demo.clj" "(ns rdm-spec.demo)\n")
    (s/write! r "src/app.clj" (str "(require '[clojure.string :as str])\n" app-text))
    (write-tests! r tests)
    r))

(defn files-under
  "{rel text} of every file under `dir`, outside .git."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [r (str/replace (str (fs/relativize dir f)) "\\" "/")]
              :when (and (fs/regular-file? f) (not (or (= ".git" r) (str/starts-with? r ".git/"))))]
          [r (slurp (str f))])))

(defn implementation?
  "Whether `rel` lies under one of the implementation paths `impl`."
  [impl rel]
  (boolean (some #(or (= rel %) (str/starts-with? rel (str % "/"))) impl)))

(defn tree-hash
  "The git tree hash of the implementation paths `impl` of the room: a
  repository holding exactly those files, committed."
  [room impl]
  (let [d (s/tmp-dir)]
    (doseq [[r t] (files-under room) :when (implementation? impl r)]
      (s/write! d r t))
    (git! d "init" "-q")
    (git! d "add" "-A" "-f" "--" ".")
    (git! d "write-tree")))

(defn free-port
  "A port nothing listens on, just now."
  []
  (with-open [ss (java.net.ServerSocket. 0)] (.getLocalPort ss)))

;; ---------------------------------------------------------------------------
;; The scenario's cycle: each Given says what it holds and builds it (the
;; When is the kernel's), the cut once, the room's tests again each time

(declare built)

(defn- with-tests [ctx f]
  (built (update ctx :tests #(f (or % {:exit 0 :results passing})))))

(defn- built
  "ctx with :dir and :room: the cycle project (unless a Given made one)
  and the room, as the Givens said."
  [ctx]
  (let [ctx (if (:dir ctx) ctx (assoc ctx :dir (cycle-project! (get ctx :verify [tests-check]) (:edit ctx))))
        tests (or (:tests ctx) {:exit 0 :results passing})]
    (cond
      (not (and (:room ctx) (fs/exists? (fs/file (:room ctx) "src/app.clj"))))
      (assoc ctx :room (room! tests) ::written tests)
      (= tests (::written ctx)) ctx
      :else (do (write-tests! (:room ctx) tests) (assoc ctx ::written tests)))))

(defn- verify! [ctx] (kc/run-line (built ctx) "verify ROOM 03"))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx]
    (let [ctx (verify! ctx)]
      (kc/exits! ctx 0)
      ctx)))

(defn- build-dir [ctx] (fs/file (:dir ctx) "dhf/cycles/03/build"))
(defn- out [ctx] (:verify (kc/stdout-edn (kc/the-run ctx))))
(defn- check-named [ctx n] (first (filter #(= n (:name %)) (:checks (out ctx)))))
(defn- ratified [ctx]
  (let [f (fs/file (:dir ctx) "dhf/cycles/03/ratified.edn")]
    (s/check! (fs/exists? f) (str "no dhf/cycles/03/ratified.edn\n" (s/show (kc/the-run ctx))))
    (edn/read-string (slurp (str f)))))

(defn- finding-naming! [ctx & parts]
  (let [r (kc/the-run ctx)]
    (s/check! (some (fn [l] (every? #(str/includes? l (str %)) parts)) (kc/finding-lines r))
              (str "no finding names all of " (pr-str parts) "\n" (s/show r)))
    r))

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [b (build-dir ctx)
          impl (:implementation (kc/config-of (:dir ctx)))
          at-cut (into {} (for [rel (str/split-lines (git! (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3"))
                                :when (not (implementation? impl rel))]
                            [rel (git! (:dir ctx) "show" (str "spec-3:" rel))]))
          have (files-under b)]
      (s/check! (not (fs/exists? (fs/file b ".git"))) "the checkout has a history")
      (doseq [[rel text] at-cut]
        (s/check! (= (str/trim text) (some-> (get have rel) str/trim)) (str "the checkout's " rel " is not the cut's")))
      nil)))

(defstep #"no dhf/cycles/03/cycle\.edn"
  (fn [ctx]
    (let [ctx (built ctx)]
      (fs/delete (fs/file (:dir ctx) "dhf/cycles/03/cycle.edn"))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (kc/refused-naming! ctx "dhf/cycles/03/cycle.edn") nil))

(defstep #"the cut spec-3 holds src/rdm_spec/old\.clj and the room holds no such file"
  (fn [ctx]
    (built (assoc ctx :edit (fn [dir] (s/write! dir "src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"))))))

(defstep #"the checkout it verifies holds no src/rdm_spec/old\.clj"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (git! (:dir ctx) "show" "spec-3:src/rdm_spec/old.clj") "the cut holds no old.clj")
    (s/check! (not (fs/exists? (fs/file (build-dir ctx) "src/rdm_spec/old.clj"))) "the checkout holds src/rdm_spec/old.clj")
    nil))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [impl (:implementation (kc/config-of (:dir ctx)))
          pick (fn [m] (into {} (filter #(implementation? impl (key %)) m)))
          room (pick (files-under (:room ctx)))
          b (dissoc (pick (files-under (build-dir ctx))) "verify-env.txt")]
      (s/check! (= room b) (str "the checkout's implementation is " (keys b) ", the room's " (keys room)))
      nil)))

(defstep #"the builder has edited dhf/harness/config\.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (built ctx)
          room (:room ctx)]
      (s/write! room "dhf/harness/config.edn" (pr-str {:verify [{:kind :tests :command ["bb" "-e" "(System/exit 7)"]}]}))
      (s/write! room lint-feature (str (feature-text) "\n  @spec-0099 @e2e\n  Scenario: Added by the builder\n    Given a model\n"))
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [rel ["dhf/harness/config.edn" lint-feature]]
      (s/check! (= (git! (:dir ctx) "show" (str "spec-3:" rel)) (str/trim (slurp (str (fs/file (build-dir ctx) rel)))))
                (str "the checkout's " rel " is not the cut's")))
    nil))

;; ---------------------------------------------------------------------------
;; The tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx] (verify! ctx)))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (let [f (fs/file (build-dir ctx) "verify-env.txt")
          want (str (fs/canonicalize (fs/file (:dir ctx) "dhf/cycles/03/results")))]
      (s/check! (fs/exists? f) (str "the test command did not run\n" (s/show (kc/the-run ctx))))
      (s/check! (= want (slurp (str f))) (str "RESULTS_DIR was " (pr-str (slurp (str f))) ", not " want))
      nil)))

(defstep #"the :tests check's command exits (\d+)"
  (fn [ctx code] (with-tests ctx #(assoc % :exit (parse-long code)))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] (with-tests ctx identity)))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] (with-tests ctx #(assoc % :results passing))))

(defstep #"the tests check passes"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (= :passed (:outcome (check-named ctx "tests"))) (str "the tests check is " (pr-str (check-named ctx "tests"))))
    nil))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (kc/exits! ctx 1)
    (finding-naming! ctx "test command" "failed" "exit status 1")
    nil))

(defstep #"the test command's output is in dhf/cycles/03/test\.log, which the refusal names"
  (fn [ctx]
    (let [f (fs/file (:dir ctx) "dhf/cycles/03/test.log")]
      (s/check! (and (fs/exists? f) (str/includes? (slurp (str f)) "the fixture's tests ran")) "test.log holds no output of the tests")
      (finding-naming! ctx "dhf/cycles/03/test.log")
      nil)))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (with-tests ctx (constantly {:exit 0 :results []}))))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (kc/exits! ctx 1) (finding-naming! ctx "a green exit with no results is not a run") nil))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx] (with-tests ctx identity)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (with-tests ctx #(assoc % :results [["spec-0014" "passed"]]))))

(defstep #"it exits 1 and names (spec-\d+)"
  (fn [ctx id] (kc/exits! ctx 1) (finding-naming! ctx id) nil))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (with-tests ctx #(assoc % :results [["spec-0012" "failed"] ["spec-0014" "passed"]]))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (kc/exits! ctx 1) (finding-naming! ctx "spec-0012" "failed") nil))

;; ---------------------------------------------------------------------------
;; Verify records what it verified

(def answers {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:help []}\")"]
              :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (built (assoc ctx :verify [tests-check answers]))]
      (assoc ctx :tree (tree-hash (:room ctx) (:implementation (kc/config-of (:dir ctx))))))))

(defstep #"dhf/cycles/03/ratified\.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [rat (ratified ctx)
          want {:cut "spec-3" :room-tree (:tree ctx) :outcome :passed :checkout "dhf/cycles/03/build" :checks (:checks (out ctx))}]
      (s/check! (= 40 (count (:tree ctx))) "no tree hash")
      (s/check! (= want rat) (str "ratified.edn holds " (pr-str rat) "\nnot " (pr-str want)))
      nil)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (let [b (build-dir ctx)]
      (s/check! (fs/exists? (fs/file b "src/rdm_spec/demo.clj")) "the checkout holds no room's code")
      (s/check! (fs/exists? (fs/file b lint-feature)) "the checkout holds no spec")
      nil)))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx] (verify! (with-tests ctx #(assoc % :exit 1)))))

(defstep #"dhf/cycles/03/ratified\.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [rat (ratified ctx)]
      (s/check! (and (= :refused (:outcome rat)) (string? (:reason rat)) (str/includes? (:reason rat) "exit status 1"))
                (str "ratified.edn holds " (pr-str rat)))
      nil)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (let [{:keys [file line]} (:unstamped ctx)
          r (kc/exits! ctx 1)]
      (s/check! (some #(str/starts-with? % (str file ":" line ": ")) (kc/finding-lines r))
                (str "no finding names " file ":" line "\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A project declares its checks

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (built (assoc ctx :verify [tests-check answers]))))

(defstep #"both pass"
  (fn [ctx] (with-tests ctx #(assoc % :exit 0 :results passing))))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .*"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          want '{:verify {:cycle "03" :cut "spec-3" :outcome :passed
                          :checks [{:name "tests" :kind :tests :outcome :passed :seconds S1 :scenarios N}
                                   {:name "answers" :kind :runs :outcome :passed :seconds S2 :budget B :margin M}]}}
          m (kc/match-edn want (kc/stdout-edn r))
          {:strs [S1 S2 N B M]} (:bound m)]
      (s/check! (not (:at m)) (str "standard output differs at " (pr-str (:at m)) "\n" (s/show r)))
      (s/check! (and (number? S1) (number? S2) (<= 0 S1) (<= 0 S2)) (str "the seconds are " S1 " and " S2))
      (s/check! (= 2 N) (str "the scenarios named are " N ", not 2"))
      (s/check! (= 30 B) (str "the budget is " B))
      (s/check! (< (Math/abs (double (- M (- B S2)))) 0.02) (str "the margin " M " is not " B " - " S2))
      nil)))

(def ^:private serves-a
  {:kind :serves :name "served" :start ["bb" "src/app.clj" "{port}" "0"] :port 0
   :budget {:seconds 10 :polls 20} :probe ["bb" "dhf/harness/probe.clj" "{url}" "0"]})

(def probe-text
  "A probe: GETs the url and exits with the code it is given, printing what it read."
  (str "(let [[url code] *command-line-args*]\n"
       "  (println \"the probe read:\" (slurp url))\n"
       "  (when-not (= \"0\" code) (println \"this is not the demo app\"))\n"
       "  (System/exit (Integer/parseInt code)))\n"))

(defn- with-probe [ctx]
  (update ctx :edit (fn [f] (fn [dir] (when f (f dir)) (s/write! dir "dhf/harness/probe.clj" probe-text)))))

(defn- serves [port delay probe-code]
  (-> serves-a
      (assoc :port port)
      (assoc-in [:start 3] (str delay))
      (assoc-in [:probe 3] (str probe-code))))

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx]
    (-> ctx
        with-probe
        (assoc :verify [tests-check (serves (free-port) 0 0) answers])
        (with-tests #(assoc % :exit 1)))))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (kc/exits! ctx 1)
    (let [cs (:checks (out ctx))]
      (s/check! (= [["tests" :failed] ["served" :not-run] ["answers" :not-run]] (map (juxt :name :outcome) cs))
                (str "the checks are " (pr-str cs)))
      (s/check! (not (fs/exists? (fs/file (build-dir ctx) "app.pid"))) "the app was started")
      nil)))

(def ^:private untrusted
  {"declares no :tests check" [answers]
   "declares a check of the kind :renders" [tests-check {:kind :renders :name "pages" :command ["true"]}]
   "declares a :serves check whose probe is under src/" [tests-check (assoc serves-a :port 8999 :probe ["bb" "src/probe.clj" "{url}"])]
   "is absent" nil
   "declares a :tests check with no command" [{:kind :tests}]})

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx which] (built (assoc ctx :verify (get untrusted which)))))

(defn- ran-nothing! [ctx]
  (s/check! (not (fs/exists? (fs/file (:dir ctx) "dhf/cycles/03/test.log"))) "the tests check ran")
  (s/check! (not (fs/exists? (fs/file (build-dir ctx) "verify-env.txt"))) "the tests check ran")
  (s/check! (not (fs/exists? (fs/file (build-dir ctx) "app.pid"))) "an app was started"))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (kc/refused-naming! ctx why)
    (ran-nothing! ctx)
    nil))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx]
    (built (assoc ctx :verify [tests-check (assoc answers :command ["rdm-spec-no-such-command" "help"])]))))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx]
    (kc/refused-naming! ctx "answers" "rdm-spec-no-such-command")
    (ran-nothing! ctx)
    nil))

;; ---------------------------------------------------------------------------
;; A :serves check

(defn- app-pid [ctx]
  (let [f (fs/file (build-dir ctx) "app.pid")]
    (when (fs/exists? f) (parse-long (str/trim (slurp (str f)))))))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx]
    ;; the first poll comes after one interval (0.5 s); the app listens after 0.6 s
    (-> ctx with-probe (assoc :verify [tests-check (serves (free-port) 0.6 0)]) built)))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [c (check-named ctx "served")]
      (s/check! (= [:passed 2 20 18] [(:outcome c) (:answered-on-poll c) (:polls c) (:margin c)]) (str "the check is " (pr-str c)))
      nil)))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (let [pid (app-pid ctx)]
      (s/check! pid "the app never ran")
      (let [h (java.lang.ProcessHandle/of pid)]
        (when (and (.isPresent h) (.isAlive (.get h)))
          (.destroyForcibly (.get h))
          (s/fail! (str "the app " pid " is still running"))))
      nil)))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx] (-> ctx with-probe (assoc :verify [tests-check (serves (free-port) -1 0)]) built)))

(defstep #"it exits 1, the check is :failed, and it says no answer came within 10 seconds"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "served"))) (str "the check is " (pr-str (check-named ctx "served"))))
    (finding-naming! ctx "no answer came within 10 seconds")
    nil))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          port (.getLocalPort ss)]
      (-> ctx with-probe (assoc :verify [tests-check (serves port 0 0)] :port port :holder ss) built))))

(defstep #"it exits 2, names the port, and starts nothing"
  (fn [ctx]
    (try
      (kc/refused-naming! ctx (str (:port ctx)))
      (s/check! (nil? (app-pid ctx)) "the app was started")
      (finally (.close ^java.net.ServerSocket (:holder ctx))))
    nil))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx] (-> ctx with-probe (assoc :verify [tests-check (serves (free-port) 0 1)]) built)))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "served"))) (str "the check is " (pr-str (check-named ctx "served"))))
    (finding-naming! ctx "this is not the demo app")
    nil))

;; ---------------------------------------------------------------------------
;; A :runs check

(def ^:private expecting
  {"the exit 0" {:expect {:exit 0}}
   "standard output starting \"{:help\"" {:expect {:exit 0 :stdout-starts "{:help"}}
   "an answer within 10 seconds" {:expect {:exit 0} :budget {:seconds 10}}})

(def ^:private doing
  {"exits 3" ["bb" "-e" "(System/exit 3)"]
   "prints \"usage\" and exits 0" ["bb" "-e" "(println \"usage\")"]
   "is still running after 10 seconds" ["bb" "-e" "(Thread/sleep 60000)"]})

(defstep #"a :runs check expecting (.+)"
  (fn [ctx e]
    (let [x (or (get expecting e) (s/fail! (str "no such expectation: " e)))]
      (assoc ctx :verify [tests-check (merge {:kind :runs :name "answers" :budget {:seconds 30}} x)]))))

(defstep #"its command (.+)"
  (fn [ctx d]
    (let [cmd (or (get doing d) (s/fail! (str "no such command: " d)))]
      (built (assoc ctx :verify (update (:verify ctx) 1 assoc :command cmd))))))

(def ^:private differences
  {"the exit 3 where 0 was expected" ["exit 3" "0 was expected"]
   "the first line it printed" ["usage"]
   "the budget of 10 seconds" ["budget of 10 seconds"]})

(defstep #"it exits 1, the check is :failed, and it names (.+)"
  (fn [ctx d]
    (kc/exits! ctx 1)
    (s/check! (= :failed (:outcome (check-named ctx "answers"))) (str "the check is " (pr-str (check-named ctx "answers"))))
    (apply finding-naming! ctx "answers" (or (get differences d) (s/fail! (str "no such difference: " d))))
    nil))
