(ns steps.project-hooks
  "Step definitions of dhf/spec/project/hooks.feature: a project made by init
  whose hook stays on, with an rdm-spec on PATH that runs the code under test
  by path and logs each call."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(defstep #"the pre-commit hook is installed"
  (fn [ctx]
    (let [ctx (k/base! ctx)
          bin (k/path-in ctx "bin")
          log (k/path-in ctx "calls.log")
          r (k/rdm ctx (:base ctx) ["init" "project"])
          dir (k/path-in ctx "project")]
      (k/check-exit r 0)
      (k/write-files! bin {"rdm-spec" (str "#!/bin/sh\necho \"$@\" >> '" log "'\nexec " (str/join " " (k/rdm-command)) " \"$@\"\n")})
      (fs/set-posix-file-permissions (fs/path bin "rdm-spec") "rwxr-xr-x")
      (assert (= ".githooks" (k/git-config dir "core.hooksPath")))
      (assoc ctx :dir dir ::log log
             :env {"PATH" (str bin ":" (System/getenv "PATH"))}))))

(defn commit [ctx]
  (k/sh (:dir ctx) (:env ctx) "git" "-c" "commit.gpgsign=false" "add" "-A")
  (k/sh (:dir ctx) (:env ctx) "git" "-c" "commit.gpgsign=false" "commit" "-q" "-m" "a change"))

(defstep #"a commit is made"
  (fn [ctx]
    (k/write-files! (:dir ctx) {"dhf/spec/glossary.md" "# Glossary\n\n**Sauna**: a hot room.\n"})
    (merge ctx (commit ctx))))

(defstep #"the hook runs \"rdm-spec check\""
  (fn [ctx]
    (assert (= 0 (:exit ctx)) (str "the commit failed: " (:err ctx)))
    (assert (fs/exists? (fs/file (::log ctx))) "the hook ran no rdm-spec")
    (assert (some #(= "check" (str/trim %)) (str/split-lines (slurp (::log ctx)))) (slurp (::log ctx)))
    ctx))

(defstep #"the commit is refused when the check exits other than 0"
  (fn [ctx]
    (let [head (k/git (:dir ctx) "rev-parse" "HEAD")]
      (k/write-files! (:dir ctx) {"dhf/spec/ids.edn" "{:issued"})
      (let [r (commit ctx)]
        (assert (not= 0 (:exit r)) "the commit was not refused")
        (assert (= head (k/git (:dir ctx) "rev-parse" "HEAD")))
        ctx))))
