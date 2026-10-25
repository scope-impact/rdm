(ns rdm-spec.ratification.ratify
  "Ratify (dhf/spec/ratification/ratify.feature): `rdm-spec ratify ROOM
  CYCLE` ratifies the room's implementation by a check the builder does not
  control. It gates; it never scores.

  In order:
    1. dhf/cycles/CYCLE/cycle.edn is read before anything else; missing, not
       an EDN map, or naming no :cut, the task cannot run (exit 2), naming
       the file. The project must be a git repository holding the cut as a
       tag, and ROOM must be a directory (else exit 2).
    2. The checkout, dhf/cycles/CYCLE/build, is made afresh as an export of
       the cut: its files, written from the cut's tree through a throwaway
       index, and no history (no .git).
    3. Every path the cut's configuration lists as :implementation is
       deleted from the checkout and the room's copied in its place, so the
       spec and the configuration are always the cut's: nothing the builder
       edited outside the implementation is used.
    4. The cut's spec is read (the cut's :spec-dir and :id-prefix): a
       scenario with no id (or a feature the Gherkin reader refuses) refuses
       ratification without running anything, naming file and line as the
       cut names them; it stops ratification rather than shrinking it.
    5. The results directory dhf/cycles/CYCLE/results is emptied, and the
       cut's :test-command runs in the checkout with the caller's environment
       and RESULTS_DIR set to that directory's absolute path. Its standard
       output and error stream both go to dhf/cycles/CYCLE/test.log.
    6. It passes only when the command exits 0, at least one Allure result
       (*-result.json) was written, and every stamped scenario not tagged
       @wip is named (label spec) by at least one result, every one of which
       passed. Each failure is a finding (exit 1).
    7. dhf/cycles/CYCLE/ratified.edn records the outcome: on a pass exactly
       {:cut :room-tree :outcome :passed :checkout}; on a refusal
       {:cut :room-tree :outcome :refused :reason :checkout}. :room-tree is the
       full git tree hash of the room's implementation paths, every file
       under them as it is on disk (ignore rules do not apply).

  Ratify knows nothing of the oracle: the command runs it as a gate before."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

;; ---------------------------------------------------------------------------
;; Paths of a cycle

(defn cycle-file
  "\"dhf/cycles/<cycle>/<f>\", under the configured :cycles-dir."
  [cycles-dir cycle f]
  (str (or cycles-dir "dhf/cycles") "/" cycle "/" f))

;; ---------------------------------------------------------------------------
;; git

(defn- clean-env
  "`env` without git's repository-locating variables, plus `extra`."
  [env extra]
  (merge (into {} (remove (fn [[k _]] (#{"GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_OBJECT_DIRECTORY"
                                         "GIT_ALTERNATE_OBJECT_DIRECTORIES" "GIT_COMMON_DIR" "GIT_NAMESPACE"
                                         "GIT_CEILING_DIRECTORIES"} k))
                          env))
         extra))

(defn git
  "Runs git in `dir`: {:exit :out :err}. `extra` adds environment variables."
  [dir extra & args]
  (let [r (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                               :env (clean-env (into {} (System/getenv)) extra)}
                      "git" (map str args))
               (catch Exception e
                 (command/cannot-run! (str "git cannot be run: " (ex-message e)))))]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn git!
  "git's trimmed standard output; cannot run when it fails."
  [dir extra & args]
  (let [r (apply git dir extra args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " args) " failed in " dir ": " (str/trim (:err r)))))
    (str/trim (:out r))))

(defn resolve-cut
  "The commit the tag `cut` names in the repository at `root`; cannot run
  when the project is no repository or the tag is not there."
  [root cut cycle-rel]
  (let [r (git root {} "rev-parse" "--is-inside-work-tree")]
    (when-not (and (zero? (:exit r)) (= "true" (str/trim (:out r))))
      (command/cannot-run! (str root " is not a git repository: the cut " cut " is a tag in one"))))
  (let [r (git root {} "rev-parse" "--verify" "--quiet" "--end-of-options" (str "refs/tags/" cut "^{commit}"))]
    (if (zero? (:exit r))
      (str/trim (:out r))
      (command/cannot-run! (str cycle-rel " names the cut " cut ", which is no tag in this repository")))))

(defn- with-temp-index
  "Calls (f env) with GIT_INDEX_FILE set to a throwaway index."
  [f]
  (let [d (fs/create-temp-dir {:prefix "rdm-spec-ratify-"})]
    (try (f {"GIT_INDEX_FILE" (str (fs/file d "index"))})
         (finally (fs/delete-tree d)))))

(defn export!
  "Writes the files of `commit` (in the repository at `root`) into `dest`,
  which must not exist: an export, with no history."
  [root commit dest]
  (fs/create-dirs dest)
  (with-temp-index
    (fn [env]
      (git! root env "read-tree" (str commit "^{tree}"))
      (git! root env "checkout-index" "-a" "-f" (str "--prefix=" (fs/absolutize dest) "/")))))

(defn tree-hash
  "The full git tree hash of the files under `paths` of `dir`, as they are on
  disk, every one of them (ignore rules do not apply). The empty tree when
  none exists."
  [dir paths]
  (let [gd (fs/create-temp-dir {:prefix "rdm-spec-tree-"})]
    (try
      (let [env {"GIT_DIR" (str gd) "GIT_WORK_TREE" (str (fs/absolutize dir))}
            run! (fn [& args]
                   (let [r (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                                                :env (merge (clean-env (into {} (System/getenv)) {}) env)}
                                       "git" args)
                                (catch Exception e (command/cannot-run! (str "git cannot be run: " (ex-message e)))))]
                     (when-not (zero? (:exit r))
                       (command/cannot-run! (str "git " (str/join " " args) " failed in " dir ": " (str/trim (:err r)))))
                     (str/trim (:out r))))
            present (filter #(fs/exists? (fs/file dir %)) paths)]
        (run! "init" "-q")
        (when (seq present)
          (apply run! "add" "-A" "-f" "--" present))
        (run! "write-tree"))
      (finally (fs/delete-tree gd)))))

;; ---------------------------------------------------------------------------
;; The checkout

(defn- copy-path! [from to]
  (cond
    (fs/directory? from) (fs/copy-tree from to {:copy-attributes true})
    (fs/exists? from) (do (fs/create-dirs (fs/parent to))
                          (fs/copy from to {:copy-attributes true}))))

(defn replace-implementation!
  "Deletes every `paths` entry of the checkout `build` and copies the room's
  in its place."
  [build room paths]
  (doseq [rel paths
          :let [target (fs/file build rel)]]
    (when (or (fs/exists? target) (fs/sym-link? target))
      (if (and (fs/directory? target) (not (fs/sym-link? target)))
        (fs/delete-tree target)
        (fs/delete target)))
    (copy-path! (fs/file room rel) target)))

;; ---------------------------------------------------------------------------
;; The cut's spec

(defn id-pattern [prefix]
  (re-pattern (str "@" (java.util.regex.Pattern/quote (str prefix)) "-\\d{4,}")))

(defn read-spec
  "The scenarios of the feature files under `spec-dir` of `build`, named as
  the cut names them: {:scenarios [{:id :wip? :file :line}] :findings []}.
  A scenario with no id, and a construct the Gherkin reader refuses, is a
  finding."
  [build spec-dir prefix]
  (let [dir (fs/file build spec-dir)
        files (if (fs/directory? dir) (sort (map str (fs/glob dir "**.feature"))) [])
        id-re (id-pattern prefix)]
    (reduce
     (fn [acc f]
       (let [rel (str/replace (str (fs/relativize (fs/absolutize build) (fs/absolutize f))) "\\" "/")
             parsed (gherkin/parse-feature rel (slurp f))]
         (-> acc
             (update :findings into (for [r (:refusals parsed)]
                                      (command/finding (:file r) (:line r) (:rule r) (:message r))))
             (update :scenarios into
                     (for [sc (:scenarios parsed)
                           :let [ids (filter #(re-matches id-re %) (:tags sc))]]
                       {:id (some-> (first ids) (subs 1))
                        :name (:name sc)
                        :wip? (boolean (some #{"@wip"} (:all-tags sc)))
                        :file rel :line (:line sc)})))))
     {:scenarios [] :findings []}
     files)))

(defn unstamped-findings [scenarios]
  (for [sc scenarios :when (nil? (:id sc))]
    (command/finding (:file sc) (:line sc) "unstamped-scenario"
                     (str "the scenario \"" (:name sc) "\" has no id: a cut holding an unstamped scenario is not ratified"))))

;; ---------------------------------------------------------------------------
;; Results

(defn read-results
  "The Allure results in `dir`: {:results [{:file :status :specs}] :findings}.
  A result file that is not a JSON object is a finding."
  [dir results-rel]
  (let [files (if (fs/directory? dir) (sort (map str (fs/glob dir "*-result.json"))) [])]
    (reduce
     (fn [acc f]
       (let [rel (str results-rel "/" (fs/file-name f))
             m (try (json/parse-string (slurp f) true) (catch Exception _ ::unreadable))]
         (if (map? m)
           (update acc :results conj {:file rel :status (some-> (:status m) str)
                                      :specs (vec (keep #(when (= "spec" (:name %)) (some-> (:value %) str))
                                                        (when (sequential? (:labels m)) (:labels m))))})
           (update acc :findings conj (command/finding rel nil "unreadable-result"
                                                       "an Allure result that is no JSON object: it could hide a failure")))))
     {:results [] :findings []}
     files)))

(defn judge
  "Findings over the run: the test command's `exit`, its `results` and the
  cut's stamped `scenarios`; `command` and `log` name the test command and
  the file holding its output."
  [{:keys [exit results scenarios command log]}]
  (let [statuses (reduce (fn [m r] (reduce #(update %1 %2 (fnil conj []) r) m (:specs r))) {} results)]
    (concat
     (when-not (zero? exit)
       [(command/finding log nil "test-command-failed"
                         (str "the test command " command " failed with exit status " exit
                              "; its output is in " log))])
     (when (and (zero? exit) (empty? results))
       [(command/finding nil nil "no-results"
                         (str "the test command " command " exited 0 but wrote no Allure result:"
                              " a green exit with no results is not a run"))])
     (when (seq results)
       (for [sc (sort-by :id scenarios)
             :when (and (:id sc) (not (:wip? sc)))
             :let [rs (get statuses (:id sc))
                   bad (remove #(= "passed" (:status %)) rs)]
             f (cond
                 (and (empty? rs) (zero? exit))
                 [(command/finding (:file sc) (:line sc) "scenario-not-run"
                                   (str (:id sc) " is named by no result: every scenario not tagged @wip must be run"))]
                 (seq bad)
                 (for [r bad]
                   (command/finding (:file r) nil "scenario-not-passed"
                                    (str (:id sc) " has a result whose status is " (or (:status r) "missing")
                                         ", not passed")))
                 :else [])]
         f)))))

;; ---------------------------------------------------------------------------
;; The stage

(defn- read-cycle [root cycle-rel]
  (let [f (command/path root cycle-rel)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " cycle-rel ": a cycle is declared there, by hand, before it is ratified")))
    (let [c (try (edn/read-string (slurp f))
                 (catch Exception e (command/cannot-run! (str cycle-rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str cycle-rel " is not an EDN map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str cycle-rel " names no :cut")))
      c)))

(defn- run-tests!
  "Runs `cmd` in `dir` with `env`, both output streams into `log`: its exit."
  [cmd dir env log]
  (fs/create-dirs (fs/parent log))
  (let [pb (doto (ProcessBuilder. ^java.util.List (mapv str cmd))
             (.directory (fs/file dir))
             (.redirectErrorStream true)
             (.redirectOutput (fs/file log)))
        penv (.environment pb)]
    (.clear penv)
    (doseq [[k v] env] (.put penv (str k) (str v)))
    (let [proc (try (.start pb)
                    (catch Exception e
                      (command/cannot-run! (str "the test command " (str/join " " cmd) " cannot be run: "
                                                (ex-message e)))))]
      (.close (.getOutputStream proc))
      (.waitFor proc))))

(defn ratify
  "The ratify stage: {:result {...} :findings [...]}."
  [req]
  (let [[room cycle] (:args req)
        root (:root req)
        cycles-dir (or (get-in req [:config :cycles-dir]) "dhf/cycles")
        cycle-rel (cycle-file cycles-dir cycle "cycle.edn")
        {:keys [cut]} (read-cycle root cycle-rel)
        commit (resolve-cut root cut cycle-rel)
        room-dir (command/path root room)
        _ (when-not (fs/directory? room-dir)
            (command/cannot-run! (str "the room " room " is not a directory")))
        build-rel (cycle-file cycles-dir cycle "build")
        results-rel (cycle-file cycles-dir cycle "results")
        log-rel (cycle-file cycles-dir cycle "test.log")
        ratified-rel (cycle-file cycles-dir cycle "ratified.edn")
        build (command/path root build-rel)
        results (fs/absolutize (command/path root results-rel))
        _ (when (fs/exists? build) (fs/delete-tree build))
        _ (export! root commit build)
        config (command/read-config build)
        _ (when-not config
            (command/cannot-run! (str "the cut " cut " holds no " command/config-file)))
        impl (or (:implementation config)
                 (command/cannot-run! (str "the cut's " command/config-file " sets no :implementation")))
        test-cmd (:test-command config)
        _ (when-not (and (sequential? test-cmd) (seq test-cmd))
            (command/cannot-run! (str "the cut's " command/config-file " sets no :test-command")))
        room-tree (tree-hash room-dir impl)
        _ (replace-implementation! build room-dir impl)
        spec (read-spec build (or (:spec-dir config) "dhf/spec") (or (:id-prefix config) "spec"))
        stopped (concat (:findings spec) (unstamped-findings (:scenarios spec)))
        findings (if (seq stopped)
                   (vec stopped)
                   (do
                     (when (fs/exists? results) (fs/delete-tree results))
                     (fs/create-dirs results)
                     (let [exit (run-tests! test-cmd build
                                            (assoc (:env req) "RESULTS_DIR" (str results))
                                            (command/path root log-rel))
                           rs (read-results results results-rel)]
                       (vec (concat (:findings rs)
                                    (judge {:exit exit :results (:results rs) :scenarios (:scenarios spec)
                                            :command (str/join " " test-cmd) :log log-rel}))))))
        record (if (seq findings)
                 {:cut cut :room-tree room-tree :outcome :refused
                  :reason (str/join "; " (map command/format-finding findings))
                  :checkout build-rel}
                 {:cut cut :room-tree room-tree :outcome :passed :checkout build-rel})]
    (spit (command/path root ratified-rel) (str (pr-str record) "\n"))
    {:result {:ratified (assoc record :cycle cycle)}
     :findings findings}))
