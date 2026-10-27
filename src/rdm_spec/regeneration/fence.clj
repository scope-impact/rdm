(ns rdm-spec.regeneration.fence
  "The fence (dhf/spec/regeneration/fence.feature): builds a clean room from
  a cut, and only from a cut: one fresh root commit holding every file
  committed at the cut except the denied ones (the scope's implementation,
  the measurements, the generated output), read from the cut's committed tree
  alone, so nothing untracked or ignored reaches a room. A kept file carrying
  the bytes of a removed implementation file stops it. Its paths stage holds
  every committed path to being denied or kept, for rdm-spec check."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.regeneration.cut :as cut]))

(defn under?
  "Whether path lies under prefix: the prefix itself, a path below it, or,
  for a prefix ending in neither / nor a file, a path starting with it."
  [prefix path]
  (let [p (str prefix)]
    (cond
      (str/ends-with? p "/") (str/starts-with? path p)
      (str/ends-with? p "_") (str/starts-with? path p)
      :else (or (= path p) (str/starts-with? path (str p "/"))))))

(defn- fill [pattern c n]
  (-> pattern (str/replace "{context}" (str c)) (str/replace "{component}" (str/replace (str n) "-" "_"))))

(defn scope-prefixes
  "The implementation paths a scope removes: every implementation path for
  the project, else the code layout's for each context and component."
  [config {:keys [context component]}]
  (if (and (empty? context) (empty? component))
    (vec (:implementation config))
    (vec (concat
          (for [c context p (get-in config [:code-layout :context])] (fill p c nil))
          (for [cc component :let [[c n] (str/split cc #"/" 2)] p (get-in config [:code-layout :component])] (fill p c n))))))

(defn denied-prefixes [config]
  (let [{:keys [measurements generated]} (get-in config [:clean-room :deny])]
    (vec (concat measurements generated))))

(defn- impl? [config path] (some #(under? % path) (:implementation config)))

(defn unclassified
  "Every committed top-level path, and every path directly under dhf/, that
  the configuration neither denies nor keeps."
  [config paths]
  (let [deny (concat (when (get-in config [:clean-room :deny :implementation]) (:implementation config))
                     (denied-prefixes config))
        keep (get-in config [:clean-room :keep])
        heads (distinct (for [p paths
                              :let [[a b] (str/split p #"/")]]
                          (if (and (= "dhf" a) b) (str a "/" b) a)))]
    (vec (for [h heads
               :when (not= "dhf" h)
               :when (not (some #(under? % h) (concat deny keep)))]
           h))))

(defn paths-stage
  "rdm-spec check: every committed path is denied or kept."
  [{:keys [root config]}]
  (let [{:keys [exit out]} (cmd/git root "ls-tree" "-r" "--name-only" "HEAD")]
    (if-not (= 0 exit)
      {}
      {:findings (vec (for [p (unclassified config (remove str/blank? (str/split-lines out)))]
                        (cmd/finding p nil "path-unclassified"
                                     (str p " is neither denied nor kept by the configuration's :clean-room: deny it or keep it"))))})))

;; ---------------------------------------------------------------------------
;; Building a room

(defn tree-at
  "[{:mode :type :sha :path}] of every file committed at rev, relative to root."
  [root rev]
  (vec (for [l (str/split-lines (cmd/git! root "ls-tree" "-r" rev))
             :when (seq l)
             :let [[meta path] (str/split l #"\t" 2)
                   [mode type sha] (str/split meta #" ")]]
         {:mode mode :type type :sha sha :path path})))

(defn- model-scopes [model]
  {:contexts (vec (concat (for [c (:containers model) cx (:contexts c)] (name (:id cx))) ["kernel"]))
   :components (set (concat (for [c (:containers model) cx (:contexts c) k (:components cx)] (subs (str (:id k)) 1))
                            (for [k (:kernel model)] (subs (str (:id k)) 1))))})

(def empty-blob "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")

(defn stage [{:keys [root args opts]}]
  (let [[room-arg rev] args
        c (or (cut/resolve-cut root rev)
              (cmd/cannot-run! (str rev " is not a cut: regeneration reads only a cut, a commit tagged spec-N")))
        room (fs/absolutize (fs/path root room-arg))
        _ (when (and (fs/exists? room) (or (not (fs/directory? room)) (seq (fs/list-dir room))))
            (cmd/cannot-run! (str "the room " room-arg " already exists: the fence builds a room only where nothing is")))
        config (or (try (cmd/read-edn (cmd/read-at root c "dhf/harness/config.edn")) (catch Exception _ nil))
                   (cmd/cannot-run! (str "the cut " c " holds no readable dhf/harness/config.edn")))
        model (or (try (cmd/read-edn (cmd/read-at root c (:model config "dhf/spec/model.edn"))) (catch Exception _ nil)) {})
        {:keys [contexts components]} (model-scopes model)
        files (tree-at root c)
        layout-finds? (fn [cc] (let [ps (scope-prefixes config {:component [cc]})]
                                 (some (fn [f] (some #(under? % (:path f)) ps)) files)))
        _ (doseq [cx (:context opts) :when (not (some #{cx} contexts))]
            (cmd/cannot-run! (str "the model names no bounded context " cx ": its bounded contexts are "
                                  (str/join ", " (butlast contexts)) ", and the kernel")))
        removed-components (vec (for [cc (:component opts) :when (not (components cc))]
                                  (if (layout-finds? cc)
                                    cc
                                    (cmd/cannot-run! (str "the model names no component " cc " and the code layout finds no file of it: its bounded contexts are "
                                                          (str/join ", " (butlast contexts)) ", and the kernel")))))
        scope (scope-prefixes config opts)
        removed? (fn [p] (and (impl? config p) (some #(under? % p) scope)))
        denied? (fn [p] (or (removed? p) (some #(under? % p) (denied-prefixes config))))
        removed (filter #(removed? (:path %)) files)
        kept (remove #(denied? (:path %)) files)
        removed-shas (into {} (for [f removed :when (not= empty-blob (:sha f))] [(:sha f) (:path f)]))
        copies (for [f kept :let [src (removed-shas (:sha f))] :when (and src (not (impl? config (:path f))))]
                 (cmd/finding (:path f) nil "kept-carries-implementation"
                              (str (:path f) " carries the bytes of the removed implementation file " src
                                   ": no kept file may, so no room was built")))]
    (if (seq copies)
      {:findings (vec copies)}
      (let [tar (cmd/temp-file ".tar")]
        (try
          (fs/create-dirs room)
          (cmd/git! (str room) "init" "-q")
          (when (seq kept)
            (apply cmd/git! root "archive" "--format=tar" "-o" (str tar) c "--" (map :path kept))
            (let [r (cmd/sh {:dir (str room)} "tar" "-xf" (str tar))]
              (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "the room could not be written: " (:err r))))))
          (let [env (merge {"GIT_AUTHOR_NAME" "rdm-spec fence" "GIT_AUTHOR_EMAIL" "fence@rdm-spec.invalid"
                            "GIT_COMMITTER_NAME" "rdm-spec fence" "GIT_COMMITTER_EMAIL" "fence@rdm-spec.invalid"}
                           (select-keys (into {} (System/getenv)) ["GIT_AUTHOR_NAME" "GIT_AUTHOR_EMAIL" "GIT_COMMITTER_NAME" "GIT_COMMITTER_EMAIL"]))
                commit (fn [& a] (let [r (apply cmd/sh {:dir (str room) :env env} "git" "-c" "core.hooksPath=/dev/null"
                                                "-c" "commit.gpgsign=false" a)]
                                   (when-not (= 0 (:exit r)) (cmd/cannot-run! (str "the room's commit failed: " (:err r))))
                                   r))]
            (commit "add" "-A" "-f" "--" ".")
            (commit "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "The clean room of " c))
            {:result {:fence {:room room-arg :cut c :commit (cmd/git! (str room) "rev-parse" "HEAD")
                              :files (count kept) :removed (mapv :path removed)
                              :removed-components removed-components}}})
          (finally (fs/delete-if-exists tar)))))))
