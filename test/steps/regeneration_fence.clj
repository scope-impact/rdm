(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature. Each scenario
  builds a throwaway repository holding a copy of the room's allowed paths, an
  implementation and other files, with history (steps.regeneration-support's
  fence-repo!), and a ROOM path that does not exist yet, in a fresh temporary
  directory. The shared `\"rdm-spec ...\" is run` step runs the command in
  the repository (:dir) with ROOM filled from :room."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.regeneration-support :as g]
            [steps.support :as s]))

(defn- needs-fence! [] (s/needs-built! 'rdm-spec.regeneration.fence))

(def ^:private extra-implementation
  "Implementation files added to the fixture's, so every scope removes
  something real: the kernel's bus, and step definitions of a component."
  {"src/rdm_spec/kernel/bus.clj" "(ns rdm-spec.kernel.bus)\n"
   "test/rdm_spec/kernel/bus_test.clj" "(ns rdm-spec.kernel.bus-test)\n"
   "test/steps/kernel_bus.clj" "(ns steps.kernel-bus)\n"
   "test/steps/spec_parser.clj" "(ns steps.spec-parser)\n"})

(defn- fixture!
  "A fence fixture (see steps.regeneration-support/fence-repo!) with the extra
  implementation files, and a ROOM that does not exist yet."
  [ctx & [opts]]
  (needs-fence!)
  (let [repo (with-redefs [g/implementation-files (merge g/implementation-files extra-implementation)]
               (g/fence-repo! (or opts {})))]
    (assoc ctx :dir (:dir repo) :repo repo
           :room (str (fs/file (s/tmp-dir) "room")))))

(defn- cut-config
  "The harness configuration as the cut spec-3 of the fixture holds it."
  [ctx]
  (edn/read-string (g/git! (:dir ctx) "show" "spec-3:dhf/harness/config.edn")))

(defn- the-run [ctx] (or (:run ctx) (s/fail! "no command was run")))

(defn- passed! [ctx]
  (let [r (the-run ctx)]
    (s/check! (zero? (:exit r)) (str "the fence did not pass\n" (s/show r)))
    r))

(defn- refused-without-room!
  "The run exited 2, printed nothing on standard output and made no room."
  [ctx]
  (let [r (the-run ctx)]
    (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
    (s/check! (str/blank? (:out r)) (str "a refusal printed a result\n" (s/show r)))
    (s/check! (not (fs/exists? (:room ctx))) (str "a refused fence left a room at " (:room ctx)))
    r))

(defn- under? [path prefix]
  (or (= path prefix) (str/starts-with? path (str (str/replace prefix #"/+$" "") "/"))))

(defn- implementation? [config path]
  (some #(under? path %) (:implementation config)))

(defn- cut-files
  "{path blob} of every file at the cut."
  [ctx]
  (g/tree-entries (:dir ctx) "spec-3" ["."]))

(defn- scope-prefixes
  "The code-layout prefixes the scope given on the command line removes."
  [config args]
  (let [opt (fn [k] (some (fn [[a b]] (when (= a k) b)) (partition 2 1 args)))
        fill (fn [tmpl ctx comp]
               (-> tmpl
                   (str/replace "{context}" (str/replace (or ctx "") "-" "_"))
                   (str/replace "{component}" (str/replace (or comp "") "-" "_"))))]
    (concat
     (for [c (some-> (opt "--context") (str/split #","))
           t (get-in config [:code-layout :context])]
       (fill t c nil))
     (for [cc (some-> (opt "--component") (str/split #","))
           :let [[c n] (str/split cc #"/" 2)]
           t (get-in config [:code-layout :component])]
       (fill t c n)))))

;; --- @spec-0037 --------------------------------------------------------------

(defstep #"the commit 1a2b3c4 carries no spec-N tag"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          {:keys [dir head-sha]} (:repo ctx)]
      ;; 1a2b3c4 names the commit after the cut, which no tag marks a cut
      (g/git! dir "branch" "1a2b3c4" head-sha)
      (s/check! (empty? (filter #(re-matches #"spec-\d+" %) (g/tags-at dir head-sha)))
                "the fixture's commit carries a spec-N tag")
      (assoc ctx :named-sha head-sha))))

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (let [r (refused-without-room! ctx)]
      (s/check! (re-find #"(?i)regeneration reads only a cut" (:err r))
                (str "the refusal does not say regeneration reads only a cut\n" (s/show r)))
      (s/check! (str/includes? (:err r) "1a2b3c4") (str "the refusal does not name 1a2b3c4\n" (s/show r)))
      (s/check! (str/includes? (:err r) (subs (:named-sha ctx) 0 7))
                (str "the refusal does not name the commit 1a2b3c4 resolves to\n" (s/show r))))))

;; --- @spec-0038 --------------------------------------------------------------

(defstep #"the configuration allows ([^,]+(?:, [^,]+)+ and \S+)"
  (fn [ctx listed]
    (let [ctx (fixture! ctx)
          allowed (vec (str/split listed #",\s*|\s+and\s+"))
          configured (get-in (cut-config ctx) [:clean-room :allow])]
      (s/check! (= (set allowed) (set configured))
                (str "the cut's configuration allows " (pr-str configured) ", not " (pr-str allowed)))
      (assoc ctx :allowed configured))))

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (passed! ctx)
    (let [room (:room ctx)
          commits (g/lines (g/git! room "rev-list" "--all"))
          roots (g/lines (g/git! room "rev-list" "--max-parents=0" "--all"))
          refs (g/lines (g/git! room "for-each-ref" "--format=%(refname)"))]
      (s/check! (= 1 (count commits)) (str "ROOM holds " (count commits) " commits: " (pr-str commits)))
      (s/check! (= commits roots) "ROOM's one commit is not a root commit")
      (s/check! (= 1 (count refs)) (str "ROOM holds more than one ref: " (pr-str refs)))
      (s/check! (= (first commits) (g/git! room "rev-parse" "HEAD")) "ROOM's HEAD is not its one commit")
      (s/check! (str/blank? (g/git! room "status" "--porcelain" "--untracked-files=all"))
                (str "ROOM's working tree differs from its commit:\n" (g/git! room "status" "--porcelain"))))))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (let [room (:room ctx)
          at-cut (g/tree-entries (:dir ctx) "spec-3" (:allowed ctx))
          files (g/room-files room)]
      (s/check! (seq at-cut) "the cut holds none of the allowed paths")
      (s/check! (= (sort (keys at-cut)) files)
                (str "ROOM holds other files than the allowed paths at the cut; extra: "
                     (pr-str (remove (set (keys at-cut)) files))
                     ", missing: " (pr-str (remove (set files) (keys at-cut)))))
      (doseq [[path blob] at-cut]
        (s/check! (= blob (g/hash-of room path)) (str path " in ROOM differs from the cut")))
      ;; modes too: what ROOM committed is what the cut holds
      (s/check! (= (g/git! (:dir ctx) "ls-tree" "-r" "--full-tree" "spec-3" "--" "dhf/harness/run.sh")
                   (g/git! room "ls-tree" "-r" "HEAD" "--" "dhf/harness/run.sh"))
                "the executable file's mode changed")
      (s/check! (fs/executable? (fs/file room "dhf/harness/run.sh")) "the executable file is not executable in ROOM")
      (s/check! (= (g/tree-entries (:dir ctx) "spec-3" (:allowed ctx)) (g/tree-entries room "HEAD" ["."]))
                "ROOM's commit holds other blobs than the allowed paths at the cut"))))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (let [room (:room ctx)
          {:keys [secret-blob]} (:repo ctx)
          allowed-blobs (set (vals (g/tree-entries (:dir ctx) "spec-3" (:allowed ctx))))
          ;; every object the room's database holds, reachable or not
          objects (for [l (g/lines (g/git! room "cat-file" "--batch-all-objects" "--batch-check"))
                        :let [[sha type] (str/split l #" ")]]
                    [sha type])
          blobs (set (keep (fn [[sha type]] (when (= "blob" type) sha)) objects))
          reachable (set (map #(first (str/split % #" ")) (g/lines (g/git! room "rev-list" "--objects" "--all"))))]
      (s/check! (= allowed-blobs blobs)
                (str "ROOM holds blobs of other paths: " (pr-str (remove allowed-blobs blobs))))
      (s/check! (every? (set (map first objects)) reachable) "an object reachable in ROOM is missing")
      (s/check! (not (zero? (:exit (g/git room "cat-file" "-e" (:cut-sha (:repo ctx)))))) "ROOM holds the cut's commit")
      (s/check! (not (zero? (:exit (g/git room "cat-file" "-e" secret-blob))))
                "ROOM holds the previous implementation's blob")
      (s/check! (empty? (filter #(= "commit" (second %)) (remove #(= (g/git! room "rev-parse" "HEAD") (first %)) objects)))
                "ROOM holds a commit besides its one root commit"))))

;; --- @spec-0040 --------------------------------------------------------------

(defstep #"the configuration allows (\S+) and the cut holds no (\S+)"
  (fn [ctx allowed missing]
    (s/check! (= allowed missing) "the step names two different paths")
    (let [ctx (fixture! ctx {:without [missing]})]
      (s/check! (some #{allowed} (get-in (cut-config ctx) [:clean-room :allow]))
                (str "the cut's configuration does not allow " allowed))
      (s/check! (empty? (g/tree-entries (:dir ctx) "spec-3" [missing])) (str "the cut holds " missing))
      ctx)))

(defstep #"it exits 2 and names (dhf/\S+)"
  (fn [ctx path]
    (let [r (refused-without-room! ctx)]
      (s/check! (str/includes? (:err r) path) (str "the refusal does not name " path "\n" (s/show r))))))

;; --- @spec-0182, @spec-0185, @spec-0222 -----------------------------------------

(defstep #"a project whose commit tagged spec-3 holds an implementation"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          config (cut-config ctx)]
      (s/check! (seq (filter #(implementation? config %) (keys (cut-files ctx))))
                "the cut holds no implementation")
      ctx)))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (passed! ctx)
    (let [config (cut-config ctx)
          held (filter #(implementation? config %) (g/room-files (:room ctx)))]
      (s/check! (empty? held) (str "ROOM holds implementation files: " (pr-str held)))
      (s/check! (= (sort (keys (g/tree-entries (:dir ctx) "spec-3" (get-in config [:clean-room :allow]))))
                   (g/room-files (:room ctx)))
                "ROOM holds other files than the allowed paths"))))

;; --- @spec-0183, @spec-0184 ----------------------------------------------------

(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          layout (get-in (cut-config ctx) [:code-layout :context])]
      (s/check! (every? (set layout) ["src/rdm_spec/{context}/" "test/rdm_spec/{context}/"])
                (str "the cut's code layout for a context is " (pr-str layout)))
      ctx)))

(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj"
  (fn [ctx]
    (let [ctx (fixture! ctx)
          layout (get-in (cut-config ctx) [:code-layout :component])]
      (s/check! (every? (set layout) ["src/rdm_spec/{context}/{component}.clj"
                                       "test/rdm_spec/{context}/{component}_test.clj"])
                (str "the cut's code layout for a component is " (pr-str layout)))
      ctx)))

(defn- absent-but-at-cut! [ctx paths]
  (passed! ctx)
  (let [at-cut (keys (cut-files ctx))
        files (g/room-files (:room ctx))]
    (doseq [p paths]
      (s/check! (some #(under? % p) at-cut) (str "the fixture's cut holds nothing at " p))
      (let [held (filter #(under? % p) files)]
        (s/check! (empty? held) (str "ROOM holds " (pr-str held)))))))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b] (absent-but-at-cut! ctx [a b])))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b] (absent-but-at-cut! ctx [a b])))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (let [r (passed! ctx)
          config (cut-config ctx)
          prefixes (scope-prefixes config (:args r))
          _ (s/check! (seq prefixes) "the command line names no scope")
          at-cut (into {} (filter (fn [[p _]] (implementation? config p)) (cut-files ctx)))
          kept (into {} (remove (fn [[p _]] (some #(str/starts-with? p %) prefixes)) at-cut))
          room (:room ctx)
          held (filter #(implementation? config %) (g/room-files room))]
      (s/check! (< (count kept) (count at-cut)) "the scope removes nothing at the cut")
      (s/check! (= (sort (keys kept)) (sort held))
                (str "ROOM's implementation is not the cut's other files; extra: "
                     (pr-str (remove (set (keys kept)) held)) ", missing: "
                     (pr-str (remove (set held) (keys kept)))))
      (doseq [[path blob] kept]
        (s/check! (= blob (g/hash-of room path)) (str path " in ROOM differs from the cut")))
      ;; and nothing else besides the allowed paths
      (let [allowed (g/tree-entries (:dir ctx) "spec-3" (get-in config [:clean-room :allow]))]
        (s/check! (= (sort (concat (keys allowed) (keys kept))) (g/room-files room))
                  "ROOM holds files that are neither allowed nor kept implementation")
        (s/check! (= 1 (count (g/lines (g/git! room "rev-list" "--all")))) "ROOM is not one commit")))))

;; --- @spec-0185 --------------------------------------------------------------

(defstep #"it exits 2 and names (\S+) and the model's bounded contexts and kernel"
  (fn [ctx scope]
    (let [r (refused-without-room! ctx)
          model (edn/read-string (g/git! (:dir ctx) "show" "spec-3:dhf/spec/model.edn"))
          contexts (for [c (:containers model) x (:contexts c)] (name (:id x)))]
      (s/check! (seq contexts) "the fixture's model has no bounded context")
      (s/check! (str/includes? (:err r) scope) (str "the refusal does not name " scope "\n" (s/show r)))
      (doseq [c (conj (vec contexts) "kernel")]
        (s/check! (re-find (re-pattern (str "\\b" c "\\b")) (:err r))
                  (str "the refusal does not name " c "\n" (s/show r)))))))
