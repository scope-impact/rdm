(ns rdm-spec.regeneration.cut
  "Component Cut (regeneration context): tags HEAD spec-N when it is the
  commit origin/HEAD names and its spec passes the check, and pushes the
  tag. Cutting a commit already cut changes nothing. Also the git helpers
  that read a cut."
  (:require [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def cut-re #"spec-(\d+)")

(defn cut-tags
  "The spec-N tags pointing at REV."
  [rev]
  (vec (filter #(re-matches cut-re %)
               (str/split-lines (str (:out (project/git (project/root) "tag" "--points-at" rev)))))))

(defn resolve-cut
  "The cut named by ARG: a spec-N tag, or a commit carrying one; nil otherwise."
  [arg]
  (let [sha (project/resolve-commit arg)]
    (when sha
      (cond
        (and (re-matches cut-re (str arg)) (= 0 (:exit (project/git (project/root) "rev-parse" "--verify" "--quiet" (str "refs/tags/" arg))))) arg
        :else (first (sort-by #(- (parse-long (second (re-matches cut-re %)))) (cut-tags sha)))))))

(defn cut-task [_]
  (let [root (project/root)]
    (when-not (project/git-repo? root) (project/cannot-run! "the project is not in a git repository, so there is nothing to cut"))
    (let [head (project/resolve-commit "HEAD")
          origin (str/trim (str (:out (project/git root "rev-parse" "--verify" "--quiet" "refs/remotes/origin/HEAD^{commit}"))))
          branch (str/trim (str (:out (project/git root "rev-parse" "--abbrev-ref" "HEAD"))))]
      (when (str/blank? origin)
        (project/cannot-run! (str "no origin/HEAD names the default branch, so the branch " branch " cannot be cut")))
      (when-not (= head origin)
        (project/cannot-run! (str "HEAD, on the branch " branch ", is not the commit origin/HEAD names: only a merge to the default branch is cut")))
      (if-let [t (first (cut-tags head))]
        (project/report {:cut {:tag t :commit head :says (str "the commit is already " t ": nothing to cut")}})
        (let [check ((requiring-resolve 'rdm-spec.kernel.command/check-task) {:cfg (project/config) :opts {} :args []})]
          (if (seq (:findings check))
            (project/report {:cut {:refused true :commit head}} (:findings check))
            (let [local (str/split-lines (str (:out (project/git root "tag" "-l" "spec-*"))))
                  remote (map #(last (str/split % #"/")) (str/split-lines (str (:out (project/git root "ls-remote" "--tags" "origin")))))
                  n (inc (reduce max 0 (keep #(some-> (re-matches cut-re (str/trim %)) second parse-long) (concat local remote))))
                  t (str "spec-" n)]
              (project/git! root "tag" t head)
              (let [r (project/git root "push" "origin" (str "refs/tags/" t))]
                (when-not (= 0 (:exit r))
                  (project/cannot-run! (str "tagged " t " but could not push it to origin: " (project/one-line (:err r))))))
              (project/report {:cut {:tag t :commit head :pushed "origin"}}))))))))
