(ns rdm-spec.regeneration.fence
  "Fence (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C,...] [--component C/N,...]` builds a clean room from a cut.

  In order, each refusal a cannot-run (exit 2) that leaves no room behind:
    1. the project must be a git repository, and CUT must name a commit that
       carries a spec-N tag (a cut); any other commit, or a name that is no
       commit, is refused: regeneration reads only a cut.
    2. the configuration read is the cut's (dhf/harness/config.edn as the
       cut holds it), never the working tree's: :clean-room :allow lists the
       allowed paths, and every one must be held by the cut (each missing one
       is named).
    3. the scope: none given is the project, which keeps no implementation.
       --context and --component (each a comma-separated list, repeatable)
       name bounded contexts and components of the cut's model (kernel
       counts as a context, its components as kernel/<name>); one the model
       does not name is refused, naming it and what the model does name.
       The configuration's :code-layout turns the scope into path prefixes
       ({context} and {component} written with - as _); every file at the
       cut under an :implementation path is kept unless it starts with one
       of them.
    4. ROOM must not exist, or be an empty directory.

  The room is then one fresh repository: the allowed paths and the kept
  implementation, exactly as the cut holds them, written straight from the
  cut's tree (never through a clone, so no other object of the history
  reaches it) and committed as a single root commit. Git runs in the room
  with none of the caller's GIT_DIR-like variables, so no alternate object
  store can leak in.

  Fence knows nothing of the oracle: the command runs it as a gate before."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; The cut

(defn resolve-cut
  "{:commit :tag} of the cut `arg` names in the repository `dir`; cannot run
  unless it names a commit carrying a spec-N tag."
  [dir arg]
  (let [commit (cut/rev dir arg)
        _ (when-not commit
            (command/cannot-run! (str "regeneration reads only a cut, a commit tagged spec-N: " arg
                                      " names no commit in this repository")))
        tags (cut/cuts-at dir commit)]
    (when (empty? tags)
      (command/cannot-run! (str "regeneration reads only a cut, a commit tagged spec-N: " arg
                                " (" (cut/short-hash commit) ") carries no spec-N tag")))
    {:commit commit :tag (if (cut/cut-number arg) arg (last tags))}))

(defn cut-files
  "Every file path the commit holds, sorted."
  [dir commit]
  (let [out (:out (let [r (cut/git dir "ls-tree" "-r" "-z" "--name-only" "--full-tree" commit)]
                    (when-not (zero? (:exit r))
                      (command/cannot-run! (str "git ls-tree " commit " failed: " (str/trim (:err r)))))
                    r))]
    (vec (sort (remove str/blank? (str/split out #"\u0000"))))))

(defn file-at
  "The text of `path` at `commit`, or nil when the commit holds no such file."
  [dir commit path]
  (let [r (cut/git dir "cat-file" "-p" (str commit ":" path))]
    (when (zero? (:exit r)) (:out r))))

(defn read-edn-at
  "`path` at the cut read as EDN; cannot run when it is missing or unreadable."
  [dir {:keys [commit tag]} path what]
  (let [text (or (file-at dir commit path)
                 (command/cannot-run! (str "the cut " tag " holds no " path ": " what " is read from the cut")))]
    (try (edn/read-string text)
         (catch Exception e
           (command/cannot-run! (str "the cut's " path " is not readable EDN: " (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Paths

(defn under?
  "True when the relative path `rel` is `p` or lies inside the directory `p`."
  [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn under-any? [rel ps] (boolean (some #(under? rel %) ps)))

(defn missing-allowed
  "The allowed paths `allow` that none of `files` is or lies under."
  [allow files]
  (vec (remove (fn [a] (some #(under? % a) files)) allow)))

;; ---------------------------------------------------------------------------
;; The scope

(defn- split-list [s]
  (vec (distinct (remove str/blank? (map str/trim (str/split (str s) #","))))))

(defn parse-scope
  "The scope the options name: nil for the project, else {:contexts [..]
  :components [\"ctx/name\" ..]}."
  [opts]
  (let [contexts (if (string? (:context opts)) (split-list (:context opts)) [])
        components (if (string? (:component opts)) (split-list (:component opts)) [])]
    (when (or (seq contexts) (seq components))
      {:contexts contexts :components components})))

(defn- spec-name
  "The component's name in its context: the last part of its :spec, else of
  its id."
  [c]
  (or (some-> (:spec c) str (str/split #"/") last)
      (some-> (:id c) name)))

(defn known-scopes
  "{context-name #{component-name}} of the model, kernel included."
  [model]
  (merge
   (into (sorted-map)
         (for [c (:containers model) ctx (:contexts c)]
           [(some-> (:id ctx) name) (set (keep spec-name (:components ctx)))]))
   {"kernel" (set (keep spec-name (:kernel model)))}))

(defn scope-problems
  "What of `scope` the model (`known`, from known-scopes) does not name: a
  list of messages."
  [known scope]
  (let [ctx-list (str (str/join ", " (remove #{"kernel"} (keys known))) " and kernel")]
    (vec
     (concat
      (for [c (:contexts scope) :when (not (contains? known c))]
        (str "the model names no bounded context " c "; its bounded contexts are " ctx-list))
      (for [c (:components scope)
            :let [[ctx comp & more] (str/split c #"/")]
            :when (or more (nil? comp) (not (contains? (get known ctx) comp)))]
        (if (contains? known ctx)
          (str "the model names no component " c "; the components of " ctx " are "
               (str/join ", " (map #(str ctx "/" %) (sort (get known ctx)))))
          (str "the model names no component " c ": it names no bounded context " ctx
               "; its bounded contexts are " ctx-list)))))))

(defn- file-part [s] (str/replace (str s) "-" "_"))

(defn scope-prefixes
  "The path prefixes the configuration's :code-layout gives `scope`."
  [layout scope]
  (vec (distinct
        (concat
         (for [c (:contexts scope) e (:context layout)]
           (str/replace e "{context}" (file-part c)))
         (for [c (:components scope)
               :let [[ctx comp] (str/split c #"/")]
               e (:component layout)]
           (-> e (str/replace "{context}" (file-part ctx)) (str/replace "{component}" (file-part comp))))))))

(defn kept-implementation
  "The implementation files of `files` (under `implementation`) a scope
  keeps: none for the project (nil scope), else those starting with none of
  `prefixes`."
  [files implementation scope prefixes]
  (if (nil? scope)
    []
    (vec (for [f files
               :when (under-any? f implementation)
               :when (not-any? #(str/starts-with? f %) prefixes)]
           f))))

;; ---------------------------------------------------------------------------
;; The room

(def ^:private leaking-env
  "Variables that would point git in the room at another repository or object
  store."
  ["GIT_DIR" "GIT_WORK_TREE" "GIT_INDEX_FILE" "GIT_OBJECT_DIRECTORY" "GIT_ALTERNATE_OBJECT_DIRECTORIES"
   "GIT_COMMON_DIR" "GIT_NAMESPACE" "GIT_CEILING_DIRECTORIES"])

(defn- room-git!
  "Runs git in the room, isolated from the caller's repository; returns its
  trimmed output, cannot run on a failure."
  [room env & args]
  (let [env (merge (apply dissoc (into {} (System/getenv)) leaking-env) env)
        r (apply p/shell {:dir (str room) :out :string :err :string :continue true :in "" :env env}
                 "git" "-c" "commit.gpgsign=false" "-c" "init.defaultBranch=main" args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " args) " failed in the room: " (str/trim (str (:err r) (:out r))))))
    (str/trim (:out r))))

(defn- empty-dir? [d] (and (fs/directory? d) (empty? (fs/list-dir d))))

(defn build-room!
  "Writes `paths` of the cut into `room` and commits them as its one root
  commit; returns the commit's hash."
  [dir {:keys [commit tag]} room paths]
  (cut/checkout! dir commit room paths)
  (let [date (cut/git! dir "show" "-s" "--format=%cI" commit)
        env {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
             "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"
             "GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date}]
    (room-git! room env "init" "-q")
    (room-git! room env "add" "-A" "-f" ".")
    (room-git! room env "commit" "-q" "--no-verify" "--allow-empty" "-m"
               (str "Clean room of " tag " (" commit ")"))
    (room-git! room env "rev-parse" "HEAD")))

;; ---------------------------------------------------------------------------
;; The task

(defn fence
  "The fence task: `rdm-spec fence ROOM CUT [--context C,...] [--component C/N,...]`."
  [req]
  (let [[room-arg cut-arg] (:args req)
        dir (:root req)
        _ (cut/repository! dir)
        c (resolve-cut dir cut-arg)
        cfg (read-edn-at dir c command/config-file "the configuration")
        _ (when-not (map? cfg) (command/cannot-run! (str "the cut's " command/config-file " is not a map")))
        allow (vec (get-in cfg [:clean-room :allow]))
        _ (when (empty? allow)
            (command/cannot-run! (str "the cut's " command/config-file " allows no path (:clean-room :allow)")))
        files (cut-files dir (:commit c))
        missing (missing-allowed allow files)
        _ (when (seq missing)
            (command/cannot-run! (str "the cut " (:tag c) " holds no " (str/join ", " missing)
                                      ", which the clean room allows (:clean-room :allow)")))
        scope (parse-scope (:opts req))
        implementation (vec (:implementation cfg))
        prefixes (when scope
                   (let [model (read-edn-at dir c (or (:model cfg) "dhf/spec/model.edn") "the model")
                         problems (scope-problems (known-scopes model) scope)
                         layout (:code-layout cfg)]
                     (when (seq problems) (command/cannot-run! (str/join "; " problems)))
                     (when (or (and (seq (:contexts scope)) (empty? (:context layout)))
                               (and (seq (:components scope)) (empty? (:component layout))))
                       (command/cannot-run! (str "the cut's " command/config-file
                                                 " has no :code-layout for the scope given")))
                     (scope-prefixes layout scope)))
        kept (kept-implementation files implementation scope prefixes)
        allowed-files (filterv #(under-any? % allow) files)
        impl-files (filterv #(under-any? % implementation) files)
        room (fs/file (fs/normalize (fs/absolutize (command/path req room-arg))))
        _ (when (and (fs/exists? room) (not (empty-dir? room)))
            (command/cannot-run! (str "the room " room-arg " exists and is not empty: a clean room is built fresh")))
        made? (not (fs/exists? room))]
    (try
      (let [room-commit (build-room! dir c room (vec (distinct (concat allowed-files kept))))]
        {:result (array-map :room room-arg
                            :cut (:tag c)
                            :commit (:commit c)
                            :scope (if scope scope :project)
                            :room-commit room-commit
                            :allowed allow
                            :files (+ (count allowed-files) (count kept))
                            :implementation-kept (count kept)
                            :implementation-removed (- (count impl-files) (count kept)))
         :findings []})
      (catch Throwable e
        (if made? (fs/delete-tree room) (doseq [f (fs/list-dir room)] (fs/delete-tree f)))
        (throw e)))))
