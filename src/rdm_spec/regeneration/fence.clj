(ns rdm-spec.regeneration.fence
  "Component Fence of the bounded context regeneration
  (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT [--context C
  | --component C/N]` builds a clean room from a cut.

  Regeneration reads only a cut: CUT must name a commit carrying a spec-N tag
  (the tag itself, or the commit's id). Everything is read from that commit,
  never from the working tree: its configuration (dhf/harness/config.edn), its
  model (for a scope) and its files.

  The clean room is an allow-list. ROOM becomes a new git repository holding
  one root commit whose tree is exactly
    - every file under the cut's :clean-room :allow paths, and
    - for a context or component scope, every implementation file (under the
      cut's :implementation paths) except those the scope rebuilds: the files
      starting with one of the scope's :code-layout prefixes, {context} and
      {component} filled in with - written _.
  The project scope (no option) keeps no implementation. Every file is written
  byte for byte with its mode, and its blob is checked to be the cut's; the
  room's object database holds only that commit, its trees and those blobs, so
  nothing else is reachable, not even by history.

  A scope names a bounded context of the cut's model, or kernel (the kernel is
  a scope like a context); a component is <context>/<component> as the
  model's :spec names it. --context and --component each take one name or a
  comma-separated list, and may be given together: the scope is then the list.

  Exit 2 (cannot run), with nothing written: not a git repository, a CUT that
  names no commit or a commit that is not a cut, a cut with no readable
  configuration, an allowed path the cut does not hold, a scope the model does
  not name, or a ROOM that exists and is not an empty directory. It publishes
  no event: the steps that come later meet it through the room."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; git, with bytes in and out, never prompting, never filtering

(def ^:private room-identity
  {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"
   "GIT_TERMINAL_PROMPT" "0"})

(defn- git-raw
  "Runs git in `dir`: {:exit :out (bytes) :err}. `in` is a string or nil."
  [dir in & args]
  (let [r (apply p/shell {:dir (str dir) :out :bytes :err :string :continue true
                          :in (or in "") :extra-env room-identity}
                 "git" "-c" "core.autocrlf=false" "-c" "core.safecrlf=false" "-c" "commit.gpgsign=false"
                 args)]
    {:exit (:exit r) :out (or (:out r) (byte-array 0)) :err (str/trim (or (:err r) ""))}))

(defn- git-out!
  "Runs git and returns its standard output as a string; cannot run on an
  error, saying what was being done."
  [doing dir in & args]
  (let [r (apply git-raw dir in args)]
    (when-not (zero? (:exit r))
      (command/cannot-run! (str "could not " doing ": git " (str/join " " args) ": " (:err r))))
    (String. ^bytes (:out r) "UTF-8")))

;; ---------------------------------------------------------------------------
;; Paths

(defn under?
  "True when `path` is `prefix` or lies under it (both relative, with /)."
  [path prefix]
  (let [prefix (str/replace (str prefix) #"/+$" "")]
    (or (= path prefix) (str/starts-with? path (str prefix "/")))))

(defn file-name-part
  "A context or component name as file names write it: - as _."
  [s]
  (str/replace (str s) "-" "_"))

(defn scope-prefixes
  "The path prefixes the scope rebuilds, from the :code-layout `layout`:
  `scope` is {:contexts [..] :components [[context component] ..]}."
  [layout {:keys [contexts components]}]
  (vec (distinct
        (concat
         (for [c contexts t (:context layout)]
           (str/replace t "{context}" (file-name-part c)))
         (for [[c n] components t (:component layout)]
           (-> t
               (str/replace "{context}" (file-name-part c))
               (str/replace "{component}" (file-name-part n))))))))

(defn room-paths
  "Which of the cut's files `paths` (relative, with /) the room holds:
  {:allowed [..] :kept [..] :removed [..] :missing-allowed [..]}. `scope` nil
  is the project scope, which keeps no implementation."
  [paths {:keys [allow implementation prefixes]}]
  (let [impl? (fn [p] (some #(under? p %) implementation))
        allowed (filterv (fn [p] (some #(under? p %) allow)) paths)
        impl (filterv #(and (impl? %) (not (some (fn [a] (under? % a)) allow))) paths)
        removed? (fn [p] (or (nil? prefixes) (some #(str/starts-with? p %) prefixes)))]
    {:allowed allowed
     :kept (filterv (complement removed?) impl)
     :removed (filterv removed? impl)
     :missing-allowed (filterv (fn [a] (not (some #(under? % a) paths))) allow)}))

;; ---------------------------------------------------------------------------
;; The cut

(defn- short-id [sha] (subs sha 0 (min 7 (count sha))))

(defn resolve-cut
  "{:commit sha :tag spec-N} for `rev` in `root`; cannot run when it names no
  commit, or a commit no spec-N tag marks."
  [root rev]
  (let [sha (or (cut/commit-of root rev)
                (command/cannot-run! (str rev " names no commit in this repository: regeneration reads only a cut,"
                                          " a commit tagged " cut/cut-prefix "-N")))
        tags (cut/cuts-at root sha)]
    (when (empty? tags)
      (command/cannot-run! (str "the commit " rev " (" (short-id sha) ") carries no " cut/cut-prefix
                                "-N tag: regeneration reads only a cut")))
    {:commit sha :tag (if (some #{rev} tags) rev (last tags))}))

(defn- tree
  "[{:mode :type :sha :path}] of every entry of the commit `sha`, recursively."
  [root sha]
  (let [out (git-out! "list the cut's files" root nil "ls-tree" "-r" "-z" "--full-tree" sha)]
    (vec (for [e (str/split out #"\u0000")
               :when (not (str/blank? e))
               :let [[meta path] (str/split e #"\t" 2)
                     [mode type obj] (str/split meta #" ")]]
           {:mode mode :type type :sha obj :path path}))))

(defn- show
  "The text of `path` at the commit `sha`, or nil when the commit holds none."
  [root sha path]
  (let [r (git-raw root nil "show" (str sha ":" path))]
    (when (zero? (:exit r)) (String. ^bytes (:out r) "UTF-8"))))

(defn- read-edn-at [root {:keys [commit tag]} path what]
  (let [text (or (show root commit path)
                 (command/cannot-run! (str "the cut " tag " holds no " path ", so it has no " what)))]
    (try (edn/read-string text)
         (catch Exception e
           (command/cannot-run! (str "the " what " " path " at the cut " tag " is not readable EDN: "
                                     (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; The scope

(defn model-scopes
  "What the model `m` lets a scope name: {:contexts [names, kernel last]
  :components #{\"context/component\"}}."
  [m]
  (let [ctxs (for [c (:containers m) x (:contexts c)] x)
        spec-of (fn [el ctx-name]
                  (or (:spec el)
                      (when (keyword? (:id el))
                        (str (or (namespace (:id el)) ctx-name) "/" (name (:id el))))))]
    {:contexts (vec (distinct (concat (keep #(some-> (:id %) name) ctxs) ["kernel"])))
     :components (set (concat (for [x ctxs c (:components x) :let [s (spec-of c (name (:id x)))] :when s] s)
                              (for [k (:kernel m) :let [s (spec-of k "kernel")] :when s] s)))}))

(defn- names [v] (when v (vec (remove str/blank? (map str/trim (str/split v #","))))))

(defn scope-of
  "The scope the options ask for, checked against the model's names: nil for
  the project, else {:contexts [..] :components [[context component] ..]}.
  `model-fn` gives the model only when a scope needs it."
  [opts model-fn tag]
  (let [ctxs (names (:context opts))
        comps (names (:component opts))]
    (when (or (some? (:context opts)) (some? (:component opts)))
      (when (and (empty? ctxs) (empty? comps))
        (command/cannot-run! "--context and --component need a name"))
      (let [{:keys [contexts components]} (model-scopes (model-fn))
            known (str "the model's bounded contexts are " (str/join ", " (butlast contexts))
                       ", and " (last contexts) " (the kernel, a scope like a context)")]
        (doseq [c ctxs :when (not (some #{c} contexts))]
          (command/cannot-run! (str "the model at " tag " names no bounded context " c "; " known)))
        (doseq [c comps :when (not (contains? components c))]
          (command/cannot-run! (str "the model at " tag " names no component " c
                                    " (written <context>/<component>); its components are "
                                    (str/join ", " (sort components)) "; " known)))
        {:contexts ctxs
         :components (mapv #(str/split % #"/" 2) comps)}))))

(defn- scope-result [scope]
  (if scope
    (cond-> {}
      (seq (:contexts scope)) (assoc :contexts (:contexts scope))
      (seq (:components scope)) (assoc :components (mapv #(str/join "/" %) (:components scope))))
    :project))

;; ---------------------------------------------------------------------------
;; The room

(defn- room-state
  "Cannot run unless `room` does not exist or is an empty directory; returns
  whether it existed."
  [room]
  (cond
    (not (fs/exists? room)) false
    (not (fs/directory? room))
    (command/cannot-run! (str "the room " room " exists and is not a directory: a clean room is built in a new one"))
    (seq (fs/list-dir room))
    (command/cannot-run! (str "the room " room " is not empty: a clean room is built in a new or empty directory"))
    :else true))

(defn- read-blobs
  "{sha bytes} of the blobs `shas` in the repository `root`, read in one go."
  [root shas]
  (if (empty? shas)
    {}
    (let [r (git-raw root (str (str/join "\n" shas) "\n") "cat-file" "--batch")
          ^bytes out (:out r)
          n (alength out)]
      (when-not (zero? (:exit r))
        (command/cannot-run! (str "could not read the cut's files: " (:err r))))
      (loop [i 0 acc {}]
        (if (>= i n)
          acc
          (let [nl (loop [j i] (if (= 10 (aget out j)) j (recur (inc j))))
                header (String. out i (- nl i) "UTF-8")
                [sha type size] (str/split header #" ")]
            (when (not= "blob" type)
              (command/cannot-run! (str "could not read the cut's object " header)))
            (let [size (parse-long size)
                  start (inc nl)]
              (recur (+ start size 1)
                     (assoc acc sha (java.util.Arrays/copyOfRange out start (+ start size)))))))))))

(defn- write-entry! [room {:keys [mode path]} ^bytes content]
  (let [f (fs/file room path)]
    (fs/create-dirs (fs/parent f))
    (if (= "120000" mode)
      (fs/create-sym-link f (String. content "UTF-8"))
      (do (fs/write-bytes f content)
          (when (= "100755" mode) (.setExecutable f true))))))

(defn- build-room!
  "Writes `entries` into `room` and commits them as its one root commit:
  the commit's id."
  [room entries message]
  (let [blobs (read-blobs (:root (meta entries)) (distinct (map :sha entries)))]
    (git-out! "make the room a repository" room nil "init" "-q" "--template=" "-b" "main" ".")
    (doseq [e entries] (write-entry! room e (get blobs (:sha e))))
    ;; the room's blobs, from the files as written, never filtered
    (let [files (remove #(= "120000" (:mode %)) entries)
          links (filter #(= "120000" (:mode %)) entries)
          hashed (when (seq files)
                   (cut/lines (git-out! "write the room's files" room
                                        (str (str/join "\n" (map :path files)) "\n")
                                        "hash-object" "-w" "--no-filters" "--stdin-paths")))
          shas (merge (zipmap (map :path files) hashed)
                      (into {} (for [l links]
                                 [(:path l) (str/trim (git-out! "write the room's links" room
                                                                (String. ^bytes (get blobs (:sha l)) "UTF-8")
                                                                "hash-object" "-w" "--stdin"))])))]
      (doseq [e entries :when (not= (:sha e) (get shas (:path e)))]
        (command/cannot-run! (str (:path e) " was not written into the room as the cut holds it")))
      (git-out! "index the room's files" room
                (apply str (map #(str (:mode %) " " (:sha %) "\t" (:path %) "\u0000") entries))
                "update-index" "-z" "--add" "--index-info")
      (let [tree-sha (str/trim (git-out! "write the room's tree" room nil "write-tree"))
            commit (str/trim (git-out! "commit the room" room message "commit-tree" tree-sha))]
        (git-out! "point the room's main at its commit" room nil "update-ref" "refs/heads/main" commit)
        (git-raw room nil "update-index" "-q" "--refresh")
        commit))))

;; ---------------------------------------------------------------------------
;; The task

(defn fence
  "The fence task: `rdm-spec fence ROOM CUT [--context C | --component C/N]`."
  [req]
  (let [root (:root req)
        [room-arg rev] (:args req)
        _ (when-not (cut/repository? root)
            (command/cannot-run! (str root " is not a git repository: there is no cut to build a clean room from")))
        {:keys [commit tag] :as the-cut} (resolve-cut root rev)
        config (read-edn-at root the-cut command/config-file "configuration")
        _ (when-not (map? config)
            (command/cannot-run! (str "the configuration " command/config-file " at the cut " tag " is not a map")))
        allow (get-in config [:clean-room :allow])
        _ (when-not (and (sequential? allow) (seq allow) (every? string? allow))
            (command/cannot-run! (str "the configuration at the cut " tag " sets no :clean-room :allow paths")))
        model-fn #(read-edn-at root the-cut (or (:model config) "dhf/spec/model.edn") "model")
        scope (scope-of (:opts req) model-fn tag)
        implementation (:implementation config)
        _ (when (and scope (not (and (sequential? implementation) (seq implementation))))
            (command/cannot-run! (str "the configuration at the cut " tag " sets no :implementation paths,"
                                      " so no scope can keep the rest")))
        layout (:code-layout config)
        prefixes (when scope
                   (let [ps (scope-prefixes layout scope)]
                     (when (empty? ps)
                       (command/cannot-run! (str "the configuration at the cut " tag " sets no :code-layout"
                                                 " for the scope, so it cannot say where its code lives")))
                     ps))
        entries (filterv #(not= "commit" (:type %)) (tree root commit))
        {:keys [allowed kept removed missing-allowed]}
        (room-paths (mapv :path entries) {:allow allow :implementation (or implementation [])
                                          :prefixes prefixes})
        _ (when (seq missing-allowed)
            (command/cannot-run! (str "the cut " tag " holds no " (str/join ", " missing-allowed)
                                      ", which the configuration allows: the clean room would not hold the whole"
                                      " allow-list")))
        room (fs/file (command/path req room-arg))
        existed? (room-state room)
        wanted (set (concat allowed kept))
        chosen (with-meta (filterv #(wanted (:path %)) entries) {:root root})
        message (str "Clean room from the cut " tag " (" commit ")\n\nScope: " (pr-str (scope-result scope)) "\n")]
    (fs/create-dirs room)
    (let [room-commit (try (build-room! room chosen message)
                           (catch Throwable e
                             (if existed?
                               (doseq [f (fs/list-dir room)] (fs/delete-tree f))
                               (fs/delete-tree room))
                             (throw e)))]
      {:result {:room (str (fs/absolutize room))
                :cut tag
                :cut-commit commit
                :scope (scope-result scope)
                :commit room-commit
                :allowed (vec allow)
                :files (count chosen)
                :implementation-kept (count kept)
                :implementation-removed (vec removed)}
       :findings []})))
