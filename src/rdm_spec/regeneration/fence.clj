(ns rdm-spec.regeneration.fence
  "Component Fence of the regeneration context
  (dhf/spec/regeneration/fence.feature; dhf/spec/glossary.md, \"Fence\"):
  `rdm-spec fence ROOM CUT [--context C,...] [--component C/N,...]` builds a
  clean room from a cut: one fresh commit holding every file committed at
  the cut except the denied ones, the configuration at the cut's
  :clean-room :deny:

    :implementation true   the scope's implementation: every implementation
                           path (:implementation) for the whole project, else
                           the files :code-layout places in each scoped
                           context and component (the kernel a context too)
    :measurements [path]   every cycle's record
    :generated [path]      what publish and the tests write

  Robust by three rules: it reads only the cut's committed tree, so nothing
  untracked or ignored reaches a room; every committed top-level path and
  every path directly under dhf/ is denied or kept (:clean-room :keep), or
  `rdm-spec check` fails (the stage `paths`, rule path-unclassified); and no
  file the room would hold may carry the bytes of a removed implementation
  file (fence-copy, exit 1, building nothing). An empty file carries no
  bytes, so it is never a copy.

  Cannot run (exit 2): a revision that is not a cut (spec-N), a ROOM that
  exists, a scope the model at the cut does not name, a cut with no
  configuration. The configuration's :clean-room :allow is read only by
  releases before spec-17, and never here.

  Result: {:fence {:room :cut :commit :scope :files :removed}};
  the stage paths: {:paths {:committed n :unclassified [path]}}."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

(def empty-blob "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")

;; ---------------------------------------------------------------------------
;; Pure

(defn under?
  "Whether the path `rel` is `p` or lies under it."
  [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (and (not (str/blank? p)) (or (= rel p) (str/starts-with? rel (str p "/"))))))

(defn- fill [pattern ctx comp]
  (-> (str pattern)
      (str/replace "{context}" (str/replace (str ctx) "-" "_"))
      (str/replace "{component}" (str/replace (str (or comp "")) "-" "_"))))

(defn scope-prefixes
  "The path prefixes the scopes {:contexts [name] :components [[ctx comp]]}
  remove, by the configuration's :code-layout."
  [config {:keys [contexts components]}]
  (vec (concat
        (for [c contexts p (get-in config [:code-layout :context])] (fill p c nil))
        (for [[c n] components p (get-in config [:code-layout :component])] (fill p c n)))))

(defn- model-scopes
  "{:contexts #{name} :components #{[ctx comp]}} the model `m` names, the
  kernel a context."
  [m]
  {:contexts (set (concat ["kernel"]
                          (for [c (:containers m) x (:contexts c) :when (:id x)] (name (:id x)))))
   :components (set (concat
                     (for [c (:containers m) x (:contexts c) comp (:components x) :when (:id comp)]
                       [(name (:id x)) (name (:id comp))])
                     (for [comp (:kernel m) :when (:id comp)]
                       ["kernel" (name (:id comp))])))})

(defn scopes-of
  "The scopes the options --context and --component name, each checked
  against the model `m`: {:contexts [name] :components [[ctx comp]]}."
  [m {:keys [context component]}]
  (let [{cs :contexts comps :components} (model-scopes m)
        split #(remove str/blank? (map str/trim (str/split (str %) #",")))
        known (str "the model's bounded contexts and kernel are " (str/join ", " (sort cs)))
        contexts (vec (for [c (when context (split context))]
                        (if (cs c) c (command/cannot-run! (str "--context " c " names no bounded context: " known)))))
        components (vec (for [cn (when component (split component))
                              :let [[c n :as pair] (str/split cn #"/" 2)]]
                          (if (and n (comps pair))
                            pair
                            (command/cannot-run! (str "--component " cn " names no component of the model, as"
                                                      " CONTEXT/COMPONENT: " known)))))]
    {:contexts contexts :components components}))

(defn- deny-of [config]
  (let [cr (:clean-room config)]
    (if (contains? cr :deny) (:deny cr) {:implementation true})))

(defn implementation?
  "Whether `rel` is an implementation path of the configuration."
  [config rel]
  (boolean (some #(under? rel %) (:implementation config))))

(defn denier
  "A predicate: whether a committed path is denied, the scope's
  implementation named by `prefixes` (empty: the whole project's)."
  [config prefixes]
  (let [deny (deny-of config)
        others (concat (:measurements deny) (:generated deny))]
    (fn [rel]
      (boolean
       (or (and (:implementation deny)
                (implementation? config rel)
                (or (empty? prefixes) (some #(str/starts-with? rel %) prefixes)))
           (some #(under? rel %) others))))))

(defn- level-path
  "The path a committed path is classified by: its top-level path, or the
  path directly under dhf/."
  [rel]
  (let [[a b] (str/split rel #"/")]
    (if (and (= "dhf" a) b (str/includes? rel "/")) (str "dhf/" b) a)))

(defn unclassified
  "The paths (top-level, or directly under dhf/) of the committed `paths`
  that are neither denied nor kept, in the order first met."
  [config paths]
  (let [deny (deny-of config)
        denied (concat (when (:implementation deny) (:implementation config)) (:measurements deny) (:generated deny))
        kept (get-in config [:clean-room :keep])
        classified? (fn [rel] (some #(under? rel %) (concat denied kept)))]
    (vec (distinct (for [p paths :when (not (classified? p))] (level-path p))))))

(defn copies
  "[[kept removed]]: each file of `kept` ({path blob}) carrying the bytes of
  a file of `removed` ({path blob}), the empty file aside."
  [removed kept]
  (let [by-blob (group-by val removed)]
    (vec (for [[k b] (sort-by key kept)
               :when (not= empty-blob b)
               [r _] (sort-by key (get by-blob b))]
           [k r]))))

;; ---------------------------------------------------------------------------
;; git

(defn- tree-of
  "[{:mode :type :blob :path}] of every file of the commit `rev`."
  [req rev]
  (let [out (cut/git! req {} (str "the cut " rev " cannot be read") "ls-tree" "-r" "-z" "--full-tree" rev)]
    (vec (for [entry (str/split out #"\u0000")
               :when (not (str/blank? entry))
               :let [[meta path] (str/split entry #"\t" 2)
                     [mode type blob] (str/split meta #" ")]]
           {:mode mode :type type :blob blob :path path}))))

(defn- build!
  "Writes the files `entries` of the project's repository into the new
  directory `room` and commits them there as one root commit: its hash."
  [req entries room cut-name commit]
  (let [idx (str (fs/create-temp-file {:prefix "rdm-spec-fence-index-"}))
        env {"GIT_INDEX_FILE" idx}]
    (try
      (fs/delete-if-exists idx)
      (cut/git! req {:env env} "the clean room's index cannot be made" "read-tree" "--empty")
      (cut/git! req {:env env :in (str/join (for [{:keys [mode blob path]} entries] (str mode " " blob "\t" path "\n")))}
                "the clean room's index cannot be made" "update-index" "--index-info")
      (fs/create-dirs room)
      (cut/git! req {:env env} (str "the clean room cannot be written to " room)
                (str "--work-tree=" (fs/absolutize room)) "checkout-index" "-a" "-f")
      (finally (fs/delete-if-exists idx))))
  (let [in-room {:dir room}
        g (fn [why & args] (apply cut/git! req in-room why args))
        ident ["-c" "user.name=rdm-spec fence" "-c" "user.email=fence@rdm-spec.invalid" "-c" "commit.gpgsign=false"
               "-c" "core.hooksPath=/dev/null"]]
    (g "the clean room cannot be made a repository" "init" "-q")
    (g "the clean room's files cannot be added" "add" "-A" "-f" "--" ".")
    (apply g "the clean room cannot be committed"
           (concat ident ["commit" "-q" "--no-verify" "--allow-empty" "-m"
                          (str "Clean room of " cut-name " (" commit ")")]))
    (g "the clean room's commit cannot be read" "rev-parse" "HEAD")))

;; ---------------------------------------------------------------------------
;; The stages

(defn fence
  "The fence stage: `rdm-spec fence ROOM CUT [--context C,...] [--component C/N,...]`."
  [req]
  (let [[room-arg rev] (:args req)]
    (cut/repository! req)
    (when-not (cut/cut-tag? rev)
      (command/cannot-run! (str rev " is not a cut: regeneration reads only a cut, a commit tagged spec-N")))
    (let [commit (or (cut/commit-of req (str "refs/tags/" rev))
                     (command/cannot-run! (str "there is no cut " rev " in " (:root req)
                                               ": regeneration reads only a cut")))
          room (command/path req room-arg)
          _ (when (fs/exists? room)
              (command/cannot-run! (str "ROOM " room-arg " exists: a clean room is built in a new directory, never into one")))
          config (or (cut/config-at req commit)
                     (command/cannot-run! (str "the cut " rev " holds no " command/config-file)))
          model (or (cut/edn-at req commit (command/config-value {:config config} :model))
                    (command/cannot-run! (str "the cut " rev " holds no model, " (command/config-value {:config config} :model))))
          scopes (scopes-of model (:opts req))
          prefixes (scope-prefixes config scopes)
          scoped? (or (seq (:contexts scopes)) (seq (:components scopes)))
          denied? (denier config (if scoped? prefixes []))
          entries (filter #(#{"blob"} (:type %)) (tree-of req commit))
          removed (filter #(and (denied? (:path %)) (implementation? config (:path %))) entries)
          kept (remove #(denied? (:path %)) entries)
          cs (copies (into {} (map (juxt :path :blob)) removed) (into {} (map (juxt :path :blob)) kept))
          scope (if scoped?
                  (vec (concat (for [c (:contexts scopes)] {:context (keyword c)})
                               (for [[c n] (:components scopes)] {:component (keyword c n)})))
                  :project)]
      (if (seq cs)
        {:result {:fence {:cut rev :scope scope :built? false}}
         :findings (vec (for [[k r] cs]
                          (command/finding k "fence-copy"
                                           (str "carries the bytes of the removed implementation file " r
                                                ": no kept file may carry what the clean room removes;"
                                                " no room was built"))))}
        (let [room-commit (build! req kept (str room) rev commit)]
          {:result {:fence {:room room-arg :cut rev :commit commit :room-commit room-commit :scope scope
                            :files (count kept) :removed (count removed)}}
           :findings []})))))

(defn- committed-paths
  "The paths committed at HEAD; in a directory that is no git repository
  (an export of a commit, as the cut checks), its files as they are; nil
  when there is no commit yet."
  [req]
  (if (zero? (:exit (cut/git req {} "rev-parse" "--git-dir")))
    (when-let [head (cut/commit-of req "HEAD")]
      (let [out (cut/git! req {} "the committed paths cannot be read" "ls-tree" "-r" "-z" "--name-only" "--full-tree" head)]
        (vec (remove str/blank? (str/split out #"\u0000")))))
    (vec (sort (for [f (fs/glob (:root req) "**" {:hidden true})
                     :when (fs/regular-file? f)]
                 (command/rel req f))))))

(defn paths
  "The classification stage of `rdm-spec check`: every committed path (at
  HEAD) is denied or kept. A configuration that declares neither :deny nor
  :keep (an allow-list of a release before spec-17) is not classified."
  [req]
  (let [cr (get-in req [:config :clean-room])
        committed (when (and (map? cr) (or (contains? cr :deny) (contains? cr :keep)))
                    (committed-paths req))]
    (if (empty? committed)
      {:result {:paths {:committed 0 :unclassified []}} :findings []}
      (let [bad (unclassified (:config req) committed)]
        {:result {:paths {:committed (count committed) :unclassified bad}}
         :findings (vec (for [p bad]
                          (command/finding p "path-unclassified"
                                           (str p " is committed and neither denied nor kept: name it in "
                                                command/config-file "'s :clean-room :deny or :keep, so the"
                                                " fence knows whether a clean room holds it"))))}))))
