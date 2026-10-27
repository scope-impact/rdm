(ns steps.verification-verify
  "Step definitions of dhf/spec/verification/verify.feature. A scenario's
  room holds a test command that runs the kernel's runner over the checkout's
  spec, every step passing (and attaching a browser trace), and that can be
  told to change what it writes; its served app and probe are small shell and
  python3 commands."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail! defer!]]
            [steps.kernel-command :as k]))

;; ---------------------------------------------------------------------------
;; The room

(def mutate-code
  "(defn spec-of [j] (some #(when (= \"spec\" (get % \"name\")) (get % \"value\")) (get j \"labels\")))
(defn set-label [j n v] (update j \"labels\" (fn [ls] (mapv #(if (= n (get % \"name\")) (assoc % \"value\" v) %) ls))))
(defn mutate [m j]
  (case m
    :step-reworded (update-in j [\"steps\" 1 \"name\"] str \", reworded\")
    :step-fewer (update j \"steps\" (comp vec butlast))
    :story (set-label j \"story\" \"Something else\")
    :epic (set-label j \"epic\" \"another-context\")
    :feature (set-label j \"feature\" \"Another feature\")
    :name (assoc j \"name\" \"Another title\")
    :component (set-label j \"component\" \"Another\")
    :tag (update j \"labels\" conj {\"name\" \"tag\" \"value\" \"extra\"})
    :links (assoc j \"links\" [])
    :no-attachment (-> j (assoc \"attachments\" []) (update \"steps\" (fn [ss] (mapv #(assoc % \"attachments\" []) ss))))
    :failed (assoc j \"status\" \"failed\")
    j))
(doseq [f (fs/glob results-dir \"*-result.json\")]
  (let [j (json/parse-string (slurp (str f)))
        m (get mutations (spec-of j))]
    (cond (= :drop m) (fs/delete f)
          m (spit (str f) (json/generate-string (mutate m j))))))
")

(defn room-test
  "The room's test command. opts: :mutations {id mutation}, :exit (forced),
  :no-results, :marker (a file it writes when it runs)."
  [{:keys [mutations exit no-results marker]}]
  (str "(require '[babashka.classpath :refer [add-classpath]] '[babashka.fs :as fs] '[cheshire.core :as json])\n"
       "(add-classpath " (pr-str (str (fs/path k/room-root "src"))) ")\n"
       "(require '[rdm-spec.kernel.runner :as r])\n"
       (when marker (str "(spit " (pr-str marker) " \"ran\")\n"))
       "(spit \"results-dir.txt\" (str (System/getenv \"RESULTS_DIR\")))\n"
       (if no-results
         "(println \"the tests ran and wrote nothing\")\n(System/exit 0)\n"
         (str "(def results-dir (or (System/getenv \"RESULTS_DIR\") \"dhf/allure-results\"))\n"
              "(def ok (r/run-suite! {:root \".\" :paths [] :parallel 1 :results-dir results-dir}))\n"
              "(def mutations " (pr-str (or mutations {})) ")\n"
              mutate-code
              (when exit (str "(println \"the test command exits " exit "\")\n"))
              "(System/exit " (if exit (str exit) "(if ok 0 1)") ")\n"))))

(def room-steps
  "(require '[rdm-spec.kernel.runner :refer [defstep attach!]])
(defstep #\".*\" (fn [ctx & _] (attach! \"browser-trace\" \"GET / answered\") ctx))
")

(defn room-of [ctx] (k/guard! (get-in ctx [:ph "ROOM"])))

(defn room!
  "ROOM, with the test command opts say."
  ([ctx] (room! ctx {}))
  ([ctx opts]
   (let [ctx (k/room! ctx)]
     (k/write-files! (room-of ctx) {"test.clj" (room-test opts) "test/steps/all.clj" room-steps})
     ctx)))

(defn ready!
  "A project (opts as k/project!), its room (room-opts) and cycle 03 of the cut spec-3."
  ([ctx] (ready! ctx {} {}))
  ([ctx opts room-opts]
   (-> (k/project! ctx opts) (room! room-opts) k/cycle-ready!)))

(defn ready-from-givens! [ctx & [opts]]
  (if (::ready ctx) ctx (-> (ready! ctx (or opts {}) (::room ctx {})) (assoc ::ready true))))

(defn verify [ctx] (k/rdm ctx ["verify" (room-of ctx) "03"]))

(defn cycle-file [ctx p] (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03" p))

(defn build [ctx] (cycle-file ctx "build"))

(defn out-checks [ctx] (get-in (k/edn-out ctx) [:verify :checks]))

(defn check-named [ctx n]
  (or (first (filter #(= n (:name %)) (out-checks ctx)))
      (fail! (str "no check " n ": " (:out ctx) (:err ctx)))))

(defn tree [d]
  (if (fs/exists? d)
    (into (sorted-map) (for [f (if (fs/directory? d) (fs/glob d "**" {:hidden true}) [d]) :when (fs/regular-file? f)]
                         [(str (fs/relativize d f)) (slurp (fs/file f))]))
    {}))

(defn set-verify! [ctx checks] (k/update-edn! (k/project! ctx) "dhf/harness/config.edn" assoc :verify checks))

(def tests-check {:kind :tests :command ["bb" "test.clj"]})

(defn round2 [x] (/ (Math/round (* 100.0 (double x))) 100.0))

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [b (build ctx)
          impl? #(some (fn [p] (or (= % p) (str/starts-with? % (str p "/")))) ["src" "test" "test.clj"])
          files (remove str/blank? (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3")))]
      (assert (not (fs/exists? (fs/path b ".git"))) "the checkout holds history")
      (doseq [f (remove impl? files)]
        (assert (= (k/git (:dir ctx) "show" (str "spec-3:" f)) (str/trim (slurp (fs/file b f)))) f))
      ctx)))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [ctx] (-> (k/project! ctx) room!)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "dhf/cycles/03/cycle.edn")))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [ctx] (ready! ctx {:extra {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"}} {})))

(defstep #"the checkout it verifies holds no src/rdm_spec/old.clj"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (fs/exists? (build ctx)))
    (assert (not (fs/exists? (fs/path (build ctx) "src/rdm_spec/old.clj"))))
    ctx))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (doseq [p ["src" "test" "test.clj"]]
      (assert (= (tree (fs/path (room-of ctx) p)) (tree (fs/path (build ctx) p))) p))
    ctx))

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (ready! ctx)]
      (k/write-files! (room-of ctx) {"dhf/harness/config.edn" "{:verify [{:kind :tests :command [\"true\"]}]}\n"
                                     k/core-feature "@UN-001\nFeature: Edited\n"})
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [f ["dhf/harness/config.edn" k/core-feature]]
      (assert (= (k/git (:dir ctx) "show" (str "spec-3:" f)) (str/trim (slurp (fs/file (build ctx) f)))) f))
    (assert (= 1 (get-in (check-named ctx "tests") [:scenarios])))
    ctx))

;; ---------------------------------------------------------------------------
;; The tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx] (verify (ready! ctx))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (str (fs/path (:dir ctx) "dhf/cycles/03/results"))
               (slurp (fs/file (build ctx) "results-dir.txt"))))
    ctx))

(defn two-scenarios [] {:features {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                   :scenarios [(k/sc 1 "One") (k/sc 12 "Twelve")]})}})

(defstep #"the :tests check's command exits (\d)( without writing any Allure result)?"
  (fn [ctx code none]
    (cond
      none (ready-from-givens! (assoc ctx ::room {:no-results true}))
      (= "1" code) (ready-from-givens! (assoc ctx ::room {:exit 1}))
      :else (assoc ctx ::room {:exit 0}))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] (ready-from-givens! ctx)))

(defstep #"the tests check passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "tests"))))
    (assert (seq (fs/glob (cycle-file ctx "results") "*-result.json")))
    ctx))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "test command failed" "1")))

(defstep #"the test command's output is in dhf/cycles/03/test.log, which the refusal names"
  (fn [ctx]
    (assert (str/includes? (slurp (cycle-file ctx "test.log")) "the test command exits 1"))
    (k/err-line-with ctx "test command failed" "dhf/cycles/03/test.log")))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "a green exit with no results is not a run")))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx] (assoc ctx ::opts (two-scenarios))))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (ready-from-givens! (assoc ctx ::room {:mutations {"spec-0012" :drop}}) (::opts ctx))))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0012")))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (ready-from-givens! (assoc ctx ::room {:exit 0 :mutations {"spec-0012" :failed}}) (two-scenarios))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0012" "failed")))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx] (k/check-exit (verify (ready-from-givens! ctx)) 0)))

;; ---------------------------------------------------------------------------
;; What verify records

(def runs-answers {:kind :runs :name "answers" :command ["sh" "-c" "echo '{:help []}'"]
                   :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defn room-tree
  "The git tree of the room's implementation paths: the test's own reading."
  [ctx]
  (let [g (k/path-in ctx "tree.git")]
    (k/sh (:base ctx) {} "git" "init" "-q" "--bare" g)
    (let [env {"GIT_DIR" g "GIT_WORK_TREE" (room-of ctx) "GIT_INDEX_FILE" (str g "/index.tree")}
          paths (filter #(fs/exists? (fs/path (room-of ctx) %)) ["src" "test" "test.clj"])]
      (apply k/sh (room-of ctx) env "git" "add" "-A" "--" paths)
      (str/trim (:out (k/sh (room-of ctx) env "git" "write-tree"))))))

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx] (-> (set-verify! ctx [tests-check runs-answers]) ready-from-givens!)))

(defstep #"dhf/cycles/03/ratified.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (edn/read-string (slurp (cycle-file ctx "ratified.edn")))]
      (assert (= {:cut "spec-3" :room-tree (room-tree ctx) :outcome :passed :checkout "dhf/cycles/03/build"
                  :checks (out-checks ctx)}
                 r)
              (pr-str r))
      (assert (= 40 (count (:room-tree r))))
      ctx)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (assert (= (slurp (fs/file (room-of ctx) "test.clj")) (slurp (fs/file (build ctx) "test.clj"))))
    (assert (fs/exists? (fs/file (build ctx) k/core-feature)))
    ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx] (verify (ready-from-givens! (assoc ctx ::room {:exit 1})))))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [r (edn/read-string (slurp (cycle-file ctx "ratified.edn")))]
      (assert (= :refused (:outcome r)) (pr-str r))
      (assert (str/includes? (str (:reason r)) "test command failed") (pr-str r))
      ctx)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx))))

;; ---------------------------------------------------------------------------
;; Declared checks

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (set-verify! ctx [tests-check runs-answers])))

(defstep #"both pass"
  (fn [ctx] (ready-from-givens! ctx)))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .+"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [b (k/unify '{:verify {:cycle "03" :cut "spec-3" :outcome :passed
                                :checks [{:name "tests" :kind :tests :outcome :passed :seconds S1 :scenarios N}
                                         {:name "answers" :kind :runs :outcome :passed :seconds S2 :budget B :margin M}]}}
                     (k/edn-out ctx) {})]
      (assert b (:out ctx))
      (assert (= 1 (b 'N)) (pr-str b))
      (assert (= 30 (b 'B)) (pr-str b))
      (assert (every? #(and (number? (b %)) (>= (b %) 0)) '[S1 S2 M]) (pr-str b))
      (assert (<= (Math/abs (- (b 'M) (round2 (- 30 (b 'S2))))) 0.011) (pr-str b))
      ctx)))

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn serves-check [port & [{:keys [seconds polls]}]]
  {:kind :serves :name "app" :start ["sh" "src/app.sh" "{port}"] :port port
   :budget {:seconds (or seconds 10) :polls (or polls 20)} :probe ["sh" "dhf/harness/probe.sh" "{url}"]})

(def app-answers
  "echo $$ > app.pid\nsleep 0.6\nexec python3 -m http.server \"$1\" --bind 127.0.0.1\n")

(def app-silent "echo $$ > app.pid\nexec sleep 60\n")

(defn serves!
  "A project whose :verify is checks, its probe exiting probe-exit, its room's
  app the script app."
  [ctx checks app probe-exit]
  (let [ctx (-> (set-verify! ctx checks)
                (k/project! {:extra {"dhf/harness/probe.sh" (str "echo \"probed $1\"\nexit " probe-exit "\n")}}))
        ctx (ready! ctx {} (::room ctx {}))]
    (k/write-files! (room-of ctx) {"src/app.sh" app})
    (assoc ctx ::ready true)))

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx] (serves! (assoc ctx ::room {:exit 1}) [tests-check (serves-check (free-port)) runs-answers] app-silent 0)))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= [["tests" :failed] ["app" :not-run] ["answers" :not-run]] (map (juxt :name :outcome) (out-checks ctx)))
            (:out ctx))
    (assert (not (fs/exists? (fs/file (build ctx) "app.pid"))) "an app was started")
    ctx))

(def untrusted-verify
  {"declares no :tests check" [runs-answers]
   "declares a check of the kind :renders" [tests-check {:kind :renders :name "page"}]
   "declares a :serves check whose probe is under src/" [tests-check (assoc (serves-check 8080) :probe ["sh" "src/probe.sh" "{url}"])]
   "is absent" nil
   "declares a :tests check with no command" [{:kind :tests}]})

(defn marker [ctx] (k/path-in ctx "tests-ran"))

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx what]
    (let [ctx (k/base! ctx)
          ctx (if (= what "is absent")
                (k/update-edn! (k/project! ctx) "dhf/harness/config.edn" dissoc :verify)
                (set-verify! ctx (untrusted-verify what)))]
      (ready-from-givens! (assoc ctx ::room {:marker (marker ctx)})))))

(defn runs-no-check [ctx]
  (assert (not (fs/exists? (marker ctx))) "a check ran")
  (assert (empty? (fs/glob (cycle-file ctx ".") "results/*")) "results were written")
  (assert (not (fs/exists? (cycle-file ctx "ratified.edn"))) "ratified.edn was written")
  ctx)

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" why)
    (runs-no-check ctx)))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx] (ready-from-givens! (set-verify! ctx [tests-check (assoc runs-answers :command ["no-such-command-on-path" "help"])]))))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "answers" "no-such-command-on-path")))

;; ---------------------------------------------------------------------------
;; :serves

(defn no-app-left [ctx]
  (let [f (fs/file (build ctx) "app.pid")]
    (when (fs/exists? f)
      (let [pid (str/trim (slurp f))]
        (Thread/sleep 200)
        (assert (not (fs/exists? (str "/proc/" pid))) (str "the app " pid " still runs")))))
  ctx)

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx] (serves! ctx [tests-check (serves-check (free-port))] app-answers 0)))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [c (check-named ctx "app")]
      (assert (= [:passed 2 20 18] ((juxt :outcome :answered-on-poll :polls :margin) c)) (pr-str c))
      ctx)))

(defstep #"no process the check started is still running"
  (fn [ctx] (no-app-left ctx)))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [ctx] (serves! ctx [tests-check (serves-check (free-port))] app-silent 0)))

(defstep #"it exits 1, the check is :failed, and it (says|names|relays) (.+)"
  (fn [ctx _ what]
    (k/check-exit ctx 1)
    (let [c (last (out-checks ctx))]
      (assert (= :failed (:outcome c)) (pr-str (out-checks ctx)))
      (cond
        (str/starts-with? what "no answer came within")
        (k/err-line-with ctx (:name c) what)
        (= what "the probe's output")
        (k/err-line-with ctx "app" "probed http://")
        (= what "the exit 3 where 0 was expected")
        (k/err-line-with ctx "answers" "exit 3" "0 was expected")
        (= what "the first line it printed")
        (k/err-line-with ctx "answers" "usage")
        (= what "the budget of 10 seconds")
        (k/err-line-with ctx "answers" "budget of 10 seconds")
        :else (fail! (str "unknown: " what))))))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [s (java.net.ServerSocket. 0)
          port (.getLocalPort s)]
      (defer! #(.close s))
      (-> (serves! ctx [tests-check (serves-check port)] "touch app-started\nexec sleep 60\n" 0)
          (assoc ::port port)))))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" (str (::port ctx)))
    (assert (not (fs/exists? (fs/file (build ctx) "app-started"))) "an app was started")
    ctx))

(defn http-get [port]
  (with-open [s (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout s 2000)
    (let [o (.getOutputStream s)]
      (.write o (.getBytes "GET / HTTP/1.0\r\n\r\n"))
      (.flush o)
      (slurp (.getInputStream s)))))

(defstep #"a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain"
  (fn [ctx]
    (let [port (free-port)
          ctx (k/base! ctx)
          pr (babashka.process/process ["python3" "-m" "http.server" (str port) "--bind" "127.0.0.1"]
                                       {:dir (:base ctx) :out :string :err :string})]
      (try
        (loop [i 0]
          (when-not (try (str/starts-with? (http-get port) "HTTP/") (catch Exception _ false))
            (when (> i 50) (fail! "the earlier app never answered"))
            (Thread/sleep 100)
            (recur (inc i))))
        (finally (babashka.process/destroy-tree pr) (deref pr 5000 nil)))
      (serves! ctx [tests-check (serves-check port)] app-answers 0))))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "app"))))
    (no-app-left ctx)))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx] (serves! ctx [tests-check (serves-check (free-port))] app-answers 1)))

(def app-mute
  "echo $$ > app.pid\nexec python3 -c \"import socket,sys\ns=socket.socket()\ns.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)\ns.bind(('127.0.0.1',int(sys.argv[1])))\ns.listen(16)\nheld=[]\nwhile True: held.append(s.accept())\" \"$1\"\n")

(defstep #"a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
  (fn [ctx] (serves! ctx [tests-check (serves-check (free-port) {:seconds 2 :polls 4})] app-mute 0)))

;; ---------------------------------------------------------------------------
;; :runs

(def expectations
  {"the exit 0" {:exit 0}
   "standard output starting \"{:help\"" {:stdout-starts "{:help"}
   "an answer within 10 seconds" {}})

(defstep #"a :runs check expecting (.+)"
  (fn [ctx e] (assoc ctx ::expect (or (expectations e) (fail! (str "unknown: " e))) ::budget (if (= e "an answer within 10 seconds") 10 30))))

(def doings
  {"exits 3" "exit 3"
   "prints \"usage\" and exits 0" "echo usage; exit 0"
   "is still running after 10 seconds" "exec sleep 15"})

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (ready-from-givens! (set-verify! ctx [tests-check {:kind :runs :name "answers" :command ["sh" "-c" (doings does)]
                                                        :expect (::expect ctx) :budget {:seconds (::budget ctx)}}]))))

;; ---------------------------------------------------------------------------
;; :plugin

(defn judge-config [ctx & [plugins]]
  (-> (set-verify! ctx [tests-check {:kind :plugin :name "judge"}])
      (k/update-edn! "dhf/harness/config.edn" assoc :plugins
                     (or plugins [{:name "judge" :answers :Check :command ["sh" "dhf/harness/plugins/judge/run.sh"]}]))))

(defstep #"a configuration whose :verify declares a :tests check, then \{:kind :plugin :name \"judge\"\}, and whose :plugins has \"judge\" answering :Check"
  (fn [ctx] (judge-config ctx)))

(def answers
  {"exits 0" "exit 0\n"
   "prints \"src/app.clj:9: form-missing: …\" and exits 1" "echo 'src/app.clj:9: form-missing: …' >&2\nexit 1\n"
   "exits 2" "echo 'cannot read the design' >&2\nexit 2\n"})

(defstep #"the tests check passes, and the plugin (.+)"
  (fn [ctx a]
    (let [ctx (k/project! ctx {:extra {"dhf/harness/plugins/judge/run.sh" (or (answers a) (fail! a))}})]
      (ready-from-givens! ctx))))

(defstep #"it (exits \d.*), and the check \"judge\" is (:\w+)"
  (fn [ctx exits outcome]
    (let [n (parse-long (subs exits 6 7))]
      (k/check-exit ctx n)
      (if (= 2 n)
        (k/err-line-with ctx "cannot-run:" "judge" outcome)
        (assert (= (keyword (subs outcome 1)) (:outcome (check-named ctx "judge"))) (:out ctx)))
      (when (= 1 n) (assert (some #{"src/app.clj:9: form-missing: …"} (k/err-lines ctx)) (:err ctx)))
      ctx)))

(defstep #"a :plugin check whose plugin writes what it is given to a file"
  (fn [ctx]
    (-> (judge-config ctx)
        (k/project! {:extra {"dhf/harness/plugins/judge/run.sh" "cat > given.edn\npwd > cwd.txt\n"}})
        ready-from-givens!)))

(defstep #"the plugin ran in the checkout, dhf/cycles/03/build, and its standard input was \{:request :Check :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\"\}"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (str (fs/canonicalize (build ctx))) (str/trim (slurp (fs/file (build ctx) "cwd.txt")))))
    (assert (= {:request :Check :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build"}
               (edn/read-string (slurp (fs/file (build ctx) "given.edn")))))
    ctx))

(defstep #"a configuration whose :verify declares \{:kind :plugin :name \"judge\"\}, and whose :plugins has no \"judge\" answering :Check"
  (fn [ctx]
    (let [ctx (k/base! ctx)
          ctx (judge-config ctx [{:name "judge" :answers :Gate :command ["sh" "dhf/harness/plugins/judge/run.sh"]}])]
      (-> (k/project! ctx {:extra {"dhf/harness/plugins/judge/run.sh" "exit 0\n"}})
          (assoc ::room {:marker (marker ctx)})
          ready-from-givens!))))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(defn mapping-features []
  {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                   :scenarios [(k/sc 1 "One")]
                                   :rules [{:name "A rule"
                                            :scenarios [(k/sc 3 "Three" ["Given a step" "When it runs" "Then it holds"])]}]})})

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (k/project! ctx {:config {:allure default-mapping} :features (mapping-features)})))

(def wrongs
  {"a second step reworded from the scenario's line" [:step-reworded {}]
   "one step fewer than the scenario's lines" [:step-fewer {}]
   "the story \"Something else\", not the scenario's rule" [:story {}]
   "the epic of another bounded context" [:epic {}]
   "no issue link to UN-001, the need the scenario traces to" [:links {}]
   "no browser-trace attachment, where :evidence requires one for @e2e" [:no-attachment {:e2e ["browser-trace"]}]})

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [[m evidence] (or (wrongs wrong) (fail! wrong))
          ctx (k/update-edn! ctx "dhf/harness/config.edn" assoc-in [:allure :evidence] evidence)]
      (ready-from-givens! (assoc ctx ::room {:mutations {"spec-0003" m}})))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (.+) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "tests"))))
    (let [l (first (filter #(and (str/includes? % "spec-0003") (str/includes? % (str ": " finding))) (k/err-lines ctx)))]
      (assert l (:err ctx))
      (assert (re-matches #"dhf/cycles/03/results/[^ ]+-result\.json: .+" l) l)
      ctx)))

(defstep #"a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
  (fn [ctx]
    (k/project! ctx {:config {:allure (assoc default-mapping :evidence {:e2e ["browser-trace"]})}
                     :features {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                :scenarios [(k/sc 1 "One")
                                                                            {:tags ["spec-0002" "e2e" "wip"] :title "Planned"
                                                                             :steps ["Given a step"]}]})}})))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment"
  (fn [ctx] (ready-from-givens! ctx)))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "tests"))))
    (assert (not (str/includes? (:err ctx) "spec-0002")) (:err ctx))
    (let [planned (filter #(str/includes? (slurp (fs/file %)) "\"planned\"") (fs/glob (cycle-file ctx "results") "*-result.json"))]
      (assert (= 1 (count planned))))
    ctx))

(def unreadable-mappings
  {"maps :epic to :aggregate" (assoc default-mapping :epic :aggregate)
   "maps :colour to :context" (assoc default-mapping :colour :context)
   "maps no label \"spec\"" (update default-mapping :labels dissoc "spec")})

(defstep #"a configuration whose :allure (maps .+)"
  (fn [ctx what]
    (let [ctx (k/base! ctx)
          ctx (k/project! ctx {:config {:allure (or (unreadable-mappings what) (fail! what))}})]
      (ready-from-givens! (assoc ctx ::room {:marker (marker ctx)})))))

(def held-to
  "One wrong element per scenario, each a finding of the default mapping."
  {"spec-0001" [:story "story"] "spec-0002" [:epic "epic"] "spec-0004" [:feature "feature"]
   "spec-0005" [:name "name"] "spec-0006" [:step-reworded "steps-differ-from-scenario"]
   "spec-0007" [:component "labels"] "spec-0008" [:tag "labels"] "spec-0009" [:links "links"]})

(defstep #"a configuration that declares no :allure"
  (fn [ctx]
    (let [ctx (k/project! ctx {:features {k/core-feature
                                          (k/feature-text {:name "Core" :tags ["UN-001"]
                                                           :rules [{:name "A rule"
                                                                    :scenarios (for [i [1 2 4 5 6 7 8 9]]
                                                                                 (k/sc i (str "Scenario " i) ["Given a step" "Then it holds"]))}]})}})]
      (assert (nil? (:allure (edn/read-string (slurp (fs/file (:dir ctx) "dhf/harness/config.edn"))))))
      (ready-from-givens! (assoc ctx ::room {:mutations (into {} (for [[id [m _]] held-to] [id m]))})))))

(defstep #"each result is held to \{.+\}"
  (fn [ctx]
    (k/check-exit ctx 1)
    (doseq [[id [_ element]] held-to]
      (k/err-line-with ctx id (if (= element "steps-differ-from-scenario") element (str "mapping-differs: " element))))
    (k/err-line-with ctx "spec-0007" "component")
    (k/err-line-with ctx "spec-0008" "tag")
    (assert (not (str/includes? (:err ctx) "evidence-missing")) (:err ctx))
    ctx))
