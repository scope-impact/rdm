(ns steps.kernel-plugins
  "Steps of the kernel components Plugins and Event bus (08-plugins/plugins.feature, bus.feature)."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-steps :as k]
            [rdm-spec.kernel.runner :refer [defstep fail!]]))

(defn passing [] (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})}))

(def parsed-keys #{:event :revision :scenarios :needs :refusals})

;; ------------------------------------------------------------------ the bus

(defn event-lines [ctx] (filterv #(str/starts-with? % "event: ") (k/err-lines ctx)))

(def core-events ["event: KnowledgeIndexed published-by knowledge/sources"
                  "event: SpecParsed published-by spec/parser"
                  "event: SpecGated published-by spec/check"])

(defstep #"the error stream lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher, in the order they were published"
  (fn [ctx] (k/expect! (= core-events (event-lines ctx)) ctx (str "the events are " (event-lines ctx))) ctx))

(defstep #"a spec and its sources unchanged between two runs" (fn [_] (passing)))

(defstep #"\"rdm-spec check --events\" is run twice"
  (fn [ctx] (let [a (k/rdm! ctx "check --events") b (k/rdm! ctx "check --events")] (assoc b :first a))))

(defstep #"both runs list the same events in the same order"
  (fn [ctx]
    (k/expect! (seq (event-lines ctx)) ctx "no event")
    (k/expect! (= (event-lines (:first ctx)) (event-lines ctx)) ctx "the runs differ")
    ctx))

(defn catalogue-project [catalogue]
  (let [m (update k/shop-model :relationships conj {:from :shop/cart :to :shop/till :description "Publishes" :event :OrderPlaced})
        ctx (k/spec-project! {"shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001"}]})
                              "shop/till.feature" (k/feature-text {:name "Till" :scenarios [{:id "spec-0002"}]})}
                             :model (if (= catalogue :empty) k/shop-model m))]
    (k/write! (:dir ctx) "dhf/spec/events.edn"
              (k/pp (case catalogue
                      :empty {}
                      :own {:OrderPlaced {:description "An order was placed" :published-by :shop/cart :carries [:order] :shape {:order "{:id}"}}}
                      :other {:OrderPlaced {:description "An order was placed" :published-by :shop/cart :carries [:order] :shape {:order "{:id}"}}
                              :SpecParsed {:description "Ours" :published-by :shop/till :carries [:x] :shape {:x "y"}}})))
    ctx))

(defstep #"a project set up by rdm-spec whose configured event catalogue declares only the event OrderPlaced, published by its own component"
  (fn [_] (catalogue-project :own)))

(defstep #"it exits 0 and lists KnowledgeIndexed, SpecParsed and SpecGated, each with its publisher"
  (fn [ctx] (k/exits! ctx 0) (k/expect! (= core-events (event-lines ctx)) ctx (str "the events are " (event-lines ctx))) ctx))

(defstep #"a project set up by rdm-spec whose configured event catalogue is (empty|one that declares only its own event OrderPlaced|one that declares SpecParsed with another publisher)"
  (fn [_ c] (catalogue-project (case c "empty" :empty "one that declares only its own event OrderPlaced" :own :other))))

(defstep #"it lists the same events, with the same publishers, in the same order, as with an empty catalogue"
  (fn [ctx]
    (let [e (k/rdm! (catalogue-project :empty) "check --events")]
      (k/expect! (= (event-lines e) (event-lines ctx)) ctx (str "the events are " (event-lines ctx)))
      (k/expect! (= core-events (event-lines ctx)) ctx "not the core's events"))
    ctx))

;; ------------------------------------------------------------------ declaring plugins

(defstep #"a configuration whose :plugins is \[\{:name \"lint-a\" .+"
  (fn [_]
    (let [ctx (passing)
          a (k/plugin! ctx "lint-a" {:subscribes [:SpecParsed] :seconds 20})
          o {:name "oracle" :answers :Gate :command ["bb" "dhf/harness/oracle/oracle.clj"]}]
      (k/write! (:dir ctx) "dhf/harness/plugins/lint-a/run.clj" (k/recorder "lint-a"))
      (k/plugins! ctx [(assoc a :command ["bb" "dhf/harness/plugins/lint-a/run.clj"]) o]))))

(defstep #"each entry is read as exactly :name, :command, one of :subscribes \(a vector of event names\) or :answers \(:Gate or :Check\), an optional :seconds, its time limit, and an optional :env"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= ["lint-a"] (k/ran ctx)) ctx "lint-a did not run")
    (k/edit-config! (:dir ctx) #(update-in % [:plugins 0] assoc :colour "blue"))
    (let [r (k/rdm! ctx "check")] (k/exits! r 2) (k/err-has! r "lint-a" ":colour"))
    (k/edit-config! (:dir ctx) #(update-in % [:plugins 0] dissoc :colour))
    ctx))

(defstep #"a plugin with no :seconds has 60 seconds"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/harness/plugins/lint-a/run.clj" (k/recorder "lint-a" {:sleep 3}))
    (k/edit-config! (:dir ctx) #(update-in % [:plugins 0] dissoc :seconds))
    (let [r (k/rdm! ctx "check")] (k/exits! r 0))
    ctx))

(defstep #"a configuration whose :plugins holds (a plugin with neither :subscribes nor :answers|a plugin with both :subscribes and :answers|a plugin subscribing to :NoSuchEvent|a plugin answering :Review|two plugins named \"lint-a\"|a plugin whose command is under the implementation's paths)"
  (fn [_ entry]
    (let [ctx (passing) d (:dir ctx)
          base {:name "lint-a" :command ["bb" "dhf/harness/plugins/lint-a/run.clj"]}]
      (k/write! d "dhf/harness/plugins/lint-a/run.clj" (k/recorder "lint-a"))
      (k/edit-config! d #(assoc % :implementation ["src"]))
      (k/plugins! ctx (case entry
                        "a plugin with neither :subscribes nor :answers" [base]
                        "a plugin with both :subscribes and :answers" [(assoc base :subscribes [:SpecParsed] :answers :Gate)]
                        "a plugin subscribing to :NoSuchEvent" [(assoc base :subscribes [:NoSuchEvent])]
                        "a plugin answering :Review" [(assoc base :answers :Review)]
                        "two plugins named \"lint-a\"" [(assoc base :subscribes [:SpecParsed]) (assoc base :answers :Gate)]
                        "a plugin whose command is under the implementation's paths" [(assoc base :subscribes [:SpecParsed] :command ["bb" "src/lint.clj"])])))))

(defstep #"it exits 2, names the plugin, and says (.+)"
  (fn [ctx why]
    (k/exits! ctx 2)
    (k/err-has! ctx "lint-a")
    (k/err-has! ctx (str/replace why #", and lists the events it publishes$" ""))
    (when (str/ends-with? why "lists the events it publishes") (k/err-has! ctx "SpecParsed" "VerificationDerived"))
    (k/expect! (empty? (k/ran ctx)) ctx "a plugin ran")
    ctx))

;; ------------------------------------------------------------------ subscribers

(defstep #"a plugin subscribing to :SpecParsed whose command writes what it is given to a file in the project"
  (fn [_] (let [ctx (passing)] (k/plugins! ctx [(k/plugin! ctx "rec" {:subscribes [:SpecParsed]})]))))

(defstep #"the plugin ran once, in the project's root, with the environment variable RDM_SPEC set as the oracle's is"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= ["rec"] (k/ran ctx)) ctx "not once")
    (k/expect! (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (k/plugin-file ctx "rec" "pwd.txt")))) ctx "not in the project's root")
    (let [cmd (get (edn/read-string (k/plugin-file ctx "rec" "env.edn")) "RDM_SPEC")
          r (p/shell {:out :string :err :string :continue true :dir (:dir ctx)} (str cmd " help"))]
      (k/expect! (and cmd (zero? (:exit r)) (str/starts-with? (:out r) "{:help")) ctx (str "RDM_SPEC " cmd " does not start rdm-spec")))
    ctx))

(defstep #"its standard input was the SpecParsed event as EDN: \{:event :SpecParsed\} merged with every key events.edn says the event carries"
  (fn [ctx]
    (let [given (edn/read-string (k/plugin-file ctx "rec" "given.edn"))]
      (k/expect! (= parsed-keys (set (keys given))) ctx (str "the input's keys are " (keys given)))
      (k/expect! (= :SpecParsed (:event given)) ctx "not SpecParsed")
      (k/expect! (= ["spec-0001" "spec-0098"] (sort (map :id (:scenarios given)))) ctx "not the spec's scenarios"))
    ctx))

(defstep #"two plugins subscribing to :SpecParsed, \"lint-a\" then \"lint-b\", each writing its name and the time to a shared file"
  (fn [_] (let [ctx (passing)]
            (k/plugins! ctx [(k/plugin! ctx "lint-a" {:subscribes [:SpecParsed]}) (k/plugin! ctx "lint-b" {:subscribes [:SpecParsed]})]))))

(defstep #"lint-a ran before lint-b, and both after the spec check's own stages"
  (fn [ctx]
    (k/exits! ctx 0)
    (k/expect! (= ["lint-a" "lint-b"] (k/ran ctx)) ctx (str "they ran " (k/ran ctx)))
    ;; after the spec check's own stages: a plugin that fails changes no core finding, and the core's findings come first
    (k/write! (:dir ctx) "dhf/spec/shop/cart.feature" (k/feature-text {:name "Cart" :scenarios [{:id "spec-0001" :level nil}]}))
    (k/write! (:dir ctx) "dhf/harness/plugins/lint-a/run.clj" (k/recorder "lint-a" {:exit 1 :err "dhf/spec/x.feature:1: from-a: plugin"}))
    (let [r (k/rdm! ctx "check")
          ls (k/finding-lines r)]
      (k/expect! (str/includes? (first ls) "spec-0001") r "the core's finding is not first")
      (k/expect! (str/includes? (last ls) "from-a") r "the plugin's finding is not after the core's"))
    ctx))

(defstep #"a plugin subscribing only to :VerificationDerived"
  (fn [_] (let [ctx (passing)] (k/plugins! ctx [(k/plugin! ctx "rec" {:subscribes [:VerificationDerived]})]))))

(defstep #"the plugin did not run"
  (fn [ctx] (k/expect! (#{0 1} (:exit ctx)) ctx "the task did not run") (k/expect! (empty? (k/ran ctx)) ctx "the plugin ran") ctx))

(defstep #"a plugin subscribing to :SpecParsed that (.+)"
  (fn [_ does]
    (let [ctx (passing)
          answer (cond
                   (str/starts-with? does "writes \"dhf/spec/a/b.feature:4: action-unoffered") {:exit 1 :err "dhf/spec/a/b.feature:4: action-unoffered: no form offers it"}
                   (str/starts-with? does "writes \"dhf/spec/a/b.feature:4: warning: label-long") {:exit 0 :err "dhf/spec/a/b.feature:4: warning: label-long: …"}
                   (= does "writes nothing and exits 0") {:exit 0}
                   (str/starts-with? does "exits 2") {:exit 2 :err "cannot read affordances.yaml"}
                   (str/starts-with? does "with :seconds 2") nil)]
      (k/plugins! ctx [(k/plugin! ctx "lint-a" {:subscribes [:SpecParsed]} answer)]))))

(defstep #"\"rdm-spec check\" is run on a spec that passes every check"
  (fn [ctx] (k/rdm! ctx "check")))

(defstep #"it (exits 1|exits 0|exits 2), and the error stream (.+)"
  (fn [ctx exits holds]
    (k/exits! ctx (parse-long (subs exits 6)))
    (let [ls (remove #(str/includes? % "citation-unchecked") (k/err-lines ctx))]
      (cond
        (str/starts-with? holds "that line, unchanged, as a finding") (k/expect! (some #{"dhf/spec/a/b.feature:4: action-unoffered: no form offers it"} ls) ctx "not relayed unchanged")
        (str/starts-with? holds "that line, unchanged, as a warning") (k/expect! (some #{"dhf/spec/a/b.feature:4: warning: label-long: …"} ls) ctx "not relayed unchanged")
        (str/starts-with? holds "no line from the plugin") (k/expect! (empty? ls) ctx "a line from the plugin")
        (str/starts-with? holds "cannot-run") (k/line-has! ctx "cannot-run" "lint-a" "cannot read affordances.yaml")))
    ctx))

(defstep #"a plugin subscribing to :SpecParsed with :seconds 2, which prints \"started\" and then sleeps for 10 seconds"
  (fn [_] (let [ctx (passing)]
            (k/write! (:dir ctx) "dhf/harness/plugins/slow/run.sh" "echo started; sleep 10; echo slowtag\n")
            (k/plugins! ctx [{:name "slow" :subscribes [:SpecParsed] :seconds 2 :command ["sh" "dhf/harness/plugins/slow/run.sh"]}]))))

(defstep #"it exits 2 within 5 seconds, says the plugin timed out after 2 seconds, and relays \"started\""
  (fn [ctx]
    (k/exits! ctx 2)
    (k/expect! (< (:elapsed ctx) 5) ctx (str "it took " (:elapsed ctx) " seconds"))
    (k/line-has! ctx "slow" "timed out after 2 seconds" "started")
    ctx))

(defstep #"no process of the plugin is still running"
  (fn [ctx]
    (Thread/sleep 200)
    (k/expect! (str/blank? (:out (p/shell {:out :string :continue true} "pgrep" "-f" "dhf/harness/plugins/slow/run.sh"))) ctx "the plugin still runs")
    ctx))

(defstep #"a plugin whose command is \[\"no-such-plugin\"\]"
  (fn [_] (let [ctx (passing)] (k/plugins! ctx [{:name "ghost" :subscribes [:SpecParsed] :command ["no-such-plugin"]}]))))

(defstep #"it exits 2 and names the plugin and its command"
  (fn [ctx] (k/exits! ctx 2) (k/line-has! ctx "ghost" "no-such-plugin") ctx))

;; ------------------------------------------------------------------ answerers

(defn gated-project []
  (let [ctx (k/cycle-project!)]
    (k/edit-config! (:dir ctx) #(assoc % :implementation ["src" "test"]))
    ctx))

(defstep #"a project with two plugins answering :Gate, \"first\" exiting 0 and \"second\" exiting 1 with the finding \"x:1: oracle-differs: …\""
  (fn [_] (let [ctx (gated-project)]
            (k/plugins! ctx [(k/plugin! ctx "first" {:answers :Gate}) (k/plugin! ctx "second" {:answers :Gate} {:exit 1 :err "x:1: oracle-differs: …"})]))))

(defstep #"first ran, then second; it exits 1, relays the finding unchanged, and builds no room"
  (fn [ctx]
    (k/exits! ctx 1)
    (k/expect! (= ["first" "second"] (k/ran ctx)) ctx (str "they ran " (k/ran ctx)))
    (k/expect! (some #{"x:1: oracle-differs: …"} (k/err-lines ctx)) ctx "not relayed unchanged")
    (k/expect! (not (fs/exists? (k/path (:dir ctx) "room"))) ctx "a room was built")
    ctx))

(defstep #"a plugin answering :Gate whose command writes what it is given to a file in the project"
  (fn [_] (let [ctx (gated-project)] (k/plugins! ctx [(k/plugin! ctx "gate" {:answers :Gate})]))))

(defstep #"its standard input was \{:request :Gate :task :verify :cut \"spec-3\" :cycle \"03\"\}"
  (fn [ctx]
    (k/expect! (= {:request :Gate :task :verify :cut "spec-3" :cycle "03"} (edn/read-string (k/plugin-file ctx "gate" "given.edn"))) ctx "not that request")
    ctx))

;; ------------------------------------------------------------------ templates

(defstep #"\"rdm-spec plugin add (ddd-domain|affordances)\" is run, and its entry is added to :plugins as it says"
  (fn [ctx t]
    (let [r (k/rdm! ctx (str "plugin add " t))]
      (k/exits! r 0)
      (k/edit-config! (:dir ctx) #(assoc % :plugins [(get-in r [:edn :plugin :declare])]))
      (assoc r :template t :entry (get-in r [:edn :plugin :declare])))))

(defstep #"every file its command names is in dhf/harness/plugins/(ddd-domain|affordances)/"
  (fn [ctx t]
    (doseq [a (:command (:entry ctx)) :when (str/includes? a "/")]
      (k/expect! (str/starts-with? a (str "dhf/harness/plugins/" t "/")) ctx (str a " is not in the plugin's directory"))
      (k/expect! (fs/exists? (k/path (:dir ctx) a)) ctx (str a " is not there")))
    ctx))

(defstep #"\"rdm-spec check\" runs the plugin, and the plugin does not answer cannot run"
  (fn [ctx]
    (let [r (k/rdm! ctx "check")]
      (k/expect! (#{0 1} (:exit r)) r "the check cannot run")
      (k/expect! (not (str/includes? (:err r) "cannot-run")) r "the plugin cannot run"))
    ctx))

(defstep #"rdm-spec installed (from its sources|as a package \(a jar\))"
  (fn [_ how] (if (= how "from its sources") {} {:argv ["bb" (k/build-jar!)]})))

(defstep #"\"rdm-spec plugin add no-such\" is run in a project set up by rdm-spec"
  (fn [ctx] (k/rdm! (merge (k/new-project) (select-keys ctx [:argv])) "plugin add no-such")))

(defstep #"it lists the templates ddd-domain and affordances, each with its version"
  (fn [ctx] (k/exits! ctx 2) (k/line-has! ctx "ddd-domain (version 2)" "affordances (version 1)") ctx))

(defstep #"a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain"
  (fn [_] (let [ctx (k/new-project)] (assoc ctx :config-before (k/slurp-in (:dir ctx) "dhf/harness/config.edn")))))

(defstep #"the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [t (k/tree-snapshot (k/path @k/root "plugins/ddd-domain"))
          c (dissoc (k/tree-snapshot (k/path (:dir ctx) "dhf/harness/plugins/ddd-domain")) "origin.edn")]
      (k/expect! (= t c) ctx "the copy differs from the template"))
    ctx))

(defstep #"dhf/harness/plugins/ddd-domain/origin.edn holds exactly \{:template \"ddd-domain\" :version V :copied D\}, V the template's version and D today's date"
  (fn [ctx]
    (k/expect! (= {:template "ddd-domain" :version 2 :copied (str (java.time.LocalDate/now))}
                  (edn/read-string (k/slurp-in (:dir ctx) "dhf/harness/plugins/ddd-domain/origin.edn"))) ctx "not exactly that")
    ctx))

(defstep #"it prints the :plugins entry to add to the configuration, and changes the configuration not at all"
  (fn [ctx]
    (k/expect! (= {:name "ddd-domain" :subscribes [:SpecParsed] :command ["bb" "dhf/harness/plugins/ddd-domain/lint.clj"]}
                  (get-in ctx [:edn :plugin :declare])) ctx "not the entry")
    (k/expect! (= (:config-before ctx) (k/slurp-in (:dir ctx) "dhf/harness/config.edn")) ctx "the configuration changed")
    ctx))

(defstep #"a project set up by rdm-spec (that already has dhf/harness/plugins/ddd-domain|and nothing else)"
  (fn [_ given]
    (let [ctx (k/new-project)]
      (when (str/starts-with? given "that") (k/write! (:dir ctx) "dhf/harness/plugins/ddd-domain/mine.clj" ";; ours\n"))
      (assoc ctx :plugins-before (k/tree-snapshot (k/path (:dir ctx) "dhf/harness"))))))

(defstep #"it exits (1|2) and (names the directory and copies nothing|names no-such and lists the templates rdm-spec carries)"
  (fn [ctx exit says]
    (k/exits! ctx (parse-long exit))
    (if (str/starts-with? says "names the directory")
      (do (k/err-has! ctx "dhf/harness/plugins/ddd-domain")
          (k/expect! (= (:plugins-before ctx) (k/tree-snapshot (k/path (:dir ctx) "dhf/harness"))) ctx "something was copied"))
      (k/err-has! ctx "no-such" "ddd-domain" "affordances"))
    ctx))

;; ------------------------------------------------------------------ :env

(defstep #"a plugin subscribing to :SpecParsed with :env \{\"ORACLE_DIR\" \"../e2e\" \"SEND_LIMIT\" \"5\"\} whose command writes its environment to a file in the project"
  (fn [_] (let [ctx (passing)]
            (k/plugins! ctx [(k/plugin! ctx "rec" {:subscribes [:SpecParsed] :env {"ORACLE_DIR" "../e2e" "SEND_LIMIT" "5"}})]))))

(defstep #"the plugin's environment held ORACLE_DIR \"../e2e\", SEND_LIMIT \"5\" and RDM_SPEC, and the rest of the caller's environment"
  (fn [ctx]
    (k/exits! ctx 0)
    (let [env (edn/read-string (k/plugin-file ctx "rec" "env.edn"))]
      (k/expect! (= ["../e2e" "5"] [(env "ORACLE_DIR") (env "SEND_LIMIT")]) ctx "not the :env")
      (k/expect! (env "RDM_SPEC") ctx "no RDM_SPEC")
      (k/expect! (= (System/getenv "PATH") (env "PATH")) ctx "the caller's PATH is not kept"))
    ctx))

(defstep #"an :env that is not a map of strings to strings is refused before anything runs, naming the plugin"
  (fn [ctx]
    (k/edit-config! (:dir ctx) #(assoc-in % [:plugins 0 :env] {"SEND_LIMIT" 5}))
    (fs/delete-if-exists (k/path (:dir ctx) "plugins-ran.txt"))
    (let [r (k/rdm! ctx "check")]
      (k/exits! r 2) (k/err-has! r "rec" ":env")
      (k/expect! (empty? (k/ran ctx)) ctx "the plugin ran"))
    ctx))

;; ------------------------------------------------------------------ :CycleVerified

(defstep #"a plugin subscribing to :CycleVerified whose command writes what it is given to a file in the project"
  (fn [_] (let [ctx (gated-project)] (k/plugins! ctx [(k/plugin! ctx "rec" {:subscribes [:CycleVerified]})]))))

(defstep #"after every check passed, the plugin ran once, in the project's root, with \{:event :CycleVerified :cycle \"03\" :cut \"spec-3\" :checkout \"dhf/cycles/03/build\" :checks \[\.\.\.\]\} on its input, the checks as ratified.edn holds them"
  (fn [ctx]
    (k/expect! (= ["rec"] (k/ran ctx)) ctx "not once")
    (let [given (edn/read-string (k/plugin-file ctx "rec" "given.edn"))
          r (edn/read-string (k/slurp-in (:dir ctx) "dhf/cycles/03/ratified.edn"))]
      (k/expect! (= {:event :CycleVerified :cycle "03" :cut "spec-3" :checkout "dhf/cycles/03/build" :checks (:checks r)} given) ctx (str "the input is " given)))
    (k/expect! (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (k/plugin-file ctx "rec" "pwd.txt")))) ctx "not in the project's root")
    ctx))

(defstep #"its findings are verify's, and a plugin that cannot run makes verify exit 2 after ratified.edn is written"
  (fn [ctx]
    (k/write! (:dir ctx) "dhf/harness/plugins/rec/run.clj" (k/recorder "rec" {:exit 1 :err "dhf/x.md:1: deploy-failed: the build did not deploy"}))
    (k/recut! ctx)
    (let [r (k/rdm! ctx "verify ROOM 03")]
      (k/exits! r 1) (k/expect! (some #{"dhf/x.md:1: deploy-failed: the build did not deploy"} (k/err-lines r)) r "not verify's finding"))
    (k/write! (:dir ctx) "dhf/harness/plugins/rec/run.clj" (k/recorder "rec" {:exit 2 :err "no deploy target"}))
    (k/recut! ctx)
    (let [r (k/rdm! ctx "verify ROOM 03")]
      (k/exits! r 2)
      (k/expect! (= :passed (:outcome (edn/read-string (k/slurp-in (:dir ctx) "dhf/cycles/03/ratified.edn")))) r "ratified.edn was not written"))
    ctx))

(defstep #"a plugin subscribing to :CycleVerified"
  (fn [_] (let [ctx (gated-project)] (k/plugins! ctx [(k/plugin! ctx "rec" {:subscribes [:CycleVerified]})]))))

(defstep #"\"rdm-spec verify ROOM 03\" is run and its tests check fails"
  (fn [ctx]
    (k/edit-config! (:dir ctx) #(assoc % :verify [{:kind :tests :command ["sh" "-c" "exit 1"]}]))
    (k/recut! ctx)
    (k/rdm! ctx "verify ROOM 03")))
