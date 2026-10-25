(ns steps.regeneration-scopes
  "Step definitions for dhf/spec/regeneration/scopes.feature.

  A scopes fixture is a throwaway repository holding a copy of the room's
  allow-listed paths, committed and tagged with the first cut; the files the
  Given names are then changed, committed and tagged with the second cut. A
  file named with a leading dhf/ is a path of the project; any other is a
  path under the configured spec directory. The context keeps :dir (the
  repository, where the shared When runs the command) and :changed (the
  project paths changed between the two cuts).

  The git helpers are copied in (dhf/spec/README.md: a step file's helpers
  are its own), so this file needs only the kernel's shared steps."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.support :as s]))

;; ---------------------------------------------------------------------------
;; git, with no hooks, no signing and a fixed identity

(def ^:private git-env
  {"GIT_AUTHOR_NAME" "Fixture" "GIT_AUTHOR_EMAIL" "fixture@example.invalid"
   "GIT_COMMITTER_NAME" "Fixture" "GIT_COMMITTER_EMAIL" "fixture@example.invalid"
   "GIT_CONFIG_NOSYSTEM" "1"})

(defn- git!
  "Runs git in `dir` and returns its trimmed output; fails the step on an error."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env git-env}
                 "git" "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "-c" "tag.gpgsign=false"
                 "-c" "init.defaultBranch=main" args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (str/trim (:out r))))

(defn- commit!
  "Commits everything in `dir` (made a repository when it is not one yet) and
  tags the commit `tag`."
  [dir msg tag]
  (when-not (fs/exists? (fs/file dir ".git")) (git! dir "init" "-q"))
  (git! dir "add" "-A" "-f" ".")
  (git! dir "commit" "-q" "--allow-empty" "-m" msg)
  (git! dir "tag" tag))

;; ---------------------------------------------------------------------------
;; The fixture

(defn- spec-dir [] (or (:spec-dir (s/room-config)) "dhf/spec"))

(defn- project-path
  "The project path of a file the Given names: dhf/... as it is, anything
  else under the spec directory."
  [named]
  (if (str/starts-with? named "dhf/") named (str (spec-dir) "/" named)))

(defn- named-files
  "The files of a list like \"a, b and c\"."
  [text]
  (vec (remove str/blank? (map str/trim (str/split text #",\s*|\s+and\s+")))))

(defn- change!
  "Changes the file `rel` of `dir` by appending a comment in its own format
  (a new file when there is none)."
  [dir rel]
  (let [f (fs/file dir rel)
        before (if (fs/exists? f) (slurp (str f)) "")
        line (cond (str/ends-with? rel ".edn") ";; changed between the cuts\n"
                   (str/ends-with? rel ".feature") "# changed between the cuts\n"
                   :else "\nChanged between the cuts.\n")]
    (s/write! dir rel (str before (when-not (or (str/blank? before) (str/ends-with? before "\n")) "\n") line))))

(defn- two-cuts!
  "ctx with a repository whose cut `from` holds a copy of the room and whose
  cut `to` changes exactly `changed` (project paths)."
  [ctx from to changed]
  (s/needs-built! 'rdm-spec.regeneration.scopes)
  (let [dir (s/copy-of-room!)]
    ;; a file the room does not hold (a component's design prose, say) is
    ;; written before the first cut, so the second cut edits it
    (doseq [rel changed :when (not (fs/exists? (fs/file dir rel)))]
      (s/write! dir rel (str "# " (fs/file-name rel) "\n")))
    (commit! dir (str "the spec at " from) from)
    (doseq [rel changed] (change! dir rel))
    (commit! dir (str "the spec at " to) to)
    (let [diff (set (remove str/blank? (str/split-lines (git! dir "diff" "--name-only" from to))))]
      (s/check! (= diff (set changed)) (str "the cuts differ in " (sort diff) ", not " (sort changed))))
    (assoc ctx :dir dir :changed changed)))

;; ---------------------------------------------------------------------------
;; Reading the run

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- passed!
  "The run's result, read from standard output; fails unless it exited 0."
  [ctx]
  (let [r (the-run ctx)]
    (s/check! (= 0 (:exit r)) (str "expected exit 0\n" (s/show r)))
    (let [out (try (edn/read-string (:out r))
                   (catch Exception e (s/fail! (str "the standard output is not EDN: " (ex-message e) "\n" (s/show r)))))]
      (s/check! (map? out) (str "the result is not a map\n" (s/show r)))
      out)))

(defn- scopes-in
  "The EDN maps written in a step's text."
  [text]
  (mapv edn/read-string (re-seq #"\{[^{}]*\}" text)))

(defn- warning-lines [r]
  (filterv #(re-find #"(^|: )warning: " %) (s/err-lines r)))

(defn- lists-exactly! [ctx expected]
  (let [r (the-run ctx)
        out (passed! ctx)]
    (s/check! (= (set expected) (set (:scopes out)))
              (str "expected the scopes " (pr-str expected) ", got " (pr-str (:scopes out)) "\n" (s/show r)))
    (s/check! (= (count expected) (count (:scopes out)))
              (str "a scope is listed twice: " (pr-str (:scopes out))))
    r))

;; ---------------------------------------------------------------------------
;; Steps

(defstep #"the cuts (spec-\d+) and (spec-\d+), between which (?:only )?(.+) changed"
  (fn [ctx from to files]
    (two-cuts! ctx from to (mapv project-path (named-files files)))))

(defstep #"it lists the scopes (.+), and nothing else"
  (fn [ctx text]
    (let [expected (scopes-in text)]
      (s/check! (seq expected) (str "no scope in " text))
      (lists-exactly! ctx expected)
      ctx)))

(defstep #"it lists the scope (\{[^{}]*\})"
  (fn [ctx text]
    (let [r (lists-exactly! ctx (scopes-in text))]
      ;; the prose is the component's own: never named as a shared file too
      (doseq [rel (:changed ctx)]
        (s/check! (not-any? #(str/includes? % rel) (warning-lines r))
                  (str rel " is named as a shared file, though a component owns it\n" (s/show r))))
      ctx)))

(defstep #"it lists no scope, and names (.+) as changed shared files whose readers a person must judge"
  (fn [ctx text]
    (let [r (the-run ctx)
          out (passed! ctx)
          warnings (warning-lines r)]
      (s/check! (empty? (:scopes out)) (str "expected no scope, got " (pr-str (:scopes out)) "\n" (s/show r)))
      (doseq [named (named-files text)
              :let [rel (first (filter #(= named (fs/file-name %)) (:changed ctx)))]]
        (s/check! rel (str named " is not a file the Given changed"))
        (s/check! (some #(str/includes? % rel) warnings)
                  (str "no warning names " rel "\n" (s/show r)))
        (s/check! (some #{rel} (:shared out))
                  (str "the result does not list " rel " among the shared files\n" (s/show r))))
      ctx)))
