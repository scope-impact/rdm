(ns rdm-spec.regeneration.fence
  "Builds a clean room from a cut: one fresh commit of every file committed at
  the cut except the denied ones, for the project, a bounded context, a
  component, a list of them, or a slice; and holds every committed path to
  being denied or kept (dhf/spec/06-regenerate/fence.feature, 11 and 13)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.project :as project]
            [rdm-spec.regeneration.cut :as cut]
            [rdm-spec.spec.parser :as parser]))

(defn cut-name? [s] (boolean (re-matches #"spec-\d+" (str s))))

(defn- under? [path prefix]
  (let [p (str/replace prefix #"/$" "")]
    (or (= path p) (str/starts-with? path (str p "/")))))

(defn impl-paths [cfg] (vec (:implementation cfg)))

(defn implementation? [cfg path] (boolean (some #(under? path %) (impl-paths cfg))))

(defn denied?
  "Whether a committed path is denied: a measurement, generated output, or the
  scope's implementation (all of it when prefixes is nil: the project scope)."
  [cfg path prefixes]
  (let [deny (get-in cfg [:clean-room :deny])]
    (boolean (or (some #(under? path %) (concat (:measurements deny) (:generated deny)))
                 (and (:implementation deny)
                      (implementation? cfg path)
                      (or (nil? prefixes) (some #(str/starts-with? path %) prefixes)))))))

(defn classified-entries [cfg]
  (let [deny (get-in cfg [:clean-room :deny])]
    (concat (get-in cfg [:clean-room :keep]) (:measurements deny) (:generated deny)
            (when (:implementation deny) (impl-paths cfg)))))

(defn unclassified
  "The committed top-level paths, and paths directly under dhf/, neither denied nor kept."
  [cfg paths]
  (let [entries (classified-entries cfg)]
    (vec (sort (distinct
                (for [p paths
                      :when (not (some #(under? p %) entries))
                      :let [parts (str/split p #"/")]]
                  (if (and (= "dhf" (first parts)) (> (count parts) 1)) (str "dhf/" (second parts)) (first parts))))))))

(defn- fill [template ctx comp]
  (-> template
      (str/replace "{context}" (str/replace (str ctx) "-" "_"))
      (str/replace "{component}" (str/replace (str comp) "-" "_"))))

(defn scope-prefixes
  "The implementation path prefixes of scopes [[:context c] [:component c/n] ...]."
  [cfg scopes]
  (let [layout (:code-layout cfg)]
    (vec (distinct
          (for [[kind id] scopes
                t (get layout kind)]
            (if (= kind :context)
              (fill t (name id) nil)
              (fill t (namespace id) (name id))))))))

(defn committed-paths
  "The files committed at rev in the project's folder, relative to it; nil when there is none."
  [root rev]
  (when (zero? (:exit (project/git root "rev-parse" "-q" "--verify" (str rev "^{commit}"))))
    (mapv :path (cut/ls-tree root rev))))

(defn paths-check
  "check's stage: every committed path is denied or kept."
  [run]
  (let [root (:root run)
        paths (when (cut/repo? root) (committed-paths root "HEAD"))]
    {:findings (vec (for [p (unclassified (:cfg run) paths)]
                      (project/finding p "path-unclassified" (str p " is neither denied nor kept: classify it in the configuration's :clean-room"))))}))

;; ------------------------------------------------------------------ the fence task

(defn- split-list [s] (when s (vec (remove str/blank? (map str/trim (str/split s #","))))))

(defn resolve-scopes
  "The scopes asked for, checked against the cut's model: {:scopes [[kind id]] :removed [id] :slice s}."
  [cfg model rel-files committed opts]
  (let [ctxs (set (map :id (mapcat :contexts (:containers model))))
        comps (set (map :id (parser/components model)))
        known (str "the model's bounded contexts are " (str/join ", " (map name (sort-by str ctxs))) ", and the kernel")
        contexts (vec (for [c (split-list (get opts "--context"))]
                        (let [k (keyword c)]
                          (when-not (or (= c "kernel") (ctxs k))
                            (project/cannot-run! (str "the scope " c " names no bounded context: " known)))
                          [:context k])))
        components (vec (for [c (split-list (get opts "--component"))]
                          (let [k (keyword c)]
                            (when-not (namespace k) (project/cannot-run! (str "the scope " c " is not CONTEXT/COMPONENT: " known)))
                            (if (comps k)
                              [:component k]
                              (let [pre (scope-prefixes cfg [[:component k]])]
                                (if (some (fn [f] (some #(str/starts-with? f %) pre)) committed)
                                  [:removed k]
                                  (project/cannot-run! (str "the scope " c " names no component of the model, and the code layout finds no file of it: " known))))))))
        slice (get opts "--slice")
        slice-comps (when slice
                      (when-not (some #{slice} (parser/slices-of rel-files))
                        (project/cannot-run! (str "the cut holds no slice " slice ": the slices are " (str/join ", " (parser/slices-of rel-files)))))
                      (parser/slice-reach model rel-files slice))]
    {:scopes (vec (concat contexts (for [[k id] components :when (= k :component)] [:component id]) (for [c slice-comps] [:component c])))
     :removed (vec (for [[k id] components :when (= k :removed)] id))
     :slice slice}))

(defn fence [run]
  (let [root (:root run)
        [room-arg cut-arg] (:args run)
        room (str (fs/absolutize (fs/path root room-arg)))]
    (when-not (and (cut-name? cut-arg) (cut/tag-commit root cut-arg))
      (project/cannot-run! (str cut-arg " is not a cut: regeneration reads only a cut, a commit tagged spec-N")))
    (when (and (fs/exists? room) (or (not (fs/directory? room)) (seq (fs/list-dir room))))
      (project/cannot-run! (str "the room " room-arg " already exists and is not empty")))
    (let [entries (cut/ls-tree root cut-arg)
          paths (mapv :path entries)
          cfg (project/with-defaults (or (cut/read-edn-at root cut-arg project/config-path)
                                         (project/cannot-run! (str "the cut " cut-arg " holds no " project/config-path))))
          model (or (cut/read-edn-at root cut-arg (:model cfg)) {})
          sd (:spec-dir cfg)
          rel-files (for [p paths :when (str/starts-with? p (str sd "/"))] (subs p (inc (count sd))))
          {:keys [scopes removed slice]} (resolve-scopes cfg model rel-files paths (:opts run))
          scoped? (or (seq scopes) (seq removed))
          prefixes (when scoped? (scope-prefixes cfg (concat scopes (for [r removed] [:component r]))))
          denied (filter #(denied? cfg (:path %) prefixes) entries)
          kept (remove #(denied? cfg (:path %) prefixes) entries)
          removed-impl (filter #(implementation? cfg (:path %)) denied)
          blobs (into {} (for [e removed-impl :when (not= (:sha e) cut/empty-blob)] [(:sha e) (:path e)]))
          copies (for [e kept :when (and (not (implementation? cfg (:path e))) (blobs (:sha e)))] [(:path e) (blobs (:sha e))])]
      (if (seq copies)
        {:exit 1
         :findings (vec (for [[k i] copies]
                          (project/finding k "fence-bytes" (str k " carries the bytes of the implementation file " i ": no room was built"))))}
        (let [commit (cut/build-room! root cut-arg room (set (map :path denied)))]
          {:result {:fence (cond-> {:room room-arg :cut cut-arg :commit commit
                                    :scope (if scoped?
                                             (vec (concat (for [[k id] scopes] {k id}) (for [r removed] {:component r})))
                                             :project)
                                    :kept (count kept) :denied (count denied)}
                             slice (assoc :slice slice)
                             (seq removed) (assoc :removed-components removed))}})))))
