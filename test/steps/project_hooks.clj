(ns steps.project-hooks
  "Steps of the component Hooks (dhf/spec/01-start-a-project/hooks.feature).
  The hook runs whatever rdm-spec is on PATH; here that is a stand-in that
  records its arguments and runs this build from its sources."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-shared :as k]))

(defstep #"the pre-commit hook is installed"
  (fn [ctx]
    (let [ctx (k/spec-project! ctx)
          bin (k/scratch! "bin-")
          log (str (:base ctx) "/rdm-spec.log")]
      (spit (str bin "/rdm-spec")
            (str "#!/bin/sh\necho \"$@\" >> " log "\nexec " (str/join " " (k/rdm-command-line)) " \"$@\"\n"))
      (fs/set-posix-file-permissions (str bin "/rdm-spec") "rwxr-xr-x")
      (-> ctx
          (assoc-in [:env "PATH"] (str bin ":" (System/getenv "PATH")))
          (assoc ::log log)))))

(defn commit-with-hooks [ctx]
  (k/sh ctx (:dir ctx) "git" "add" "-A")
  (k/sh ctx (:dir ctx) "git" "commit" "-q" "-m" "a change"))

(defstep #"a commit is made"
  (fn [ctx] (assoc ctx ::commit (commit-with-hooks ctx))))

(defstep #"the hook runs \"rdm-spec check\""
  (fn [ctx]
    (k/check! (zero? (:exit (::commit ctx))) "the commit was refused: " (:err (::commit ctx)))
    (k/check! (= "check" (str/trim (or (k/slurp-rel ctx (::log ctx)) ""))) "the hook ran " (pr-str (k/slurp-rel ctx (::log ctx))))
    ctx))

(defstep #"the commit is refused when the check exits other than 0"
  (fn [ctx]
    (k/write! ctx "dhf/spec/demo/thing.feature" "@UN-001\nFeature: Thing\n\n  @spec-0001\n  Scenario: No level\n    Given a thing\n")
    (let [head (k/git! ctx "rev-parse" "HEAD")
          r (commit-with-hooks ctx)]
      (k/check! (not (zero? (:exit r))) "the commit was made")
      (k/check! (= head (k/git! ctx "rev-parse" "HEAD")) "HEAD moved")
      ctx)))
