(ns steps.project-init
  "Step definitions for dhf/spec/project/init.feature. Context keys: see
  steps.project-support."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.project-support :as ps]
            [steps.support :as s]))

(defstep #"no directory at the path \"([^\"]+)\""
  (fn [ctx target]
    (ps/needs-project!)
    (let [dir (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file dir target))) (str target " exists"))
      (assoc ctx :dir dir :target target))))

(defstep #"\"([^\"]+)\" is a git repository holding exactly:"
  (fn [ctx target table]
    (kc/exits! ctx 0)
    (s/check! (= (ps/skeleton-table) (vec (for [row (rest table)] (zipmap (first table) row))))
              "the table is not the skeleton's")
    (ps/skeleton-at! (fs/file (:dir ctx) target))
    nil))

(defstep #"init lists every file it wrote and the next steps"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          res (ps/result-of r :init)]
      (s/check! (= (ps/skeleton-paths) (set (:wrote res)))
                (str "init lists " (sort (:wrote res)) ", not the skeleton\n" (s/show r)))
      (s/check! (and (sequential? (:next-steps res)) (seq (:next-steps res)) (every? string? (:next-steps res)))
                (str "init lists no next steps\n" (s/show r)))
      nil)))

(defstep #"no directory at DIR/([^\s]+)"
  (fn [ctx target]
    (ps/needs-project!)
    (let [parent (s/tmp-dir)]
      (s/check! (not (fs/exists? (fs/file parent target))) (str target " exists"))
      (-> ctx
          (assoc :dir (s/tmp-dir) :target target)
          (assoc-in [:placeholders "DIR"] (str (fs/absolutize parent)))))))

(defstep #"DIR/([^\s]+) is a git repository holding the skeleton"
  (fn [ctx target]
    (kc/exits! ctx 0)
    (ps/skeleton-at! (fs/file (get-in ctx [:placeholders "DIR"]) target))
    (s/check! (not (fs/exists? (fs/file (:dir ctx) target))) "init wrote into the working directory too")
    nil))

(defstep #"a directory at the path \"([^\"]+)\""
  (fn [ctx target]
    (let [dir (s/tmp-dir)]
      (s/write! dir (str target "/notes.txt") "Something of its own.\n")
      (assoc ctx :dir dir :target target :before (ps/files-of (fs/file dir target))))))

(defstep #"it exits 2 and names the path"
  (fn [ctx] (kc/refused-naming! ctx (:target ctx)) nil))

(defstep #"nothing in \"([^\"]+)\" changes"
  (fn [ctx target]
    (s/check! (= (:before ctx) (ps/files-of (fs/file (:dir ctx) target))) (str "something in " target " changed"))
    nil))

(defn- prefix-of [ctx target]
  (let [r (kc/exits! ctx 0)
        f (fs/file (:dir ctx) target "dhf/harness/config.edn")]
    (s/check! (fs/exists? f) (str "no configuration was written\n" (s/show r)))
    (:id-prefix (edn/read-string (slurp (str f))))))

(defstep #"the harness configuration sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (let [p (prefix-of ctx "sauna")]
      (s/check! (= prefix p) (str "the configuration sets the id prefix to " (pr-str p))))
    nil))

(defstep #"it sets the id prefix to \"([^\"]+)\""
  (fn [ctx prefix]
    (let [p (prefix-of ctx "sauna")]
      (s/check! (= prefix p) (str "the configuration sets the id prefix to " (pr-str p))))
    nil))

(defstep #"it exits 2 and says (a prefix starts with a letter and holds letters, digits and single hyphens|the prefix \S+ is taken by need tags)"
  (fn [ctx says]
    (kc/refused-naming! ctx says)
    (s/check! (not (fs/exists? (fs/file (:dir ctx) "sauna"))) "init wrote something before refusing")
    nil))

(defstep #"a project just initialised at \"([^\"]+)\""
  (fn [ctx target]
    (ps/needs-project!)
    (let [parent (s/tmp-dir)
          r (kc/run! ctx parent ["init" target])]
      (s/check! (zero? (:exit r)) (str "rdm-spec init failed\n" (s/show r)))
      (assoc ctx :dir parent :target target))))

(defstep #"\"rdm-spec ([^\"]*)\" is run in (it again|it|\"[^\"]+\")"
  (fn [ctx line where]
    (let [ctx (if (str/starts-with? where "\"")
                (assoc ctx :dir (str (fs/file (:dir ctx) (subs where 1 (dec (count where))))))
                ctx)]
      (kc/run-line ctx line))))
