(ns rdm-spec.kernel.command
  "The one command, rdm-spec (dhf/spec/kernel/command.feature): the composition
  root and the task table. It dispatches each task to the stages of the
  contexts that own it, in order; a gate stage stops the task when the task
  has findings by then; every other stage reports. It also holds the helpers
  every context shares: findings, processes, git, files at a revision and the
  results directory."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings, and refusing to run

(defn finding
  "A finding (or a warning): {:file :line :rule :message}."
  [file line rule message]
  {:file file :line line :rule rule :message message})

(defn- one-line [s] (str/replace (str s) #"\s*\r?\n\s*" " "))

(defn finding-line [{:keys [file line rule message]}]
  (str (when file (str file (when line (str ":" line)) ": ")) rule ": " (one-line message)))

(defn warning-line [{:keys [file line rule message]}]
  (str (when file (str file (when line (str ":" line)) ": ")) "warning: " rule ": " (one-line message)))

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2)."
  [message]
  (throw (ex-info (str message) {:cannot-run (str message)})))

;; ---------------------------------------------------------------------------
;; Temporary files: under TMPDIR when it is set, as the caller asked

(defn tmp-dir []
  (let [t (System/getenv "TMPDIR")]
    (if (and t (fs/directory? t)) t (System/getProperty "java.io.tmpdir"))))

(defn temp-dir [prefix] (fs/create-temp-dir {:dir (tmp-dir) :prefix prefix}))
(defn temp-file [suffix] (fs/create-temp-file {:dir (tmp-dir) :prefix "rdm-spec-" :suffix suffix}))

;; ---------------------------------------------------------------------------
;; Processes, git, and files at a revision

(defn sh
  "Runs a command, never through a shell. Returns {:exit :out :err}, or
  {:exit nil :not-found true} when the program cannot be started.
  Options: :dir :env (added to the inherited environment, a nil value
  removing a variable) :in :timeout-ms."
  [{:keys [dir env in timeout-ms]} & args]
  (let [base (into {} (System/getenv))
        env' (reduce-kv (fn [m k v] (if (nil? v) (dissoc m k) (assoc m k (str v)))) base (or env {}))]
    (try
      (let [proc (p/process (mapv str args) {:dir (some-> dir str) :env env' :in (or in "") :out :string :err :string})]
        (if timeout-ms
          (let [r (deref proc timeout-ms ::timeout)]
            (if (= ::timeout r)
              (do (p/destroy-tree proc) @proc {:exit nil :timed-out true :out "" :err ""})
              (select-keys r [:exit :out :err])))
          (select-keys @proc [:exit :out :err])))
      (catch java.io.IOException e
        {:exit nil :not-found true :out "" :err (ex-message e)}))))

(def ^:dynamic *git-env*
  "git never runs a hook and never asks a question in a task's fixture."
  {"GIT_TERMINAL_PROMPT" "0"})

(defn git
  "Runs git in `dir`. Returns {:exit :out :err}."
  [dir & args]
  (apply sh {:dir dir :env *git-env*} "git" args))

(defn git!
  "Runs git in `dir`, returning its trimmed output, or cannot-run."
  [dir & args]
  (let [{:keys [exit out err]} (apply git dir args)]
    (if (= 0 exit) (str/trim-newline out) (cannot-run! (str "git " (str/join " " args) " failed: " (one-line err))))))

(defn working-tree? [rev] (or (nil? rev) (= :working-tree rev)))

(defn read-at
  "The text of `path` (relative to the root) at `rev`, or the working tree's
  when rev is nil or :working-tree; nil when it does not exist."
  [root rev path]
  (if (working-tree? rev)
    (let [f (fs/file root path)] (when (fs/regular-file? f) (slurp f)))
    (let [{:keys [exit out]} (git root "show" (str rev ":" path))]
      (when (= 0 exit) out))))

(defn files-at
  "Every file under `dir` (relative to the root) at `rev`, relative to the root, sorted."
  [root rev dir]
  (if (working-tree? rev)
    (let [d (fs/file root dir)]
      (if (fs/directory? d)
        (->> (fs/glob d "**" {:hidden true})
             (filter fs/regular-file?)
             (map #(str (fs/relativize (fs/absolutize root) (fs/absolutize %))))
             sort vec)
        []))
    (let [{:keys [exit out]} (git root "ls-tree" "-r" "--name-only" rev "--" dir)]
      (if (= 0 exit) (vec (sort (remove str/blank? (str/split-lines out)))) []))))

(defn read-edn
  "Reads EDN text; nil text is nil."
  [text]
  (when text (edn/read-string text)))

(defn rel
  "A path relative to the root, as the project names it."
  [root path]
  (let [a (fs/absolutize root) b (fs/absolutize path)]
    (if (str/starts-with? (str (fs/normalize b)) (str (fs/normalize a)))
      (str (fs/relativize (fs/normalize a) (fs/normalize b)))
      (str path))))

(defn results-dir
  "The results directory a task reads: --results, else RESULTS_DIR, else the
  configured one."
  [{:keys [opts env config]}]
  (or (:results opts)
      (let [e (get env "RESULTS_DIR")] (when-not (str/blank? e) e))
      (:results-dir config)
      "dhf/allure-results"))

(defn self-command
  "A command line (words) that runs this same rdm-spec, by path."
  []
  (let [f (some-> (io/resource "rdm_spec/kernel/command.clj") (.getPath))
        src (if f (fs/parent (fs/parent (fs/parent f))) (fs/absolutize "src"))]
    ["bb" "-cp" (str src) "-m" "rdm-spec.kernel.command"]))

;; ---------------------------------------------------------------------------
;; The task table

(def check-stages
  [{:id :knowledge/sources :fn 'rdm-spec.knowledge.sources/stage}
   {:id :knowledge/wiki :fn 'rdm-spec.knowledge.wiki/stage}
   {:id :spec/parser :fn 'rdm-spec.spec.parser/stage}
   {:id :spec/check :fn 'rdm-spec.spec.check/stage}
   {:id :architecture/model :fn 'rdm-spec.architecture.model/stage}
   {:id :architecture/lint :fn 'rdm-spec.architecture.lint/stage}
   {:id :architecture/domain :fn 'rdm-spec.architecture.domain/stage}
   {:id :architecture/views :fn 'rdm-spec.architecture.views/freshness-stage}
   {:id :compliance/gap :fn 'rdm-spec.compliance.gap/stage}
   {:id :regeneration/fence :fn 'rdm-spec.regeneration.fence/paths-stage}])

(def ^:private release-stages
  [{:id :spec/parser :fn 'rdm-spec.spec.parser/stage}
   {:id :verification/status :fn 'rdm-spec.verification.status/stage}
   {:id :compliance/gap :fn 'rdm-spec.compliance.gap/stage :quiet true}
   {:id :verification/release :fn 'rdm-spec.verification.release/stage}])

(def tasks
  "Every task, in the order help lists them."
  [{:task "help" :usage "help" :does "list every task with its usage"
    :project false :args [0 0] :stages [{:id :kernel/command :fn 'rdm-spec.kernel.command/help-stage}]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :project false :args [1 1] :flags #{:id-prefix} :stages [{:id :project/init :fn 'rdm-spec.project.init/stage}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :project false :args [0 0] :stages [{:id :project/adopt :fn 'rdm-spec.project.adopt/stage}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :args [0 0] :stages [{:id :spec/stamper :fn 'rdm-spec.spec.stamper/stage}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the domain model, the views' freshness, sources, wiki, clauses, paths"
    :args [0 0] :flags #{:sources} :stages check-stages}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :args [0 0] :stages [{:id :architecture/model :fn 'rdm-spec.architecture.model/stage :gate true}
                         {:id :architecture/views :fn 'rdm-spec.architecture.views/generate-stage}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :args [0 0] :stages (vec (concat [{:id :regeneration/cut :fn 'rdm-spec.regeneration.cut/precondition-stage}]
                                     (butlast check-stages)
                                     [(assoc (last check-stages) :gate true)]
                                     [{:id :regeneration/cut :fn 'rdm-spec.regeneration.cut/stage}]))}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :args [2 2] :stages [{:id :regeneration/scopes :fn 'rdm-spec.regeneration.scopes/stage}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :args [2 2] :flags #{:context :component}
    :stages [{:id :measurement/oracle :fn 'rdm-spec.measurement.oracle/gate-stage :gate true}
             {:id :regeneration/fence :fn 'rdm-spec.regeneration.fence/stage}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :args [0 0] :flags #{:results} :stages [{:id :kernel/runner :fn 'rdm-spec.kernel.command/run-stage}]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :args [2 2] :stages [{:id :measurement/oracle :fn 'rdm-spec.measurement.oracle/gate-stage :gate true}
                         {:id :verification/verify :fn 'rdm-spec.verification.verify/prepare-stage}
                         {:id :spec/parser :fn 'rdm-spec.spec.parser/stage :quiet true}
                         {:id :verification/verify :fn 'rdm-spec.verification.verify/stage}]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :args [1 1] :stages [{:id :measurement/checklist :fn 'rdm-spec.measurement.checklist/prepare-stage}
                         {:id :spec/parser :fn 'rdm-spec.spec.parser/stage}
                         {:id :compliance/gap :fn 'rdm-spec.compliance.gap/stage :quiet true}
                         {:id :measurement/checklist :fn 'rdm-spec.measurement.checklist/stage}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :args [0 0] :flags #{:results} :stages [{:id :spec/parser :fn 'rdm-spec.spec.parser/stage}
                                            {:id :verification/status :fn 'rdm-spec.verification.status/stage}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :args [1 1] :flags #{:results} :results-optional true
    :stages [{:id :spec/parser :fn 'rdm-spec.spec.parser/stage}
             {:id :verification/status :fn 'rdm-spec.verification.status/stage}
             {:id :verification/trace :fn 'rdm-spec.verification.trace/stage}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :args [0 0] :flags #{:results} :stages release-stages}
   {:task "score" :usage "score CYCLE --verdicts FILE" :does "file a grader's verdicts on a verified cycle into its score.edn"
    :args [1 1] :flags #{:verdicts} :stages [{:id :measurement/scorer :fn 'rdm-spec.measurement.scorer/stage}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :args [2 2] :stages [{:id :measurement/scoreboard :fn 'rdm-spec.measurement.scoreboard/stage}]}
   {:task "oracle" :usage "oracle [ARGS]" :does "run the oracle the project keeps, the command its configuration names"
    :args :raw :stages [{:id :measurement/oracle :fn 'rdm-spec.measurement.oracle/stage}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :args [0 0] :flags #{:results} :results-optional true
    :stages [{:id :architecture/views :fn 'rdm-spec.architecture.views/freshness-stage :gate true}
             {:id :knowledge/sources :fn 'rdm-spec.knowledge.sources/stage :quiet true}
             {:id :spec/parser :fn 'rdm-spec.spec.parser/stage}
             {:id :spec/check :fn 'rdm-spec.spec.check/stage :quiet true}
             {:id :verification/status :fn 'rdm-spec.verification.status/stage}
             {:id :publishing/site :fn 'rdm-spec.publishing.site/stage}]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :args [1 1] :flags #{:results}
    :stages (vec (concat [{:id :compliance/audit :fn 'rdm-spec.compliance.audit/precondition-stage}
                          {:id :compliance/audit :fn 'rdm-spec.compliance.audit/committed-stage :gate true}]
                         (butlast release-stages)
                         [(assoc (last release-stages) :gate true)]
                         [{:id :compliance/audit :fn 'rdm-spec.compliance.audit/stage}]))}
   {:task "observe" :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :args [3 4] :flags #{:tokens} :stages [{:id :agency/observe :fn 'rdm-spec.agency.observe/stage}]}])

(def task-names (mapv :task tasks))

(defn help-stage [_]
  {:result {:help (mapv #(select-keys % [:task :usage :does]) tasks)}})

;; ---------------------------------------------------------------------------
;; `rdm-spec run`: the kernel's runner on a project's scenarios

(defn run-stage [{:keys [root config] :as ctx}]
  (let [runner-ns (do (require 'rdm-spec.kernel.runner) (find-ns 'rdm-spec.kernel.runner))
        f (fn [s] (ns-resolve runner-ns s))
        files ((f 'feature-files) root (:spec-dir config "dhf/spec"))]
    (when (empty? files)
      (cannot-run! (str "no scenario was found: " (:spec-dir config "dhf/spec") " holds no feature file")))
    (let [dir (results-dir ctx)
          abs (fs/absolutize (fs/path root dir))]
      ((f 'load-steps!) root (:steps-dir config "test/steps"))
      (fs/create-dirs abs)
      (let [{:keys [counts refusals results]}
            ((f 'run-features) {:files (map (fn [x] {:file x :text (slurp (fs/file root x))}) files)
                                :results-dir (str abs)
                                :owner ((f 'model-owners) root config)
                                :needs-path (:needs config "dhf/spec/needs.edn")
                                :id-prefix (:id-prefix config "spec")})
            bad (remove #(#{"passed" "skipped"} (get % "status")) results)]
        {:findings (concat refusals
                           (map (fn [r] (finding (get-in r [(keyword "rdm-spec.kernel.runner" "file")])
                                                 (get-in r [(keyword "rdm-spec.kernel.runner" "line")])
                                                 (str "scenario-" (get r "status"))
                                                 (str (get r "name") ": " (get-in r ["statusDetails" "message"]))))
                                bad))
         :result {:run (merge {:results dir :scenarios (count results)} counts)}}))))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- parse-args
  "Splits the words after the task into positional arguments and options."
  [task words]
  (if (= :raw (:args task))
    {:args (vec words) :opts {}}
    (loop [ws words args [] opts {}]
      (if-let [w (first ws)]
        (cond
          (= "--events" w) (recur (rest ws) args (assoc opts :events true))
          (str/starts-with? w "--")
          (let [k (keyword (subs w 2))]
            (cond
              (not (contains? (:flags task #{}) k))
              (cannot-run! (str "unknown argument " w " for " (:task task) "; usage: rdm-spec " (:usage task)))
              (nil? (second ws))
              (cannot-run! (str "the argument " w " needs a value; usage: rdm-spec " (:usage task)))
              :else (recur (nnext ws) args (assoc opts k (second ws)))))
          :else (recur (rest ws) (conj args w) opts))
        (let [[lo hi] (:args task)]
          (when-not (<= lo (count args) hi)
            (cannot-run! (str "wrong number of arguments for " (:task task) "; usage: rdm-spec " (:usage task))))
          {:args args :opts opts})))))

(defn- load-config [root]
  (let [f (fs/file root "dhf/harness/config.edn")]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "not a project's root: " root " holds no dhf/harness/config.edn"
                        " (rdm-spec runs in the root of a project set up by init or adopt)")))
    (try (edn/read-string (slurp f))
         (catch Exception e
           (cannot-run! (str "the configuration dhf/harness/config.edn cannot be read: " (ex-message e)))))))

(defn- resolve-stage [{:keys [fn id]}]
  (or (try (requiring-resolve fn) (catch Exception _ nil))
      (cannot-run! (str "the stage " (subs (str id) 1) " (" fn ") is not built, so the task cannot run"))))

(defn run-task
  "Runs a task. Returns {:exit :out :err} (strings), never exiting."
  [task-name words {:keys [root env]}]
  (bus/start!)
  (let [root (str (fs/absolutize (or root ".")))
        env (or env (into {} (System/getenv)))
        out (StringBuilder.) err (StringBuilder.)
        emit (fn [^StringBuilder sb s] (.append sb (str s "\n")))]
    (try
      (let [task (or (first (filter #(= task-name (:task %)) tasks))
                     (cannot-run! (str "unknown task " task-name "; the tasks are: " (str/join ", " task-names))))
            {:keys [args opts]} (parse-args task words)
            config (when-not (false? (:project task)) (load-config root))
            ctx0 {:root root :config config :args args :opts opts :env env :task task-name
                  :results-optional (:results-optional task)}]
        (loop [stages (:stages task) ctx ctx0 findings [] warnings [] result {}]
          (if-let [stage (first stages)]
            (if (and (:when stage) (not ((:when stage) ctx)))
              (recur (rest stages) ctx findings warnings result)
              (let [f (resolve-stage stage)
                    r (or (f (assoc ctx :stage stage)) {})]
                (if-let [raw (:raw r)]
                  (do (.append out (str (:out raw))) (.append err (str (:err raw)))
                      {:exit (:exit raw) :out (str out) :err (str err)})
                  (let [findings (into findings (when-not (:quiet stage) (:findings r)))
                        warnings (into warnings (when-not (:quiet stage) (:warnings r)))
                        result (merge result (:result r))
                        ctx (merge ctx (:ctx r))]
                    (if (or (and (:gate stage) (seq findings)) (:stop r))
                      (do (doseq [x findings] (emit err (finding-line x)))
                          (doseq [x warnings] (emit err (warning-line x)))
                          (when-let [raw-err (:err-raw r)] (.append err (str raw-err)))
                          (when (:events opts)
                            (doseq [[e pub] (bus/published)] (emit err (str "event: " (name e) " published-by " (subs (str pub) 1)))))
                          (emit out (pr-str (if (:stop r) result {})))
                          {:exit (or (:exit r) 1) :out (str out) :err (str err)})
                      (recur (rest stages) ctx findings warnings result))))))
            (do (doseq [x findings] (emit err (finding-line x)))
                (doseq [x warnings] (emit err (warning-line x)))
                (when (:events opts)
                  (doseq [[e pub] (bus/published)] (emit err (str "event: " (name e) " published-by " (subs (str pub) 1)))))
                (emit out (pr-str result))
                {:exit (if (seq findings) 1 0) :out (str out) :err (str err)}))))
      (catch clojure.lang.ExceptionInfo e
        (if-let [why (:cannot-run (ex-data e))]
          {:exit 2 :out "" :err (str "cannot-run: " (one-line why) "\n")}
          {:exit 2 :out "" :err (str "cannot-run: an error in rdm-spec: " (one-line (ex-message e)) "\n")}))
      (catch Throwable e
        {:exit 2 :out "" :err (str "cannot-run: an error in rdm-spec: " (one-line (str (.getName (class e)) ": " (ex-message e))) "\n")}))))

(defn -main [& argv]
  (let [[task & words] argv
        {:keys [exit out err]} (run-task (or task "help") words {:root (System/getProperty "user.dir")})]
    (print out) (flush)
    (binding [*out* *err*] (print err) (flush))
    (shutdown-agents)
    (System/exit (or exit 2))))
