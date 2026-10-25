(ns rdm-spec.ratification.ratify
  "Ratify (dhf/spec/ratification/ratify.feature): `rdm-spec ratify ROOM
  CYCLE` judges the room's implementation in a fresh checkout of the cycle's
  cut, by a check the builder does not control. It gates; it never scores.

  In order:
    1. The cycle first: dhf/cycles/CYCLE/cycle.edn (under the configured
       :cycles-dir) must exist, be an EDN map and name its :cut. Anything
       else cannot run (exit 2), naming the file; nothing is written.
    2. ROOM must be a directory, the project a git repository holding the cut
       as a tag. Otherwise cannot run.
    3. The checkout: dhf/cycles/CYCLE/build is emptied and filled with the
       cut's files (never the working tree's, never the room's), so the spec
       and the configuration are always the cut's. Then every implementation
       path the cut's configuration names (:implementation) is deleted from
       it and replaced by the room's, never merged: a path the room lacks is
       absent. The room's implementation is identified by its git tree hash
       over those paths.
    4. The cut's spec is read (its :spec-dir, by the kernel's Gherkin
       reader): a scenario with no id, or a file the reader refuses, is a
       finding naming its file and line.
    5. The cut's :test-command runs in the checkout with RESULTS_DIR set to
       the absolute path of dhf/cycles/CYCLE/results in this repository,
       emptied first. A non-zero exit is a finding with the exit status.
    6. On exit 0 the results are read: none at all is a finding (a green exit
       with no results is not a run); so is an unreadable result file, a
       stamped scenario not tagged @wip that no result names, and every
       result naming one that did not pass (with its status).
    7. dhf/cycles/CYCLE/ratified.edn records {:cut :room-tree :outcome
       :checkout}, with :outcome :passed when there was no finding, else
       :refused and the :reason (the findings, one per line).

  Ratify reads only the cut, the room and the results: nothing of the
  instrument's oracle, which the command runs as a gate before this stage."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

(def config-file "dhf/harness/config.edn")

;; ---------------------------------------------------------------------------
;; git

(def git-env
  "git isolated from any user or system configuration."
  {"GIT_CONFIG_NOSYSTEM" "1" "GIT_CONFIG_GLOBAL" "/dev/null" "GIT_TERMINAL_PROMPT" "0"})

(defn git
  "Runs git in `dir`: {:exit :out :err}. `env` adds variables."
  ([dir args] (git dir args {}))
  ([dir args env]
   (try
     (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                             :extra-env (merge git-env env)}
                    "git" "-c" "core.hooksPath=/dev/null" args)]
       {:exit (:exit r) :out (:out r) :err (:err r)})
     (catch java.io.IOException e
       (command/cannot-run! (str "git cannot be run: " (ex-message e)))))))

(defn- git!
  [dir args & [env]]
  (let [r (git dir args (or env {}))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " args) " failed: " (str/trim (:err r)))))
    (:out r)))

(defn cut-commit
  "The commit the tag `cut` names in the repository at `root`; cannot run
  when the project is no git repository or holds no such tag."
  [root cut]
  (when-not (zero? (:exit (git root ["rev-parse" "--git-dir"])))
    (command/cannot-run! (str root " is not a git repository: ratify checks out the cut " cut " from it")))
  (let [r (git root ["rev-parse" "--verify" "-q" (str "refs/tags/" cut "^{commit}")])]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the cut " cut " is not a tag of this repository")))
    (str/trim (:out r))))

(defn- executables
  "The paths of the commit's files git records as executable."
  [root commit]
  (for [e (str/split (git! root ["ls-tree" "-r" "-z" "--full-tree" commit]) #"\u0000")
        :when (not (str/blank? e))
        :let [[meta path] (str/split e #"\t" 2)]
        :when (str/starts-with? meta "100755")]
    path))

(defn checkout!
  "Fills the empty directory `dest` with the files of `commit`, read from the
  repository at `root` (never its working tree)."
  [root commit dest]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-ratify-"})
        zip (str (fs/file tmp "cut.zip"))]
    (try
      (git! root ["archive" "--format=zip" "-o" zip commit])
      (fs/create-dirs dest)
      (fs/unzip zip dest {:replace-existing true})
      (doseq [path (executables root commit)
              :let [f (fs/file dest path)]
              :when (fs/regular-file? f)]
        (.setExecutable f true false))
      (finally (fs/delete-tree tmp)))))

(defn tree-hash
  "The git tree hash of the files under `dir` at `paths` (those that exist),
  as git would record them; the empty tree when there is none."
  [dir paths]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-tree-"})
        gd (str (fs/file tmp "index" ".git"))]
    (try
      (git! tmp ["init" "-q" (str (fs/file tmp "index"))])
      (let [present (filterv #(fs/exists? (fs/file dir %)) paths)
            env {"GIT_DIR" gd "GIT_WORK_TREE" (str (fs/absolutize dir))}]
        (when (seq present)
          (git! dir (concat ["add" "-f" "-A" "--"] present) env))
        (str/trim (git! dir ["write-tree"] env)))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; The room's implementation in the checkout

(defn- copy-path! [from to]
  (cond
    (fs/directory? from) (fs/copy-tree from to {:replace-existing true})
    (fs/exists? from) (do (fs/create-dirs (fs/parent to))
                          (fs/copy from to {:replace-existing true}))))

(defn replace-implementation!
  "Deletes every path of `paths` from `build` and copies the room's in its
  place: what the checkout holds there is exactly what the room holds."
  [build room paths]
  (doseq [p paths
          :let [target (fs/file build p)]]
    (when (fs/exists? target {:nofollow-links true})
      (if (fs/directory? target {:nofollow-links true})
        (fs/delete-tree target)
        (fs/delete target)))
    (copy-path! (fs/file room p) target)))

;; ---------------------------------------------------------------------------
;; The cut's spec

(defn scenario-id
  "The id tag of a parsed scenario (`spec-0012`), or nil when it has none."
  [id-prefix sc]
  (let [re (re-pattern (str "@(" (java.util.regex.Pattern/quote id-prefix) "-\\d{4,})"))]
    (some #(second (re-matches re %)) (:tags sc))))

(defn read-spec
  "The scenarios of the feature files under `spec-dir` in `base`, named by
  their path relative to `base`: {:scenarios [{:id :wip? :file :line :name}]
  :findings [...]}, a finding for each refusal and each scenario with no id."
  [base spec-dir id-prefix]
  (let [dir (fs/file base spec-dir)
        files (if (fs/directory? dir) (sort (map str (fs/glob dir "**.feature"))) [])]
    (reduce
     (fn [acc f]
       (let [rel (command/rel base f)
             {:keys [scenarios refusals]} (gherkin/parse-feature rel (slurp f))]
         (-> acc
             (update :findings into (for [r refusals]
                                      (command/finding (:file r) (:line r) (:rule r) (:message r))))
             (update :findings into (for [sc scenarios :when (nil? (scenario-id id-prefix sc))]
                                      (command/finding rel (:line sc) "ratify-unstamped-scenario"
                                                       (str "the scenario \"" (:name sc) "\" at the cut has no id:"
                                                            " a cut holding an unstamped scenario is not ratified"))))
             (update :scenarios into (for [sc scenarios
                                           :let [id (scenario-id id-prefix sc)]
                                           :when id]
                                       {:id id :file rel :line (:line sc) :name (:name sc)
                                        :wip? (boolean (some #{"@wip"} (:all-tags sc)))})))))
     {:scenarios [] :findings []}
     files)))

;; ---------------------------------------------------------------------------
;; Results

(defn read-results
  "Every Allure result in `dir`: {:results [{:file :spec [ids] :status}]
  :unreadable [file]}."
  [dir]
  (let [files (if (fs/directory? dir) (sort (map str (fs/glob dir "*-result.json"))) [])]
    (reduce
     (fn [acc f]
       (let [r (try (json/parse-string (slurp f) true) (catch Exception _ ::unreadable))]
         (if (map? r)
           (update acc :results conj
                   {:file f
                    :spec (vec (keep #(when (= "spec" (:name %)) (:value %))
                                     (when (sequential? (:labels r)) (filter map? (:labels r)))))
                    :status (str (or (:status r) "unknown"))})
           (update acc :unreadable conj f))))
     {:results [] :unreadable []}
     files)))

(defn judge
  "The findings of a run whose test command exited 0: `scenarios` the cut's
  stamped scenarios, `results` the run's (read-results). `results-name` is how
  the results directory is named."
  [scenarios {:keys [results unreadable]} results-name]
  (if (and (empty? results) (empty? unreadable))
    [(command/finding results-name nil "ratify-no-results"
                      (str "the test command exited 0 but wrote no Allure result into " results-name
                           ": a green exit with no results is not a run"))]
    (let [by-id (group-by identity (mapcat :spec results))
          due (remove :wip? scenarios)]
      (vec
       (concat
        (for [f unreadable]
          (command/finding results-name nil "ratify-unreadable-result"
                           (str (fs/file-name f) " is not a readable Allure result")))
        (for [{:keys [id file line]} due :when (not (contains? by-id id))]
          (command/finding file line "ratify-scenario-not-named"
                           (str id " is stamped and not tagged @wip, and no result in " results-name " names it")))
        (for [{:keys [id file line]} due
              r results
              :when (and (some #{id} (:spec r)) (not= "passed" (:status r)))]
          (command/finding file line "ratify-result-not-passed"
                           (str id " has a result that is " (:status r) " (" (fs/file-name (:file r)) ")"))))))))

;; ---------------------------------------------------------------------------
;; The test command

(defn run-tests
  "Runs `cmd` in `dir` with RESULTS_DIR set to `results`: its exit status.
  Its output is not the task's and is not printed."
  [cmd dir results]
  (when-not (and (sequential? cmd) (seq cmd) (every? string? cmd))
    (command/cannot-run! (str "the cut's " config-file " declares no :test-command as a list of strings")))
  (try
    (:exit @(apply p/process {:dir (str dir) :out :string :err :string :in ""
                        :extra-env {"RESULTS_DIR" (str results)}}
                       cmd))
    (catch java.io.IOException e
      (command/cannot-run! (str "the test command " (str/join " " cmd) " cannot be run: " (ex-message e))))))

;; ---------------------------------------------------------------------------
;; The cycle

(defn read-cycle
  "The cycle's cycle.edn; cannot run when it is missing, unreadable, not a
  map or names no cut."
  [root rel]
  (let [f (command/path root rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str rel " does not exist: a cycle is declared in its cycle.edn before it is ratified")))
    (let [c (try (edn/read-string (slurp f))
                 (catch Exception e (command/cannot-run! (str rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str rel " is not a map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str rel " names no :cut")))
      c)))

(defn- read-cut-config [build cut]
  (let [f (fs/file build config-file)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "the cut " cut " holds no " config-file)))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str "the cut's " config-file " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str "the cut's " config-file " is not a map")))
      c)))

(defn ratify
  "The stage of `rdm-spec ratify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        root (:root req)
        cycles-dir (or (get-in req [:config :cycles-dir]) "dhf/cycles")
        cycle-dir (str cycles-dir "/" cycle)
        cycle-rel (str cycle-dir "/cycle.edn")
        {:keys [cut]} (read-cycle root cycle-rel)
        room (let [r (fs/path room-arg)] (if (fs/absolute? r) r (fs/path root r)))
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "the room " room-arg " is not a directory")))
        commit (cut-commit root cut)
        build-rel (str cycle-dir "/build")
        build (command/path root build-rel)
        results-rel (str cycle-dir "/results")
        results (fs/absolutize (command/path root results-rel))
        ratified (command/path root (str cycle-dir "/ratified.edn"))]
    (fs/delete-if-exists ratified)
    (fs/delete-tree build)
    (checkout! root commit build)
    (let [config (read-cut-config build cut)
          paths (vec (:implementation config))
          _ (replace-implementation! build room paths)
          room-tree (tree-hash room paths)
          spec (read-spec build (or (:spec-dir config) "dhf/spec") (or (:id-prefix config) "spec"))
          _ (fs/delete-tree results)
          _ (fs/create-dirs results)
          exit (run-tests (:test-command config) build results)
          findings (vec (concat
                         (:findings spec)
                         (if (zero? exit)
                           (judge (:scenarios spec) (read-results results) results-rel)
                           [(command/finding nil nil "ratify-test-command-failed"
                                             (str "the test command " (str/join " " (:test-command config))
                                                  " failed with exit status " exit))])))
          outcome (if (seq findings) :refused :passed)
          record (cond-> {:cut cut :room-tree room-tree :outcome outcome :checkout build-rel}
                   (seq findings) (assoc :reason (str/join "\n" (map command/format-finding findings))))]
      (fs/create-dirs (fs/parent ratified))
      (spit (str ratified) (str (pr-str record) "\n"))
      {:result (assoc record :cycle cycle) :findings findings})))
