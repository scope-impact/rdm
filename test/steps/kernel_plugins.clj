(ns steps.kernel-plugins
  "Step definitions of dhf/spec/kernel/plugins.feature: each plugin is a
  small command the scenario writes into its project."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn- file [ctx p] (fs/file (k/guard! (:dir ctx)) p))
(defn- text [ctx p] (let [f (file ctx p)] (when (fs/exists? f) (slurp f))))

(defn plugins!
  "A project whose :plugins are entries, with the files written."
  [ctx entries & [files]]
  (let [ctx (k/project! ctx {:config {:plugins entries}})]
    (k/write-files! (:dir ctx) (or files {}))
    ctx))

(def recorder
  "Records its input, where it ran and its environment."
  "cat > got.edn; pwd > cwd.txt; env > env.txt; echo ran >> ran.log")

(defn sh-plugin [name kind & {:keys [script seconds env]}]
  (cond-> {:name name :command ["sh" "-c" (or script recorder)]}
    (vector? kind) (assoc :subscribes kind)
    (keyword? kind) (assoc :answers kind)
    seconds (assoc :seconds seconds)
    env (assoc :env env)))

;; ---------------------------------------------------------------------------
;; Declared in the configuration

(defstep #"a configuration whose :plugins is \[\{:name \"lint-a\" :subscribes \[:SpecParsed\] :command \[\"bb\" \"dhf/harness/plugins/lint-a/run.clj\"\] :seconds 20\} \{:name \"oracle\" :answers :Gate :command \[\"bb\" \"dhf/harness/oracle/oracle.clj\"\]\}\]"
  (fn [ctx]
    (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/lint-a/run.clj"] :seconds 20}
                   {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}]
              {"dhf/harness/plugins/lint-a/run.clj" "(spit \"got.edn\" (slurp *in*))\n"})))

(defstep #"each entry is read as exactly :name, :command, one of :subscribes \(a vector of event names\) or :answers \(:Gate or :Check\), an optional :seconds, its time limit, and an optional :env"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= :SpecParsed (:event (edn/read-string (text ctx "got.edn")))))
    ;; a key it does not know is refused
    (k/update-edn! ctx "dhf/harness/config.edn" update-in [:plugins 0] assoc :owner "Sami")
    (let [r (k/rdm ctx ["check"])]
      (k/check-exit r 2)
      (k/err-line-with r "lint-a" ":owner"))
    (k/update-edn! ctx "dhf/harness/config.edn" update-in [:plugins 0] dissoc :owner)
    ctx))

(defstep #"a plugin with no :seconds has 60 seconds"
  (fn [ctx]
    (let [declared (requiring-resolve 'rdm-spec.kernel.plugins/declared)
          cfg (edn/read-string (text ctx "dhf/harness/config.edn"))]
      (assert (= [20 60] (map :seconds (declared cfg)))))
    ctx))

(def bad-entries
  {"a plugin with neither :subscribes nor :answers" [[{:name "judge" :command ["sh" "-c" "true"]}] "subscribes to events or answers a request"]
   "a plugin with both :subscribes and :answers" [[{:name "judge" :subscribes [:SpecParsed] :answers :Gate :command ["sh" "-c" "true"]}] "not both"]
   "a plugin subscribing to :NoSuchEvent" [[{:name "judge" :subscribes [:NoSuchEvent] :command ["sh" "-c" "true"]}] "NoSuchEvent" "SpecParsed" "KnowledgeIndexed" "CycleVerified"]
   "a plugin answering :Review" [[{:name "judge" :answers :Review :command ["sh" "-c" "true"]}] ":Gate and :Check"]
   "two plugins named \"lint-a\"" [[{:name "lint-a" :subscribes [:SpecParsed] :command ["sh" "-c" "true"]}
                                   {:name "lint-a" :answers :Gate :command ["sh" "-c" "true"]}] "declared once"]
   "a plugin whose command is under the implementation's paths" [[{:name "judge" :answers :Gate :command ["bb" "src/judge.clj"]}] "never writes"]})

(defstep #"a configuration whose :plugins holds (a plugin .+|two plugins named \"lint-a\")"
  (fn [ctx entry]
    (let [[entries & names] (or (bad-entries entry) (fail! (str "no such entry: " entry)))]
      (apply k/names+ (plugins! ctx entries) (if (str/includes? entry "lint-a") "lint-a" "judge") names))))

(defstep #"it exits 2, names the plugin, and says .+"
  (fn [ctx]
    (k/check-exit ctx 2)
    (apply k/err-line-with ctx "cannot-run:" (:names ctx))
    (assert (not (fs/exists? (file ctx "ran.log"))))
    ctx))

;; ---------------------------------------------------------------------------
;; Subscribers

(defstep #"a plugin subscribing to :SpecParsed whose command writes what it is given to a file in the project"
  (fn [ctx] (plugins! ctx [(sh-plugin "lint-a" [:SpecParsed])])))

(defn rdm-spec-line? [s] (and s (str/includes? s "-m rdm-spec.kernel.command")))

(defn env-of [ctx]
  (into {} (for [l (str/split-lines (or (text ctx "env.txt") "")) :let [[k v] (str/split l #"=" 2)] :when v] [k v])))

(defstep #"the plugin ran once, in the project's root, with the environment variable RDM_SPEC set as the oracle's is"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "ran\n" (text ctx "ran.log")) (text ctx "ran.log"))
    (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (str/trim (text ctx "cwd.txt"))))))
    (assert (rdm-spec-line? (get (env-of ctx) "RDM_SPEC")) (pr-str (get (env-of ctx) "RDM_SPEC")))
    ctx))

(defstep #"its standard input was the SpecParsed event as EDN: \{:event :SpecParsed\} merged with every key events.edn says the event carries"
  (fn [ctx]
    (let [got (edn/read-string (text ctx "got.edn"))]
      (assert (= #{:event :revision :scenarios :needs :refusals} (set (keys got))) (pr-str (keys got)))
      (assert (= :SpecParsed (:event got)))
      (assert (= ["spec-0001"] (map :id (:scenarios got))))
      ctx)))

(defn appender [name & [extra]]
  (str "echo " name " $(date +%s%N) >> order.log" (when extra (str "; " extra))))

(defstep #"two plugins subscribing to :SpecParsed, \"lint-a\" then \"lint-b\", each writing its name and the time to a shared file"
  (fn [ctx]
    (let [ctx (plugins! ctx [(sh-plugin "lint-a" [:SpecParsed] :script (appender "lint-a" "echo 'x.feature:1: plugin-a: from lint-a' >&2; exit 1"))
                             (sh-plugin "lint-b" [:SpecParsed] :script (appender "lint-b"))])]
      ;; and a finding of the spec check's own
      (k/write-files! (:dir ctx) {k/core-feature (k/feature-text {:name "Core" :tags ["UN-001"]
                                                                  :scenarios [(assoc (k/sc 1 "No level") :tags ["spec-0001"])]})})
      ctx)))

(defstep #"lint-a ran before lint-b, and both after the spec check's own stages"
  (fn [ctx]
    (k/check-exit ctx 1)
    (let [order (map #(first (str/split % #" ")) (str/split-lines (text ctx "order.log")))
          ls (k/err-lines ctx)
          idx (fn [s] (first (keep-indexed (fn [i l] (when (str/includes? l s) i)) ls)))]
      (assert (= ["lint-a" "lint-b"] order) (pr-str order))
      (assert (< (idx "spec-0001") (idx "plugin-a")) (:err ctx))
      ctx)))

(defstep #"a plugin subscribing only to :VerificationDerived"
  (fn [ctx] (plugins! ctx [(sh-plugin "lint-a" [:VerificationDerived])])))

(defstep #"the plugin did not run"
  (fn [ctx]
    (when-not (#{0 1} (:exit ctx)) (fail! (str "the task did not run: " (:err ctx))))
    (assert (not (fs/exists? (file ctx "ran.log"))))
    ctx))

;; ---------------------------------------------------------------------------
;; One interface

(def answers
  {"writes \"dhf/spec/a/b.feature:4: action-unoffered: no form offers it\" on its error stream and exits 1"
   ["echo 'dhf/spec/a/b.feature:4: action-unoffered: no form offers it' >&2; exit 1" "dhf/spec/a/b.feature:4: action-unoffered: no form offers it"]
   "writes \"dhf/spec/a/b.feature:4: warning: label-long: …\" on its error stream and exits 0"
   ["echo 'dhf/spec/a/b.feature:4: warning: label-long: …' >&2; exit 0" "dhf/spec/a/b.feature:4: warning: label-long: …"]
   "writes nothing and exits 0" ["exit 0" nil]
   "exits 2, writing \"cannot read affordances.yaml\" on its error stream"
   ["echo 'cannot read affordances.yaml' >&2; exit 2" "cannot read affordances.yaml"]})

(defstep #"a plugin subscribing to :SpecParsed that (.+)"
  (fn [ctx does]
    (let [[script line] (or (answers does) (fail! (str "no such plugin: " does)))]
      (-> (plugins! ctx [(sh-plugin "lint-a" [:SpecParsed] :script script)]) (assoc ::line line)))))

(defstep #"\"rdm-spec check\" is run on a spec that passes every check"
  (fn [ctx] (k/rdm ctx ["check"])))

(defstep #"it (exits \d), and the error stream (.+)"
  (fn [ctx exits holds]
    (k/check-exit ctx (parse-long (subs exits 6)))
    (let [line (::line ctx) ls (k/err-lines ctx)]
      (cond
        (str/starts-with? holds "cannot-run") (k/err-line-with ctx "cannot-run:" "lint-a" line)
        line (assert (some #{line} ls) (:err ctx))
        :else (assert (not-any? #(str/includes? % "b.feature") ls) (:err ctx))))
    ctx))

(defstep #"a plugin subscribing to :SpecParsed with :seconds 2, which prints \"started\" and then sleeps for 10 seconds"
  (fn [ctx] (plugins! ctx [(sh-plugin "lint-a" [:SpecParsed] :script "echo started; sleep 10.37" :seconds 2)])))

(defn- running? [pattern]
  (let [r (k/sh "." {} "ps" "-eo" "args")]
    (some #(str/includes? % pattern) (str/split-lines (:out r)))))

(defstep #"it exits 2 within 5 seconds, says the plugin timed out after 2 seconds, and relays \"started\""
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "lint-a" "timed out after 2 seconds" "started")
    (assert (< (:seconds ctx) 5) (str "it took " (:seconds ctx)))
    ctx))

(defstep #"no process of the plugin is still running"
  (fn [ctx] (assert (not (running? "sleep 10.37"))) ctx))

(defstep #"a plugin whose command is \[\"no-such-plugin\"\]"
  (fn [ctx] (plugins! ctx [{:name "lint-a" :subscribes [:SpecParsed] :command ["no-such-plugin"]}])))

(defstep #"it exits 2 and names the plugin and its command"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "lint-a" "no-such-plugin")))

;; ---------------------------------------------------------------------------
;; Answerers

(defstep #"a project with two plugins answering :Gate, \"first\" exiting 0 and \"second\" exiting 1 with the finding \"x:1: oracle-differs: …\""
  (fn [ctx]
    (-> (plugins! ctx [(sh-plugin "first" :Gate :script "echo first >> order.log")
                       (sh-plugin "second" :Gate :script "echo second >> order.log; echo 'x:1: oracle-differs: …' >&2; exit 1")])
        (k/tag-cut! "spec-3"))))

(defstep #"first ran, then second; it exits 1, relays the finding unchanged, and builds no room"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= ["first" "second"] (str/split-lines (text ctx "order.log"))))
    (assert (some #{"x:1: oracle-differs: …"} (k/err-lines ctx)) (:err ctx))
    (assert (not (fs/exists? (fs/path (:dir ctx) "room"))))
    (assert (not (fs/exists? (fs/path (get-in ctx [:ph "ROOM"] (fs/path (:base ctx) "none")) ".git"))))
    ctx))

(defstep #"a plugin answering :Gate whose command writes what it is given to a file in the project"
  (fn [ctx] (k/verifiable! ctx {:config {:plugins [(sh-plugin "gate" :Gate)]}})))

(defstep #"its standard input was \{:request :Gate :task :verify :cut \"spec-3\" :cycle \"03\"\}"
  (fn [ctx]
    (assert (= {:request :Gate :task :verify :cut "spec-3" :cycle "03"} (edn/read-string (text ctx "got.edn"))) (text ctx "got.edn"))
    ctx))

;; ---------------------------------------------------------------------------
;; Templates

(defn- jar!
  "rdm-spec as a package: a jar of its sources and its templates, as
  resources under rdm-spec/plugins/."
  [ctx]
  (let [res (k/path-in ctx "resources")
        jar (k/path-in ctx "rdm-spec.jar")]
    (fs/copy-tree (fs/path k/room-root "plugins") (fs/path res "rdm-spec/plugins"))
    (let [b (k/sh k/room-root {} "bb" (str "-Djava.io.tmpdir=" k/tmp-root) "--classpath" (str "src:" res)
                  "uberjar" jar "-m" "rdm-spec.kernel.command")]
      (when-not (= 0 (:exit b)) (fail! (str "the jar was not built: " (:err b)))))
    jar))

(defstep #"rdm-spec installed (from its sources|as a package \(a jar\))"
  (fn [ctx how]
    (let [ctx (k/base! ctx)]
      (if (= how "from its sources")
        ctx
        (assoc ctx ::jar (jar! ctx))))))

(defstep #"\"rdm-spec plugin add no-such\" is run in a project set up by rdm-spec"
  (fn [ctx]
    (let [ctx (k/init-project! ctx)]
      (if-let [jar (::jar ctx)]
        (merge ctx (k/sh (:dir ctx) {::k/base (:base ctx)} "bb" "-cp" jar "-m" "rdm-spec.kernel.command" "plugin" "add" "no-such"))
        (k/rdm ctx ["plugin" "add" "no-such"])))))

(defstep #"it lists the templates ddd-domain and affordances, each with its version"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "no-such" "ddd-domain" "affordances" "version 1")
    ctx))

(defstep #"a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain"
  (fn [ctx]
    (let [ctx (k/init-project! ctx)]
      (assert (not (fs/exists? (file ctx "dhf/harness/plugins/ddd-domain"))))
      (assoc ctx ::config (text ctx "dhf/harness/config.edn")))))

(defn- tree-of [d]
  (into {} (for [f (fs/glob d "**") :when (fs/regular-file? f)] [(str (fs/relativize d f)) (slurp (fs/file f))])))

(defstep #"the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= (tree-of (fs/path k/room-root "plugins/ddd-domain"))
               (dissoc (tree-of (file ctx "dhf/harness/plugins/ddd-domain")) "origin.edn")))
    ctx))

(defstep #"dhf/harness/plugins/ddd-domain/origin.edn holds exactly \{:template \"ddd-domain\" :version V :copied D\}, V the template's version and D today's date"
  (fn [ctx]
    (assert (= {:template "ddd-domain" :version 1 :copied (str (java.time.LocalDate/now))}
               (edn/read-string (text ctx "dhf/harness/plugins/ddd-domain/origin.edn"))))
    ctx))

(defstep #"it prints the :plugins entry to add to the configuration, and changes the configuration not at all"
  (fn [ctx]
    (assert (str/includes? (:out ctx) "dhf/harness/plugins/ddd-domain/lint.clj") (:out ctx))
    (assert (= (::config ctx) (text ctx "dhf/harness/config.edn")))
    ctx))

(defstep #"a project set up by rdm-spec (that already has dhf/harness/plugins/ddd-domain|and nothing else)"
  (fn [ctx given]
    (let [ctx (k/init-project! ctx)]
      (when (str/starts-with? given "that")
        (k/write-files! (:dir ctx) {"dhf/harness/plugins/ddd-domain/mine.clj" "mine\n"}))
      ctx)))

(defstep #"it exits (1|2) and (names the directory and copies nothing|names no-such and lists the templates rdm-spec carries)"
  (fn [ctx exit says]
    (k/check-exit ctx (parse-long exit))
    (if (= exit "1")
      (do (k/err-line-with ctx "dhf/harness/plugins/ddd-domain")
          (assert (= ["mine.clj"] (keys (tree-of (file ctx "dhf/harness/plugins/ddd-domain"))))))
      (k/err-line-with ctx "no-such" "ddd-domain" "affordances"))
    ctx))

;; ---------------------------------------------------------------------------
;; :env

(defstep #"a plugin subscribing to :SpecParsed with :env \{\"ORACLE_DIR\" \"../e2e\" \"SEND_LIMIT\" \"5\"\} whose command writes its environment to a file in the project"
  (fn [ctx] (plugins! ctx [(sh-plugin "lint-a" [:SpecParsed] :env {"ORACLE_DIR" "../e2e" "SEND_LIMIT" "5"})])))

(defstep #"the plugin's environment held ORACLE_DIR \"../e2e\", SEND_LIMIT \"5\" and RDM_SPEC, and the rest of the caller's environment"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [e (env-of ctx)]
      (assert (= ["../e2e" "5"] [(get e "ORACLE_DIR") (get e "SEND_LIMIT")]) (pr-str e))
      (assert (rdm-spec-line? (get e "RDM_SPEC")))
      (assert (= (System/getenv "PATH") (get e "PATH")))
      (assert (= (System/getenv "HOME") (get e "HOME"))))
    ctx))

(defstep #"an :env that is not a map of strings to strings is refused before anything runs, naming the plugin"
  (fn [ctx]
    (fs/delete-if-exists (file ctx "ran.log"))
    (k/update-edn! ctx "dhf/harness/config.edn" assoc-in [:plugins 0 :env] {"SEND_LIMIT" 5})
    (let [r (k/rdm ctx ["check"])]
      (k/check-exit r 2)
      (k/err-line-with r "cannot-run:" "lint-a" ":env")
      (assert (not (fs/exists? (file ctx "ran.log")))))
    ctx))

;; ---------------------------------------------------------------------------
;; :CycleVerified

(defstep #"a plugin subscribing to :CycleVerified whose command writes what it is given to a file in the project"
  (fn [ctx] (k/verifiable! ctx {:config {:plugins [(sh-plugin "deploy" [:CycleVerified])]}})))

(defstep #"a plugin subscribing to :CycleVerified"
  (fn [ctx] (k/verifiable! ctx {:config {:plugins [(sh-plugin "deploy" [:CycleVerified])]}})))

(defstep #"\"rdm-spec verify ROOM 03\" is run and its tests check fails"
  (fn [ctx]
    (let [ctx (-> ctx
                  (k/update-edn! "dhf/harness/config.edn" assoc :verify [{:kind :tests :command ["sh" "-c" "exit 1"]}])
                  (k/tag-cut! "spec-3"))]
      (-> (k/rdm-line ctx "rdm-spec verify ROOM 03") (k/check-exit 1)))))

(defstep #"after every check passed, the plugin ran once, in the project's root, with \{:event :CycleVerified :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\" :checks \[\.\.\.\]\} on its input, the checks as ratified.edn holds them"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [got (edn/read-string (text ctx "got.edn"))
          ratified (edn/read-string (text ctx "dhf/cycles/03/ratified.edn"))]
      (assert (= "ran\n" (text ctx "ran.log")))
      (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (str/trim (text ctx "cwd.txt"))))))
      (assert (= {:event :CycleVerified :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build" :checks (:checks ratified)} got)
              (pr-str got))
      (assert (= [:passed] (map :outcome (:checks got)))))
    ctx))

(defstep #"its findings are verify's, and a plugin that cannot run makes verify exit 2 after ratified.edn is written"
  (fn [ctx]
    (let [ctx (-> ctx
                  (k/update-edn! "dhf/harness/config.edn" assoc :plugins
                                 [(sh-plugin "deploy" [:CycleVerified] :script "echo 'deploy.yml:2: deploy-failed: no target' >&2; exit 1")])
                  (k/tag-cut! "spec-3"))
          r (k/rdm-line ctx "rdm-spec verify ROOM 03")]
      (k/check-exit r 1)
      (k/err-line-with r "deploy.yml:2: deploy-failed: no target")
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :plugins [(sh-plugin "deploy" [:CycleVerified] :script "exit 2")])
      (k/tag-cut! ctx "spec-3")
      (fs/delete (file ctx "dhf/cycles/03/ratified.edn"))
      (let [r (k/rdm-line ctx "rdm-spec verify ROOM 03")]
        (k/check-exit r 2)
        (k/err-line-with r "cannot-run:" "deploy")
        (assert (= :passed (:outcome (edn/read-string (text ctx "dhf/cycles/03/ratified.edn")))))))
    ctx))
