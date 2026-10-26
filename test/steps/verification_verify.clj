(ns steps.verification-verify
  "Step definitions of dhf/spec/verification/verify.feature. The room's test
  command is the kernel's fixture: it runs the project's scenarios with
  rdm-spec run and then does what test/fixture.edn says."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail! defer!]]
            [steps.kernel-command :as k]))

(def core-feature
  "@UN-001
Feature: Core

  @spec-0001 @e2e
  Scenario: The core works
    Given a step
    Then it holds

  @spec-0012 @contract
  Scenario: Twelve
    Given a step

  Rule: A rule

    @spec-0003 @e2e
    Scenario: Three
      Given a step
      When it runs
      Then it holds
")

(defn vproject!
  "A project whose cycle 03 of the cut spec-3 has a room to verify."
  ([ctx] (vproject! ctx nil))
  ([ctx config]
   (if (:dir ctx)
     ctx
     (-> (k/project! ctx {:features {"dhf/spec/kernel/core.feature" core-feature} :config config})
         (k/cycle-ready!)))))

(defn config! [ctx f & args]
  (apply k/update-edn! ctx "dhf/harness/config.edn" f args)
  (k/tag-cut! ctx "spec-3"))

(defn verify [ctx] (k/rdm ctx ["verify" (get-in ctx [:ph "ROOM"]) "03"]))

(defn cycle-file [ctx p] (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03" p))

(defn ratified [ctx]
  (let [f (cycle-file ctx "ratified.edn")]
    (when-not (fs/exists? f) (fail! (str "no ratified.edn: " (:err ctx))))
    (edn/read-string (slurp f))))

(defn checks [ctx] (get-in (k/edn-out ctx) [:verify :checks]))

(defn check-named [ctx n]
  (or (first (filter #(= n (:name %)) (checks ctx))) (fail! (str "no check " n ": " (:out ctx) (:err ctx)))))

(defn build-files [ctx]
  (let [d (fs/path (k/guard! (:dir ctx)) "dhf/cycles/03/build")]
    (->> (fs/glob d "**" {:hidden true}) (filter fs/regular-file?) (map #(str (fs/relativize d %))) sort vec)))

(defn free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

;; --- trusting only the cut

(defstep #"it verifies against a fresh export of spec-3: its files, and no history"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [files (build-files ctx)
          cut (remove #(re-find #"^(src|test)/|^test\.clj$" %)
                      (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "--name-only" "spec-3")))]
      (assert (not-any? #(str/starts-with? % ".git/") files) "the checkout holds history")
      (assert (every? (set files) cut) (pr-str (remove (set files) cut)))
      ctx)))

(defstep #"no dhf/cycles/03/cycle.edn"
  (fn [ctx] (let [ctx (k/room! (k/project! ctx))] (k/tag-cut! ctx "spec-3"))))

(defstep #"it exits 2 and names the missing file"
  (fn [ctx] (k/check-exit ctx 2) (k/err-has ctx "dhf/cycles/03/cycle.edn")
    (assert (not (fs/exists? (cycle-file ctx "ratified.edn")))) ctx))

(defstep #"the cut spec-3 holds src/rdm_spec/old.clj and the room holds no such file"
  (fn [ctx]
    (-> (k/project! ctx {:features {"dhf/spec/kernel/core.feature" core-feature}
                         :extra {"src/rdm_spec/old.clj" "(ns rdm-spec.old)\n"}})
        (k/cycle-ready!))))

(defstep #"the checkout it verifies holds no src/rdm_spec/old.clj"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (some #{"src/rdm_spec/old.clj"} (build-files ctx))))
    ctx))

(defn room-impl [ctx]
  (let [r (get-in ctx [:ph "ROOM"])]
    (->> (fs/glob r "**" {:hidden true}) (filter fs/regular-file?) (map #(str (fs/relativize r %)))
         (filter #(re-find #"^(src|test)/|^test\.clj$" %)) sort vec)))

(defstep #"every configured implementation path holds exactly what the room holds"
  (fn [ctx]
    (let [b (filter #(re-find #"^(src|test)/|^test\.clj$" %) (build-files ctx))]
      (assert (= (room-impl ctx) b) (str (room-impl ctx) " / " b))
      (doseq [f b]
        (assert (= (slurp (fs/file (k/guard! (get-in ctx [:ph "ROOM"])) f)) (slurp (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03/build" f))) f))
      ctx)))

(defstep #"the builder has edited dhf/harness/config.edn and a feature file in the room"
  (fn [ctx]
    (let [ctx (vproject! ctx)]
      (k/write-files! (get-in ctx [:ph "ROOM"]) {"dhf/harness/config.edn" "{:id-prefix \"evil\" :verify [{:kind :tests :command [\"true\"]}]}\n"
                                                 "dhf/spec/kernel/core.feature" "Feature: Edited\n"})
      ctx)))

(defstep #"the configuration and the spec used are those of spec-3, unedited"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [f ["dhf/harness/config.edn" "dhf/spec/kernel/core.feature"]]
      (assert (= (k/git (:dir ctx) "show" (str "spec-3:" f)) (str/trim (slurp (fs/file (k/guard! (:dir ctx)) "dhf/cycles/03/build" f)))) f))
    ctx))

;; --- the tests check

(defstep #"\"rdm-spec verify ROOM 03\" runs the command of the :tests check"
  (fn [ctx]
    (let [ctx (vproject! ctx)
          rec (k/path-in ctx "results-dir.txt")]
      (k/fixture! ctx {:record-env rec})
      (assoc (verify ctx) ::rec rec))))

(defstep #"the command runs with the environment variable RESULTS_DIR set to the absolute path of dhf/cycles/03/results in the repository rdm-spec verify was run in"
  (fn [ctx]
    (assert (= (str (fs/path (k/guard! (:dir ctx)) "dhf/cycles/03/results")) (slurp (::rec ctx))) (slurp (::rec ctx)))
    ctx))

(defstep #"the :tests check's command exits 0"
  (fn [ctx] (vproject! ctx)))

(defstep #"it wrote Allure results into dhf/cycles/03/results"
  (fn [ctx] ctx))

(defstep #"every stamped scenario not tagged @wip is named by at least one result, and every result naming it passed"
  (fn [ctx] ctx))

(defstep #"the tests check passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "tests"))))
    (assert (seq (fs/glob (fs/path (k/guard! (:dir ctx)) "dhf/cycles/03/results") "*-result.json")))
    ctx))

(defstep #"the :tests check's command exits 1"
  (fn [ctx] (let [ctx (vproject! ctx)] (k/fixture! ctx {:exit 1}) ctx)))

(defstep #"it exits 1 and says the test command failed, with its exit status"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "test command failed" "1")))

(defstep #"the test command's output is in dhf/cycles/03/test.log, which the refusal names"
  (fn [ctx]
    (k/err-has ctx "dhf/cycles/03/test.log")
    (assert (fs/exists? (cycle-file ctx "test.log")))
    (assert (str/includes? (slurp (cycle-file ctx "test.log")) "scenarios") (slurp (cycle-file ctx "test.log")))
    ctx))

(defstep #"the :tests check's command exits 0 without writing any Allure result"
  (fn [ctx] (let [ctx (vproject! ctx)] (k/fixture! ctx {:no-results true}) ctx)))

(defstep #"it exits 1 and says a green exit with no results is not a run"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "no results is not a run")))

(defstep #"the stamped scenario @spec-0012 is not tagged @wip"
  (fn [ctx] (vproject! ctx)))

(defstep #"no result in dhf/cycles/03/results is labelled spec=spec-0012"
  (fn [ctx] (k/fixture! ctx {:mutate {"spec-0012" [[:drop]]}})))

(defstep #"it exits 1 and names spec-0012"
  (fn [ctx] (k/check-exit ctx 1) (k/err-has ctx "spec-0012")))

(defstep #"a result in dhf/cycles/03/results labelled spec=spec-0012 is failed"
  (fn [ctx] (k/fixture! (vproject! ctx) {:mutate {"spec-0012" [[:status "failed"]]}})))

(defstep #"it exits 1 and names spec-0012 and its status"
  (fn [ctx] (k/check-exit ctx 1) (k/err-line-with ctx "spec-0012" "failed")))

;; --- the record

(def answers {:kind :runs :name "answers" :command ["bb" "-e" "(println \"{:help []}\")"]
              :expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}})

(defn tree-hash
  "The git tree hash of the room's implementation, as git writes it."
  [ctx]
  (let [d (k/path-in ctx "hash-repo")]
    (fs/create-dirs d)
    (k/git-init! d)
    (doseq [f (room-impl ctx)]
      (fs/create-dirs (fs/parent (fs/path d f)))
      (fs/copy (fs/path (get-in ctx [:ph "ROOM"]) f) (fs/path d f) {:replace-existing true :copy-attributes true}))
    (k/git d "add" "-A")
    (k/git d "write-tree")))

(defstep #"a room whose implementation has a tree hash H, and a configuration declaring a :tests check and a :runs check"
  (fn [ctx]
    (let [ctx (vproject! ctx {:verify [{:kind :tests :command ["bb" "test.clj"]} answers]})]
      (assoc ctx ::h (tree-hash ctx)))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and passes"
  (fn [ctx] (k/check-exit (verify ctx) 0)))

(defstep #"dhf/cycles/03/ratified.edn holds exactly :cut \"spec-3\", :room-tree H in full, :outcome :passed, :checkout \"dhf/cycles/03/build\" and :checks, each check as its standard output reports it"
  (fn [ctx]
    (let [r (ratified ctx)]
      (assert (= #{:cut :room-tree :outcome :checkout :checks} (set (keys r))) (pr-str r))
      (assert (= ["spec-3" (::h ctx) :passed "dhf/cycles/03/build"] [(:cut r) (:room-tree r) (:outcome r) (:checkout r)]) (pr-str r))
      (assert (= (checks ctx) (:checks r)))
      ctx)))

(defstep #"dhf/cycles/03/build is the verified checkout, which the grader grades"
  (fn [ctx]
    (assert (some #{"dhf/spec/kernel/core.feature"} (build-files ctx)))
    (assert (some #{"test.clj"} (build-files ctx)))
    ctx))

(defstep #"\"rdm-spec verify ROOM 03\" is run and the :tests check's command exits 1"
  (fn [ctx] (let [ctx (vproject! ctx)] (k/fixture! ctx {:exit 1}) (verify ctx))))

(defstep #"dhf/cycles/03/ratified.edn holds :outcome :refused and the reason"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [r (ratified ctx)]
      (assert (= :refused (:outcome r)))
      (assert (str/includes? (str (:reason r)) "test command failed") (pr-str r))
      ctx)))

(defstep #"it exits 1 and names the file and line of the unstamped scenario"
  (fn [ctx] (k/check-exit ctx 1) (apply k/err-line-with ctx (:names ctx))))

;; --- the checks a project declares

(def probe-clj "(let [[url mode] *command-line-args*] (if (= mode \"fail\") (do (println \"the probe says: not this application\") (System/exit 1)) (do (println \"probe ok\" url) (System/exit 0))))\n")

(def app-clj
  "(require '[org.httpkit.server :as s])
(let [[port delay pidfile mode] *command-line-args*]
  (spit pidfile (str (.pid (java.lang.ProcessHandle/current))))
  (Thread/sleep (parse-long delay))
  (case mode
    \"http\" (s/run-server (fn [_] {:status 200 :body \"ok\"}) {:port (parse-long port)})
    \"silent\" (let [ss (java.net.ServerSocket. (parse-long port))] (future (loop [acc []] (recur (conj acc (.accept ss))))))
    \"never\" nil)
  @(promise))
")

(defn serves [ctx {:keys [seconds polls delay mode probe port] :or {seconds 10 polls 20 delay 0 mode "http" probe "ok"}}]
  (let [pidfile (k/path-in ctx "app.pid")]
    [{:kind :serves :name "app" :start ["bb" "src/app.clj" "{port}" (str delay) pidfile mode] :port port
      :budget {:seconds seconds :polls polls} :probe ["bb" "dhf/harness/probe.clj" "{url}" probe]}
     pidfile]))

(defn serves-project!
  "A project whose :verify is the tests check then a :serves check."
  [ctx opts]
  (let [ctx (k/base! ctx)
        port (or (:port opts) (free-port))
        [check pid] (serves ctx (assoc opts :port port))
        ctx (vproject! ctx {:verify (into [{:kind :tests :command ["bb" "test.clj"]} check] (:more opts))})]
    (k/write-files! (get-in ctx [:ph "ROOM"]) {"src/app.clj" app-clj})
    (k/write-files! (:dir ctx) {"dhf/harness/probe.clj" probe-clj})
    (k/tag-cut! ctx "spec-3")
    (defer! #(when (fs/exists? pid) (k/sh (:base ctx) {} "kill" "-9" (str/trim (slurp pid)))))
    (assoc ctx ::pid pid ::port port)))

(def verify-configs
  {"declares a :tests check, then a :runs check named \"answers\""
   [{:kind :tests :command ["bb" "test.clj"]} answers]
   "declares no :tests check" [answers]
   "declares a check of the kind :renders" [{:kind :tests :command ["bb" "test.clj"]} {:kind :renders :name "x" :command ["true"]}]
   "declares a :serves check whose probe is under src/"
   [{:kind :tests :command ["bb" "test.clj"]}
    {:kind :serves :name "app" :start ["bb" "src/app.clj" "{port}"] :port 18999 :budget {:seconds 2 :polls 2} :probe ["bb" "src/probe.clj" "{url}"]}]
   "is absent" ::absent
   "declares a :tests check with no command" [{:kind :tests}]})

(defstep #"a configuration whose :verify declares a :tests check, a :serves check and a :runs check, and the :tests check's command exits 1"
  (fn [ctx]
    (let [ctx (serves-project! ctx {:more [answers]})]
      (k/fixture! ctx {:exit 1}))))

(defstep #"a configuration whose :verify (?!declares a :tests check, a :serves check)(.+)"
  (fn [ctx what]
    (let [v (get verify-configs what ::none)]
      (when (= ::none v) (fail! (str "no such :verify: " what)))
      (let [ctx (vproject! ctx)
            marker (k/path-in ctx "ran.txt")]
        (k/fixture! ctx {:ran marker})
        (-> (config! ctx #(if (= ::absent v) (dissoc % :verify) (assoc % :verify v)))
            (assoc ::ran marker))))))

(defstep #"both pass"
  (fn [ctx] ctx))

(defstep #"it exits 0, and its standard output, read as EDN, is exactly \{:verify .*\}, S1 and S2 the seconds each took, N the stamped scenarios not tagged @wip that results name, B the check's budget in seconds and M the seconds of it left unused"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [o (k/edn-out ctx)
          [t r] (get-in o [:verify :checks])]
      (assert (= {:cycle "03" :cut "spec-3" :outcome :passed} (dissoc (:verify o) :checks)) (:out ctx))
      (assert (= #{:name :kind :outcome :seconds :scenarios} (set (keys t))) (pr-str t))
      (assert (= ["tests" :tests :passed 3] [(:name t) (:kind t) (:outcome t) (:scenarios t)]) (pr-str t))
      (assert (number? (:seconds t)))
      (assert (= #{:name :kind :outcome :seconds :budget :margin} (set (keys r))) (pr-str r))
      (assert (= ["answers" :runs :passed 30] [(:name r) (:kind r) (:outcome r) (:budget r)]) (pr-str r))
      (assert (< (Math/abs (- (:margin r) (- 30 (:seconds r)))) 0.011) (pr-str r))
      ctx)))

(defstep #"it exits 1, the tests check is :failed, and the :serves and :runs checks are listed with the outcome :not-run"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= [[:tests :failed] [:serves :not-run] [:runs :not-run]] (map (juxt :kind :outcome) (checks ctx))) (:out ctx))
    (assert (not (fs/exists? (::pid ctx))) "an app was started")
    ctx))

(def whys
  {"a project is verified by its tests first" ["tests"]
   ":renders is not a kind of check" [":renders" "not a kind"]
   "the probe is in the implementation the builder writes" ["probe" "implementation"]
   "the configuration declares no :verify" [":verify"]
   "the tests check names no command" ["tests" "no command"]
   ":aggregate is not an element of the spec" [":aggregate" "not an element of the spec"]
   ":colour is not an element of an Allure result" [":colour" "not an element of an Allure result"]
   "the label spec, from the scenario's id, is required" ["spec" "required"]})

(defstep #"it exits 2, says (.+), and runs no check"
  (fn [ctx why]
    (k/check-exit ctx 2)
    (apply k/err-line-with ctx (or (whys why) (fail! (str "unknown why: " why))))
    (assert (not (fs/exists? (::ran ctx))) "a check ran")
    (assert (not (fs/exists? (cycle-file ctx "ratified.edn"))))
    ctx))

(defstep #"a configuration whose :runs check names a command that is not on PATH"
  (fn [ctx]
    (-> (vproject! ctx {:verify [{:kind :tests :command ["bb" "test.clj"]}
                                 (assoc answers :command ["no-such-command-anywhere" "help"])]}))))

(defstep #"it exits 2 and names the check and the command it could not find"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "answers" "no-such-command-anywhere")
    (assert (not (fs/exists? (cycle-file ctx "ratified.edn"))))
    ctx))

;; --- :serves

(defn alive? [pidfile]
  (and (fs/exists? pidfile)
       (= 0 (:exit (k/sh (fs/parent pidfile) {} "kill" "-0" (str/trim (slurp pidfile)))))))

(defstep #"a :serves check (.+)"
  (fn [ctx what]
    (case what
      "with the budget of 10 seconds in 20 polls, whose app answers on the second poll and whose probe exits 0"
      (serves-project! ctx {:seconds 10 :polls 20 :delay 650})
      "with the budget of 10 seconds in 20 polls, whose app never answers"
      (serves-project! ctx {:seconds 10 :polls 20 :mode "never"})
      "on a port another process already holds"
      (let [ss (java.net.ServerSocket. 0)
            ctx (serves-project! ctx {:port (.getLocalPort ss) :seconds 2 :polls 2})]
        (defer! #(.close ss))
        ctx)
      "whose app was started and stopped on its port a moment ago, so only closing connections remain"
      (let [port (free-port)
            ss (java.net.ServerSocket. port)
            client (java.net.Socket. "127.0.0.1" (int port))
            conn (.accept ss)]
        ;; the server side closes first, so its end of the connection waits in TIME_WAIT
        (.close conn) (.close ss) (Thread/sleep 100) (.close client)
        (serves-project! ctx {:port port :seconds 10 :polls 20}))
      "whose app answers and whose probe exits 1"
      (serves-project! ctx {:seconds 10 :polls 20 :probe "fail"})
      "with the budget of 2 seconds in 4 polls, whose start command listens on its port and never writes a response"
      (serves-project! ctx {:seconds 2 :polls 4 :mode "silent"})
      (fail! (str "no such :serves check: " what)))))

(defstep #"the check passes with :answered-on-poll 2, :polls 20 and :margin 18"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [c (check-named ctx "app")]
      (assert (= [:passed 2 20 18] [(:outcome c) (:answered-on-poll c) (:polls c) (:margin c)]) (pr-str c))
      ctx)))

(defstep #"no process the check started is still running"
  (fn [ctx]
    (assert (fs/exists? (::pid ctx)) "the app never started")
    (assert (not (alive? (::pid ctx))) "the app is still running")
    ctx))

(defstep #"it exits 1, the check is :failed, and it says no answer came within (\d+) seconds"
  (fn [ctx n]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "app"))))
    (k/err-line-with ctx "no answer came within" (str n " seconds"))
    (when (fs/exists? (::pid ctx)) (assert (not (alive? (::pid ctx)))))
    ctx))

(defstep #"it exits 2, names the port, and starts no app of a :serves check"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-has ctx (str (::port ctx)))
    (assert (not (fs/exists? (::pid ctx))) "an app was started")
    ctx))

(defstep #"the check starts its app on that port and passes"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "app"))))
    (assert (fs/exists? (::pid ctx)))
    ctx))

(defstep #"it exits 1, the check is :failed, and it relays the probe's output"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "app"))))
    (k/err-has ctx "the probe says: not this application")
    ctx))

;; --- :runs

(def expectations
  {"the exit 0" {:expect {:exit 0} :budget {:seconds 30}}
   "standard output starting \"{:help\"" {:expect {:exit 0 :stdout-starts "{:help"} :budget {:seconds 30}}
   "an answer within 10 seconds" {:expect {:exit 0} :budget {:seconds 10}}})

(def doings
  {"exits 3" ["bb" "-e" "(System/exit 3)"]
   "prints \"usage\" and exits 0" ["bb" "-e" "(println \"usage\")"]
   "is still running after 10 seconds" ["bb" "-e" "(Thread/sleep 60000)"]})

(defstep #"a :runs check expecting (.+)"
  (fn [ctx e] (assoc ctx ::expect (or (expectations e) (fail! e)))))

(defstep #"its command (.+)"
  (fn [ctx d]
    (vproject! ctx {:verify [{:kind :tests :command ["bb" "test.clj"]}
                             (merge {:kind :runs :name "answers" :command (or (doings d) (fail! d))} (::expect ctx))]})))

(defstep #"it exits 1, the check is :failed, and it names (.+)"
  (fn [ctx what]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "answers"))))
    (case what
      "the exit 3 where 0 was expected" (k/err-line-with ctx "answers" "exit 3" "0")
      "the first line it printed" (k/err-line-with ctx "answers" "usage")
      "the budget of 10 seconds" (k/err-line-with ctx "answers" "10 seconds")
      (fail! what))
    ctx))

;; --- the Allure mapping

(def default-mapping
  {:epic :context :feature :feature :story :rule :name :scenario :step :gherkin-step :parameter :example-row
   :labels {"spec" :scenario-id "component" :component "tag" :gherkin-tags}
   :links {:need {:type "issue" :url "dhf/spec/needs.edn#{id}"}} :evidence {}})

(def allure-configs
  {"is rdm-spec's default mapping" default-mapping
   "requires a browser-trace attachment for @e2e, and a stamped scenario tagged @e2e and @wip"
   (assoc default-mapping :evidence {:e2e ["browser-trace"]})
   "maps :epic to :aggregate" (assoc default-mapping :epic :aggregate)
   "maps :colour to :context" (assoc default-mapping :colour :context)
   "maps no label \"spec\"" (update default-mapping :labels dissoc "spec")})

(defstep #"a configuration whose :allure (.+)"
  (fn [ctx what]
    (let [m (or (allure-configs what) (fail! (str "no such :allure: " what)))
          ctx (vproject! ctx)
          marker (k/path-in ctx "ran.txt")]
      (k/fixture! ctx {:ran marker})
      (when (str/includes? what "@wip")
        (let [f (fs/file (k/guard! (:dir ctx)) "dhf/spec/kernel/core.feature")]
          (spit f (str (slurp f) "\n    @spec-0020 @e2e @wip\n    Scenario: Planned\n      Given a step\n"))))
      (-> (config! ctx assoc :allure m) (assoc ::ran marker)))))

(def wrongs
  {"a second step reworded from the scenario's line" [[[:step-name 1 "When it runs fast"]] "steps-differ-from-scenario"]
   "one step fewer than the scenario's lines" [[[:drop-step]] "steps-differ-from-scenario"]
   "the story \"Something else\", not the scenario's rule" [[[:label "story" "Something else"]] "mapping-differs: story"]
   "the epic of another bounded context" [[[:label "epic" "billing"]] "mapping-differs: epic"]
   "no issue link to UN-001, the need the scenario traces to" [[[:no-links]] "mapping-differs: links"]
   "no browser-trace attachment, where :evidence requires one for @e2e" [[[:no-attachments]] "evidence-missing: browser-trace"]})

(defstep #"the tests check's results for @spec-0003 hold (.+)"
  (fn [ctx what]
    (let [[ops _] (or (wrongs what) (fail! (str "no such wrong: " what)))]
      (when (str/includes? what "browser-trace")
        (config! ctx assoc-in [:allure :evidence] {:e2e ["browser-trace"]}))
      (k/fixture! ctx {:mutate {"spec-0003" ops}}))))

(defstep #"it exits 1, the tests check is :failed, and it names spec-0003, (.+) and the result's file, as FILE:LINE: RULE: what \(the result's file\)"
  (fn [ctx finding]
    (k/check-exit ctx 1)
    (assert (= :failed (:outcome (check-named ctx "tests"))))
    (let [l (first (filter #(str/includes? % finding) (k/err-lines ctx)))]
      (assert l (str "no line names " finding ": " (:err ctx)))
      (assert (re-find (re-pattern (str "^dhf/spec/kernel/core\\.feature:\\d+: " (java.util.regex.Pattern/quote finding))) l) l)
      (assert (str/includes? l "spec-0003") l)
      (assert (re-find #"dhf/cycles/03/results/[^ ]+-result\.json" l) l))
    ctx))

(defstep #"the tests check's results hold that scenario's skipped result, labelled planned, with no attachment"
  (fn [ctx] ctx))

(defstep #"the tests check passes, and names no finding about that scenario"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :passed (:outcome (check-named ctx "tests"))))
    (assert (not (str/includes? (:err ctx) "spec-0020")) (:err ctx))
    (let [planned (filter #(str/includes? (slurp (fs/file %)) "\"spec-0020\"") (fs/glob (fs/path (k/guard! (:dir ctx)) "dhf/cycles/03/results") "*-result.json"))]
      (assert (= 1 (count planned)))
      (assert (str/includes? (slurp (fs/file (first planned))) "\"skipped\"")))
    ctx))

(defstep #"a configuration that declares no :allure"
  (fn [ctx] (-> (vproject! ctx) (config! dissoc :allure))))

(defstep #"each result is held to \{.*\}"
  (fn [ctx]
    (k/check-exit ctx 0)
    (doseq [[what [ops finding]] (dissoc wrongs "no browser-trace attachment, where :evidence requires one for @e2e")]
      (k/fixture! ctx {:mutate {"spec-0003" nil}})
      (let [f (fs/file (k/guard! (get-in ctx [:ph "ROOM"])) "test/fixture.edn")]
        (spit f (pr-str (assoc (edn/read-string (slurp f)) :mutate {"spec-0003" ops}))))
      (let [r (verify ctx)]
        (k/check-exit r 1)
        (k/err-line-with r finding "spec-0003")))
    ctx))
