(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature: `rdm-spec init` run in
  a new directory (:dir), the path it is given relative to it or, as DIR/...,
  absolute. The skeleton is the spec's table (steps.project-support).

  Context keys set here: :dir (where init runs), :target (the path given),
  :before ({path text} of the existing directory), :placeholders DIR."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defn- target [ctx nm] (str (fs/file (:dir ctx) nm)))

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx nm]
    (ps/needs-project!)
    (let [ctx (assoc ctx :dir (s/tmp-dir) :target nm)]
      (s/check! (not (fs/exists? (target ctx nm))) (str nm " exists"))
      ctx)))

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx nm _table]
    (kc/exits! ctx 0)
    (ps/skeleton-at! (target ctx nm))
    nil))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [res (ps/result-of (kc/exits! ctx 0) :init)]
      (s/check! (= (ps/skeleton-paths) (set (:wrote res))) (str "init lists " (pr-str (:wrote res))))
      (s/check! (and (sequential? (:next-steps res)) (seq (:next-steps res)) (every? string? (:next-steps res)))
                (str "init names no next steps: " (pr-str res)))
      nil)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx]
    (ps/needs-project!)
    (let [d (s/tmp-dir)]
      (s/check! (fs/absolute? d) "DIR is not absolute")
      (assoc ctx :dir (s/tmp-dir) :placeholders (assoc (:placeholders ctx) "DIR" d)))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (kc/exits! ctx 0)
    (ps/skeleton-at! (str (fs/file (get-in ctx [:placeholders "DIR"]) "sauna")))
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote into the working directory")
    nil))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx nm]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str nm "/notes.txt") "a file that was here first\n")
      (assoc ctx :dir dir :target nm :before (ps/files-of (fs/file dir nm))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (kc/refused-naming! ctx (:target ctx)) nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx nm]
    (kc/exits! ctx 2)
    (s/check! (= (:before ctx) (ps/files-of (target ctx nm))) (str nm " changed"))
    nil))

(defn- prefix-of [ctx]
  (let [r (kc/exits! ctx 0)
        f (fs/file (target ctx "sauna") "dhf/harness/config.edn")]
    (s/check! (fs/exists? f) (str "no configuration was written\n" (s/show r)))
    (:id-prefix (kc/config-of (target ctx "sauna")))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (s/check! (= p (prefix-of ctx)) (str "the id prefix is " (pr-str (prefix-of ctx))))
    nil))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx p]
    (s/check! (= p (prefix-of ctx)) (str "the id prefix is " (pr-str (prefix-of ctx))))
    nil))

(defstep #"it exits 2 and says (a prefix starts with a letter and holds letters, digits and single hyphens|the prefix UN is taken by need tags)"
  (fn [ctx says]
    (kc/refused-naming! ctx says)
    (s/check! (not (fs/exists? (target ctx "sauna"))) "init wrote the project")
    nil))

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx nm]
    (ps/needs-project!)
    (let [dir (s/tmp-dir)
          r (kc/run! ctx dir ["init" nm])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir dir :target nm))))

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line nm]
    (assoc ctx :run (kc/run! ctx (target ctx nm) (s/split-args line)))))
