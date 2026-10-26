(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature: `rdm-spec init` run in
  a new directory the scenario makes (:dir), the skeleton checked against
  the table of spec-0086 (steps.project-support). The When is the kernel's
  shared one; \"rdm-spec check\" is run in \"sauna\" is the kernel's too.

  Context keys set here: :dir (where init runs), :target (the path init is
  given, as given), :before ({path text} of a directory before the run),
  :placeholders DIR (a new directory, for an absolute path)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defn- target [ctx t] (str (fs/file (:dir ctx) t)))

(defn- inited [ctx] (ps/result-of (kc/exits! ctx 0) :init))

(defn- config-at [dir] (edn/read-string (slurp (str (fs/file dir "dhf/harness/config.edn")))))

;; ---------------------------------------------------------------------------
;; Givens

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx t]
    (ps/needs-project!)
    (let [dir (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file dir t))) (str t " exists"))
      (assoc ctx :dir dir :target t))))

(defstep #"no directory at DIR/(\S+)"
  (fn [ctx t]
    (ps/needs-project!)
    (let [dir (s/tmp-dir)
          d (s/tmp-dir)]
      (assoc ctx :dir dir :target (str d "/" t) :placeholders (assoc (:placeholders ctx) "DIR" d)))))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx t]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str t "/notes.txt") "Our own notes.\n")
      (assoc ctx :dir dir :target t :before (ps/files-of (fs/file dir t))))))

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx t]
    (ps/needs-project!)
    (let [dir (s/tmp-dir)
          r (kc/run! ctx dir ["init" t])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir dir :target t))))

;; ---------------------------------------------------------------------------
;; Thens

(defstep #"\"([^\"]+)\" is a git repository with no commit yet, whose working tree holds exactly:"
  (fn [ctx t _table]
    (inited ctx)
    (let [d (target ctx t)]
      (ps/skeleton-at! d)
      (s/check! (not (zero? (:exit (ps/git d {} "rev-parse" "--verify" "--quiet" "HEAD"))))
                (str t " has a commit"))
      nil)))

(defstep #"DIR/(\S+) is a git repository holding the skeleton"
  (fn [ctx t]
    (inited ctx)
    (ps/skeleton-at! (str (fs/file (get-in ctx [:placeholders "DIR"]) t)))
    nil))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [res (inited ctx)]
      (s/check! (= (ps/skeleton-paths) (set (:wrote res))) (str "init lists " (sort (:wrote res))))
      (s/check! (and (sequential? (:next-steps res)) (seq (:next-steps res)) (every? string? (:next-steps res)))
                (str "init names no next steps: " (pr-str (:next-steps res))))
      nil)))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (kc/refused-naming! ctx (:target ctx)) nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx t]
    (s/check! (= (:before ctx) (ps/files-of (target ctx t))) (str t " changed"))
    nil))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (inited ctx)
    (let [c (config-at (target ctx "sauna"))]
      (s/check! (= p (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))
      nil)))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (inited ctx)
    (let [c (config-at (target ctx "sauna"))]
      (s/check! (= p (:id-prefix c)) (str "the id prefix is " (pr-str (:id-prefix c))))
      nil)))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx]
    (kc/refused-naming! ctx "starts with a letter" "letters, digits and single hyphens")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "sauna was written")
    nil))

(defstep #"it exits 2 and says the prefix UN is taken by need tags"
  (fn [ctx]
    (kc/refused-naming! ctx "UN" "need tags")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "sauna was written")
    nil))
