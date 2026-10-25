(ns rdm-spec.project.init
  "Init (dhf/spec/project/init.feature): `rdm-spec init PATH [--id-prefix P]`
  writes a new project's skeleton into a path that does not exist yet, makes
  it a git repository, installs the pre-commit hook (rdm-spec.project.hooks),
  and names every file it wrote and the next steps.

  The skeleton is the table of init.feature's first scenario: the spec (an
  empty model, event catalogue, needs, ledger at 0, glossary and the spec's
  conventions), the harness configuration, the raw and wiki directories,
  decisions, the hook, the cut workflow and .gitignore. Its texts are the
  templates under skeleton/, shared with Adopt (rdm-spec.project.adopt), which
  writes the same files into an existing repository through `write-skeleton!`,
  never overwriting one.

  Templates carry {{placeholders}}: name, name-edn, prefix, prefix-edn and
  system-id (see `vars-for`). A placeholder with no value is a bug, never
  written out."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.project.hooks :as hooks]))

(def default-id-prefix "spec")

(def skeleton
  "Every file of the skeleton, in the order written: [path template]."
  [["dhf/spec/model.edn" "model.edn.tmpl"]
   ["dhf/spec/events.edn" "events.edn.tmpl"]
   ["dhf/spec/needs.edn" "needs.edn.tmpl"]
   ["dhf/spec/ids.edn" "ids.edn.tmpl"]
   ["dhf/spec/glossary.md" "glossary.md.tmpl"]
   ["dhf/spec/README.md" "spec-README.md.tmpl"]
   ["dhf/harness/config.edn" "config.edn.tmpl"]
   ["dhf/raw/README.md" "raw-README.md.tmpl"]
   ["dhf/raw/sources.edn" "sources.edn.tmpl"]
   ["dhf/wiki/index.md" "wiki-index.md.tmpl"]
   ["dhf/decisions/README.md" "decisions-README.md.tmpl"]
   [hooks/hook-path "pre-commit.tmpl"]
   [".github/workflows/cut.yml" "cut.yml.tmpl"]
   [".gitignore" "gitignore.tmpl"]])

(def executable
  "The skeleton's files that are programs."
  #{hooks/hook-path})

(defn skeleton-paths [] (mapv first skeleton))

;; ---------------------------------------------------------------------------
;; The id prefix

(def prefix-rule
  "What a prefix must be, as the refusal says it."
  "a prefix starts with a letter and holds letters, digits and single hyphens")

(defn valid-prefix?
  "True when `p` can start a scenario tag @<p>-NNNN: an ASCII letter, then
  letters and digits, a hyphen only between two of them."
  [p]
  (boolean (and (string? p) (re-matches #"[A-Za-z](?:-?[A-Za-z0-9])*" p))))

;; ---------------------------------------------------------------------------
;; Templates

(defn- system-id
  "A keyword for the system, made from the project's name: lower case, every
  run of other characters one hyphen; :system when nothing is left."
  [name]
  (let [s (-> (str/lower-case (str name))
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-+|-+$" ""))]
    (keyword (cond (str/blank? s) "system"
                   (re-find #"^[0-9]" s) (str "system-" s)
                   :else s))))

(defn vars-for
  "The template values of a project named `name` whose ids are @<prefix>-NNNN."
  [name prefix]
  (let [one-line (str/replace (str name) #"\s*[\r\n]+\s*" " ")
        id (system-id name)]
    {:name one-line
     :name-edn (pr-str (str name))
     :prefix prefix
     :prefix-edn (pr-str prefix)
     :system-id (pr-str id)
     :system-id-kw id}))

(defn- template [file]
  (let [r (io/resource (str "rdm_spec/project/skeleton/" file))]
    (when-not r (throw (ex-info (str "the skeleton template " file " is missing") {})))
    (slurp r)))

(defn render
  "The text init writes at the skeleton path `rel`, with `vars`."
  [rel vars]
  (let [file (or (some (fn [[p t]] (when (= p rel) t)) skeleton)
                 (throw (ex-info (str rel " is not in the skeleton") {})))]
    (str/replace (template file) #"\{\{([a-z-]+)\}\}"
                 (fn [[_ k]]
                   (let [v (get vars (keyword k))]
                     (when (nil? v) (throw (ex-info (str "the template " file " needs " k) {})))
                     (str v))))))

(defn gitignore-lines
  "The lines init writes to .gitignore."
  []
  (vec (remove str/blank? (map str/trim (str/split-lines (template "gitignore.tmpl"))))))

;; ---------------------------------------------------------------------------
;; Writing

(defn- exists? [f] (or (fs/exists? f {:nofollow-links true}) (fs/sym-link? f)))

(defn- blocked-by
  "The first directory on the way from `root` to the file `rel` that is a
  link or not a directory, relative to root; nil when every one is a plain
  directory or not there yet."
  [root rel]
  (let [parts (butlast (str/split rel #"/"))]
    (loop [[p & more] parts acc []]
      (when p
        (let [acc (conj acc p)
              f (apply fs/file root acc)]
          (cond (fs/sym-link? f) (str/join "/" acc)
                (and (exists? f) (not (fs/directory? f {:nofollow-links true}))) (str/join "/" acc)
                :else (recur more acc)))))))

(defn write-skeleton!
  "Writes every skeleton file under `root` that is not there yet, in any form
  (a link included): those are kept. A file whose directory is a link or a
  file is skipped, never written through, with a warning. Returns {:wrote
  [rel] :kept [rel] :skipped [rel] :findings [warning]}."
  [root vars]
  (reduce
   (fn [out [rel _]]
     (let [f (fs/file root rel)]
       (cond
         (exists? f) (update out :kept conj rel)

         (blocked-by root rel)
         (let [b (blocked-by root rel)]
           (-> out
               (update :skipped conj rel)
               (update :findings conj
                       (command/warning rel nil "skeleton-skipped"
                                        (str rel " is not written: " b
                                             (if (fs/sym-link? (fs/file root b)) " is a link" " is not a directory"))))))

         :else
         (do (fs/create-dirs (fs/parent f))
             (spit f (render rel vars))
             (when (executable rel) (fs/set-posix-file-permissions f "rwxr-xr-x"))
             (update out :wrote conj rel)))))
   {:wrote [] :kept [] :skipped [] :findings []}
   skeleton))

(defn next-steps
  "What to do after init (in the new project at `path`) or adopt (nil)."
  [path]
  (vec (concat
        (when path [(str "cd " path)])
        ["describe the system in dhf/spec/model.edn"
         "record each raw source the spec rests on in dhf/raw/ and declare the user needs in dhf/spec/needs.edn"
         "write scenarios in dhf/spec/<context>/<component>.feature, then run \"rdm-spec stamp\" to give each its id"
         "run \"rdm-spec check\"; the pre-commit hook runs it on every commit"
         "commit the skeleton: git add -A && git commit"])))

;; ---------------------------------------------------------------------------
;; The task

(defn- refuse-path!
  "Cannot run unless `target` (given as `given`) can be made: it does not
  exist, and no directory on the way to it is a file."
  [given target]
  (when (exists? target)
    (command/cannot-run! (str "the path " given " already exists; init writes only into a path that does not exist yet"
                              " (\"rdm-spec adopt\" adds the skeleton to an existing repository)")))
  (when-let [blocker (->> (iterate fs/parent (fs/parent target))
                          (take-while some?)
                          (filter #(and (exists? %) (not (fs/directory? %))))
                          first)]
    (command/cannot-run! (str "the path " given " cannot be made: " blocker " is not a directory"))))

(defn init
  "The init task: `rdm-spec init PATH [--id-prefix P]`."
  [req]
  (let [given (first (:args req))
        prefix (get-in req [:opts :id-prefix] default-id-prefix)
        _ (when-not (valid-prefix? prefix)
            (command/cannot-run! (str "the id prefix " prefix " is refused: " prefix-rule)))
        target (fs/absolutize (fs/file (:root req) given))
        _ (refuse-path! given target)
        name (fs/file-name (fs/normalize target))]
    (try
      (fs/create-dirs target)
      (let [g (hooks/git target "init" "--quiet")]
        (when-not (zero? (:exit g))
          (command/cannot-run! (str "git could not make " given " a repository: " (:err g)))))
      (let [{:keys [wrote findings]} (write-skeleton! (str target) (vars-for name prefix))
            activated (hooks/activate! (str target))]
        {:result {:path given
                  :id-prefix prefix
                  :wrote wrote
                  :hooks-path (:hooks-path activated)
                  :next-steps (next-steps given)}
         :findings (vec (concat findings (:findings activated)))})
      (catch Throwable e
        ;; nothing is left half-written: the path did not exist before
        (try (fs/delete-tree target) (catch Exception _ nil))
        (throw e)))))
