(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix
  P]` writes a new project's skeleton into a path that does not exist yet,
  makes it a git repository and installs the pre-commit hook (core.hooksPath
  .githooks, rdm-spec.project.hooks).

  The skeleton is spec-0086's table: each file rendered from a template
  under src/rdm_spec/project/skeleton/ (`skeleton`, [destination
  resource]), where {{id-prefix}}, {{system-id}} and {{system-name}} are
  the id prefix and the system named after the path. Adopt writes the same
  files (`write-file!`, `skeleton-text`, `exists?`, `destinations`,
  `next-steps`, `default-prefix`).

  The id prefix (default spec) starts a tag, @<prefix>-NNNN: it starts with
  a letter and holds letters, digits and single hyphens, and it is not UN,
  which the need tags (@UN-nnn) hold.

  Cannot run (exit 2), writing nothing: a PATH that exists (a symbolic link
  too, even a broken one), a prefix that cannot start a tag, a repository
  git cannot make.

  Result: {:init {:path PATH :id-prefix P :wrote [path] :hooks-path
  \".githooks\" :next-steps [..]}}."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-prefix "spec")

(def skeleton
  "[destination resource] of every file of the skeleton, in the order they
  are written."
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
  ["Record each raw source in dhf/raw/ and index it in dhf/raw/sources.edn"
   "Distil the domain into dhf/wiki/, every claim footnoted to a source"
   "Declare the user needs in dhf/spec/needs.edn, each citing its sources"
   "Draw the model in dhf/spec/model.edn and write each component's feature file"
   "Run rdm-spec stamp, then rdm-spec views, then rdm-spec check"
   "Commit: the pre-commit hook runs rdm-spec check on every commit"])

(defn- slug [s]
  (let [t (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (or (str/blank? t) (not (re-find #"^[a-z]" t))) (str "project" (when-not (str/blank? t) (str "-" t))) t)))

(defn skeleton-text
  "The text of the skeleton's template `resource`, rendered for `opts`
  ({:name :id-prefix})."
  [resource {:keys [name id-prefix]}]
  (let [r (or (io/resource (str "rdm_spec/project/skeleton/" resource))
              (command/cannot-run! (str "the skeleton's " resource " is not built")))]
    (-> (slurp r)
        (str/replace "{{id-prefix}}" (pr-str (or id-prefix default-prefix)))
        (str/replace "{{system-id}}" (str (keyword (slug name))))
        (str/replace "{{system-name}}" (pr-str (str (or name "project")))))))

(defn exists?
  "Whether something is at `f`: a file, a directory or a symbolic link,
  even a broken one."
  [f]
  (or (fs/exists? f) (fs/sym-link? f)))

(defn write-file!
  "Writes the skeleton's `resource` to `dest` under `root`; the hook is
  made executable."
  [root dest resource opts]
  (let [f (fs/file root dest)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) (skeleton-text resource opts))
    (when (= dest hooks/hook-file)
      (fs/set-posix-file-permissions f "rwxr-xr-x"))
    (str f)))

(defn prefix-problem
  "Why `p` cannot be an id prefix, or nil."
  [p]
  (cond
    (not (re-matches #"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*" (str p)))
    (str "the id prefix " (pr-str (str p)) " cannot start a tag @<prefix>-NNNN:"
         " a prefix starts with a letter and holds letters, digits and single hyphens")
    (= "UN" p)
    "the prefix UN is taken by need tags (@UN-nnn): choose another id prefix"))

(defn init
  "The init task's stage."
  [req]
  (let [given (first (:args req))
        prefix (or (get-in req [:opts :id-prefix]) default-prefix)
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        dir (command/path req given)
        _ (when (exists? dir)
            (command/cannot-run! (str given " exists: init writes the skeleton into a path that does not exist yet"
                                      " (rdm-spec adopt adds it to an existing repository)")))
        opts {:name (fs/file-name dir) :id-prefix prefix}]
    (try
      (fs/create-dirs dir)
      (when-not (hooks/init-repository! dir)
        (command/cannot-run! (str "git cannot make " given " a repository: the hook needs one")))
      (let [wrote (mapv (fn [[dest resource]] (write-file! dir dest resource opts) dest) skeleton)
            {:keys [hooks-path]} (hooks/install! dir)]
        {:result {:init {:path given :id-prefix prefix :wrote wrote :hooks-path hooks-path
                         :next-steps next-steps}}
         :findings []})
      (catch Throwable e
        (fs/delete-tree dir)
        (throw e)))))
