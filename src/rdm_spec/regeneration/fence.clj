(ns rdm-spec.regeneration.fence
  "Fence (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C,...] [--component C/N,...]` builds a clean room from a cut:
  a new repository at ROOM whose one root commit holds the paths the
  configuration's :clean-room :allow lists, as the cut holds them, plus, for
  a scoped regeneration, the cut's implementation outside the scopes, and
  nothing else. The oracle gates it first (the command's task table).

  In order, each refusal a cannot-run (exit 2) that builds nothing:
    1. the project must be a git repository, and CUT must name a commit that
       carries a spec-N tag: regeneration reads only a cut.
    2. ROOM must not exist, or be an empty directory.
    3. the configuration read is the cut's (dhf/harness/config.edn as the
       cut holds it), else the working tree's; it must list :clean-room
       :allow, and, for a scope, :implementation and :code-layout.
    4. every allowed path must exist at the cut (naming each missing one).
    5. each --context must be a bounded context of the cut's model or the
       kernel, each --component <context>/<name> a component of the model
       (the kernel's included); an unknown one is named with the model's
       bounded contexts and the kernel.

  Without a scope (the whole project) the room keeps no implementation.
  A scope removes every implementation file starting with one of its
  :code-layout entries ({context}, {component} filled in, - written _ in a
  component's name) and keeps every other implementation file of the cut,
  unchanged.

  The room's commit is made with a fixed identity, no hooks and no
  signing: `Clean room of CUT (COMMIT)`. The result:
    {:fence {:room ROOM :cut CUT :commit C :scope [..] | :project
             :room-commit C :room-tree T :files n :kept-implementation n}}"
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; Pure parts

(defn- strip [p] (str/replace (str p) #"/+$" ""))

(defn under?
  "Pure: whether the path `rel` is `p` or lies under it."
  [rel p]
  (let [p (strip p)]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn split-list
  "Pure: the comma-separated names of an option's value."
  [v]
  (if (string? v) (vec (remove str/blank? (map str/trim (str/split v #",")))) []))

(defn model-scopes
  "Pure: {:contexts #{\"name\"} :components {\"context/name\" {:context c :component n}}}
  of the model, the kernel counted as a context."
  [model]
  (let [ctxs (for [c (:containers model) ctx (:contexts c)] ctx)
        ctx-name (fn [ctx] (or (some-> (:id ctx) name) (:name ctx)))
        comp-entry (fn [ctx-n comp]
                     (let [n (or (some-> (:id comp) name)
                                 (some-> (:spec comp) (str/split #"/") last))]
                       [(str ctx-n "/" n) {:context ctx-n :component n}]))]
    {:contexts (into (sorted-set) (concat (map ctx-name ctxs) ["kernel"]))
     :components (into (sorted-map)
                       (concat (for [ctx ctxs comp (:components ctx)] (comp-entry (ctx-name ctx) comp))
                               (for [comp (:kernel model)] (comp-entry "kernel" comp))))}))

(defn resolve-scopes
  "Pure: the scopes the options name, [{:context c} | {:context c :component n}],
  or {:unknown [name ..]}."
  [known contexts components]
  (let [unknown (concat (remove (:contexts known) contexts)
                        (remove (:components known) components))]
    (if (seq unknown)
      {:unknown (vec unknown)}
      (vec (concat (for [c contexts] {:context c})
                   (for [c components] (get (:components known) c)))))))

(defn removed-prefixes
  "Pure: the implementation path prefixes the scopes remove, per the
  configured :code-layout."
  [layout scopes]
  (vec (distinct
        (for [{:keys [context component]} scopes
              entry (if component (:component layout) (:context layout))]
          (-> entry
              (str/replace "{context}" context)
              (str/replace "{component}" (str/replace (str component) "-" "_")))))))

(defn kept-implementation
  "Pure: the implementation files (`impl-files` at the cut) a room keeps:
  none for the whole project, else those no removed prefix starts."
  [impl-files scopes prefixes]
  (if (empty? scopes)
    []
    (vec (remove (fn [f] (some #(str/starts-with? f %) prefixes)) impl-files))))

;; ---------------------------------------------------------------------------
;; Reading the cut

(defn- files-at
  "Every file the commit holds under the paths `paths`, sorted."
  [dir commit paths]
  (if (empty? paths)
    []
    (vec (sort (remove str/blank?
                       (str/split (apply cut/git! dir "ls-tree" "-r" "-z" "--name-only" commit "--" (map strip paths))
                                  #"\u0000"))))))

(defn- text-at [dir commit rel]
  (let [r (cut/git dir "show" (str commit ":" rel))]
    (when (zero? (:exit r)) (:out r))))

(defn- edn-at [dir commit rel]
  (when-let [t (text-at dir commit rel)]
    (try (edn/read-string t) (catch Exception _ nil))))

(defn- exists-at? [dir commit rel]
  (zero? (:exit (cut/git dir "cat-file" "-e" (str commit ":" (strip rel))))))

;; ---------------------------------------------------------------------------
;; The stage

(def ^:private room-git-env
  {:extra-env {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
               "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"
               "GIT_CONFIG_NOSYSTEM" "1"}})

(defn- room-git! [room & args]
  (apply cut/git! room room-git-env "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false"
         "-c" "init.defaultBranch=main" args))

(defn fence
  "The fence stage."
  [req]
  (let [root (:root req)
        [room-arg cut-arg] (:args req)
        _ (cut/repository! root)
        commit (or (cut/rev root cut-arg)
                   (command/cannot-run! (str cut-arg " names no commit: regeneration reads only a cut, a commit tagged spec-N")))
        cuts (cut/cuts-at root commit)
        _ (when (empty? cuts)
            (command/cannot-run! (str cut-arg " (" (cut/short-hash commit) ") carries no spec-N tag:"
                                      " regeneration reads only a cut")))
        room (fs/absolutize (command/path req room-arg))
        _ (when (and (fs/exists? room) (not (and (fs/directory? room) (empty? (fs/list-dir room)))))
            (command/cannot-run! (str "the room " room-arg " already exists and is not empty: a clean room is built new")))
        config (let [c (edn-at root commit command/config-file)] (if (map? c) c (:config req)))
        _ (when-not (map? config)
            (command/cannot-run! (str "no configuration " command/config-file " at the cut " cut-arg " or in the working tree")))
        allow (get-in config [:clean-room :allow])
        _ (when-not (and (sequential? allow) (seq allow) (every? string? allow))
            (command/cannot-run! (str "the configuration " command/config-file " lists no :clean-room :allow")))
        missing (vec (remove #(exists-at? root commit %) allow))
        _ (when (seq missing)
            (command/cannot-run! (str "the cut " cut-arg " holds no " (str/join ", " missing)
                                      ", which the clean room allows: every allowed path exists at the cut")))
        contexts (split-list (get-in req [:opts :context]))
        components (split-list (get-in req [:opts :component]))
        scoped? (or (seq contexts) (seq components))
        scopes (when scoped?
                 (let [model-rel (or (:model config) "dhf/spec/model.edn")
                       model (edn-at root commit model-rel)
                       _ (when-not (map? model)
                           (command/cannot-run! (str "the cut " cut-arg " holds no readable model " model-rel)))
                       known (model-scopes model)
                       r (resolve-scopes known contexts components)]
                   (when (map? r)
                     (command/cannot-run! (str "the model names no scope " (str/join ", " (:unknown r))
                                               "; its bounded contexts and kernel: "
                                               (str/join ", " (:contexts known))
                                               "; a component is <context>/<name>")))
                   r))
        impl (:implementation config)
        layout (:code-layout config)
        _ (when (and scoped? (not (and (sequential? impl) (map? layout))))
            (command/cannot-run! (str "the configuration " command/config-file
                                      " lists no :implementation or :code-layout, so no scope can be kept")))
        impl-files (if scoped? (files-at root commit impl) [])
        kept (kept-implementation impl-files scopes (removed-prefixes layout scopes))
        allowed-files (files-at root commit allow)
        paths (vec (sort (distinct (concat allowed-files kept))))]
    (fs/create-dirs room)
    (try
      (cut/checkout! root commit room paths)
      (room-git! room "init" "-q")
      (room-git! room "add" "-A" "-f" ".")
      (room-git! room "commit" "-q" "--allow-empty" "-m" (str "Clean room of " (first cuts) " (" commit ")"))
      (catch Throwable e
        (fs/delete-tree room)
        (throw e)))
    {:result {:fence {:room (str room-arg) :cut (first cuts) :commit commit
                      :scope (if scoped? (vec scopes) :project)
                      :room-commit (room-git! room "rev-parse" "HEAD")
                      :room-tree (room-git! room "rev-parse" "HEAD^{tree}")
                      :files (count paths)
                      :kept-implementation (count kept)}}
     :findings []}))
