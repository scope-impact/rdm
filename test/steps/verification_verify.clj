(ns steps.verification-verify
  "Steps of the component Verify (06-regenerate/verify.feature, 08-plugins,
  11-project-in-a-folder, 12-several-specs and 13-slice-development's verify.feature)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [cheshire.core :as json]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn ratified [ctx] (some-> (k/slurp-in (:dir ctx) "dhf/cycles/03/ratified.edn") edn/read-string))
(defn checks [ctx] (get-in ctx [:edn :verify :checks]))
(defn check-named [ctx n] (some #(when (= n (:name %)) %) (or (checks ctx) (:checks (ratified ctx)))))
(defn build [ctx & parts] (apply k/path (:dir ctx) "dhf/cycles/03/build" parts))

(defn set-verify! [ctx v] (k/edit-config! (:dir ctx) #(assoc % :verify v)) (k/recut! ctx))

(defn free-port []
  (let [s (java.net.ServerSocket. 0)] (try (.getLocalPort s) (finally (.close s)))))

(defn server-cmd
  "A command that serves HTTP on {port} after delay seconds, its process marked by tag."
  [delay tag]
  ["bb" "-e" (str "(def tag \"" tag "\") (Thread/sleep " (long (* 1000 delay)) ") (require '[org.httpkit.server :as s]) "
                  "(s/run-server (fn [_] {:status 200 :body \"served\"}) {:port (parse-long (first *command-line-args*))}) @(promise)")
   "{port}"])

(defn running? [tag]
  (let [r (p/shell {:out :string :continue true} "pgrep" "-f" tag)]
    (not (str/blank? (:out r)))))

;; ------------------------------------------------------------------ the cut alone

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (fs/exists? (build ctx "dhf/spec/shop/cart.feature")) ctx "no checkout of the spec")
    (k/expect! (not (fs/exists? (build ctx ".git"))) ctx "the checkout has history")
    (k/expect! (not (str/includes? (slurp (build ctx "dhf/spec/glossary.md")) "after the cut")) ctx "the checkout is not spec-3")
    ctx))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [_] (let [ctx (k/cycle-project!)] (fs/delete (k/path (:dir ctx) "dhf/cycles/03/cycle.edn")) ctx)))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "dhf/cycles/03/cycle.edn") ctx))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [_] (let [ctx (k/cycle-project!)]
            (k/write! (:dir ctx) "src/rdm_spec/old.clj" "(ns old)\n")
            (k/recut! ctx))))

(defstep #"the checkout it verifies holds no src/rdm_spec/old.clj"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (not (fs/exists? (build ctx "src/rdm_spec/old.clj"))) ctx "the old file is in the checkout") ctx))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (doseq [p ["src" "test"]]
      (k/expect! (= (k/tree-snapshot (k/path (get-in ctx [:vars "ROOM"]) p)) (k/tree-snapshot (build ctx p))) ctx (str p " differs from the room's")))
    ctx))

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [_]
    (let [ctx (k/cycle-project!) room (get-in ctx [:vars "ROOM"])]
      (k/write! room "dhf/harness/config.edn" "{:verify [{:kind :tests :command [\"true\"]}]}")
      (k/write! room "dhf/spec/shop/cart.feature" "Feature: edited in the room\n")
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= (k/git! (:dir ctx) "show" "spec-3:dhf/harness/config.edn") (str/trim (slurp (build ctx "dhf/harness/config.edn")))) ctx "the configuration is the room's")
    (k/expect! (str/includes? (slurp (build ctx "dhf/spec/shop/cart.feature")) "spec-0001") ctx "the spec is the room's")
    ctx))

;; ------------------------------------------------------------------ the tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [_]
    (let [ctx (k/cycle-project! :verify [{:kind :tests :command ["sh" "-c" (str "echo \"$RESULTS_DIR\" > results-dir.txt; exec " (str/join " " (k/rdm-argv)) " run")]}])]
      (k/rdm! ctx "verify ROOM 03"))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= (str (fs/canonicalize (k/path (:dir ctx) "dhf/cycles/03/results"))) (str/trim (slurp (build ctx "results-dir.txt")))) ctx "RESULTS_DIR is not that path")
    ctx))

(defstep #"the :tests check's command exits 0"
  (fn [_] (k/cycle-project! :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run; exit 0")]}]
                            :features {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id "spec-0012" :steps ["Given the twelfth" "Then the twelfth holds"]}]})})))

(defstep #"it wrote Allure results into dhf/cycles/03/results" (fn [ctx] ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed" (fn [ctx] ctx))

(defstep #"the tests check passes"
  (fn [ctx]
    (let [ctx (if (:runs ctx) ctx (k/rdm! ctx "verify ROOM 03"))]
      (k/exits! ctx 0)
      (k/expect! (= :passed (:outcome (check-named ctx "tests"))) ctx "the tests check did not pass")
      ctx)))

(defstep #"the :tests check's command exits 1"
  (fn [_] (k/cycle-project! :verify [{:kind :tests :command ["sh" "-c" "echo the tests ran; exit 1"]}])))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "test command" "exit" "1") ctx))

(defstep #"the test command's output is in dhf/cycles/03/test.log, which the refusal names"
  (fn [ctx]
    (k/expect! (str/includes? (str (k/slurp-in (:dir ctx) "dhf/cycles/03/test.log")) "the tests ran") ctx "test.log lacks the output")
    (k/err-has! ctx "dhf/cycles/03/test.log")
    ctx))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [_] (k/cycle-project! :verify [{:kind :tests :command ["true"]}])))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (k/exits! ctx 1) (k/err-has! ctx "a green exit with no results is not a run") ctx))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [_] (k/cycle-project! :features {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id "spec-0012"}]})}
                            :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run && rm -f $(grep -l '\"spec-0012\"' \"$RESULTS_DIR\"/*-result.json)")]}])))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012" (fn [ctx] ctx))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0012") ctx))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx]
    (k/write! (get-in ctx [:vars "ROOM"]) "test/steps/all.clj"
              (str "(require '[rdm-spec.kernel.runner :refer [defstep fail!]])\n"
                   "(defstep #\"the twelfth holds\" (fn [ctx] (fail! \"it does not\")))\n"
                   "(defstep #\"(?!the twelfth holds).*\" (fn [ctx & _] ctx))\n"))
    ctx))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx "spec-0012" "failed") ctx))

;; ------------------------------------------------------------------ the record

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [_]
    (let [ctx (k/cycle-project! :verify [(k/tests-check) {:kind :runs :name "answers" :command (conj (k/rdm-argv) "help")
                                                         :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}}])
          room (get-in ctx [:vars "ROOM"])]
      (k/git! room "init" "-q")
      (k/commit-all! room "the build")
      (assoc ctx :tree (k/git! room "rev-parse" "HEAD^{tree}")))))


(defstep #"dhf/cycles/03/ratified.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (ratified ctx)]
      (k/expect! (= #{:cut :room-tree :outcome :checkout :checks} (set (keys r))) ctx (str "ratified.edn holds " (keys r)))
      (k/expect! (= ["spec-3" (:tree ctx) :passed "dhf/cycles/03/build"] [(:cut r) (:room-tree r) (:outcome r) (:checkout r)]) ctx (str "ratified.edn is " r))
      (k/expect! (= (checks ctx) (:checks r)) ctx "the checks differ from standard output's"))
    ctx))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx] (k/expect! (fs/exists? (build ctx "src/shop/app.clj")) ctx "the checkout does not hold the room's code") ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [_] (let [ctx (k/cycle-project! :verify [{:kind :tests :command ["sh" "-c" "exit 1"]}])] (k/rdm! ctx "verify ROOM 03"))))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (k/exits! ctx 1)
    (let [r (ratified ctx)]
      (k/expect! (= :refused (:outcome r)) ctx "not refused")
      (k/expect! (not (str/blank? (str (:reason r)))) ctx "no reason"))
    ctx))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx] (k/exits! ctx 1) (k/line-has! ctx (:unstamped ctx)) ctx))

;; ------------------------------------------------------------------ declared checks

(def runs-answers {:kind :runs :name "answers" :command ["sh" "-c" "echo '{:help []}'"] :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defstep #"a configuration whose :verify declares a :tests check, then a :runs check named \"answers\""
  (fn [_] (k/cycle-project! :verify [(k/tests-check) runs-answers])))

(defstep #"both pass" (fn [ctx] ctx))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .+"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [v (:verify (:edn ctx))
          [t r] (:checks v)]
      (k/expect! (= #{:verify} (set (keys (:edn ctx)))) ctx "not one map under :verify")
      (k/expect! (= {:cycle "03" :cut "spec-3" :outcome :passed} (dissoc v :checks)) ctx (str "the verify is " v))
      (k/expect! (= {:name "tests" :kind :tests :outcome :passed :scenarios 3} (dissoc t :seconds)) ctx (str "the tests check is " t))
      (k/expect! (number? (:seconds t)) ctx "no seconds")
      (k/expect! (= {:name "answers" :kind :runs :outcome :passed :budget 30} (dissoc r :seconds :margin)) ctx (str "the runs check is " r))
      (k/expect! (and (number? (:seconds r)) (number? (:margin r)) (< (Math/abs (double (- (:margin r) (- 30 (:seconds r))))) 0.02)) ctx "the margin is not the budget left"))
    ctx))

(def serves-check
  (fn [port & {:keys [delay polls seconds probe tag probe-seconds] :or {delay 0 polls 20 seconds 10 tag "servetag"}}]
    (cond-> {:kind :serves :name "serves" :start (server-cmd delay tag) :port port :budget {:seconds seconds :polls polls}
             :probe (or probe ["sh" "-c" "exit 0"])}
      probe-seconds (assoc :probe-seconds probe-seconds))))

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [_] (k/cycle-project! :verify [{:kind :tests :command ["sh" "-c" "exit 1"]} (serves-check (free-port)) runs-answers])))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/expect! (= [:failed :not-run :not-run] (map :outcome (checks ctx))) ctx (str "the checks are " (checks ctx)))
    ctx))

(defstep #"a configuration whose :verify (declares no :tests check|declares a check of the kind :renders|declares a :serves check whose probe is under src/|is absent|declares a :tests check with no command)"
  (fn [_ is]
    (let [ctx (k/cycle-project!) d (:dir ctx)]
      (k/write! d "ran.txt" "")
      (fs/delete (k/path d "ran.txt"))
      (k/edit-config! d #(case is
                           "declares no :tests check" (assoc % :verify [runs-answers])
                           "declares a check of the kind :renders" (assoc % :verify [(k/tests-check) {:kind :renders :name "r"}])
                           "declares a :serves check whose probe is under src/" (assoc % :verify [(k/tests-check) (serves-check 1 :probe ["sh" "src/probe.sh" "{url}"])])
                           "is absent" (dissoc % :verify)
                           "declares a :tests check with no command" (assoc % :verify [{:kind :tests}])))
      (k/recut! ctx))))

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (k/exits! ctx 2)
    (k/err-has! ctx (str/replace why #"^names " ""))
    (k/expect! (not (fs/exists? (k/path (:dir ctx) "dhf/cycles/03/results"))) ctx "a check ran")
    (k/expect! (not (fs/exists? (k/path (:dir ctx) "dhf/cycles/03/ratified.edn"))) ctx "ratified.edn was written")
    ctx))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [_] (k/cycle-project! :verify [(k/tests-check) (assoc runs-answers :command ["no-such-command-xyz"])])))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx] (k/exits! ctx 2) (k/err-has! ctx "answers" "no-such-command-xyz") ctx))

;; ------------------------------------------------------------------ :serves

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
  (fn [_] (let [tag (str "srv" (rand-int 1000000))]
            (assoc (k/cycle-project! :verify [(k/tests-check) (serves-check (free-port) :delay 0.6 :tag tag)]) :tag tag))))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [c (check-named ctx "serves")]
      (k/expect! (= [:passed 2 20 18] [(:outcome c) (:answered-on-poll c) (:polls c) (:margin c)]) ctx (str "the check is " c)))
    ctx))

(defstep #"no process the check started is still running"
  (fn [ctx] (Thread/sleep 300) (k/expect! (not (running? (or (:tag ctx) "servetag"))) ctx "a process is still running") ctx))

(defstep #"a :serves check whose app answers on port P, with :probe-seconds 20"
  (fn [_] (let [port (free-port)]
            (assoc (k/cycle-project! :verify [(k/tests-check) (serves-check port :probe ["sh" "-c" "pwd > probe.txt; echo \"$1\" >> probe.txt" "probe" "{url}"] :probe-seconds 20)])
                   :port port))))

(defstep #"its probe runs in the checkout with \{url\} filled in as \"http://127.0.0.1:P/\", and may run for 20 seconds after the app answered"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [[pwd url] (str/split-lines (slurp (build ctx "probe.txt")))]
      (k/expect! (= (str (fs/canonicalize (build ctx))) pwd) ctx "the probe did not run in the checkout")
      (k/expect! (= (str "http://127.0.0.1:" (:port ctx) "/") url) ctx (str "the url is " url)))
    (k/expect! (= 20 (:probe-seconds (check-named ctx "serves"))) ctx "not 20 seconds")
    ctx))

(defstep #"a :serves check with no :probe-seconds gives its probe 30 seconds"
  (fn [ctx]
    (k/edit-config! (:dir ctx) #(update-in % [:verify 1] dissoc :probe-seconds))
    (k/recut! ctx)
    (fs/delete-tree (k/path (:dir ctx) "dhf/cycles/03/build"))
    (let [r (k/rdm! ctx "verify ROOM 03")]
      (k/exits! r 0)
      (k/expect! (= 30 (:probe-seconds (check-named r "serves"))) r "not 30 seconds"))
    ctx))

(defstep #"a :serves check whose probe, with :probe-seconds 2, prints \"probing\" and then sleeps for 10 seconds"
  (fn [_] (let [tag (str "srv" (rand-int 1000000))]
            (assoc (k/cycle-project! :verify [(k/tests-check) (serves-check (free-port) :tag tag :probe ["sh" "-c" "echo probing; sleep 10"] :probe-seconds 2)]) :tag tag))))

(defstep #"it exits 1 within 6 seconds, the check is :failed, and it says the probe timed out after 2 seconds and relays \"probing\""
  (fn [ctx]
    (k/exits! ctx 1)
    (k/expect! (= :failed (:outcome (check-named ctx "serves"))) ctx "not failed")
    (k/line-has! ctx "timed out after 2 seconds" "probing")
    (k/expect! (< (:seconds (check-named ctx "serves")) 6) ctx "it took 6 seconds or more")
    (k/expect! (not (running? "sleep 10")) ctx "the probe is still running")
    ctx))

(defstep #"a :serves check with the budget of 10 seconds in 20 polls, whose app never answers"
  (fn [_] (let [tag (str "srv" (rand-int 1000000))]
            (assoc (k/cycle-project! :verify [(k/tests-check) (assoc (serves-check (free-port) :tag tag) :start ["bb" "-e" (str "(def tag \"" tag "\") (Thread/sleep 60000)")])]) :tag tag))))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (10|2) seconds"
  (fn [ctx n]
    (k/exits! ctx 1)
    (k/expect! (= :failed (:outcome (check-named ctx "serves"))) ctx "not failed")
    (k/line-has! ctx (str "no answer came within " n " seconds"))
    ctx))

(defstep #"a :serves check on a port another process already holds"
  (fn [_]
    (let [port (free-port)
          holder (k/track-process! (p/process ["bb" "-e" "(require '[org.httpkit.server :as s]) (s/run-server (fn [_] {:status 200 :body \"other\"}) {:port (parse-long (first *command-line-args*))}) @(promise)" (str port)]))]
      (loop [i 0] (when (and (< i 50) (not (try (.close (java.net.Socket. "127.0.0.1" (int port))) true (catch Exception _ false)))) (Thread/sleep 100) (recur (inc i))))
      (assoc (k/cycle-project! :verify [(k/tests-check) (serves-check port :probe ["sh" "-c" "exit 0"])
                                        ])
             :port port :holder holder))))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (k/exits! ctx 2)
    (k/err-has! ctx (str (:port ctx)))
    (k/expect! (not (running? "servetag")) ctx "an app was started")
    ctx))

(defstep #"a :serves check whose app was started and stopped on its port a moment ago, so only closing connections remain"
  (fn [_]
    (let [port (free-port)
          pr (p/process ["bb" "-e" "(require '[org.httpkit.server :as s]) (s/run-server (fn [_] {:status 200 :body \"gone\"}) {:port (parse-long (first *command-line-args*))}) @(promise)" (str port)])]
      (loop [i 0] (when (and (< i 50) (not (try (slurp (str "http://127.0.0.1:" port "/")) true (catch Exception _ false)))) (Thread/sleep 100) (recur (inc i))))
      (p/destroy-tree pr) @pr
      (k/cycle-project! :verify [(k/tests-check) (serves-check port)]))))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= :passed (:outcome (check-named ctx "serves"))) ctx "not passed") ctx))

(defstep #"a :serves check whose app answers and whose probe exits 1"
  (fn [_] (k/cycle-project! :verify [(k/tests-check) (serves-check (free-port) :probe ["sh" "-c" "echo this is not the app; exit 1"])])))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx] (k/exits! ctx 1) (k/expect! (= :failed (:outcome (check-named ctx "serves"))) ctx "not failed") (k/err-has! ctx "this is not the app") ctx))

(defstep #"a :serves check with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
  (fn [_] (k/cycle-project! :verify [(k/tests-check)
                                     (assoc (serves-check (free-port) :seconds 2 :polls 4)
                                            :start ["bb" "-e" "(def tag \"servetag\") (let [s (java.net.ServerSocket. (parse-long (first *command-line-args*)))] (loop [acc []] (recur (conj acc (.accept s)))))" "{port}"])])))

;; ------------------------------------------------------------------ :runs

(defstep #"a :runs check expecting (the exit 0|standard output starting \"\{:help\"|an answer within 10 seconds)"
  (fn [_ expected] (assoc (k/cycle-project!) :expected expected)))

(defstep #"its command (exits 3|prints \"usage\" and exits 0|is still running after 10 seconds)"
  (fn [ctx does]
    (let [cmd (case does "exits 3" ["sh" "-c" "exit 3"] "prints \"usage\" and exits 0" ["sh" "-c" "echo usage"] "is still running after 10 seconds" ["sleep" "30"])
          check (case (:expected ctx)
                  "the exit 0" {:kind :runs :name "answers" :command cmd :expect {:exit 0} :budget {:seconds 10}}
                  "standard output starting \"{:help\"" {:kind :runs :name "answers" :command cmd :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 10}}
                  "an answer within 10 seconds" {:kind :runs :name "answers" :command cmd :expect {:exit 0} :budget {:seconds 10}})]
      (set-verify! ctx [(k/tests-check) check]))))

(defstep #"it exits 1, the check is :failed, and it names (the exit 3 where 0 was expected|the first line it printed|the budget of 10 seconds)"
  (fn [ctx diff]
    (k/exits! ctx 1)
    (k/expect! (= :failed (:outcome (check-named ctx "answers"))) ctx "not failed")
    (case diff
      "the exit 3 where 0 was expected" (k/line-has! ctx "exit 3" "0")
      "the first line it printed" (k/line-has! ctx "usage")
      "the budget of 10 seconds" (k/line-has! ctx "10 seconds"))
    ctx))

;; ------------------------------------------------------------------ the Allure mapping

(def corrupt-script
  "(require '[cheshire.core :as json] '[babashka.fs :as fs])
(def mode (first *command-line-args*))
(doseq [f (fs/glob (System/getenv \"RESULTS_DIR\") \"*-result.json\")
        :let [r (json/parse-string (slurp (str f)))]
        :when (some #(= {\"name\" \"spec\" \"value\" \"spec-0003\"} %) (get r \"labels\"))]
  (let [set-label (fn [r n v] (update r \"labels\" (fn [ls] (mapv #(if (= n (get % \"name\")) (assoc % \"value\" v) %) ls))))
        r (case mode
            \"reword\" (assoc-in r [\"steps\" 1 \"name\"] \"Then something else holds\")
            \"fewer\" (update r \"steps\" (comp vec butlast))
            \"story\" (set-label r \"story\" \"Something else\")
            \"epic\" (set-label r \"epic\" \"elsewhere\")
            \"links\" (assoc r \"links\" [])
            \"evidence\" r)]
    (spit (str f) (json/generate-string r {:pretty true}))))
")

(def mapping-features
  {"shop/cart.feature" (str "@UN-001\nFeature: Cart\n\n  Rule: A rule\n\n    @spec-0003 @e2e\n    Scenario: Three\n      Given a thing\n      Then it holds\n\n"
                            "    @spec-0004 @contract\n    Scenario: Four\n      Given a thing\n")})

(defstep #"a configuration whose :allure is rdm-spec's default mapping"
  (fn [_] (let [ctx (k/cycle-project! :features mapping-features)]
            (k/write! (:dir ctx) "dhf/harness/corrupt.clj" corrupt-script)
            (k/edit-config! (:dir ctx) #(assoc % :allure {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
                                                          :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
                                                          :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}}))
            (k/recut! ctx))))

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx wrong]
    (let [mode (cond (str/starts-with? wrong "a second step reworded") "reword"
                     (str/starts-with? wrong "one step fewer") "fewer"
                     (str/starts-with? wrong "the story") "story"
                     (str/starts-with? wrong "the epic") "epic"
                     (str/starts-with? wrong "no issue link") "links"
                     (str/starts-with? wrong "no browser-trace") "evidence")]
      (k/edit-config! (:dir ctx) #(cond-> (assoc % :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run && bb dhf/harness/corrupt.clj " mode)]}])
                                    (= mode "evidence") (assoc-in [:allure :evidence] {:e2e ["browser-trace"]})))
      (when (= mode "evidence")
        ;; every other scenario's results carry the trace; spec-0003's does not
        (k/write! (get-in ctx [:vars "ROOM"]) "test/steps/all.clj"
                  (str "(require '[rdm-spec.kernel.runner :refer [defstep attach!]])\n"
                       "(defstep #\".*\" (fn [ctx & _] ctx))\n")))
      (k/recut! ctx))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (\S+(?:: \S+)?) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (k/exits! ctx 1)
    (k/expect! (= :failed (:outcome (check-named ctx "tests"))) ctx "the tests check is not failed")
    (k/expect! (some #(re-matches (re-pattern (str "dhf/cycles/03/results/[^ :]+-result\\.json:\\d+: " (java.util.regex.Pattern/quote finding) ": .*spec-0003.*")) %)
                     (k/err-lines ctx))
               ctx (str "no line FILE:LINE: " finding ": ... spec-0003"))
    ctx))

(defstep #"a configuration whose :allure requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
  (fn [_]
    (let [ctx (k/cycle-project! :features {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"} {:id "spec-0002" :wip true}]})}
                                :cfg {:allure {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
                                               :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
                                               :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {:e2e ["browser-trace"]}}}
                                :room-steps (str "(require '[rdm-spec.kernel.runner :refer [defstep attach!]])\n"
                                                 "(defstep #\".*\" (fn [ctx & _] (attach! \"browser-trace\" \"the trace\") ctx))\n"))]
      (k/recut! ctx))))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment" (fn [ctx] ctx))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= :passed (:outcome (check-named ctx "tests"))) ctx "not passed")
    (k/expect! (not (str/includes? (str (:err ctx)) "spec-0002")) ctx "a finding names the planned scenario")
    ctx))

(defstep #"a configuration whose :allure (maps :epic to :aggregate|maps :colour to :context|maps no label \"spec\")"
  (fn [_ is]
    (let [ctx (k/cycle-project!)
          base {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
                :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
                :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}}]
      (k/edit-config! (:dir ctx) #(assoc % :allure (case is
                                                     "maps :epic to :aggregate" (assoc base :epic :aggregate)
                                                     "maps :colour to :context" (assoc base :colour :context)
                                                     "maps no label \"spec\"" (update base :labels dissoc "spec"))))
      (k/recut! ctx))))

(defstep #"a configuration that declares no :allure"
  (fn [_] (k/cycle-project!)))

(defstep #"each result is held to \{.+\}, N the configuration's :needs"
  (fn [ctx]
    (k/exits! ctx 0)
    ;; held: a result whose need link names another file fails the check
    (k/edit-config! (:dir ctx) #(assoc % :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run && sed -i 's|dhf/spec/needs.edn#|elsewhere.edn#|' \"$RESULTS_DIR\"/*-result.json")]}]))
    (k/recut! ctx)
    (let [r (k/rdm! ctx "verify ROOM 03")]
      (k/exits! r 1)
      (k/line-has! r "mapping-differs: links"))
    ctx))

(defstep #"N is dhf/spec/needs.edn for a configuration that names no :needs"
  (fn [ctx]
    (let [c (k/cycle-project!)]
      (k/edit-config! (:dir c) #(dissoc % :needs))
      (k/recut! c)
      (let [r (k/rdm! c "verify ROOM 03")]
        (k/exits! r 0)))
    ctx))

;; ------------------------------------------------------------------ 11

(defstep #"a room whose implementation holds a file its .gitignore excludes, a directory it excludes, and a symbolic link to a file beside it"
  (fn [_]
    (let [ctx (k/cycle-project!) room (get-in ctx [:vars "ROOM"])]
      (k/write! room ".gitignore" "src/secret.txt\nsrc/node_modules/\n")
      (k/write! room "src/secret.txt" "excluded\n")
      (k/write! room "src/node_modules/x/index.js" "excluded\n")
      (k/write! room "src/target.txt" "the target\n")
      (fs/create-sym-link (k/path room "src/link.txt") "target.txt")
      ctx)))

(defstep #"the checkout holds neither the excluded file nor the excluded directory"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (not (fs/exists? (build ctx "src/secret.txt"))) ctx "the excluded file reached the checkout")
    (k/expect! (not (fs/exists? (build ctx "src/node_modules"))) ctx "the excluded directory reached the checkout")
    ctx))

(defstep #"the symbolic link is a symbolic link in the checkout, to the same target"
  (fn [ctx]
    (let [l (fs/path (build ctx "src/link.txt"))]
      (k/expect! (fs/sym-link? l) ctx "not a symbolic link")
      (k/expect! (= "target.txt" (str (fs/read-link l))) ctx "not the same target"))
    ctx))

;; ------------------------------------------------------------------ 08: the :plugin check

(defn judge-project! [answer & {:keys [n] :or {n "judge"}}]
  (let [ctx (k/cycle-project! :verify [(k/tests-check) {:kind :plugin :name n}])]
    (k/plugins! ctx [(k/plugin! ctx n {:answers :Check} answer)])
    (k/recut! ctx)))

(defstep #"a configuration whose :verify declares a :tests check, then \{:kind :plugin :name \"judge\"\}, and whose :plugins has \"judge\" answering :Check"
  (fn [_] (judge-project! nil)))

(defstep #"the tests check passes, and the plugin (exits 0|writes \"src/app.clj:9: form-missing: …\" on its error stream and exits 1|exits 2)"
  (fn [ctx answers]
    (k/write! (:dir ctx) "dhf/harness/plugins/judge/run.clj"
              (k/recorder "judge" (case answers "exits 0" {:exit 0} "exits 2" {:exit 2 :err "no browser"} {:exit 1 :err "src/app.clj:9: form-missing: …"})))
    (k/recut! ctx)))

(defstep #"it (exits 0|exits 1, relaying the finding unchanged|exits 2, naming the plugin), and the check \"judge\" is (:passed|:failed|:refused)"
  (fn [ctx exits outcome]
    (k/exits! ctx (parse-long (subs exits 6 7)))
    (case (subs exits 0 7)
      "exits 1" (k/expect! (some #{"src/app.clj:9: form-missing: …"} (k/err-lines ctx)) ctx "not relayed unchanged")
      "exits 2" (k/err-has! ctx "judge")
      nil)
    (k/expect! (= (keyword (subs outcome 1)) (:outcome (check-named ctx "judge"))) ctx (str "the check is " (check-named ctx "judge")))
    ctx))

(def acceptance-script
  (str "(require '[babashka.process :as p] '[babashka.fs] '[clojure.edn :as edn] '[clojure.string :as str])\n"
       "(let [req (edn/read-string (slurp *in*))]\n"
       "  (spit \"acceptance-request.edn\" (pr-str req))\n"
       "  (let [r (apply p/shell {:out :string :err :string :continue true} (concat (str/split (System/getenv \"RDM_SPEC\") #\" \") [\"run\" \"--results\" (:results req)]))]\n"
       "    (when (= \"drop\" (first *command-line-args*))\n"
       "      (doseq [f (babashka.fs/glob (:results req) \"*-result.json\") :when (str/includes? (slurp (str f)) \"\\\"spec-0002\\\"\")] (babashka.fs/delete f)))\n"
       "    (System/exit 0)))\n"))

(defstep #"a :plugin check \"acceptance\" whose plugin writes Allure results of every scenario into the :results directory its request names"
  (fn [_]
    (let [ctx (k/cycle-project! :verify [(k/tests-check) {:kind :plugin :name "acceptance"}])]
      (k/write! (:dir ctx) "dhf/harness/plugins/acceptance/run.clj" acceptance-script)
      (k/plugins! ctx [{:name "acceptance" :answers :Check :command ["bb" "dhf/harness/plugins/acceptance/run.clj"]}])
      (k/recut! ctx))))

(defstep #"its request held :results \"dhf/cycles/03/results/acceptance\", and each result there was held to the :allure mapping as the tests check's are"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= "dhf/cycles/03/results/acceptance" (:results (edn/read-string (slurp (build ctx "acceptance-request.edn"))))) ctx "not that :results")
    (k/expect! (= 3 (:scenarios (check-named ctx "acceptance"))) ctx "the results were not held")
    ctx))

(defstep #"a result that did not pass, or a scenario with no result, fails the check \"acceptance\", naming the scenario"
  (fn [ctx]
    (k/edit-config! (:dir ctx) #(assoc-in % [:plugins 0 :command] ["bb" "dhf/harness/plugins/acceptance/run.clj" "drop"]))
    (k/recut! ctx)
    (let [r (k/rdm! ctx "verify ROOM 03")]
      (k/exits! r 1)
      (k/expect! (= :failed (:outcome (check-named r "acceptance"))) r "not failed")
      (k/line-has! r "spec-0002"))
    ctx))

(defstep #"a plugin that writes no results is judged by its exit alone, as before"
  (fn [ctx]
    (let [c (judge-project! {:exit 0})
          r (k/rdm! c "verify ROOM 03")]
      (k/exits! r 0)
      (k/expect! (= :passed (:outcome (check-named r "judge"))) r "not passed"))
    ctx))

(defstep #"a :plugin check whose plugin writes what it is given to a file"
  (fn [_] (judge-project! nil)))

(defstep #"the plugin ran in the checkout, dhf/cycles/03/build, and its standard input was \{:request :Check :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\" :results \"dhf/cycles/03/results/judge\"\}, :results as spec-0391 states"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= (str (fs/canonicalize (build ctx))) (str (fs/canonicalize (k/plugin-file ctx "judge" "pwd.txt" (build ctx))))) ctx "not in the checkout")
    (k/expect! (= {:request :Check :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build" :results "dhf/cycles/03/results/judge"}
                  (edn/read-string (k/plugin-file ctx "judge" "given.edn" (build ctx)))) ctx "not that request")
    ctx))

(defstep #"a configuration whose :verify declares \{:kind :plugin :name \"judge\"\}, and whose :plugins has no \"judge\" answering :Check"
  (fn [_] (let [ctx (k/cycle-project! :verify [(k/tests-check) {:kind :plugin :name "judge"}])]
            (k/plugins! ctx [(k/plugin! ctx "judge" {:answers :Gate})])
            (k/recut! ctx))))

;; ------------------------------------------------------------------ 12: a spec-scoped verify

(defstep #"two specs, auth and infra, and dhf/cycles/03/cycle.edn whose :scope is \[\{:spec \"auth\"\}\], and a room whose tests write a passing result for every scenario of auth and none of infra"
  (fn [_]
    (let [ctx (k/two-specs-cycle!)]
      (k/cycle-edn! (:dir ctx) "03" {:cut "spec-3" :scope [{:spec "auth"}]})
      (k/edit-config! (:dir ctx) #(assoc % :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run; rm -f $(grep -l '\"infra-' \"$RESULTS_DIR\"/*-result.json)")]}]))
      (k/recut! ctx))))

(defstep #"the tests check passes, counting the scenarios of auth only"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= 2 (:scenarios (check-named ctx "tests"))) ctx (str "it counts " (:scenarios (check-named ctx "tests"))))
    ctx))

(defstep #"with one of auth's results missing, it fails, naming that scenario"
  (fn [ctx]
    (k/edit-config! (:dir ctx) #(assoc % :verify [{:kind :tests :command ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run; rm -f $(grep -l -e '\"infra-' -e '\"auth-0002\"' \"$RESULTS_DIR\"/*-result.json)")]}]))
    (k/recut! ctx)
    (let [r (k/rdm! ctx "verify ROOM 03")] (k/exits! r 1) (k/line-has! r "auth-0002"))
    ctx))

;; ------------------------------------------------------------------ 13: a slice-scoped verify

(defstep #"a configuration with :spec-layout :slices; the slice 12-several-specs reaching :spec/parser and :measurement/checklist; dhf/cycles/03/cycle.edn whose :scope is \[\{:slice \"12-several-specs\"\}\]; and a room whose tests write a passing result for every scenario of :spec/parser and :measurement/checklist, in any slice, and none for any other component"
  (fn [_]
    (let [ctx (k/slices-project! {"12-several-specs/stamper.feature" nil}) d (:dir ctx) room (k/path (:top ctx) "room")
          ;; stamper's scenarios: spec-0004 (02); their results are removed
          drop-cmd (fn [ids] ["sh" "-c" (str (str/join " " (k/rdm-argv)) " run; rm -f $(grep -l " (str/join " " (for [i ids] (str "-e '\"" i "\"'"))) " \"$RESULTS_DIR\"/*-result.json)")])]
      (k/edit-config! d #(assoc % :implementation ["test"] :verify [{:kind :tests :command (drop-cmd ["spec-0004"])}]))
      (k/git! d "init" "-q")
      (k/commit-all! d "spec-3" "spec-3")
      (k/cycle-edn! d "03" {:cut "spec-3" :scope [{:slice "12-several-specs"}]})
      (k/write! room "test/steps/all.clj" k/catch-all-steps)
      (assoc (assoc-in ctx [:vars "ROOM"] room) :drop-cmd drop-cmd))))

(defstep #"the tests check passes, counting those components' scenarios only"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= 8 (:scenarios (check-named ctx "tests"))) ctx (str "it counts " (:scenarios (check-named ctx "tests"))))
    ctx))

(defstep #"with one of :spec/parser's results missing, it fails, naming that scenario"
  (fn [ctx]
    (k/edit-config! (:dir ctx) #(assoc % :verify [{:kind :tests :command ((:drop-cmd ctx) ["spec-0004" "spec-0007"])}]))
    (k/recut! ctx)
    (let [r (k/rdm! ctx "verify ROOM 03")] (k/exits! r 1) (k/line-has! r "spec-0007"))
    ctx))
