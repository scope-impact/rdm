(ns steps.project-hooks
  "Step definitions for dhf/spec/project/hooks.feature: the pre-commit hook
  init installs, run by a real commit. The hook runs rdm-spec from PATH, so
  the scenario puts first on PATH an rdm-spec that logs its arguments and
  runs the room's code by path (steps.project-support/shim!). Context keys:
  see steps.project-support; :commits, the commits made, {:exit :err}."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defstep #"the pre-commit hook is installed"
  (fn [ctx]
    (ps/needs-project!)
    (let [parent (s/tmp-dir)
          log (str (fs/file (s/tmp-dir) "rdm-spec.log"))
          r (kc/run! ctx parent ["init" "sauna"])
          dir (str (fs/file parent "sauna"))]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/check! (= ".githooks" (ps/git! dir "config" "--get" "core.hooksPath")) "core.hooksPath is not .githooks")
      (assoc ctx :dir dir :hook-log log
             :env {"PATH" (str (ps/shim! log) java.io.File/pathSeparator (System/getenv "PATH"))
                   "GIT_CEILING_DIRECTORIES" parent}))))

(defn- commit!
  "Makes a commit of everything in the project, the hook running: {:exit :err :head-before :head-after}."
  [ctx msg]
  (let [dir (:dir ctx)
        head #(str/trim (:out (ps/git dir (:env ctx) "rev-parse" "--verify" "--quiet" "HEAD")))
        before (head)
        _ (ps/git! dir "add" "-A")
        r (ps/git dir (:env ctx) "commit" "-q" "-m" msg)]
    {:exit (:exit r) :err (:err r) :head-before before :head-after (head)}))

(defn- log-lines [ctx]
  (let [f (fs/file (:hook-log ctx))]
    (if (fs/exists? f) (vec (remove str/blank? (str/split-lines (slurp (str f))))) [])))

(defstep #"a commit is made"
  (fn [ctx]
    (assoc ctx :commits [(commit! ctx "The skeleton")])))

(defstep #"the hook runs \"rdm-spec ([^\"]+)\""
  (fn [ctx line]
    (let [c (last (:commits ctx))]
      (s/check! (= [line] (log-lines ctx)) (str "the hook ran rdm-spec with " (log-lines ctx) ", not " line))
      (s/check! (zero? (:exit c)) (str "the commit of a skeleton that passes the check was refused: " (:err c)))
      (s/check! (not= (:head-before c) (:head-after c)) "no commit was made")
      nil)))

(defstep #"the commit is refused when the check exits other than 0"
  (fn [ctx]
    ;; a needs file that is not EDN is a finding: the check exits 1
    (s/write! (:dir ctx) "dhf/spec/needs.edn" "{\"UN-001\" \n")
    (let [c (commit! ctx "A broken spec")]
      (s/check! (= 2 (count (log-lines ctx))) (str "the hook did not run the check again: " (log-lines ctx)))
      (s/check! (not (zero? (:exit c))) "the commit was made")
      (s/check! (= (:head-before c) (:head-after c)) "HEAD moved")
      (s/check! (str/includes? (:err c) "needs") (str "the refusal does not say why: " (:err c)))
      nil)))
