(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature: `rdm-spec init` in a
  new directory of the scenario's own; and the project context's Whens that
  run the command in a repository or a path (adopt.feature too). Context
  keys: see steps.project-support; :placeholders \"DIR\" (spec-0272)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defn- target-dir [ctx t] (str (fs/file (:dir ctx) t)))

;; ---------------------------------------------------------------------------
;; The project context's Whens

(defstep #"\"rdm-spec ([^\"]*)\" is run in it(?: again)?"
  (fn [ctx line]
    (s/check! (:dir ctx) "no repository to run in")
    (kc/run-line ctx line)))

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line sub]
    (let [ctx2 (kc/run-line (assoc ctx :dir (target-dir ctx sub)) line)]
      (assoc ctx2 :dir (:dir ctx)))))

;; ---------------------------------------------------------------------------
;; Init starts a project from nothing

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx t]
    (let [ctx (assoc ctx :dir (or (:dir ctx) (s/tmp-dir)) :target t)]
      (s/check! (not (fs/exists? (target-dir ctx t))) (str t " exists"))
      ctx)))

(defstep #"\"([^\"]+)\" is a git repository with no commit yet, whose working tree holds exactly:"
  (fn [ctx t table]
    (kc/exits! ctx 0)
    (let [dir (target-dir ctx t)
          want (set (map first (rest table)))]
      (s/check! (= want (ps/skeleton-paths)) "the table is not spec-0086's")
      (ps/skeleton-at! dir)
      (s/check! (not (zero? (:exit (ps/git dir {} "rev-parse" "-q" "--verify" "HEAD")))) (str t " has a commit"))
      nil)))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [out (ps/result-of (kc/the-run ctx) :init)]
      (s/check! (= (ps/skeleton-paths) (set (:wrote out))) (str "init lists " (pr-str (:wrote out))))
      (s/check! (and (sequential? (:next-steps out)) (seq (:next-steps out))) "init names no next steps")
      nil)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx]
    (assoc ctx :dir (s/tmp-dir) :placeholders (assoc (:placeholders ctx) "DIR" (s/tmp-dir)))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (kc/exits! ctx 0)
    (ps/skeleton-at! (str (fs/file (get-in ctx [:placeholders "DIR"]) "sauna")))
    nil))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx t]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str t "/notes.txt") "already here\n")
      (assoc ctx :dir dir :target t :before (ps/files-of (str (fs/file dir t)))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (kc/refused-naming! ctx (:target ctx)) nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx t]
    (s/check! (= (:before ctx) (ps/files-of (target-dir ctx t))) (str t " changed"))
    nil))

(defn- prefix-of [ctx t]
  (kc/exits! ctx 0)
  (:id-prefix (edn/read-string (slurp (str (fs/file (target-dir ctx t) "dhf/harness/config.edn"))))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (s/check! (= p (prefix-of ctx "sauna")) (str "the id prefix is " (pr-str (prefix-of ctx "sauna"))))
    nil))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (s/check! (= p (prefix-of ctx "sauna")) (str "the id prefix is " (pr-str (prefix-of ctx "sauna"))))
    nil))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx]
    (kc/refused-naming! ctx "starts with a letter" "letters, digits and single hyphens")
    (s/check! (not (fs/exists? (target-dir ctx "sauna"))) "init wrote sauna")
    nil))

(defstep #"it exits 2 and says the prefix UN is taken by need tags"
  (fn [ctx]
    (kc/refused-naming! ctx "UN" "need tags")
    (s/check! (not (fs/exists? (target-dir ctx "sauna"))) "init wrote sauna")
    nil))

;; ---------------------------------------------------------------------------
;; A new project is green before anything is written in it

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx t]
    (let [dir (s/tmp-dir)
          r (kc/run! ctx dir ["init" t])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (s/hooks-off! (str (fs/file dir t)))
      (assoc ctx :dir dir :target t))))
