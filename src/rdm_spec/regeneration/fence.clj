(ns rdm-spec.regeneration.fence
  "Fence (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C,...] [--component C/N,...]` builds a clean room from a cut and
  proves it holds nothing else.

  In order:
    1. CUT must name a cut: a spec-N tag, or a commit one names. Anything else
       cannot run (exit 2): regeneration reads only a cut.
    2. ROOM must not exist, or be an empty directory.
    3. Everything is read at the cut, never from the working tree: the
       configuration (dhf/harness/config.edn), the model, the files. Every
       allowed path (:clean-room :allow) must be there; one missing cannot
       run, naming it.
    4. The scope: no option is the whole project, whose implementation the
       room never keeps. --context and --component each take a
       comma-separated list; each must be a bounded context or component the
       cut's model names, the kernel counting as a context named `kernel`
       whose components are the model's :kernel. A scoped room keeps every
       file of the configured :implementation paths except those starting
       with an entry of the :code-layout filled for each scope ({context},
       {component}, - written _).
    5. ROOM becomes a new repository holding one root commit: the allowed
       paths and the kept implementation, exactly as at the cut. Only their
       objects are ever written to it.
    6. The proof: the room's single commit holds exactly the files chosen,
       each with the cut's mode and blob, and its object store holds no other
       blob. A room that fails the proof is deleted and the task exits 1."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

(def config-file "dhf/harness/config.edn")

(def room-identity
  {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
   "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"})

;; ---------------------------------------------------------------------------
;; The cut

(defn resolve-cut
  "{:tag :commit} of the cut `arg` names in `dir`, or cannot run: a commit
  no spec-N tag names is not a cut."
  [dir arg]
  (let [tag? (and (cut/cut-number arg)
                  (zero? (:exit (cut/git dir "rev-parse" "--verify" "-q" (str "refs/tags/" arg)))))
        commit (cut/rev dir (if tag? (str "refs/tags/" arg) arg))]
    (when-not commit
      (command/cannot-run! (str arg " names no commit: regeneration reads only a cut (a spec-N tag)")))
    (let [tag (if tag? arg (first (cut/cuts-at dir commit)))]
      (when-not tag
        (command/cannot-run! (str arg " (" (cut/short-hash commit) ") carries no spec-N tag:"
                                  " regeneration reads only a cut")))
      {:tag tag :commit commit})))

(defn tree-entries
  "Every file of the commit: [{:mode :type :hash :path}], by path."
  [dir commit]
  (let [out (cut/git! dir "ls-tree" "-r" "-z" "--full-tree" commit)]
    (vec (for [e (str/split out #"\u0000")
               :when (not (str/blank? e))
               :let [[meta path] (str/split e #"\t" 2)
                     [mode type hash] (str/split meta #" ")]]
           {:mode mode :type type :hash hash :path path}))))

(defn read-at
  "The text of `path` at `commit`, or nil when the cut holds no such file."
  [dir commit path]
  (let [r (cut/git dir "cat-file" "blob" (str commit ":" path))]
    (when (zero? (:exit r)) (:out r))))

(defn read-edn-at
  "`path` at the cut read as EDN; cannot run when it is absent or unreadable."
  [dir {:keys [tag commit]} path]
  (let [text (or (read-at dir commit path)
                 (command/cannot-run! (str "the cut " tag " holds no " path)))]
    (try (edn/read-string text)
         (catch Exception e
           (command/cannot-run! (str path " at the cut " tag " is not readable EDN: " (ex-message e)))))))

;; ---------------------------------------------------------------------------
;; Paths

(defn under?
  "True when the relative path `rel` is `p` or lies under the directory `p`."
  [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn missing-allowed
  "The allowed paths no file of the cut lies under."
  [allow paths]
  (vec (remove (fn [a] (some #(under? % a) paths)) allow)))

;; ---------------------------------------------------------------------------
;; Scopes

(defn split-list
  "The names of a comma-separated option, blanks left out."
  [s]
  (if (nil? s) [] (vec (remove str/blank? (map str/trim (str/split s #","))))))

(defn model-scopes
  "{:contexts [name ...] :components {context [name ...]}} of the model, the
  kernel counting as the context `kernel`."
  [model]
  (let [ctxs (for [c (:containers model) x (:contexts c)] x)
        comp-name (fn [id] (name id))]
    {:contexts (vec (concat (map (comp name :id) ctxs) ["kernel"]))
     :components (into {"kernel" (mapv (comp comp-name :id) (:kernel model))}
                       (for [x ctxs] [(name (:id x)) (mapv (comp comp-name :id) (:components x))]))}))

(defn- known-list [{:keys [contexts]}]
  (str "the model's bounded contexts are " (str/join ", " (remove #{"kernel"} contexts))
       ", and the kernel is the context kernel"))

(defn scope
  "The scope the options ask for: {:project true}, or {:contexts [..]
  :components [[context name] ..]}, each checked against the model's
  scopes; one the model does not name cannot run."
  [scopes {:keys [context component]}]
  (let [cs (split-list context)
        comps (split-list component)]
    (if (and (empty? cs) (empty? comps))
      (do (when (or context component)
            (command/cannot-run! "--context and --component name no scope: give a name, or neither for the project"))
          {:project true})
      (let [known (set (:contexts scopes))
            unknown-c (remove known cs)
            parsed (for [n comps] [n (str/split n #"/")])
            unknown-n (for [[n parts] parsed
                            :let [[c x] parts]
                            :when (not (and (= 2 (count parts))
                                            (some #{x} (get-in scopes [:components c]))))]
                        n)]
        (when (seq unknown-c)
          (command/cannot-run! (str "the model names no bounded context " (str/join ", " unknown-c)
                                    "; " (known-list scopes))))
        (when (seq unknown-n)
          (command/cannot-run!
           (str "the model names no component " (str/join ", " unknown-n)
                " (a component is CONTEXT/NAME); its components are "
                (str/join ", " (for [c (:contexts scopes) x (get-in scopes [:components c])] (str c "/" x)))
                "; " (known-list scopes))))
        {:contexts (vec (distinct cs)) :components (vec (distinct (map second parsed)))}))))

(defn- file-name [s] (str/replace (str s) "-" "_"))

(defn scope-prefixes
  "The path prefixes the code layout gives the scope: every implementation
  file starting with one of them is removed."
  [layout {:keys [contexts components]}]
  (let [fill (fn [t c n] (-> t
                             (str/replace "{context}" (file-name c))
                             (str/replace "{component}" (file-name n))))]
    (vec (distinct (concat (for [c contexts t (:context layout)] (fill t c ""))
                           (for [[c n] components t (:component layout)] (fill t c n)))))))

(defn describe-scope
  "The scope in words: the project, or its contexts and components."
  [{:keys [project contexts components]}]
  (if project
    "the project"
    (let [part (fn [one many xs] (when (seq xs) (str (if (next xs) many one) " " (str/join ", " xs))))]
      (str/join " and " (remove nil? [(part "the context" "the contexts" contexts)
                                      (part "the component" "the components" (map #(str/join "/" %) components))])))))

(defn choose
  "The files of the cut the room holds: those under an allowed path, and,
  for a scoped room, the implementation files no scope prefix removes.
  {:kept [entry ..] :implementation n :removed n}."
  [entries {:keys [allow implementation]} prefixes project?]
  (let [allowed? (fn [p] (some #(under? p %) allow))
        impl? (fn [p] (some #(under? p %) implementation))
        removed? (fn [p] (or project? (some #(str/starts-with? p %) prefixes)))
        files (filter #(= "blob" (:type %)) entries)
        impl (filter #(and (impl? (:path %)) (not (allowed? (:path %)))) files)
        kept-impl (remove #(removed? (:path %)) impl)]
    {:kept (vec (sort-by :path (concat (filter #(allowed? (:path %)) files) kept-impl)))
     :implementation (count kept-impl)
     :removed (- (count impl) (count kept-impl))}))

;; ---------------------------------------------------------------------------
;; The room

(defn room-dir!
  "ROOM as a file; cannot run when it exists and is not an empty directory."
  [req room]
  (let [f (command/path req room)]
    (when (fs/exists? f)
      (when-not (and (fs/directory? f) (empty? (fs/list-dir f)))
        (command/cannot-run! (str "ROOM " room " exists and is not an empty directory: a clean room starts empty"))))
    f))

(defn proof-findings
  "What makes the room unproven: its commits, its files against those
  chosen, and any blob in its store no chosen file holds."
  [room kept]
  (let [commits (cut/lines (cut/git! room "rev-list" "--all"))
        parents (cut/git! room "rev-list" "--parents" "-n" "1" "HEAD")
        got (set (map #(select-keys % [:mode :hash :path]) (tree-entries room "HEAD")))
        want (set (map #(select-keys % [:mode :hash :path]) kept))
        blobs (set (keep #(let [[o t] (str/split % #" ")] (when (= "blob" t) o))
                         (cut/lines (cut/git! room "cat-file" "--batch-all-objects"
                                              "--batch-check=%(objectname) %(objecttype)"))))
        stray (remove (set (map :hash kept)) blobs)]
    (cond-> []
      (not= 1 (count commits))
      (conj (command/finding "room-history" (str "the room holds " (count commits) " commits, not one")))
      (not= [(first commits)] (str/split parents #" "))
      (conj (command/finding "room-history" "the room's commit has a parent"))
      (not= got want)
      (conj (command/finding "room-differs"
                             (str "the room's files differ from the cut's: missing "
                                  (str/join ", " (sort (map :path (remove got want))))
                                  "; unexpected " (str/join ", " (sort (map :path (remove want got)))))))
      (seq stray)
      (conj (command/finding "room-stray-object"
                             (str "the room's store holds " (count stray) " blob(s) no allowed file holds"))))))

(defn build-room!
  "Makes `room` a repository whose one root commit holds the `kept` files of
  the cut, and returns the commit's hash."
  [dir {:keys [tag commit]} room kept message]
  (fs/create-dirs room)
  (cut/git! room "init" "-q")
  (cut/checkout! dir commit room (map :path kept))
  (cut/git! room "-c" "core.autocrlf=false" "add" "-A" "-f" ".")
  (cut/git! room {:extra-env room-identity}
            "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null"
            "commit" "-q" "--no-verify" "--allow-empty" "-m" message)
  (cut/git! room "rev-parse" "HEAD"))

;; ---------------------------------------------------------------------------
;; The task

(defn fence
  "The fence task: `rdm-spec fence ROOM CUT [--context C,...] [--component C/N,...]`."
  [req]
  (let [dir (:root req)
        [room-arg cut-arg] (:args req)
        _ (cut/repository! dir)
        c (resolve-cut dir cut-arg)
        room (room-dir! req room-arg)
        config (read-edn-at dir c config-file)
        _ (when-not (map? config) (command/cannot-run! (str config-file " at the cut " (:tag c) " is not a map")))
        allow (or (not-empty (get-in config [:clean-room :allow]))
                  (command/cannot-run! (str config-file " at the cut " (:tag c) " allows no path (:clean-room :allow)")))
        implementation (vec (:implementation config))
        entries (tree-entries dir (:commit c))
        missing (missing-allowed allow (map :path entries))
        _ (when (seq missing)
            (command/cannot-run! (str "the cut " (:tag c) " holds no " (str/join ", " missing)
                                      ", which the configuration allows: a clean room holds every allowed path")))
        sc (scope (when (or (get-in req [:opts :context]) (get-in req [:opts :component]))
                    (let [mp (or (:model config)
                                 (command/cannot-run! (str config-file " at the cut sets no :model, so no scope can be checked")))]
                      (model-scopes (read-edn-at dir c mp))))
                  (:opts req))
        layout (:code-layout config)
        _ (when (and (not (:project sc)) (not (map? layout)))
            (command/cannot-run! (str config-file " at the cut " (:tag c) " sets no :code-layout, so no scope can be kept")))
        prefixes (if (:project sc) [] (scope-prefixes layout sc))
        {:keys [kept] :as chosen} (choose entries {:allow allow :implementation implementation}
                                          prefixes (:project sc))
        message (str "Clean room from " (:tag c) " (" (:commit c) "), " (describe-scope sc))
        existed? (fs/exists? room)
        room-commit (try (build-room! dir c room kept message)
                         (catch Exception e
                           (if existed?
                             (doseq [x (fs/list-dir room)] (fs/delete-tree x))
                             (fs/delete-tree room))
                           (throw e)))
        unproven (proof-findings room kept)]
    (if (seq unproven)
      (do (if existed?
            (doseq [x (fs/list-dir room)] (fs/delete-tree x))
            (fs/delete-tree room))
          {:result {} :findings unproven})
      {:result {:room (str (fs/absolutize room))
                :cut (:tag c)
                :commit (:commit c)
                :room-commit room-commit
                :scope (if (:project sc)
                         {:project true}
                         {:contexts (:contexts sc) :components (mapv #(str/join "/" %) (:components sc))})
                :removed-prefixes prefixes
                :files (count kept)
                :implementation-kept (:implementation chosen)
                :implementation-removed (:removed chosen)}
       :findings []})))
