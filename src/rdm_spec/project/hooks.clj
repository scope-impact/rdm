(ns rdm-spec.project.hooks
  "Hooks (dhf/spec/project/hooks.feature): the pre-commit hook that runs
  `rdm-spec check` on every commit, and refuses the commit unless it exits 0.

  No task of its own: Init and Adopt install the hook with it. The hook is
  `.githooks/pre-commit`, activated by setting git's `core.hooksPath` to
  `.githooks` (dhf/harness/stack.md). A hook needs a git repository, so this is
  also where a directory that is not one is refused."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

(def hooks-dir ".githooks")
(def hook-path ".githooks/pre-commit")

(defn hook-content
  "The text of the pre-commit hook."
  []
  (slurp (io/resource "rdm_spec/project/skeleton/pre-commit.tmpl")))

(defn runs-check?
  "True when a hook's text runs rdm-spec check."
  [text]
  (boolean (re-find #"rdm-spec\"?\s+\"?check\b" (or text ""))))

;; ---------------------------------------------------------------------------
;; git

(defn git
  "Runs git with `args` in `dir`: {:exit :out :err}. Cannot run without git."
  [dir & args]
  (let [r (try (apply p/shell {:dir (str dir) :out :string :err :string :continue true} "git" args)
               (catch Exception e
                 (command/cannot-run! (str "git could not be run: " (ex-message e)))))]
    {:exit (:exit r) :out (str/trim (or (:out r) "")) :err (str/trim (or (:err r) ""))}))

(defn repository-root
  "The top-level directory of the git work tree holding `dir`, or nil when
  `dir` is in none."
  [dir]
  (when (fs/directory? dir)
    (let [r (git dir "rev-parse" "--show-toplevel")]
      (when (and (zero? (:exit r)) (not (str/blank? (:out r))))
        (str (fs/canonicalize (:out r)))))))

(defn require-repository!
  "The top-level directory of the repository holding `dir`, or cannot-run: the
  hook needs a git repository."
  [dir]
  (or (repository-root dir)
      (command/cannot-run! (str "the pre-commit hook needs a git repository, and " dir
                                " is not in one; run \"git init\" first, or \"rdm-spec init PATH\" for a new project"))))

(defn hooks-path
  "git's core.hooksPath in the repository at `root`, or nil when unset."
  [root]
  (let [r (git root "config" "--local" "--get" "core.hooksPath")]
    (when (zero? (:exit r)) (not-empty (:out r)))))

(defn activate!
  "Points core.hooksPath at .githooks, unless it is already set: a hooks path
  set to anything else is kept, never overwritten. Returns
  {:hooks-path value :set? bool :findings [...]}."
  [root]
  (let [current (hooks-path root)]
    (cond
      (nil? current)
      (let [r (git root "config" "--local" "core.hooksPath" hooks-dir)]
        (when-not (zero? (:exit r))
          (command/cannot-run! (str "git could not set core.hooksPath: " (:err r))))
        {:hooks-path hooks-dir :set? true :findings []})

      (= (str/replace current #"/+$" "") hooks-dir)
      {:hooks-path current :set? false :findings []}

      :else
      {:hooks-path current :set? false
       :findings [(command/warning
                   "hooks-path-kept"
                   (str "core.hooksPath is already " current ", and is kept, so " hook-path
                        " does not run; make the hook there run \"rdm-spec check\""))]})))

(defn check-kept-hook
  "Warnings about a pre-commit hook that was already there and kept."
  [root]
  (let [f (fs/file root hook-path)]
    (when (and (fs/exists? f) (not (runs-check? (try (slurp f) (catch Exception _ "")))))
      [(command/warning hook-path nil "hook-kept"
                        "the pre-commit hook already there is kept, and it does not run \"rdm-spec check\"")])))
