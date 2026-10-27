(ns steps.kernel-plugins
  "Step definitions of dhf/spec/kernel/plugins.feature. A scenario's plugins
  are small commands it writes into its own project; the templates are the
  ones this rdm-spec carries (plugins/ beside src/), and a package of it is a
  jar the scenario builds in its own directory, as the way of working's
  packaging step does."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail! defer!]]
            [steps.kernel-command :as k]))

(defn- file [ctx p] (fs/file (k/guard! (:dir ctx)) p))

(defn- script [name] (str "dhf/harness/plugins/" name "/run.sh"))

(defn plugins!
  "A project whose :plugins are entries, with the scripts {name text} each
  plugin runs (sh dhf/harness/plugins/<name>/run.sh)."
  [ctx entries scripts]
  (let [ctx (k/project! ctx {:config {:plugins entries}})]
    (k/write-files! (:dir ctx) (into {} (for [[n text] scripts] [(script n) text])))
    ctx))

(defn- sub [name events & {:as more}]
  (merge {:name name :subscribes events :command ["sh" (script name)]} more))

(def recorder
  "Writes what it is given, where it ran and RDM_SPEC, and counts its runs."
  (str "cat > given.edn\n"
       "pwd > cwd.txt\n"
       "printf '%s' \"$RDM_SPEC\" > rdm-spec.txt\n"
       "echo ran >> runs.txt\n"))

(defn- ran-log [name] (str "echo " name " >> ran.log\n"))

(defn- read-file [ctx p] (let [f (file ctx p)] (when (fs/exists? f) (slurp f))))

(defn- plugin-runs [ctx] (:plugins (k/edn-out ctx)))

;; ---------------------------------------------------------------------------
;; A plugin is declared in the configuration, in the order it runs

(defstep #"a configuration whose :plugins is (\[.+\])"
  (fn [ctx entries]
    (let [es (edn/read-string entries)]
      (-> (k/project! ctx {:config {:plugins es}})
          (doto (-> :dir (k/write-files! {"dhf/harness/plugins/lint-a/run.clj" "(slurp *in*)\n(spit \"lint-a.log\" \"ran\\n\" :append true)\n"
                                          "dhf/harness/oracle/oracle.clj" "(System/exit 0)\n"})))
          (assoc ::entries es)))))

(defn- check-with [ctx plugins]
  (k/update-edn! ctx "dhf/harness/config.edn" assoc :plugins plugins)
  (k/rdm ctx ["check"]))

(defstep #"each entry is read as exactly :name, :command, one of :subscribes \(a vector of event names\) or :answers \(:Gate or :Check\), and an optional :seconds, its time limit"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [[lint oracle] (::entries ctx)
          runs (plugin-runs ctx)]
      (assert (= [{:plugin "lint-a" :event :SpecParsed :exit 0 :seconds 20}] (map #(dissoc % :notes) runs)) (pr-str runs))
      (assert (= "ran\n" (read-file ctx "lint-a.log")))
      ;; anything else in an entry is refused, before anything runs
      (doseq [bad [(assoc lint :colour "red")
                   (assoc lint :subscribes :SpecParsed)
                   (assoc lint :seconds "twenty")
                   (dissoc lint :command)
                   (assoc lint :command "bb run.clj")
                   (dissoc lint :name)]]
        (fs/delete-if-exists (file ctx "lint-a.log"))
        (let [r (check-with ctx [bad oracle])]
          (k/check-exit r 2)
          (assert (not (fs/exists? (file ctx "lint-a.log"))) (str "it ran with " (pr-str bad)))))
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :plugins (::entries ctx))
      ctx)))

(defstep #"a plugin with no :seconds has 60 seconds"
  (fn [ctx]
    (let [[lint oracle] (::entries ctx)
          r (check-with ctx [(dissoc lint :seconds) oracle])]
      (k/check-exit r 0)
      (assert (= [60] (map :seconds (plugin-runs r))) (:out r))
      ctx)))

(def bad-entries
  {"a plugin with neither :subscribes nor :answers" [{:name "lint-a" :command ["sh" (script "lint-a")]}]
   "a plugin with both :subscribes and :answers" [(sub "lint-a" [:SpecParsed] :answers :Gate)]
   "a plugin subscribing to :NoSuchEvent" [(sub "lint-a" [:NoSuchEvent])]
   "a plugin answering :Review" [{:name "lint-a" :answers :Review :command ["sh" (script "lint-a")]}]
   "two plugins named \"lint-a\"" [(sub "lint-a" [:SpecParsed]) (sub "lint-a" [:SpecGated])]
   "a plugin whose command is under the implementation's paths" [(assoc (sub "lint-a" [:SpecParsed]) :command ["sh" "src/lint-a.sh"])]})

(defstep #"a configuration whose :plugins holds (a plugin .+|two plugins named .+)"
  (fn [ctx entry]
    (let [es (or (bad-entries entry) (throw (ex-info (str "no fixture for " entry) {})))
          ctx (plugins! ctx es {"lint-a" (ran-log "lint-a")})]
      (k/write-files! (:dir ctx) {"src/lint-a.sh" (ran-log "lint-a")})
      ctx)))

(def event-names ["KnowledgeIndexed" "SpecParsed" "SpecGated" "VerificationDerived" "ClausesIndexed"])

(defstep #"it exits 2, names the plugin, and says (.+)"
  (fn [ctx why]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" "lint-a")
    (if-let [[_ head] (re-matches #"(.+), and lists the events it publishes" why)]
      (do (k/err-line-with ctx head) (apply k/err-line-with ctx event-names))
      (k/err-line-with ctx why))
    (assert (str/blank? (:out ctx)) (:out ctx))
    (assert (not (fs/exists? (file ctx "ran.log"))) "a plugin ran")
    ctx))

;; ---------------------------------------------------------------------------
;; A subscriber receives each event it subscribes to

(defstep #"a plugin subscribing to :SpecParsed whose command writes what it is given to a file in the project"
  (fn [ctx] (plugins! ctx [(sub "rec" [:SpecParsed])] {"rec" recorder})))

(defn- rdm-spec-line-ok [ctx line]
  (assert (str/includes? line (str (fs/path k/room-root "src"))) line)
  (assert (str/includes? line "-m rdm-spec.kernel.command") line)
  ;; it starts this same rdm-spec
  (let [r (k/sh (:dir ctx) {} "sh" "-c" (str line " help"))]
    (assert (= 0 (:exit r)) (:err r))
    (assert (str/starts-with? (:out r) "{:help") (:out r))))

(defstep #"the plugin ran once, in the project's root, with the environment variable RDM_SPEC set as the oracle's is"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "ran\n" (read-file ctx "runs.txt")) (pr-str (read-file ctx "runs.txt")))
    (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (str/trim (read-file ctx "cwd.txt"))))))
    (rdm-spec-line-ok ctx (read-file ctx "rdm-spec.txt"))
    ctx))

(defstep #"its standard input was the SpecParsed event as EDN: \{:event :SpecParsed\} merged with every key events.edn says the event carries"
  (fn [ctx]
    (let [given (edn/read-string (read-file ctx "given.edn"))
          carries (get-in (edn/read-string (slurp (fs/file k/room-root "dhf/spec/events.edn"))) [:SpecParsed :carries])]
      (assert (map? given) (pr-str given))
      (assert (= (set (cons :event carries)) (set (keys given))) (pr-str (keys given)))
      (assert (= :SpecParsed (:event given)))
      (assert (= :working-tree (:revision given)) (pr-str (:revision given)))
      (assert (= ["spec-0001"] (map :id (:scenarios given))) (pr-str (:scenarios given)))
      (assert (= ["UN-001"] (map :id (:needs given))) (pr-str (:needs given)))
      ctx)))

(defn- timed [name] (str "cat > /dev/null\necho \"" name " $(date +%s%N)\" >> order.log\n"))

(defstep #"two plugins subscribing to :SpecParsed, \"lint-a\" then \"lint-b\", each writing its name and the time to a shared file"
  (fn [ctx]
    (-> (plugins! ctx [(sub "lint-a" [:SpecParsed]) (sub "lint-b" [:SpecParsed])]
                  {"lint-a" (timed "lint-a") "lint-b" (timed "lint-b")})
        (assoc ::before (* 1000000 (System/currentTimeMillis))))))

(defstep #"lint-a ran before lint-b, and both after the spec check's own stages"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [ls (map #(str/split % #" ") (str/split-lines (read-file ctx "order.log")))]
      (assert (= ["lint-a" "lint-b"] (map first ls)) (pr-str ls))
      (assert (apply <= (map (comp parse-long second) ls)))
      ;; the result reports them after the core's own, in the order declared
      (assert (= ["lint-a" "lint-b"] (map :plugin (plugin-runs ctx))))
      ;; and the core's stages had all published by then
      (let [r (k/rdm ctx ["check" "--events"])
            events (filter #(str/starts-with? % "event: ") (k/err-lines r))]
        (k/check-exit r 0)
        (assert (some #(str/includes? % "SpecGated") events) (:err r))
        (assert (= 4 (count (str/split-lines (read-file ctx "order.log")))))))
    ctx))

(defstep #"a plugin subscribing only to :VerificationDerived"
  (fn [ctx] (plugins! ctx [(sub "late" [:VerificationDerived])] {"late" (ran-log "late")})))

(defstep #"the plugin did not run"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (fs/exists? (file ctx "ran.log"))) "it ran")
    (assert (empty? (plugin-runs ctx)) (:out ctx))
    ctx))

;; ---------------------------------------------------------------------------
;; A plugin answers through one interface

(defn- sq [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defstep #"a plugin subscribing to :SpecParsed that (.+)"
  (fn [ctx does]
    (let [text (or (when-let [[_ line n] (re-matches #"writes \"(.+)\" on its error stream and exits (\d)" does)]
                     (str "printf '%s\\n' " (sq line) " >&2\nexit " n "\n"))
                   (when-let [[_ n] (re-matches #"writes nothing and exits (\d)" does)]
                     (str "exit " n "\n"))
                   (when-let [[_ n line] (re-matches #"exits (\d), writing \"(.+)\" on its error stream" does)]
                     (str "printf '%s\\n' " (sq line) " >&2\nexit " n "\n"))
                   (throw (ex-info (str "no fixture for " does) {})))
          line (second (re-find #"\"(.+)\"" does))]
      (-> (plugins! ctx [(sub "lint-a" [:SpecParsed])] {"lint-a" (str "cat > /dev/null\n" text)})
          (assoc ::line line)))))

(defstep #"\"rdm-spec check\" is run on a spec that passes every check"
  (fn [ctx]
    (let [ctx (k/project! ctx)
          r (k/rdm ctx ["check"])]
      ;; the spec passes every check without the plugin
      (let [plugins (:plugins (edn/read-string (slurp (file ctx "dhf/harness/config.edn"))))
            _ (k/update-edn! ctx "dhf/harness/config.edn" dissoc :plugins)
            bare (k/rdm ctx ["check"])]
        (k/update-edn! ctx "dhf/harness/config.edn" assoc :plugins plugins)
        (k/check-exit bare 0))
      r)))

(defstep #"it exits (\d), and the error stream (that line, unchanged, as a finding|that line, unchanged, as a warning|no line from the plugin|cannot-run, naming the plugin and relaying its line)"
  (fn [ctx n holds]
    (k/check-exit ctx (parse-long n))
    (let [ls (k/err-lines ctx)]
      (case holds
        "that line, unchanged, as a finding"
        (do (assert (some #{(::line ctx)} ls) (:err ctx))
            (assert (some #{"action-unoffered"} (k/finding-rules ctx)) (:err ctx)))
        "that line, unchanged, as a warning"
        (assert (some #{(::line ctx)} ls) (:err ctx))
        "no line from the plugin"
        (assert (not-any? #(str/includes? % "lint-a") ls) (:err ctx))
        "cannot-run, naming the plugin and relaying its line"
        (do (k/err-line-with ctx "cannot-run:" "lint-a" (::line ctx))
            (assert (str/blank? (:out ctx)) (:out ctx)))))
    ctx))

(defn- running?
  "The process pid runs: it exists and is no zombie."
  [pid]
  (and (.isPresent (java.lang.ProcessHandle/of pid))
       (let [stat (try (slurp (str "/proc/" pid "/stat")) (catch Exception _ nil))]
         (and stat (not (re-find #"^\d+ \(.*\) Z" stat))))))

(defn- alive?
  "The process pid still runs a moment after it was stopped (a stop is not instant)."
  [pid]
  (loop [n 20]
    (cond (not (running? pid)) false
          (zero? n) true
          :else (do (Thread/sleep 100) (recur (dec n))))))

(defstep #"a plugin subscribing to :SpecParsed with :seconds 2, which prints \"started\" and then sleeps for 10 seconds"
  (fn [ctx]
    (let [ctx (plugins! ctx [(sub "slow" [:SpecParsed] :seconds 2)]
                        {"slow" (str "echo $$ > slow.pids\necho started\nsleep 10 &\necho $! >> slow.pids\nwait\n")})]
      ;; whatever the step decides, nothing it started outlives the scenario
      (defer! #(doseq [l (some-> (read-file ctx "slow.pids") str/split-lines)
                       :let [pid (parse-long (str/trim l))]
                       :when pid]
                 (when-let [h (.orElse (java.lang.ProcessHandle/of pid) nil)] (.destroyForcibly h))))
      ctx)))

(defstep #"it exits 2 within 5 seconds, says the plugin timed out after 2 seconds, and relays \"started\""
  (fn [ctx]
    (k/check-exit ctx 2)
    (assert (< (:seconds ctx) 5) (str "it took " (:seconds ctx) " seconds"))
    (k/err-line-with ctx "cannot-run:" "slow" "timed out after 2 seconds" "started")
    ctx))

(defstep #"no process of the plugin is still running"
  (fn [ctx]
    (let [pids (map (comp parse-long str/trim) (str/split-lines (read-file ctx "slow.pids")))]
      (assert (= 2 (count pids)) (pr-str pids))
      (doseq [pid pids] (assert (not (alive? pid)) (str "the process " pid " is still running")))
      ctx)))

(defstep #"a plugin whose command is \[\"no-such-plugin\"\]"
  (fn [ctx] (plugins! ctx [{:name "ghost" :subscribes [:SpecParsed] :command ["no-such-plugin"]}] {})))

(defstep #"it exits 2 and names the plugin and its command"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" "ghost" "no-such-plugin")))

;; ---------------------------------------------------------------------------
;; An answerer is asked at its request's place

(defstep #"a project with two plugins answering :Gate, \"first\" exiting 0 and \"second\" exiting 1 with the finding \"(.+)\""
  (fn [ctx finding]
    (let [gate (fn [n] {:name n :answers :Gate :command ["sh" (script n)]})
          ctx (plugins! ctx [(gate "first") (gate "second")]
                        {"first" (str "cat > /dev/null\necho first >> gate.log\n")
                         "second" (str "cat > /dev/null\necho second >> gate.log\nprintf '%s\\n' " (sq finding) " >&2\nexit 1\n")})]
      (-> (k/tag-cut! ctx "spec-3") (assoc ::line finding)))))

(defstep #"first ran, then second; it exits 1, relays the finding unchanged, and builds no room"
  (fn [ctx]
    (assert (= "first\nsecond\n" (read-file ctx "gate.log")) (pr-str (read-file ctx "gate.log")))
    (k/check-exit ctx 1)
    (assert (some #{(::line ctx)} (k/err-lines ctx)) (:err ctx))
    (assert (not (fs/exists? (file ctx "room"))) "a room was built")
    ctx))

(defstep #"a plugin answering :Gate whose command writes what it is given to a file in the project"
  (fn [ctx]
    (-> (plugins! ctx [{:name "rec" :answers :Gate :command ["sh" (script "rec")]}] {"rec" recorder})
        k/cycle-ready!)))

(defstep #"its standard input was \{:request :Gate :task :verify :cut \"spec-3\" :cycle \"03\"\}"
  (fn [ctx]
    (assert (= {:request :Gate :task :verify :cut "spec-3" :cycle "03"} (edn/read-string (read-file ctx "given.edn")))
            (read-file ctx "given.edn"))
    (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (str/trim (read-file ctx "cwd.txt"))))))
    ctx))

;; ---------------------------------------------------------------------------
;; A plugin is added from the templates rdm-spec carries, and then owned

(def templates-dir (str (fs/path k/room-root "plugins")))

(defn templates
  "{name version} of every template this rdm-spec carries."
  []
  (into (sorted-map) (for [t (fs/list-dir templates-dir)
                           :let [f (fs/file t "template.edn")]
                           :when (fs/exists? f)]
                       [(str (fs/file-name t)) (:version (edn/read-string (slurp f)))])))

(defn- tree [d]
  (into (sorted-map) (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (slurp (fs/file f))])))

(defn package!
  "A jar of this rdm-spec, packaged as the way of working does: plugins/
  copied to res/rdm-spec/plugins/, then bb --classpath src:res uberjar."
  [ctx]
  (let [ctx (k/base! ctx)
        d (k/path-in ctx "package")
        res (fs/path d "res" "rdm-spec" "plugins")
        jar (str (fs/path d "rdm-spec.jar"))]
    (fs/create-dirs (fs/parent res))
    (fs/copy-tree templates-dir res)
    (let [r (k/sh d {} "bb" (str "-Djava.io.tmpdir=" k/tmp-root) "--classpath" (str (fs/path k/room-root "src") ":" (fs/path d "res"))
                  "uberjar" jar "-m" "rdm-spec.kernel.command")]
      (when-not (= 0 (:exit r)) (throw (ex-info (str "the package was not built: " (:err r)) r))))
    (assoc ctx ::command ["bb" "-cp" jar "-m" "rdm-spec.kernel.command"] ::jar jar)))

(defstep #"rdm-spec installed (from its sources|as a package \(a jar\))"
  (fn [ctx how]
    (if (= how "from its sources")
      (assoc ctx ::command (k/rdm-command))
      (package! ctx))))

(defn- run-installed [ctx & args]
  (let [dir (k/guard! (:dir ctx))
        r (k/run-process dir (k/env-for dir (:env ctx)) (concat (::command ctx) args))]
    (assoc ctx :exit (:exit r) :out (:out r) :err (:err r))))

(defstep #"\"rdm-spec plugin add no-such\" is run in a project set up by rdm-spec"
  (fn [ctx] (run-installed (k/init-project! ctx) "plugin" "add" "no-such")))

(defn- lists-templates [ctx]
  (k/err-line-with ctx "cannot-run:" "no-such")
  (doseq [[n v] (templates)]
    (k/err-line-with ctx (str n " (version " v ")")))
  ctx)

(defstep #"it lists the templates ddd-domain and affordances, each with its version"
  (fn [ctx]
    (k/check-exit ctx 2)
    (assert (= #{"ddd-domain" "affordances"} (set (keys (templates)))))
    (lists-templates ctx)
    ;; and it copies the same template, however it was installed
    (let [r (run-installed ctx "plugin" "add" "ddd-domain")]
      (k/check-exit r 0)
      (assert (= (tree (fs/path templates-dir "ddd-domain"))
                 (dissoc (tree (file ctx "dhf/harness/plugins/ddd-domain")) "origin.edn"))))
    ctx))

(defstep #"a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain"
  (fn [ctx]
    (let [ctx (k/init-project! ctx)]
      (assert (not (fs/exists? (file ctx "dhf/harness/plugins/ddd-domain"))))
      (assoc ctx ::config (read-file ctx "dhf/harness/config.edn")))))

(defstep #"the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [copy (tree (file ctx "dhf/harness/plugins/ddd-domain"))]
      (assert (= (tree (fs/path templates-dir "ddd-domain")) (dissoc copy "origin.edn")) (pr-str (keys copy))))
    ctx))

(defn today [] (str (java.time.LocalDate/now)))

(defstep #"dhf/harness/plugins/ddd-domain/origin.edn holds exactly \{:template \"ddd-domain\" :version V :copied D\}, V the template's version and D today's date"
  (fn [ctx]
    (let [o (edn/read-string (read-file ctx "dhf/harness/plugins/ddd-domain/origin.edn"))]
      (assert (= {:template "ddd-domain" :version (get (templates) "ddd-domain") :copied (today)} o) (pr-str o))
      ctx)))

(defn- holds? [x v] (or (= x v) (and (coll? x) (some #(holds? % v) (if (map? x) (vals x) x)))))

(defstep #"it prints the :plugins entry to add to the configuration, and changes the configuration not at all"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [entry (:plugin (edn/read-string (slurp (fs/file templates-dir "ddd-domain" "template.edn"))))]
      (assert (holds? (k/edn-out ctx) entry) (:out ctx)))
    (assert (= (::config ctx) (read-file ctx "dhf/harness/config.edn")) "the configuration changed")
    ctx))

(defstep #"a project set up by rdm-spec (that already has dhf/harness/plugins/ddd-domain|and nothing else)"
  (fn [ctx given]
    (let [ctx (k/init-project! ctx)]
      (if (= given "and nothing else")
        ctx
        (do (k/write-files! (:dir ctx) {"dhf/harness/plugins/ddd-domain/mine.txt" "our own\n"})
            (-> ctx (assoc ::before (tree (file ctx "dhf/harness/plugins/ddd-domain")))
                (k/names+ "dhf/harness/plugins/ddd-domain")))))))

(defstep #"it exits (\d) and (names the directory and copies nothing|names no-such and lists the templates rdm-spec carries)"
  (fn [ctx n says]
    (k/check-exit ctx (parse-long n))
    (if (= says "names the directory and copies nothing")
      (do (k/err-has ctx "dhf/harness/plugins/ddd-domain")
          (assert (= (::before ctx) (tree (file ctx "dhf/harness/plugins/ddd-domain"))) "it copied"))
      (do (lists-templates ctx)
          (assert (not (fs/exists? (file ctx "dhf/harness/plugins/no-such"))))))
    ctx))
