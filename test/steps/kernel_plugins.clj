(ns steps.kernel-plugins
  "Step definitions of dhf/spec/kernel/plugins.feature: each plugin is a
  small shell script the scenario writes into its project."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defn script-path [name] (str "dhf/harness/plugins/" name "/run.sh"))

(defn plugin
  "A plugin entry running the script of `name`."
  [name m]
  (merge {:name name :command ["sh" (script-path name)]} m))

(defn plugins!
  "A project whose :plugins are `entries`, each script of {name text} written."
  [ctx entries scripts]
  (let [ctx (k/project! ctx {:config {:plugins entries}})]
    (k/write-files! (:dir ctx) (into {} (for [[n t] scripts] [(script-path n) t])))
    ctx))

(def recorder
  "Writes what it is given, where it ran and its RDM_SPEC, appending a line to ran.log."
  "cat > given.edn\npwd > cwd.txt\nprintf '%s' \"$RDM_SPEC\" > rdm-spec.txt\necho ran >> ran.log\n")

(defn- file [ctx p] (fs/file (k/guard! (:dir ctx)) p))

(defn- slurp-if [f] (when (fs/exists? f) (slurp f)))

;; --- declared in the configuration

(defstep #"a configuration whose :plugins is (\[.+\])"
  (fn [ctx entries]
    (let [es (edn/read-string entries)
          ctx (k/project! ctx {:config {:plugins es}})]
      (k/write-files! (:dir ctx) {"dhf/harness/plugins/lint-a/run.clj" "(slurp *in*)\n"
                                  "dhf/harness/oracle/oracle.clj" "(println {:oracle {:differences 0}})\n"})
      (assoc ctx ::declared es))))

(defstep #"each entry is read as exactly :name, :command, one of :subscribes \(a vector of event names\) or :answers \(:Gate or :Check\), and an optional :seconds, its time limit"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [read (:plugins (k/edn-out ctx))]
      (assert (= (map :name (::declared ctx)) (map :name read)) (:out ctx))
      (doseq [[d r] (map vector (::declared ctx) read)]
        (assert (= (dissoc d :seconds) (dissoc r :seconds)) (pr-str r))
        (assert (#{#{:name :command :subscribes :seconds} #{:name :command :answers :seconds}} (set (keys r))) (pr-str r)))
      (assert (= 20 (:seconds (first read))) (pr-str read))
      ctx)))

(defstep #"a plugin with no :seconds has 60 seconds"
  (fn [ctx]
    (assert (= 60 (:seconds (second (:plugins (k/edn-out ctx))))) (:out ctx))
    ctx))

(def untrusted
  {"a plugin with neither :subscribes nor :answers" [(plugin "lint-a" {})]
   "a plugin with both :subscribes and :answers" [(plugin "lint-a" {:subscribes [:SpecParsed] :answers :Gate})]
   "a plugin subscribing to :NoSuchEvent" [(plugin "lint-a" {:subscribes [:NoSuchEvent]})]
   "a plugin answering :Review" [(plugin "lint-a" {:answers :Review})]
   "two plugins named \"lint-a\"" [(plugin "lint-a" {:subscribes [:SpecParsed]}) (plugin "lint-a" {:answers :Gate})]
   "a plugin whose command is under the implementation's paths" [{:name "lint-a" :subscribes [:SpecParsed] :command ["bb" "src/lint.clj"]}]})

(defstep #"a configuration whose :plugins holds (a plugin (?:with|subscribing to|answering|whose).+|two plugins named \"lint-a\")"
  (fn [ctx what]
    (let [es (or (untrusted what) (fail! (str "no such declaration: " what)))]
      (plugins! ctx es {"lint-a" "echo ran >> ran.log\n"}))))

(defstep #"it exits 2, names the plugin, and says (.+)"
  (fn [ctx why]
    (k/check-exit ctx 2)
    (let [why (str/replace why #", and lists the events it publishes$" "")]
      (k/err-line-with ctx "cannot-run:" "lint-a" why)
      (when (str/includes? why "NoSuchEvent")
        (k/err-line-with ctx "SpecParsed" "SpecGated" "KnowledgeIndexed" "VerificationDerived" "ClausesIndexed")))
    (assert (not (fs/exists? (file ctx "ran.log"))) "a plugin ran")
    (assert (str/blank? (:out ctx)) (:out ctx))
    ctx))

;; --- subscribers

(defstep #"a plugin subscribing to :SpecParsed whose command writes what it is given to a file in the project"
  (fn [ctx] (plugins! ctx [(plugin "rec" {:subscribes [:SpecParsed]})] {"rec" recorder})))

(defstep #"the plugin ran once, in the project's root, with the environment variable RDM_SPEC set as the oracle's is"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (= "ran\n" (slurp-if (file ctx "ran.log"))) (str (slurp-if (file ctx "ran.log"))))
    (assert (= (str (fs/canonicalize (:dir ctx))) (str (fs/canonicalize (str/trim (slurp (file ctx "cwd.txt")))))))
    (let [line (slurp (file ctx "rdm-spec.txt"))
          r (k/sh (:dir ctx) {} "sh" "-c" (str line " help"))]
      (assert (not (str/blank? line)) "RDM_SPEC is not set")
      (assert (= 0 (:exit r)) (str line ": " (:err r)))
      (assert (str/starts-with? (:out r) "{:help") (:out r)))
    ctx))

(defstep #"its standard input was the SpecParsed event as EDN: \{:event :SpecParsed\} merged with every key events.edn says the event carries"
  (fn [ctx]
    (let [given (edn/read-string (slurp (file ctx "given.edn")))
          carries (get-in (edn/read-string (slurp (fs/file k/room-root "dhf/spec/events.edn"))) [:SpecParsed :carries])]
      (assert (= (set (cons :event carries)) (set (keys given))) (pr-str (keys given)))
      (assert (= :SpecParsed (:event given)))
      (assert (= ["spec-0001"] (map :id (:scenarios given))) (pr-str (:scenarios given)))
      ctx)))

(defn stamper [name] (str "echo \"" name " $(date +%s%N)\" >> order.log\n"))

(defstep #"two plugins subscribing to :SpecParsed, \"lint-a\" then \"lint-b\", each writing its name and the time to a shared file"
  (fn [ctx]
    (plugins! ctx [(plugin "lint-a" {:subscribes [:SpecParsed]}) (plugin "lint-b" {:subscribes [:SpecParsed]})]
              {"lint-a" (stamper "lint-a") "lint-b" (stamper "lint-b")})))

(defstep #"lint-a ran before lint-b, and both after the spec check's own stages"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [ls (map #(str/split % #" ") (str/split-lines (slurp (file ctx "order.log"))))
          [[a ta] [b tb]] ls]
      (assert (= ["lint-a" "lint-b"] [a b]) (pr-str ls))
      (assert (<= (parse-long ta) (parse-long tb)))
      ;; the core's stages publish SpecParsed, then SpecGated; both happened
      ;; before the plugins were given anything
      (assert (> (quot (parse-long ta) 1000000) (:started ctx)))
      ctx)))

(defstep #"a plugin subscribing only to :VerificationDerived"
  (fn [ctx] (plugins! ctx [(plugin "rec" {:subscribes [:VerificationDerived]})] {"rec" recorder})))

(defstep #"the plugin did not run"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (fs/exists? (file ctx "ran.log"))) "the plugin ran")
    ctx))

(def says
  {"writes \"dhf/spec/a/b.feature:4: action-unoffered: no form offers it\" on its error stream and exits 1"
   ["echo 'dhf/spec/a/b.feature:4: action-unoffered: no form offers it' >&2\nexit 1\n"
    "dhf/spec/a/b.feature:4: action-unoffered: no form offers it"]
   "writes \"dhf/spec/a/b.feature:4: warning: label-long: …\" on its error stream and exits 0"
   ["echo 'dhf/spec/a/b.feature:4: warning: label-long: …' >&2\nexit 0\n" "dhf/spec/a/b.feature:4: warning: label-long: …"]
   "writes nothing and exits 0" ["exit 0\n" nil]
   "exits 2, writing \"cannot read affordances.yaml\" on its error stream"
   ["echo 'cannot read affordances.yaml' >&2\nexit 2\n" "cannot read affordances.yaml"]})

(defstep #"a plugin subscribing to :SpecParsed that (.+)"
  (fn [ctx does]
    (let [[script line] (or (says does) (fail! (str "no such plugin: " does)))]
      (-> (plugins! ctx [(plugin "judge" {:subscribes [:SpecParsed]})] {"judge" script})
          (assoc ::line line)))))

(defstep #"\"rdm-spec check\" is run on a spec that passes every check"
  (fn [ctx]
    (let [ctx (k/project! ctx)
          plugins (:plugins (edn/read-string (slurp (file ctx "dhf/harness/config.edn"))))
          plain (k/rdm (k/update-edn! ctx "dhf/harness/config.edn" dissoc :plugins) ["check"])]
      (k/check-exit plain 0)
      (k/update-edn! ctx "dhf/harness/config.edn" assoc :plugins plugins)
      (k/rdm ctx ["check"]))))

(defstep #"it exits (\d), and the error stream (.+)"
  (fn [ctx n holds]
    (k/check-exit ctx (parse-long n))
    (let [line (::line ctx)
          ls (k/err-lines ctx)]
      (cond
        (str/starts-with? holds "that line, unchanged")
        (assert (some #{line} ls) (:err ctx))
        (= holds "no line from the plugin")
        (assert (not-any? #(str/includes? % "judge") ls) (:err ctx))
        (str/starts-with? holds "cannot-run")
        (k/err-line-with ctx "cannot-run:" "judge" line)
        :else (fail! (str "unknown: " holds)))
      ctx)))

(defstep #"a plugin subscribing to :SpecParsed with :seconds 2, which prints \"started\" and then sleeps for 10 seconds"
  (fn [ctx]
    (plugins! ctx [(plugin "slow" {:subscribes [:SpecParsed] :seconds 2})]
              {"slow" "echo started\necho $$ > plugin.pid\nexec sleep 10\n"})))

(defstep #"it exits 2 within 5 seconds, says the plugin timed out after 2 seconds, and relays \"started\""
  (fn [ctx]
    (k/check-exit ctx 2)
    (assert (< (:seconds ctx) 5) (str "it took " (:seconds ctx) " seconds"))
    (k/err-line-with ctx "slow" "timed out after 2 seconds")
    (k/err-has ctx "started")))

(defstep #"no process of the plugin is still running"
  (fn [ctx]
    (let [pid (str/trim (slurp (file ctx "plugin.pid")))]
      (assert (not (fs/exists? (str "/proc/" pid))) (str "the plugin's process " pid " still runs"))
      ctx)))

(defstep #"a plugin whose command is \[\"no-such-plugin\"\]"
  (fn [ctx] (plugins! ctx [{:name "missing" :subscribes [:SpecParsed] :command ["no-such-plugin"]}] {})))

(defstep #"it exits 2 and names the plugin and its command"
  (fn [ctx] (k/check-exit ctx 2) (k/err-line-with ctx "cannot-run:" "missing" "no-such-plugin")))

;; --- answerers

(defn gate-script [name exit & [finding]]
  (str "echo " name " >> gates.log\n"
       (when finding (str "echo '" finding "' >&2\n"))
       "exit " exit "\n"))

(defstep #"a project with two plugins answering :Gate, \"first\" exiting 0 and \"second\" exiting 1 with the finding \"(.+)\""
  (fn [ctx finding]
    (-> (plugins! ctx [(plugin "first" {:answers :Gate}) (plugin "second" {:answers :Gate})]
                  {"first" (gate-script "first" 0) "second" (gate-script "second" 1 finding)})
        (k/tag-cut! "spec-3")
        (assoc ::line finding))))

(defstep #"first ran, then second; it exits 1, relays the finding unchanged, and builds no room"
  (fn [ctx]
    (k/check-exit ctx 1)
    (assert (= "first\nsecond\n" (slurp-if (file ctx "gates.log"))) (str (slurp-if (file ctx "gates.log"))))
    (assert (some #{(::line ctx)} (k/err-lines ctx)) (:err ctx))
    (assert (not (fs/exists? (file ctx "room"))) "a room was built")
    ctx))

(defstep #"a plugin answering :Gate whose command writes what it is given to a file in the project"
  (fn [ctx]
    (-> (plugins! ctx [(plugin "rec" {:answers :Gate})] {"rec" recorder})
        k/cycle-ready!)))

(defstep #"its standard input was \{:request :Gate :task :verify :cut \"spec-3\" :cycle \"03\"\}"
  (fn [ctx]
    (let [given (edn/read-string (slurp-if (file ctx "given.edn")))]
      (assert (= {:request :Gate :task :verify :cut "spec-3" :cycle "03"} given) (str (pr-str given) (:err ctx)))
      ctx)))

;; --- templates

(def template-dir (str (fs/path k/room-root "plugins/ddd-domain")))

(defn tree [d]
  (into (sorted-map) (for [f (fs/glob d "**" {:hidden true}) :when (fs/regular-file? f)]
                       [(str (fs/relativize d f)) (slurp (fs/file f))])))

(defstep #"a project set up by rdm-spec with no dhf/harness/plugins/ddd-domain"
  (fn [ctx]
    (let [ctx (k/init-project! ctx)]
      (assert (not (fs/exists? (file ctx "dhf/harness/plugins/ddd-domain"))))
      (assoc ctx ::config (slurp (file ctx "dhf/harness/config.edn"))))))

(defstep #"the template ddd-domain rdm-spec carries is copied into dhf/harness/plugins/ddd-domain, unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [copy (tree (file ctx "dhf/harness/plugins/ddd-domain"))]
      (assert (= (tree template-dir) (dissoc copy "origin.edn")) (pr-str (keys copy)))
      ctx)))

(defstep #"dhf/harness/plugins/ddd-domain/origin.edn holds exactly \{:template \"ddd-domain\" :version V :copied D\}, V the template's version and D today's date"
  (fn [ctx]
    (let [o (edn/read-string (slurp (file ctx "dhf/harness/plugins/ddd-domain/origin.edn")))
          t (edn/read-string (slurp (fs/file template-dir "template.edn")))]
      (assert (= {:template "ddd-domain" :version (:version t) :copied (str (java.time.LocalDate/now))} o) (pr-str o))
      ctx)))

(defstep #"it prints the :plugins entry to add to the configuration, and changes the configuration not at all"
  (fn [ctx]
    (let [t (edn/read-string (slurp (fs/file template-dir "template.edn")))]
      (assert (some #(= (:plugin t) %) (tree-seq coll? seq (k/edn-out ctx))) (:out ctx))
      (assert (= (::config ctx) (slurp (file ctx "dhf/harness/config.edn"))))
      ctx)))

(defstep #"a project set up by rdm-spec that already has dhf/harness/plugins/ddd-domain"
  (fn [ctx]
    (let [ctx (k/init-project! ctx)]
      (k/write-files! (:dir ctx) {"dhf/harness/plugins/ddd-domain/mine.txt" "mine\n"})
      ctx)))

(defstep #"a project set up by rdm-spec and nothing else"
  (fn [ctx] (k/init-project! ctx)))

(defstep #"it exits 1 and names the directory and copies nothing"
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-has ctx "dhf/harness/plugins/ddd-domain")
    (assert (= {"mine.txt" "mine\n"} (tree (file ctx "dhf/harness/plugins/ddd-domain"))))
    ctx))

(defstep #"it exits 2 and names no-such and lists the templates rdm-spec carries"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "no-such" "affordances" "ddd-domain")
    (assert (not (fs/exists? (file ctx "dhf/harness/plugins/no-such"))))
    ctx))
