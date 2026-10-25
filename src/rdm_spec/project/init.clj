(ns rdm-spec.project.init
  "The Init component of the project context (dhf/spec/project/init.feature):
  `rdm-spec init PATH [--id-prefix P]` writes a new project's skeleton into a
  path that does not exist yet, makes it a git repository and installs the
  pre-commit hook (rdm-spec.project.hooks).

  The skeleton (`skeleton`) is the table of init.feature's first scenario:
  each file rendered from a template under rdm_spec/project/skeleton/, its
  {{name}} the project directory's name and {{prefix}} the id prefix. Adopt
  (rdm-spec.project.adopt) writes the same files with `write-skeleton!`,
  keeping every destination that already exists.

  The id prefix defaults to spec; it starts with a letter and holds letters,
  digits and single hyphens, and it is never UN, which need tags use."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def skeleton
  "Every file of the skeleton, in the order init writes them:
  [{:path :template :exec?}]."
  [{:path "dhf/spec/model.edn" :template "model.edn.tmpl"}
   {:path "dhf/spec/events.edn" :template "events.edn.tmpl"}
   {:path "dhf/spec/needs.edn" :template "needs.edn.tmpl"}
   {:path "dhf/spec/ids.edn" :template "ids.edn.tmpl"}
   {:path "dhf/spec/glossary.md" :template "glossary.md.tmpl"}
   {:path "dhf/spec/README.md" :template "spec-README.md.tmpl"}
   {:path "dhf/harness/config.edn" :template "config.edn.tmpl"}
   {:path "dhf/harness/stack.md" :template "stack.md.tmpl"}
   {:path "dhf/raw/README.md" :template "raw-README.md.tmpl"}
   {:path "dhf/raw/sources.edn" :template "sources.edn.tmpl"}
   {:path "dhf/wiki/index.md" :template "wiki-index.md.tmpl"}
   {:path "dhf/decisions/README.md" :template "decisions-README.md.tmpl"}
   {:path hooks/hook-path :template "pre-commit.tmpl" :exec? true}
   {:path ".github/workflows/cut.yml" :template "cut.yml.tmpl"}
   {:path ".gitignore" :template "gitignore.tmpl"}])

(def default-prefix "spec")

;; ---------------------------------------------------------------------------
;; Templates

(defn template
  "The text of the skeleton template `name`."
  [name]
  (slurp (or (io/resource (str "rdm_spec/project/skeleton/" name))
             (command/cannot-run! (str "the skeleton template " name " is missing")))))

(defn- slug [s]
  (let [x (-> (str/lower-case (str s)) (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-+|-+$" ""))]
    (if (re-matches #"[a-z].*" x) x (str "system" (when (seq x) (str "-" x))))))

(defn vars
  "The template variables of a project named `name` with the id `prefix`."
  [name prefix]
  {"name" name
   "name-edn" (pr-str name)
   "prefix" prefix
   "prefix-edn" (pr-str prefix)
   "system-id" (str (keyword (slug name)))})

(defn render
  "`text` with every {{key}} of `vs` filled in."
  [text vs]
  (str/replace text #"\{\{([a-z-]+)\}\}" (fn [[m k]] (get vs k m))))

(defn content
  "The text of the skeleton file `entry` for the variables `vs`."
  [entry vs]
  (render (template (:template entry)) vs))

(defn gitignore-lines
  "The lines init writes to .gitignore."
  []
  (vec (remove str/blank? (str/split-lines (template "gitignore.tmpl")))))

;; ---------------------------------------------------------------------------
;; Writing

(defn exists?
  "True when something is at `f`: a file, a directory or a symbolic link,
  whether or not the link resolves."
  [f]
  (or (fs/sym-link? f) (fs/exists? f {:nofollow-links true})))

(defn write-skeleton!
  "Writes every skeleton file under `dir` for the variables `vs`, keeping a
  destination that exists: {:wrote [path] :kept [path]}."
  [dir vs]
  (reduce (fn [out {:keys [path exec?] :as entry}]
            (let [f (fs/file dir path)]
              (if (exists? f)
                (update out :kept conj path)
                (do (fs/create-dirs (fs/parent f))
                    (spit (str f) (content entry vs))
                    (when exec? (fs/set-posix-file-permissions f "rwxr-xr-x"))
                    (update out :wrote conj path)))))
          {:wrote [] :kept []}
          skeleton))

;; ---------------------------------------------------------------------------
;; The id prefix

(defn prefix-problem
  "Why `prefix` cannot be the id prefix, or nil."
  [prefix]
  (cond
    (not (and (string? prefix) (re-matches #"[A-Za-z](?:[A-Za-z0-9]|-(?=[A-Za-z0-9]))*" prefix)))
    (str "the id prefix " (pr-str (str prefix)) " cannot be used: a prefix starts with a letter and holds letters, "
         "digits and single hyphens")
    (= "UN" prefix)
    "the prefix UN is taken by need tags (@UN-nnn); choose another id prefix"))

;; ---------------------------------------------------------------------------
;; The task

(defn next-steps
  "What to do after init, for the project at `path` (as given)."
  [path]
  [(str "cd " path)
   "describe the system in dhf/spec/model.edn and the stack in dhf/harness/stack.md"
   "record a raw source in dhf/raw/ (sources.edn and its record) and cite it from a need in dhf/spec/needs.edn"
   "write scenarios in dhf/spec/<context>/<component>.feature, then run \"rdm-spec stamp\""
   "run \"rdm-spec check\"; the pre-commit hook runs it on every commit"])

(defn init
  "The init task's stage: see the namespace doc. Result {:init {:path
  :id-prefix :wrote [path] :next-steps [step]}}."
  [req]
  (let [path (first (:args req))
        prefix (let [p (get-in req [:opts :id-prefix])] (if (nil? p) default-prefix p))
        _ (when (str/blank? path) (command/cannot-run! "init needs a PATH: rdm-spec init PATH [--id-prefix P]"))
        _ (when-let [why (prefix-problem prefix)] (command/cannot-run! why))
        target (fs/absolutize (command/path req path))]
    (when (exists? target)
      (command/cannot-run! (str "the path " path " already exists: init writes a new project into a path that"
                                " does not exist; to add the skeleton to a repository, run \"rdm-spec adopt\" in it")))
    (fs/create-dirs target)
    (let [r (hooks/git target "init" "--quiet")]
      (when-not (zero? (:exit r))
        (command/cannot-run! (str "git init failed in " path ": " (:err r)))))
    (let [{:keys [wrote]} (write-skeleton! target (vars (str (fs/file-name target)) prefix))
          {:keys [findings]} (hooks/activate! (str target))]
      {:result {:init {:path path :id-prefix prefix :wrote wrote :next-steps (next-steps path)}}
       :findings findings})))
