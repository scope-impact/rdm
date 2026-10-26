(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix P]`
  writes a new project's skeleton into a path that does not exist yet, makes
  it a git repository with no commit, and installs the pre-commit hook
  (rdm-spec.project.hooks).

  The skeleton is the resources under rdm_spec/project/skeleton/, each
  written to its destination (`skeleton`), with {{id-prefix}},
  {{system-id}} and {{system-name}} filled in: the prefix, and the system
  named after the path's last name.

  Cannot run (exit 2), writing nothing: the path exists (a broken symbolic
  link too), or the prefix does not start with a letter and hold letters,
  digits and single hyphens, or is UN, which need tags take.

  Result: {:init {:path P :wrote [path] :next-steps [..]}}.

  API for adopt: default-prefix, skeleton, destinations, exists?,
  skeleton-text, write-file!, next-steps."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-prefix "spec")

(def skeleton
  "[destination resource], in the order init writes them."
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
  ["Name the system in dhf/spec/model.edn and describe it"
   "Record the raw sources in dhf/raw/sources.edn and write the domain wiki under dhf/wiki/"
   "Declare the user needs in dhf/spec/needs.edn and write the feature files under dhf/spec/"
   "Run rdm-spec stamp, then rdm-spec check"
   "Set the :tests check's command in dhf/harness/config.edn's :verify"
   "Commit: the pre-commit hook runs rdm-spec check on every commit"])

(defn prefix-problem
  "Why `p` cannot be an id prefix, or nil."
  [p]
  (cond
    (= "UN" p) (str "the prefix UN is taken by need tags (@UN-nnn): choose another")
    (not (re-matches #"[A-Za-z][A-Za-z0-9-]*" (str p)))
    (str "the prefix " (pr-str p) " cannot start a tag: a prefix starts with a letter and holds letters, digits and single hyphens")
    (or (str/includes? p "--") (str/ends-with? p "-"))
    (str "the prefix " (pr-str p) " cannot start a tag: a prefix starts with a letter and holds letters, digits and single hyphens")
    :else nil))

(defn system-id
  "The model's id of a system named `nm`."
  [nm]
  (let [s (-> (str/lower-case (str nm)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (keyword (if (str/blank? s) "project" s))))

(def ^:private ns-dir (some-> *file* str not-empty fs/absolutize fs/parent str))

(defn skeleton-text
  "The text of the skeleton's `resource`, filled for `opts` {:name :id-prefix}."
  [resource {:keys [name id-prefix]}]
  (let [src (or (io/resource (str "rdm_spec/project/skeleton/" resource))
                (io/file ns-dir "skeleton" resource))]
    (-> (slurp src)
        (str/replace "{{id-prefix}}" (pr-str (or id-prefix default-prefix)))
        (str/replace "{{system-id}}" (str (system-id name)))
        (str/replace "{{system-name}}" (pr-str (str name))))))

(defn exists?
  "Whether anything is at `f`: a file, a directory or a symbolic link, even a broken one."
  [f]
  (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(defn write-file!
  "Writes the skeleton's `resource` to `dest` under `root`."
  [root dest resource opts]
  (let [f (fs/file root dest)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (skeleton-text resource opts))
    (when (= ".githooks/pre-commit" dest)
      (fs/set-posix-file-permissions f "rwxr-xr-x"))
    dest))

(defn init
  "The init task's stage."
  [req]
  (let [p (first (:args req))
        prefix (or (get-in req [:opts :id-prefix]) default-prefix)
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        target (command/path req p)]
    (when (exists? target)
      (command/cannot-run! (str p " exists: init writes a new project into a path that does not exist"
                                " (rdm-spec adopt adds the skeleton to an existing repository)")))
    (fs/create-dirs target)
    (when-not (hooks/init-repository! target)
      (command/cannot-run! (str p " cannot be made a git repository: is git installed?")))
    (let [opts {:name (fs/file-name target) :id-prefix prefix}
          wrote (mapv (fn [[dest resource]] (write-file! target dest resource opts)) skeleton)]
      (hooks/install! target)
      {:result {:init {:path p :wrote wrote :next-steps next-steps}}
       :findings []})))
