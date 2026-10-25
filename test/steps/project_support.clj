(ns steps.project-support
  "Helpers for the project context's step definitions (init, adopt, hooks).
  No step is defined here, so loading this file twice defines nothing twice."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.gherkin :as gherkin]
            [steps.support :as s]))

(def check-stages
  "The namespaces `rdm-spec check` runs (as steps.kernel-command names them)."
  ['rdm-spec.knowledge.sources 'rdm-spec.spec.check 'rdm-spec.spec.parser
   'rdm-spec.architecture.lint 'rdm-spec.architecture.views])

(defn skeleton-table
  "The skeleton as the spec lists it: [[path holds] ...] from the table of
  init.feature's @spec-0086, the oracle for what init writes."
  []
  (let [f (str (fs/file s/room-root "dhf/spec/project/init.feature"))
        parsed (gherkin/parse-feature "init.feature" (slurp f))
        sc (first (filter #(some #{"@spec-0086"} (:tags %)) (:scenarios parsed)))
        table (some :data-table (:steps sc))]
    (vec (rest table))))

(defn skeleton-paths [] (mapv first (skeleton-table)))

(defn git
  "Runs git in `dir`: {:exit :out :err}."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)]
    {:exit (:exit r) :out (str/trim (:out r)) :err (str/trim (:err r))}))

(defn git-repo!
  "A temporary git repository holding `files` ({rel content})."
  [files]
  (let [dir (s/tmp-dir)]
    (s/check! (zero? (:exit (git dir "init" "--quiet"))) "git init failed")
    (doseq [[rel content] files] (s/write! dir rel content))
    dir))

(defn repo-root [dir]
  (let [r (git dir "rev-parse" "--show-toplevel")]
    (when (zero? (:exit r)) (str (fs/canonicalize (:out r))))))

(defn snapshot
  "Every file and link under `dir` (the .git directory included when
  `git?`): {rel {:link target} | {:content s :exec? b}}."
  ([dir] (snapshot dir true))
  ([dir git?]
   (into (sorted-map)
         (for [f (fs/glob dir "**" {:hidden true :follow-links false})
               :let [rel (str (fs/relativize dir f))]
               :when (or git? (not (or (= rel ".git") (str/starts-with? rel ".git/"))))
               :when (or (fs/sym-link? f) (fs/regular-file? f {:nofollow-links true}))]
           [rel (if (fs/sym-link? f)
                  {:link (str (fs/read-link f))}
                  {:content (slurp (str f)) :exec? (fs/executable? f)})]))))

(defn diff
  "The paths whose entries differ between two snapshots."
  [a b]
  (vec (sort (filter #(not= (get a %) (get b %)) (distinct (concat (keys a) (keys b)))))))

(defn result
  "The EDN result a run printed on stdout."
  [run]
  (try (edn/read-string (:out run))
       (catch Exception _ (s/fail! (str "stdout is not EDN\n" (s/show run))))))

(defn run-in
  "Runs rdm-spec in `dir`, keeping the run and the directory in the context."
  [ctx dir args]
  (assoc ctx :dir (str dir) :run (apply s/rdm-spec! dir args)))

(defn read-edn [f] (edn/read-string (slurp (str f))))
