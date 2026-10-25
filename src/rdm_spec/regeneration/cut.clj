(ns rdm-spec.regeneration.cut
  "Cut (dhf/spec/regeneration/cut.feature): `rdm-spec cut` tags HEAD spec-N
  when HEAD is the commit origin/HEAD names, and pushes the tag to origin. A
  commit already cut is cut once: cutting it again changes nothing.

  Also the git plumbing the regeneration context shares: Fence builds clean
  rooms only from cuts, and asks this namespace what a cut is (`cuts-at`)."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]))

;; ---------------------------------------------------------------------------
;; git

(def ^:private git-env {"GIT_TERMINAL_PROMPT" "0"})

(defn git
  "Runs git in `dir` with `args`: {:exit :out :err}, :out with the trailing
  newline removed. Never prompts. Pass {:bytes true} as the first argument
  for :out as a byte array (and {:in data} to feed standard input)."
  [dir & args]
  (let [[opts args] (if (map? (first args)) [(first args) (rest args)] [{} args])
        r (apply p/shell {:dir (str dir) :out (if (:bytes opts) :bytes :string) :err :string
                          :continue true :in (or (:in opts) "") :extra-env git-env}
                 "git" args)]
    {:exit (:exit r)
     :out (if (:bytes opts) (:out r) (str/replace (or (:out r) "") #"\r?\n\z" ""))
     :err (str/trim (or (:err r) ""))}))

(defn git!
  "Runs git like `git` and returns its output, or cannot-run with git's error."
  [dir & args]
  (let [r (apply git dir args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " (remove map? args)) " failed: " (:err r))))
    (:out r)))

(defn lines [s] (vec (remove str/blank? (str/split-lines (or s "")))))

(defn repository?
  "True when `dir` is inside a git working tree."
  [dir]
  (= "true" (:out (git dir "rev-parse" "--is-inside-work-tree"))))

(defn commit-of
  "The full id of the commit `rev` names in `dir`, or nil."
  [dir rev]
  (let [r (git dir "rev-parse" "--verify" "--quiet" "--end-of-options" (str rev "^{commit}"))]
    (when (zero? (:exit r)) (not-empty (:out r)))))

;; ---------------------------------------------------------------------------
;; What a cut is

(def cut-prefix
  "Cuts are tagged spec-N, N counting from 1 (the spec's own wording; the
  configuration's :id-prefix names scenario ids, not cuts)."
  "spec")

(def ^:private cut-re (re-pattern (str "^" cut-prefix "-([1-9]\\d*)$")))

(defn cut-number
  "N for a tag spec-N, else nil."
  [tag]
  (some-> (re-matches cut-re (str tag)) second parse-long))

(defn cut-tag? [tag] (some? (cut-number tag)))

(defn by-number [tags] (sort-by cut-number (filter cut-tag? tags)))

(defn next-cut
  "The tag the next cut takes, given every tag there is: spec-(N+1) for the
  highest spec-N, spec-1 when there is none."
  [tags]
  (str cut-prefix "-" (inc (reduce max 0 (keep cut-number tags)))))

(defn cuts-at
  "The cut tags (spec-N) on the commit `rev` names in `dir`, lowest N first."
  [dir rev]
  (by-number (lines (:out (git dir "tag" "--points-at" rev)))))

(defn- remote-tags
  "Every tag origin holds, or cannot-run when origin cannot be read."
  [dir]
  (let [r (git dir "ls-remote" "--tags" "origin")]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "cannot read origin's tags: " (:err r))))
    (for [l (lines (:out r))
          :let [ref (second (str/split l #"\s+"))]
          :when ref]
      (-> ref (str/replace #"^refs/tags/" "") (str/replace #"\^\{\}$" "")))))

(defn- short-id [sha] (subs sha 0 (min 7 (count sha))))

;; ---------------------------------------------------------------------------
;; The task

(defn cut
  "The cut task: `rdm-spec cut`."
  [req]
  (let [root (:root req)]
    (when-not (repository? root)
      (command/cannot-run! (str root " is not a git repository: there is nothing to cut")))
    (let [head (or (commit-of root "HEAD")
                   (command/cannot-run! "HEAD names no commit: there is nothing to cut"))
          already (cuts-at root head)]
      (if (seq already)
        (let [tag (first already)]
          {:result {:cut tag :commit head :already-cut true
                    :message (str "the commit " (short-id head) " is already " tag "; nothing was tagged or pushed")}
           :findings []})
        (let [default-ref (let [r (git root "rev-parse" "--abbrev-ref" "origin/HEAD")]
                            (when (zero? (:exit r)) (not-empty (:out r))))
              _ (when-not default-ref
                  (command/cannot-run! (str "origin/HEAD is not set, so the default branch is unknown "
                                            "(git remote set-head origin --auto sets it): only the default branch is cut")))
              default-branch (str/replace default-ref #"^origin/" "")
              default-sha (commit-of root "origin/HEAD")
              branch (:out (git root "rev-parse" "--abbrev-ref" "HEAD"))
              branch (if (= "HEAD" branch) "a detached HEAD" (str "branch " branch))]
          (when (not= head default-sha)
            (command/cannot-run! (str "HEAD (" branch ", " (short-id head) ") is not the commit origin/HEAD names ("
                                      default-ref ", " (short-id (or default-sha "")) "): only the default branch "
                                      default-branch " is cut")))
          (let [tag (next-cut (concat (lines (:out (git root "tag" "--list"))) (remote-tags root)))]
            (git! root "tag" tag head)
            (let [pushed (git root "push" "--quiet" "origin" (str "refs/tags/" tag))]
              (when-not (zero? (:exit pushed))
                (git root "tag" "-d" tag)
                (command/cannot-run! (str "the tag " tag " could not be pushed to origin, so HEAD is not cut: "
                                          (:err pushed)))))
            {:result {:cut tag :commit head :branch default-branch :pushed-to "origin"}
             :findings []}))))))
