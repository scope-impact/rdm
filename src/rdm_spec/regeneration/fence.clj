(ns rdm-spec.regeneration.fence
  "Component Fence (regeneration context): builds a clean room from a cut,
  the cut's committed files minus the denied ones, for the whole project or
  the scopes a spec change touched, as one root commit; and holds every
  committed path to being denied or kept."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]))

(defn strip [p] (str/replace (str p) #"/+$" ""))

(defn covered?
  "Whether PATH is ENTRY or under it."
  [entry path]
  (let [e (strip entry)] (or (= e ".") (= path e) (str/starts-with? path (str e "/")))))

(defn denied-entries
  "Every path the configuration denies whole: the implementation, the measurements, the generated output."
  [cfg]
  (let [deny (get-in cfg [:clean-room :deny])]
    (vec (concat (when (:implementation deny) (:implementation cfg))
                 (:measurements deny)
                 (:generated deny)))))

(defn classified? [cfg path]
  (some #(covered? % path) (concat (denied-entries cfg) (get-in cfg [:clean-room :keep]))))

(defn committed-paths
  "The project's committed files, relative to its root; nil outside a repository."
  []
  (let [root (project/root)]
    (when (project/git-repo? root)
      (let [r (project/git root "ls-files" "-z" "--full-name" "--" ".")
            prefix (project/git-prefix)]
        (when (= 0 (:exit r))
          (vec (for [p (str/split (:out r) #"\u0000") :when (not (str/blank? p))]
                 (subs p (min (count p) (count prefix))))))))))

(defn classify-findings
  "Every committed top-level path, and every path directly under dhf/, is denied or kept."
  [cfg paths]
  (let [heads (distinct (for [p paths
                              :let [parts (str/split p #"/")]]
                          (if (and (= "dhf" (first parts)) (> (count parts) 1))
                            (str "dhf/" (second parts))
                            (first parts))))]
    (for [h (sort heads) :when (not (classified? cfg h))]
      (project/finding h "path-unclassified"
                       (str h " is committed and neither denied nor kept: add it to the configuration's :clean-room :deny or :keep")))))

(defn paths-stage
  "The check's stage holding every committed path to being denied or kept."
  [{:keys [cfg]}]
  (let [paths (committed-paths)]
    {:result {:paths {:committed (count paths)}}
     :findings (if (and paths (:clean-room cfg)) (vec (classify-findings cfg paths)) [])
     :warnings []}))

;; ------------------------------------------------------------------ building a clean room

(defn tree-files
  "Every file committed at REV in the project's folder: [{:path :mode :sha}]."
  [rev]
  (let [out (project/git! (project/toplevel) "ls-tree" "-r" "-z" (project/tree-ish rev))]
    (vec (for [e (str/split out #"\u0000") :when (not (str/blank? e))
               :let [[meta path] (str/split e #"\t" 2) [mode _ sha] (str/split meta #" ")]]
           {:path path :mode mode :sha sha}))))

(def empty-blob "e69de29bb2d1d6434b8b29ae775ad8c2d48c5391")

(defn layout-prefixes
  "The code layout's paths for a context, or a component of a context."
  [cfg ctx comp]
  (let [fill #(-> % (str/replace "{context}" (str/replace ctx "-" "_"))
                  (str/replace "{component}" (str/replace (or comp "") "-" "_")))]
    (if comp
      (map fill (get-in cfg [:code-layout :component]))
      (map fill (get-in cfg [:code-layout :context])))))

(defn layout-match? [prefixes path] (some #(str/starts-with? path %) prefixes))

(defn split-list [s] (when s (vec (remove str/blank? (map str/trim (str/split s #","))))))

(defn resolve-scopes
  "The prefixes of the implementation a scope removes, and the components the
  cut removed. Refuses a scope the model does not name."
  [cfg model tree opts files]
  (let [contexts (map #(name (:id %)) (project/contexts model))
        comps (project/components model)
        known (str/join ", " (concat contexts ["kernel"]))
        impl (filter #(some (fn [i] (covered? i (:path %))) (:implementation cfg)) files)
        ctx-prefixes (for [c (split-list (get opts "--context"))]
                       (if (or (= c "kernel") (some #{c} contexts))
                         (layout-prefixes cfg c nil)
                         (project/cannot-run! (str "the scope --context " c " names no bounded context of the model at the cut; its contexts are " known))))
        comp-scopes (for [c (split-list (get opts "--component"))]
                      (let [[cx n] (str/split c #"/" 2)
                            in-model (some #(= c (str (namespace (:id %)) "/" (name (:id %)))) comps)
                            ps (layout-prefixes cfg (str cx) n)]
                        (cond
                          (nil? n) (project/cannot-run! (str "the scope --component " c " is not CONTEXT/NAME"))
                          in-model {:prefixes ps}
                          (some #(layout-match? ps (:path %)) impl) {:prefixes ps :removed c}
                          :else (project/cannot-run! (str "the scope --component " c " names no component of the model at the cut, and the code layout finds no file of it; the model's contexts are " known)))))
        slice-scopes (for [s (split-list (get opts "--slice"))]
                       (let [spec-dirs (map :dir (project/specs cfg))
                             feats (for [sd spec-dirs
                                         f (filter #(str/ends-with? % ".feature") (map :path files))
                                         :when (= s (project/slice-of sd f))] [sd f])]
                         (when-not (and (project/slices? cfg) (seq (filter #(str/starts-with? (:path %) (str (first spec-dirs) "/" s "/")) files)))
                           (project/cannot-run! (str "the scope --slice " s " names no slice the cut holds")))
                         {:prefixes (mapcat (fn [[sd f]]
                                              (when-let [c (project/owner cfg {:dir sd} model f)]
                                                (layout-prefixes cfg (if (= :kernel (:context c)) "kernel" (name (:context c))) (name (:id c)))))
                                            feats)}))]
    {:prefixes (vec (concat (apply concat ctx-prefixes) (mapcat :prefixes comp-scopes) (mapcat :prefixes slice-scopes)))
     :removed-components (vec (keep :removed comp-scopes))
     :scoped? (boolean (or (get opts "--context") (get opts "--component") (get opts "--slice")))}))

(defn fence-task [{:keys [cfg args opts]}]
  (let [[room-arg cut-arg] args
        _ (do ((requiring-resolve 'rdm-spec.kernel.plugins/validate!) cfg))
        gate ((requiring-resolve 'rdm-spec.kernel.plugins/gate!) cfg {:request :Gate :task :fence :cut cut-arg})]
    (if-not (:passed? gate)
      (project/report {} (:findings gate) (:warnings gate))
      (let [cut ((requiring-resolve 'rdm-spec.regeneration.cut/resolve-cut) cut-arg)
            _ (when-not cut (project/cannot-run! (str cut-arg " is not a cut: regeneration reads only a cut, a commit tagged spec-N")))
            room (project/abs room-arg)
            _ (when (and (fs/exists? room) (seq (fs/list-dir room)))
                (project/cannot-run! (str "the room " room-arg " exists and is not empty: a clean room is built in a new directory")))]
        (project/with-temp-dir [t "rdm-cut-"]
          (project/export! cut t)
          (let [cut-cfg (project/config t)
                model (or (project/read-model t (first (project/specs cut-cfg))) {})
                files (tree-files cut)
                {:keys [prefixes removed-components scoped?]} (resolve-scopes cut-cfg model t opts files)
                impl? (fn [p] (and (get-in cut-cfg [:clean-room :deny :implementation])
                                   (some #(covered? % p) (:implementation cut-cfg))))
                other-denied? (fn [p] (some #(covered? % p) (concat (get-in cut-cfg [:clean-room :deny :measurements])
                                                                    (get-in cut-cfg [:clean-room :deny :generated]))))
                removed-impl? (fn [p] (and (impl? p) (or (not scoped?) (layout-match? prefixes p))))
                removed (filter #(removed-impl? (:path %)) files)
                kept (remove #(or (removed-impl? (:path %)) (other-denied? (:path %))) files)
                by-sha (group-by :sha (filter #(not= empty-blob (:sha %)) removed))
                copies (for [k kept :when (not (impl? (:path k))) :let [r (first (by-sha (:sha k)))] :when r] [k r])]
            (if (seq copies)
              (project/report {} (for [[k r] copies]
                                   (project/finding (:path k) "fence-copy"
                                                    (str (:path k) " carries the bytes of the implementation file " (:path r) ", which the room may not hold"))))
              (do
                (fs/create-dirs room)
                (doseq [{:keys [path]} kept]
                  (let [from (fs/path t path) to (fs/path room path)]
                    (fs/create-dirs (fs/parent to))
                    (if (fs/sym-link? from)
                      (fs/create-sym-link to (fs/read-link from))
                      (fs/copy from to {:replace-existing true}))))
                (project/git! room "init" "-q")
                (project/git! room "add" "-A" "-f" "--" ".")
                (project/git! room "-c" "user.name=rdm-spec" "-c" "user.email=rdm-spec@localhost"
                              "commit" "-q" "--no-verify" "--allow-empty" "-m" (str "The clean room of " cut))
                (project/report {:fence (cond-> {:room room-arg :cut cut :files (count kept)
                                                 :scope (if scoped? (into {} (for [k ["--context" "--component" "--slice"] :when (get opts k)]
                                                                               [(keyword (subs k 2)) (split-list (get opts k))]))
                                                            :project)}
                                          (seq removed-components) (assoc :removed-components removed-components))}
                                (:warnings gate))))))))))
