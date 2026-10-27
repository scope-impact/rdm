(ns steps.verification-verify
  "Step definitions of dhf/spec/verification/verify.feature. A scenario's
  project is verifiable (k/verifiable!): its tests run its own scenarios with
  rdm-spec run, by path; a scenario changes the configuration at the cut, or
  what the room holds, to make the gate refuse."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn- file [ctx p] (fs/file (k/guard! (:dir ctx)) p))
(defn- text [ctx p] (let [f (file ctx p)] (when (fs/exists? f) (slurp f))))
(defn room [ctx] (k/guard! (get-in ctx [:ph "ROOM"])))
(defn checkout [ctx] (fs/path (:dir ctx) "dhf/cycles/03/build"))
(defn ratified [ctx] (some-> (text ctx "dhf/cycles/03/ratified.edn") edn/read-string))

(defn recut!
  "Changes the configuration and cuts spec-3 again."
  [ctx f & args]
  (apply k/update-edn! ctx "dhf/harness/config.edn" f args)
  (k/tag-cut! ctx "spec-3"))

(defn checks! [ctx checks] (recut! ctx assoc :verify checks))

(defn verify [ctx] (k/rdm-line ctx "rdm-spec verify ROOM 03"))

(defn out-checks [ctx] (get-in (k/edn-out ctx) [:verify :checks]))
(defn check-named [ctx n] (or (first (filter #(= n (:name %)) (or (out-checks ctx) (:checks (ratified ctx)))))
                              (fail! (str "no check " n ": " (:out ctx) (:err ctx)))))

(defn tests-then
  "The tests command, then a shell line."
  [line]
  ["sh" "-c" (str (str/join " " (k/tests-command)) "; " line)])

(def ok-runs {:kind :runs :name "answers" :command ["sh" "-c" "echo '{:help []}'"]
              :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [co (checkout ctx)
          committed (sort (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3")))
          have (->> (fs/glob co "**" {:hidden true}) (filter fs/regular-file?) (map #(str (fs/relativize co %))) sort)]
      (assert (not (fs/exists? (fs/path co ".git"))) "the checkout has a history")
      (assert (= committed have) (pr-str have))
      ctx)))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx)]
      (fs/delete (file ctx "dhf/cycles/03/cycle.edn"))
      ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "dhf/cycles/03/cycle.edn")))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx {:config {:implementation ["src" "test"]} :extra {"src/rdm_spec/old.clj" "(ns old)\n"}})]
      (fs/delete-tree (fs/path (room ctx) "src"))
      ctx)))

(defn tree [d]
  (->> (fs/glob d "**" {:hidden true})
       (filter #(or (fs/regular-file? % {:nofollow-links true}) (fs/sym-link? %)))
       (map #(str (fs/relativize d %))) (remove #(str/starts-with? % ".git/")) sort vec))

(defstep #"the checkout it verifies holds no src/rdm_spec/old.clj"
  (fn [ctx] (k/check-exit ctx 0) (assert (not (fs/exists? (fs/path (checkout ctx) "src/rdm_spec/old.clj")))) ctx))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (doseq [p ["src" "test"]]
      (assert (= (tree (fs/path (room ctx) p)) (tree (fs/path (checkout ctx) p))) p))
    ctx))

(defstep #"a room whose implementation holds a file its .gitignore excludes, a directory it excludes, and a symbolic link to a file beside it"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx)
          r (room ctx)]
      (k/write-files! r {".gitignore" "*.log\nnode_modules/\n" "test/debug.log" "noise\n" "test/node_modules/x/index.js" "x\n"})
      (k/write-files! r {"test/notes.txt" "notes\n"})
      (fs/create-sym-link (fs/path r "test/link.txt") "notes.txt")
      ctx)))

(defstep #"the checkout holds neither the excluded file nor the excluded directory"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (fs/exists? (fs/path (checkout ctx) "test/debug.log"))))
    (assert (not (fs/exists? (fs/path (checkout ctx) "test/node_modules"))))
    ctx))

(defstep #"the symbolic link is a symbolic link in the checkout, to the same target"
  (fn [ctx]
    (let [l (fs/path (checkout ctx) "test/link.txt")]
      (assert (fs/sym-link? l))
      (assert (= "notes.txt" (str (fs/read-link l)))))
    ctx))

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx)]
      (k/write-files! (room ctx) {"dhf/harness/config.edn" (k/pr-edn (assoc k/default-config :verify [{:kind :tests :command ["sh" "-c" "exit 1"]}]))
                                  k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "A scenario") (k/sc 2 "Added")]})})
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (k/git (:dir ctx) "show" "spec-3:dhf/harness/config.edn") (str/trim (slurp (fs/file (checkout ctx) "dhf/harness/config.edn")))))
    (assert (= 1 (get-in (k/edn-out ctx) [:verify :checks 0 :scenarios])))
    ctx))

;; ---------------------------------------------------------------------------
;; The tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx]
    (-> (k/verifiable! ctx)
        (checks! [{:kind :tests :command ["sh" "-c" "printf '%s' \"$RESULTS_DIR\" > results-dir.txt"]}])
        verify)))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (assert (= (str (fs/path (:dir ctx) "dhf/cycles/03/results")) (slurp (fs/file (checkout ctx) "results-dir.txt"))))
    ctx))

(defstep #"the :tests check's command exits 0"
  (fn [ctx] (k/verifiable! ctx)))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] ctx))

(defstep #"the tests check passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "tests"))))
    (assert (seq (fs/list-dir (file ctx "dhf/cycles/03/results"))))
    ctx))

(defstep #"the :tests check's command exits 1"
  (fn [ctx] (-> (k/verifiable! ctx) (checks! [{:kind :tests :command ["sh" "-c" "echo the build broke; exit 1"]}]))))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "test command failed" "1")))

(defstep #"the test command's output is in dhf/cycles/03/test.log, which the refusal names"
  (fn [ctx]
    (assert (str/includes? (text ctx "dhf/cycles/03/test.log") "the build broke"))
    (k/err-line-with ctx "test command failed" "dhf/cycles/03/test.log")))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (-> (k/verifiable! ctx) (checks! [{:kind :tests :command ["sh" "-c" "exit 0"]}]))))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "no results is not a run")))

(defn twelve!
  "A verifiable project of spec-0001 and spec-0012."
  [ctx & [opts]]
  (k/verifiable! ctx (merge {:features {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                        :scenarios [(k/sc 1 "One")
                                                                                    (k/sc 12 "Twelve" ["Given a step" "When it runs" "Then it does not hold"])]})}
                             :extra {"dhf/spec/ids.edn" "{:issued 12}\n"}}
                            opts)))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx] (twelve! ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (checks! ctx [{:kind :tests :command (tests-then "cd \"$RESULTS_DIR\" && rm -f $(grep -l '\"spec-0012\"' *-result.json)")}])))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0012")))

(def failing-twelve
  (str "(require '[rdm-spec.kernel.runner :refer [defstep]])\n"
       "(defstep #\"(?!it does not hold$).*\" (fn [ctx] ctx))\n"
       "(defstep #\"it does not hold\" (fn [ctx] (assert false \"it does not hold\") ctx))\n"))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx]
    (let [ctx (twelve! ctx)]
      (k/write-files! (room ctx) {"test/steps/all.clj" failing-twelve})
      (checks! ctx [{:kind :tests :command (tests-then "exit 0")}]))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0012" "failed")))

;; ---------------------------------------------------------------------------
;; Verify records what it verified

(defn tree-hash
  "The tree hash of the room's implementation, computed apart: its files in a
  fresh repository of their own."
  [ctx]
  (let [d (k/path-in ctx "tree-hash")]
    (k/git-init! d)
    (fs/copy-tree (fs/path (room ctx) "test") (fs/path d "test"))
    (k/git d "add" "-A")
    (k/git d "write-tree")))

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx)
          ctx (checks! ctx [{:kind :tests :command (k/tests-command)} ok-runs])]
      (assoc ctx ::h (tree-hash ctx)))))

(defstep #"dhf/cycles/03/ratified.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (ratified ctx)]
      (assert (= #{:cut :room-tree :outcome :checkout :checks} (set (keys r))) (pr-str r))
      (assert (= ["spec-3" (::h ctx) :passed "dhf/cycles/03/build"] [(:cut r) (:room-tree r) (:outcome r) (:checkout r)]) (pr-str r))
      (assert (= (out-checks ctx) (:checks r)))
      ctx)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx] (assert (fs/exists? (fs/path (checkout ctx) "dhf/harness/config.edn"))) ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx]
    (-> (k/verifiable! ctx) (checks! [{:kind :tests :command ["sh" "-c" "exit 1"]}]) verify (k/check-exit 1))))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (let [r (ratified ctx)]
      (assert (= :refused (:outcome r)) (pr-str r))
      (assert (str/includes? (str (:reason r)) "test command failed") (pr-str r))
      ctx)))

;; ---------------------------------------------------------------------------
;; Declared checks

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (-> (k/verifiable! ctx) (checks! [{:kind :tests :command (k/tests-command)} ok-runs]))))

(defstep #"both pass"
  (fn [ctx] ctx))

(defn- seconds? [x] (and (number? x) (>= x 0)))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .+"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [v (:verify (k/edn-out ctx))
          [t r] (:checks v)]
      (assert (= #{:verify} (set (keys (k/edn-out ctx)))))
      (assert (= {:cycle "03" :cut "spec-3" :outcome :passed} (dissoc v :checks)) (pr-str v))
      (assert (= {:name "tests" :kind :tests :outcome :passed :scenarios 1} (dissoc t :seconds)) (pr-str t))
      (assert (seconds? (:seconds t)))
      (assert (= {:name "answers" :kind :runs :outcome :passed :budget 30} (dissoc r :seconds :margin)) (pr-str r))
      (assert (seconds? (:seconds r)))
      (assert (< (Math/abs (- (:margin r) (- 30 (:seconds r)))) 0.011) (pr-str r))
      ctx)))

(defn port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn http-app
  "A command that serves HTTP on {port} after waiting `after` seconds."
  [after]
  ["sh" "-c" (str "sleep " after "; exec python3 -m http.server {port} --bind 127.0.0.1")])

(defn serves [p & {:keys [start seconds polls probe probe-seconds]}]
  (cond-> {:kind :serves :name "app" :start (or start (http-app 0)) :port p
           :budget {:seconds (or seconds 10) :polls (or polls 20)}
           :probe (or probe ["sh" "-c" "exit 0"])}
    probe-seconds (assoc :probe-seconds probe-seconds)))

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx] (-> (k/verifiable! ctx) (checks! [{:kind :tests :command ["sh" "-c" "exit 1"]} (serves (port)) ok-runs]))))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= [:failed :not-run :not-run] (map :outcome (out-checks ctx))) (:out ctx))
    ctx))

(def bad-verify
  {"declares no :tests check" [[ok-runs] "verified by its tests first"]
   "declares a check of the kind :renders" [[{:kind :tests :command ["true"]} {:kind :renders :name "r"}] ":renders is not a kind of check"]
   "declares a :serves check whose probe is under src/" [[{:kind :tests :command ["true"]} (serves 8000 :probe ["sh" "src/probe.sh"])] "the probe is in the implementation"]
   "is absent" [nil "declares no :verify"]
   "declares a :tests check with no command" [[{:kind :tests}] "names no command"]})

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx is]
    (let [[checks why] (bad-verify is)
          ctx (k/verifiable! ctx {:config {:implementation ["src" "test"]}})
          ctx (if checks (checks! ctx checks) (recut! ctx dissoc :verify))]
      (k/names+ ctx why))))

(defn ran-nothing [ctx]
  (assert (not (fs/exists? (file ctx "dhf/cycles/03/test.log"))) "a check ran")
  (assert (not (fs/exists? (file ctx "dhf/cycles/03/ratified.edn"))) "ratified.edn was written")
  ctx)

(defstep #"it exits 2, says .+, and runs no check"
  (fn [ctx]
    (k/check-exit ctx 2)
    (apply k/err-line-with ctx "cannot-run:" (:names ctx))
    (ran-nothing ctx)))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx] (-> (k/verifiable! ctx) (checks! [{:kind :tests :command (k/tests-command)} (assoc ok-runs :command ["no-such-command-x"])]))))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "answers" "no-such-command-x")))

;; ---------------------------------------------------------------------------
;; :serves

(defn- running? [pattern]
  (first (filter #(and (str/includes? % pattern) (not (str/includes? % "<defunct>")))
                 (str/split-lines (:out (k/sh "." {} "ps" "-eo" "pid,ppid,stat,args"))))))

(defn serves! [ctx check]
  (-> (k/verifiable! ctx) (checks! [{:kind :tests :command (k/tests-command)} check]) (assoc ::port (:port check))))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx] (serves! ctx (serves (port) :start (http-app 0.75)))))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [c (check-named ctx "app")]
      (assert (= [:passed 2 20 18] ((juxt :outcome :answered-on-poll :polls :margin) c)) (pr-str c))
      ctx)))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (Thread/sleep 300)
    (assert (not (running? (str "http.server " (::port ctx)))) "the app still runs")
    (doseq [m (::markers ctx)] (let [r (running? m)] (assert (not r) (str m " still runs: " r))))
    ctx))

(defstep #"a :serves check whose app answers on port P, with :probe-seconds 20"
  (fn [ctx]
    (serves! ctx (serves (port) :probe-seconds 20
                         :probe ["sh" "-c" "printf '%s' {url} > probe-url.txt; pwd > probe-cwd.txt"]))))

(defstep #"its probe runs in the checkout with \{url\} filled in as \"http://127.0.0.1:P/\", and may run for 20 seconds after the app answered"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (str "http://127.0.0.1:" (::port ctx) "/") (slurp (fs/file (checkout ctx) "probe-url.txt"))))
    (assert (= (str (fs/canonicalize (checkout ctx))) (str/trim (slurp (fs/file (checkout ctx) "probe-cwd.txt")))))
    (assert (= 20 (:probe-seconds (check-named ctx "app"))))
    ctx))

(defstep #"a :serves check with no :probe-seconds gives its probe 30 seconds"
  (fn [ctx]
    (let [r (-> ctx (checks! [{:kind :tests :command (k/tests-command)} (serves (port))]) verify)]
      (k/check-exit r 0)
      (assert (= 30 (:probe-seconds (check-named r "app")))))
    ctx))

(defstep #"a :serves check whose probe, with :probe-seconds 2, prints \"probing\" and then sleeps for 10 seconds"
  (fn [ctx] (-> (serves! ctx (serves (port) :probe-seconds 2 :probe ["sh" "-c" "echo probing; sleep 10.29"]))
                (assoc ::markers ["sleep 10.29"]))))

(defstep #"it exits 1 within 6 seconds, the check is :failed, and it says the probe timed out after 2 seconds and relays \"probing\""
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "app"))))
    (k/err-line-with ctx "probe" "timed out after 2 seconds" "probing")
    ;; the tests check comes first; the probe's part is what is timed
    (assert (< (:seconds (check-named ctx "app")) 6) (pr-str (check-named ctx "app")))
    ctx))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx] (-> (serves! ctx (serves (port) :start ["sh" "-c" "exec sleep 10.31"])) (assoc ::markers ["sleep 10.31"]))))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (\d+) seconds"
  (fn [ctx n]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "app"))))
    (k/err-line-with ctx "no answer came within" (str n " seconds"))))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [s (java.net.ServerSocket. 0)
          ctx (serves! ctx (serves (.getLocalPort s) :start ["sh" "-c" "echo started > app-started.txt; exec sleep 5"]))]
      (k/write-files! (room ctx) {})
      (rdm-spec.kernel.runner/cleanup! #(.close s))
      (assoc ctx ::held s))))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" (str (::port ctx)))
    (assert (not (fs/exists? (fs/path (checkout ctx) "app-started.txt"))))
    ctx))

(defstep #"a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain"
  (fn [ctx]
    (let [p (port)
          srv (java.net.ServerSocket. p 50 (java.net.InetAddress/getByName "127.0.0.1"))
          accept (future (with-open [c (.accept srv)] (.close c)))]
      (with-open [c (java.net.Socket. "127.0.0.1" (int p))] (.getInputStream c))
      @accept
      (.close srv)
      (serves! ctx (serves p)))))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx] (k/check-exit ctx 0) (assert (= :passed (:outcome (check-named ctx "app")))) ctx))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx] (serves! ctx (serves (port) :probe ["sh" "-c" "echo not this application; exit 1"]))))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "app"))))
    (k/err-line-with ctx "not this application")))

(def silent-listener
  "Listens on {port}, accepts, and never answers."
  ["python3" "-c" "import socket,time,sys\ns=socket.socket()\ns.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)\ns.bind(('127.0.0.1',int(sys.argv[1])))\ns.listen(50)\ncs=[]\nwhile True:\n  c,a=s.accept(); cs.append(c)\n" "{port}"])

(defstep #"a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
  (fn [ctx] (serves! ctx (serves (port) :seconds 2 :polls 4 :start silent-listener))))

;; ---------------------------------------------------------------------------
;; :runs

(def runs-cases
  {"the exit 0" {:expect {:exit 0}}
   "standard output starting \"{:help\"" {:expect {:exit 0 :stdout-starts "{:help"}}
   "an answer within 10 seconds" {:budget {:seconds 10}}})

(def doings
  {"exits 3" ["sh" "-c" "exit 3"]
   "prints \"usage\" and exits 0" ["sh" "-c" "echo usage; echo more"]
   "is still running after 10 seconds" ["sh" "-c" "sleep 14.17"]})

(defstep #"a :runs check expecting (the exit 0|standard output starting \"\{:help\"|an answer within 10 seconds)"
  (fn [ctx expected] (assoc (k/verifiable! ctx) ::runs (merge {:kind :runs :name "answers" :expect {:exit 0} :budget {:seconds 30}}
                                                                (runs-cases expected)))))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (checks! ctx [{:kind :tests :command (k/tests-command)} (assoc (::runs ctx) :command (doings does))])))

(defstep #"it exits 1, the check is :failed, and it names (the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx difference]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "answers"))))
    (case difference
      "the exit 3 where 0 was expected" (k/err-line-with ctx "answers" "exit" "3" "0")
      "the first line it printed" (k/err-line-with ctx "answers" "usage")
      (k/err-line-with ctx "answers" "10 seconds"))
    (assert (not (running? "sleep 14.17")))
    ctx))

;; ---------------------------------------------------------------------------
;; :plugin

(def judges
  {"exits 0" "exit 0"
   "writes \"src/app.clj:9: form-missing: …\" on its error stream and exits 1" "echo 'src/app.clj:9: form-missing: …' >&2; exit 1"
   "exits 2" "echo 'cannot judge' >&2; exit 2"})

(defn judge! [ctx script & [extra-checks]]
  (let [ctx (k/verifiable! ctx {:config {:plugins [{:name "judge" :answers :Check :command ["sh" "-c" script]}]}})]
    (checks! ctx (into [{:kind :tests :command (k/tests-command)} {:kind :plugin :name "judge"}] extra-checks))))

(defstep #"a configuration whose :verify declares a :tests check, then \{:kind :plugin :name \"judge\"\}, and whose :plugins has \"judge\" answering :Check"
  (fn [ctx] (k/verifiable! ctx)))

(defstep #"the tests check passes, and the plugin (.+)"
  (fn [ctx answers]
    (-> (judge! ctx (or (judges answers) (fail! answers))) (assoc ::judge answers))))

(defstep #"it (exits 0|exits 1, relaying the finding unchanged|exits 2, naming the plugin), and the check \"judge\" is (:\w+)"
  (fn [ctx exits outcome]
    (k/check-exit ctx (parse-long (subs exits 6 7)))
    (when (str/includes? exits "relaying") (assert (some #{"src/app.clj:9: form-missing: …"} (k/err-lines ctx)) (:err ctx)))
    (when (str/includes? exits "naming") (k/err-line-with ctx "cannot-run:" "judge"))
    (assert (= (keyword (subs outcome 1)) (:outcome (check-named ctx "judge"))) (pr-str (ratified ctx)))
    ctx))

(def acceptance
  "Runs the scenarios into the :results directory its request names."
  (str "req=$(cat); dir=$(echo \"$req\" | sed -e 's/.*:results \"\\([^\"]*\\)\".*/\\1/'); "
       "echo \"$req\" > ../request.edn; "
       "RESULTS_DIR=\"../../../../$dir\" $RDM_SPEC run > /dev/null"))

(defn acceptance! [ctx script]
  (let [ctx (k/verifiable! ctx {:config {:plugins [{:name "acceptance" :answers :Check :command ["sh" "-c" script]}]}})]
    (checks! ctx [{:kind :tests :command (k/tests-command)} {:kind :plugin :name "acceptance"}])))

(defstep #"a :plugin check \"acceptance\" whose plugin writes Allure results of every scenario into the :results directory its request names"
  (fn [ctx] (acceptance! ctx acceptance)))

(defstep #"its request held :results \"dhf/cycles/03/results/acceptance\", and each result there was held to the :allure mapping as the tests check's are"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "dhf/cycles/03/results/acceptance" (:results (edn/read-string (text ctx "dhf/cycles/03/request.edn")))))
    (assert (seq (fs/glob (file ctx "dhf/cycles/03/results/acceptance") "*-result.json")))
    (assert (= :passed (:outcome (check-named ctx "acceptance"))))
    ;; a result that breaks the mapping fails it
    (let [ctx (acceptance! ctx (str acceptance "; cd \"../../../../$dir\" && sed -i -e 's/\"story\"/\"storyx\"/' *-result.json"))]
      (k/write-files! (:dir ctx) {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                  :rules [{:name "A rule" :scenarios [(k/sc 1 "A scenario")]}]})})
      (k/tag-cut! ctx "spec-3")
      (let [v (verify ctx)]
        (k/check-exit v 1)
        (k/err-line-with v "mapping-differs: story" "spec-0001")))
    ctx))

(defstep #"a result that did not pass, or a scenario with no result, fails the check \"acceptance\", naming the scenario"
  (fn [ctx]
    (let [ctx (acceptance! ctx (str acceptance "; cd \"../../../../$dir\" && rm -f *-result.json && echo '{\"status\":\"failed\",\"labels\":[{\"name\":\"spec\",\"value\":\"spec-0001\"}]}' > x-result.json"))
          v (verify ctx)]
      (k/check-exit v 1)
      (assert (= :failed (:outcome (check-named v "acceptance"))))
      (k/err-line-with v "spec-0001"))
    ctx))

(defstep #"a plugin that writes no results is judged by its exit alone, as before"
  (fn [ctx]
    (let [v (verify (acceptance! ctx "cat > /dev/null; exit 0"))]
      (k/check-exit v 0)
      (assert (= :passed (:outcome (check-named v "acceptance")))))
    ctx))

(defstep #"a :plugin check whose plugin writes what it is given to a file"
  (fn [ctx] (judge! ctx "cat > got.edn; pwd > cwd.txt")))

(defstep #"the plugin ran in the checkout, dhf/cycles/03/build, and its standard input was \{:request :Check :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\"\}"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [got (edn/read-string (slurp (fs/file (checkout ctx) "got.edn")))]
      (assert (= (str (fs/canonicalize (checkout ctx))) (str/trim (slurp (fs/file (checkout ctx) "cwd.txt")))))
      (assert (= {:request :Check :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build"} (dissoc got :results)) (pr-str got)))
    ctx))

(defstep #"a configuration whose :verify declares \{:kind :plugin :name \"judge\"\}, and whose :plugins has no \"judge\" answering :Check"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx {:config {:plugins [{:name "judge" :answers :Gate :command ["sh" "-c" "exit 0"]}]}})]
      (-> (checks! ctx [{:kind :tests :command (k/tests-command)} {:kind :plugin :name "judge"}])
          (k/names+ "judge" "no plugin answering :Check")))))


;; ---------------------------------------------------------------------------
;; The Allure mapping

(def mapping-project
  "@spec-0003 under the rule \"Every view is described\" of architecture/lint;
  the other scenarios @contract."
  {:model-spec {:contexts [["architecture" ["lint"]]] :kernel ["core"]}
   :features {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(assoc (k/sc 1 "A scenario") :tags ["spec-0001" "contract"])]})
              "dhf/spec/architecture/lint.feature"
              (k/feature-text {:name "Lint" :tags ["UN-001"]
                               :rules [{:name "Every view is described"
                                        :scenarios [(k/sc 3 "Lint names the view" ["Given a view" "When it is linted" "Then it is named"])]}]})}
   :extra {"dhf/spec/ids.edn" "{:issued 3}\n"}})

(def mutations
  {"a second step reworded from the scenario's line" "(update r \"steps\" (fn [ss] (assoc-in (vec ss) [1 \"name\"] \"When it is checked\")))"
   "one step fewer than the scenario's lines" "(update r \"steps\" (fn [ss] (vec (butlast ss))))"
   "the story \"Something else\", not the scenario's rule" "(update r \"labels\" (fn [ls] (mapv #(if (= \"story\" (get % \"name\")) (assoc % \"value\" \"Something else\") %) ls)))"
   "the epic of another bounded context" "(update r \"labels\" (fn [ls] (mapv #(if (= \"epic\" (get % \"name\")) (assoc % \"value\" \"kernel\") %) ls)))"
   "no issue link to UN-001, the need the scenario traces to" "(assoc r \"links\" [])"
   "no browser-trace attachment, where :evidence requires one for @e2e" "r"})

(defn mutator!
  "A script, outside the project, that rewrites spec-0003's results."
  [ctx form]
  (let [f (k/path-in ctx "mutate.clj")]
    (spit f (str "(require '[cheshire.core :as json] '[babashka.fs :as fs])\n"
                 "(doseq [f (fs/glob (first *command-line-args*) \"*-result.json\")\n"
                 "        :let [r (json/parse-string (slurp (str f)))]\n"
                 "        :when (some #(= {\"name\" \"spec\" \"value\" \"spec-0003\"} %) (get r \"labels\"))]\n"
                 "  (spit (str f) (json/generate-string " form " {:pretty true})))\n"))
    f))

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (k/verifiable! ctx mapping-project)))

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [form (or (mutations wrong) (fail! wrong))
          m (mutator! ctx form)
          ctx (if (str/includes? wrong "browser-trace") (recut! ctx assoc :allure {:evidence {:e2e ["browser-trace"]}}) ctx)]
      (checks! ctx [{:kind :tests :command (tests-then (str "bb " m " \"$RESULTS_DIR\""))}]))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (.+) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "tests"))))
    (let [l (first (filter #(and (str/includes? % (str ": " finding ":")) (str/includes? % "spec-0003")) (k/err-lines ctx)))]
      (when-not l (fail! (str "no line names " finding " and spec-0003:\n" (:err ctx))))
      (assert (re-find #"^dhf/cycles/03/results/[^ :]+-result\.json:\d+: " l) l))
    ctx))

(defstep #"a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx (assoc-in mapping-project [:features "dhf/spec/architecture/lint.feature"]
                                           (k/feature-text {:name "Lint" :tags ["UN-001"]
                                                            :scenarios [(assoc (k/sc 3 "Planned") :tags ["spec-0003" "e2e" "wip"])]})))]
      (recut! ctx assoc :allure {:evidence {:e2e ["browser-trace"]}}))))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment"
  (fn [ctx] ctx))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "tests"))))
    (assert (not (str/includes? (str (:err ctx)) "spec-0003")) (:err ctx))
    ctx))

(def bad-mappings
  {"maps :epic to :aggregate" [{:epic :aggregate} ":aggregate is not an element of the spec"]
   "maps :colour to :context" [{:colour :context} ":colour is not an element of an Allure result"]
   "maps no label \"spec\"" [{:labels {"component" :component}} "the label spec"]})

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [ctx is]
    (let [[m why] (bad-mappings is)]
      (-> (k/verifiable! ctx) (recut! assoc :allure m) (k/names+ why)))))

(defstep #"a configuration that declares no :allure"
  (fn [ctx]
    (let [ctx (k/verifiable! ctx mapping-project)]
      (assert (not (str/includes? (text ctx "dhf/harness/config.edn") ":allure")))
      ctx)))

(defstep #"each result is held to \{.+\}, N the configuration's :needs"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [m (mutator! ctx (mutations "the story \"Something else\", not the scenario's rule"))
          r (-> ctx (checks! [{:kind :tests :command (tests-then (str "bb " m " \"$RESULTS_DIR\""))}]) verify)]
      (k/check-exit r 1)
      (k/err-line-with r "mapping-differs: story" "spec-0003"))
    ;; N is the configuration's :needs
    (fs/move (file ctx "dhf/spec/needs.edn") (file ctx "dhf/needs.edn"))
    (let [m (mutator! ctx "(assoc r \"links\" [{\"type\" \"issue\" \"name\" \"UN-001\" \"url\" \"dhf/spec/needs.edn#UN-001\"}])")
          r (-> ctx (recut! assoc :needs "dhf/needs.edn"
                            :clean-room (update (:clean-room k/default-config) :keep conj "dhf/needs.edn"))
                (checks! [{:kind :tests :command (tests-then (str "bb " m " \"$RESULTS_DIR\""))}]) verify)]
      (k/check-exit r 1)
      (k/err-line-with r "mapping-differs: links" "spec-0003"))
    (fs/move (file ctx "dhf/needs.edn") (file ctx "dhf/spec/needs.edn"))
    (-> ctx (recut! dissoc :needs) (checks! [{:kind :tests :command (k/tests-command)}]))))

(defstep #"N is dhf/spec/needs.edn for a configuration that names no :needs"
  (fn [ctx]
    (let [r (verify ctx)]
      (k/check-exit r 0)
      (assert (some #(str/includes? % "\"url\":\"dhf/spec/needs.edn#UN-001\"")
                    (map #(slurp (str %)) (fs/glob (file ctx "dhf/cycles/03/results") "*-result.json")))))
    ctx))
