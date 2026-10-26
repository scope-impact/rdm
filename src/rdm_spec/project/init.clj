(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix P]`
  writes a new project's skeleton into a path that does not exist, makes it
  a git repository and installs the pre-commit hook (core.hooksPath set to
  .githooks). Nothing is committed: the first commit is the project's.

  The skeleton is spec-0086's table, one resource under
  rdm_spec/project/skeleton/ per destination (`skeleton`, in the table's
  order). The configuration holds the id prefix, every directory the tasks
  read, the clean room's deny-list (the implementation, the measurements
  dhf/cycles, the generated site) and kept paths, the watched directories
  and the temporary directory, and no implementation paths.

  The id prefix (default spec) starts with a letter and holds letters,
  digits and single hyphens, and is not UN, which need tags take.

  Cannot run (exit 2), writing nothing: a path that exists (a file, a
  directory or a symbolic link), a malformed prefix.

  Result: {:init {:path PATH :id-prefix P :wrote [path] :next-steps [..]}}.

  Used by Adopt: skeleton, destinations, skeleton-text, write-file!,
  exists?, default-prefix, next-steps."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-prefix "spec")

(def skeleton
  "[destination resource] of every file of the skeleton, in spec-0086's order."
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
  ["Describe the system in dhf/spec/model.edn and record the raw sources in dhf/raw/"
   "Declare the user needs in dhf/spec/needs.edn, each citing a source"
   "Write scenarios in dhf/spec/<context>/<component>.feature, then run rdm-spec stamp"
   "Run rdm-spec check; the pre-commit hook runs it on every commit"
   "Make the first commit, and push it to a remote whose default branch the cut workflow tags"])

(defn prefix-problem
  "Why `p` cannot be the id prefix, or nil."
  [p]
  (cond
    (= "UN" p) "the prefix UN is taken by need tags (@UN-nnn): choose another"
    (not (re-matches #"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*" (str p)))
    (str "the prefix " (pr-str p) " is not one: a prefix starts with a letter and holds letters, digits and"
         " single hyphens")
    :else nil))

(defn- system-id [nm]
  (let [slug (-> (str/lower-case (str nm)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (keyword (if (re-matches #"[a-z][a-z0-9-]*" slug) slug "system"))))

(defn skeleton-text
  "The text of the skeleton's `resource` for a project named `:name` with
  the id prefix `:id-prefix`."
  [resource {:keys [name id-prefix]}]
  (let [r (or (io/resource (str "rdm_spec/project/skeleton/" resource))
              (command/cannot-run! (str "the skeleton's " resource " is missing from rdm-spec")))
        nm (if (str/blank? (str name)) "System" (str name))]
    (-> (slurp r)
        (str/replace "{{id-prefix}}" (pr-str (or id-prefix default-prefix)))
        (str/replace "{{system-id}}" (pr-str (system-id nm)))
        (str/replace "{{system-name}}" (pr-str nm)))))

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
    (when (str/starts-with? dest ".githooks/")
      (fs/set-posix-file-permissions f "rwxr-xr-x"))
    dest))

(defn init
  "The init task's stage."
  [req]
  (let [[path] (:args req)
        prefix (or (get-in req [:opts :id-prefix]) default-prefix)
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        target (command/path req path)
        _ (when (exists? target)
            (command/cannot-run! (str path " exists: init writes a new project into a path that does not exist"
                                      " (rdm-spec adopt adds the skeleton to an existing repository)")))
        opts {:name (str (fs/file-name target)) :id-prefix prefix}]
    (fs/create-dirs target)
    (let [wrote (mapv (fn [[dest resource]] (write-file! target dest resource opts)) skeleton)]
      (when-not (hooks/init-repository! target)
        (command/cannot-run! (str path " could not be made a git repository: init needs git on PATH")))
      (hooks/install! target)
      {:result {:init {:path path :id-prefix prefix :wrote wrote :next-steps next-steps}}
       :findings []})))
