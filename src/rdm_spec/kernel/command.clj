(ns rdm-spec.kernel.command
  "The rdm-spec command (dhf/spec/kernel/command.feature): one entry point
  for every task. It belongs to no bounded context: it reads the project's
  configuration, runs each task's stages in order (a gate stopping the task
  when it has findings), and reports every task the same way: the result as
  EDN on standard output, one line per finding, then per warning, on the
  error stream, and the exit 0 (pass), 1 (a finding) or 2 (cannot run).

  Stages are named, never required: a context's code is loaded when a task
  needs it, so a stage whose code is not built refuses the task (exit 2), and
  every other task still runs.

  The helpers here (findings, reading a revision, running git and other
  commands within a time limit) are the kernel's, shared by every context."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.bus :as bus]))

;; ---------------------------------------------------------------------------
;; Findings and refusals

(defn finding
  "One finding: FILE:LINE: RULE: message, file and line optional."
  [file line rule message]
  {:file file :line line :rule rule :message message})

(defn cannot-run!
  "Stops the task: it cannot run as asked (exit 2)."
  [why]
  (throw (ex-info (str why) {:cannot-run (str why)})))

(defn one-line [s] (str/replace (str s) #"\s*[\r\n]+\s*" " "))

(defn finding-line
  "A finding or warning as the line the error stream holds."
  [{:keys [file line rule message raw]} warning?]
  (if raw
    (one-line raw)
    (str (when file (str file (when line (str ":" line)) ": "))
         (when warning? "warning: ")
         rule ": " (one-line message))))

;; ---------------------------------------------------------------------------
;; Running other commands

(defn tmp-root
  "The temporary directory: TMPDIR, else the platform's."
  []
  (or (System/getenv "TMPDIR") (System/getProperty "java.io.tmpdir")))

(defn temp-dir [prefix]
  (fs/create-dirs (tmp-root))
  (fs/create-temp-dir {:dir (tmp-root) :prefix prefix}))

(defn temp-file [suffix]
  (fs/create-dirs (tmp-root))
  (fs/create-temp-file {:dir (tmp-root) :suffix suffix}))

(defn kill-tree!
  "Stops a process and every process it started."
  [^Process proc]
  (let [kids (try (vec (iterator-seq (.iterator (.descendants (.toHandle proc))))) (catch Exception _ []))]
    (.destroyForcibly proc)
    (doseq [k kids] (.destroyForcibly k))))

(defn run-process
  "Runs cmd: {:exit :out :err :seconds}, {:timed-out true :out :err} past
  seconds, {:not-found true} when it cannot be started. opts: :dir :env (added
  to the environment) :in :seconds."
  [cmd {:keys [dir env in seconds]}]
  (let [start (System/nanoTime)
        in-file (when (seq in) (let [f (temp-file ".in")] (spit (fs/file f) in) f))
        proc (try (p/process (vec cmd) (cond-> {:out :string :err :string :in (if in-file (fs/file in-file) "")}
                                         dir (assoc :dir (str dir))
                                         env (assoc :extra-env env)))
                  (catch java.io.IOException e {::not-found (ex-message e)}))]
    (try
    (if (::not-found proc)
      {:not-found true :why (::not-found proc)}
      (let [r (if seconds (deref proc (long (* 1000 seconds)) ::timeout) @proc)
            took (/ (- (System/nanoTime) start) 1e9)]
        (if (= ::timeout r)
          (do (kill-tree! (:proc proc))
              (let [out (deref (:out proc) 2000 "") err (deref (:err proc) 2000 "")]
                {:timed-out true :out (str out) :err (str err) :seconds took}))
          {:exit (:exit r) :out (:out r) :err (:err r) :seconds took})))
    (finally (when in-file (fs/delete-if-exists in-file))))))

(defn sh
  "Runs args: {:exit :out :err}, or {:not-found true} when the program is not
  found. opts: :dir :env."
  [{:keys [dir env]} & args]
  (run-process args {:dir dir :env env}))

(defn git
  "Runs git in root: {:exit :out :err}. Paths are the project's, relative to
  its root, also when it is one folder of a larger repository."
  [root & args]
  (let [r (apply sh {:dir root} "git" "-c" "diff.relative=true" "-c" "core.quotePath=false" args)]
    (if (:not-found r) {:exit 127 :out "" :err "git is not installed"} r)))

(defn git!
  "Runs git in root and returns its output, trimmed; cannot run when it fails."
  [root & args]
  (let [{:keys [exit out err]} (apply git root args)]
    (when-not (= 0 exit)
      (cannot-run! (str "git " (str/join " " args) " failed: " (str/trim (str err)))))
    (str/trim out)))

;; ---------------------------------------------------------------------------
;; Reading the project, at a revision or in the working tree

(defn working-tree? [rev] (or (nil? rev) (= :working-tree rev)))

(defn read-at
  "A file's text at a revision (nil: the working tree), relative to the
  project's root; nil when it is not there."
  [root rev path]
  (if (working-tree? rev)
    (let [f (fs/path root path)]
      (when (fs/regular-file? f) (slurp (fs/file f))))
    (let [{:keys [exit out]} (git root "show" (str rev ":./" path))]
      (when (= 0 exit) out))))

(defn files-at
  "Every file under dir at a revision (nil: the working tree), relative to
  the project's root, sorted."
  [root rev dir]
  (if (working-tree? rev)
    (let [d (fs/path root dir)]
      (if (fs/directory? d)
        (->> (fs/glob d "**")
             (filter fs/regular-file?)
             (map #(str (fs/relativize root %)))
             sort vec)
        []))
    (let [{:keys [exit out]} (git root "ls-tree" "-r" "--name-only" rev "--" dir)]
      (if (= 0 exit) (vec (sort (remove str/blank? (str/split-lines out)))) []))))

(defn read-edn [text]
  (when text (edn/read-string {:default tagged-literal} text)))

(defn results-dir
  "The results a task reads: --results, else RESULTS_DIR, else the configured one."
  [{:keys [opts env config]}]
  (or (:results opts)
      (get (or env (System/getenv)) "RESULTS_DIR")
      (:results-dir config)
      "dhf/allure-results"))

(defn results-given?
  "Whether the results directory was named, by --results or RESULTS_DIR."
  [{:keys [opts env]}]
  (boolean (or (:results opts) (get (or env (System/getenv)) "RESULTS_DIR"))))

(defn this-command-line
  "A command line that starts this same rdm-spec, however it was installed."
  []
  (let [cp (System/getProperty "java.class.path")
        here (System/getProperty "user.dir")
        abs (str/join ":" (for [e (str/split (str cp) #":") :when (seq e)]
                            (str (fs/absolutize (fs/path here e)))))]
    (str "bb -cp " abs " -m rdm-spec.kernel.command")))

;; ---------------------------------------------------------------------------
;; Several specs

(defn spec-configs
  "One configuration per spec the project keeps: each of :specs, its folder's
  files in place of the configuration's, or the configuration itself."
  [config]
  (if-let [specs (seq (:specs config))]
    (vec (for [{:keys [name dir id-prefix]} specs]
           (assoc config :name name :spec-dir dir :id-prefix id-prefix
                  :model (str dir "/model.edn") :events (str dir "/events.edn")
                  :needs (str dir "/needs.edn") :ledger (str dir "/ids.edn")
                  :glossary (str dir "/glossary.md") :domain (str dir "/domain.edn"))))
    [(assoc config :name nil)]))

(defn- check-specs! [root config]
  (when-let [specs (:specs config)]
    (when-not (and (sequential? specs) (every? map? specs))
      (cannot-run! ":specs is a vector of {:name :dir :id-prefix}"))
    (doseq [[k why] [[:name "a spec's name is declared once"]
                     [:id-prefix "an id prefix is declared once, so no two scenarios share an id"]
                     [:dir "a folder holds one spec"]]
            [v n] (frequencies (map k specs)) :when (> n 1)]
      (cannot-run! (str "the configuration's :specs declares " (pr-str v) " " n " times: " why)))
    (doseq [{:keys [name dir id-prefix]} specs]
      (when-not (and (string? name) (string? dir) (string? id-prefix))
        (cannot-run! (str "the spec " (pr-str name) " needs a :name, a :dir and an :id-prefix")))
      (when-not (fs/directory? (fs/path root dir))
        (cannot-run! (str "the spec " name "'s folder " dir " does not exist"))))))

;; ---------------------------------------------------------------------------
;; The tasks

(def tasks
  [{:task "help" :usage "help" :does "list every task with its usage"
    :no-project true :stages [{:f `help-stage}]}
   {:task "init" :usage "init PATH [--id-prefix P]" :does "write the skeleton into a path that does not exist"
    :argc [1 1] :no-project true :stages [{:f 'rdm-spec.project.init/stage}]}
   {:task "adopt" :usage "adopt" :does "add the skeleton to the current repository, overwriting nothing"
    :argc [0 0] :no-project true :stages [{:f 'rdm-spec.project.adopt/stage}]}
   {:task "stamp" :usage "stamp" :does "give every unstamped scenario its id"
    :argc [0 0] :stages [{:f 'rdm-spec.spec.stamper/stage :per-spec true}]}
   {:task "check" :usage "check [--sources DIR] [--events]"
    :does "the spec check, the model lint, the views' freshness, sources, wiki, clauses, paths, and the plugins that subscribe to what it reads"
    :argc [0 0] :stages :check}
   {:task "views" :usage "views" :does "generate and render every view to dhf/views/"
    :argc [0 0] :stages [{:f 'rdm-spec.architecture.views/generate-stage :per-spec true}]}
   {:task "cut" :usage "cut" :does "tag HEAD spec-N when it is origin/HEAD, and push the tag"
    :argc [0 0] :stages :cut}
   {:task "scopes" :usage "scopes CUT CUT" :does "list the scopes a spec change touched between two cuts"
    :argc [2 2] :stages [{:f 'rdm-spec.regeneration.scopes/stage}]}
   {:task "fence" :usage "fence ROOM CUT [--context C,...] [--component C/N,...]" :does "build a clean room from a cut"
    :argc [2 2] :stages [{:f 'rdm-spec.kernel.plugins/fence-gate-stage :gate true}
                         {:f 'rdm-spec.regeneration.fence/stage}]}
   {:task "run" :usage "run [--results DIR]" :does "run the scenarios against a project's step definitions"
    :argc [0 0] :stages [{:f 'rdm-spec.kernel.runner/stage}]}
   {:task "verify" :usage "verify ROOM CYCLE" :does "run the project's checks on the room's code in a checkout of the cut"
    :argc [2 2] :stages [{:f 'rdm-spec.verification.verify/stage}]}
   {:task "checklist" :usage "checklist CYCLE" :does "derive the cycle's checklist into dhf/cycles/CYCLE/checklist.json"
    :argc [1 1] :stages [{:f 'rdm-spec.measurement.checklist/stage}]}
   {:task "status" :usage "status [--results DIR]" :does "each scenario's status"
    :argc [0 0] :stages [{:f 'rdm-spec.verification.status/stage}]}
   {:task "trace" :usage "trace ID [--results DIR]" :does "a need or a scenario with everything linked to it"
    :argc [1 1] :stages [{:f 'rdm-spec.verification.trace/stage}]}
   {:task "release" :usage "release [--results DIR]" :does "the release check"
    :argc [0 0] :stages [{:f 'rdm-spec.compliance.gap/stage}
                         {:f 'rdm-spec.verification.release/stage}]}
   {:task "score" :usage "score CYCLE --verdicts FILE [--verdicts FILE ...]"
    :does "file the graders' verdicts on a verified cycle into its score.edn"
    :argc [1 1] :stages [{:f 'rdm-spec.measurement.scorer/stage}]}
   {:task "scoreboard" :usage "scoreboard CYCLE CYCLE" :does "compare two cycles"
    :argc [2 2] :stages [{:f 'rdm-spec.measurement.scoreboard/stage}]}
   {:task "oracle" :usage "oracle [ARGS]" :does "run the oracle the project keeps: its plugin named \"oracle\""
    :raw-args true :stages [{:f 'rdm-spec.measurement.oracle/stage}]}
   {:task "plugin" :usage "plugin add NAME" :does "copy a plugin template rdm-spec carries into dhf/harness/plugins/NAME"
    :argc [2 2] :stages [{:f 'rdm-spec.kernel.plugins/add-stage}]}
   {:task "publish" :usage "publish [--results DIR]" :does "write the site and build it"
    :argc [0 0] :stages [{:f 'rdm-spec.architecture.views/freshness-stage :gate true :per-spec true}
                         {:f 'rdm-spec.knowledge.sources/stage}
                         {:f 'rdm-spec.spec.parser/stage :per-spec true}
                         {:f 'rdm-spec.spec.check/stage :per-spec true}
                         {:f 'rdm-spec.verification.status/derive-stage}
                         {:f 'rdm-spec.publishing.site/stage}]}
   {:task "audit" :usage "audit OUT [--results DIR]"
    :does "write the auditor's and the engineer's copy, with their evidence, to OUT"
    :argc [1 1] :stages [{:f 'rdm-spec.compliance.audit/precondition-stage}
                         {:f 'rdm-spec.compliance.audit/committed-stage :gate true}
                         {:f 'rdm-spec.compliance.gap/stage}
                         {:f 'rdm-spec.spec.parser/stage :per-spec true}
                         {:f 'rdm-spec.verification.release/stage}
                         {:barrier true}
                         {:f 'rdm-spec.compliance.audit/stage}]}
   {:task "observe" :usage "observe begin CYCLE ROLE ROOM, or end CYCLE ROLE [--tokens N], or report CYCLE CYCLE"
    :does "record a run: its time, its cost and what it touched outside its room"
    :argc [1 4] :stages [{:f 'rdm-spec.agency.observe/stage}]}])

(def check-stages
  [{:f 'rdm-spec.knowledge.sources/stage :per-spec true}
   {:f 'rdm-spec.knowledge.wiki/stage}
   {:f 'rdm-spec.spec.parser/stage :per-spec true}
   {:f 'rdm-spec.spec.check/stage :per-spec true}
   {:f 'rdm-spec.architecture.model/stage :per-spec true}
   {:f 'rdm-spec.architecture.lint/stage :per-spec true}
   {:f 'rdm-spec.architecture.views/freshness-stage :per-spec true}
   {:f 'rdm-spec.compliance.gap/stage}
   {:f 'rdm-spec.regeneration.fence/paths-stage}])

(defn stages-of [{:keys [stages]}]
  (case stages
    :check check-stages
    :cut (concat [{:f 'rdm-spec.regeneration.cut/precondition-stage}]
                 check-stages
                 [{:barrier true} {:f 'rdm-spec.regeneration.cut/stage}])
    stages))

(defn help-stage [_]
  {:result {:help (mapv #(select-keys % [:task :usage :does]) tasks)}})

;; ---------------------------------------------------------------------------
;; Arguments

(def flags
  {"--results" :results "--sources" :sources "--id-prefix" :id-prefix "--context" :context
   "--component" :component "--verdicts" :verdicts "--tokens" :tokens "--spec" :spec})

(def list-flags #{:context :component})
(def repeat-flags #{:verdicts})

(defn parse-args
  "A task's words as {:args [positional] :opts {flag value}}."
  [task words]
  (loop [ws words args [] opts {}]
    (if-let [w (first ws)]
      (cond
        (= "--events" w) (recur (rest ws) args (assoc opts :events true))
        (flags w)
        (let [k (flags w) v (second ws)]
          (when (or (nil? v) (str/starts-with? v "--"))
            (cannot-run! (str "the argument " w " of " task " needs a value")))
          (recur (drop 2 ws) args
                 (cond (list-flags k) (update opts k (fnil into []) (remove str/blank? (str/split v #",")))
                       (repeat-flags k) (update opts k (fnil conj []) v)
                       :else (assoc opts k v))))
        (str/starts-with? w "--") (cannot-run! (str "unknown argument " w " of " task))
        :else (recur (rest ws) (conj args w) opts))
      {:args args :opts opts})))

;; ---------------------------------------------------------------------------
;; Running a task

(defn- resolve-stage [f]
  (try (or (requiring-resolve f) (cannot-run! (str "the stage " f " is not built")))
       (catch clojure.lang.ExceptionInfo e (if (:cannot-run (ex-data e)) (throw e) (cannot-run! (str "the stage " f " is not built: " (ex-message e)))))
       (catch Throwable e (cannot-run! (str "the stage " f " is not built: " (ex-message e))))))

(defn- deep-merge [a b]
  (cond (nil? b) a
        (and (map? a) (map? b)) (merge-with deep-merge a b)
        :else b))

(defn- read-config [root]
  (let [f (fs/path root "dhf/harness/config.edn")]
    (when-not (fs/regular-file? f)
      (cannot-run! (str "there is no project here: " (str root) " holds no dhf/harness/config.edn;"
                        " rdm-spec runs in a project's root, the folder that holds its configuration")))
    (let [c (try (edn/read-string (slurp (fs/file f)))
                 (catch Exception e (cannot-run! (str "dhf/harness/config.edn cannot be read: " (ex-message e)))))]
      (when-not (map? c) (cannot-run! "dhf/harness/config.edn is not a map"))
      c)))

(defn- call-stage [stage ctx]
  (let [f (resolve-stage (:f stage))]
    (if (:per-spec stage)
      (reduce (fn [acc sc]
                (let [r (or (f (assoc ctx :config sc :spec-name (:name sc))) {})]
                  (-> acc
                      (update :result deep-merge (:result r))
                      (update :findings into (:findings r))
                      (update :warnings into (:warnings r))
                      (update :ctx merge (:ctx r))
                      (cond-> (:stop r) (assoc :stop true :exit (:exit r))))))
              {:result {} :findings [] :warnings [] :ctx {}}
              (spec-configs (:config ctx)))
      (or (f ctx) {}))))

(defn- run-stages
  "Runs the task's stages: {:result :findings :warnings :exit :raw}."
  [task ctx]
  (loop [stages (stages-of task) ctx ctx acc {:result {} :findings [] :warnings []}]
    (if-let [stage (first stages)]
      (if (:barrier stage)
        (if (seq (:findings acc)) acc (recur (rest stages) ctx acc))
        (let [r (call-stage stage ctx)
              acc (-> acc
                      (update :result deep-merge (:result r))
                      (update :findings into (:findings r))
                      (update :warnings into (:warnings r)))]
          (cond
            (:raw r) (assoc acc :raw true :exit (:exit r))
            (:stop r) (cond-> acc (:exit r) (assoc :exit (:exit r)))
            (and (:gate stage) (seq (:findings r))) acc
            :else (recur (rest stages) (merge ctx (:ctx r)) acc))))
      (assoc acc :ctx ctx))))

(defn- print-err [lines]
  (binding [*out* *err*]
    (doseq [l lines] (println l))
    (flush)))

(defn- event-lines []
  (for [[e publisher] (bus/published)]
    (str "event: " (name e) " published-by " (subs (str publisher) 1))))

(defn run
  "Runs the command line `argv` in the directory root; the exit code."
  [root argv]
  (bus/start!)
  (let [[tname & words] argv
        task (first (filter #(= tname (:task %)) tasks))
        events? (some #{"--events"} words)]
    (try
      (when-not task
        (cannot-run! (str "unknown task " (or tname "(none)") ": the tasks are "
                          (str/join ", " (map :task tasks)))))
      (let [{:keys [args opts]} (if (:raw-args task) {:args (vec words) :opts {}} (parse-args tname words))
            [lo hi] (:argc task)]
        (when (and lo (not (<= lo (count args) hi)))
          (cannot-run! (str "usage: rdm-spec " (:usage task))))
        (let [config (when-not (:no-project task) (read-config root))
              _ (when config
                  (check-specs! root config)
                  ((resolve-stage 'rdm-spec.kernel.plugins/validate!) root config))
              ctx {:root root :config config :args args :opts opts :task (keyword tname) :revision nil
                   :env (into {} (System/getenv))}
              subscribed (when config ((resolve-stage 'rdm-spec.kernel.plugins/subscribe!) ctx))
              acc (run-stages task ctx)]
          (if (:raw acc)
            (or (:exit acc) 0)
            (let [plug (when (and subscribed (not (:exit acc)))
                         ((resolve-stage 'rdm-spec.kernel.plugins/run-subscribers!) ctx subscribed))
                  findings (distinct (map #(finding-line % false) (concat (:findings acc) (:findings plug))))
                  warnings (distinct (map #(finding-line % true) (concat (:warnings acc) (:warnings plug))))
                  result (deep-merge (:result acc) (:result plug))]
              (binding [*print-namespace-maps* false]
                (println (pr-str (or result {}))))
              (flush)
              (print-err (concat findings warnings (when events? (event-lines))))
              (or (:exit acc) (if (seq findings) 1 0))))))
      (catch clojure.lang.ExceptionInfo e
        (if-let [why (:cannot-run (ex-data e))]
          (do (print-err (concat [(str "cannot-run: " (one-line why))] (when events? (event-lines)))) 2)
          (do (print-err [(str "cannot-run: " (one-line (ex-message e)))]) 2)))
      (catch Throwable e
        (print-err [(str "cannot-run: " (one-line (str (.getName (class e)) ": " (ex-message e))))])
        2))))

(defn -main [& argv]
  (System/exit (run (str (fs/canonicalize ".")) argv)))
