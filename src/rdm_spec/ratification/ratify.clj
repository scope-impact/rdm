(ns rdm-spec.ratification.ratify
  "Component Ratify of the ratification context
  (dhf/spec/ratification/ratify.feature): `rdm-spec ratify ROOM CYCLE`.

  Trusts only the cut: reads the cycle's cycle.edn first, exports its cut
  (files, no history) to dhf/cycles/CYCLE/build, replaces every configured
  implementation path there with the room's (never merged), and runs the
  cut's declared test command in it with RESULTS_DIR set to the cycle's
  results directory, its output in the cycle's test.log. Passes only when
  the cut's spec is stamped, the command exits 0, it wrote Allure results,
  every stamped scenario not tagged @wip is named by a result, and no
  result naming one, or naming nothing, failed. Records what it ratified in
  ratified.edn. It gates; it never scores."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.kernel.runner :as runner]))

;; ---------------------------------------------------------------------------
;; git

(defn- git
  [req opts & args]
  (let [r (try (apply p/shell {:dir (str (or (:dir opts) (:root req))) :out :string :err :string :continue true
                               :in "" :env (merge (or (:env req) (into {} (System/getenv))) (:env opts))}
                      "git" args)
               (catch java.io.IOException e
                 (command/cannot-run! (str "git cannot be run (" (ex-message e) "): rdm-spec ratify needs git on PATH"))))]
    {:exit (:exit r) :out (:out r) :err (str/trim (str (:err r)))}))

(defn- git! [req opts why & args]
  (let [r (apply git req opts args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str why (when-not (str/blank? (:err r)) (str ": " (:err r))))))
    (str/trim (:out r))))

(defn- export!
  "Writes the files of `commit` into `dir`: its files, and no history."
  [req commit dir]
  (let [idx (str (fs/create-temp-file {:prefix "rdm-spec-index-"}))]
    (try
      (fs/delete-if-exists idx)
      (fs/create-dirs dir)
      (git! req {:env {"GIT_INDEX_FILE" idx}} (str "the cut " commit " cannot be read") "read-tree" commit)
      (git! req {:env {"GIT_INDEX_FILE" idx}} "the cut cannot be exported"
            (str "--work-tree=" (fs/absolutize dir)) "checkout-index" "-a" "-f")
      (finally (fs/delete-if-exists idx)))))

(defn tree-hash
  "The git tree hash of the implementation paths `impl` of `room`: the tree
  holding exactly the room's files under them."
  [req room impl]
  (let [gd (str (fs/create-temp-dir {:prefix "rdm-spec-tree-"}))
        o {:dir room :env {"GIT_DIR" (str (fs/file gd ".git")) "GIT_WORK_TREE" (str (fs/absolutize room))
                           "GIT_INDEX_FILE" (str (fs/file gd "index"))}}
        present (filter #(fs/exists? (fs/file room %)) impl)]
    (try
      (git! req {:dir gd} "a scratch repository cannot be made" "init" "-q")
      (when (seq present)
        (apply git! req o "the room's implementation cannot be read" "add" "-A" "-f" "--" present))
      (git! req o "the room's tree cannot be written" "write-tree")
      (finally (fs/delete-tree gd)))))

;; ---------------------------------------------------------------------------
;; The checks

(defn- labels [r]
  (into {} (for [l (:labels r) :when (map? l)] [(str (:name l)) (str (:value l))])))

(defn read-results
  "[{:file :status :spec :name}] of every *-result.json under `dir`, and
  findings for those that cannot be read; files named relative to the
  project of `req`."
  [req dir]
  (let [files (sort (map str (fs/glob dir "**-result.json")))]
    (reduce (fn [acc f]
              (let [rel (command/rel req f)
                    r (try (json/parse-string (slurp f) true) (catch Exception _ ::unreadable))]
                (if (map? r)
                  (update acc :results conj {:file rel :status (str (:status r)) :name (:name r)
                                             :spec (get (labels r) "spec")})
                  (update acc :findings conj
                          (command/finding rel "result-unreadable" "the file is not an Allure result: not a JSON object")))))
            {:results [] :findings []}
            files)))

(defn result-findings
  "The findings of the results `results` against the scenarios `scenarios`
  ([{:id :file :line :wip?}]): every stamped scenario not tagged @wip is
  named by a result, and none that did not pass names one; a result naming
  no scenario that failed or broke is a finding too; one naming an id the
  spec does not hold is a warning."
  [results scenarios results-rel]
  (let [by-id (group-by :spec results)
        ids (set (map :id scenarios))
        required (remove :wip? scenarios)]
    (vec (concat
          (for [{:keys [id file line]} required
                :when (empty? (get by-id id))]
            (command/finding file line "scenario-unnamed"
                             (str id " is named by no result in " results-rel
                                  ": every scenario not tagged @wip must be tested")))
          (for [{:keys [id]} required
                r (get by-id id)
                :when (not= "passed" (:status r))]
            (command/finding (:file r) "result-not-passed"
                             (str id " has a result whose status is " (if (str/blank? (:status r)) "unknown" (:status r))
                                  (when (:name r) (str " (" (:name r) ")")))))
          (for [r (get by-id nil)
                :when (#{"failed" "broken"} (:status r))]
            (command/finding (:file r) "result-not-passed"
                             (str "the result " (pr-str (:name r)) " names no scenario and its status is " (:status r))))
          (for [[id rs] by-id
                :when (and id (not (contains? ids id)))]
            (command/warning (:file (first rs)) "result-unknown"
                             (str "the result names " id ", which no scenario of the cut carries")))))))

(defn spec-scenarios
  "{:scenarios [{:id :file :line :wip?}] :findings} of the spec in the
  checkout `dir`, by the configuration `cfg`: an unstamped scenario, or one
  the reader refuses, is a finding."
  [dir cfg]
  (let [{:keys [pickles refusals]} (runner/plan dir {:spec-dir (:spec-dir cfg) :id-prefix (:id-prefix cfg)
                                                     :model (:model cfg)})]
    {:scenarios (vec (vals (into (sorted-map)
                                 (for [pk pickles :when (:id pk)]
                                   [(:id pk) {:id (:id pk) :file (:file pk) :line (:line pk) :wip? (:wip? pk)}]))))
     :findings (vec (concat
                     (map #(assoc % :severity :error) refusals)
                     (distinct
                      (for [pk pickles :when (empty? (:ids pk))]
                        (command/finding (:file pk) (:line pk) "scenario-unstamped"
                                         (str "the scenario \"" (:name pk) "\" carries no id: a cut holding an unstamped"
                                              " scenario is not ratified (rdm-spec stamp gives it one)"))))))}))

;; ---------------------------------------------------------------------------
;; Ratify

(defn- record!
  "Writes ratified.edn and returns the stage's output."
  [req rel-of base findings result]
  (let [errors (remove command/warning? findings)
        r (if (seq errors)
            (assoc base :outcome :refused :reason (str/join "; " (map :message errors)))
            (assoc base :outcome :passed))
        m (reduce (fn [m k] (if (contains? r k) (assoc m k (get r k)) m))
                  (array-map) [:cut :room-tree :outcome :checkout :reason])]
    (spit (str (command/path req (rel-of "ratified.edn"))) (str (pr-str m) "\n"))
    {:result {:ratify (merge result (dissoc m :reason) {:ratified (rel-of "ratified.edn")})}
     :findings (vec findings)}))

(defn- copy-implementation!
  "Replaces every implementation path of `build` with the room's."
  [room build impl]
  (doseq [p impl
          :let [dst (fs/file build p) src (fs/file room p)]]
    (fs/delete-tree dst)
    (when (fs/exists? src)
      (if (fs/directory? src)
        (fs/copy-tree src dst {:replace-existing true})
        (do (fs/create-dirs (fs/parent dst))
            (fs/copy src dst {:replace-existing true}))))))

(defn- run-tests!
  "Runs `cmd` in `build` with RESULTS_DIR set, its output into `log`: the
  exit status."
  [req cmd build results log]
  (try
    (:exit @(p/process (vec cmd) {:dir (str build) :in ""
                                  :env (assoc (or (:env req) (into {} (System/getenv))) "RESULTS_DIR" (str results))
                                  :out (io/file (str log)) :err :out}))
    (catch java.io.IOException e
      (command/cannot-run! (str "the test command " (str/join " " cmd) " cannot be run: " (ex-message e))))))

(defn ratify
  "The ratify stage: `rdm-spec ratify ROOM CYCLE`."
  [req]
  (let [[room-arg cycle] (:args req)
        cycles-dir (str/replace (str (command/config-value req :cycles-dir)) #"/+$" "")
        rel-of (fn [f] (str cycles-dir "/" cycle "/" f))
        cycle-rel (rel-of "cycle.edn")
        cycle-file (command/path req cycle-rel)
        _ (when-not (fs/regular-file? cycle-file)
            (command/cannot-run! (str "no " cycle-rel ": ratify reads the cycle's cut from it before anything else")))
        cyc (try (edn/read-string (slurp (str cycle-file)))
                 (catch Exception e (command/cannot-run! (str cycle-rel " is not readable EDN: " (ex-message e)))))
        cut (when (map? cyc) (:cut cyc))
        _ (when-not (and (string? cut) (not (str/blank? cut)))
            (command/cannot-run! (str cycle-rel " names no :cut: ratify ratifies against a cut")))
        _ (when-not (zero? (:exit (git req {} "rev-parse" "--git-dir")))
            (command/cannot-run! (str (:root req) " is not a git repository: ratify reads the cut " cut " from it")))
        commit (let [r (git req {} "rev-parse" "--verify" "-q" (str cut "^{commit}"))]
                 (if (zero? (:exit r))
                   (str/trim (:out r))
                   (command/cannot-run! (str "the cut " cut " that " cycle-rel " names is not in " (:root req)))))
        room (command/path req room-arg)
        _ (when-not (fs/directory? room)
            (command/cannot-run! (str "ROOM " room-arg " is not a directory: ratify takes the room's implementation from it")))
        build-rel (rel-of "build")
        build (command/path req build-rel)
        _ (fs/delete-tree build)
        _ (export! req commit build)
        cfg (let [f (fs/file build command/config-file)]
              (when (fs/regular-file? f)
                (try (edn/read-string (slurp (str f))) (catch Exception _ nil))))
        _ (when-not (map? cfg)
            (command/cannot-run! (str "the cut " cut " holds no readable " command/config-file)))
        impl (vec (:implementation cfg))
        cmd (:test-command cfg)
        _ (when (empty? impl)
            (command/cannot-run! (str "the configuration at " cut " names no :implementation: nothing to take from the room")))
        _ (when-not (and (sequential? cmd) (seq cmd) (every? string? cmd))
            (command/cannot-run! (str "the configuration at " cut " declares no :test-command")))
        room-tree (tree-hash req room impl)
        base {:cut cut :room-tree room-tree :checkout build-rel}
        result {:cycle cycle :commit commit}
        _ (copy-implementation! room build impl)
        {:keys [scenarios findings]} (spec-scenarios (str build) cfg)]
    (if (seq findings)
      (record! req rel-of base findings result)
      (let [results-rel (rel-of "results")
            results (fs/absolutize (command/path req results-rel))
            log-rel (rel-of "test.log")
            _ (fs/delete-tree results)
            _ (fs/create-dirs results)
            exit (run-tests! req cmd build results (command/path req log-rel))
            result (assoc result :results results-rel :log log-rel :exit exit)]
        (if-not (zero? exit)
          (record! req rel-of base
                   [(command/finding log-rel "test-command-failed"
                                     (str "the test command " (str/join " " cmd) " failed with exit status " exit
                                          "; its output is in " log-rel))]
                   result)
          (let [{rs :results unreadable :findings} (read-results req results)]
            (record! req rel-of base
                     (if (and (empty? rs) (empty? unreadable))
                       [(command/finding results-rel "no-results"
                                         (str "the test command exited 0 and wrote no Allure result into " results-rel
                                              ": a green exit with no results is not a run"))]
                       (concat unreadable (result-findings rs scenarios results-rel)))
                     (assoc result :scenarios (count scenarios)))))))))
