(ns rdm-spec.project.hooks
  "Component Hooks (project context): the pre-commit hook that runs
  rdm-spec check on every commit, and its installation in a git repository."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(def hook-path ".githooks/pre-commit")

(def hook-text
  (str "#!/bin/sh\n"
       "# rdm-spec's pre-commit hook: every commit is checked, and refused when\n"
       "# \"rdm-spec check\" exits other than 0 (dhf/spec, the hooks component).\n"
       "exec rdm-spec check\n"))

(defn install!
  "Makes the hook at DIR executable and points core.hooksPath at it."
  [dir]
  (let [f (project/abs dir hook-path)]
    (when (and (fs/regular-file? f) (not (fs/sym-link? f)))
      (try (fs/set-posix-file-permissions f "rwxr-xr-x") (catch Exception _ nil)))
    (let [cur (str/trim (str (:out (project/git dir "config" "--get" "core.hooksPath"))))]
      (when-not (= cur ".githooks")
        (project/git! dir "config" "core.hooksPath" ".githooks")))))
