(ns steps.verification-verify
  "Step definitions of dhf/spec/verification/verify.feature. Every scenario
  verifies cycle 03 of the cut spec-3 of its own project, with a room ROOM it
  makes; a room's tests are the kernel's runner over the checkout's spec,
  every step passing, sometimes with its results changed afterwards. A served
  app is a small program the room holds (src/serve.clj), a probe a script the
  cut keeps (dhf/harness/probe.sh); every process either starts writes its id
  into the checkout, and is stopped when the scenario ends, however it ends."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail! defer!]]
            [steps.kernel-command :as k]))

(defn- file [ctx p] (fs/file (k/guard! (:dir ctx)) p))
(defn- read-file [ctx p] (let [f (file ctx p)] (when (fs/exists? f) (slurp f))))
(def build "dhf/cycles/03/build")
(def results "dhf/cycles/03/results")
(defn- room [ctx] (get-in ctx [:ph "ROOM"]))

;; ---------------------------------------------------------------------------
;; Rooms and their tests

(defn suite-test
  "A room's test command: the kernel's runner over the checkout's spec, its
  results where RESULTS_DIR says; then, for the results labelled id, mutate
  (a function of the result's JSON, as code) or, with drop?, their removal."
  [& {:keys [id mutate drop? exit]}]
  (str "(require '[babashka.classpath :refer [add-classpath]])\n"
       "(add-classpath " (pr-str (str (fs/path k/room-root "src"))) ")\n"
       "(require '[rdm-spec.kernel.runner :as r] '[cheshire.core :as json] '[babashka.fs :as fs])\n"
       "(println (str \"RESULTS_DIR=\" (System/getenv \"RESULTS_DIR\")))\n"
       "(def results-dir (or (System/getenv \"RESULTS_DIR\") \"dhf/allure-results\"))\n"
       "(def ok (r/run-suite! {:root \".\" :paths [] :parallel 1 :results-dir results-dir}))\n"
       (when id
         (str "(doseq [f (fs/glob results-dir \"*-result.json\")\n"
              "        :let [j (json/parse-string (slurp (str f)))]\n"
              "        :when (some #(= {\"name\" \"spec\" \"value\" " (pr-str id) "} %) (get j \"labels\"))]\n"
              (if drop?
                "  (fs/delete f))\n"
                (str "  (spit (str f) (json/generate-string (" mutate " j) {:pretty true})))\n"))))
       "(System/exit " (if exit (str exit) "(if ok 0 1)") ")\n"))

(def serve-app
  "The served app: src/serve.clj PORT MODE. now answers every GET; second
  closes its first connection unanswered, then answers; silent accepts and
  never answers; never does not listen at all."
  (str "(let [[port mode] *command-line-args*\n"
       "      held (atom [])]\n"
       "  (spit \"app.pids\" (str (.pid (java.lang.ProcessHandle/current)) \"\\n\") :append true)\n"
       "  (if (= mode \"never\")\n"
       "    (Thread/sleep 600000)\n"
       "    (with-open [ss (java.net.ServerSocket.)]\n"
       "      (.setReuseAddress ss true)\n"
       "      (.bind ss (java.net.InetSocketAddress. \"127.0.0.1\" (int (parse-long port))))\n"
       "      (loop [n 1]\n"
       "        (let [s (.accept ss)]\n"
       "          (cond\n"
       "            (= mode \"silent\") (swap! held conj s)\n"
       "            (and (= mode \"second\") (= n 1)) (.close s)\n"
       "            :else (do (.read (.getInputStream s) (byte-array 4096))\n"
       "                      (doto (.getOutputStream s) (.write (.getBytes \"HTTP/1.0 200 OK\\r\\nContent-Length: 2\\r\\n\\r\\nok\")) (.flush))\n"
       "                      (.close s)))\n"
       "          (recur (inc n)))))))\n"))

(defn room-with!
  "ROOM, holding the passing steps, the served app and test.clj as given."
  [ctx test-text]
  (let [ctx (if (room ctx) ctx (k/placeholder! ctx "ROOM"))]
    (k/write-files! (room ctx) {"test.clj" test-text
                                "test/steps/all.clj" k/passing-steps
                                "src/app.clj" "(ns app)\n"
                                "src/serve.clj" serve-app})
    ctx))

(defn- running?
  [pid]
  (and (.isPresent (java.lang.ProcessHandle/of pid))
       (let [stat (try (slurp (str "/proc/" pid "/stat")) (catch Exception _ nil))]
         (and stat (not (re-find #"^\d+ \(.*\) Z" stat))))))

(defn- alive?
  "The process still runs a moment after it was stopped (a stop is not instant)."
  [pid]
  (loop [n 20]
    (cond (not (running? pid)) false
          (zero? n) true
          :else (do (Thread/sleep 100) (recur (dec n))))))

(defn- pids [ctx]
  (for [f ["app.pids" "probe.pids" "runs.pids"]
        :let [t (read-file ctx (str build "/" f))]
        :when t
        l (str/split-lines t)
        :let [pid (parse-long (str/trim l))]
        :when pid]
    pid))

(defn reap!
  "Stops, when the scenario ends, every process a check of it started."
  [ctx]
  (defer! #(doseq [pid (pids ctx)]
             (when-let [h (.orElse (java.lang.ProcessHandle/of pid) nil)] (.destroyForcibly h))))
  ctx)

(defn verify-project!
  "Cycle 03 of the cut spec-3 of a project with the configuration opts merged
  in (:config), the files :extra, features :features, the room's tests
  :test; the context."
  [ctx {:keys [config extra features test issued]}]
  (let [ctx (k/project! ctx (cond-> {:config (or config {})}
                              extra (assoc :extra extra)
                              features (assoc :features features)
                              issued (assoc :issued issued)))
        ctx (room-with! ctx (or test (suite-test)))]
    (reap! (k/cycle-ready! ctx))))

(defn retag!
  "The project as it now stands, cut again as spec-3."
  [ctx]
  (k/tag-cut! ctx "spec-3"))

(defn- out [ctx] (:verify (k/edn-out ctx)))
(defn- check-of [ctx n] (first (filter #(= n (:name %)) (:checks (out ctx)))))

(defn- outcome! [ctx n want]
  (let [c (check-of ctx n)]
    (when-not (= want (:outcome c)) (fail! (str "the check " n " is " (pr-str (:outcome c)) ", not " want ": " (:out ctx) (:err ctx))))
    ctx))

(def tests-check {:kind :tests :command ["bb" "test.clj"]})

;; ---------------------------------------------------------------------------
;; Verify trusts only the cut

(defn- cut-files [ctx]
  (into (sorted-map) (for [p (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3"))]
                       [p (k/git (:dir ctx) "show" (str "spec-3:" p))])))

(defn- tree [d]
  (into (sorted-map) (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (slurp (fs/file f))])))

(def impl ["src" "test" "test.clj"])
(defn- impl? [p] (some #(or (= p %) (str/starts-with? p (str % "/"))) impl))

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [b (file ctx build)
          got (tree b)]
      (assert (not (fs/exists? (fs/path b ".git"))) "the checkout holds a history")
      (doseq [[p text] (cut-files ctx) :when (not (impl? p))]
        (assert (= (str/trim text) (some-> (get got p) str/trim)) (str p " is not the cut's")))
      (assert (= (into (sorted-map) (filter (comp impl? key) got)) (tree (room ctx))) "the implementation is not the room's"))
    ctx))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [ctx]
    (let [ctx (verify-project! ctx {})]
      (fs/delete-tree (file ctx "dhf/cycles"))
      (retag! ctx))))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "dhf/cycles/03/cycle.edn")))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [ctx]
    (let [ctx (verify-project! ctx {:extra {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"}})]
      (assert (seq (k/git (:dir ctx) "ls-tree" "spec-3" "src/rdm_spec/old.clj")))
      (assert (not (fs/exists? (fs/path (room ctx) "src/rdm_spec/old.clj"))))
      ctx)))

(defstep #"the checkout it verifies holds no src/rdm_spec/old.clj"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (fs/directory? (file ctx build)))
    (assert (not (fs/exists? (file ctx (str build "/src/rdm_spec/old.clj")))))
    ctx))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [got (tree (file ctx build))]
      (assert (= (into (sorted-map) (filter (comp impl? key) got)) (tree (room ctx)))))
    ctx))

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (verify-project! ctx {})]
      (k/write-files! (room ctx) {"dhf/harness/config.edn" (k/pr-edn (assoc k/default-config :verify [{:kind :tests :command ["sh" "-c" "exit 0"]}]))
                                  k/core-feature "Feature: Edited\n"})
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (k/check-exit ctx 0)
    (outcome! ctx "tests" :passed)
    (assert (= 1 (:scenarios (check-of ctx "tests"))))
    (doseq [p ["dhf/harness/config.edn" k/core-feature]]
      (assert (= (k/git (:dir ctx) "show" (str "spec-3:" p)) (str/trim (read-file ctx (str build "/" p)))) p))
    ctx))

;; ---------------------------------------------------------------------------
;; The tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx] (k/rdm-line (verify-project! ctx {}) "rdm-spec verify ROOM 03")))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [log (read-file ctx "dhf/cycles/03/test.log")
          want (str (fs/path (fs/canonicalize (:dir ctx)) results))]
      (assert (str/includes? log (str "RESULTS_DIR=" want "\n")) log)
      (assert (seq (fs/glob (file ctx results) "*-result.json"))))
    ctx))

(defstep #"the :tests check's command exits ([01])"
  (fn [ctx n]
    (verify-project! ctx (when (= "1" n) {:test (suite-test :exit 1)}))))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] ctx))

(defstep #"the tests check passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (outcome! ctx "tests" :passed)
    (assert (= :passed (:outcome (out ctx))))
    (assert (seq (fs/glob (file ctx results) "*-result.json")))
    ctx))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx]
    (k/check-exit ctx 1)
    (outcome! ctx "tests" :failed)
    (k/err-line-with ctx "test command failed" "exit 1")))

(defstep #"the test command's output is in dhf/cycles/03/test.log, which the refusal names"
  (fn [ctx]
    (assert (str/includes? (read-file ctx "dhf/cycles/03/test.log") "RESULTS_DIR="))
    (k/err-line-with ctx "test command failed" "dhf/cycles/03/test.log")))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (verify-project! ctx {:test "(System/exit 0)\n"})))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx]
    (k/check-exit ctx 1)
    (outcome! ctx "tests" :failed)
    (k/err-line-with ctx "a green exit with no results is not a run")))

(def two-scenarios
  {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "One") (k/sc 12 "Twelve")]})})

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx] (verify-project! ctx {:features two-scenarios})))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (room-with! ctx (suite-test :id "spec-0012" :drop? true))))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx]
    (k/check-exit ctx 1)
    (outcome! ctx "tests" :failed)
    (k/err-line-with ctx "spec-0012")))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx]
    (-> (k/project! ctx {:features two-scenarios})
        retag!
        (room-with! (suite-test :id "spec-0012" :mutate "(fn [j] (assoc j \"status\" \"failed\"))")))))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx]
    (k/check-exit ctx 1)
    (outcome! ctx "tests" :failed)
    (k/err-line-with ctx "spec-0012" "failed")))

;; ---------------------------------------------------------------------------
;; Verify records what it verified

(defn- room-tree
  "The tree hash of the room's implementation: its implementation paths as
  git would commit them, nothing else."
  [ctx]
  (let [d (k/path-in ctx "tree")]
    (k/git-init! d)
    (doseq [p impl :let [s (fs/path (room ctx) p)] :when (fs/exists? s)]
      (if (fs/directory? s) (fs/copy-tree s (fs/path d p)) (fs/copy s (fs/path d p))))
    (k/git d "add" "-A")
    (let [t (k/git d "write-tree")] (fs/delete-tree d) t)))

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (verify-project! ctx {:config {:verify [tests-check
                                                      {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:help []}\")"]
                                                       :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}}]}})]
      (assoc-in ctx [:bindings 'H] (room-tree ctx)))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx] (k/rdm-line ctx "rdm-spec verify ROOM 03")))

(defstep #"dhf/cycles/03/ratified.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (edn/read-string (read-file ctx "dhf/cycles/03/ratified.edn"))
          h (get-in ctx [:bindings 'H])]
      (k/check-exit ctx 0)
      (assert (re-matches #"[0-9a-f]{40}" h) h)
      (assert (= {:cut "spec-3" :room-tree h :outcome :passed :checkout build :checks (:checks (out ctx))} r) (pr-str r))
      (assert (= ["tests" "answers"] (map :name (:checks r))))
      ctx)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (let [got (tree (file ctx build))]
      (assert (= (into (sorted-map) (filter (comp impl? key) got)) (tree (room ctx))))
      (assert (= (str/trim (k/git (:dir ctx) "show" "spec-3:dhf/harness/config.edn")) (str/trim (get got "dhf/harness/config.edn")))))
    ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx]
    (k/rdm-line (verify-project! ctx {:test (suite-test :exit 1)}) "rdm-spec verify ROOM 03")))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [r (edn/read-string (read-file ctx "dhf/cycles/03/ratified.edn"))]
      (assert (= :refused (:outcome r)) (pr-str r))
      (assert (str/includes? (str (:reason r)) "test command failed") (pr-str r))
      (assert (= "spec-3" (:cut r)))
      ctx)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx]
    (k/check-exit ctx 1)
    (doseq [n (:names ctx)] (k/err-has ctx n))
    (assert (= :refused (:outcome (edn/read-string (read-file ctx "dhf/cycles/03/ratified.edn")))))
    ctx))

;; ---------------------------------------------------------------------------
;; A project declares its checks

(def answers {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:help []}\")"]
              :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [ctx] (verify-project! ctx {:config {:verify [tests-check answers]}})))

(defstep #"both pass"
  (fn [ctx] ctx))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify \{:cycle \"03\" :cut \"spec-3\" :outcome :passed :checks \[\{:name \"tests\" :kind :tests :outcome :passed :seconds S1 :scenarios N\} \{:name \"answers\" :kind :runs :outcome :passed :seconds S2 :budget B :margin M\}\]\}\}, S1 and S2 the seconds each took, N the stamped scenarios not tagged @wip that results name, B the check's budget in seconds and M the seconds of it left unused"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [b (k/unify '{:verify {:cycle "03" :cut "spec-3" :outcome :passed
                                :checks [{:name "tests" :kind :tests :outcome :passed :seconds S1 :scenarios N}
                                         {:name "answers" :kind :runs :outcome :passed :seconds S2 :budget B :margin M}]}}
                     (k/edn-out ctx) {})]
      (when-not b (fail! (str "the standard output is not of that shape: " (:out ctx))))
      (let [{:syms [S1 S2 N B M]} b]
        (assert (and (number? S1) (pos? S1) (number? S2) (pos? S2)) (pr-str b))
        (assert (= 1 N) (pr-str b))
        (assert (= 30 B) (pr-str b))
        (assert (< (Math/abs (double (- M (- B S2)))) 0.011) (pr-str b)))
      ctx)))

(def serves {:kind :serves :name "serves" :start ["bb" "src/serve.clj" "{port}" "now"] :port 0
             :budget {:seconds 10 :polls 20} :probe ["sh" "dhf/harness/probe.sh" "{url}"]})

(def probe-ok "echo $$ >> probe.pids\nprintf '%s\\n' \"$1\" > probe.args\npwd > probe.cwd\nexit 0\n")

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- runs-marker [] {:kind :runs :name "answers" :command ["sh" "-c" "echo ran > runs.ran"]
                       :expect {:exit 0} :budget {:seconds 30}})

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx]
    (verify-project! ctx {:config {:verify [tests-check (assoc serves :port (free-port)) (runs-marker)]}
                          :extra {"dhf/harness/probe.sh" probe-ok}
                          :test (suite-test :exit 1)})))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= [["tests" :failed] ["serves" :not-run] ["answers" :not-run]]
               (map (juxt :name :outcome) (:checks (out ctx)))) (:out ctx))
    (assert (empty? (pids ctx)) "an app was started")
    (assert (not (fs/exists? (file ctx (str build "/runs.ran")))))
    ctx))

(def tests-marker {:kind :tests :command ["sh" "-c" "echo ran > tests.ran; bb test.clj"]})

(def bad-verify
  {"declares no :tests check" [answers]
   "declares a check of the kind :renders" [tests-marker {:kind :renders :name "page"}]
   "declares a :serves check whose probe is under src/" [tests-marker (assoc serves :port 8000 :probe ["sh" "src/probe.sh" "{url}"])]
   "declares a :tests check with no command" [{:kind :tests}]})

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [ctx is]
    (let [ctx (verify-project! ctx {:config {:verify (get bad-verify is [tests-marker])}})]
      (if (= is "is absent")
        (-> (k/update-edn! ctx "dhf/harness/config.edn" dissoc :verify) retag!)
        ctx))))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" why)
    (assert (str/blank? (:out ctx)) (:out ctx))
    (assert (not (fs/exists? (file ctx (str build "/tests.ran")))) "a check ran")
    (assert (not (fs/exists? (file ctx (str build "/judge.ran")))) "a check ran")
    (assert (not (fs/exists? (file ctx "dhf/cycles/03/test.log"))) "a check ran")
    (assert (not (fs/exists? (file ctx "dhf/cycles/03/ratified.edn"))) "a refusal wrote ratified.edn")
    ctx))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx]
    (verify-project! ctx {:config {:verify [tests-check (assoc answers :command ["no-such-command-xyz" "help"])]}})))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" "answers" "no-such-command-xyz")))

;; ---------------------------------------------------------------------------
;; A :serves check

(defn- serves! [ctx check probe]
  (verify-project! ctx {:config {:verify [tests-check check]} :extra {"dhf/harness/probe.sh" probe}}))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [ctx]
    (serves! ctx (assoc serves :port (free-port) :start ["bb" "src/serve.clj" "{port}" "second"]) probe-ok)))

(defn- serves-check [ctx] (first (filter #(= :serves (:kind %)) (:checks (out ctx)))))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [c (serves-check ctx)]
      (assert (= [:passed 2 20 18] ((juxt :outcome :answered-on-poll :polls :margin) c)) (pr-str c)))
    ctx))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (let [ps (pids ctx)]
      (assert (seq ps) "no process wrote its id")
      (doseq [pid ps] (assert (not (alive? pid)) (str "the process " pid " is still running"))))
    ctx))

(defstep #"a :serves check whose app answers on port P, with :probe-seconds 20"
  (fn [ctx]
    (let [port (free-port)]
      (-> (serves! ctx (assoc serves :port port :probe-seconds 20)
                   (str "echo $$ >> probe.pids\nprintf '%s\\n' \"$1\" > probe.args\npwd > probe.cwd\nsleep 1\nexit 0\n"))
          (assoc-in [:bindings 'P] port)))))

(defstep #"its probe runs in the checkout with \{url\} filled in as \"http://127.0.0.1:P/\", and may run for 20 seconds after the app answered"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [p (get-in ctx [:bindings 'P])]
      (assert (= (str "http://127.0.0.1:" p "/\n") (read-file ctx (str build "/probe.args"))) (read-file ctx (str build "/probe.args")))
      (assert (= (str (fs/canonicalize (file ctx build))) (str/trim (read-file ctx (str build "/probe.cwd")))))
      (assert (= 20 (:probe-seconds (serves-check ctx))) (pr-str (serves-check ctx))))
    ctx))

(defstep #"a :serves check with no :probe-seconds gives its probe 30 seconds"
  (fn [ctx]
    (k/update-edn! ctx "dhf/harness/config.edn" update :verify (fn [cs] (mapv #(dissoc % :probe-seconds) cs)))
    (let [r (k/rdm-line (retag! ctx) "rdm-spec verify ROOM 03")]
      (k/check-exit r 0)
      (assert (= 30 (:probe-seconds (serves-check r))) (:out r))
      ctx)))

(defstep #"a :serves check whose probe, with :probe-seconds 2, prints \"probing\" and then sleeps for 10 seconds"
  (fn [ctx]
    (serves! ctx (assoc serves :port (free-port) :probe-seconds 2)
             "echo $$ >> probe.pids\necho probing\nsleep 10 &\necho $! >> probe.pids\nwait\n")))

(defstep #"it exits 1 within 6 seconds, the check is :failed, and it says the probe timed out after 2 seconds and relays \"probing\""
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [c (serves-check ctx)]
      (assert (= :failed (:outcome c)) (pr-str c))
      (assert (< (:seconds c) 6) (pr-str c)))
    (k/err-line-with ctx "probe" "timed out after 2 seconds" "probing")))

(defstep #"a :serves check with the budget of (\d+) seconds in (\d+) polls, whose (app never answers|start command listens on its port and never writes a response)"
  (fn [ctx s k how]
    (serves! ctx (assoc serves :port (free-port) :budget {:seconds (parse-long s) :polls (parse-long k)}
                        :start ["bb" "src/serve.clj" "{port}" (if (= how "app never answers") "never" "silent")])
             probe-ok)))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (\d+) seconds"
  (fn [ctx s]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (serves-check ctx))))
    (k/err-line-with ctx (str "no answer came within " s " seconds"))))

(defstep #"a :serves check on a port another process already holds"
  (fn [ctx]
    (let [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
          port (.getLocalPort ss)]
      (defer! #(.close ss))
      (-> (serves! ctx (assoc serves :port port) probe-ok)
          (k/names+ (str port))))))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" (first (:names ctx)))
    (assert (empty? (pids ctx)) "an app was started")
    ctx))

(defstep #"a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain"
  (fn [ctx]
    (let [port (free-port)
          ctx (serves! ctx (assoc serves :port port) probe-ok)]
      ;; the app's side closes first, so its port is left in TIME_WAIT
      (with-open [ss (doto (java.net.ServerSocket.) (.setReuseAddress true)
                       (.bind (java.net.InetSocketAddress. "127.0.0.1" (int port))))]
        (let [c (java.net.Socket. "127.0.0.1" (int port))
              s (.accept ss)]
          (.close s)
          (Thread/sleep 50)
          (.close c)))
      ctx)))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (serves-check ctx))))
    (assert (seq (pids ctx)))
    ctx))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [ctx]
    (serves! ctx (assoc serves :port (free-port)) "echo $$ >> probe.pids\necho 'no form on the page: not this application'\nexit 1\n")))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (serves-check ctx))))
    (k/err-line-with ctx "no form on the page: not this application")))

;; ---------------------------------------------------------------------------
;; A :runs check

(defstep #"a :runs check expecting (the exit 0|standard output starting \"\{:help\"|an answer within 10 seconds)"
  (fn [ctx expected]
    (assoc ctx ::runs (case expected
                        "the exit 0" {:exit 0}
                        "standard output starting \"{:help\"" {:exit 0 :stdout-starts "{:help"}
                        "an answer within 10 seconds" {:seconds 10}))))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (let [e (::runs ctx)
          script (case does
                   "exits 3" "exit 3\n"
                   "prints \"usage\" and exits 0" "echo usage\necho more\nexit 0\n"
                   "is still running after 10 seconds" "echo $$ >> runs.pids\nsleep 30 &\necho $! >> runs.pids\nwait\n")
          check {:kind :runs :name "answers" :command ["sh" "dhf/harness/answer.sh"]
                 :expect (dissoc e :seconds) :budget {:seconds (or (:seconds e) 30)}}]
      (verify-project! ctx {:config {:verify [tests-check check]} :extra {"dhf/harness/answer.sh" script}}))))

(defstep #"it exits 1, the check is :failed, and it names (the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx difference]
    (k/check-exit ctx 1)
    (outcome! ctx "answers" :failed)
    (case difference
      "the exit 3 where 0 was expected" (k/err-line-with ctx "answers" "the exit 3 where 0 was expected")
      "the first line it printed" (k/err-line-with ctx "answers" "usage")
      "the budget of 10 seconds" (do (k/err-line-with ctx "answers" "the budget of 10 seconds")
                                     (doseq [pid (pids ctx)] (assert (not (alive? pid)) (str pid " still runs")))))
    ctx))

;; ---------------------------------------------------------------------------
;; A :plugin check

(def judge-check {:kind :plugin :name "judge"})
(defn- judge [] {:name "judge" :answers :Check :command ["sh" "dhf/harness/plugins/judge/run.sh"]})

(defstep #"a configuration whose :verify declares a :tests check, then \{:kind :plugin :name \"judge\"\}, and whose :plugins has \"judge\" answering :Check"
  (fn [ctx] ctx))

(defstep #"the tests check passes, and the plugin (exits 0|writes \"(.+)\" on its error stream and exits 1|exits 2)"
  (fn [ctx answers line]
    (let [script (case answers
                   "exits 0" "cat > /dev/null\nexit 0\n"
                   "exits 2" "cat > /dev/null\necho 'cannot read the judge contract' >&2\nexit 2\n"
                   (str "cat > /dev/null\nprintf '%s\\n' '" line "' >&2\nexit 1\n"))]
      (-> (verify-project! ctx {:config {:verify [tests-check judge-check] :plugins [(judge)]}
                                :extra {"dhf/harness/plugins/judge/run.sh" script}})
          (assoc ::line line)))))

(defstep #"it (exits 0|exits 1, relaying the finding unchanged|exits 2, naming the plugin), and the check \"judge\" is (:passed|:failed|:refused)"
  (fn [ctx exits outcome]
    (case exits
      "exits 0" (do (k/check-exit ctx 0) (outcome! ctx "judge" :passed))
      "exits 1, relaying the finding unchanged" (do (k/check-exit ctx 1)
                                                    (assert (some #{(::line ctx)} (k/err-lines ctx)) (:err ctx))
                                                    (outcome! ctx "judge" :failed))
      "exits 2, naming the plugin" (do (k/check-exit ctx 2)
                                       (k/err-line-with ctx "cannot-run:" "judge" ":refused")))
    (assert (= (str ":" (subs outcome 1)) outcome))
    ctx))

(defstep #"a :plugin check whose plugin writes what it is given to a file"
  (fn [ctx]
    (verify-project! ctx {:config {:verify [tests-check judge-check] :plugins [(judge)]}
                          :extra {"dhf/harness/plugins/judge/run.sh" "cat > judge.given\npwd > judge.cwd\n"}})))

(defstep #"the plugin ran in the checkout, dhf/cycles/03/build, and its standard input was \{:request :Check :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\"\}"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (str (fs/canonicalize (file ctx build))) (str/trim (read-file ctx (str build "/judge.cwd")))))
    (assert (= {:request :Check :cycle "03" :cut "spec-3" :checkout build}
               (edn/read-string (read-file ctx (str build "/judge.given")))))
    ctx))

(defstep #"a configuration whose :verify declares \{:kind :plugin :name \"judge\"\}, and whose :plugins has no \"judge\" answering :Check"
  (fn [ctx]
    (verify-project! ctx {:config {:verify [tests-marker judge-check]
                                   :plugins [{:name "judge" :subscribes [:SpecParsed] :command ["sh" "dhf/harness/plugins/judge/run.sh"]}]}
                          :extra {"dhf/harness/plugins/judge/run.sh" "cat > /dev/null\necho ran > judge.ran\n"}})))

;; ---------------------------------------------------------------------------
;; The Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(def ruled
  {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"] :scenarios [(k/sc 1 "One")]
                                   :rules [{:name "Things happen" :scenarios [(k/sc 3 "Three" ["Given one" "When two" "Then three"])]}]})})

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [ctx] (assoc ctx ::allure default-mapping)))

(defn- relabel [label value]
  (str "(fn [j] (update j \"labels\" (fn [ls] (mapv #(if (= " (pr-str label) " (get % \"name\")) (assoc % \"value\" " (pr-str value) ") %) ls))))"))

(def wrongs
  {"a second step reworded from the scenario's line" "(fn [j] (assoc-in j [\"steps\" 1 \"name\"] \"When something else happens\"))"
   "one step fewer than the scenario's lines" "(fn [j] (update j \"steps\" (comp vec butlast)))"
   "the story \"Something else\", not the scenario's rule" (relabel "story" "Something else")
   "the epic of another bounded context" (relabel "epic" "shop")
   "no issue link to UN-001, the need the scenario traces to" "(fn [j] (assoc j \"links\" []))"
   "no browser-trace attachment, where :evidence requires one for @e2e" "identity"})

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [m (or (wrongs wrong) (throw (ex-info (str "no fixture for " wrong) {})))
          allure (cond-> (::allure ctx) (str/includes? wrong "browser-trace") (assoc :evidence {:e2e ["browser-trace"]}))]
      (verify-project! ctx {:config {:allure allure} :features ruled
                            :test (suite-test :id "spec-0003" :mutate m)}))))

(defn- result-file-of [ctx id]
  (first (for [f (fs/glob (file ctx results) "*-result.json")
               :let [j (json/parse-string (slurp (fs/file f)))]
               :when (some #(= {"name" "spec" "value" id} %) (get j "labels"))]
           (str results "/" (fs/file-name f)))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (.+) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (k/check-exit ctx 1)
    (outcome! ctx "tests" :failed)
    (let [f (result-file-of ctx "spec-0003")
          l (first (filter #(str/starts-with? % (str f ":")) (k/err-lines ctx)))]
      (assert f "no result names spec-0003")
      (assert (some #(and (re-matches (re-pattern (str (java.util.regex.Pattern/quote f) ":\\d+: [a-z][a-z0-9-]*: .+")) %)
                          (str/includes? % (str ": " finding))
                          (str/includes? % "spec-0003"))
                    (k/err-lines ctx))
              (str "no line " f ":LINE: " finding " naming spec-0003:\n" (:err ctx) l)))
    ctx))

(defstep #"a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
  (fn [ctx]
    (verify-project! ctx {:config {:allure (assoc default-mapping :evidence {:e2e ["browser-trace"]})}
                          :features {k/core-feature
                                     (k/feature-text {:name "Core" :tags ["UN-001"]
                                                      :scenarios [{:tags ["spec-0001" "contract"] :title "One" :steps ["Given a step"]}
                                                                  {:tags ["spec-0002" "e2e" "wip"] :title "Planned" :steps ["Given a step"]}]})}})))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment"
  (fn [ctx] ctx))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (outcome! ctx "tests" :passed)
    (let [f (result-file-of ctx "spec-0002")
          j (json/parse-string (slurp (file ctx f)))]
      (assert (= "skipped" (get j "status")))
      (assert (some #{{"name" "planned" "value" "true"}} (get j "labels"))))
    (assert (not (str/includes? (str (:err ctx)) "spec-0002")) (:err ctx))
    ctx))

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [ctx is]
    (verify-project! ctx {:config {:verify [tests-marker]
                                   :allure (case is
                                             "maps :epic to :aggregate" (assoc default-mapping :epic :aggregate)
                                             "maps :colour to :context" (assoc default-mapping :colour :context)
                                             (update default-mapping :labels dissoc "spec"))}})))

(defstep #"a configuration that declares no :allure"
  (fn [ctx] (verify-project! ctx {:features ruled})))

(defstep #"each result is held to (\{.+\})"
  (fn [ctx mapping]
    (assert (= default-mapping (edn/read-string mapping)) "the scenario's mapping is not the steps' default")
    (assert (not (contains? (edn/read-string (k/git (:dir ctx) "show" "spec-3:dhf/harness/config.edn")) :allure)))
    (k/check-exit ctx 0)
    (outcome! ctx "tests" :passed)
    ;; each element of it holds
    (doseq [[m finding] [[(relabel "story" "Elsewhere") "mapping-differs: story"]
                         [(relabel "epic" "shop") "mapping-differs: epic"]
                         [(relabel "feature" "Other") "mapping-differs: feature"]
                         [(relabel "component" "Other") "mapping-differs: labels"]
                         ["(fn [j] (assoc j \"name\" \"Another\"))" "mapping-differs: name"]
                         ["(fn [j] (assoc j \"links\" []))" "mapping-differs: links"]
                         ["(fn [j] (update j \"labels\" (fn [ls] (vec (remove #(= {\"name\" \"tag\" \"value\" \"e2e\"} %) ls)))))" "mapping-differs: labels"]
                         ["(fn [j] (assoc-in j [\"steps\" 0 \"name\"] \"Given zero\"))" "steps-differ-from-scenario"]]]
      (room-with! ctx (suite-test :id "spec-0003" :mutate m))
      (let [r (k/rdm-line ctx "rdm-spec verify ROOM 03")]
        (k/check-exit r 1)
        (k/err-line-with r (str ": " finding) "spec-0003")))
    ctx))
