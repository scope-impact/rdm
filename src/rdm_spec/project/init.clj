(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix P]`
  writes a new project's skeleton into a path that does not exist yet, makes
  it a git repository and installs the pre-commit hook (core.hooksPath set to
  .githooks). PATH is relative to the working directory, or absolute.

  The skeleton is the files under src/rdm_spec/project/skeleton/, each
  written to its destination (`skeleton`), with the system's name (the
  path's last part) and the id prefix filled in. It holds no feature file,
  no need and no source: a new project passes `rdm-spec check` before
  anything is written in it.

  The id prefix defaults to spec. It starts a tag: it starts with a letter
  and holds letters, digits and single hyphens, and it is not UN, which need
  tags start with.

  Cannot run (exit 2), writing nothing: a path that exists (a file, a
  directory or a symbolic link, even a broken one), a prefix that cannot
  start a tag, git missing.

  Result: {:init {:path PATH :wrote [path ...] :next-steps [\"...\"]}}, the
  paths relative to the new project."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def skeleton
  "Every file of the skeleton: [destination resource], in the order written.
  Dot files are kept under names without the dot, so they never act inside
  this repository."
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

(def executable #{".githooks/pre-commit"})

(def default-prefix "spec")

(defn prefix-problem
  "Pure: why `prefix` cannot start an id tag, or nil."
  [prefix]
  (cond
    (not (re-matches #"[A-Za-z](?:[A-Za-z0-9]|-(?=[A-Za-z0-9]))*" (str prefix)))
    (str "the prefix " (pr-str prefix) " cannot start a tag: a prefix starts with a letter and holds letters,"
         " digits and single hyphens")
    (= "UN" prefix)
    (str "the prefix UN is taken by need tags (@UN-nnn): choose another")))

(defn system-id
  "Pure: the model id of a system named `nm`."
  [nm]
  (let [s (-> (str/lower-case (str nm)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (keyword (if (re-matches #"[a-z].*" s) s (str "system-" s)))))

(defn render
  "Pure: the skeleton file `text` with its placeholders filled."
  [text {:keys [name id-prefix]}]
  (-> text
      (str/replace "{{system-id}}" (str (system-id name)))
      (str/replace "{{system-name}}" (pr-str (str name)))
      (str/replace "{{id-prefix}}" (pr-str (str id-prefix)))))

(defn skeleton-text
  "The text of the skeleton file `resource`, filled for `opts` ({:name :id-prefix})."
  [resource opts]
  (let [r (io/resource (str "rdm_spec/project/skeleton/" resource))]
    (when-not r (command/cannot-run! (str "the skeleton file " resource " is missing from rdm-spec")))
    (render (slurp r) opts)))

(defn write-file!
  "Writes the skeleton file `dest` of the project `dir`."
  [dir dest resource opts]
  (let [f (fs/file dir dest)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (skeleton-text resource opts))
    (when (executable dest) (fs/set-posix-file-permissions f "rwxr-xr-x"))))

(defn exists?
  "Whether something is at `f`: a file, a directory or a symbolic link, even a broken one."
  [f]
  (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(def next-steps
  ["Name the system in dhf/spec/model.edn and describe it."
   "Record each raw source in dhf/raw/ and dhf/raw/sources.edn, and write the user needs in dhf/spec/needs.edn."
   "Write each component's scenarios in dhf/spec/<context>/<component>.feature, then run rdm-spec stamp."
   "Run rdm-spec check; the pre-commit hook runs it on every commit."
   "Commit the skeleton."])

(defn init
  "The init task's stage."
  [req]
  (let [given (first (:args req))
        prefix (or (get-in req [:opts :id-prefix]) default-prefix)
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        target (fs/file (command/path req given))
        _ (when (exists? target)
            (command/cannot-run! (str "the path " given " already exists: init writes a new project into a path"
                                      " that does not exist (rdm-spec adopt adds the skeleton to a repository)")))
        _ (when-not (fs/which "git") (command/cannot-run! "git is not installed (not on PATH): a project is a git repository"))
        opts {:name (fs/file-name (fs/normalize (fs/absolutize target))) :id-prefix prefix}]
    (fs/create-dirs target)
    (when-not (hooks/init-repository! target)
      (fs/delete-tree target)
      (command/cannot-run! (str "git could not make " given " a repository")))
    (doseq [[dest resource] skeleton] (write-file! target dest resource opts))
    (hooks/install! target)
    {:result {:init {:path given :wrote destinations :next-steps next-steps}}
     :findings []}))
