(ns rdm-spec.regeneration.fence
  "The fence (dhf/spec/regeneration/fence.feature): builds a clean room from a
  cut, one fresh root commit holding the cut's committed files minus the
  denied ones (the scope's implementation, the measurements, the generated
  output), refusing when a kept file carries a removed implementation file's
  bytes. Its paths check holds every committed path to being denied or kept."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.command :as cmd]
            [rdm-spec.regeneration.cut :as cut]))

(defn- under? [path entry]
  (let [e (str/replace entry #"/$" "")]
    (or (= path e) (str/starts-with? path (str e "/")))))

(defn prefixes
  "The code layout's path prefixes of one scope, {:context c} or {:component \"c/n\"}."
  [config {:keys [context component]}]
  (let [fill (fn [s c n] (-> s (str/replace "{context}" (str/replace c "-" "_"))
                             (str/replace "{component}" (str/replace (or n "") "-" "_"))))]
    (if component
      (let [[c n] (str/split component #"/" 2)]
        (mapv #(fill % c n) (get-in config [:code-layout :component])))
      (mapv #(fill % context nil) (get-in config [:code-layout :context])))))

(defn implementation? [config path]
  (some #(under? path %) (:implementation config)))

(defn denied-fn
  "A predicate of a path: is it denied from a room of these scopes (nil, the
  project)?"
  [config scopes]
  (let [deny (get-in config [:clean-room :deny])
        others (concat (:measurements deny) (:generated deny))
        scope-prefixes (mapcat #(prefixes config %) scopes)]
    (fn [path]
      (boolean
       (or (some #(under? path %) others)
           (when (:implementation deny)
             (if (empty? scopes)
               (implementation? config path)
               (some #(str/starts-with? path %) scope-prefixes))))))))

(defn unclassified
  "The committed paths (top-level, then directly under dhf/) neither denied nor kept."
  [config top dhf]
  (let [deny (get-in config [:clean-room :deny])
        entries (concat (when (:implementation deny) (:implementation config))
                        (:measurements deny) (:generated deny)
                        (get-in config [:clean-room :keep]))
        classified? (fn [p] (some #(or (under? p %) (str/starts-with? % (str p "/"))) entries))]
    (vec (remove classified? (concat top dhf)))))

(defn- ls [root rev path]
  (let [{:keys [exit out]} (cmd/git root "ls-tree" "--name-only" rev (or path "."))]
    (if (= 0 exit) (remove str/blank? (str/split-lines out)) [])))

(defn paths-stage
  "Every committed top-level path, and every path directly under dhf/, is
  denied or kept."
  [{:keys [root config]}]
  (if (or (nil? (:clean-room config)) (nil? (cut/commit-of root "HEAD")))
    {}
    (let [top (ls root "HEAD" nil)
          dhf (when (some #{"dhf"} top) (ls root "HEAD" "dhf/"))]
      {:findings (vec (for [p (unclassified config top dhf)]
                        (cmd/finding p nil "path-unclassified"
                                     (str p " is committed and neither denied nor kept: classify it in the configuration's :clean-room"))))})))

;; ---------------------------------------------------------------------------
;; Building a clean room

(defn- tree-at
  "[{:path :sha :mode}] of every file committed at rev."
  [root rev]
  (for [l (str/split-lines (cmd/git! root "ls-tree" "-r" "--full-tree" rev))
        :let [[_ mode type sha path] (re-matches #"(\S+) (\S+) (\S+)\t(.*)" l)]
        :when (#{"blob"} type)]
    {:path path :sha sha :mode mode}))

(def empty-blob "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391")

(defn- model-scopes
  "The model's bounded contexts and components at the cut, and the kernel's."
  [root rev config]
  (let [m (try (cmd/read-edn (cmd/read-at root rev (:model config "dhf/spec/model.edn"))) (catch Exception _ nil))
        ctxs (vec (for [c (:containers m) cx (:contexts c)] (name (:id cx))))
        comps (set (concat (for [c (:containers m) cx (:contexts c) k (:components cx)] (subs (str (:id k)) 1))
                           (for [k (:kernel m)] (subs (str (:id k)) 1))))]
    {:contexts ctxs :components comps}))

(defn- parse-scopes [opts]
  (vec (concat (for [c (some-> (:context opts) (str/split #",")) :when (seq c)] {:context c})
               (for [c (some-> (:component opts) (str/split #",")) :when (seq c)] {:component c}))))

(defn stage [{:keys [root args opts]}]
  (let [[room-arg cut-arg] args
        cut (or (cut/resolve-cut root cut-arg)
                (cmd/cannot-run! (str cut-arg " is not a cut: regeneration reads only a cut, a commit tagged spec-N")))
        room (fs/absolutize (fs/path root room-arg))
        _ (when (and (fs/exists? room) (or (not (fs/directory? room)) (seq (fs/list-dir room))))
            (cmd/cannot-run! (str "the room " room-arg " already exists and is not empty")))
        config (or (try (cmd/read-edn (cmd/read-at root cut "dhf/harness/config.edn")) (catch Exception _ nil))
                   (cmd/cannot-run! (str "the cut " cut " holds no readable dhf/harness/config.edn")))
        scopes (parse-scopes opts)
        {:keys [contexts components]} (model-scopes root cut config)
        files (tree-at root cut)
        layout-finds (fn [s] (seq (filter (fn [f] (some #(str/starts-with? (:path f) %) (prefixes config s))) files)))
        known (str "the model's bounded contexts are " (str/join ", " contexts) ", and the kernel")
        removed-components
        (vec (for [s scopes]
               (cond
                 (:context s) (when-not (or (= "kernel" (:context s)) (some #{(:context s)} contexts))
                                (cmd/cannot-run! (str "the scope --context " (:context s) " names no bounded context: " known)))
                 (contains? components (:component s)) nil
                 (layout-finds s) (:component s)
                 :else (cmd/cannot-run! (str "the scope --component " (:component s)
                                             " names no component of the model, and the code layout finds no file of it: " known)))))
        denied? (denied-fn config scopes)
        removed (filter #(denied? (:path %)) files)
        kept (remove #(denied? (:path %)) files)
        removed-impl (filter #(implementation? config (:path %)) removed)
        by-sha (group-by :sha (remove #(= empty-blob (:sha %)) removed-impl))
        copies (for [k kept :let [same (by-sha (:sha k))] :when same] [k (first same)])]
    (if (seq copies)
      {:findings (vec (for [[k i] copies]
                        (cmd/finding (:path k) nil "fence-copy"
                                     (str (:path k) " carries the same bytes as the implementation file " (:path i)
                                          ", which the room must not hold: no room was built"))))}
      (let [tar (cmd/temp-file ".tar")
            list-file (cmd/temp-file ".txt")]
        (try
          (let [a (cmd/sh {:dir root} "git" "archive" "--format=tar" "-o" (str tar) cut)]
            (when-not (= 0 (:exit a)) (cmd/cannot-run! (str "git archive failed: " (:err a)))))
          (fs/create-dirs room)
          (spit (fs/file list-file) (str/join "\n" (map :path kept)))
          (when (seq kept)
            (let [x (cmd/sh {:dir (str room)} "tar" "-x" "-f" (str tar) "-T" (str list-file))]
              (when-not (= 0 (:exit x)) (cmd/cannot-run! (str "tar failed: " (:err x))))))
          (finally (fs/delete-if-exists tar) (fs/delete-if-exists list-file)))
        (let [g (fn [& a] (apply cmd/git! (str room) "-c" "user.name=rdm-spec" "-c" "user.email=rdm-spec@localhost"
                                 "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" a))]
          (g "init" "-q")
          (g "add" "-A")
          (g "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "Clean room of " cut))
          {:result {:fence {:room room-arg :cut cut :commit (g "rev-parse" "HEAD")
                            :scopes (if (seq scopes) scopes :project)
                            :removed (vec (sort (map :path removed)))
                            :removed-components (vec (remove nil? removed-components))}}})))))
