(ns rdm-spec.regeneration.cut
  "Cut (dhf/spec/regeneration/cut.feature): `rdm-spec cut` tags HEAD spec-N
  when it is the commit origin/HEAD names, and pushes the tag to origin. A
  regeneration reads a cut and nothing else (the fence, beside this, builds
  clean rooms only from cuts, through the git helpers here).

  In order:
    1. the project must be a git repository with a HEAD commit (else exit 2).
    2. a HEAD that already carries a spec-N tag is already cut: nothing is
       tagged or pushed, the result says which cut it is (exit 0). Cutting
       twice changes nothing, so CI may re-run a cut harmlessly.
    3. origin/HEAD must name a commit (else exit 2), and HEAD must be that
       commit; any other HEAD is refused (exit 2), naming the default branch
       origin/HEAD names and HEAD's own branch.
    4. a HEAD that origin already holds a spec-N tag for is already cut, as in 2.
    5. `rdm-spec check` runs on HEAD, in a temporary worktree of the same
       repository (so the check sees the commit and its history, never an
       uncommitted change); when it has findings the cut exits 1, tags
       nothing and relays each finding line unchanged. A check that cannot
       run stops the cut (exit 2).
    6. HEAD is tagged spec-N, N one more than the highest cut number among
       the local tags and origin's (spec-1 when there is none), as a
       lightweight tag (it needs no identity, which CI often lacks), and the
       tag is pushed to origin. A push that fails removes the local tag again,
       so a re-run cuts the commit rather than calling it already cut.

  Cut tags are always spec-N: the tag names a cut, not a scenario id, so the
  configured :id-prefix does not apply."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; Names

(defn lines
  "The non-blank lines of `s`, trimmed."
  [s]
  (vec (remove str/blank? (map str/trim (str/split-lines (or s ""))))))

(defn cut-number
  "N when `s` is a cut's tag name, spec-N; else nil."
  [s]
  (when-let [[_ n] (and (string? s) (re-matches #"spec-(\d+)" s))]
    (parse-long n)))

(defn sort-cuts
  "The cut tags among `names`, by their number."
  [names]
  (vec (sort-by cut-number (distinct (filter cut-number names)))))

(defn next-cut
  "The tag of the cut after every cut among `names`: spec-1 when there is none."
  [names]
  (str "spec-" (inc (reduce max 0 (keep cut-number names)))))

(defn short-hash
  "The commit hash `c` abbreviated to seven characters."
  [c]
  (let [c (str c)] (subs c 0 (min 7 (count c)))))

;; ---------------------------------------------------------------------------
;; git

(defn git
  "Runs git in `dir`: {:exit :out :err}, whatever its exit. An options map
  may come first among `args`: :extra-env (variables added to the
  environment) and :in (standard input, default none). Cannot run when git
  itself cannot be run."
  [dir & args]
  (let [[opts args] (if (map? (first args)) [(first args) (rest args)] [{} args])]
    (try
      (let [r (apply p/shell (cond-> {:dir (str dir) :out :string :err :string :continue true
                                      :in (or (:in opts) "")}
                               (:extra-env opts) (assoc :extra-env (:extra-env opts)))
                     "git" (map str args))]
        {:exit (:exit r) :out (str (:out r)) :err (str (:err r))})
      (catch java.io.IOException e
        (command/cannot-run! (str "git cannot be run: " (ex-message e)))))))

(defn git!
  "Runs git as `git` does and returns its trimmed standard output; cannot run
  when git fails."
  [dir & args]
  (let [r (apply git dir args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " (remove map? args)) " failed in " dir ": "
                                (str/trim (str (:err r) (:out r))))))
    (str/trim (:out r))))

(defn repository!
  "Cannot run unless `dir` lies in a git repository's working tree."
  [dir]
  (let [r (git dir "rev-parse" "--is-inside-work-tree")]
    (when-not (and (zero? (:exit r)) (= "true" (str/trim (:out r))))
      (command/cannot-run! (str dir " is not a git repository: a cut is a tag in one"))))
  nil)

(defn rev
  "The full hash of the commit `name` names in `dir`, nil when it names none."
  [dir name]
  (when-not (str/blank? (str name))
    (let [r (git dir "rev-parse" "--verify" "--quiet" "--end-of-options" (str name "^{commit}"))]
      (when (zero? (:exit r)) (not-empty (str/trim (:out r)))))))

(defn cuts-at
  "The spec-N tags pointing at `commit` in `dir`, by their number."
  [dir commit]
  (sort-cuts (lines (git! dir "tag" "--points-at" commit "--list" "spec-*"))))

(defn local-cuts
  "Every spec-N tag of `dir`, by number."
  [dir]
  (sort-cuts (lines (git! dir "tag" "--list" "spec-*"))))

(defn remote-cut-commits
  "{tag commit} of origin's spec-N tags (each tag peeled to its commit)."
  [dir]
  (let [r (git dir "ls-remote" "--tags" "origin" "refs/tags/spec-*")]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "origin's tags cannot be read: " (str/trim (:err r)))))
    (reduce (fn [m l]
              (let [[oid ref] (str/split l #"\s+")
                    peeled? (str/ends-with? (str ref) "^{}")
                    tag (-> (str ref) (str/replace #"^refs/tags/" "") (str/replace #"\^\{\}$" ""))]
                (cond (not (cut-number tag)) m
                      peeled? (assoc m tag oid)
                      (contains? m tag) m
                      :else (assoc m tag oid))))
            {} (lines (:out r)))))

(defn remote-cuts
  "origin's spec-N tags, by number."
  [dir]
  (sort-cuts (keys (remote-cut-commits dir))))

(defn default-branch
  "{:branch :commit} of what origin/HEAD names, nil when it names no commit."
  [dir]
  (when-let [c (rev dir "refs/remotes/origin/HEAD")]
    (let [r (git dir "symbolic-ref" "--quiet" "refs/remotes/origin/HEAD")]
      {:branch (when (zero? (:exit r))
                 (str/replace (str/trim (:out r)) #"^refs/remotes/origin/" ""))
       :commit c})))

(defn current-branch
  "The branch HEAD is on, nil when HEAD is detached."
  [dir]
  (let [r (git dir "symbolic-ref" "--quiet" "--short" "HEAD")]
    (when (zero? (:exit r)) (not-empty (str/trim (:out r))))))

(defn checkout!
  "Writes the files `paths` as the commit `commit` of the repository `dir`
  holds them into the directory `out` (made when missing): straight from the
  commit's tree through a temporary index, so neither the repository's index
  nor its working tree is read or changed, and no .git is written."
  [dir commit out paths]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-index-"})
        index (str (fs/file tmp "index"))
        env {:extra-env {"GIT_INDEX_FILE" index}}
        out (fs/absolutize out)]
    (try
      (fs/create-dirs out)
      (git! dir env "read-tree" commit)
      (when (seq paths)
        (git! dir (assoc env :in (str (str/join "\u0000" paths) "\u0000"))
              "checkout-index" "-f" "-z" "--stdin" (str "--prefix=" out "/")))
      nil
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; The check, on HEAD

(defn- check-at
  "Runs the check on `commit` in a temporary worktree of `dir`: the check's
  {:exit :findings :cannot-run}."
  [dir commit env]
  (let [tmp (fs/create-temp-dir {:prefix "rdm-spec-cut-"})
        wt (str (fs/file tmp "head"))]
    (try
      (git! dir "-c" "core.hooksPath=/dev/null" "worktree" "add" "--quiet" "--detach" wt commit)
      (command/execute ["check"] {:root wt :env env})
      (finally
        (git dir "worktree" "remove" "--force" wt)
        (git dir "worktree" "prune")
        (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; The task

(defn- already [head tag]
  {:result (array-map :cut tag :commit head :already-cut tag) :findings []})

(defn cut
  "The cut task: `rdm-spec cut`."
  [req]
  (let [dir (:root req)
        _ (repository! dir)
        head (or (rev dir "HEAD") (command/cannot-run! "HEAD names no commit: there is nothing to cut"))]
    (if-let [tag (last (cuts-at dir head))]
      (already head tag)
      (let [{:keys [branch commit]} (or (default-branch dir)
                                        (command/cannot-run!
                                         (str "origin/HEAD names no commit: the default branch is unknown"
                                              " (git remote set-head origin --auto)")))
            on (current-branch dir)]
        (when-not (= head commit)
          (command/cannot-run!
           (str "HEAD (" (if on (str "the branch " on) "a detached HEAD") ", " (short-hash head)
                ") is not the commit origin/HEAD names (the branch " (or branch "origin/HEAD") ", "
                (short-hash commit) "): only the default branch is cut")))
        (let [remote (remote-cut-commits dir)]
          (if-let [tag (last (sort-cuts (for [[t c] remote :when (= c head)] t)))]
            (already head tag)
            (let [check (check-at dir head (:env req))]
              (when (:cannot-run check)
                (command/cannot-run! (str "rdm-spec check cannot run on HEAD: " (:cannot-run check))))
              (if (= 1 (:exit check))
                {:result (array-map :cut nil :commit head :branch branch)
                 :findings (vec (remove command/warning? (:findings check)))}
                (let [previous (last (sort-cuts (concat (local-cuts dir) (keys remote))))
                      tag (next-cut (concat (local-cuts dir) (keys remote)))]
                  (git! dir "tag" tag head)
                  (let [push (git dir "push" "--quiet" "origin" (str "refs/tags/" tag))]
                    (when-not (zero? (:exit push))
                      (git dir "tag" "-d" tag)
                      (command/cannot-run! (str "the tag " tag " could not be pushed to origin, so nothing is cut: "
                                                (str/trim (:err push))))))
                  {:result (array-map :cut tag :commit head :branch branch :previous previous :pushed-to "origin")
                   :findings []})))))))))
