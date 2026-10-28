(ns steps.verification-verify
  "Steps of the component Verify (dhf/spec/06-regenerate/verify.feature).
  Each scenario makes a project cut at spec-3 with a cycle 03 and a room;
  its tests run the scenarios with rdm-spec run unless the scenario's
  checks say otherwise."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(def run-line (str (str/join " " (k/rdm-command-line)) " run"))

(defn tests-check [script] {:kind :tests :command ["sh" "tests.sh"]})

(defn vproject!
  "A cut project whose :tests check runs tests.sh (SCRIPT, by default the
  scenarios through rdm-spec run), then the checks in MORE."
  [ctx & [{:keys [script more features model cfg-fn files needs]}]]
  (k/verify-project! ctx {:features features :model model :needs needs
                          :files (merge {"tests.sh" (str "#!/bin/sh\n" (or script (str "exec " run-line)) "\n")} files)
                          :cfg-fn (fn [c] (cond-> (-> c (assoc :verify (into [(tests-check script)] more))
                                                      (update-in [:clean-room :keep] (fnil into []) ["tests.sh" "dhf/steps" "probe.sh" "app.py" "mutate.clj"]))
                                            cfg-fn cfg-fn))}))

(defn verify! [ctx] (k/run-rdm ctx "verify ROOM 03"))

(defn checks [ctx] (get-in ctx [:result :verify :checks]))
(defn check-named [ctx n] (first (filter #(= n (:name %)) (checks ctx))))
(defn ratified [ctx] (some-> (k/slurp-rel ctx "dhf/cycles/03/ratified.edn") edn/read-string))
(defn build [ctx rel] (k/path ctx (str "dhf/cycles/03/build/" rel)))

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn running? [pattern]
  (zero? (:exit @(p/process ["pgrep" "-f" pattern] {:out :string :err :string}))))

;; ------------------------------------------------------------------ the cut alone

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (fs/exists? (build ctx "dhf/spec/demo/thing.feature")) "the checkout holds no spec")
    (k/check! (not (fs/exists? (build ctx ".git"))) "the checkout holds history")
    ctx))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [ctx] (let [ctx (vproject! ctx)] (k/delete! ctx "dhf/cycles/03/cycle.edn"))))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "dhf/cycles/03/cycle.edn") ctx))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [ctx] (vproject! ctx {:files {"src/rdm_spec/old.clj" "(ns old)\n"}})))

(defstep #"the checkout it verifies holds no src/rdm_spec/old.clj"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not (fs/exists? (build ctx "src/rdm_spec/old.clj"))) "the checkout holds src/rdm_spec/old.clj")
    ctx))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [room (get-in ctx [:ph "ROOM"])
          files (fn [d] (set (for [f (fs/glob (str d "/src") "**") :when (fs/regular-file? f)] (str (fs/relativize d f)))))]
      (k/check! (= (files room) (files (k/path ctx "dhf/cycles/03/build"))) "the checkout's src differs from the room's")
      ctx)))

(defstep #"a room whose implementation holds a file its .gitignore excludes, a directory it excludes, and a symbolic link to a file beside it"
  (fn [ctx]
    (let [ctx (vproject! ctx)
          room (get-in ctx [:ph "ROOM"])
          r (assoc ctx :dir room)]
      (k/write! r ".gitignore" "*.log\nnode_modules/\n")
      (k/write! r "src/kernel/debug.log" "noise\n")
      (k/write! r "src/node_modules/dep/index.js" "module.exports = 1\n")
      (fs/create-sym-link (str room "/src/kernel/link.clj") "thing.clj")
      ctx)))

(defstep #"the checkout holds neither the excluded file nor the excluded directory"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not (fs/exists? (build ctx "src/kernel/debug.log"))) "the excluded file reached the checkout")
    (k/check! (not (fs/exists? (build ctx "src/node_modules"))) "the excluded directory reached the checkout")
    ctx))

(defstep #"the symbolic link is a symbolic link in the checkout, to the same target"
  (fn [ctx]
    (let [l (build ctx "src/kernel/link.clj")]
      (k/check! (and (fs/sym-link? l) (= "thing.clj" (str (fs/read-link l)))) "the link is not a link to thing.clj")
      ctx)))

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (vproject! ctx)
          r (assoc ctx :dir (get-in ctx [:ph "ROOM"]))]
      (k/write! r "dhf/harness/config.edn" (str (k/slurp-rel ctx "dhf/harness/config.edn") "\n;; the builder's\n"))
      (k/write-edn! r "dhf/harness/config.edn" (assoc (k/read-config ctx) :verify [{:kind :tests :command ["sh" "-c" "exit 0"]}]))
      (k/write! r "dhf/spec/demo/thing.feature" "@UN-001\nFeature: Edited by the builder\n")
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (k/slurp-rel ctx "dhf/harness/config.edn") (slurp (build ctx "dhf/harness/config.edn"))) "the configuration is not spec-3's")
    (k/check! (= (k/slurp-rel ctx "dhf/spec/demo/thing.feature") (slurp (build ctx "dhf/spec/demo/thing.feature"))) "the spec is not spec-3's")
    (k/check! (= 1 (get-in (check-named ctx "tests") [:scenarios])) "the builder's configuration ran")
    ctx))

;; ------------------------------------------------------------------ the tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx]
    (let [ctx (vproject! ctx {:script (str "echo \"$RESULTS_DIR\" > \"$RESULTS_DIR.seen\"\nexec " run-line)})]
      (verify! ctx))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [expected (k/path ctx "dhf/cycles/03/results")]
      (k/check! (= expected (str/trim (str (k/slurp-rel ctx "dhf/cycles/03/results.seen")))) "RESULTS_DIR was " (k/slurp-rel ctx "dhf/cycles/03/results.seen"))
      ctx)))

(defstep #"the :tests check's command exits 0"
  (fn [ctx] (if (:dir ctx) ctx (vproject! ctx))))

(defstep #"it wrote Allure results into dhf/cycles/03/results" (fn [ctx] ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] ctx))

(defstep #"the tests check passes"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= :passed (:outcome (check-named ctx "tests"))) "the tests check is " (pr-str (check-named ctx "tests")))
    ctx))

(defstep #"the :tests check's command exits 1"
  (fn [ctx] (vproject! ctx {:script "echo 'the tests ran and failed'\nexit 1"})))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "test command failed" "exit status 1") ctx))

(defstep #"the test command's output is in dhf/cycles/03/test.log, which the refusal names"
  (fn [ctx]
    (k/check! (str/includes? (str (k/slurp-rel ctx "dhf/cycles/03/test.log")) "the tests ran and failed") "test.log does not hold the output")
    (k/err-line-has! ctx "dhf/cycles/03/test.log")
    ctx))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (vproject! ctx {:script "exit 0"})))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (k/exits! ctx 1) (k/err-has! ctx "a green exit with no results is not a run") ctx))

(def two-scenarios
  {"dhf/spec/demo/thing.feature" (k/feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "One"} {:id "spec-0012" :title "Twelve"}])})

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx]
    (vproject! ctx {:features two-scenarios
                    :script (str run-line "\nfor f in \"$RESULTS_DIR\"/*-result.json; do grep -q '\"spec-0012\"' \"$f\" && rm \"$f\"; done\nexit 0")})))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012" (fn [ctx] ctx))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx] (k/exits! ctx 1) (k/err-has! ctx "spec-0012") ctx))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx]
    (vproject! ctx {:features two-scenarios
                    :script (str run-line "\nfor f in \"$RESULTS_DIR\"/*-result.json; do grep -q '\"spec-0012\"' \"$f\" && "
                                 "sed -i 's/\"status\":\"passed\"/\"status\":\"failed\"/g' \"$f\"; done\nexit 0")})))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "spec-0012" "failed") ctx))

;; ------------------------------------------------------------------ the record

(def answers-check {:kind :runs :name "answers" :command ["sh" "-c" "echo '{:help []}'"]
                    :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defn room-tree [ctx]
  (let [room (get-in ctx [:ph "ROOM"])
        gd (k/scratch! "tree-")
        g (fn [& args] (str/trim (:out (apply k/sh ctx room "git" (str "--git-dir=" gd) (str "--work-tree=" room) args))))]
    (k/sh ctx room "git" "init" "-q" "--bare" gd)
    (g "add" "-A" "--" "src")
    (g "write-tree")))

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx] (let [ctx (vproject! ctx {:more [answers-check]})] (assoc ctx ::h (room-tree ctx)))))

(defstep #"dhf/cycles/03/ratified.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (ratified ctx)]
      (k/check! (= {:cut "spec-3" :room-tree (::h ctx) :outcome :passed :checkout "dhf/cycles/03/build" :checks (checks ctx)} r)
                "ratified.edn holds " (pr-str r) " where H is " (::h ctx))
      ctx)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx] (k/check! (fs/exists? (build ctx "dhf/harness/config.edn")) "dhf/cycles/03/build is not the checkout") ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx] (verify! (vproject! ctx {:script "exit 1"}))))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [r (ratified ctx)]
      (k/check! (= :refused (:outcome r)) "ratified.edn holds " (pr-str r))
      (k/check! (str/includes? (str (:reason r)) "test command failed") "the reason is " (pr-str (:reason r)))
      ctx)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx] (k/exits! ctx 1) (k/err-line-has! ctx "dhf/spec/demo/thing.feature:5") ctx))

;; ------------------------------------------------------------------ the checks

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (vproject! ctx {:more [answers-check]})))

(defstep #"both pass" (fn [ctx] ctx))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify \{:cycle \"03\" :cut \"spec-3\" :outcome :passed :checks .*"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [v (:verify (:result ctx))
          [t r] (:checks v)]
      (k/check! (= #{:verify} (set (keys (:result ctx)))) "standard output holds " (pr-str (keys (:result ctx))))
      (k/check! (= {:cycle "03" :cut "spec-3" :outcome :passed} (dissoc v :checks)) "verify reports " (pr-str v))
      (k/check! (and (= {:name "tests" :kind :tests :outcome :passed :scenarios 1} (dissoc t :seconds)) (number? (:seconds t)))
                "the tests check is " (pr-str t))
      (k/check! (and (= {:name "answers" :kind :runs :outcome :passed :budget 30} (dissoc r :seconds :margin))
                     (number? (:seconds r)) (number? (:margin r)) (<= 29 (:margin r) 30)
                     (< (Math/abs (- (:margin r) (- 30 (:seconds r)))) 0.011))
                "the runs check is " (pr-str r))
      ctx)))

(defn serves-check [port & [m]]
  (merge {:kind :serves :name "app" :start ["python3" "app.py" "{port}"] :port port :budget {:seconds 10 :polls 20}
          :probe ["sh" "probe.sh" "{url}"]}
         m))

(def app-py
  "import sys, time, http.server
delay = float(sys.argv[2]) if len(sys.argv) > 2 else 0
time.sleep(delay)
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers(); self.wfile.write(b'ok')
    def log_message(self, *a): pass
http.server.HTTPServer.allow_reuse_address = True
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
")

(defn serves-project! [ctx port & [{:keys [check probe script more]}]]
  (vproject! ctx {:script script
                  :more (concat [(serves-check port check)] more)
                  :files {"app.py" app-py "probe.sh" (str "#!/bin/sh\n" (or probe "exit 0") "\n")}}))

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx] (serves-project! ctx (free-port) {:script "exit 1" :more [answers-check]})))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (= [:failed :not-run :not-run] (map :outcome (checks ctx))) "the checks are " (pr-str (checks ctx)))
    (k/check! (= [:tests :serves :runs] (map :kind (checks ctx))) "the checks are " (pr-str (checks ctx)))
    ctx))

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx is]
    (let [ctx (vproject! ctx)]
      (k/config! ctx (fn [c]
                       (case is
                         "declares no :tests check" (assoc c :verify [answers-check])
                         "declares a check of the kind :renders" (update c :verify conj {:kind :renders :name "page"})
                         "declares a :serves check whose probe is under src/" (update c :verify conj (serves-check 8099 {:probe ["sh" "src/probe.sh" "{url}"]}))
                         "is absent" (dissoc c :verify)
                         "declares a :tests check with no command" (assoc c :verify [{:kind :tests}]))))
      (k/git! ctx "tag" "-d" "spec-3")
      (k/commit-tag! ctx "spec-3"))))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (k/exits! ctx 2)
    (k/err-has! ctx why)
    (k/check! (not (fs/exists? (k/path ctx "dhf/cycles/03/test.log"))) "a check ran")
    (k/check! (not (fs/exists? (k/path ctx "dhf/cycles/03/ratified.edn"))) "ratified.edn was written")
    ctx))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx] (vproject! ctx {:more [(assoc answers-check :command ["no-such-command-anywhere" "help"])]})))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (k/exits! ctx 2) (k/err-line-has! ctx "answers" "no-such-command-anywhere") ctx))

;; ------------------------------------------------------------------ :serves

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx]
    (let [port (free-port)]
      (-> (serves-project! ctx port {:check {:start ["python3" "app.py" "{port}" "0.75"]}})
          (assoc ::port port)))))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [c (check-named ctx "app")]
      (k/check! (= {:answered-on-poll 2 :polls 20 :margin 18 :outcome :passed} (select-keys c [:answered-on-poll :polls :margin :outcome]))
                "the check is " (pr-str c))
      ctx)))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (k/check! (not (running? (str "app.py " (::port ctx))))  "the app is still running")
    (k/check! (not (running? (str "sleep " (::marker ctx "none-was-started")))) "the probe is still running")
    ctx))

(defstep #"a :serves check whose app answers on port P, with :probe-seconds 20"
  (fn [ctx]
    (let [port (free-port)]
      (-> (serves-project! ctx port {:check {:probe-seconds 20} :probe "echo \"$1\" > probe.args\npwd > probe.pwd\nexit 0"})
          (assoc ::port port)))))

(defstep #"its probe runs in the checkout with \{url\} filled in as \"http://127.0.0.1:P/\", and may run for 20 seconds after the app answered"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (str "http://127.0.0.1:" (::port ctx) "/") (str/trim (slurp (build ctx "probe.args")))) "the probe was given " (slurp (build ctx "probe.args")))
    (k/check! (= (str (fs/canonicalize (k/path ctx "dhf/cycles/03/build"))) (str/trim (slurp (build ctx "probe.pwd")))) "the probe ran in " (slurp (build ctx "probe.pwd")))
    (k/check! (= 20 (:probe-seconds (check-named ctx "app"))) "the probe's limit is " (:probe-seconds (check-named ctx "app")))
    ctx))

(defstep #"a :serves check with no :probe-seconds gives its probe 30 seconds"
  (fn [ctx]
    (let [port (free-port)
          c (serves-project! (dissoc ctx :dir :base :ph) port)
          c (verify! c)]
      (k/check! (= 30 (:probe-seconds (check-named c "app"))) "the probe's limit is " (pr-str (check-named c "app")))
      ctx)))

(defstep #"a :serves check whose probe, with :probe-seconds 2, prints \"probing\" and then sleeps for 10 seconds"
  (fn [ctx]
    (let [port (free-port) marker (str "10." (rand-int 100000000))]
      (-> (serves-project! ctx port {:check {:probe-seconds 2} :probe (str "echo probing\nsleep " marker)})
          (assoc ::port port ::marker marker)))))

(defstep #"it exits 1 within 6 seconds, the check is :failed, and it says the probe timed out after 2 seconds and relays \"probing\""
  (fn [ctx]
    (let [t0 (System/currentTimeMillis)
          ctx (verify! ctx)
          secs (/ (- (System/currentTimeMillis) t0) 1000.0)]
      (k/exits! ctx 1)
      (k/check! (< secs 6) "it took " secs " seconds")
      (k/check! (= :failed (:outcome (check-named ctx "app"))) "the check is " (pr-str (check-named ctx "app")))
      (k/err-line-has! ctx "probe" "timed out after 2 seconds")
      (k/err-has! ctx "probing")
      ctx)))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx]
    (let [port (free-port)]
      (-> (serves-project! ctx port {:check {:start ["python3" "app.py" "{port}" "60"]}})
          (assoc ::port port)))))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (\d+) seconds"
  (fn [ctx n]
    (k/exits! ctx 1)
    (k/check! (= :failed (:outcome (check-named ctx "app"))) "the check is " (pr-str (check-named ctx "app")))
    (k/err-has! ctx (str "no answer came within " n " seconds"))
    ctx))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          port (.getLocalPort ss)
          ctx (serves-project! ctx port {:check {:start ["sh" "-c" "touch app.started; exec python3 app.py {port}"]}})]
      (assoc ctx ::held ss ::port port))))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (.close ^java.net.ServerSocket (::held ctx))
    (k/exits! ctx 2)
    (k/err-has! ctx (str (::port ctx)))
    (k/check! (not (fs/exists? (build ctx "app.started"))) "an app was started")
    ctx))

(defstep #"a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain"
  (fn [ctx]
    (let [port (free-port)
          ctx (serves-project! ctx port)
          proc (k/track-process! (p/process ["python3" (k/path ctx "app.py") (str port)] {:dir (:dir ctx) :env (k/base-env ctx)}))]
      (loop [i 0]
        (when (and (< i 50) (not (try (slurp (str "http://127.0.0.1:" port "/")) true (catch Exception _ false))))
          (Thread/sleep 100) (recur (inc i))))
      (p/destroy-tree proc)
      @proc
      (assoc ctx ::port port))))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= :passed (:outcome (check-named ctx "app"))) "the check is " (pr-str (check-named ctx "app")))
    ctx))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx]
    (let [port (free-port)]
      (-> (serves-project! ctx port {:probe "echo 'this is not the app'\nexit 1"}) (assoc ::port port)))))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (= :failed (:outcome (check-named ctx "app"))) "the check is " (pr-str (check-named ctx "app")))
    (k/err-has! ctx "this is not the app")
    ctx))

(def silent-py
  "import socket, sys, time
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', int(sys.argv[1]))); s.listen(5)
conns = []
while True:
    c, _ = s.accept(); conns.append(c)
")

(defstep #"a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
  (fn [ctx]
    (let [port (free-port)]
      (-> (vproject! ctx {:more [(serves-check port {:start ["python3" "silent.py" "{port}"] :budget {:seconds 2 :polls 4}})]
                          :files {"silent.py" silent-py "probe.sh" "exit 0\n"}
                          :cfg-fn #(update-in % [:clean-room :keep] conj "silent.py")})
          (assoc ::port port)))))

;; ------------------------------------------------------------------ :runs

(defstep #"a :runs check expecting (the exit 0|standard output starting \"\{:help\"|an answer within 10 seconds)"
  (fn [ctx expected] (assoc ctx ::expected expected)))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (let [cmd (case does
                "exits 3" ["sh" "-c" "exit 3"]
                "prints \"usage\" and exits 0" ["sh" "-c" "echo usage"]
                "is still running after 10 seconds" ["sh" "-c" "echo started; sleep 20"])
          budget (if (= does "is still running after 10 seconds") 10 30)]
      (vproject! ctx {:more [(assoc answers-check :command cmd :budget {:seconds budget})]}))))

(defstep #"it exits 1, the check is :failed, and it names (the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx difference]
    (k/exits! ctx 1)
    (k/check! (= :failed (:outcome (check-named ctx "answers"))) "the check is " (pr-str (check-named ctx "answers")))
    (case difference
      "the exit 3 where 0 was expected" (k/err-line-has! ctx "answers" "exit 3" "0 was expected")
      "the first line it printed" (k/err-line-has! ctx "answers" "usage")
      "the budget of 10 seconds" (k/err-line-has! ctx "answers" "10 seconds"))
    ctx))

;; ------------------------------------------------------------------ :plugin

(defn judge-project! [ctx body & [{:keys [name] :or {name "judge"}}]]
  (let [ctx (vproject! ctx {:more [{:kind :plugin :name name}]
                            :files {(str "dhf/harness/plugins/" name "/run.sh") (str "#!/bin/sh\n" body "\n")}
                            :cfg-fn #(assoc % :plugins [{:name name :answers :Check :command ["sh" (str "dhf/harness/plugins/" name "/run.sh")]}])})]
    ctx))

(def judge-answers
  {"exits 0" "exit 0"
   "writes \"src/app.clj:9: form-missing: …\" on its error stream and exits 1" "echo 'src/app.clj:9: form-missing: …' >&2\nexit 1"
   "exits 2" "echo 'cannot judge' >&2\nexit 2"})

(defstep #"a configuration whose :verify declares a :tests check, then \{:kind :plugin :name \"judge\"\}, and whose :plugins has \"judge\" answering :Check"
  (fn [ctx] (assoc ctx ::judge true)))

(defstep #"the tests check passes, and the plugin (.+)"
  (fn [ctx answers] (judge-project! ctx (judge-answers answers))))

(defstep #"it (exits 0|exits 1, relaying the finding unchanged|exits 2, naming the plugin), and the check \"judge\" is (:passed|:failed|:refused)"
  (fn [ctx exits outcome]
    (k/exits! ctx (parse-long (subs exits 6 7)))
    (when (str/includes? exits "relaying") (k/check! (some #{"src/app.clj:9: form-missing: …"} (k/err-lines ctx)) "the finding is not relayed"))
    (when (str/includes? exits "naming") (k/err-has! ctx "judge"))
    (let [c (or (check-named ctx "judge") (first (filter #(= "judge" (:name %)) (:checks (ratified ctx)))))]
      (k/check! (= (keyword (subs outcome 1)) (:outcome c)) "the check judge is " (pr-str c) "; " (k/shows ctx)))
    ctx))

(def acceptance-script
  (str "R=\"$(pwd)/../../../../dhf/cycles/03/results/acceptance\"\ncat > ../acceptance.in.edn\n"
       "if [ -f ../../../../no-results ]; then exit 0; fi\n"
       "RESULTS_DIR=\"$R\" " run-line " > /dev/null 2>&1\n"
       "if [ -f ../../../../fail-one ]; then for f in \"$R\"/*-result.json; do "
       "sed -i 's/\"status\":\"passed\"/\"status\":\"failed\"/g' \"$f\"; done; fi\nexit 0"))

(defstep #"a :plugin check \"acceptance\" whose plugin writes Allure results of every scenario into the :results directory its request names"
  (fn [ctx] (judge-project! ctx acceptance-script {:name "acceptance"})))

(defstep #"its request held :results \"dhf/cycles/03/results/acceptance\", and each result there was held to the :allure mapping as the tests check's are"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [m (edn/read-string (k/slurp-rel ctx "dhf/cycles/03/acceptance.in.edn"))]
      (k/check! (= "dhf/cycles/03/results/acceptance" (:results m)) "the request held " (pr-str m))
      (k/check! (seq (fs/glob (k/path ctx "dhf/cycles/03/results/acceptance") "*-result.json")) "no results were written")
      (k/check! (= :passed (:outcome (check-named ctx "acceptance"))) "the check is " (pr-str (check-named ctx "acceptance"))))
    ctx))

(defstep #"a result that did not pass, or a scenario with no result, fails the check \"acceptance\", naming the scenario"
  (fn [ctx]
    (k/write! ctx "fail-one" "")
    (let [r (verify! ctx)]
      (k/exits! r 1)
      (k/check! (= :failed (:outcome (check-named r "acceptance"))) "the check is " (pr-str (check-named r "acceptance")))
      (k/err-has! r "spec-0001"))
    ctx))

(defstep #"a plugin that writes no results is judged by its exit alone, as before"
  (fn [ctx]
    (k/delete! ctx "fail-one")
    (k/write! ctx "no-results" "")
    (let [r (verify! ctx)]
      (k/exits! r 0))
    ctx))

(defstep #"a :plugin check whose plugin writes what it is given to a file"
  (fn [ctx] (judge-project! ctx "cat > ../judge.in.edn\npwd > ../judge.pwd\nexit 0")))

(defstep #"the plugin ran in the checkout, dhf/cycles/03/build, and its standard input was \{:request :Check :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\"\}"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= (str (fs/canonicalize (k/path ctx "dhf/cycles/03/build"))) (str/trim (k/slurp-rel ctx "dhf/cycles/03/judge.pwd"))) "the plugin ran in " (k/slurp-rel ctx "dhf/cycles/03/judge.pwd"))
    (let [m (edn/read-string (k/slurp-rel ctx "dhf/cycles/03/judge.in.edn"))]
      ;; decision: the request also names the :results directory spec-0391 states
      (k/check! (= {:request :Check :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build"} (dissoc m :results)) "the plugin was given " (pr-str m)))
    ctx))

(defstep #"a configuration whose :verify declares \{:kind :plugin :name \"judge\"\}, and whose :plugins has no \"judge\" answering :Check"
  (fn [ctx] (vproject! ctx {:more [{:kind :plugin :name "judge"}]})))

;; ------------------------------------------------------------------ the mapping

(def mapped-feature
  {"dhf/spec/demo/thing.feature"
   "@UN-001\nFeature: Thing\n\n  Rule: The rule\n\n    @spec-0003 @e2e\n    Scenario: Three\n      Given a thing\n      When it runs\n      Then it holds\n"})

(def mapped-model (k/context-model [[:demo [[:thing "Thing"]]] [:other [[:part "Part" "other/part"]]]]))

(defn mutate-script [code]
  (str "(require '[cheshire.core :as json])\n"
       "(doseq [f (babashka.fs/glob (System/getenv \"RESULTS_DIR\") \"*-result.json\")]\n"
       "  (let [r (json/parse-string (slurp (str f)))]\n"
       "    (when (some #(= \"spec-0003\" (get % \"value\")) (get r \"labels\"))\n"
       "      (spit (str f) (json/generate-string (" code " r))))))\n"))

(def wrongs
  {"a second step reworded from the scenario's line" "(fn [r] (assoc-in r [\"steps\" 1 \"name\"] \"When it runs, reworded\"))"
   "one step fewer than the scenario's lines" "(fn [r] (update r \"steps\" (comp vec butlast)))"
   "the story \"Something else\", not the scenario's rule" "(fn [r] (update r \"labels\" (fn [ls] (mapv #(if (= \"story\" (get % \"name\")) (assoc % \"value\" \"Something else\") %) ls))))"
   "the epic of another bounded context" "(fn [r] (update r \"labels\" (fn [ls] (mapv #(if (= \"epic\" (get % \"name\")) (assoc % \"value\" \"other\") %) ls))))"
   "no issue link to UN-001, the need the scenario traces to" "(fn [r] (assoc r \"links\" []))"
   "no browser-trace attachment, where :evidence requires one for @e2e" "identity"})

(defn mapped-project! [ctx code & [cfg-fn]]
  (let [ctx (vproject! ctx {:features mapped-feature :model mapped-model
                            :script (str run-line "\nbb mutate.clj\nexit 0")
                            :files {"mutate.clj" (mutate-script code)
                                    "dhf/spec/other/part.feature" (k/feature-text "Part" ["UN-001"] [])}
                            :cfg-fn cfg-fn})]
    ctx))

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (assoc ctx ::mapping :default)))

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (mapped-project! ctx (wrongs wrong)
                     (when (str/includes? wrong "browser-trace")
                       #(assoc % :allure {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step
                                          :parameter :example-row :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
                                          :links {:need {:type "issue" :url "N#{id}"}} :evidence {:e2e ["browser-trace"]}})))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (.+) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (k/exits! ctx 1)
    (k/check! (= :failed (:outcome (check-named ctx "tests"))) "the tests check is " (pr-str (check-named ctx "tests")))
    (k/check! (some #(re-find (re-pattern (str "^dhf/cycles/03/results/[^ ]+-result\\.json:\\d+: " (java.util.regex.Pattern/quote finding) ": .*spec-0003")) %)
                    (k/err-lines ctx))
              "no line names " finding ": " (k/shows ctx))
    ctx))

(defstep #"a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
  (fn [ctx]
    (vproject! ctx {:features {"dhf/spec/demo/thing.feature"
                               (k/feature-text "Thing" ["UN-001"] [{:id "spec-0001" :title "One" :level "contract"}
                                                                   {:id "spec-0002" :title "Planned" :wip? true}])}
                    :cfg-fn #(assoc % :allure {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step
                                               :parameter :example-row :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
                                               :links {:need {:type "issue" :url "N#{id}"}} :evidence {:e2e ["browser-trace"]}})})))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment"
  (fn [ctx] ctx))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (not-any? #(str/includes? % "spec-0002") (k/err-lines ctx)) "a line names spec-0002: " (k/shows ctx))
    ctx))

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [ctx is]
    (vproject! ctx {:cfg-fn #(assoc % :allure (case is
                                                 "maps :epic to :aggregate" {:epic :aggregate}
                                                 "maps :colour to :context" {:colour :context}
                                                 "maps no label \"spec\"" {:labels {"component" :component}}))})))

(defstep #"a configuration that declares no :allure"
  (fn [ctx] (vproject! ctx)))

(defstep #"each result is held to \{:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row :labels \{\"spec\" :scenario-id \"component\" :component \"tag\" :gherkin-tags\} :links \{:need \{:type \"issue\" :url \"N#\{id\}\"\}\} :evidence \{\}\}, N the configuration's :needs"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [c (mapped-project! (dissoc ctx :dir :base :ph) "(fn [r] (update r \"labels\" (fn [ls] (mapv #(if (= \"component\" (get % \"name\")) (assoc % \"value\" \"Another\") %) ls))))")
          r (verify! c)]
      (k/exits! r 1)
      (k/err-line-has! r "mapping-differs: component" "spec-0003"))
    ctx))

(defstep #"N is dhf/spec/needs.edn for a configuration that names no :needs"
  (fn [ctx]
    (let [c (mapped-project! (dissoc ctx :dir :base :ph) "(fn [r] (assoc-in r [\"links\" 0 \"url\"] \"dhf/elsewhere.edn#UN-001\"))")
          r (verify! c)]
      (k/exits! r 1)
      (k/err-line-has! r "mapping-differs: links" "spec-0003"))
    ctx))
