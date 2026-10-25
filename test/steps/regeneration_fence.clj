(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature.

  Context keys set here: :dir (the project, a git repository whose commit
  tagged spec-3 is the cut), :implementation (the implementation files it
  wrote, {rel text}), :foreign (blob ids of files at the cut or in its
  history that the clean room must not hold)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- cut-project!
  "A copy of the room holding an implementation, `edit!` applied, committed
  and tagged spec-3."
  ([ctx] (cut-project! ctx (fn [_])))
  ([ctx edit!]
   (let [dir (rs/repo! (s/copy-of-room!))
         impl (rs/synthesize-implementation! dir)]
     (edit! dir)
     (rs/commit! dir "the cut")
     (rs/tag! dir "spec-3")
     (assoc ctx :dir dir :implementation impl))))

(defn- the-room [ctx]
  (or (:room ctx) (s/fail! "no ROOM was named")))

(defn- cfg [ctx] (kc/config-of (:dir ctx)))

(defn- room-files [ctx]
  (let [room (the-room ctx)]
    (s/check! (fs/directory? room) (str "ROOM was not built\n" (s/show (kc/the-run ctx))))
    (rs/files-under room)))

;; ---------------------------------------------------------------------------
;; Only a cut

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx]
    (let [ctx (cut-project! ctx)
          dir (:dir ctx)]
      (s/write! dir "NOTES.md" "a commit after the cut\n")
      (rs/commit! dir "after the cut")
      (s/check! (str/blank? (rs/git! dir "tag" "--points-at" "HEAD")) "HEAD carries a tag")
      ctx)))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (kc/refused-naming! ctx "regeneration reads only a cut")
    (s/check! (not (fs/exists? (the-room ctx))) "a room was made")
    nil))

;; ---------------------------------------------------------------------------
;; The allow-list

(defn- listed
  "The paths of a list written `a, b, c and d`."
  [text]
  (vec (remove str/blank? (map str/trim (str/split text #",\s*|\s+and\s+")))))

(defstep #"the configuration allows (.+), and the cut also holds (\S+)"
  (fn [ctx allowed extra]
    (let [want (listed allowed)
          ctx (cut-project!
               ctx
               (fn [dir]
                 (s/check! (= (set want) (set (get-in (kc/config-of dir) [:clean-room :allow])))
                           (str "the configuration allows " (pr-str (get-in (kc/config-of dir) [:clean-room :allow]))))
                 ;; history: a file deleted before the cut
                 (s/write! dir "src/rdm_spec/old.clj" "(ns rdm-spec.old) ; an older implementation\n")
                 (rs/commit! dir "an older implementation")
                 (fs/delete (fs/file dir "src/rdm_spec/old.clj"))
                 (s/write! dir (str extra "golden.edn") "{:golden \"a measurement\"}\n")
                 (s/write! dir "README.md" "# A project\n")))
          dir (:dir ctx)
          foreign (vec (for [rev ["spec-3:README.md" (str "spec-3:" extra "golden.edn") "HEAD~1:src/rdm_spec/old.clj"
                                  "spec-3:test.clj"]]
                         (rs/git! dir "rev-parse" rev)))]
      (assoc ctx :allowed want :foreign foreign))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)
          room (the-room ctx)
          commits (str/split-lines (rs/git! room "rev-list" "--all"))]
      (s/check! (= 1 (count commits)) (str "ROOM holds " (count commits) " commits\n" (s/show r)))
      (s/check! (= 1 (count (str/split (rs/git! room "rev-list" "--parents" "-n" "1" (first commits)) #" ")))
                "the commit has a parent")
      nil)))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (let [at-cut (rs/files-at (:dir ctx) "spec-3")
          want (into (sorted-map) (filter (fn [[r _]] (some #(rs/under? r %) (:allowed ctx))) at-cut))
          got (room-files ctx)]
      (s/check! (seq want) "the cut holds no allowed file")
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "ROOM holds other files: " (pr-str (sort (remove (set (keys want)) (keys got))))
                     "; lacks: " (pr-str (sort (remove (set (keys got)) (keys want))))))
      (doseq [[r t] want] (s/check! (= t (get got r)) (str r " changed")))
      nil)))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (let [room (the-room ctx)
          objs (for [l (str/split-lines (rs/git! room "rev-list" "--objects" "--all"))
                     :let [[id path] (str/split l #" " 2)]]
                 [id path])
          all (set (map #(first (str/split % #" "))
                        (str/split-lines (rs/git! room "cat-file" "--batch-all-objects" "--batch-check"))))]
      (doseq [[_ path] objs :when (and path (not (str/blank? path)))]
        (s/check! (some #(or (rs/under? path %) (rs/under? % path)) (:allowed ctx))
                  (str "ROOM holds an object of the path " path)))
      (s/check! (= all (set (map first objs))) "ROOM holds objects no commit reaches")
      (doseq [id (:foreign ctx)]
        (s/check! (not (contains? all id)) (str "ROOM holds the object " id " of a path not allowed")))
      nil)))

(defstep #"the configuration allows dhf/wiki and the cut holds no dhf/wiki"
  (fn [ctx]
    (cut-project! ctx (fn [dir]
                        (s/check! (some #{"dhf/wiki"} (get-in (kc/config-of dir) [:clean-room :allow]))
                                  "the configuration does not allow dhf/wiki")
                        (fs/delete-tree (fs/file dir "dhf/wiki"))))))

;; ---------------------------------------------------------------------------
;; Scopes

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx] (cut-project! ctx)))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [room (the-room ctx)]
      (s/check! (seq (:implementation ctx)) "the cut held no implementation")
      (doseq [p (:implementation (cfg ctx))]
        (s/check! (not (fs/exists? (fs/file room p))) (str "ROOM holds " p)))
      nil)))

(defstep #"the code layout places (?:context|component) code at (\S+) and its unit tests at (\S+)"
  (fn [ctx code tests]
    (let [ctx (cut-project! ctx)
          layout (:code-layout (cfg ctx))
          pattern (fn [p] (-> p (str/replace "<context>" "{context}") (str/replace "<component>" "{component}")))
          placed (set (concat (:context layout) (:component layout)))]
      (doseq [p [code tests]]
        (s/check! (contains? placed (pattern p)) (str "the code layout does not place " p ": " (pr-str layout))))
      ctx)))

(defn- held-at-cut [ctx]
  (rs/implementation-files (cfg ctx) (rs/files-at (:dir ctx) "spec-3")))

(defn- none-held!
  "Checks ROOM holds no file `gone?` names, and that the cut held some."
  [ctx gone? what]
  (kc/exits! ctx 0)
  (let [files (room-files ctx)]
    (s/check! (some gone? (keys (held-at-cut ctx))) (str "the cut held no file " what))
    (doseq [r (keys files)] (s/check! (not (gone? r)) (str "ROOM holds " r)))
    nil))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b]
    (none-held! ctx (fn [r] (or (str/starts-with? r a) (str/starts-with? r b))) (str "under " a " or " b))))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b]
    (none-held! ctx #{a b} (str a " or " b))))

(defstep #"ROOM holds no file of the bounded contexts (\S+) and (\S+) or of the component (\S+)"
  (fn [ctx c1 c2 comp]
    (let [prefixes (rs/scope-prefixes (cfg ctx) ["--context" (str c1 "," c2) "--component" comp])]
      (none-held! ctx #(rs/removed? prefixes %) (str "of " c1 ", " c2 " or " comp)))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [prefixes (rs/scope-prefixes (cfg ctx) (:args (kc/the-run ctx)))
          _ (s/check! (seq prefixes) "the command names no scope")
          want (into (sorted-map) (remove (fn [[r _]] (rs/removed? prefixes r)) (held-at-cut ctx)))
          got (rs/implementation-files (cfg ctx) (room-files ctx))]
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "ROOM's implementation differs from the cut's kept files: extra "
                     (pr-str (sort (remove (set (keys want)) (keys got)))) ", missing "
                     (pr-str (sort (remove (set (keys got)) (keys want))))))
      (doseq [[r t] want] (s/check! (= t (get got r)) (str r " changed")))
      nil)))

(defstep #"it exits 2 and names (\S+) and the model's bounded contexts and kernel"
  (fn [ctx word]
    (apply kc/refused-naming! ctx word "kernel" (rs/context-names (rs/model-of (:dir ctx))))
    nil))
