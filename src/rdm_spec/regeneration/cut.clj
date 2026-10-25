(ns rdm-spec.regeneration.cut
  "Cut (dhf/spec/regeneration/cut.feature): `rdm-spec cut` tags HEAD spec-N
  when it is the commit origin/HEAD names, and pushes the tag to origin. A
  regeneration reads a cut and nothing else.

  In order:
    1. HEAD must be the commit origin/HEAD names (the default branch, as the
       cut workflow points it); otherwise it cannot run (exit 2), naming the
       branch HEAD is on and the one origin/HEAD names.
    2. A HEAD already tagged spec-N (here or at origin) is not cut again: the
       task passes, adds no tag, and says which cut the commit already is.
    3. HEAD's spec must pass `rdm-spec check`, run on HEAD's tree exactly as
       committed: a HEAD the check finds a problem in is not cut (exit 1), and
       the cut names each of the check's findings.
    4. The tag is spec-N, N one more than the highest cut here or at origin
       (spec-1 for the first), a lightweight tag pushed to origin.

  The git helpers here (`git`, `git!`, `cut-tags`, `cuts-at`, `checkout!`)
  are the ones the fence uses too."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; git

(defn git
  "Runs git in `dir` with `args`: {:exit :out :err}. `opts` may give
  :extra-env."
  [dir & args]
  (let [[opts args] (if (map? (first args)) [(first args) (rest args)] [{} args])
        r (apply p/shell (merge {:dir (str dir) :out :string :err :string :continue true :in ""} opts)
                 "git" args)]
    {:exit (:exit r) :out (:out r) :err (:err r)}))

(defn git!
  "Runs git in `dir` and returns its trimmed output; cannot run (exit 2) when
  git fails."
  [dir & args]
  (let [r (apply git dir args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " (remove map? args)) " failed: "
                                (str/trim (str (:err r) (:out r))))))
    (str/trim (:out r))))

(defn lines [s] (vec (remove str/blank? (str/split-lines (or s "")))))

(defn repository!
  "Cannot run unless `dir` is in a git repository."
  [dir]
  (when-not (zero? (:exit (git dir "rev-parse" "--git-dir")))
    (command/cannot-run! (str dir " is not a git repository: a cut is a git tag"))))

(defn rev
  "The full hash of the commit `r` names in `dir`, or nil."
  [dir r]
  (let [x (git dir "rev-parse" "--verify" "-q" "--end-of-options" (str r "^{commit}"))]
    (when (zero? (:exit x)) (str/trim (:out x)))))

;; ---------------------------------------------------------------------------
;; Cuts

(def cut-re #"spec-(\d+)")

(defn cut-number
  "N of the cut tag spec-N, or nil for any other name."
  [tag]
  (some-> (re-matches cut-re (str tag)) second parse-long))

(defn cut-tags
  "The cut tags among `tags`, by number."
  [tags]
  (vec (sort-by cut-number (distinct (filter cut-number tags)))))

(defn next-cut
  "The tag of the next cut after `tags`: one more than the highest."
  [tags]
  (str "spec-" (inc (reduce max 0 (keep cut-number tags)))))

(defn cuts-at
  "The cut tags in `dir` pointing at the commit `commit`, by number."
  [dir commit]
  (cut-tags (lines (git! dir "tag" "--points-at" commit))))

(defn local-tags [dir] (lines (git! dir "tag" "--list")))

(defn parse-ls-remote
  "{tag commit} from `git ls-remote --tags` output: a peeled line (^{})
  gives an annotated tag's commit."
  [out]
  (reduce (fn [m l]
            (let [[sha ref] (str/split l #"\s+" 2)
                  ref (str/replace (or ref "") #"^refs/tags/" "")]
              (if (str/ends-with? ref "^{}")
                (assoc m (subs ref 0 (- (count ref) 3)) sha)
                (if (contains? m ref) m (assoc m ref sha)))))
          {} (lines out)))

(defn remote-tags
  "The tags origin holds: {tag commit}."
  [dir]
  (parse-ls-remote (git! dir "ls-remote" "--tags" "origin")))

(defn branch-of
  "The branch HEAD is on, or a description of a detached HEAD."
  [dir]
  (let [r (git dir "symbolic-ref" "--short" "-q" "HEAD")]
    (if (zero? (:exit r)) (str/trim (:out r)) "a detached HEAD")))

(defn default-branch
  "The branch origin/HEAD names (origin/main), or nil."
  [dir]
  (let [r (git dir "symbolic-ref" "--short" "-q" "refs/remotes/origin/HEAD")]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn short-hash [h] (subs h 0 (min 12 (count h))))

;; ---------------------------------------------------------------------------
;; A commit's files, outside the repository

(defn checkout!
  "Writes the files `paths` (relative, every one a file of the commit; nil
  for every file) of `commit` in the repository `dir` into `target`, through
  a temporary index: the repository's own index and working tree are not
  touched, and nothing else of its history is copied."
  [dir commit target paths]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-checkout-"})]
    (try
      (let [env {:extra-env {"GIT_INDEX_FILE" (str (fs/file tmp "index"))
                             "GIT_LITERAL_PATHSPECS" "1"}}
            spec-file (str (fs/file tmp "paths"))]
        (fs/create-dirs target)
        (if (nil? paths)
          (git! dir env (str "--work-tree=" (fs/absolutize target)) "checkout" "-f" commit "--" ".")
          (do (spit spec-file (apply str (map #(str % "\u0000") paths)))
              (when (seq paths)
                (git! dir env (str "--work-tree=" (fs/absolutize target)) "checkout" "-f" commit
                      (str "--pathspec-from-file=" spec-file) "--pathspec-file-nul")))))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; The check on HEAD

(defn check-commit
  "Runs `rdm-spec check` on the tree of `commit` exactly as committed:
  {:exit :out :err}."
  [dir commit]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-cut-check-"})]
    (try
      (checkout! dir commit tmp nil)
      (command/run-task ["check"] (str tmp) nil)
      (finally (fs/delete-tree tmp)))))

(defn check-findings
  "The findings of a check's error stream, as findings of the cut: each of
  the check's finding lines as it printed it (warnings and events left out)."
  [err-lines]
  (vec (for [l err-lines
             :when (not (or (str/includes? l " warning: ") (str/starts-with? l "warning: ")
                            (str/starts-with? l "event: ")))]
         {:file nil :line nil :rule nil :message l :severity :error})))

;; ---------------------------------------------------------------------------
;; The task

(defn cut
  "The cut task: `rdm-spec cut`."
  [req]
  (let [dir (:root req)
        _ (repository! dir)
        head (or (rev dir "HEAD") (command/cannot-run! "HEAD names no commit: there is nothing to cut"))
        origin-head (rev dir "refs/remotes/origin/HEAD")
        branch (branch-of dir)]
    (when-not origin-head
      (command/cannot-run! (str "origin/HEAD names no commit, so the default branch is unknown (HEAD is on "
                                branch "); point it with: git remote set-head origin --auto")))
    (when (not= head origin-head)
      (command/cannot-run! (str "HEAD (" (short-hash head) ", on " branch ") is not the commit origin/HEAD names ("
                                (or (default-branch dir) "origin/HEAD") ", " (short-hash origin-head)
                                "): only the default branch is cut")))
    (let [remote (remote-tags dir)
          already (cut-tags (concat (cuts-at dir head)
                                    (for [[t c] remote :when (= c head)] t)))]
      (if (seq already)
        (let [t (first already)]
          {:result {:cut t :commit head :already-cut true
                    :message (str "HEAD " (short-hash head) " is already " t ": nothing was tagged")}
           :findings []})
        (let [check (check-commit dir head)
              err (lines (str/join "\n" (:err check)))]
          (case (long (:exit check))
            0 (let [tag (next-cut (concat (local-tags dir) (keys remote)))]
                (git! dir "tag" tag head)
                (let [push (git dir "push" "origin" (str "refs/tags/" tag))]
                  (when-not (zero? (:exit push))
                    (git dir "tag" "-d" tag)
                    (command/cannot-run! (str "the tag " tag " could not be pushed to origin, so nothing was cut: "
                                              (str/trim (:err push))))))
                {:result {:cut tag :commit head :branch branch :pushed-to "origin"}
                 :findings []})
            1 {:result {}
               :findings (conj (check-findings err)
                               (command/finding "check-failed"
                                                (str "HEAD " (short-hash head) " fails rdm-spec check, so it is not cut:"
                                                     " nothing was tagged")))}
            (command/cannot-run! (str "rdm-spec check cannot run on HEAD " (short-hash head) ", so it is not cut: "
                                      (str/join "; " err)))))))))
