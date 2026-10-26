(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature: `rdm-spec init` into
  a path of a directory the scenario makes, and the project context's
  Whens that run the command in a named directory (\"rdm-spec LINE\" is run
  in \"P\", is run in it, is run in it again), which adopt.feature shares.

  The skeleton is spec-0086's table, read from the room's spec
  (steps.project-support). Context keys: see steps.project-support; :dir
  is where init runs, :target the path it is given, :before the files of
  an existing target, and :placeholders DIR (a directory of its own, so a
  path under it is absolute)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defn- target [ctx] (str (fs/file (:dir ctx) (or (:target ctx) (s/fail! "no path was named")))))

;; ---------------------------------------------------------------------------
;; Running the command in a directory

(defstep #"\"rdm-spec ([^\"]*)\" is run in \"([^\"]+)\""
  (fn [ctx line sub]
    (let [dir (or (:dir ctx) (s/fail! "no project to run in"))]
      (kc/run-line (assoc ctx :dir (str (fs/file dir sub))) line))))

(defstep #"\"rdm-spec ([^\"]*)\" is run in it(?: again)?"
  (fn [ctx line]
    (s/check! (:dir ctx) "no directory to run in")
    (kc/run-line ctx line)))

;; ---------------------------------------------------------------------------
;; Init starts a project from nothing

(defn- no-directory! [ctx rel]
  (ps/needs-project!)
  (let [dir (s/tmp-dir)]
    (s/check! (not (fs/exists? (fs/file dir rel))) (str rel " exists"))
    (assoc ctx :dir dir :target rel)))

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx rel] (no-directory! ctx rel)))

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx rel table]
    (kc/exits! ctx 0)
    (s/check! (= (set (map first (rest table))) (ps/skeleton-paths)) "the table is not spec-0086's")
    (ps/skeleton-at! (fs/file (:dir ctx) rel))
    nil))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          {:keys [wrote next-steps]} (ps/result-of r :init)]
      (s/check! (= (ps/skeleton-paths) (set wrote)) (str "init lists " (pr-str wrote)))
      (s/check! (= (count wrote) (count (distinct wrote))) "a file is listed twice")
      (s/check! (and (sequential? next-steps) (seq next-steps) (every? string? next-steps))
                (str "init lists no next steps: " (pr-str next-steps)))
      nil)))

(defstep #"no directory at DIR/sauna"
  (fn [ctx]
    (ps/needs-project!)
    (let [d (s/tmp-dir)]
      (-> ctx
          (assoc :dir (s/tmp-dir))
          (assoc-in [:placeholders "DIR"] d)))))

(defstep #"DIR/sauna is a git repository holding the skeleton"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [d (get-in ctx [:placeholders "DIR"])]
      (s/check! (fs/absolute? d) "DIR is not absolute")
      (ps/skeleton-at! (fs/file d "sauna"))
      (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote under the working directory"))
    nil))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx rel]
    (let [ctx (no-directory! ctx rel)]
      (s/write! (target ctx) "notes.txt" "a file that was here first\n")
      (assoc ctx :before (ps/files-of (target ctx))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (kc/refused-naming! ctx (:target ctx)) nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx _]
    (kc/exits! ctx 2)
    (s/check! (= (:before ctx) (ps/files-of (target ctx))) "the directory changed")
    (s/check! (not (fs/exists? (fs/file (target ctx) ".git"))) "the directory was made a repository")
    nil))

(defn- prefix-of [ctx]
  (let [rel (str (fs/file (:dir ctx) "sauna" "dhf/harness/config.edn"))]
    (s/check! (fs/regular-file? rel) (str "no configuration was written\n" (s/show (kc/the-run ctx))))
    (:id-prefix (edn/read-string (slurp rel)))))

(defstep #"(?:the harness configuration|it) sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (kc/exits! ctx 0)
    (s/check! (= prefix (prefix-of ctx)) (str "the id prefix is " (pr-str (prefix-of ctx))))
    nil))

(defstep #"it exits 2 and says a prefix starts with a letter and holds letters, digits and single hyphens"
  (fn [ctx]
    (kc/refused-naming! ctx "9lives" "starts with a letter" "letters, digits and single hyphens")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote sauna")
    nil))

(defstep #"it exits 2 and says the prefix UN is taken by need tags"
  (fn [ctx]
    (kc/refused-naming! ctx "UN" "need tags")
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote sauna")
    nil))

;; ---------------------------------------------------------------------------
;; A new project is green

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx rel]
    (let [ctx (no-directory! ctx rel)
          r (kc/run! ctx (:dir ctx) ["init" rel])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      ctx)))
