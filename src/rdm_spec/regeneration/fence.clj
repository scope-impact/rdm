(ns rdm-spec.regeneration.fence
  "Fence (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C,...] [--component C/N,...]` builds a clean room from a cut: a
  fresh repository at ROOM whose one root commit holds the paths the cut's
  configuration allows (:clean-room :allow), unchanged, plus the
  implementation its scope keeps, and nothing else. Every file is written
  straight from the cut's tree, so neither the project's working tree nor
  any other commit reaches the room. The command runs the oracle gate first;
  the fence itself knows nothing about the oracle.

  In order, each refusal a cannot-run (exit 2), and nothing is built:
    1. the project must be a git repository, and CUT must name a commit
       carrying a spec-N tag: regeneration reads only a cut.
    2. ROOM (relative to the project) must not exist, or be an empty
       directory: a clean room is built fresh, never over something.
    3. the configuration and the model are the cut's (dhf/harness/config.edn
       as the cut holds it, then its :model, read only when a scope is
       given), never the working tree's: the fence reads the cut and nothing
       else. A cut holding either unreadably or not at all cannot be read.
    4. every scope must be one the cut's model names: --context a bounded
       context, or kernel (the kernel counts as a context); --component
       CONTEXT/NAME a component of one (kernel/bus included). A refusal names
       each unknown scope and what the model has.
    5. every allowed path must be at the cut; a refusal names each missing one.

  The scope (glossary: Scope):
    no --context, no --component  the project: no implementation is kept.
    a list of contexts and components  every implementation file at the cut
       (under one of :implementation) is kept unless it starts with one of
       the scopes' :code-layout prefixes, {context} and {component} filled
       in, {component} written with - as _.

  The room's commit is made with a fixed identity and the cut's own dates,
  so the same cut and scope build the same commit.

  Standard output: {:room ROOM :cut \"spec-3\" :commit <cut hash> :room-commit
  <hash> :scope :project | {:contexts [..] :components [..]} :allowed [..]
  :kept <implementation files kept> :files <files in the room>}."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; Scopes

(defn- split-list
  "The entries of a comma-separated option value; cannot run on an empty one."
  [opt v]
  (when v
    (let [xs (map str/trim (str/split (str v) #"," -1))]
      (when (some str/blank? xs)
        (command/cannot-run! (str "--" opt " " v " names an empty scope; usage: --" opt " A,B")))
      (vec (distinct xs)))))

(defn parse-scope
  "The scope the options give: :project when neither --context nor
  --component is given, else {:contexts [..] :components [..]}."
  [opts]
  (let [contexts (split-list "context" (:context opts))
        components (split-list "component" (:component opts))]
    (doseq [c components]
      (when-not (re-matches #"[^/\s]+/[^/\s]+" c)
        (command/cannot-run! (str "--component " c " is not CONTEXT/NAME, as in spec/parser"))))
    (if (or (seq contexts) (seq components))
      {:contexts (vec contexts) :components (vec components)}
      :project)))

(defn model-contexts
  "The names of the model's bounded contexts, in the model's order."
  [model]
  (vec (for [c (:containers model) x (:contexts c) :when (:id x)] (name (:id x)))))

(defn model-components
  "Every component of the model as CONTEXT/NAME, the kernel's included."
  [model]
  (vec (for [comp (concat (for [c (:containers model) x (:contexts c) comp (:components x)] comp)
                          (:kernel model))
             :let [id (:id comp)]
             :when (and (keyword? id) (namespace id))]
         (str (namespace id) "/" (name id)))))

(defn unknown-scopes
  "Why each scope of `scope` the model does not name is refused: [why]."
  [model scope]
  (if (= :project scope)
    []
    (let [contexts (conj (model-contexts model) "kernel")
          components (model-components model)]
      (vec (concat
            (for [c (:contexts scope) :when (not (some #{c} contexts))]
              (str "the bounded context " c " is not in the model; its bounded contexts are "
                   (str/join ", " (butlast contexts)) ", and kernel"))
            (for [c (:components scope) :when (not (some #{c} components))]
              (str "the component " c " is not in the model; its components are "
                   (str/join ", " components))))))))

(defn layout-prefixes
  "The :code-layout prefixes of the scope `scope`, in order."
  [layout scope]
  (if (= :project scope)
    []
    (let [fill (fn [tpl ctx comp]
                 (-> (str tpl)
                     (str/replace "{context}" ctx)
                     (str/replace "{component}" (str/replace (str comp) "-" "_"))))]
      (vec (distinct
            (concat
             (for [c (:contexts scope) tpl (:context layout)] (fill tpl c nil))
             (for [cn (:components scope)
                   :let [[c n] (str/split cn #"/" 2)]
                   tpl (:component layout)]
               (fill tpl c n))))))))

;; ---------------------------------------------------------------------------
;; Paths

(defn- trim-dir [d] (str/replace (str d) #"/+$" ""))

(defn under?
  "True when the path `rel` is `p` or lies under the directory `p`."
  [rel p]
  (let [p (trim-dir p)]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn select-files
  "The files of `files` (paths at the cut) a clean room holds: every one
  under an allowed path, and, unless the scope is the project, every
  implementation file that starts with none of `removed` (prefixes).
  Returns {:allowed [..] :kept [..]}, sorted."
  [{:keys [allow implementation scope removed]} files]
  (let [allowed? (fn [f] (some #(under? f %) allow))
        impl? (fn [f] (some #(under? f %) implementation))
        removed? (fn [f] (some #(str/starts-with? f %) removed))
        files (sort (distinct files))]
    {:allowed (vec (filter allowed? files))
     :kept (if (= :project scope)
             []
             (vec (filter #(and (impl? %) (not (allowed? %)) (not (removed? %))) files)))}))

;; ---------------------------------------------------------------------------
;; The cut

(defn resolve-cut
  "{:commit :tag} of the cut `arg` names in `dir`; cannot run unless it
  names a commit carrying a spec-N tag."
  [dir arg]
  (let [why "regeneration reads only a cut, a commit tagged spec-N: "
        commit (or (cut/rev dir arg)
                   (command/cannot-run! (str why arg " names no commit in this repository")))
        tags (cut/cuts-at dir commit)]
    (when (empty? tags)
      (command/cannot-run! (str why arg " (" (cut/short-hash commit) ") carries no spec-N tag")))
    {:commit commit :tag (if (some #{arg} tags) arg (last tags))}))

(defn- at-cut?
  [dir commit path]
  (zero? (:exit (cut/git dir "cat-file" "-e" (str commit ":" (trim-dir path))))))

(defn- edn-at
  "`path` as the cut holds it, read as EDN; cannot run when it is missing or
  unreadable or not a map."
  [dir {:keys [commit tag]} path what]
  (let [r (cut/git dir "cat-file" "-p" (str commit ":" path))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the cut " tag " holds no " path ": the fence reads " what " from the cut")))
    (let [v (try (edn/read-string (:out r))
                 (catch Exception e
                   (command/cannot-run! (str "the cut " tag "'s " path " is not readable EDN: " (ex-message e)))))]
      (when-not (map? v) (command/cannot-run! (str "the cut " tag "'s " path " is not a map")))
      v)))

(defn files-at
  "Every file (blob) the commit `commit` holds, by path."
  [dir commit]
  (let [r (cut/git dir "ls-tree" "-r" "-z" "--full-tree" commit)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the tree of " (cut/short-hash commit) " cannot be read: " (str/trim (:err r)))))
    (vec (for [entry (str/split (:out r) #"\u0000")
               :let [[meta path] (str/split entry #"\t" 2)
                     [_ type] (str/split (str meta) #" ")]
               :when (and path (= "blob" type))]
           path))))

;; ---------------------------------------------------------------------------
;; The room

(defn- room-dir!
  "The room's absolute directory; cannot run when it exists and is not an
  empty directory."
  [req room]
  (let [d (fs/absolutize (command/path req room))]
    (when (fs/exists? d)
      (when-not (and (fs/directory? d) (empty? (fs/list-dir d)))
        (command/cannot-run! (str "the room " room " already exists and is not empty: a clean room is built fresh"))))
    d))

(defn- build!
  "Writes `paths` of the cut into the fresh directory `out` and commits them
  as one root commit; returns its hash."
  [dir {:keys [commit tag]} out paths message]
  (let [existed? (fs/exists? out)]
    (try
      (cut/checkout! dir commit out paths)
      (let [date (cut/git! dir "show" "-s" "--format=%cI" commit)
            env {:extra-env {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
                             "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"
                             "GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date}}]
        (cut/git! out "init" "--quiet" "--template=" "--initial-branch=main")
        (cut/git! out "-c" "core.hooksPath=/dev/null" "add" "-A" "-f" ".")
        (cut/git! out env "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false"
                  "commit" "--quiet" "--allow-empty" "-m" message)
        (cut/git! out "rev-parse" "HEAD"))
      (catch Throwable e
        ;; never leave half a room behind
        (if existed?
          (doseq [f (fs/list-dir out)] (fs/delete-tree f))
          (fs/delete-tree out))
        (throw e)))))

(defn- scope-text [scope]
  (if (= :project scope)
    "the project"
    (str/join ", " (concat (map #(str "the bounded context " %) (:contexts scope))
                           (map #(str "the component " %) (:components scope))))))

;; ---------------------------------------------------------------------------
;; The task

(defn fence
  "The fence task: `rdm-spec fence ROOM CUT [--context C,...] [--component C/N,...]`."
  [req]
  (let [dir (:root req)
        [room cut-arg] (:args req)
        _ (cut/repository! dir)
        scope (parse-scope (:opts req))
        the-cut (resolve-cut dir cut-arg)
        out (room-dir! req room)
        cfg (edn-at dir the-cut command/config-file "the configuration")
        need (fn [k v] (or v (command/cannot-run! (str "the cut " (:tag the-cut) "'s configuration sets no " k))))
        allow (need ":clean-room :allow" (not-empty (vec (get-in cfg [:clean-room :allow]))))
        implementation (need ":implementation" (not-empty (vec (:implementation cfg))))
        removed (when-not (= :project scope)
                  (let [model (edn-at dir the-cut (need ":model" (:model cfg)) "the model")
                        unknown (unknown-scopes model scope)
                        layout (need ":code-layout" (:code-layout cfg))]
                    (when (seq unknown)
                      (command/cannot-run! (str "the model names no such scope: " (str/join "; " unknown))))
                    (layout-prefixes layout scope)))
        missing (vec (remove #(at-cut? dir (:commit the-cut) %) allow))
        _ (when (seq missing)
            (command/cannot-run! (str "the cut " (:tag the-cut) " holds no " (str/join ", " missing)
                                      ", which the configuration allows (:clean-room :allow):"
                                      " a clean room holds every allowed path")))
        {:keys [allowed kept]} (select-files {:allow allow :implementation implementation
                                              :scope scope :removed removed}
                                             (files-at dir (:commit the-cut)))
        message (str "Clean room of " (:tag the-cut) " (" (:commit the-cut) ")\n\nScope: " (scope-text scope) "\n")
        room-commit (build! dir the-cut out (concat allowed kept) message)]
    {:result (array-map :room room :cut (:tag the-cut) :commit (:commit the-cut) :room-commit room-commit
                        :scope scope :allowed allow :kept (count kept) :files (+ (count allowed) (count kept)))
     :findings []}))
