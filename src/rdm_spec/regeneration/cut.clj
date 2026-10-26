(ns rdm-spec.regeneration.cut
  "The cut (dhf/spec/regeneration/cut.feature): tags HEAD spec-N when HEAD is
  the commit origin/HEAD names and its spec passes the check (the gate the
  task table puts before it), and pushes the tag. Cutting a commit already
  cut changes nothing. Its git helpers resolve cuts for the fence."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]))

(defn cut-tags
  "The cuts among tag names."
  [tags]
  (filterv #(re-matches #"spec-\d+" %) tags))

(defn cut-number [tag] (parse-long (subs tag 5)))

(defn next-tag [tags]
  (str "spec-" (inc (reduce max 0 (map cut-number (cut-tags tags))))))

(defn tags-at
  "The cuts that tag a revision."
  [root rev]
  (let [{:keys [exit out]} (cmd/git root "tag" "--points-at" rev)]
    (if (= 0 exit) (cut-tags (remove str/blank? (str/split-lines out))) [])))

(defn resolve-cut
  "The cut a revision names: a tag spec-N, or a commit spec-N tags. nil when
  it is no cut."
  [root rev]
  (if (and (re-matches #"spec-\d+" rev)
           (= 0 (:exit (cmd/git root "rev-parse" "-q" "--verify" (str "refs/tags/" rev)))))
    rev
    (let [{:keys [exit out]} (cmd/git root "rev-parse" "-q" "--verify" (str rev "^{commit}"))]
      (when (= 0 exit)
        (first (sort-by cut-number > (tags-at root (str/trim out))))))))

(defn commit-of [root rev]
  (let [{:keys [exit out]} (cmd/git root "rev-parse" "-q" "--verify" (str rev "^{commit}"))]
    (when (= 0 exit) (str/trim out))))

(defn precondition-stage
  "HEAD must be origin/HEAD; a commit already cut is said, and nothing more runs."
  [{:keys [root]}]
  (let [head (or (commit-of root "HEAD") (cmd/cannot-run! "HEAD names no commit: there is nothing to cut"))
        branch (str/trim (:out (cmd/git root "rev-parse" "--abbrev-ref" "HEAD")))
        origin (commit-of root "refs/remotes/origin/HEAD")
        origin-branch (str/trim (:out (cmd/git root "symbolic-ref" "-q" "--short" "refs/remotes/origin/HEAD")))]
    (cond
      (nil? origin)
      (cmd/cannot-run! (str "there is no origin/HEAD: only the default branch of origin is cut, and HEAD is on " branch))
      (not= head origin)
      (cmd/cannot-run! (str "HEAD, on the branch " branch ", is not the commit " origin-branch " names: only the default branch is cut"))
      :else
      (if-let [t (first (tags-at root "HEAD"))]
        {:stop true :exit 0
         :result {:cut {:tag t :commit head :already true
                        :message (str "the commit " (subs head 0 7) " is already " t ": nothing to cut")}}}
        {:ctx {::head head}}))))

(defn stage
  "Tags HEAD with the next spec-N and pushes the tag."
  [{:keys [root]}]
  (let [head (commit-of root "HEAD")
        tags (remove str/blank? (str/split-lines (:out (cmd/git root "tag" "--list" "spec-*"))))
        tag (next-tag tags)]
    (cmd/git! root "tag" tag head)
    (let [{:keys [exit err]} (cmd/git root "push" "-q" "origin" (str "refs/tags/" tag))]
      (when-not (= 0 exit)
        (cmd/git root "tag" "-d" tag)
        (cmd/cannot-run! (str "the tag " tag " could not be pushed to origin, so it was removed: " err))))
    {:result {:cut {:tag tag :commit head :pushed true}}}))
