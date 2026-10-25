(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix P]`
  writes a new project's skeleton into a path that does not exist yet, makes it
  a git repository and installs the pre-commit hook (rdm-spec.project.hooks).

  The skeleton is the table of init.feature's first scenario: each file is
  written from a template under rdm_spec/project/skeleton/, filled with the
  project's name (the path's last part), its system id and its id prefix.
  Adopt (rdm-spec.project.adopt) writes the same skeleton into an existing
  repository with `write-skeleton!`, which never overwrites a destination that
  exists in any form, a symbolic link included.

  The result names every file written, in the skeleton's order, and the next
  steps. A path that exists, in any form, is refused (exit 2) before anything is
  written, and so is an id prefix that could not start a tag: a prefix starts
  with a letter and holds letters, digits and single hyphens."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-id-prefix "spec")

(def skeleton
  "Every file of the skeleton, in the order the spec lists it: its path, its
  template, and whether it is executable."
  [{:path "dhf/spec/model.edn" :template "model.edn.tmpl"}
   {:path "dhf/spec/events.edn" :template "events.edn.tmpl"}
   {:path "dhf/spec/needs.edn" :template "needs.edn.tmpl"}
   {:path "dhf/spec/ids.edn" :template "ids.edn.tmpl"}
   {:path "dhf/spec/glossary.md" :template "glossary.md.tmpl"}
   {:path "dhf/spec/README.md" :template "spec-README.md.tmpl"}
   {:path "dhf/harness/config.edn" :template "config.edn.tmpl"}
   {:path "dhf/raw/README.md" :template "raw-README.md.tmpl"}
   {:path "dhf/raw/sources.edn" :template "sources.edn.tmpl"}
   {:path "dhf/wiki/index.md" :template "wiki-index.md.tmpl"}
   {:path "dhf/decisions/README.md" :template "decisions-README.md.tmpl"}
   {:path hooks/hook-path :template "pre-commit.tmpl" :executable? true}
   {:path ".github/workflows/cut.yml" :template "cut.yml.tmpl"}
   {:path ".gitignore" :template "gitignore.tmpl"}])

(def skeleton-paths (mapv :path skeleton))

(defn- template [name]
  (let [r (io/resource (str "rdm_spec/project/skeleton/" name))]
    (when-not r
      (command/cannot-run! (str "the skeleton template " name " is missing from rdm-spec")))
    (slurp r)))

(defn gitignore-lines
  "The lines Init writes to .gitignore, blank lines left out."
  []
  (vec (remove str/blank? (map str/trim (str/split-lines (template "gitignore.tmpl"))))))

;; ---------------------------------------------------------------------------
;; The id prefix

(def prefix-rule "a prefix starts with a letter and holds letters, digits and single hyphens")

(defn valid-prefix?
  "True when `p` can start a scenario id tag: a letter, then letters, digits
  and single hyphens, never ending with a hyphen."
  [p]
  (boolean (and (string? p) (re-matches #"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*" p))))

(defn require-prefix!
  "`p`, or cannot run when it could not start a tag."
  [p]
  (when-not (valid-prefix? p)
    (command/cannot-run! (str "the id prefix " (pr-str p) " is refused: " prefix-rule)))
  p)

;; ---------------------------------------------------------------------------
;; Filling the templates

(defn system-id
  "The model's id for a system named `name`: a keyword of its lowercase
  letters and digits joined by single hyphens, starting with a letter."
  [name]
  (let [slug (-> (str/lower-case (str name))
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))
        slug (cond (str/blank? slug) "system"
                   (re-find #"^[a-z]" slug) slug
                   :else (str "system-" slug))]
    (keyword slug)))

(defn vars-for
  "The values the templates are filled with, for a project named `name` with
  the id prefix `prefix`."
  [name prefix]
  {"name" (str name)
   "name-edn" (pr-str (str name))
   "system-id" (pr-str (system-id name))
   "prefix" (str prefix)
   "prefix-edn" (pr-str (str prefix))})

(defn fill
  "The template text `t` with every {{key}} replaced by its value in `vars`;
  a key with no value is left as written."
  [t vars]
  (str/replace t #"\{\{([a-z-]+)\}\}" (fn [[whole k]] (get vars k whole))))

;; ---------------------------------------------------------------------------
;; Writing

(defn- exists-in-any-form? [f]
  (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(defn- blocked-by
  "The ancestor of `rel` under `root` that exists and is not a directory, or nil."
  [root rel]
  (let [parts (butlast (str/split rel #"/"))]
    (some (fn [n]
            (let [p (str/join "/" (take n parts))
                  f (fs/file root p)]
              (when (and (exists-in-any-form? f) (not (fs/directory? f))) p)))
          (range 1 (inc (count parts))))))

(defn write-skeleton!
  "Writes every file of the skeleton under `root`, filled with `vars`, never
  overwriting: a destination that exists in any form is kept, and one that
  cannot be written because a parent path is a file is skipped, with a
  warning. Returns {:wrote [path] :kept [path] :skipped [path] :findings [..]},
  each in the skeleton's order."
  [root vars]
  (reduce
   (fn [acc {tmpl :template :keys [path executable?]}]
     (let [f (fs/file root path)]
       (cond
         (exists-in-any-form? f)
         (update acc :kept conj path)

         (blocked-by root path)
         (-> acc
             (update :skipped conj path)
             (update :findings conj (command/warning path nil "skeleton-skipped"
                                                     (str "not written: " (blocked-by root path)
                                                          " is a file, not a directory"))))

         :else
         (do (fs/create-dirs (fs/parent f))
             (spit f (fill (template tmpl) vars))
             (when executable?
               (fs/set-posix-file-permissions f "rwxr-xr-x"))
             (update acc :wrote conj path)))))
   {:wrote [] :kept [] :skipped [] :findings []}
   skeleton))

(defn next-steps
  "What to do after the skeleton is written; `path` is the new project's
  path as given, or nil when the skeleton was added where the command ran."
  [path]
  (vec (concat
        (when path [(str "cd " path)])
        ["describe the system in dhf/spec/model.edn"
         "record the raw sources in dhf/raw/ and distil the domain into dhf/wiki/"
         "declare the user needs in dhf/spec/needs.edn, each citing a source"
         "write scenarios in dhf/spec/<context>/<component>.feature, then run \"rdm-spec stamp\""
         "run \"rdm-spec check\"; the pre-commit hook runs it on every commit"
         "commit and push: the cut workflow tags each push to the default branch"])))

(defn init
  "The init task: `rdm-spec init PATH [--id-prefix P]`."
  [req]
  (let [[path] (:args req)
        prefix (require-prefix! (get-in req [:opts :id-prefix] default-id-prefix))
        target (command/path req path)]
    (when (exists-in-any-form? target)
      (command/cannot-run! (str path " already exists: init writes only into a path that does not exist"
                                " (\"rdm-spec adopt\" adds the skeleton to an existing repository)")))
    (when-let [blocked (some #(when (and (exists-in-any-form? %) (not (fs/directory? %))) %)
                             (take-while some? (rest (iterate fs/parent (fs/absolutize target)))))]
      (command/cannot-run! (str "cannot write " path ": " blocked " is not a directory")))
    (fs/create-dirs target)
    (let [r (hooks/git target "init" "--quiet")]
      (when-not (zero? (:exit r))
        (command/cannot-run! (str "git init failed in " path ": " (:err r)))))
    (let [root (str (fs/canonicalize target))
          name (fs/file-name root)
          {:keys [wrote findings]} (write-skeleton! root (vars-for name prefix))
          activated (hooks/activate! root)]
      {:result {:root root
                :id-prefix prefix
                :wrote wrote
                :hooks-path (:hooks-path activated)
                :next-steps (next-steps path)}
       :findings (vec (concat findings (:findings activated)))})))
