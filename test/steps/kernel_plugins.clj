(ns steps.kernel-plugins
  "Steps of the kernel component Plugins (dhf/spec/08-plugins/plugins.feature).
  A plugin here is a small shell command kept under dhf/harness/plugins/."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defn plugin-script!
  "Writes dhf/harness/plugins/NAME/run.sh holding BODY; returns its command."
  [ctx name body]
  (k/write! ctx (str "dhf/harness/plugins/" name "/run.sh") (str "#!/bin/sh\n" body "\n"))
  ["sh" (str "dhf/harness/plugins/" name "/run.sh")])

(defn plugins! [ctx entries] (k/config! ctx assoc :plugins entries) ctx)

(defn recorder
  "A script body recording what the plugin is given, where it runs, and its environment."
  [name]
  (str "cat > " name ".in.edn\npwd > " name ".pwd\nenv > " name ".env\necho ran >> " name ".runs\n"))

(defn received [ctx name] (some-> (k/slurp-rel ctx (str name ".in.edn")) edn/read-string))
(defn runs-of [ctx name] (count (remove str/blank? (str/split-lines (or (k/slurp-rel ctx (str name ".runs")) "")))))
(defn env-of [ctx name]
  (into {} (for [l (str/split-lines (or (k/slurp-rel ctx (str name ".env")) "")) :let [[a b] (str/split l #"=" 2)] :when b] [a b])))

;; ------------------------------------------------------------------ declared

(defstep #"a configuration whose :plugins is \[\{:name \"lint-a\" :subscribes \[:SpecParsed\] :command \[\"bb\" \"dhf/harness/plugins/lint-a/run.clj\"\] :seconds 20\} \{:name \"oracle\" :answers :Gate :command \[\"bb\" \"dhf/harness/oracle/oracle.clj\"\]\}\]"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (k/write! ctx "dhf/harness/plugins/lint-a/run.clj" "(spit \"lint-a.ran\" (slurp *in*))\n")
      (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/lint-a/run.clj"] :seconds 20}
                     {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}]))))

(defstep #"each entry is read as exactly :name, :command, one of :subscribes \(a vector of event names\) or :answers \(:Gate or :Check\), an optional :seconds, its time limit, and an optional :env"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (fs/exists? (k/path ctx "lint-a.ran")) "lint-a did not run: " (k/shows ctx))
    (let [declared ((requiring-resolve 'rdm-spec.kernel.plugins/declared) (k/read-config ctx))]
      (k/check! (= [{:name "lint-a" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/lint-a/run.clj"] :seconds 20 :env {}}
                    {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"] :seconds 60 :env {}}]
                   declared)
                "the plugins are read as " (pr-str declared)))
    ctx))

(defstep #"a plugin with no :seconds has 60 seconds"
  (fn [ctx]
    (k/check! (= 60 (:seconds (first ((requiring-resolve 'rdm-spec.kernel.plugins/declared) {:plugins [{:name "x" :answers :Gate :command ["x"]}]}))))
              "a plugin with no :seconds has another limit")
    ctx))

(defn bad-entry [ctx entry]
  (let [cmd (plugin-script! ctx "lint-a" "exit 0")]
    (case entry
      "a plugin with neither :subscribes nor :answers" [{:name "lint-a" :command cmd}]
      "a plugin with both :subscribes and :answers" [{:name "lint-a" :command cmd :subscribes [:SpecParsed] :answers :Gate}]
      "a plugin subscribing to :NoSuchEvent" [{:name "lint-a" :command cmd :subscribes [:NoSuchEvent]}]
      "a plugin answering :Review" [{:name "lint-a" :command cmd :answers :Review}]
      "two plugins named \"lint-a\"" [{:name "lint-a" :command cmd :subscribes [:SpecParsed]}
                                      {:name "lint-a" :command cmd :answers :Gate}]
      "a plugin whose command is under the implementation's paths"
      (do (k/config! ctx assoc :implementation ["src"]) [{:name "lint-a" :command ["bb" "src/judge.clj"] :subscribes [:SpecParsed]}]))))

(defstep #"a configuration whose :plugins holds (a plugin with neither :subscribes nor :answers|a plugin with both :subscribes and :answers|a plugin subscribing to :NoSuchEvent|a plugin answering :Review|two plugins named \"lint-a\"|a plugin whose command is under the implementation's paths)"
  (fn [ctx entry]
    (let [ctx (k/spec-project! ctx)
          ctx (plugins! ctx (bad-entry ctx entry))]
      (plugin-script! ctx "lint-a" (recorder "lint-a"))
      ctx)))

(defstep #"it exits 2, names the plugin, and says (.+)"
  (fn [ctx why]
    (k/exits! ctx 2)
    (k/err-has! ctx "lint-a")
    (let [why (str/replace why ", and lists the events it publishes" "")]
      (k/err-has! ctx why)
      (when (str/includes? why "NoSuchEvent") (k/err-has! ctx "SpecParsed" "KnowledgeIndexed" "CycleVerified")))
    (k/check! (zero? (runs-of ctx "lint-a")) "the plugin ran")
    ctx))

;; ------------------------------------------------------------------ subscribers

(defstep #"a plugin subscribing to :SpecParsed whose command writes what it is given to a file in the project"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command (plugin-script! ctx "lint-a" (recorder "lint-a"))}]))))

(defstep #"the plugin ran once, in the project's root, with the environment variable RDM_SPEC set as the oracle's is"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/check! (= 1 (runs-of ctx "lint-a")) "the plugin ran " (runs-of ctx "lint-a") " times")
    (k/check! (= (str (fs/canonicalize (:dir ctx))) (str/trim (k/slurp-rel ctx "lint-a.pwd"))) "the plugin ran in " (k/slurp-rel ctx "lint-a.pwd"))
    (k/check! (str/includes? (str (get (env-of ctx "lint-a") "RDM_SPEC")) "rdm-spec.kernel.command") "RDM_SPEC is " (get (env-of ctx "lint-a") "RDM_SPEC"))
    ctx))

(defstep #"its standard input was the SpecParsed event as EDN: \{:event :SpecParsed\} merged with every key events.edn says the event carries"
  (fn [ctx]
    (let [m (received ctx "lint-a")]
      (k/check! (= #{:event :revision :scenarios :needs :refusals} (set (keys m))) "the plugin was given " (pr-str (keys m)))
      (k/check! (= :SpecParsed (:event m)) "the event is " (:event m))
      (k/check! (= ["spec-0001"] (map :id (:scenarios m))) "the scenarios are " (pr-str (:scenarios m)))
      ctx)))

(defstep #"two plugins subscribing to :SpecParsed, \"lint-a\" then \"lint-b\", each writing its name and the time to a shared file"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)
          body (fn [n] (str "echo \"" n " $(date +%s%N)\" >> shared.txt"))]
      (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command (plugin-script! ctx "lint-a" (body "lint-a"))}
                     {:name "lint-b" :subscribes [:SpecParsed] :command (plugin-script! ctx "lint-b" (body "lint-b"))}]))))

(defstep #"lint-a ran before lint-b, and both after the spec check's own stages"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [ls (str/split-lines (k/slurp-rel ctx "shared.txt"))]
      (k/check! (= ["lint-a" "lint-b"] (map #(first (str/split % #" ")) ls)) "the plugins ran as " (pr-str ls))
      ctx)))

(defstep #"a plugin subscribing only to :VerificationDerived"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (plugins! ctx [{:name "lint-a" :subscribes [:VerificationDerived] :command (plugin-script! ctx "lint-a" (recorder "lint-a"))}]))))

(defstep #"the plugin did not run"
  (fn [ctx] (k/check! (zero? (runs-of ctx "lint-a")) "the plugin ran") ctx))

;; ------------------------------------------------------------------ one interface

(def plugin-does
  {"writes \"dhf/spec/a/b.feature:4: action-unoffered: no form offers it\" on its error stream and exits 1"
   "echo 'dhf/spec/a/b.feature:4: action-unoffered: no form offers it' >&2\nexit 1"
   "writes \"dhf/spec/a/b.feature:4: warning: label-long: …\" on its error stream and exits 0"
   "echo 'dhf/spec/a/b.feature:4: warning: label-long: …' >&2\nexit 0"
   "writes nothing and exits 0" "exit 0"
   "exits 2, writing \"cannot read affordances.yaml\" on its error stream"
   "echo 'cannot read affordances.yaml' >&2\nexit 2"})

(defstep #"a plugin subscribing to :SpecParsed that (.+)"
  (fn [ctx does]
    (let [ctx (k/spec-project! ctx)]
      (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command (plugin-script! ctx "lint-a" (plugin-does does))}]))))

(defstep #"\"rdm-spec check\" is run on a spec that passes every check"
  (fn [ctx] (k/run-rdm ctx "check")))

(defstep #"it (exits \d), and the error stream (.+)"
  (fn [ctx exits holds]
    (k/exits! ctx (parse-long (subs exits 6)))
    (let [ls (k/err-lines ctx)]
      (case holds
        "that line, unchanged, as a finding"
        (k/check! (some #{"dhf/spec/a/b.feature:4: action-unoffered: no form offers it"} ls) "the line is not relayed: " (k/shows ctx))
        "that line, unchanged, as a warning"
        (k/check! (some #{"dhf/spec/a/b.feature:4: warning: label-long: …"} ls) "the line is not relayed: " (k/shows ctx))
        "no line from the plugin"
        (k/check! (not-any? #(str/includes? % "lint-a") ls) "a line names the plugin: " (k/shows ctx))
        "cannot-run, naming the plugin and relaying its line"
        (k/err-line-has! ctx "cannot-run" "lint-a" "cannot read affordances.yaml"))
      ctx)))

(defstep #"a plugin subscribing to :SpecParsed with :seconds 2, which prints \"started\" and then sleeps for 10 seconds"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)
          marker (str "10." (rand-int 100000000))]
      (-> (plugins! ctx [{:name "slow" :subscribes [:SpecParsed] :seconds 2
                          :command (plugin-script! ctx "slow" (str "echo started\nsleep " marker))}])
          (assoc ::marker marker)))))

(defn running? [pattern]
  (let [r @(p/process ["pgrep" "-f" pattern] {:out :string :err :string})]
    (zero? (:exit r))))

(defstep #"it exits 2 within 5 seconds, says the plugin timed out after 2 seconds, and relays \"started\""
  (fn [ctx]
    (let [t0 (System/currentTimeMillis)
          ctx (k/run-rdm ctx "check")
          secs (/ (- (System/currentTimeMillis) t0) 1000.0)]
      (k/exits! ctx 2)
      (k/check! (< secs 5) "it took " secs " seconds")
      (k/err-line-has! ctx "slow" "timed out after 2 seconds")
      (k/err-has! ctx "started")
      ctx)))

(defstep #"no process of the plugin is still running"
  (fn [ctx] (k/check! (not (running? (str "sleep " (::marker ctx)))) "the plugin's process is still running") ctx))

(defstep #"a plugin whose command is \[\"no-such-plugin\"\]"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command ["no-such-plugin"]}]))))

(defstep #"it exits 2 and names the plugin and its command"
  (fn [ctx] (k/exits! ctx 2) (k/err-line-has! ctx "lint-a" "no-such-plugin") ctx))

;; ------------------------------------------------------------------ answerers

(defstep #"a project with two plugins answering :Gate, \"first\" exiting 0 and \"second\" exiting 1 with the finding \"x:1: oracle-differs: …\""
  (fn [ctx]
    (let [ctx (k/cut-project! ctx)]
      (plugins! ctx [{:name "first" :answers :Gate :command (plugin-script! ctx "first" "echo first >> gates.txt\nexit 0")}
                     {:name "second" :answers :Gate :command (plugin-script! ctx "second" "echo second >> gates.txt\necho 'x:1: oracle-differs: …' >&2\nexit 1")}]))))

(defstep #"first ran, then second; it exits 1, relays the finding unchanged, and builds no room"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/check! (= "first\nsecond\n" (k/slurp-rel ctx "gates.txt")) "the gates ran as " (pr-str (k/slurp-rel ctx "gates.txt")))
    (k/check! (some #{"x:1: oracle-differs: …"} (k/err-lines ctx)) "the finding is not relayed: " (k/shows ctx))
    (k/check! (not (fs/exists? (k/path ctx "room"))) "a room was built")
    ctx))

(defstep #"a plugin answering :Gate whose command writes what it is given to a file in the project"
  (fn [ctx]
    (let [ctx (k/verify-project! ctx)]
      (plugins! ctx [{:name "gate" :answers :Gate :command (plugin-script! ctx "gate" (recorder "gate"))}]))))

(defstep #"its standard input was \{:request :Gate :task :verify :cut \"spec-3\" :cycle \"03\"\}"
  (fn [ctx]
    (k/check! (= {:request :Gate :task :verify :cut "spec-3" :cycle "03"} (received ctx "gate")) "the Gate was given " (pr-str (received ctx "gate")) "; " (k/shows ctx))
    ctx))

;; ------------------------------------------------------------------ templates

(defn jar! [ctx]
  (let [b (k/scratch! "package-")
        res (str b "/res/rdm-spec/plugins")]
    (fs/create-dirs (fs/parent res))
    (fs/copy-tree (str k/room "/plugins") res)
    (let [r @(p/process ["bb" "--classpath" (str k/src ":" b "/res") "uberjar" (str b "/rdm-spec.jar") "-m" "rdm-spec.kernel.command"]
                        {:dir b :out :string :err :string :env (k/base-env ctx)})]
      (when-not (zero? (:exit r)) (throw (ex-info (str "uberjar: " (:err r)) {}))))
    (str b "/rdm-spec.jar")))

(defstep #"rdm-spec installed (from its sources|as a package \(a jar\))"
  (fn [ctx how]
    (assoc ctx ::how how)))

(defstep #"\"rdm-spec plugin add no-such\" is run in a project set up by rdm-spec"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (if (= "from its sources" (::how ctx))
        (k/run-rdm-process ctx "plugin add no-such")
        (let [jar (jar! ctx)
              r @(p/process ["bb" jar "plugin" "add" "no-such"] {:dir (:dir ctx) :out :string :err :string :env (k/base-env ctx)})]
          (assoc ctx :exit (:exit r) :out (:out r) :err (:err r)))))))

(defstep #"it lists the templates ddd-domain and affordances, each with its version"
  (fn [ctx]
    (k/err-line-has! ctx "ddd-domain" "1")
    (k/err-line-has! ctx "affordances" "1")
    ctx))

(defstep #"a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain"
  (fn [ctx] (k/project! ctx)))

(defstep #"the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [from (str k/room "/plugins/ddd-domain")]
      (doseq [f (fs/glob from "**") :when (fs/regular-file? f)]
        (let [rel (str (fs/relativize from f))]
          (k/check! (= (slurp (str f)) (k/slurp-rel ctx (str "dhf/harness/plugins/ddd-domain/" rel))) rel " is not copied unchanged"))))
    ctx))

(defstep #"dhf/harness/plugins/ddd-domain/origin.edn holds exactly \{:template \"ddd-domain\" :version V :copied D\}, V the template's version and D today's date"
  (fn [ctx]
    (let [o (edn/read-string (k/slurp-rel ctx "dhf/harness/plugins/ddd-domain/origin.edn"))]
      (k/check! (= {:template "ddd-domain" :version 1 :copied (str (java.time.LocalDate/now))} o) "origin.edn holds " (pr-str o))
      ctx)))

(defstep #"it prints the :plugins entry to add to the configuration, and changes the configuration not at all"
  (fn [ctx]
    (k/check! (str/includes? (:out ctx) (pr-str {:name "ddd-domain" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/ddd-domain/lint.clj"]}))
              "the entry is not printed: " (k/shows ctx))
    (k/check! (nil? (:plugins (k/read-config ctx))) "the configuration changed")
    ctx))

(defstep #"a project set up by rdm-spec (that already has dhf/harness/plugins/ddd-domain|and nothing else)"
  (fn [ctx given]
    (let [ctx (k/project! ctx)]
      (when (str/starts-with? given "that")
        (k/write! ctx "dhf/harness/plugins/ddd-domain/mine.txt" "my own\n"))
      ctx)))

(defstep #"it exits (\d) and (names the directory and copies nothing|names no-such and lists the templates rdm-spec carries)"
  (fn [ctx exit says]
    (k/exits! ctx (parse-long exit))
    (if (str/starts-with? says "names the directory")
      (do (k/err-has! ctx "dhf/harness/plugins/ddd-domain")
          (k/check! (= ["mine.txt"] (map #(str (fs/file-name %)) (fs/list-dir (k/path ctx "dhf/harness/plugins/ddd-domain")))) "something was copied"))
      (k/err-has! ctx "no-such" "ddd-domain" "affordances"))
    ctx))

;; ------------------------------------------------------------------ :env

(defstep #"a plugin subscribing to :SpecParsed with :env \{\"ORACLE_DIR\" \"../e2e\" \"SEND_LIMIT\" \"5\"\} whose command writes its environment to a file in the project"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)]
      (-> (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :env {"ORACLE_DIR" "../e2e" "SEND_LIMIT" "5"}
                          :command (plugin-script! ctx "lint-a" (recorder "lint-a"))}])
          (assoc-in [:env "CALLER_MARK"] "kept")))))

(defstep #"the plugin's environment held ORACLE_DIR \"../e2e\", SEND_LIMIT \"5\" and RDM_SPEC, and the rest of the caller's environment"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [e (env-of ctx "lint-a")]
      (k/check! (= ["../e2e" "5" "kept"] [(e "ORACLE_DIR") (e "SEND_LIMIT") (e "CALLER_MARK")]) "the plugin's environment was " (pr-str (select-keys e ["ORACLE_DIR" "SEND_LIMIT" "CALLER_MARK"])))
      (k/check! (e "RDM_SPEC") "no RDM_SPEC")
      ctx)))

(defstep #"an :env that is not a map of strings to strings is refused before anything runs, naming the plugin"
  (fn [ctx]
    (k/delete! ctx "lint-a.runs")
    (k/config! ctx assoc-in [:plugins 0 :env] {"SEND_LIMIT" 5})
    (let [r (k/run-rdm ctx "check")]
      (k/exits! r 2)
      (k/err-line-has! r "lint-a" ":env")
      (k/check! (zero? (runs-of r "lint-a")) "the plugin ran")
      ctx)))

;; ------------------------------------------------------------------ :CycleVerified

(defstep #"a plugin subscribing to :CycleVerified whose command writes what it is given to a file in the project"
  (fn [ctx]
    (let [ctx (k/verify-project! ctx)]
      (plugins! ctx [{:name "lint-a" :subscribes [:CycleVerified] :command (plugin-script! ctx "lint-a" (recorder "lint-a"))}]))))

(defstep #"after every check passed, the plugin ran once, in the project's root, with \{:event :CycleVerified :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\" :checks \[\.\.\.\]\} on its input, the checks as ratified.edn holds them"
  (fn [ctx]
    (let [m (received ctx "lint-a")
          ratified (edn/read-string (k/slurp-rel ctx "dhf/cycles/03/ratified.edn"))]
      (k/check! (= 1 (runs-of ctx "lint-a")) "the plugin ran " (runs-of ctx "lint-a") " times")
      (k/check! (= (str (fs/canonicalize (:dir ctx))) (str/trim (k/slurp-rel ctx "lint-a.pwd"))) "it ran in " (k/slurp-rel ctx "lint-a.pwd"))
      (k/check! (= {:event :CycleVerified :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build" :checks (:checks ratified)} m)
                "the plugin was given " (pr-str m))
      ctx)))

(defstep #"its findings are verify's, and a plugin that cannot run makes verify exit 2 after ratified.edn is written"
  (fn [ctx]
    (plugin-script! ctx "lint-a" "echo 'x.clj:1: deploy-failed: the deploy failed' >&2\nexit 1")
    (k/delete! ctx "dhf/cycles/03/ratified.edn")
    (let [r (k/run-rdm ctx "verify ROOM 03")]
      (k/exits! r 1)
      (k/check! (some #{"x.clj:1: deploy-failed: the deploy failed"} (k/err-lines r)) "the finding is not verify's: " (k/shows r)))
    (plugin-script! ctx "lint-a" "echo 'cannot deploy' >&2\nexit 2")
    (k/delete! ctx "dhf/cycles/03/ratified.edn")
    (let [r (k/run-rdm ctx "verify ROOM 03")]
      (k/exits! r 2)
      (k/check! (fs/exists? (k/path ctx "dhf/cycles/03/ratified.edn")) "no ratified.edn was written"))
    ctx))

(defstep #"a plugin subscribing to :CycleVerified"
  (fn [ctx]
    (let [ctx (k/verify-project! ctx {:cfg-fn #(assoc % :verify [{:kind :tests :command ["sh" "-c" "exit 1"]}])})]
      (plugins! ctx [{:name "lint-a" :subscribes [:CycleVerified] :command (plugin-script! ctx "lint-a" (recorder "lint-a"))}]))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and its tests check fails"
  (fn [ctx] (let [ctx (k/run-rdm ctx "verify ROOM 03")] (k/exits! ctx 1))))
