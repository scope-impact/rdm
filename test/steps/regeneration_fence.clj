(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature: `rdm-spec fence`
  and `rdm-spec check` in a regeneration project (steps.regeneration-support):
  a copy of the room made a git repository on main, an implementation
  written from its own model when a step needs one, its commit tagged
  spec-3. ROOM is the kernel's: a new path that does not exist yet.

  Context keys set here: :dir, :cut-files ({rel text} of the cut), :untracked
  (paths a step wrote and never committed), :copy {:kept :of} (a kept file
  and the implementation file whose bytes it carries)."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.kernel-command :as kc]
            [steps.regeneration-support :as rs]
            [steps.support :as s]))

(defn- project!
  "A regeneration project, with an implementation when `impl?`."
  [impl?]
  (let [dir (rs/repo! (s/copy-of-room!))]
    (when impl? (rs/synthesize-implementation! dir))
    dir))

(defn- cut!
  "Commits the project and tags it spec-3: ctx with :dir and :cut-files."
  [ctx dir]
  (rs/commit! dir "the cut spec-3")
  (rs/tag! dir "spec-3")
  (assoc ctx :dir dir :cut-files (rs/files-at dir "spec-3")))

(defn- write-config! [dir f]
  (s/write! dir "dhf/harness/config.edn" (str (pr-str (f (kc/config-of dir))) "\n")))

(defn- room [ctx] (or (:room ctx) (s/fail! "no ROOM was named")))

(defn- room-files [ctx]
  (let [r (room ctx)]
    (s/check! (fs/directory? r) (str "no room was built at " r "\n" (s/show (kc/the-run ctx))))
    (rs/files-under r)))

(defn- built! [ctx] (kc/exits! ctx 0) (room-files ctx))

;; ---------------------------------------------------------------------------
;; A clean room is built only from a cut

(defstep #"the project's commit HEAD carries no spec-N tag"
  (fn [ctx]
    (let [dir (project! false)]
      (rs/commit! dir "a commit nobody cut")
      (s/check! (empty? (filter #(re-matches #"spec-\d+" %) (rs/tags dir))) "a spec-N tag exists")
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
    (let [dir (project! true)]
      (s/write! dir "site/index.html" "<html>a site an earlier publish left</html>\n")
      (write-config! dir (fn [c]
                           (assoc c :clean-room
                                  {:deny {:implementation true :measurements ["dhf/cycles"]
                                          :generated ["site" "site-src" "dhf/allure-results"]}
                                   :keep ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki" "dhf/views" "dhf/decisions"]})))
      (assoc ctx :dir dir))))

(defstep #"the cut also holds dhf/cycles/01/score\.edn"
  (fn [ctx]
    (let [dir (:dir ctx)]
      (s/write! dir "dhf/cycles/01/score.edn" "{:cycle \"01\" :items [] :passed 0 :scored 0}\n")
      (cut! ctx dir))))

(defn- denied? [ctx rel]
  (let [cfg (kc/config-of (:dir ctx))
        deny (get-in cfg [:clean-room :deny])]
    (boolean (or (and (:implementation deny) (some #(rs/under? rel %) (:implementation cfg)))
                 (some #(rs/under? rel %) (concat (:measurements deny) (:generated deny)))))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (built! ctx)
    (let [r (room ctx)
          commits (str/split-lines (rs/git! r "rev-list" "--all"))]
      (s/check! (= 1 (count commits)) (str "the room holds " (count commits) " commits"))
      (s/check! (= 1 (count (str/split (rs/git! r "rev-list" "--parents" "-n" "1" (first commits)) #" ")))
                "the room's commit has a parent")
      nil)))

(defstep #"its working tree holds every file committed at the cut that is not denied, unchanged, and no other file"
  (fn [ctx]
    (let [got (built! ctx)
          want (into (sorted-map) (remove (fn [[r _]] (denied? ctx r)) (:cut-files ctx)))]
      (s/check! (some #(denied? ctx %) (keys (:cut-files ctx))) "the cut holds no denied file")
      (s/check! (= (set (keys want)) (set (keys got)))
                (str "the room's files differ: extra " (pr-str (remove (set (keys want)) (keys got)))
                     ", missing " (pr-str (remove (set (keys got)) (keys want)))))
      (doseq [[r t] want] (s/check! (= t (get got r)) (str r " differs from the cut's")))
      nil)))

(defstep #"no object reachable in it belongs to a denied path"
  (fn [ctx]
    (built! ctx)
    (let [paths (keep #(second (str/split % #" " 2))
                      (str/split-lines (rs/git! (room ctx) "rev-list" "--objects" "--all")))
          bad (filter #(denied? ctx %) paths)]
      (s/check! (empty? bad) (str "reachable objects belong to denied paths: " (pr-str bad)))
      nil)))

(defstep #"a project whose working tree holds an untracked file notes\.md and a file git ignores, beside its commit tagged spec-3"
  (fn [ctx]
    (let [dir (project! false)]
      (s/write! dir ".gitignore" "ignored.txt\n")
      (let [ctx (cut! ctx dir)]
        (s/write! dir "notes.md" "notes nobody committed\n")
        (s/write! dir "ignored.txt" "a file git ignores\n")
        (s/check! (str/includes? (rs/git! dir "status" "--porcelain" "--ignored") "ignored.txt") "git does not see ignored.txt")
        (assoc ctx :untracked ["notes.md" "ignored.txt"])))))

(defstep #"ROOM holds neither file"
  (fn [ctx]
    (let [got (built! ctx)]
      (doseq [f (:untracked ctx)]
        (s/check! (not (contains? got f)) (str "the room holds " f)))
      nil)))

(defstep #"a project whose commit tagged spec-3 holds dhf/wiki/pages/copy\.md with the same bytes as an implementation file"
  (fn [ctx]
    (let [dir (project! true)
          of "src/rdm_spec/kernel/bus.clj"
          kept "dhf/wiki/pages/copy.md"]
      (s/check! (fs/exists? (fs/file dir of)) (str "the implementation holds no " of))
      (fs/copy (fs/file dir of) (fs/file dir kept) {:replace-existing true})
      (assoc (cut! ctx dir) :copy {:kept kept :of of}))))

(defstep #"it exits 1, names dhf/wiki/pages/copy\.md and the implementation file, and builds no room"
  (fn [ctx]
    (let [r (kc/exits! ctx 1)
          {:keys [kept of]} (:copy ctx)]
      (s/check! (some #(and (str/includes? % kept) (str/includes? % of)) (kc/finding-lines r))
                (str "no finding names " kept " and " of "\n" (s/show r)))
      (s/check! (not (fs/exists? (room ctx))) "a room was built")
      nil)))

;; ---------------------------------------------------------------------------
;; Every committed path is denied or kept

(defstep #"a project whose configuration denies the implementation and the measurements and keeps dhf/spec, and a committed top-level file notes\.md"
  (fn [ctx]
    (let [dir (project! false)]
      (write-config! dir (fn [c] (assoc c :clean-room {:deny {:implementation true :measurements ["dhf/cycles"]}
                                                       :keep ["dhf/spec" "dhf/harness" "dhf/raw" "dhf/wiki"
                                                              "dhf/views" "dhf/decisions"]})))
      (s/write! dir "notes.md" "notes, committed\n")
      (rs/commit! dir "notes")
      (assoc ctx :dir dir))))

(defstep #"a project whose configuration keeps dhf/spec, and a committed directory dhf/scratch"
  (fn [ctx]
    (let [dir (project! false)]
      (s/check! (some #{"dhf/spec"} (get-in (kc/config-of dir) [:clean-room :keep])) "the configuration keeps no dhf/spec")
      (s/write! dir "dhf/scratch/draft.md" "a draft, committed\n")
      (rs/commit! dir "scratch")
      (assoc ctx :dir dir))))

(defstep #"it exits 1 with a finding naming (\S+) as neither denied nor kept"
  (fn [ctx path]
    (let [r (kc/exits! ctx 1)]
      (s/check! (some #(and (str/starts-with? % (str path ": ")) (str/includes? % "neither denied nor kept"))
                      (kc/finding-lines r))
                (str "no finding names " path " as neither denied nor kept\n" (s/show r)))
      nil)))

;; ---------------------------------------------------------------------------
;; A clean room can rebuild the project, one bounded context or one component

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx] (cut! ctx (project! true))))

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx]
    (let [dir (project! true)
          lay (get-in (kc/config-of dir) [:code-layout :context])]
      (s/check! (every? (set lay) ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/"]) (str "the layout is " (pr-str lay)))
      (cut! ctx dir))))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>\.clj and its unit tests at test/rdm_spec/<context>/<component>_test\.clj"
  (fn [ctx]
    (let [dir (project! true)
          lay (get-in (kc/config-of dir) [:code-layout :component])]
      (s/check! (every? (set lay) ["src/rdm_spec/{context}/{component}.clj" "test/rdm_spec/{context}/{component}_test.clj"])
                (str "the layout is " (pr-str lay)))
      (cut! ctx dir))))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (let [got (built! ctx)
          impl (:implementation (kc/config-of (:dir ctx)))
          bad (filter (fn [r] (some #(rs/under? r %) impl)) (keys got))]
      (s/check! (seq (rs/implementation-files (kc/config-of (:dir ctx)) (:cut-files ctx))) "the cut holds no implementation")
      (s/check! (empty? bad) (str "the room holds " (pr-str bad)))
      nil)))

(defn- holds-none! [ctx prefixes]
  (let [got (built! ctx)
        bad (filter #(rs/removed? prefixes %) (keys got))]
    (s/check! (some #(rs/removed? prefixes %) (keys (:cut-files ctx))) "the cut holds nothing the scope removes")
    (s/check! (empty? bad) (str "the room holds " (pr-str bad)))
    nil))

(defstep #"ROOM holds no file under src/rdm_spec/verification/ or test/rdm_spec/verification/"
  (fn [ctx] (holds-none! ctx ["src/rdm_spec/verification/" "test/rdm_spec/verification/"])))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b] (holds-none! ctx [a b])))

(defstep #"ROOM holds no file of the bounded contexts knowledge and publishing or of the component spec/parser"
  (fn [ctx]
    (holds-none! ctx (rs/scope-prefixes (kc/config-of (:dir ctx))
                                        ["--context" "knowledge,publishing" "--component" "spec/parser"]))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [got (built! ctx)
          prefixes (rs/scope-prefixes (kc/config-of (:dir ctx)) (drop 3 (:args (kc/the-run ctx))))
          want (remove (fn [[r _]] (rs/removed? prefixes r))
                       (rs/implementation-files (kc/config-of (:dir ctx)) (:cut-files ctx)))]
      (s/check! (seq want) "the cut keeps no implementation file")
      (doseq [[r t] want]
        (s/check! (= t (get got r)) (str "the room does not hold " r " as the cut does")))
      nil)))

(defstep #"it exits 2 and names archive and the model's bounded contexts and kernel"
  (fn [ctx]
    (apply kc/refused-naming! ctx "archive" "kernel" (rs/context-names (rs/model-of (:dir ctx))))
    nil))
