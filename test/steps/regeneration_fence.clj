(ns steps.regeneration-fence
  "Step definitions of dhf/spec/regeneration/fence.feature: a project laid
  out as rdm-spec is, with an implementation, cut at spec-3."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep fail!]]
            [steps.kernel-command :as k]))

(def layout
  {:implementation ["src" "test" "test.clj" "bb.edn"]
   :code-layout {:context ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/" "test/steps/{context}_"]
                 :component ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"
                             "test/steps/{context}_{component}.clj"]}})

(def components
  [["verification" "status"] ["verification" "release"] ["spec" "parser"] ["spec" "check"] ["knowledge" "sources"]
   ["publishing" "site"] ["kernel" "bus"] ["kernel" "core"]])

(defn code-files []
  (into {"test.clj" "(println :tests)\n" "bb.edn" "{:paths [\"src\"]}\n"}
        (for [[c n] components
              [p text] [[(str "src/rdm_spec/" c "/" n ".clj") (str "(ns rdm-spec." c "." n ")\n")]
                        [(str "test/rdm_spec/" c "/" n "_test.clj") (str "(ns rdm-spec." c "." n "-test)\n")]
                        [(str "test/steps/" c "_" n ".clj") (str "(ns steps." c "-" n ")\n")]]]
          [p text])))

(defn fence-project!
  "The project, its implementation committed and tagged spec-3."
  [ctx & [{:keys [config extra]}]]
  (let [ctx (k/project! ctx {:config (merge layout config)
                             :model-spec {:contexts [["verification" ["status" "release"]] ["spec" ["parser" "check"]]
                                                     ["knowledge" ["sources"]] ["publishing" ["site"]]]
                                          :kernel ["core" "bus"]}
                             :extra (merge (code-files) {"README.md" "# A project\n" "site/index.html" "<p>site</p>\n"} extra)})]
    (k/tag-cut! ctx "spec-3")))

(defn room [ctx] (k/guard! (get-in ctx [:ph "ROOM"])))

(defn tree
  "Every file of a directory but .git, relative, sorted."
  [d]
  (->> (fs/glob d "**" {:hidden true})
       (filter #(fs/regular-file? % {:nofollow-links true}))
       (map #(str (fs/relativize d %)))
       (remove #(str/starts-with? % ".git/"))
       sort vec))

(defn committed [ctx rev]
  (vec (sort (str/split-lines (k/git (:dir ctx) "ls-tree" "-r" "--name-only" rev)))))

(defn impl? [p] (some #(or (= p %) (str/starts-with? p (str % "/"))) (:implementation layout)))

(defn- same-bytes? [ctx rev p]
  (= (k/git (:dir ctx) "rev-parse" (str rev ":" p))
     (str/trim (:out (k/sh (room ctx) {} "git" "hash-object" p)))))

(defn kept-unchanged
  "Asserts ROOM holds exactly the files committed at rev that keep? keeps, unchanged."
  [ctx rev keep?]
  (let [want (filterv keep? (committed ctx rev))]
    (assert (= want (tree (room ctx))) (str "ROOM holds " (pr-str (tree (room ctx))) " not " (pr-str want)))
    (doseq [p want] (assert (same-bytes? ctx rev p) (str p " changed")))
    ctx))

(def measured? #(str/starts-with? % "dhf/cycles/"))
(def generated? #(str/starts-with? % "site/"))

(defn others-kept
  "Asserts ROOM holds every file at the cut but the removed ones and the
  measurements and generated output, unchanged."
  [ctx removed?]
  (k/check-exit ctx 0)
  (kept-unchanged ctx "spec-3" #(not (or (removed? %) (measured? %) (generated? %)))))

;; ---------------------------------------------------------------------------
;; Only from a cut

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx]
    (let [ctx (k/project! ctx)]
      (k/commit! (:dir ctx) "not a cut")
      ctx)))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" "HEAD" "only a cut")
    (assert (not (fs/exists? (fs/path (room ctx) ".git"))))
    ctx))

;; ---------------------------------------------------------------------------
;; The cut minus what is denied

(defstep #"the configuration denies the implementation, the measurements dhf/cycles and the generated site, and keeps every other path"
  (fn [ctx] (assoc ctx ::denies true)))

(defstep #"the cut also holds dhf/cycles/01/score.edn"
  (fn [ctx] (fence-project! ctx {:extra {"dhf/cycles/01/score.edn" "{:cycle \"01\"}\n"}})))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [commits (str/split-lines (k/git (room ctx) "rev-list" "--all"))]
      (assert (= 1 (count commits)) (pr-str commits))
      (assert (= 1 (count (str/split (k/git (room ctx) "rev-list" "--parents" "-n" "1" "HEAD") #" ")))))
    ctx))

(defstep #"its working tree holds every file committed at the cut that is not denied, unchanged, and no other file"
  (fn [ctx] (kept-unchanged ctx "spec-3" #(not (or (impl? %) (measured? %) (generated? %))))))

(defstep #"no object reachable in it belongs to a denied path"
  (fn [ctx]
    (let [objects (str/split-lines (k/git (room ctx) "rev-list" "--objects" "--all"))
          paths (keep #(second (str/split % #" " 2)) objects)
          denied-blobs (set (for [p (committed ctx "spec-3") :when (or (impl? p) (measured? p) (generated? p))]
                              (k/git (:dir ctx) "rev-parse" (str "spec-3:" p))))]
      (assert (not-any? #(or (impl? %) (measured? %) (generated? %)) paths) (pr-str paths))
      (assert (not-any? #(denied-blobs (first (str/split % #" "))) objects)))
    ctx))

(defstep #"a project whose working tree holds an untracked file notes.md and a file git ignores, beside its commit tagged spec-3"
  (fn [ctx]
    (let [ctx (fence-project! ctx {:extra {".gitignore" "secret.txt\n"}})]
      (k/write-files! (:dir ctx) {"notes.md" "mine\n" "secret.txt" "ignored\n"})
      ctx)))

(defstep #"ROOM holds neither file"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not (fs/exists? (fs/path (room ctx) "notes.md"))))
    (assert (not (fs/exists? (fs/path (room ctx) "secret.txt"))))
    ctx))

(defstep #"a project whose commit tagged spec-3 holds dhf/wiki/pages/copy.md with the same bytes as an implementation file"
  (fn [ctx] (fence-project! ctx {:extra {"dhf/wiki/pages/copy.md" (get (code-files) "src/rdm_spec/spec/parser.clj")}})))

(defstep #"it exits 1, names dhf/wiki/pages/copy.md and the implementation file, and builds no room"
  (fn [ctx]
    (k/check-exit ctx 1)
    (k/err-line-with ctx "dhf/wiki/pages/copy.md" "src/rdm_spec/spec/parser.clj")
    (assert (not (fs/exists? (fs/path (room ctx) ".git"))))
    ctx))

;; ---------------------------------------------------------------------------
;; Every committed path is denied or kept

(defstep #"a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes.md"
  (fn [ctx]
    (let [ctx (k/project! ctx {:config {:clean-room {:deny {:implementation true :measurements ["dhf/cycles"]} :keep ["dhf/spec"]}}
                               :extra {"notes.md" "notes\n"}})]
      (k/commit! (:dir ctx) "notes")
      ctx)))

(defstep #"it exits 1 with a finding naming (notes.md|dhf/scratch) as neither denied nor kept"
  (fn [ctx p]
    (k/check-exit ctx 1)
    (k/err-line-with ctx p "neither denied nor kept")))

(defstep #"a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch"
  (fn [ctx]
    (let [ctx (k/project! ctx {:config {:clean-room {:deny {:implementation true :measurements ["dhf/cycles"]}
                                                     :keep ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki"]}}
                               :extra {"dhf/scratch/x.md" "scratch\n"}})]
      (k/commit! (:dir ctx) "scratch")
      ctx)))

;; ---------------------------------------------------------------------------
;; Scopes

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx] (fence-project! ctx)))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx] (others-kept ctx impl?)))

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx] (fence-project! ctx)))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj"
  (fn [ctx] (fence-project! ctx)))

(defn of-context [c] #(or (str/starts-with? % (str "src/rdm_spec/" c "/")) (str/starts-with? % (str "test/rdm_spec/" c "/"))
                          (str/starts-with? % (str "test/steps/" c "_"))))
(defn of-component [c n] #(#{(str "src/rdm_spec/" c "/" n ".clj") (str "test/rdm_spec/" c "/" n "_test.clj")
                              (str "test/steps/" c "_" n ".clj")} %))

(defstep #"ROOM holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/"
  (fn [ctx] (k/check-exit ctx 0) (assert (not-any? (of-context "verification") (tree (room ctx))))
    (assoc ctx ::removed (of-context "verification"))))

(defstep #"ROOM holds neither src/rdm_spec/(\w+)/(\w+).clj nor test/rdm_spec/\w+/\w+_test.clj"
  (fn [ctx c n] (k/check-exit ctx 0) (assert (not-any? (of-component c n) (tree (room ctx)))) (assoc ctx ::removed (of-component c n))))

(defstep #"ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser"
  (fn [ctx]
    (k/check-exit ctx 0)
    (let [removed #(or ((of-context "knowledge") %) ((of-context "publishing") %) ((of-component "spec" "parser") %))]
      (assert (not-any? removed (tree (room ctx))))
      (assoc ctx ::removed removed))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (k/check-exit ctx 0)
    (kept-unchanged ctx (::cut ctx "spec-3") #(not (or ((::removed ctx) %) (measured? %) (generated? %))))))

(defstep #"the cut spec-4's model no longer names ratification/ratify, and the cut still holds src/rdm_spec/ratification/ratify.clj and test/rdm_spec/ratification/ratify_test.clj"
  (fn [ctx]
    (let [ctx (fence-project! ctx {:extra {"src/rdm_spec/ratification/ratify.clj" "(ns rdm-spec.ratification.ratify)\n"
                                           "test/rdm_spec/ratification/ratify_test.clj" "(ns rdm-spec.ratification.ratify-test)\n"}})]
      (k/tag-cut! ctx "spec-4")
      (assoc ctx ::cut "spec-4"))))

(defstep #"ROOM holds neither file, and its result names ratification/ratify as a removed component"
  (fn [ctx]
    (k/check-exit ctx 0)
    (assert (not-any? (of-component "ratification" "ratify") (tree (room ctx))))
    (assert (str/includes? (:out ctx) "ratification/ratify") (:out ctx))
    (assoc ctx ::removed (of-component "ratification" "ratify"))))

(defstep #"it exits 2 and names archive and the model's bounded contexts and kernel"
  (fn [ctx]
    (k/check-exit ctx 2)
    (k/err-line-with ctx "cannot-run:" "archive" "verification" "spec" "knowledge" "publishing" "kernel")
    (assert (not (fs/exists? (fs/path (room ctx) ".git"))))
    ctx))

(defstep #"so does a component the model does not name whose files the code layout finds none of"
  (fn [ctx]
    (let [r (k/rdm-line ctx "rdm-spec fence ROOM2 spec-3 --component archive/old")]
      (k/check-exit r 2)
      (k/err-line-with r "cannot-run:" "archive/old"))
    ctx))
