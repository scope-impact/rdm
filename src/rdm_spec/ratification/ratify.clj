(ns rdm-spec.ratification.ratify
  "Component Ratify of the bounded context ratification
  (dhf/spec/ratification/ratify.feature): `rdm-spec ratify ROOM CYCLE`.

  Ratify trusts only the cut. It reads the cycle's cycle.edn first (its :cut),
  makes a fresh checkout of that cut at dhf/cycles/CYCLE/build, deletes the
  checkout's implementation (the cut's configured :implementation paths) and
  copies in the room's, so the spec and the configuration are always the
  cut's. It then runs the cut's declared :test-command in the checkout, with
  RESULTS_DIR set to the absolute path of dhf/cycles/CYCLE/results in the
  repository ratify was run in, and passes on exactly one condition:

    - the cut's spec holds no unstamped scenario (checked before any test runs),
    - the test command exits 0,
    - it wrote at least one Allure result,
    - every stamped scenario not tagged @wip is named by a result (label spec),
    - no result for such a scenario, and no result naming no scenario (a unit
      test), failed or broke; a scenario's result must have passed.

  Whatever the outcome, once the checkout is made it records
  dhf/cycles/CYCLE/ratified.edn: {:cut :room-tree :outcome :passed|:refused
  :checkout [:reason]}. :room-tree is the git tree hash of the room's
  implementation paths, abbreviated to 7 characters. The test command's output
  goes to dhf/cycles/CYCLE/test.log, never to the command's own streams.

  Exit 2 (cannot run): no cycle.edn, a cycle.edn naming no cut, a cut the
  repository does not hold, a room that is not a directory, a repository that
  is not a git repository, or a test command that cannot be started.

  Ratification only gates; it is never the score. It publishes no event: the
  steps that run later (the checklist, the scorer) meet it through the files
  it writes."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]
            [rdm-spec.kernel.runner :as runner]))

;; ---------------------------------------------------------------------------
;; git

(defn- git
  "Runs git with `args`: {:exit :out :err}. `opts` may give :dir and :extra-env."
  [opts & args]
  (let [r (apply p/shell (merge {:out :string :err :string :continue true :in ""} opts)
                 "git" "-c" "advice.detachedHead=false" args)]
    {:exit (:exit r) :out (str/trim (:out r)) :err (str/trim (:err r))}))

(defn- git!
  "Runs git; cannot run (exit 2) when it fails, saying what `doing` was."
  [doing opts & args]
  (let [r (apply git opts args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "could not " doing ": git " (str/join " " args) ": " (:err r))))
    (:out r)))

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn tree-hash
  "The git tree hash of the paths `paths` (relative) as they are under `dir`,
  computed in a throwaway repository: what git would record for them. The
  empty tree when none of them exists."
  [dir paths]
  (let [present (vec (filter #(fs/exists? (fs/file dir %)) paths))]
    (if (empty? present)
      empty-tree
      (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-tree-"})]
        (try
          (let [git-dir (str (fs/file tmp "git"))
                env {"GIT_INDEX_FILE" (str (fs/file tmp "index"))}]
            (git! "make a repository to hash the room in" {} "init" "-q" "--bare" git-dir)
            (apply git! "hash the room's implementation" {:extra-env env}
                   "--git-dir" git-dir "--work-tree" (str dir) "add" "-A" "-f" "--" present)
            (git! "hash the room's implementation" {:extra-env env} "--git-dir" git-dir "write-tree"))
          (finally (fs/delete-tree tmp)))))))

(defn short-hash [h] (subs h 0 (min 7 (count h))))

;; ---------------------------------------------------------------------------
;; The cycle

(defn cycle-dir
  "The directory of cycle `cycle` under the configured :cycles-dir; a name
  that is not a plain directory name cannot run."
  [req cycle]
  (when-not (and (re-matches #"[A-Za-z0-9][A-Za-z0-9._-]*" cycle) (not (str/includes? cycle "..")))
    (command/cannot-run! (str "the cycle " (pr-str cycle) " is not a cycle name (a directory name such as 03)")))
  (fs/file (command/path req (command/config-value req :cycles-dir)) cycle))

(defn read-cycle
  "The cycle's cycle.edn; cannot run when it is absent, unreadable or names no
  cut."
  [req dir]
  (let [f (fs/file dir "cycle.edn")
        name (command/rel req f)]
    (when-not (fs/exists? f)
      (command/cannot-run! (str "no " name ": the cycle is not declared (write its cycle.edn, naming its cut)")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str name " is not EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str name " is not a map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str name " names no :cut")))
      c)))

;; ---------------------------------------------------------------------------
;; The checkout

(defn- delete-path! [f]
  (cond (and (fs/directory? f) (not (fs/sym-link? f))) (fs/delete-tree f)
        (or (fs/exists? f) (fs/sym-link? f)) (fs/delete f)))

(defn checkout!
  "Makes `build` a fresh checkout of the cut `cut` of the repository `root`,
  deleting whatever was there before."
  [root cut build]
  (delete-path! build)
  (fs/create-dirs (fs/parent build))
  (git! (str "check out the cut " cut) {} "clone" "-q" "--no-hardlinks" "--branch" cut (str root) (str build)))

(defn replace-implementation!
  "Deletes every path of `paths` from `build` and copies the room's in, so
  each holds exactly what the room holds (nothing where the room has nothing)."
  [room build paths]
  (doseq [p paths
          :let [from (fs/file room p) to (fs/file build p)]]
    (delete-path! to)
    (cond (fs/directory? from) (do (fs/create-dirs (fs/parent to))
                                   (fs/copy-tree from to {:copy-attributes true}))
          (fs/exists? from) (do (fs/create-dirs (fs/parent to))
                                (fs/copy from to {:copy-attributes true})))))

(defn read-checkout-config
  "The cut's configuration, from the checkout; cannot run without one."
  [build]
  (or (try (command/read-config (str build)) (catch Exception _ nil))
      (command/cannot-run! (str "the cut holds no readable " command/config-file))))

;; ---------------------------------------------------------------------------
;; The spec at the cut

(defn scenarios
  "Every scenario of the feature files under `spec-dir` (absolute), each named
  by `name-of` (a file to how findings name it): {:scenarios [{:id :wip? :file
  :line :name}] :refusals [..]}. :id is nil for an unstamped scenario."
  [spec-dir id-prefix name-of]
  (let [id-re (re-pattern (str "@" (java.util.regex.Pattern/quote id-prefix) "-\\d{4,}"))
        parsed (for [f (runner/feature-files spec-dir)]
                 (gherkin/parse-feature (name-of f) (slurp f)))]
    {:scenarios (vec (for [{:keys [file scenarios]} parsed
                           sc scenarios
                           :let [ids (distinct (keep #(when (re-matches id-re %) (subs % 1)) (:tags sc)))]]
                       {:id (when (= 1 (count ids)) (first ids))
                        :ids (vec ids)
                        :wip? (boolean (some #{"@wip"} (:all-tags sc)))
                        :file file :line (:line sc) :name (:name sc)}))
     :refusals (vec (mapcat :refusals parsed))}))

(defn spec-findings
  "Findings that stop ratification before any test runs: a scenario with no
  id (or several), and Gherkin the reader refused."
  [{:keys [scenarios refusals]}]
  (vec (concat
        (for [{:keys [file line name ids]} scenarios
              :when (not= 1 (count ids))]
          (if (empty? ids)
            (command/finding file line "unstamped-scenario"
                             (str "the scenario \"" name "\" at the cut has no id: a cut holding an unstamped scenario is not ratified"))
            (command/finding file line "scenario-ids"
                             (str "the scenario \"" name "\" at the cut has " (count ids) " ids: " (str/join ", " ids)))))
        (for [{:keys [file line rule message]} refusals]
          (command/finding file line (or rule "gherkin") (str "the cut's spec cannot be read: " message))))))

;; ---------------------------------------------------------------------------
;; The results

(defn read-results
  "Every Allure result file in `dir`: [{:file f :result map}] or, for a file
  that is not a JSON object, [{:file f :error why}]."
  [dir]
  (if (fs/directory? dir)
    (vec (for [f (sort (map str (fs/glob dir "*-result.json")))]
           (try (let [r (json/parse-string (slurp f) true)]
                  (if (map? r) {:file f :result r} {:file f :error "it is not a JSON object"}))
                (catch Exception e {:file f :error (str "it is not JSON: " (ex-message e))}))))
    []))

(defn spec-labels [result]
  (vec (keep #(when (= "spec" (:name %)) (:value %)) (:labels result))))

(defn result-findings
  "What refuses ratification in a run: `exit` the test command's status,
  `command-line` how it is named, `results` as read-results returns them (each
  :file already named for findings), `scs` the cut's scenarios,
  `results-name` how the results directory is named."
  [{:keys [exit command-line results scs results-name]}]
  (let [planned (set (keep #(when (:wip? %) (:id %)) scs))
        readable (filter :result results)
        named (set (mapcat (comp spec-labels :result) readable))]
    (vec (concat
          (when-not (zero? exit)
            [(command/finding "test-command-failed"
                              (str "the test command " command-line " failed with exit status " exit))])
          (when (empty? results)
            [(command/finding "no-results"
                              (str "the test command " command-line " exited " exit
                                   " but wrote no Allure result into " results-name
                                   ": a green exit with no results is not a run"))])
          (for [{:keys [file error]} results :when error]
            (command/finding file nil "unreadable-result" (str "the result cannot be read: " error)))
          (for [{:keys [file result]} readable
                :let [status (or (:status result) "unknown")
                      ids (remove planned (spec-labels result))
                      message (get-in result [:statusDetails :message])]
                :when (and (not= "passed" status)
                           (or (seq ids)
                               ;; a result naming no scenario (a unit test) refuses when it failed or broke
                               (and (empty? (spec-labels result)) (#{"failed" "broken"} status))))]
            (command/finding file nil "result-not-passed"
                             (str (if (seq ids) (str/join ", " ids) (str "the test \"" (:name result) "\""))
                                  " is " status (when-not (str/blank? message) (str ": " (first (str/split-lines message)))))))
          (when (seq results)
            (for [{:keys [id wip? file line name]} scs
                  :when (and id (not wip?) (not (named id)))]
              (command/finding file line "scenario-not-named"
                               (str "no result in " results-name " is labelled spec=" id
                                    " (\"" name "\"): every scenario not tagged @wip must be tested"))))))))

;; ---------------------------------------------------------------------------
;; ratified.edn

(defn ratified-text
  "The text of ratified.edn: :cut, :room-tree, :outcome, :checkout, then
  :reason for a refusal, one key per line."
  [{:keys [cut room-tree outcome checkout reason]}]
  (str "{:cut " (pr-str cut) "\n"
       " :room-tree " (pr-str room-tree) "\n"
       " :outcome " (pr-str outcome) "\n"
       " :checkout " (pr-str checkout)
       (when reason (str "\n :reason " (pr-str reason)))
       "}\n"))

(defn reason [findings]
  (str/join "; " (map command/format-finding findings)))

;; ---------------------------------------------------------------------------
;; Running the test command

(defn run-tests!
  "Runs `cmd` (a vector) in `build` with RESULTS_DIR set to `results` (an
  absolute path), its output into `log`. Returns its exit status; cannot run
  when it cannot be started."
  [cmd build results log]
  (let [r (try (apply p/shell {:dir (str build) :out :string :err :string :continue true :in ""
                               :extra-env {"RESULTS_DIR" (str results)}}
                      cmd)
               (catch Exception e
                 (command/cannot-run! (str "the test command " (str/join " " cmd) " cannot be started: " (ex-message e)))))]
    (spit (str log) (str "$ " (str/join " " cmd) "\n" (:out r)
                         (when-not (str/blank? (:err r)) (str "\n--- error stream ---\n" (:err r)))
                         "\nexit status " (:exit r) "\n"))
    (:exit r)))

;; ---------------------------------------------------------------------------
;; The task

(defn ratify
  "The ratify task: `rdm-spec ratify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        root (str (fs/absolutize (:root req)))
        cdir (cycle-dir req cycle)
        ;; the cycle first: it names the cut
        {:keys [cut]} (read-cycle req cdir)
        room (fs/absolutize (fs/path (if (fs/absolute? room-arg) room-arg (fs/file root room-arg))))
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "the room " room-arg " is not a directory")))
        _ (when-not (zero? (:exit (git {:dir root} "rev-parse" "--git-dir")))
            (command/cannot-run! (str root " is not a git repository: ratify checks out the cut from it")))
        _ (when-not (zero? (:exit (git {:dir root} "rev-parse" "--verify" "--quiet" (str "refs/tags/" cut "^{commit}"))))
            (command/cannot-run! (str "the cut " cut " named by " (command/rel req (fs/file cdir "cycle.edn"))
                                      " is not a tag of this repository")))
        build (fs/file cdir "build")
        results (fs/file cdir "results")
        build-name (command/rel req build)
        results-name (command/rel req results)
        _ (checkout! root cut build)
        config (read-checkout-config build)
        impl (or (:implementation config)
                 (command/cannot-run! (str "the cut's " command/config-file " sets no :implementation")))
        cmd (or (:test-command config)
                (command/cannot-run! (str "the cut's " command/config-file " sets no :test-command")))
        _ (when-not (and (sequential? cmd) (seq cmd) (every? string? cmd))
            (command/cannot-run! (str "the cut's :test-command is not a vector of strings: " (pr-str cmd))))
        room-tree (short-hash (tree-hash room impl))
        _ (replace-implementation! room build impl)
        name-of #(command/rel req %)
        spec (scenarios (fs/file build (or (:spec-dir config) "dhf/spec")) (or (:id-prefix config) "spec") name-of)
        stop (spec-findings spec)
        _ (delete-path! results)
        _ (fs/create-dirs results)
        exit (when (empty? stop)
               (run-tests! cmd build (str (fs/canonicalize results)) (fs/file cdir "test.log")))
        findings (if (seq stop)
                   stop
                   (result-findings {:exit exit :command-line (str/join " " cmd)
                                     :results (mapv #(update % :file name-of) (read-results results))
                                     :scs (:scenarios spec) :results-name results-name}))
        outcome (if (seq findings) :refused :passed)
        record {:cut cut :room-tree room-tree :outcome outcome :checkout build-name
                :reason (when (seq findings) (reason findings))}]
    (spit (str (fs/file cdir "ratified.edn")) (ratified-text record))
    {:result {:cycle cycle :cut cut :room (str room) :room-tree room-tree :outcome outcome
              :checkout build-name :results results-name :test-exit exit
              :scenarios (count (remove :wip? (:scenarios spec)))
              :ratified (name-of (fs/file cdir "ratified.edn"))}
     :findings findings}))
