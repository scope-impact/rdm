(ns steps.regeneration-support
  "Helpers the regeneration context's step files share (cut, fence,
  scopes). No step is defined here.

  A regeneration project is a fixture: a copy of the room's allow-list
  (steps.support/copy-of-room!, its configuration without :oracle), made a
  git repository on the branch main, with an implementation written from
  its own model (synthesize-implementation!) when a scenario needs one.

  Helpers: git!, git-try, repo!, commit!, tag!, origin!, tags, remote-tags,
  files-under, files-at, implementation-files, scope-prefixes,
  synthesize-implementation!, model-of, context-names, drop-level!."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [steps.kernel-command :as kc]
            [steps.support :as s]))

(def ^:private identity-env
  {"GIT_AUTHOR_NAME" "steps" "GIT_AUTHOR_EMAIL" "steps@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "steps" "GIT_COMMITTER_EMAIL" "steps@rdm-spec.invalid"})

(defn git-try
  "git `args` in `dir`: {:exit :out :err}, :out trimmed."
  [dir & args]
  (let [r (apply p/shell {:dir (str dir) :out :string :err :string :continue true :in ""
                          :extra-env identity-env}
                 "git" args)]
    (update r :out str/trim)))

(defn git!
  "git `args` in `dir`; fails the step on an error; the trimmed output."
  [dir & args]
  (let [r (apply git-try dir args)]
    (s/check! (zero? (:exit r)) (str "git " (str/join " " args) " failed in " dir ": " (:err r)))
    (:out r)))

(defn repo!
  "Makes `dir` a git repository on the branch main; returns `dir`."
  [dir]
  (git! dir "init" "-q" "-b" "main")
  (git! dir "config" "user.name" "steps")
  (git! dir "config" "user.email" "steps@rdm-spec.invalid")
  (git! dir "config" "commit.gpgsign" "false")
  (git! dir "config" "tag.gpgsign" "false")
  dir)

(defn commit!
  "Commits everything in `dir` (no hook runs); returns the commit."
  [dir msg]
  (git! dir "add" "-A" "-f" "--" ".")
  (git! dir "commit" "-q" "--no-verify" "--allow-empty" "-m" msg)
  (git! dir "rev-parse" "HEAD"))

(defn tag!
  "Tags HEAD of `dir` `tag`."
  [dir tag]
  (git! dir "tag" tag))

(defn origin!
  "A bare repository the project `dir` pushes main and its tags to, set as
  its origin with origin/HEAD naming main; returns the bare repository."
  [dir]
  (let [bare (str (fs/file (s/tmp-dir) "origin.git"))]
    (fs/create-dirs bare)
    (git! bare "init" "-q" "--bare" "-b" "main")
    (git! dir "remote" "add" "origin" bare)
    (git! dir "push" "-q" "origin" "main" "--tags")
    (git! dir "fetch" "-q" "origin")
    (git! dir "remote" "set-head" "origin" "main")
    bare))

(defn tags
  "The tags of the repository `dir`, sorted."
  [dir]
  (vec (sort (remove str/blank? (str/split-lines (git! dir "tag" "--list"))))))

(defn remote-tags
  "{tag commit} of the bare repository `bare`."
  [bare]
  (into (sorted-map)
        (for [t (remove str/blank? (str/split-lines (git! bare "tag" "--list")))]
          [t (git! bare "rev-parse" (str t "^{commit}"))])))

(defn files-under
  "{rel text} of every file under `dir`, outside .git."
  [dir]
  (into (sorted-map)
        (for [f (fs/glob dir "**" {:hidden true})
              :let [r (str/replace (str (fs/relativize dir f)) "\\" "/")]
              :when (and (fs/regular-file? f)
                         (not (or (= ".git" r) (str/starts-with? r ".git/"))))]
          [r (slurp (str f))])))

(defn files-at
  "{rel text} of every file of the commit `rev` in the repository `dir`."
  [dir rev]
  (into (sorted-map)
        (for [r (remove str/blank? (str/split-lines (git! dir "ls-tree" "-r" "--name-only" rev)))]
          [r (:out (p/shell {:dir (str dir) :out :string :err :string :continue true} "git" "show" (str rev ":" r)))])))

(defn under?
  "Whether the path `rel` is `p` or lies under it."
  [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn implementation-files
  "The keys of `files` ({rel text}) the configuration `cfg` counts as
  implementation."
  [cfg files]
  (into (sorted-map) (filter (fn [[r _]] (some #(under? r %) (:implementation cfg))) files)))

(defn- fill [pattern ctx comp]
  (-> pattern
      (str/replace "{context}" (str/replace (str ctx) "-" "_"))
      (str/replace "{component}" (str/replace (str (or comp "")) "-" "_"))))

(defn scope-prefixes
  "The path prefixes the scopes of a fence command's `args` remove, by the
  configuration `cfg`'s :code-layout."
  [cfg args]
  (let [vals (fn [flag] (mapcat #(str/split % #",")
                                (keep (fn [[a b]] (when (= flag a) b)) (partition 2 1 args))))]
    (vec (concat
          (for [c (vals "--context") p (get-in cfg [:code-layout :context])] (fill p c nil))
          (for [cn (vals "--component")
                :let [[c n] (str/split cn #"/" 2)]
                p (get-in cfg [:code-layout :component])]
            (fill p c n))))))

(defn removed?
  "Whether `rel` starts with one of `prefixes`."
  [prefixes rel]
  (boolean (some #(str/starts-with? rel %) prefixes)))

(defn model-of
  "The model of the project `dir`, read."
  [dir]
  (edn/read-string (slurp (str (fs/file dir (or (:model (kc/config-of dir)) "dhf/spec/model.edn"))))))

(defn context-names
  "The names of the model's bounded contexts."
  [m]
  (vec (for [c (:containers m) x (:contexts c)] (name (:id x)))))

(defn- components
  "[[context component]] of every component of the model, the kernel's
  under the context kernel."
  [m]
  (concat (for [c (:containers m) x (:contexts c) comp (:components x)]
            [(name (:id x)) (name (:id comp))])
          (for [comp (:kernel m)] ["kernel" (name (:id comp))])))

(defn synthesize-implementation!
  "Writes an implementation into the project `dir` from its own model and
  code layout: every component's code, unit test and step file, a support
  step file per context, and the other implementation paths; returns the
  files written ({rel text})."
  [dir]
  (let [cfg (kc/config-of dir)
        m (model-of dir)
        lay (:code-layout cfg)
        comps (components m)
        files (into (sorted-map)
                    (concat
                     (for [[c n] comps p (:component lay)
                           :let [rel (fill p c n)]]
                       [rel (str ";; " rel ", a fixture of " c "/" n "\n")])
                     (for [c (distinct (map first comps))
                           p (:context lay)
                           :let [prefix (fill p c nil)
                                 rel (if (str/ends-with? prefix "/")
                                       (str prefix "support.clj")
                                       (str prefix "support.clj"))]]
                       [rel (str ";; " rel ", a fixture of the context " c "\n")])
                     [["test.clj" ";; the fixture's tests\n(System/exit 0)\n"]
                      ["bb.edn" "{:paths [\"src\" \"test\"]}\n"]
                      ["test/steps/support.clj" ";; the fixture's step helpers\n"]]))]
    (doseq [[rel text] files] (s/write! dir rel text))
    files))

(defn drop-level!
  "Removes the level tag of the first stamped scenario of the project
  `dir`'s spec; returns the feature file's path."
  [dir]
  (let [f (first (filter #(re-find #"@spec-\d{4,} @(?:e2e|contract)" (slurp (str %)))
                         (sort (map str (fs/glob (fs/file dir "dhf/spec") "**.feature")))))
        _ (s/check! f "the project holds no stamped scenario with a level")
        text (slurp f)]
    (spit f (str/replace-first text #"(@spec-\d{4,}) @(?:e2e|contract)" "$1"))
    (str (fs/relativize dir f))))
