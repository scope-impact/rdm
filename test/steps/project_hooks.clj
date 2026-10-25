;; Step definitions for dhf/spec/project/hooks.feature. The hook is installed
;; by "rdm-spec init"; commits are made with git, with a stand-in rdm-spec first
;; on PATH that records how it was called and exits as the scenario says, so
;; the hook is verified whatever the real check finds.
(ns steps.project-hooks
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defn- stand-in!
  "A directory holding an executable rdm-spec that appends its arguments to
  `log` and exits with the code written in `code-file`."
  [log code-file]
  (let [bin (s/tmp-dir)
        f (fs/file bin "rdm-spec")]
    (spit f (str "#!/usr/bin/env bb\n"
                 "(spit " (pr-str (str log)) " (str (clojure.string/join \" \" *command-line-args*) \"\\n\") :append true)\n"
                 "(System/exit (parse-long (clojure.string/trim (slurp " (pr-str (str code-file)) "))))\n"))
    (fs/set-posix-file-permissions f "rwxr-xr-x")
    bin))

(defn- commit!
  "Stages everything and commits in `repo`, the stand-in rdm-spec exiting
  `code`. Returns git's {:exit :out :err}."
  [{:keys [repo bin code-file]} code message]
  (spit (str code-file) (str code))
  (s/write! repo "notes.md" (str message "\n"))
  (let [path (str/join java.io.File/pathSeparator
                       (concat [bin (str (fs/parent s/bb))] [(System/getenv "PATH")]))
        sh (fn [& args]
             (apply p/shell {:dir repo :out :string :err :string :continue true
                             :extra-env {"PATH" path}}
                    "git" "-c" "user.name=Builder" "-c" "user.email=builder@example.org" args))]
    (sh "add" "-A")
    (let [r (sh "commit" "-m" message)]
      {:exit (:exit r) :out (:out r) :err (:err r)})))

(defn- head [repo] (:out (ps/git repo "rev-parse" "--verify" "-q" "HEAD")))

(defn- calls [log] (if (fs/exists? log) (vec (str/split-lines (slurp (str log)))) []))

(defstep #"the pre-commit hook is installed"
  (fn [_]
    (let [dir (s/tmp-dir)
          r (s/rdm-spec! dir "init" "project")
          repo (str (fs/file dir "project"))
          log (str (fs/file dir "calls.log"))
          code-file (str (fs/file dir "exit-code"))]
      (s/check! (= 0 (:exit r)) (s/show r))
      {:repo repo :log log :code-file code-file :bin (str (stand-in! log code-file))})))

(defstep #"a commit is made"
  (fn [ctx]
    (assoc ctx :commit (commit! ctx 0 "first commit"))))

(defstep #"the hook runs \"rdm-spec check\""
  (fn [ctx]
    (let [c (:commit ctx)]
      (s/check! (= 0 (:exit c)) (str "the commit failed: " (pr-str c)))
      (s/check! (= ["check"] (calls (:log ctx))) (str "rdm-spec was called with " (pr-str (calls (:log ctx)))))
      (s/check! (head (:repo ctx)) "no commit was made"))))

(defstep #"the commit is refused when the check exits other than 0"
  (fn [ctx]
    (doseq [code [1 2]]
      (let [before (head (:repo ctx))
            n (count (calls (:log ctx)))
            c (commit! ctx code (str "a commit the check refuses with " code))]
        (s/check! (not= 0 (:exit c)) (str "the commit was made although the check exited " code))
        (s/check! (= before (head (:repo ctx))) "HEAD moved")
        (s/check! (= (inc n) (count (calls (:log ctx)))) "the check was not run")))))
