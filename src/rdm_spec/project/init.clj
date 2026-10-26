(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix
  P]` writes a new project's skeleton into a path that does not exist yet,
  makes it a git repository with no commit, and installs the pre-commit
  hook (core.hooksPath .githooks). The skeleton is spec-0086's table; its
  files are resources under rdm_spec/project/skeleton/, each with the
  placeholders {{id-prefix}}, {{system-id}} and {{system-name}}.

  The id prefix (default spec) starts with a letter and holds letters,
  digits and single hyphens; UN is taken by need tags.

  Cannot run (exit 2), writing nothing: PATH exists, a prefix that is not
  one. Result: {:init {:path PATH :wrote [path] :next-steps [..]}}."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-prefix "spec")

(def skeleton
  "[destination resource], in spec-0086's order."
  [["dhf/spec/model.edn" "spec/model.edn"]
   ["dhf/spec/events.edn" "spec/events.edn"]
   ["dhf/spec/needs.edn" "spec/needs.edn"]
   ["dhf/spec/ids.edn" "spec/ids.edn"]
   ["dhf/spec/glossary.md" "spec/glossary.md"]
   ["dhf/spec/README.md" "spec/README.md"]
   ["dhf/harness/config.edn" "harness/config.edn"]
   ["dhf/harness/stack.md" "harness/stack.md"]
   ["dhf/raw/README.md" "raw/README.md"]
   ["dhf/raw/sources.edn" "raw/sources.edn"]
   ["dhf/wiki/index.md" "wiki/index.md"]
   ["dhf/decisions/README.md" "decisions/README.md"]
   [".githooks/pre-commit" "githooks/pre-commit"]
   [".github/workflows/cut.yml" "workflows/cut.yml"]
   [".gitignore" "gitignore"]])

(def destinations (mapv first skeleton))

(def next-steps
  ["describe the system in dhf/spec/model.edn, and its user needs in dhf/spec/needs.edn, each resting on a source in dhf/raw/"
   "write scenarios in dhf/spec/<context>/<component>.feature, then run rdm-spec stamp"
   "declare the tests check's :command under :verify in dhf/harness/config.edn"
   "run rdm-spec check, and commit"])

(defn prefix-problem
  "Why `p` cannot be an id prefix, or nil."
  [p]
  (cond
    (= "UN" p) "the prefix UN is taken by need tags (@UN-nnn)"
    (not (re-matches #"[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*" (str p)))
    (str "the prefix " (pr-str p) " is not one: a prefix starts with a letter and holds letters, digits and single hyphens")
    :else nil))

(defn skeleton-text
  "The text of the skeleton resource `resource`, its placeholders filled
  from `opts` {:name :id-prefix}."
  [resource {:keys [name id-prefix]}]
  (let [r (io/resource (str "rdm_spec/project/skeleton/" resource))
        nm (str (or name "project"))
        id (keyword (or (not-empty (str/replace (str/lower-case nm) #"[^a-z0-9]+" "-")) "system"))]
    (when r
      (-> (slurp r)
          (str/replace "{{id-prefix}}" (pr-str (or id-prefix default-prefix)))
          (str/replace "{{system-id}}" (str id))
          (str/replace "{{system-name}}" (pr-str nm))))))

(defn exists?
  "Whether anything is at `f`: a file, a directory or a symbolic link, even
  a broken one."
  [f]
  (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(defn write-file!
  "Writes the skeleton's `resource` to `dest` under `root`."
  [root dest resource opts]
  (let [f (fs/file root dest)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (skeleton-text resource opts))
    (when (= dest hooks/hook-file)
      (fs/set-posix-file-permissions f "rwxr-xr-x"))
    dest))

(defn init
  "The init task's stage."
  [req]
  (let [target (first (:args req))
        prefix (or (get-in req [:opts :id-prefix]) default-prefix)
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        dir (command/path req target)
        _ (when (exists? dir)
            (command/cannot-run! (str target " exists: init writes a new project into a path that does not exist"
                                      " (rdm-spec adopt adds the skeleton to an existing repository)")))
        opts {:name (str (fs/file-name (fs/absolutize dir))) :id-prefix prefix}]
    (try
      (fs/create-dirs dir)
      (let [wrote (mapv (fn [[dest resource]] (write-file! dir dest resource opts)) skeleton)]
        (when-not (hooks/init-repository! dir)
          (command/cannot-run! (str target " cannot be made a git repository: git is needed")))
        (hooks/install! dir)
        {:result {:init {:path target :wrote wrote :next-steps next-steps}} :findings []})
      (catch Throwable e
        (fs/delete-tree dir)
        (throw e)))))
