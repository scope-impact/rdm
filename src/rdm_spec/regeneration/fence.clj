(ns rdm-spec.regeneration.fence
  "Fence (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C | --component C/N]` builds a clean room from a cut.

  The room is a new git repository with one root commit holding, from the
  cut and nothing else (never the working tree, never history):
    - every allowed path (the cut configuration's :clean-room :allow), each of
      which must be at the cut;
    - for a context or component scope, every implementation file (the
      configured :implementation) except those the scope removes: each
      :code-layout entry of the scope's kind, with {context} and {component}
      filled in ({component} with - written _), is a path prefix, and a file
      starting with one is removed. The project scope keeps no implementation.

  Blobs are copied byte for byte and keep their mode, so the room's objects are
  the cut's objects for those paths; the room's store holds nothing else. The
  room commit is dated as the cut commit, so fencing the same cut and scope
  twice gives the same commit.

  Order of refusals (exit 2): the scope against the project's model, the cut,
  the cut's configuration and model, the allowed paths at the cut, then the
  room (absent, or an empty directory). Nothing is written before all pass."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; Scope

(defn parse-scope
  "The scope the options ask for: {:kind :project}, {:kind :context :context
  \"c\"} or {:kind :component :context \"c\" :component \"n\"}. Cannot run
  on both options or a malformed component."
  [{:keys [context component]}]
  (cond
    (and context component)
    (command/cannot-run! "give --context or --component, not both")

    context {:kind :context :context context}

    component
    (let [parts (str/split component #"/" -1)]
      (when-not (and (= 2 (count parts)) (every? (complement str/blank?) parts))
        (command/cannot-run! (str "--component takes CONTEXT/COMPONENT, as spec/parser, not " component)))
      {:kind :component :context (first parts) :component (second parts)})

    :else {:kind :project}))

(defn scope-label [{:keys [kind context component]}]
  (case kind
    :project "the project"
    :context (str "the context " context)
    :component (str "the component " context "/" component)))

(defn model-contexts
  "The bounded contexts the model names, in its order: [\"project\" ...]."
  [model]
  (vec (for [c (:containers model) ctx (:contexts c)] (name (:id ctx)))))

(defn model-components
  "Every component the model names as \"context/component\": those of the
  bounded contexts, then the kernel's."
  [model]
  (vec (concat
        (for [c (:containers model) ctx (:contexts c) comp (:components ctx)]
          (str (name (:id ctx)) "/" (name (:id comp))))
        (for [comp (:kernel model)]
          (str "kernel/" (name (:id comp)))))))

(defn scope-refusal
  "Why the model does not name the scope, or nil when it does. The kernel,
  shared code in no context, is named by the model too."
  [model {:keys [kind context component]}]
  (let [contexts (model-contexts model)
        kernel? (seq (:kernel model))]
    (case kind
      :project nil
      :context (when-not (or (some #{context} contexts) (and kernel? (= "kernel" context)))
                 (str "the model names no bounded context " context
                      "; its bounded contexts are " (str/join ", " contexts)
                      (when kernel? "; the kernel (kernel) is a scope too")))
      :component (let [c (str context "/" component)]
                   (when-not (some #{c} (model-components model))
                     (str "the model names no component " c "; its components are "
                          (str/join ", " (model-components model))))))))

(defn scope-prefixes
  "The path prefixes the scope removes, from the configured :code-layout:
  [] for the project scope (which keeps no implementation at all)."
  [code-layout {:keys [kind context component]}]
  (if (= :project kind)
    []
    (let [entries (get code-layout kind)]
      (when-not (seq entries)
        (command/cannot-run! (str "the configuration's :code-layout places no " (name kind) " code")))
      (mapv #(-> %
                 (str/replace "{context}" (str/replace context "-" "_"))
                 (str/replace "{component}" (str/replace (or component "") "-" "_")))
            entries))))

;; ---------------------------------------------------------------------------
;; Selecting the cut's files

(defn under?
  "True when `path` is `p` or lies in the directory `p`."
  [p path]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= p path) (str/starts-with? path (str p "/")))))

(defn select-entries
  "The tree entries ({:mode :type :sha :path}) the room takes: those under an
  allowed path, plus (unless the scope is the project) those under an
  implementation path that start with none of `prefixes`."
  [entries {:keys [allow implementation scope prefixes]}]
  (let [allowed? (fn [path] (some #(under? % path) allow))
        impl? (fn [path] (some #(under? % path) implementation))
        removed? (fn [path] (some #(str/starts-with? path %) prefixes))]
    (vec (filter (fn [{:keys [path]}]
                   (or (allowed? path)
                       (and (not= :project (:kind scope)) (impl? path) (not (removed? path)))))
                 entries))))

(defn missing-allowed
  "The allowed paths no entry lies under."
  [entries allow]
  (vec (remove (fn [a] (some #(under? a (:path %)) entries)) allow)))

;; ---------------------------------------------------------------------------
;; git with bytes

(defn- sh
  "Runs git in `dir` with `args`; `opts` may give :in (string or bytes),
  :out :bytes and :env. Returns {:exit :out :err}."
  [dir opts & args]
  (let [r (apply p/shell {:dir (str dir) :in (or (:in opts) "") :out (or (:out opts) :string) :err :string
                          :continue true
                          :extra-env (merge {"GIT_TERMINAL_PROMPT" "0"} (:env opts))}
                 "git" args)]
    {:exit (:exit r) :out (:out r) :err (str/trim (or (:err r) ""))}))

(defn- sh!
  [dir opts & args]
  (let [r (apply sh dir opts args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "git " (str/join " " args) " failed in " dir ": " (:err r))))
    (:out r)))

(defn parse-ls-tree
  "Entries of `git ls-tree -r -z` output."
  [out]
  (vec (for [rec (str/split (or out "") #"\x00")
             :when (not (str/blank? rec))
             :let [[meta path] (str/split rec #"\t" 2)
                   [mode type sha] (str/split meta #" ")]]
         {:mode mode :type type :sha sha :path path})))

(defn tree-entries [dir rev]
  (parse-ls-tree (sh! dir {} "ls-tree" "-r" "-z" "--full-tree" rev)))

(defn- index-of [^bytes bs b from]
  (loop [i from]
    (cond (>= i (alength bs)) -1
          (= b (aget bs i)) i
          :else (recur (inc i)))))

(defn parse-batch
  "{sha bytes} of `git cat-file --batch` output."
  [^bytes bs]
  (loop [pos 0 acc {}]
    (if (>= pos (alength bs))
      acc
      (let [nl (index-of bs (byte 10) pos)
            header (String. bs (int pos) (int (- nl pos)) "UTF-8")
            [sha type size] (str/split header #" ")]
        (when (= "missing" type)
          (command/cannot-run! (str "the object " sha " is missing from the repository")))
        (let [size (parse-long size)
              start (inc nl)
              content (java.util.Arrays/copyOfRange bs (int start) (int (+ start size)))]
          (recur (+ start size 1) (assoc acc sha content)))))))

(defn- blobs [dir shas]
  (if (empty? shas)
    {}
    (parse-batch (sh! dir {:in (str (str/join "\n" shas) "\n") :out :bytes} "cat-file" "--batch"))))

;; ---------------------------------------------------------------------------
;; The cut

(defn- resolve-cut
  "{:commit sha :tag spec-N} for `rev`, or cannot run: regeneration reads only a cut."
  [root rev]
  (when-not (cut/repository? root)
    (command/cannot-run! (str root " is not a git repository, so it holds no cut " rev
                              ": regeneration reads only a cut (a commit tagged spec-N)")))
  (let [sha (or (cut/commit-of root rev)
                (command/cannot-run! (str rev " names no commit here: regeneration reads only a cut"
                                          " (a commit tagged spec-N)")))
        tags (cut/cuts-at root sha)]
    (when (empty? tags)
      (command/cannot-run! (str rev " (" (subs sha 0 7) ") carries no spec-N tag: regeneration reads only a cut")))
    {:commit sha :tag (if (cut/cut-tag? rev) rev (first tags))}))

(defn- read-at
  "The EDN map in `path` at the commit `sha`, or cannot run naming why."
  [root sha tag path what]
  (let [r (sh root {} "show" (str sha ":" path))]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "the cut " tag " holds no " what " " path)))
    (let [v (try (edn/read-string (:out r))
                 (catch Exception e
                   (command/cannot-run! (str "the " what " " path " at the cut " tag " is not readable EDN: "
                                             (ex-message e)))))]
      (when-not (map? v)
        (command/cannot-run! (str "the " what " " path " at the cut " tag " is not a map")))
      v)))

(defn- project-model [req]
  (let [rel (command/config-value req :model)
        f (command/path req rel)]
    (when-not (fs/exists? f)
      (command/cannot-run! (str "the model " rel " does not exist")))
    (let [m (try (edn/read-string (slurp f))
                 (catch Exception e (command/cannot-run! (str "the model " rel " is not readable EDN: " (ex-message e)))))]
      (when-not (map? m) (command/cannot-run! (str "the model " rel " is not a map")))
      m)))

;; ---------------------------------------------------------------------------
;; The room

(defn- check-room! [room]
  (when (fs/exists? room)
    (when-not (fs/directory? room)
      (command/cannot-run! (str "the room " room " is a file: the room must be a new directory")))
    (when (seq (fs/list-dir room))
      (command/cannot-run! (str "the room " room " is not empty: the room must be a new directory")))))

(defn- write-file! [room {:keys [mode path]} ^bytes content]
  (let [f (fs/file room path)]
    (fs/create-dirs (fs/parent f))
    (if (= "120000" mode)
      (fs/create-sym-link f (String. content "UTF-8"))
      (do (with-open [o (java.io.FileOutputStream. f)] (.write o content))
          (.setExecutable f (= "100755" mode) false)))))

(defn- store-blobs!
  "Writes the blobs of `entries` into the room's object store from the files
  just written, checking each object id is the cut's."
  [room entries contents]
  (let [{links true files false} (group-by #(= "120000" (:mode %)) entries)
        stored (concat
                (when (seq files)
                  (map vector files
                       (cut/lines (sh! room {:in (str (str/join "\n" (map :path files)) "\n")}
                                       "hash-object" "-w" "--no-filters" "--stdin-paths"))))
                (for [e links]
                  [e (str/trim (sh! room {:in (get contents (:sha e))} "hash-object" "-w" "--no-filters" "--stdin"))]))]
    (doseq [[e sha] stored :when (not= (:sha e) sha)]
      (command/cannot-run! (str (:path e) " did not copy unchanged into the room (" sha ", not " (:sha e) ")")))))

(defn- build-room!
  "Writes `entries` (with their contents) into `room` as a new repository
  with one root commit dated `date`. Returns {:commit :tree}."
  [room entries contents message date]
  (fs/create-dirs room)
  (sh! room {} "init" "-q" "-b" "main")
  (doseq [e entries] (write-file! room e (get contents (:sha e))))
  (store-blobs! room entries contents)
  (sh! room {:in (apply str (map #(str (:mode %) " " (:sha %) "\t" (:path %) "\u0000") entries))}
       "update-index" "-z" "--add" "--index-info")
  (let [tree (str/trim (sh! room {} "write-tree"))
        env {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
             "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"
             "GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date}
        commit (str/trim (sh! room {:env env :in message} "commit-tree" tree))]
    (sh! room {} "update-ref" "refs/heads/main" commit)
    (sh room {} "update-index" "-q" "--refresh")
    {:commit commit :tree tree}))

;; ---------------------------------------------------------------------------
;; The task

(defn fence
  "The fence task: `rdm-spec fence ROOM CUT [--context C | --component C/N]`."
  [req]
  (let [root (:root req)
        [room-arg rev] (:args req)
        scope (parse-scope (:opts req))]
    ;; the scope, against the project's model, before anything else is read
    (when (not= :project (:kind scope))
      (when-let [why (scope-refusal (project-model req) scope)]
        (command/cannot-run! why)))
    (let [{:keys [commit tag]} (resolve-cut root rev)
          config (read-at root commit tag command/config-file "configuration")
          allow (vec (get-in config [:clean-room :allow]))
          _ (when (empty? allow)
              (command/cannot-run! (str "the configuration at the cut " tag " allows no path (:clean-room :allow)")))
          implementation (vec (:implementation config))
          _ (when (and (not= :project (:kind scope)) (empty? implementation))
              (command/cannot-run! (str "the configuration at the cut " tag " lists no :implementation")))
          _ (when (not= :project (:kind scope))
              (when-let [why (scope-refusal (read-at root commit tag (or (:model config) "dhf/spec/model.edn") "model")
                                            scope)]
                (command/cannot-run! (str why " (at the cut " tag ")"))))
          prefixes (scope-prefixes (:code-layout config) scope)
          all (tree-entries root commit)
          missing (missing-allowed all allow)
          _ (when (seq missing)
              (command/cannot-run! (str "the cut " tag " holds no " (str/join ", no " missing)
                                        ": every allowed path must be at the cut")))
          chosen (select-entries all {:allow allow :implementation implementation :scope scope :prefixes prefixes})
          gitlinks (filter #(not= "blob" (:type %)) chosen)
          entries (vec (filter #(= "blob" (:type %)) chosen))
          room (str (fs/normalize (fs/absolutize (command/path req room-arg))))
          _ (check-room! room)
          impl-at-cut (filter (fn [e] (and (= "blob" (:type e)) (some #(under? % (:path e)) implementation))) all)
          kept (filter (fn [e] (some #(under? % (:path e)) implementation)) entries)
          removed (if (= :project (:kind scope))
                    (map :path impl-at-cut)
                    (keep (fn [e] (when (some #(str/starts-with? (:path e) %) prefixes) (:path e))) impl-at-cut))
          date (str/trim (sh! root {} "log" "-1" "--format=%cI" commit))
          message (str "Clean room from " tag " (" commit "), " (scope-label scope) "\n")
          contents (blobs root (distinct (map :sha entries)))
          built (build-room! room entries contents message date)]
      {:result (cond-> {:room room :cut tag :commit commit
                        :scope (cond-> {:kind (:kind scope)}
                                 (:context scope) (assoc :context (:context scope))
                                 (:component scope) (assoc :component (str (:context scope) "/" (:component scope))))
                        :room-commit (:commit built) :room-tree (:tree built)
                        :files (count entries)
                        :allowed allow
                        :implementation-kept (count kept)
                        :implementation-removed (vec (sort removed))}
                 (seq prefixes) (assoc :removed-prefixes prefixes))
       :findings (vec (for [g gitlinks]
                        (command/warning "gitlink-skipped"
                                         (str (:path g) " is a submodule at the cut, not a file: the room does not carry it"))))})))
