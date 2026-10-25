(ns steps.regeneration-cut
  "Step definitions for dhf/spec/regeneration/cut.feature.

  A cut fixture is a throwaway repository with a bare origin
  (steps.regeneration-support/origin-project!): a copy of the room's
  allow-listed paths committed on main and pushed, origin/HEAD naming main,
  earlier cuts tagged on earlier commits and pushed. The context keeps :dir
  (the repository, where the shared When runs the command), :origin (the bare
  remote), :head (the commit HEAD names), and what the tags were before the
  run (:tags-before, :remote-before), so a Then can say what the run added."
  (:require [clojure.string :as str]
            [babashka.fs :as fs]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- needs-cut! [] (s/needs-built! 'rdm-spec.regeneration.cut))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- remember-tags
  "ctx with the tags the repository and its origin hold now."
  [ctx]
  (assoc ctx
         :tags-before (rs/all-tags (:dir ctx))
         :remote-before (rs/remote-tags (:origin ctx))))

(defn- fixture!
  "ctx with a project on origin/HEAD whose earlier cuts are `cuts`."
  [ctx cuts]
  (needs-cut!)
  (let [p (rs/origin-project! {:cuts cuts})]
    (s/check! (= (:head p) (rs/git! (:dir p) "rev-parse" "refs/remotes/origin/HEAD"))
              "HEAD is not the commit origin/HEAD names")
    (remember-tags (merge ctx p))))

(defn- exits! [ctx n]
  (let [r (the-run ctx)]
    (s/check! (= n (:exit r)) (str "expected exit " n "\n" (s/show r)))
    r))

(defn- tags-unchanged! [ctx]
  (let [r (the-run ctx)]
    (s/check! (= (:tags-before ctx) (rs/all-tags (:dir ctx)))
              (str "the run changed the tags: " (sort (rs/all-tags (:dir ctx))) "\n" (s/show r)))
    (s/check! (= (:remote-before ctx) (rs/remote-tags (:origin ctx)))
              (str "the run changed origin's tags: " (rs/remote-tags (:origin ctx)) "\n" (s/show r)))))

(defn- strip-level!
  "Removes the level tag of the first scenario with one in `rel` (a feature
  file of `dir`), so the spec check finds a scenario with no level."
  [dir rel]
  (let [f (fs/file dir rel)
        lines (str/split-lines (slurp f))
        i (first (keep-indexed (fn [i l] (when (re-find #"^\s*@\S+.*@(e2e|contract)\b" l) i)) lines))]
    (s/check! i (str rel " has no scenario with a level"))
    (spit f (str (str/join "\n" (update (vec lines) i #(str/replace % #"\s*@(e2e|contract)\b" ""))) "\n"))))

(defn- finding-lines
  "The error lines of a run that are findings, not warnings or events."
  [r]
  (vec (remove #(or (re-find #"(^|: )warning: " %) (str/starts-with? % "event: ")) (s/err-lines r))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"HEAD is the commit origin/HEAD names, and the last cut is spec-(\d+)"
  (fn [ctx n]
    (let [n (parse-long n)]
      ;; an earlier cut below the last, so the last is found by its number
      (fixture! ctx (vec (distinct [(str "spec-" (max 1 (dec n))) (str "spec-" n)]))))))

(defstep #"the commit tagged (spec-\d+)"
  (fn [ctx tag]
    (let [ctx (fixture! ctx ["spec-1"])
          {:keys [dir head]} ctx]
      (rs/git! dir "tag" tag head)
      (rs/git! dir "push" "-q" "origin" (str "refs/tags/" tag))
      (s/check! (= head (get (rs/remote-tags (:origin ctx)) tag)) (str tag " is not on origin"))
      (remember-tags (assoc ctx :tag tag)))))

(defstep #"HEAD is the commit origin/HEAD names, and \"rdm-spec check\" exits 1 on it"
  (fn [ctx]
    (let [ctx (fixture! ctx ["spec-6"])
          dir (:dir ctx)]
      (strip-level! dir "dhf/spec/architecture/lint.feature")
      (let [head (rs/commit! dir "a merged spec change the check refuses")]
        (rs/push-head! dir)
        (s/check! (= head (rs/git! dir "rev-parse" "refs/remotes/origin/HEAD")) "HEAD is not origin/HEAD")
        (let [check (kc/run! ctx dir ["check"])]
          (s/check! (= 1 (:exit check)) (str "rdm-spec check does not exit 1 on HEAD\n" (s/show check)))
          (s/check! (seq (finding-lines check)) "the check names no finding")
          (remember-tags (assoc ctx :head head :check check)))))))

(defstep #"HEAD is not the commit origin/HEAD names"
  (fn [ctx]
    (let [ctx (fixture! ctx ["spec-6"])
          dir (:dir ctx)]
      (rs/git! dir "checkout" "-q" "-b" "feature")
      (s/write! dir "dhf/spec/history.md" "a change not merged\n")
      (let [head (rs/commit! dir "work on a branch")]
        (s/check! (not= head (rs/git! dir "rev-parse" "refs/remotes/origin/HEAD")) "HEAD is origin/HEAD")
        (remember-tags (assoc ctx :head head :branch "feature" :default-branch "main"))))))

;; ---------------------------------------------------------------------------
;; Whens

(defstep #"\"rdm-spec cut\" is run (?:again )?on it"
  (fn [ctx] (kc/run-line ctx "cut")))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"HEAD is tagged (spec-\d+)"
  (fn [ctx tag]
    (let [r (exits! ctx 0)
          {:keys [dir head]} ctx
          result (kc/stdout-edn r)]
      (s/check! (= head (rs/git! dir "rev-parse" "HEAD")) "HEAD moved")
      (s/check! (contains? (rs/tags-at dir head) tag) (str "HEAD is not tagged " tag "\n" (s/show r)))
      (s/check! (= (conj (:tags-before ctx) tag) (rs/all-tags dir))
                (str "the run added other tags than " tag ": " (sort (rs/all-tags dir))))
      (s/check! (= tag (:cut result)) (str "the result does not name the cut " tag "\n" (s/show r)))
      (assoc ctx :tag tag))))

(defstep #"the tag is pushed to origin"
  (fn [ctx]
    (let [remote (rs/remote-tags (:origin ctx))]
      (s/check! (= (:head ctx) (get remote (:tag ctx)))
                (str "origin holds no " (:tag ctx) " on HEAD: " remote))
      (s/check! (= (assoc (:remote-before ctx) (:tag ctx) (:head ctx)) remote)
                (str "the run pushed other tags: " remote)))))

(defstep #"no tag is added and the cut says the commit is already (spec-\d+)"
  (fn [ctx tag]
    (let [r (exits! ctx 0)
          result (kc/stdout-edn r)]
      (tags-unchanged! ctx)
      (s/check! (map? result) (str "the result is not EDN\n" (s/show r)))
      (s/check! (= tag (:cut result)) (str "the result does not name " tag "\n" (s/show r)))
      (s/check! (= tag (:already-cut result)) (str "the result does not say the commit is already " tag "\n" (s/show r))))))

(defstep #"it exits 1, tags nothing, and prints each of the check's finding lines unchanged"
  (fn [ctx]
    (let [r (exits! ctx 1)
          lines (set (s/err-lines r))
          expected (finding-lines (:check ctx))]
      (tags-unchanged! ctx)
      (s/check! (map? (kc/stdout-edn r)) (str "on exit 1 standard output holds no result\n" (s/show r)))
      (doseq [l expected]
        (s/check! (contains? lines l) (str "the cut does not print the check's finding " (pr-str l) "\n" (s/show r)))))))

(defstep #"it exits 2 and names the branch"
  (fn [ctx]
    (let [r (exits! ctx 2)
          line (first (filter #(str/starts-with? % "cannot-run: ") (s/err-lines r)))]
      (tags-unchanged! ctx)
      (s/check! (str/blank? (:out r)) (str "a cut that cannot run printed a result\n" (s/show r)))
      (s/check! line (str "the cut says no cannot-run line\n" (s/show r)))
      (doseq [b [(:default-branch ctx) (:branch ctx)]]
        (s/check! (re-find (re-pattern (str "\\b" b "\\b")) line)
                  (str "the refusal does not name the branch " b "\n" (s/show r)))))))
