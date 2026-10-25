(ns rdm-spec.regeneration.fence
  "Component Fence of the regeneration context
  (dhf/spec/regeneration/fence.feature): `rdm-spec fence ROOM CUT
  [--context C,...] [--component C/N,...]` builds a clean room from a cut,
  one fresh repository with a single root commit holding the paths the cut's
  configuration allows (:clean-room :allow) and, for a scoped regeneration,
  the implementation the scope keeps; then proves it holds nothing else.

  A scope names the model's bounded contexts (or kernel) and components
  (context/component); the cut's :code-layout says which implementation
  files each removes. Without a scope the whole project is regenerated and
  no implementation is kept."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as command]
            [rdm-spec.regeneration.cut :as cut]))

;; ---------------------------------------------------------------------------
;; Scopes

(defn- csv [s]
  (vec (remove str/blank? (map str/trim (str/split (str s) #",")))))

(defn model-scopes
  "{:contexts #{name} :components #{\"context/component\"}} of the model `m`:
  its bounded contexts and the kernel, and every component of each."
  [m]
  (let [ctxs (for [c (:containers m) x (:contexts c)] x)]
    {:contexts (vec (concat (map #(name (:id %)) ctxs) ["kernel"]))
     :components (set (concat (for [x ctxs comp (:components x)]
                                (str (name (:id x)) "/" (name (:id comp))))
                              (for [comp (:kernel m)] (str "kernel/" (name (:id comp))))))}))

(defn scope-of
  "The scopes the options name: [{:context :c} | {:component :c/n}], or
  :project when none; the task cannot run on one the model does not name."
  [m {:keys [context component]}]
  (let [{:keys [contexts components]} (model-scopes m)
        names (str "the model's bounded contexts are " (str/join ", " (butlast contexts))
                   ", and kernel")
        cs (csv context)
        ns* (csv component)]
    (doseq [c cs :when (not (some #{c} contexts))]
      (command/cannot-run! (str "--context " c ": the model names no bounded context " c "; " names)))
    (doseq [n ns* :when (not (contains? components n))]
      (command/cannot-run! (str "--component " n ": the model names no component " n
                                " (a component is context/component); " names)))
    (if (or (seq cs) (seq ns*))
      (vec (concat (for [c cs] {:context (keyword c)})
                   (for [n ns* :let [[c x] (str/split n #"/" 2)]] {:component (keyword c x)})))
      :project)))

(defn- fill [pattern ctx comp]
  (-> (str pattern)
      (str/replace "{context}" (str/replace (str ctx) "-" "_"))
      (str/replace "{component}" (str/replace (str comp) "-" "_"))))

(defn removed-prefixes
  "The path prefixes the scopes remove, by the code layout `layout`."
  [layout scopes]
  (vec (for [s scopes
             p (if (:context s) (:context layout) (:component layout))
             :let [k (or (:context s) (:component s))]]
         (if (:context s)
           (fill p (name k) nil)
           (fill p (namespace k) (name k))))))

(defn- under? [rel p]
  (let [p (str/replace (str p) #"/+$" "")]
    (or (= rel p) (str/starts-with? rel (str p "/")))))

(defn kept?
  "Whether the clean room keeps the file `rel` of the cut: an allowed path,
  or, under scopes, an implementation file no scope removes."
  [{:keys [allow implementation prefixes scope]} rel]
  (boolean
   (or (some #(under? rel %) allow)
       (and (not= :project scope)
            (some #(under? rel %) implementation)
            (not (some #(str/starts-with? rel %) prefixes))))))

;; ---------------------------------------------------------------------------
;; The clean room

(defn- tree-entries
  "[{:mode :type :id :path}] of every file of the commit `commit`."
  [req commit]
  (let [out (cut/git! req {} (str "the cut " commit " cannot be listed") "ls-tree" "-r" "-z" "--full-tree" commit)]
    (vec (for [e (str/split out #"\u0000") :when (not (str/blank? e))
               :let [[meta path] (str/split e #"\t" 2)
                     [mode type id] (str/split meta #" ")]]
           {:mode mode :type type :id id :path path}))))

(defn- entry-line [{:keys [mode id path]}] (str mode " " id "\t" path))

(defn- write-files!
  "Writes the blobs `entries` of the project's repository into `room`."
  [req entries room]
  (let [idx (str (fs/create-temp-file {:prefix "rdm-spec-index-"}))]
    (try
      (fs/delete-if-exists idx)
      (cut/git! req {:env {"GIT_INDEX_FILE" idx}
                     :in (str (str/join "\u0000" (map entry-line entries)) "\u0000")}
                "the clean room's files cannot be selected" "update-index" "-z" "--index-info")
      (cut/git! req {:env {"GIT_INDEX_FILE" idx}} "the clean room's files cannot be written"
                (str "--work-tree=" room) "checkout-index" "-a" "-f")
      (finally (fs/delete-if-exists idx)))))

(defn- commit-room!
  "Makes `room` a repository with one root commit of everything in it,
  dated as the cut; returns that commit."
  [req room msg date]
  (let [env {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
             "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"
             "GIT_AUTHOR_DATE" date "GIT_COMMITTER_DATE" date}
        o {:dir room :env env}
        why "the clean room cannot be committed"]
    (cut/git! req o why "init" "-q")
    (cut/git! req o why "add" "-A" "-f" "--" ".")
    (cut/git! req o why "-c" "commit.gpgsign=false" "commit" "-q" "--no-verify" "-m" msg)
    (cut/git! req o why "rev-parse" "HEAD")))

(defn proof-findings
  "What the clean room `room` holds beyond `entries`: findings, none when
  it is one root commit whose files are exactly `entries`."
  [req room-rel room entries]
  (let [o {:dir room}
        commits (remove str/blank? (str/split-lines (:out (cut/git req o "rev-list" "--all" "--parents"))))
        objects (set (remove str/blank? (map #(first (str/split % #" "))
                                             (str/split-lines (:out (cut/git req o "cat-file" "--batch-all-objects" "--batch-check"))))))
        reachable (set (remove str/blank? (map #(first (str/split % #" "))
                                               (str/split-lines (:out (cut/git req o "rev-list" "--objects" "--all"))))))
        held (set (map entry-line (tree-entries (assoc req :root room) "HEAD")))
        want (set (map entry-line entries))
        loose (str/trim (:out (cut/git req o "status" "--porcelain" "--ignored")))
        f (fn [msg] (command/finding room-rel "fence-unproved" msg))]
    (vec (concat
          (when-not (and (= 1 (count commits)) (= 1 (count (str/split (first commits) #" "))))
            [(f (str "the clean room holds " (count commits) " commits, not one root commit"))])
          (when-not (= held want)
            [(f (str "the clean room's commit holds other files than the cut allows: "
                     (str/join ", " (take 10 (sort (map #(second (str/split % #"\t" 2))
                                                        (concat (remove want held) (remove held want))))))))])
          (when-not (= objects reachable)
            [(f (str "the clean room holds " (count (remove reachable objects)) " objects its commit does not reach"))])
          (when-not (str/blank? loose)
            [(f (str "the clean room's working tree holds files its commit does not: " loose))])))))

(defn fence
  "The fence stage: `rdm-spec fence ROOM CUT [--context C,...] [--component C/N,...]`."
  [req]
  (cut/repository! req)
  (let [[room-arg cut-arg] (:args req)
        commit (or (cut/commit-of req cut-arg)
                   (command/cannot-run! (str cut-arg " names no commit of " (:root req)
                                             ": regeneration reads only a cut, a spec-N tag")))
        tags (cut/cut-tags-at req commit)
        _ (when (empty? tags)
            (command/cannot-run! (str cut-arg " (" (subs commit 0 12) ") carries no spec-N tag: regeneration reads only a cut")))
        tag (if (cut/cut-tag? cut-arg) cut-arg (last tags))
        cfg (or (cut/config-at req commit)
                (command/cannot-run! (str "the cut " tag " holds no " command/config-file ": its :clean-room says what a clean room holds")))
        allow (vec (get-in cfg [:clean-room :allow]))
        _ (when (empty? allow)
            (command/cannot-run! (str "the configuration at " tag " allows nothing (:clean-room :allow)")))
        missing (remove #(zero? (:exit (cut/git req {} "cat-file" "-e" (str commit ":" (str/replace % #"/+$" ""))))) allow)
        _ (when (seq missing)
            (command/cannot-run! (str "the cut " tag " holds no " (str/join ", " missing)
                                      ", which the configuration allows: a clean room holds every allowed path")))
        flags (select-keys (:opts req) [:context :component])
        model-rel (or (:model cfg) (str (str/replace (or (:spec-dir cfg) "dhf/spec") #"/+$" "") "/model.edn"))
        scope (if (seq flags)
                (scope-of (or (cut/edn-at req commit model-rel)
                              (command/cannot-run! (str "the cut " tag " holds no model " model-rel ": a scope names its bounded contexts")))
                          flags)
                :project)
        layout (:code-layout cfg)
        _ (when (and (not= :project scope) (not (map? layout)))
            (command/cannot-run! (str "the configuration at " tag " has no :code-layout: a scope cannot say which code it removes")))
        rules {:allow allow :implementation (vec (:implementation cfg)) :scope scope
               :prefixes (when (not= :project scope) (removed-prefixes layout scope))}
        room (command/path req room-arg)
        room-rel (command/rel req room)
        _ (when (and (fs/exists? room) (not (and (fs/directory? room) (empty? (fs/list-dir room)))))
            (command/cannot-run! (str "ROOM " room-arg " exists and is not empty: a clean room is built in a new directory")))
        entries (filterv #(and (= "blob" (:type %)) (kept? rules (:path %))) (tree-entries req commit))
        date (cut/git! req {} "the cut's date cannot be read" "show" "-s" "--format=%cI" commit)]
    (fs/create-dirs room)
    (write-files! req entries (str (fs/absolutize room)))
    (let [room-commit (commit-room! req (str (fs/absolutize room))
                                    (str "Clean room of " tag " (" commit ")"
                                         (when (not= :project scope) (str "\n\nScope: " (pr-str scope))))
                                    date)
          findings (proof-findings req room-rel (str (fs/absolutize room)) entries)]
      {:result {:fence {:room room-rel :cut tag :commit commit :scope scope
                        :files (count entries) :room-commit room-commit
                        :proved? (empty? findings)}}
       :findings findings})))
