(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix
  P]` writes the skeleton into a path that does not exist yet, makes it a
  git repository with no commit yet, and installs the pre-commit hook
  (core.hooksPath set to .githooks).

  The skeleton is spec-0086's table: each destination and the resource
  under rdm_spec/project/skeleton/ it is written from, `{{id-prefix}}`,
  `{{system-id}}` and `{{system-name}}` filled in as EDN. Adopt writes the
  same files (write-file!, skeleton-text).

  The id prefix (default spec) starts with a letter and holds letters,
  digits and single hyphens, and is not UN, which need tags take.

  Cannot run (exit 2), writing nothing: a PATH that exists, a bad prefix.

  Result: {:init {:path PATH :wrote [path] :next-steps [step]}}."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-prefix "spec")

(def skeleton
  "[destination resource], in the order spec-0086 lists them."
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
  ["Describe the system in dhf/spec/model.edn, and record its raw sources in dhf/raw/"
   "Write the user needs in dhf/spec/needs.edn and the scenarios in dhf/spec/<context>/<component>.feature"
   "Run \"rdm-spec stamp\" to give each scenario its id, then \"rdm-spec check\""
   "Commit: the pre-commit hook runs \"rdm-spec check\" on every commit"])

(defn prefix-problem
  "Why `p` cannot be an id prefix, or nil."
  [p]
  (cond
    (= "UN" p) "the prefix UN is taken by need tags (@UN-nnn): choose another"
    (not (re-matches #"[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*" (str p)))
    (str "the prefix " (pr-str p) " cannot start a tag: a prefix starts with a letter and holds letters,"
         " digits and single hyphens")))

(defn- system-id [nm]
  (let [s (-> (str/lower-case (str nm)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (keyword (if (re-matches #"[a-z].*" s) s (str "system-" s)))))

(defn skeleton-text
  "The text of the skeleton resource `resource`, filled for `opts` {:name :id-prefix}."
  [resource {:keys [name id-prefix]}]
  (let [r (io/resource (str "rdm_spec/project/skeleton/" resource))]
    (when-not r (command/cannot-run! (str "the skeleton resource " resource " is not in this build")))
    (-> (slurp r)
        (str/replace "{{id-prefix}}" (pr-str (str (or id-prefix default-prefix))))
        (str/replace "{{system-id}}" (pr-str (system-id name)))
        (str/replace "{{system-name}}" (pr-str (str name))))))

(defn exists?
  "Whether anything is at `f`: a file, a directory, or a link, even a broken one."
  [f]
  (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(defn write-file!
  "Writes the skeleton's `dest` under `root` from `resource`; the hook is executable."
  [root dest resource opts]
  (let [f (fs/file (str root) dest)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (skeleton-text resource opts))
    (when (= hooks/hook-file dest)
      (fs/set-posix-file-permissions f "rwxr-xr-x"))
    (str f)))

(defn init
  "The init task's stage."
  [req]
  (let [p (str (first (:args req)))
        prefix (or (get-in req [:opts :id-prefix]) default-prefix)
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        target (command/path req p)
        _ (when (exists? target)
            (command/cannot-run! (str p " exists: init writes the skeleton into a path that does not exist"
                                      " (to add it to a repository, run rdm-spec adopt there)")))
        opts {:name (fs/file-name (fs/absolutize target)) :id-prefix prefix}]
    (try
      (fs/create-dirs target)
      (doseq [[dest resource] skeleton] (write-file! target dest resource opts))
      (when-not (hooks/init-repository! target)
        (command/cannot-run! (str "git cannot make " p " a repository: init needs git on PATH")))
      (hooks/install! target)
      (catch Exception e
        (fs/delete-tree target)
        (throw e)))
    {:result {:init {:path p :wrote destinations :next-steps next-steps}}
     :findings []}))
