(ns rdm-spec.project.hooks
  "The hooks (dhf/spec/project/hooks.feature): the pre-commit hook that runs
  rdm-spec check, so a commit is refused while the check fails."
  (:require [babashka.fs :as fs]
            [rdm-spec.kernel.command :as cmd]))

(def hook-path ".githooks/pre-commit")

(def pre-commit
  "#!/bin/sh
# Written by rdm-spec: every commit is checked, and refused while the check
# fails (dhf/spec/project/hooks.feature).
exec rdm-spec check
")

(defn install!
  "Makes the hook executable and points git at .githooks."
  [dir]
  (let [f (fs/path dir hook-path)]
    (when (and (fs/exists? f) (not (fs/sym-link? f)))
      (fs/set-posix-file-permissions f "rwxr-xr-x")))
  (cmd/git! dir "config" "core.hooksPath" ".githooks"))
