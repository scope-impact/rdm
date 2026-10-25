(ns rdm-spec.ratification.ratify
  "Ratify (dhf/spec/ratification/ratify.feature): `rdm-spec ratify ROOM CYCLE`
  ratifies a build by a check the builder does not control.

  In order:
    1. reads dhf/cycles/CYCLE/cycle.edn before anything else: its :cut names
       the cut (a tag of the repository ratify runs in). No cycle.edn, no
       :cut, no such tag, or no directory ROOM: cannot run (exit 2).
    2. makes dhf/cycles/CYCLE/build a fresh checkout of the cut (a clone of
       the repository, HEAD detached at the cut; an earlier build is deleted
       first), then deletes every configured :implementation path from it
       and copies in what the room holds at those paths. The configuration
       read is the cut's, never the room's or the working tree's: whatever
       the builder edited outside the implementation is never used.
    3. the room's tree: the git tree object (full hash) of the room's
       implementation paths, as copied, and nothing else (the empty tree
       when the room holds none).
    4. refuses a cut whose spec holds an unstamped scenario (or a construct
       the Gherkin subset refuses), naming its file and line, without running
       the tests.
    5. empties dhf/cycles/CYCLE/results and runs the cut's :test-command in
       the checkout with RESULTS_DIR set to that directory's absolute path.
       The command's output goes to dhf/cycles/CYCLE/test-output.log, never
       to the task's own streams.
    6. passes only when the command exited 0, wrote at least one Allure
       result, every result is readable, and every stamped scenario not
       tagged @wip is named (label spec) by at least one result and every
       result naming it passed.

  Writes dhf/cycles/CYCLE/ratified.edn, the record the scorer reads:
    {:cut \"spec-3\" :room-tree \"<40 hex>\" :outcome :passed
     :checkout \"dhf/cycles/03/build\"}
  and, when refused, :outcome :refused and :reason, the findings in one
  string. A cannot-run after the checkout has begun leaves no ratified.edn."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.gherkin :as gherkin]))

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
  "cycle.edn of `dir`: a map naming a :cut, or cannot run naming the file."
  [req dir]
  (let [f (fs/file dir "cycle.edn")
        rel (command/rel req f)]
    (when-not (fs/regular-file? f)
      (command/cannot-run! (str "no " rel ": the cycle is declared there, by hand, before it is ratified")))
    (let [c (try (edn/read-string (slurp (str f)))
                 (catch Exception e (command/cannot-run! (str rel " is not EDN: " (ex-message e)))))]
      (when-not (map? c) (command/cannot-run! (str rel " is not a map")))
      (when-not (and (string? (:cut c)) (not (str/blank? (:cut c))))
        (command/cannot-run! (str rel " names no :cut")))
      c)))

;; ---------------------------------------------------------------------------
;; git

(def ^:private git-env
  {"GIT_TERMINAL_PROMPT" "0" "GIT_CONFIG_NOSYSTEM" "1"})

(defn- git
  [dir env & args]
  (let [r (apply p/shell {:dir (str dir) :in "" :out :string :err :string :continue true
                          :extra-env (merge git-env env)}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "core.autocrlf=false" args)]
    {:exit (:exit r) :out (str/trim (:out r)) :err (str/trim (:err r))}))

(defn- git!
  [dir env & args]
  (let [r (apply git dir env args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " args) " failed in " dir ": " (:err r))))
    (:out r)))

(defn cut-commit
  "The commit the tag `cut` names in the repository `root`, or cannot run."
  [root cut]
  (let [inside (git root nil "rev-parse" "--is-inside-work-tree")]
    (when-not (and (zero? (:exit inside)) (= "true" (:out inside)))
      (command/cannot-run! (str root " is not a git repository: ratify checks out the cut from it"))))
  (let [r (git root nil "rev-parse" "--verify" "--quiet" (str "refs/tags/" cut "^{commit}"))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the cut " cut " does not exist: no tag " cut " in " root)))
    (:out r)))

(defn checkout!
  "Makes `build` a fresh checkout of `commit` of the repository `root`."
  [root build commit]
  (when (fs/exists? build) (fs/delete-tree build))
  (fs/create-dirs (fs/parent build))
  (git! (fs/parent build) nil "clone" "-q" "--no-checkout" (str root) (str build))
  (git! build nil "checkout" "-q" "--detach" commit)
  build)

(def empty-tree "4b825dc642cb6eb9a060e54bf8d69288fbee4904")

(defn tree-hash
  "The full hash of the git tree holding exactly the files under `paths` in the
  working tree of the repository `dir` (ignored files included), whatever its
  index and HEAD hold."
  [dir paths]
  (let [index (str (fs/create-temp-file {:prefix "ratify-index-"}))
        env {"GIT_INDEX_FILE" index}]
    (try
      (fs/delete index)
      (let [present (filterv #(fs/exists? (fs/file dir %)) paths)]
        (when (seq present)
          (apply git! dir env "add" "-A" "-f" "--" present))
        (git! dir env "write-tree"))
      (finally (fs/delete-if-exists index)))))

;; ---------------------------------------------------------------------------
;; The room's implementation in place of the cut's

(defn replace-implementation!
  "Deletes every implementation path from `build` and copies in what `room`
  holds at it."
  [room build paths]
  (doseq [p paths
          :let [target (fs/file build p)
                src (fs/file room p)]]
    (when (or (fs/exists? target {:nofollow-links true}))
      (if (and (fs/directory? target) (not (fs/sym-link? target)))
        (fs/delete-tree target)
        (fs/delete target)))
    (when (fs/exists? src)
      (fs/create-dirs (fs/parent target))
      (if (fs/directory? src)
        (fs/copy-tree src target {:copy-attributes true :nofollow-links true})
        (fs/copy src target {:copy-attributes true :nofollow-links true})))))

;; ---------------------------------------------------------------------------
;; The spec at the cut

(defn read-config-in
  "The configuration of the checkout `build` (the cut's), or cannot run."
  [build]
  (let [c (command/read-config (str build))]
    (when-not c (command/cannot-run! (str "the cut holds no " command/config-file)))
    c))

(defn scenarios
  "Every scenario of the spec at `spec-dir` under `build`: {:scenarios
  [{:ids [..] :wip? :file :line :name}] :refusals [..]}, files named
  by `name-file`."
  [build {:keys [spec-dir id-prefix]} name-file]
  (let [dir (fs/file build (or spec-dir "dhf/spec"))
        id-re (re-pattern (str "@" (java.util.regex.Pattern/quote (or id-prefix "spec")) "-\\d{4,}"))
        files (if (fs/directory? dir) (sort (map str (fs/glob dir "**.feature"))) [])
        parsed (for [f files] (gherkin/parse-feature (name-file f) (slurp f)))]
    {:refusals (vec (mapcat :refusals parsed))
     :scenarios (vec (for [{:keys [file scenarios]} parsed
                           sc scenarios]
                       {:ids (vec (distinct (map #(subs % 1) (filter #(re-matches id-re %) (:tags sc)))))
                        :wip? (boolean (some #{"@wip"} (:all-tags sc)))
                        :file file :line (:line sc) :name (:name sc)}))}))

(defn spec-findings
  "Findings that stop ratification before any test runs: constructs the
  Gherkin subset refuses, and unstamped scenarios."
  [{:keys [refusals scenarios]}]
  (vec (concat
        (for [{:keys [file line rule message]} refusals]
          (command/finding file line (str "spec-" rule) (str "the cut's spec is not readable: " message)))
        (for [{:keys [ids file line name]} scenarios :when (empty? ids)]
          (command/finding file line "unstamped-scenario"
                           (str "the scenario \"" name "\" at the cut carries no id: a cut is ratified only when "
                                "every scenario is stamped"))))))

;; ---------------------------------------------------------------------------
;; Allure results

(defn read-results
  "Every *-result.json in `dir`: {:results [{:file :spec [ids] :status}]
  :unreadable [{:file :why}]}."
  [dir]
  (let [files (if (fs/directory? dir) (sort (map str (fs/glob dir "*-result.json"))) [])]
    (reduce (fn [acc f]
              (let [r (try (json/parse-string (slurp f) true) (catch Exception e {::error (ex-message e)}))]
                (if (or (::error r) (not (map? r)))
                  (update acc :unreadable conj {:file f :why (or (::error r) "not a JSON object")})
                  (update acc :results conj
                          {:file f
                           :status (:status r)
                           :spec (vec (keep #(when (and (map? %) (= "spec" (:name %))) (:value %))
                                            (if (sequential? (:labels r)) (:labels r) [])))}))))
            {:results [] :unreadable []}
            files)))

(defn scenario-findings
  "Findings for the stamped scenarios not tagged @wip: one no result names, or
  one a result names that did not pass."
  [scenarios results]
  (let [by-id (group-by identity (mapcat :spec results))
        statuses (reduce (fn [m r] (reduce #(update %1 %2 (fnil conj []) (:status r)) m (:spec r)))
                         {} results)]
    (vec (for [{:keys [ids wip? file line]} scenarios
               :when (not wip?)
               id ids
               :let [st (get statuses id)
                     bad (sort (distinct (map #(or % "no status") (remove #{"passed"} st))))]
               :when (or (nil? (by-id id)) (seq bad))]
           (if (nil? (by-id id))
             (command/finding file line "scenario-not-run"
                              (str id " is named by no result: every scenario not tagged @wip must be run"))
             (command/finding file line "scenario-not-passed"
                              (str id " has a result that did not pass: " (str/join ", " bad))))))))

(defn result-findings
  "Findings on the results themselves: none at all, or one unreadable."
  [{:keys [results unreadable]} name-file]
  (vec (concat
        (when (and (empty? results) (empty? unreadable))
          [(command/finding "no-results"
                            "the test command wrote no Allure result: a green exit with no results is not a run")])
        (for [{:keys [file why]} unreadable]
          (command/finding (name-file file) nil "unreadable-result" (str "not an Allure result: " why))))))

;; ---------------------------------------------------------------------------
;; The test command

(defn run-tests!
  "Runs `cmd` in `build` with RESULTS_DIR set to `results`, its output into
  `log`. Returns its exit status; cannot run when it cannot be started."
  [cmd build results log]
  (when-not (and (sequential? cmd) (seq cmd) (every? string? cmd))
    (command/cannot-run! (str "the cut's configuration declares no :test-command (a vector of strings): "
                              (pr-str cmd))))
  (let [out (java.io.File. (str log))]
    (try
      (:exit @(apply p/process {:dir (str build) :in "" :out out :err out
                                 :extra-env {"RESULTS_DIR" (str results)}}
                        cmd))
      (catch java.io.IOException e
        (command/cannot-run! (str "the test command " (str/join " " cmd) " cannot be started: " (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; ratified.edn

(defn ratified-map
  [{:keys [cut room-tree outcome checkout reason]}]
  (cond-> (array-map :cut cut :room-tree room-tree :outcome outcome :checkout checkout)
    reason (assoc :reason reason)))

(defn ratified-text
  "ratified.edn's text: one key per line, in the order cut, room-tree,
  outcome, checkout, reason."
  [m]
  (str "{" (str/join "\n " (for [[k v] m] (str (pr-str k) " " (pr-str v)))) "}\n"))

;; ---------------------------------------------------------------------------
;; The task

(defn ratify
  "The ratify task: `rdm-spec ratify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        root (str (fs/normalize (fs/absolutize (:root req))))
        dir (cycle-dir req cycle)
        c (read-cycle req dir)
        cut (:cut c)
        commit (cut-commit root cut)
        room (command/path req room-arg)
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "the room " room-arg " is not a directory")))
        dir (fs/file (fs/normalize (fs/absolutize dir)))
        build (fs/file dir "build")
        results (fs/file dir "results")
        ratified-file (fs/file dir "ratified.edn")
        build-rel (command/rel req build)
        name-in-project #(command/rel req %)]
    (fs/delete-if-exists ratified-file)
    (checkout! root build commit)
    (let [cfg (read-config-in build)
          impl (vec (:implementation cfg))
          _ (when (empty? impl)
              (command/cannot-run! "the cut's configuration declares no :implementation paths"))
          _ (replace-implementation! room build impl)
          room-tree (tree-hash build impl)
          spec (scenarios build cfg name-in-project)
          stop (spec-findings spec)
          findings
          (if (seq stop)
            stop
            (do (when (fs/exists? results) (fs/delete-tree results))
                (fs/create-dirs results)
                (let [cmd (:test-command cfg)
                      exit (run-tests! cmd build results (fs/file dir "test-output.log"))
                      read (read-results results)
                      rf (result-findings read name-in-project)]
                  (vec (concat
                        (when-not (zero? exit)
                          [(command/finding "test-command-failed"
                                            (str "the test command " (str/join " " cmd) " failed: it exited with status "
                                                 exit " (its output: " (name-in-project (fs/file dir "test-output.log")) ")"))])
                        rf
                        (when (seq (:results read))
                          (scenario-findings (:scenarios spec) (:results read))))))))
          outcome (if (seq findings) :refused :passed)
          m (ratified-map {:cut cut :room-tree room-tree :outcome outcome :checkout build-rel
                           :reason (when (seq findings)
                                     (str/join "; " (map command/format-finding findings)))})]
      (spit (str ratified-file) (ratified-text m))
      {:result (array-map :cycle cycle :cut cut :room-tree room-tree :outcome outcome
                          :checkout build-rel :written (name-in-project ratified-file))
       :findings findings})))
