(ns rdm-spec.regeneration.fence
  "Fence (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C,...] [--component C/N,...]` builds a clean room from a cut,
  and the check stage `paths` holds every committed path to the clean
  room's classification.

  The clean room is the cut's committed files (never the working tree, so
  nothing untracked or ignored reaches it) minus what the cut's
  configuration denies (:clean-room :deny): the scope's implementation, the
  measurements and the generated output; one root commit in a new
  repository at ROOM (which must not exist, or be empty). The scope is the
  project (every :implementation path) unless --context and --component
  name parts: each removes the files its :code-layout prefixes find
  ({context}, {component}, - written _). A context is one of the cut's
  model's bounded contexts or kernel; a component one the model names, or
  one it no longer names whose files the code layout finds at the cut (a
  removed component, named in the result). Anything else cannot run, naming
  the model's bounded contexts and kernel.

  A kept file whose bytes are those of a removed implementation file (an
  empty file aside) stops the fence: a finding naming both
  (fence-copied-implementation), exit 1, and no room.

  Cannot run: CUT names no commit, or one no spec-N tag names
  (regeneration reads only a cut); ROOM exists and is not empty.

  Result {:fence {:room ROOM :cut TAG :commit C :scope :project | {:contexts
  [..] :components [..]} :files n :removed n :removed-components [C/N]}}.

  `paths` (task check): every committed top-level path and every path
  directly under dhf/ lies under a denied or a kept path, or it is a finding
  (path-unclassified)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; Classification

(defn- under? [p l]
  (let [l (str/replace (str l) #"/+$" "")]
    (or (= p l) (str/starts-with? p (str l "/")))))

(defn- listed
  "Every path the configuration `cfg` denies or keeps."
  [cfg]
  (let [{:keys [deny keep]} (:clean-room cfg)]
    (concat (when (:implementation deny) (:implementation cfg)) (:measurements deny) (:generated deny) keep)))

(defn- classified-path
  "The path of `p` that is classified: its top-level name, or dhf/NAME."
  [p]
  (let [[a b] (str/split p #"/")]
    (if (and (= "dhf" a) b) (str "dhf/" b) a)))

(defn unclassified
  "The paths among the committed `paths` (and their top-level names) that
  the configuration `cfg` neither denies nor keeps, sorted."
  [cfg paths]
  (let [ls (listed cfg)]
    (vec (sort (distinct (for [p paths
                               :let [c (classified-path p)]
                               :when (not-any? #(under? c %) ls)]
                           c))))))

(defn paths
  "The check stage: every committed path classified."
  [req]
  (let [r (cut/git req {} "ls-files" "-z")]
    {:result {}
     :findings (if-not (zero? (:exit r))
                 []
                 (vec (for [p (unclassified (:config req) (remove str/blank? (str/split (:out r) #"\u0000")))]
                        (command/finding p "path-unclassified"
                                         (str p " is committed but neither denied nor kept by :clean-room in "
                                              command/config-file ": every committed path is classified")))))}))

;; ---------------------------------------------------------------------------
;; Scopes

(defn scopes-of
  "The scopes the options `opts` name: {:contexts [C] :components [[C N]]}."
  [opts]
  (let [split (fn [s] (vec (remove str/blank? (map str/trim (str/split (str s) #",")))))]
    {:contexts (if (:context opts) (split (:context opts)) [])
     :components (vec (for [c (if (:component opts) (split (:component opts)) [])]
                        (let [[a b] (str/split c #"/" 2)]
                          (when (or (str/blank? a) (str/blank? b))
                            (command/cannot-run! (str "the component " c " is not CONTEXT/COMPONENT")))
                          [a b])))}))

(defn context-names
  "The model's bounded contexts' names, and kernel."
  [m]
  (set (concat (for [c (:containers m) :when (map? c) x (:contexts c) :when (:id x)] (name (:id x)))
               ["kernel"])))

(defn- id-name [k] (if (namespace k) (str (namespace k) "/" (name k)) (name k)))

(defn component-names
  "Every component the model names, as CONTEXT/COMPONENT."
  [m]
  (set (concat (for [c (:containers m) :when (map? c) x (:contexts c) comp (:components x) :when (:id comp)]
                 (id-name (:id comp)))
               (for [comp (:kernel m) :when (:id comp)] (id-name (:id comp))))))

(defn- fill [pattern ctx comp]
  (-> (str pattern)
      (str/replace "{context}" (str/replace (str ctx) "-" "_"))
      (str/replace "{component}" (str/replace (str comp) "-" "_"))))

(defn prefixes
  "The path prefixes the scopes remove, by `cfg`'s :code-layout."
  [cfg {:keys [contexts components]}]
  (vec (concat (for [c contexts p (get-in cfg [:code-layout :context])] (fill p c nil))
               (for [[c n] components p (get-in cfg [:code-layout :component])] (fill p c n)))))

;; ---------------------------------------------------------------------------
;; The fence

(defn- committed
  "[{:path :blob}] of every file of `commit`."
  [req commit]
  (let [out (cut/git! req {} (str "the commit " commit " cannot be read") "ls-tree" "-r" "-z" "--full-tree" commit)]
    (vec (for [entry (str/split out #"\u0000") :when (not (str/blank? entry))
               :let [[meta p] (str/split entry #"\t" 2)
                     [_ _ blob] (str/split meta #" ")]]
           {:path p :blob blob}))))

(def ^:private empty-blob "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")

(defn- remove-empty-dirs! [dir]
  (doseq [d (reverse (sort-by #(count (str %)) (filter fs/directory? (fs/glob dir "**" {:hidden true}))))]
    (when (and (not (str/includes? (str (fs/relativize dir d)) ".git")) (empty? (fs/list-dir d)))
      (fs/delete d))))

(defn fence
  "The fence stage."
  [req]
  (let [[room-arg rev] (:args req)
        _ (cut/repository! req)
        commit (or (cut/commit-of req rev)
                   (command/cannot-run! (str rev " names no commit of this repository: regeneration reads only a cut, a spec-N tag")))
        tags (cut/cut-tags-at req commit)
        _ (when (empty? tags)
            (command/cannot-run! (str rev " carries no spec-N tag: regeneration reads only a cut")))
        tag (if (cut/cut-tag? rev) rev (last tags))
        cfg (or (cut/config-at req commit)
                (command/cannot-run! (str "the cut " tag " holds no " command/config-file)))
        model-rel (command/config-value {:config cfg} :model)
        model (let [m (cut/edn-at req commit model-rel)] (if (map? m) m {}))
        scopes (scopes-of (:opts req))
        project? (and (empty? (:contexts scopes)) (empty? (:components scopes)))
        files (committed req commit)
        known-contexts (context-names model)
        refuse (fn [what]
                 (command/cannot-run! (str what " is not a scope the model at " tag " names: its bounded contexts are "
                                           (str/join ", " (sort (disj known-contexts "kernel"))) ", and kernel")))
        _ (doseq [c (:contexts scopes) :when (not (known-contexts c))] (refuse (str "the context " c)))
        known-components (component-names model)
        removed-components (vec (for [[c n :as cn] (:components scopes)
                                      :let [nm (str c "/" n)]
                                      :when (not (known-components nm))]
                                  (let [ps (prefixes cfg {:contexts [] :components [cn]})]
                                    (if (some (fn [{:keys [path]}] (some #(str/starts-with? path %) ps)) files)
                                      nm
                                      (refuse (str "the component " nm))))))
        deny (get-in cfg [:clean-room :deny])
        impl? (if project?
                (fn [p] (and (:implementation deny) (some #(under? p %) (:implementation cfg))))
                (let [ps (prefixes cfg scopes)] (fn [p] (some #(str/starts-with? p %) ps))))
        other? (fn [p] (some #(under? p %) (concat (:measurements deny) (:generated deny))))
        removed-impl (filter #(impl? (:path %)) files)
        kept (remove #(or (impl? (:path %)) (other? (:path %))) files)
        impl-blobs (group-by :blob (remove #(= empty-blob (:blob %)) removed-impl))
        copies (for [k kept :let [orig (get impl-blobs (:blob k))] :when orig]
                 (command/finding (:path k) "fence-copied-implementation"
                                  (str (:path k) " carries the bytes of the implementation file " (:path (first orig))
                                       ", which the clean room removes: nothing is copied into a clean room, only rebuilt")))
        room (command/path req room-arg)]
    (if (seq copies)
      {:result {} :findings (vec copies)}
      (do
        (when (and (fs/exists? room) (not (and (fs/directory? room) (empty? (fs/list-dir room)))))
          (command/cannot-run! (str "the room " room-arg " exists: a clean room is built in a new directory")))
        (cut/export! req commit room)
        (doseq [{:keys [path]} files :when (or (impl? path) (other? path))]
          (fs/delete-if-exists (fs/file room path)))
        (remove-empty-dirs! room)
        (let [g (fn [& args]
                  (apply cut/git! req {:dir (str room)} (str "the clean room " room-arg " cannot be committed")
                         "-c" "user.name=rdm-spec fence" "-c" "user.email=fence@rdm-spec.invalid"
                         "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null" args))]
          (g "init" "-q")
          (g "add" "-A" "-f" "--" ".")
          (g "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "Clean room of " tag " (" commit ")")))
        {:result {:fence {:room room-arg :cut tag :commit commit
                          :scope (if project? :project scopes)
                          :files (count kept) :removed (- (count files) (count kept))
                          :removed-components removed-components}}
         :findings []}))))
