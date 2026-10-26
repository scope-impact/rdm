(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature: `rdm-spec
  fence ROOM CUT` on a regeneration project (steps.regeneration-support)
  and `rdm-spec check`'s classification of committed paths.

  Context keys set here: :dir (the project), :files-at-cut ({rel text} of
  the cut's files), :impl (the implementation files written at the cut),
  :copy (the kept file carrying an implementation file's bytes) and
  :copied (that implementation file)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- update-config! [dir f]
  (s/write! dir "dhf/harness/config.edn" (str (pr-str (f (kc/config-of dir))) "\n")))

(defn- cut-project!
  "A copy of the room with an implementation, `edit!` applied, committed
  and tagged `tag` (spec-3 unless given)."
  ([ctx edit!] (cut-project! ctx edit! "spec-3"))
  ([ctx edit! tag]
   (let [dir (rs/repo! (s/copy-of-room!))
         impl (rs/synthesize-implementation! dir)]
     (edit! dir)
     (rs/commit! dir "the cut")
     (rs/tag! dir tag)
     (assoc ctx :dir dir :impl impl :files-at-cut (rs/files-at dir tag)))))

(defn- room [ctx] (or (:room ctx) (s/fail! "no ROOM was given")))

(defn- room-files [ctx] (rs/files-under (room ctx)))

(defn- cfg [ctx] (kc/config-of (:dir ctx)))

(defn- denied? [cfg rel]
  (let [d (get-in cfg [:clean-room :deny])]
    (some #(rs/under? rel %) (concat (when (:implementation d) (:implementation cfg))
                                     (:measurements d) (:generated d)))))

;; ---------------------------------------------------------------------------
;; A clean room is built only from a cut

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx]
    (let [dir (rs/repo! (s/copy-of-room!))]
      (rs/commit! dir "not a cut")
      (assoc ctx :dir dir))))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (kc/refused-naming! ctx "regeneration reads only a cut")
    (s/check! (not (fs/exists? (room ctx))) "a room was built")
    nil))

;; ---------------------------------------------------------------------------
;; A clean room is the cut minus what is denied

(defstep #"the configuration denies the implementation, the measurements dhf/cycles and the generated site, and keeps every other path"
  (fn [ctx]
    (let [c (kc/config-of s/room-root)]
      (s/check! (= {:implementation true :measurements ["dhf/cycles"]}
                   (select-keys (get-in c [:clean-room :deny]) [:implementation :measurements]))
                "the room's configuration does not deny the implementation and dhf/cycles")
      (s/check! (some #{"site"} (get-in c [:clean-room :deny :generated])) "the room's configuration does not deny the site")
      ctx)))

(defstep #"the cut also holds dhf/cycles/01/score\.edn"
  (fn [ctx]
    (cut-project! ctx (fn [dir]
                        (s/write! dir "dhf/cycles/01/score.edn" "{:cycle \"01\"}\n")
                        (s/write! dir "site/index.html" "<p>a generated site</p>\n")))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [revs (str/split-lines (rs/git! (room ctx) "rev-list" "--all"))]
      (s/check! (= 1 (count revs)) (str "ROOM holds " (count revs) " commits"))
      (s/check! (str/blank? (:out (rs/git-try (room ctx) "rev-parse" "-q" "--verify" "HEAD^"))) "the commit has a parent")
      nil)))

(defstep #"its working tree holds every file committed at the cut that is not denied, unchanged, and no other file"
  (fn [ctx]
    (let [c (cfg ctx)
          want (into (sorted-map) (remove #(denied? c (key %)) (:files-at-cut ctx)))
          have (room-files ctx)]
      (s/check! (contains? (:files-at-cut ctx) "dhf/cycles/01/score.edn") "the cut does not hold the score")
      (s/check! (= want have) (str "ROOM holds " (count have) " files, not " (count want)
                                   "; missing " (take 5 (remove (set (keys have)) (keys want)))
                                   ", extra " (take 5 (remove (set (keys want)) (keys have)))))
      nil)))

(defstep #"no object reachable in it belongs to a denied path"
  (fn [ctx]
    (let [c (cfg ctx)
          paths (remove str/blank? (str/split-lines (rs/git! (room ctx) "ls-tree" "-r" "--name-only" "HEAD")))
          objects (count (remove str/blank? (str/split-lines (rs/git! (room ctx) "rev-list" "--objects" "--all"))))]
      (s/check! (not-any? #(denied? c %) paths) (str "the room's commit holds " (first (filter #(denied? c %) paths))))
      (s/check! (pos? objects) "the room holds no object")
      (let [denied-blobs (set (for [[rel _] (:files-at-cut ctx) :when (denied? c rel)
                                    :let [b (rs/git! (:dir ctx) "rev-parse" (str "spec-3:" rel))]]
                                b))
            kept-blobs (set (for [[rel _] (:files-at-cut ctx) :when (not (denied? c rel))]
                              (rs/git! (:dir ctx) "rev-parse" (str "spec-3:" rel))))
            reachable (set (map #(first (str/split % #" ")) (str/split-lines (rs/git! (room ctx) "rev-list" "--objects" "--all"))))
            leaked (remove kept-blobs (filter reachable denied-blobs))]
        (s/check! (empty? leaked) (str "the room reaches the denied objects " (take 3 leaked))))
      nil)))

(defstep #"a project whose working tree holds an untracked file notes\.md and a file git ignores, beside its commit tagged spec-3"
  (fn [ctx]
    (let [ctx (cut-project! ctx (fn [_]))]
      (s/write! (:dir ctx) "notes.md" "untracked\n")
      (s/write! (:dir ctx) "dhf/spec/ignored.log" "ignored\n")
      (spit (str (fs/file (:dir ctx) ".gitignore")) "\n*.log\n" :append true)
      ctx)))

(defstep #"ROOM holds neither file"
  (fn [ctx]
    (kc/exits! ctx 0)
    (s/check! (not (fs/exists? (fs/file (room ctx) "notes.md"))) "ROOM holds notes.md")
    (s/check! (not (fs/exists? (fs/file (room ctx) "dhf/spec/ignored.log"))) "ROOM holds the ignored file")
    nil))

(defstep #"a project whose commit tagged spec-3 holds dhf/wiki/pages/copy\.md with the same bytes as an implementation file"
  (fn [ctx]
    (let [copied "src/rdm_spec/spec/parser.clj"]
      (assoc (cut-project! ctx (fn [dir] (fs/copy (fs/file dir copied) (fs/file dir "dhf/wiki/pages/copy.md"))))
             :copy "dhf/wiki/pages/copy.md" :copied copied))))

(defstep #"it exits 1, names dhf/wiki/pages/copy\.md and the implementation file, and builds no room"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/includes? % (:copy ctx)) (str/includes? % (:copied ctx))) (kc/finding-lines r))
                (str "no finding names both files\n" (s/show r)))
      (s/check! (not (fs/exists? (room ctx))) "a room was built")
      nil)))

;; ---------------------------------------------------------------------------
;; Every committed path is denied or kept

(defn- classified-project! [ctx keep extra]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (update-config! dir #(-> % (assoc-in [:clean-room :keep] keep)
                             (assoc-in [:clean-room :deny] {:implementation true :measurements ["dhf/cycles"]})))
    (doseq [[rel text] extra] (s/write! dir rel text))
    (rs/commit! dir "a commit")
    (assoc ctx :dir dir)))

(defstep #"a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes\.md"
  (fn [ctx] (classified-project! ctx ["dhf/spec"] {"notes.md" "notes\n"})))

(defstep #"a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch"
  (fn [ctx]
    (classified-project! ctx (vec (remove #{"dhf/scratch"} (concat (get-in (kc/config-of s/room-root) [:clean-room :keep]) ["dhf/spec"])))
                         {"dhf/scratch/a.md" "scratch\n"})))

(defstep #"it exits 1 with a finding naming (\S+) as neither denied nor kept"
  (fn [ctx p]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/starts-with? % (str p ": ")) (str/includes? % "neither denied nor kept"))
                      (kc/finding-lines r))
                (str "no finding names " p " as neither denied nor kept\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A clean room can rebuild the project, one bounded context or one component

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx] (cut-project! ctx (fn [_]))))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (kc/exits! ctx 0)
    (doseq [p (:implementation (cfg ctx))]
      (s/check! (not (fs/exists? (fs/file (room ctx) p))) (str "ROOM holds " p)))
    (s/check! (seq (:impl ctx)) "the cut holds no implementation")
    nil))

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx]
    (let [ctx (cut-project! ctx (fn [_]))]
      (s/check! (every? (set (get-in (cfg ctx) [:code-layout :context])) ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/"])
                "the code layout does not place context code so")
      ctx)))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>\.clj and its unit tests at test/rdm_spec/<context>/<component>_test\.clj"
  (fn [ctx]
    (let [ctx (cut-project! ctx (fn [_]))]
      (s/check! (every? (set (get-in (cfg ctx) [:code-layout :component]))
                        ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"])
                "the code layout does not place component code so")
      ctx)))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b]
    (kc/exits! ctx 0)
    (let [fs* (keys (room-files ctx))]
      (doseq [p [a b]]
        (s/check! (some #(str/starts-with? % p) (keys (:impl ctx))) (str "the cut holds nothing under " p))
        (s/check! (not-any? #(str/starts-with? % p) fs*) (str "ROOM holds a file under " p))))
    nil))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b]
    (kc/exits! ctx 0)
    (doseq [p [a b]]
      (s/check! (contains? (:impl ctx) p) (str "the cut does not hold " p))
      (s/check! (not (fs/exists? (fs/file (room ctx) p))) (str "ROOM holds " p)))
    nil))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [c (cfg ctx)
          prefixes (rs/scope-prefixes c (:args (kc/the-run ctx)))
          at-cut (rs/implementation-files c (:files-at-cut ctx))
          want (into (sorted-map) (remove #(rs/removed? prefixes (key %)) at-cut))
          have (rs/implementation-files c (room-files ctx))]
      (s/check! (seq prefixes) "the run names no scope")
      (s/check! (= want have) (str "ROOM's implementation differs from the cut's kept part: missing "
                                   (take 5 (remove (set (keys have)) (keys want))) ", extra "
                                   (take 5 (remove (set (keys want)) (keys have)))))
      nil)))

(defstep #"ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser"
  (fn [ctx]
    (kc/exits! ctx 0)
    (let [prefixes (rs/scope-prefixes (cfg ctx) ["--context" "knowledge,publishing" "--component" "spec/parser"])
          fs* (keys (room-files ctx))]
      (s/check! (some #(rs/removed? prefixes %) (keys (:impl ctx))) "the cut holds no file of those scopes")
      (s/check! (not-any? #(rs/removed? prefixes %) fs*) (str "ROOM holds " (first (filter #(rs/removed? prefixes %) fs*))))
      nil)))

(def ^:private removed-files ["src/rdm_spec/ratification/ratify.clj" "test/rdm_spec/ratification/ratify_test.clj"])

(defstep #"the cut spec-4's model no longer names ratification/ratify, and the cut still holds src/rdm_spec/ratification/ratify\.clj and test/rdm_spec/ratification/ratify_test\.clj"
  (fn [ctx]
    (let [ctx (cut-project! ctx (fn [dir] (doseq [f removed-files] (s/write! dir f (str ";; " f ", left by a removed component\n"))))
                            "spec-4")]
      (s/check! (not (str/includes? (slurp (str (fs/file (:dir ctx) "dhf/spec/model.edn"))) ":ratification/ratify"))
                "the model names ratification/ratify")
      (assoc ctx :impl (merge (:impl ctx) (zipmap removed-files (repeat "")))))))

(defstep #"ROOM holds neither file, and its result names ratification/ratify as a removed component"
  (fn [ctx]
    (let [r (kc/exits! ctx 0)]
      (doseq [f removed-files] (s/check! (not (fs/exists? (fs/file (room ctx) f))) (str "ROOM holds " f)))
      (s/check! (some #{:ratification/ratify "ratification/ratify"} (get-in (kc/stdout-edn r) [:fence :removed-components]))
                (str "the result names no removed component ratification/ratify\n" (s/show r)))
      nil)))

(defn- refused-naming-scopes! [ctx r word]
  (let [m (rs/model-of (:dir ctx))
        l (first (filter #(str/includes? % word) (kc/cannot-run-lines r)))]
    (s/check! (= 2 (:exit r)) (str "it exited " (:exit r) "\n" (s/show r)))
    (s/check! l (str "no cannot-run line names " word "\n" (s/show r)))
    (doseq [c (conj (rs/context-names m) "kernel")]
      (s/check! (re-find (re-pattern (str "\\b" c "\\b")) l) (str "the refusal does not name the context " c ": " l)))
    (s/check! (not (fs/exists? (room ctx))) "a room was built")))

(defstep #"it exits 2 and names archive and the model's bounded contexts and kernel"
  (fn [ctx] (refused-naming-scopes! ctx (kc/the-run ctx) "archive") nil))

(defstep #"so does a component the model does not name whose files the code layout finds none of"
  (fn [ctx]
    (let [r (kc/run! ctx (:dir ctx) ["fence" (str (room ctx)) "spec-3" "--component" "archive/ledger"])]
      (refused-naming-scopes! ctx r "archive/ledger")
      nil)))
