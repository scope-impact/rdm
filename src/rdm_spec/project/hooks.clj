(ns rdm-spec.project.hooks
  "Installs the pre-commit hook that runs rdm-spec check
  (dhf/spec/01-start-a-project/hooks.feature): .githooks/pre-commit, with
  core.hooksPath set to .githooks."
  (:require [rdm-spec.kernel.project :as project]))

(defn install!
  "Points the repository at dir to its .githooks directory."
  [dir]
  (project/git! dir "config" "core.hooksPath" ".githooks"))
