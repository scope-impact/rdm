(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix P]`
  writes a new project's skeleton into a path that does not exist yet, makes it
  a git repository and installs the pre-commit hook, then names every file it
  wrote and the next steps.

  The skeleton is the list init.feature's @spec-0086 tabulates; each file's
  text is a template under rdm_spec/project/skeleton/, its placeholders
  ({{name}}, {{name-edn}}, {{prefix}}, {{prefix-edn}}, {{system-id}}) filled
  from the project's name (its directory's name) and its id prefix. Adopt
  (rdm-spec.project.adopt) writes the same skeleton with `write-skeleton!`, which
  never overwrites: a destination that exists in any form, a symbolic link
  included, is kept.

  A path that already exists, a malformed id prefix, and a git that cannot
  run are refused (exit 2). The command publishes no event."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-id-prefix
  "The id prefix when init is given none: ids are @spec-NNNN."
  "spec")

(def clean-room-allow
  "The clean room's allow-list a new project starts with (@spec-0086): the
  spec, the harness configuration, the raw sources, the wiki, the views and
  the decisions."
  ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"])

(def skeleton
  "Every file of the skeleton, in the spec's order: [path template executable?]."
  [["dhf/spec/model.edn" "model.edn.tmpl" false]
   ["dhf/spec/events.edn" "events.edn.tmpl" false]
   ["dhf/spec/needs.edn" "needs.edn.tmpl" false]
   ["dhf/spec/ids.edn" "ids.edn.tmpl" false]
   ["dhf/spec/glossary.md" "glossary.md.tmpl" false]
   ["dhf/spec/README.md" "spec-README.md.tmpl" false]
   ["dhf/harness/config.edn" "config.edn.tmpl" false]
   ["dhf/raw/README.md" "raw-README.md.tmpl" false]
   ["dhf/raw/sources.edn" "sources.edn.tmpl" false]
   ["dhf/wiki/index.md" "wiki-index.md.tmpl" false]
   ["dhf/decisions/README.md" "decisions-README.md.tmpl" false]
   [hooks/hook-path "pre-commit.tmpl" true]
   [".github/workflows/cut.yml" "cut.yml.tmpl" false]
   [".gitignore" "gitignore.tmpl" false]])

(defn skeleton-paths
  "The paths of the skeleton, relative to the project's root."
  []
  (mapv first skeleton))

;; ---------------------------------------------------------------------------
;; Templates

(defn- template [file]
  (let [r (io/resource (str "rdm_spec/project/skeleton/" file))]
    (when-not r (command/cannot-run! (str "the skeleton template " file " is missing from rdm-spec")))
    (slurp r)))

(defn render
  "`text` with every {{key}} replaced by (vars :key). A placeholder `vars`
  does not fill cannot run: a skeleton never holds one."
  [text vars]
  (str/replace text #"\{\{([a-z][a-z0-9-]*)\}\}"
               (fn [[whole k]]
                 (let [v (get vars (keyword k))]
                   (when (nil? v)
                     (command/cannot-run! (str "the skeleton placeholder " whole " has no value")))
                   (str/re-quote-replacement (str v))))))

(defn gitignore-lines
  "The lines init writes to .gitignore."
  []
  (vec (remove str/blank? (map str/trim (str/split-lines (template "gitignore.tmpl"))))))

(defn- system-id
  "The model's system id for a project named `name`: a keyword of its words in
  lower case, joined by hyphens; :system when the name has none."
  [name]
  (let [s (-> (str/lower-case (str name))
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (keyword (cond (str/blank? s) "system"
                   (re-find #"^[0-9]" s) (str "system-" s)
                   :else s))))

(defn vars-for
  "The template values for a project named `name` with the id prefix `prefix`."
  [name prefix]
  {:name (str name)
   :name-edn (pr-str (str name))
   :prefix (str prefix)
   :prefix-edn (pr-str (str prefix))
   :system-id (str (system-id name))})

(defn valid-prefix?
  "True when `p` can start a scenario id tag, @<p>-NNNN: letters and digits,
  starting with a letter, words joined by single hyphens."
  [p]
  (boolean (and (string? p) (re-matches #"[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)*" p))))

(defn- content
  "The text of the skeleton file `rel` from `tmpl`, filled with `vars`."
  [rel tmpl vars]
  (let [text (render (template tmpl) vars)]
    (if (= rel command/config-file)
      ;; The allow-list is what @spec-0086 states, whatever the template says.
      (str/replace text #":allow\s*\[[^\]]*\]"
                   (str/re-quote-replacement (str ":allow " (pr-str clean-room-allow))))
      text)))

;; ---------------------------------------------------------------------------
;; Writing

(defn- exists-in-any-form? [f]
  (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(defn- blocked-by
  "The first ancestor of `rel` under `root` that exists and is not a
  directory, or nil."
  [root rel]
  (let [parts (butlast (str/split rel #"/"))]
    (first (for [i (range 1 (inc (count parts)))
                 :let [p (str/join "/" (take i parts))
                       f (fs/file root p)]
                 :when (and (exists-in-any-form? f) (not (fs/directory? f)))]
             p))))

(defn write-skeleton!
  "Writes every skeleton file under `root` that is not there yet, filled with
  `vars` (see vars-for). Never overwrites: a destination that exists in any
  form, a symbolic link included (even a dangling one), is kept; one whose
  directory is taken by a file is skipped, with a warning. Returns {:wrote
  [paths] :kept [paths] :skipped [paths] :findings [warnings]}, in the
  skeleton's order."
  [root vars]
  (reduce
   (fn [acc [rel tmpl exec?]]
     (let [f (fs/file root rel)]
       (cond
         (exists-in-any-form? f)
         (update acc :kept conj rel)

         (blocked-by root rel)
         (-> acc
             (update :skipped conj rel)
             (update :findings conj
                     (command/warning rel nil "skeleton-skipped"
                                      (str "not written: " (blocked-by root rel) " is a file, not a directory"))))

         :else
         (do (fs/create-dirs (fs/parent f))
             (spit f (content rel tmpl vars))
             (when exec? (fs/set-posix-file-permissions f "rwxr-xr-x"))
             (update acc :wrote conj rel)))))
   {:wrote [] :kept [] :skipped [] :findings []}
   skeleton))

(defn next-steps
  "What to do after the skeleton is written; `path`, when given, is where the
  new project is, relative to where init ran."
  [path]
  (vec (remove nil?
               [(when path (str "cd " (if (re-find #"\s" path) (pr-str path) path)))
                "Record each raw source the spec rests on in dhf/raw/: a provenance record and an entry in dhf/raw/sources.edn"
                "Declare the first user need in dhf/spec/needs.edn, citing a raw source"
                "Describe the system in dhf/spec/model.edn, then add its containers, bounded contexts and components"
                "Write each component's scenarios in dhf/spec/<context>/<component>.feature, then run rdm-spec stamp"
                "Run rdm-spec check: the pre-commit hook runs it on every commit"
                "Commit and push: the cut workflow runs rdm-spec cut on every push to the default branch"])))

;; ---------------------------------------------------------------------------
;; The task

(defn init
  "The init task: `rdm-spec init PATH [--id-prefix P]`."
  [req]
  (let [path (first (:args req))
        prefix (get-in req [:opts :id-prefix] default-id-prefix)
        _ (when (str/blank? path) (command/cannot-run! "init needs a PATH; usage: rdm-spec init PATH [--id-prefix P]"))
        _ (when-not (valid-prefix? prefix)
            (command/cannot-run! (str "the id prefix " (pr-str prefix) " cannot start an id tag @" prefix
                                      "-NNNN: use letters and digits, starting with a letter, words joined by single hyphens")))
        target (fs/normalize (fs/absolutize (command/path req path)))
        _ (when (exists-in-any-form? (fs/file (command/path req path)))
            (command/cannot-run! (str path " already exists: init writes only into a path that does not exist;"
                                      " to add the skeleton to an existing repository, run rdm-spec adopt in it")))
        r (hooks/git (:root req) "init" "--quiet" (str target))]
    (when-not (zero? (:exit r))
      (when (fs/exists? target) (fs/delete-tree target))
      (command/cannot-run! (str "git could not make " path " a repository: " (:err r))))
    (let [{:keys [wrote findings]} (write-skeleton! target (vars-for (fs/file-name target) prefix))
          activated (hooks/activate! target)]
      {:result {:path path
                :root (str target)
                :id-prefix prefix
                :wrote wrote
                :hooks-path (:hooks-path activated)
                :next-steps (next-steps path)}
       :findings (vec (concat findings (:findings activated)))})))
