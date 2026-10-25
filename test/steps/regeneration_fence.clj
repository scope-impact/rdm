(ns steps.regeneration-fence
  "Step definitions for dhf/spec/regeneration/fence.feature.

  Each Given builds a throwaway repository (steps.regeneration-support/
  fence-repo!): the room's allowed paths, an implementation and other files
  at the commit tagged spec-3, a previous implementation only in history, a
  commit after the cut and uncommitted changes, so a room built from anything
  but the cut shows. It sets :dir (where the shared When runs) and :room, a
  path in a new temporary directory that does not exist yet (ROOM).

  @spec-0182 has no Given: its When runs in a copy of the room's allow-list,
  which holds no cut. Its Then builds the same fixture and runs the When's
  command line there (see `with-cut-fixture`)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [rdm-spec.kernel.runner :refer [defstep]]
            [steps.regeneration-support :as g]
            [steps.support :as s]))

(defn- needs-fence! [] (s/needs-built! 'rdm-spec.regeneration.fence))

(defn- fresh-room [] (str (fs/file (s/tmp-dir) "room")))

(defn- fixture [ctx opts]
  (needs-fence!)
  (let [f (g/fence-repo! opts)]
    (assoc ctx :dir (:dir f) :fence f :room (fresh-room))))

(defn- config [] (s/room-config))

(defn- passed! [ctx]
  (let [r (:run ctx)]
    (s/check! r "no command was run")
    (s/check! (zero? (:exit r)) (str "fence did not pass\n" (s/show r)))))

(defn- refused! [ctx]
  (let [r (:run ctx)]
    (s/check! (= 2 (:exit r)) (str "expected exit 2\n" (s/show r)))
    r))

(defn- room-untouched! [ctx]
  (when (:fence ctx)
    (s/check! (not (fs/exists? (:room ctx))) (str "the refused fence wrote the room " (:room ctx)))))

(defn- implementation-files-at-cut
  "{path blob} of every implementation file at the cut of the fixture."
  [ctx]
  (let [{:keys [dir cut-sha]} (:fence ctx)]
    (g/tree-entries dir cut-sha (:implementation (config)))))

(defn- under? [p path]
  (let [p (str/replace p #"/+$" "")]
    (or (= p path) (str/starts-with? path (str p "/")))))

(defn- removed-prefixes
  "The prefixes the run's scope removes, from the configured :code-layout."
  [ctx]
  (let [args (:args (:run ctx))
        opt (fn [k] (second (drop-while #(not= k %) args)))
        layout (:code-layout (config))
        fill (fn [entries c n]
               (mapv #(-> % (str/replace "{context}" (str/replace c "-" "_"))
                          (str/replace "{component}" (str/replace (or n "") "-" "_")))
                     entries))]
    (cond
      (opt "--context") (fill (:context layout) (opt "--context") nil)
      (opt "--component") (let [[c n] (str/split (opt "--component") #"/")] (fill (:component layout) c n))
      :else nil)))

;; ---------------------------------------------------------------------------
;; Givens

;; @spec-0037
(defstep #"the commit 1a2b3c4 carries no spec-N tag"
  (fn [ctx]
    (let [ctx (fixture ctx {})
          dir (:dir ctx)]
      ;; 1a2b3c4 names a real commit, the one after the cut, which no spec-N tag is on
      (g/git! dir "branch" "1a2b3c4" (:head-sha (:fence ctx)))
      (s/check! (= (:head-sha (:fence ctx)) (g/git! dir "rev-parse" "1a2b3c4^{commit}")) "1a2b3c4 names no commit")
      (s/check! (empty? (filter #(re-matches #"spec-\d+" %) (g/tags-at dir "1a2b3c4")))
                "1a2b3c4 carries a spec-N tag")
      ctx)))

;; @spec-0038
(defstep #"the configuration allows ((?:[\w./-]+, )+[\w./-]+) and ([\w./-]+)"
  (fn [ctx listed last-one]
    (let [allowed (conj (str/split listed #", ") last-one)]
      (s/check! (= (set allowed) (set (get-in (config) [:clean-room :allow])))
                (str "the configuration allows " (pr-str (get-in (config) [:clean-room :allow]))))
      (fixture ctx {}))))

;; @spec-0040
(defstep #"the configuration allows dhf/wiki and the cut holds no dhf/wiki"
  (fn [ctx]
    (s/check! (some #{"dhf/wiki"} (get-in (config) [:clean-room :allow])) "the configuration does not allow dhf/wiki")
    (let [ctx (fixture ctx {:without ["dhf/wiki"]})
          {:keys [dir cut-sha]} (:fence ctx)]
      (s/check! (empty? (g/tree-entries dir cut-sha ["dhf/wiki"])) "the cut holds dhf/wiki")
      (s/check! (fs/exists? (fs/file dir "dhf/wiki")) "the fixture holds no dhf/wiki after the cut")
      ctx)))

;; @spec-0183
(defstep #"the code layout places context code at src/rdm_spec/<context>/ and its unit tests at test/rdm_spec/<context>/"
  (fn [ctx]
    (let [entries (get-in (config) [:code-layout :context])]
      (s/check! (and (some #{"src/rdm_spec/{context}/"} entries) (some #{"test/rdm_spec/{context}/"} entries))
                (str "the configured context layout is " (pr-str entries)))
      (fixture ctx {}))))

;; @spec-0184
(defstep #"the code layout places component code at src/rdm_spec/<context>/<component>.clj and its unit tests at test/rdm_spec/<context>/<component>_test.clj"
  (fn [ctx]
    (let [entries (get-in (config) [:code-layout :component])]
      (s/check! (and (some #{"src/rdm_spec/{context}/{component}.clj"} entries)
                     (some #{"test/rdm_spec/{context}/{component}_test.clj"} entries))
                (str "the configured component layout is " (pr-str entries)))
      (fixture ctx {}))))

;; ---------------------------------------------------------------------------
;; Refusals

(defstep #"it exits 2 and says regeneration reads only a cut"
  (fn [ctx]
    (let [r (refused! ctx)]
      (s/check! (str/includes? (:err r) "regeneration reads only a cut") (str "the refusal does not say so\n" (s/show r)))
      (s/check! (str/includes? (:err r) "1a2b3c4") (str "the refusal does not name 1a2b3c4\n" (s/show r)))
      (room-untouched! ctx))))

(defstep #"it exits 2 and names dhf/wiki"
  (fn [ctx]
    (let [r (refused! ctx)]
      (s/check! (str/includes? (:err r) "dhf/wiki") (str "the refusal does not name dhf/wiki\n" (s/show r)))
      (room-untouched! ctx))))

(defstep #"it exits 2 and names archive and the model's bounded contexts"
  (fn [ctx]
    (let [r (refused! ctx)
          model (edn/read-string (slurp (str (fs/file s/room-root "dhf/spec/model.edn"))))
          contexts (for [c (:containers model) x (:contexts c)] (name (:id x)))]
      (s/check! (seq contexts) "the model names no bounded context")
      (s/check! (re-find #"\barchive\b" (:err r)) (str "the refusal does not name archive\n" (s/show r)))
      (doseq [c contexts]
        (s/check! (re-find (re-pattern (str "\\b" c "\\b")) (str/replace (:err r) "archive" ""))
                  (str "the refusal does not name the bounded context " c "\n" (s/show r))))
      (room-untouched! ctx))))

;; ---------------------------------------------------------------------------
;; The room

(defstep #"ROOM is a repository with a single root commit"
  (fn [ctx]
    (passed! ctx)
    (let [room (:room ctx)]
      (s/check! (= "true" (g/git! room "rev-parse" "--is-inside-work-tree")) "ROOM is not a repository")
      (s/check! (= (str (fs/canonicalize room)) (str (fs/canonicalize (g/git! room "rev-parse" "--show-toplevel"))))
                "ROOM is not the top of its repository")
      (let [commits (g/lines (g/git! room "rev-list" "--all"))
            parents (str/split (g/git! room "rev-list" "--parents" "-n" "1" "HEAD") #" ")]
        (s/check! (= 1 (count commits)) (str "ROOM holds " (count commits) " commits"))
        (s/check! (= 1 (count parents)) "ROOM's commit has a parent"))
      (s/check! (empty? (g/all-tags room)) (str "ROOM holds tags " (pr-str (g/all-tags room))))
      (s/check! (= "" (g/git! room "status" "--porcelain")) "ROOM's working tree differs from its commit"))))

(defstep #"its working tree holds the allowed paths, unchanged, and no other file"
  (fn [ctx]
    (passed! ctx)
    (let [room (:room ctx)
          {:keys [dir cut-sha]} (:fence ctx)
          allow (get-in (config) [:clean-room :allow])
          expected (g/tree-entries dir cut-sha allow)
          files (g/room-files room)]
      (s/check! (seq expected) "the cut holds no allowed path")
      (s/check! (= (sort (keys expected)) files)
                (str "ROOM's files differ from the allowed paths at the cut; extra: "
                     (pr-str (remove (set (keys expected)) files)) " missing: "
                     (pr-str (remove (set files) (keys expected)))))
      (doseq [[path obj] expected]
        (s/check! (= obj (g/hash-of room path)) (str path " is not as it was at the cut")))
      ;; modes too: the executable script stays executable
      (s/check! (= (g/git! dir "ls-tree" "-r" "--full-tree" cut-sha "--" "dhf/harness/run.sh")
                   (g/git! room "ls-tree" "-r" "--full-tree" "HEAD" "--" "dhf/harness/run.sh"))
                "dhf/harness/run.sh lost its mode")
      (s/check! (.canExecute (fs/file room "dhf/harness/run.sh")) "dhf/harness/run.sh is not executable in ROOM"))))

(defstep #"no object reachable in it belongs to any other path"
  (fn [ctx]
    (passed! ctx)
    (let [room (:room ctx)
          {:keys [dir cut-sha secret-blob]} (:fence ctx)
          allow (get-in (config) [:clean-room :allow])
          allowed-blobs (set (vals (g/tree-entries dir cut-sha allow)))
          reachable (for [l (g/lines (g/git! room "rev-list" "--objects" "--all"))
                          :let [[obj path] (str/split l #" " 2)]]
                      [obj path])
          stored (for [l (g/lines (g/git! room "cat-file" "--batch-all-objects" "--batch-check"))
                       :let [[obj type] (str/split l #" ")]]
                   [obj type])]
      (doseq [[obj path] reachable :when (not (str/blank? path))]
        (s/check! (or (some #(under? % path) allow)
                      ;; a tree on the way to an allowed path
                      (some #(str/starts-with? % (str path "/")) allow))
                  (str "the object " obj " reachable in ROOM belongs to " path)))
      (doseq [[obj type] stored :when (= "blob" type)]
        (s/check! (contains? allowed-blobs obj) (str "ROOM stores the blob " obj ", of no allowed path")))
      (s/check! (not (zero? (:exit (g/git room "cat-file" "-e" secret-blob))) )
                "ROOM holds the previous implementation from history")
      (s/check! (not (zero? (:exit (g/git room "cat-file" "-e" cut-sha))))
                "ROOM holds the cut commit itself, and so its history"))))

;; @spec-0182: no Given, so the When ran where no cut is.
(defn- with-cut-fixture
  "The context with a fence run against a cut: as it is when a Given built the
  fixture, else the When's command line run again in a fixture with a cut
  spec-3 and a fresh ROOM (the scenario names no project, and the When alone
  runs in a copy of the room's allow-list, which is no repository)."
  [ctx]
  (if (:fence ctx)
    ctx
    (let [args (:args (:run ctx))
          ctx (fixture ctx {})
          args (assoc (vec args) 1 (:room ctx))]
      (s/check! (= "fence" (first args)) (str "the run was not a fence: " (pr-str args)))
      (assoc ctx :run (apply s/rdm-spec! (:dir ctx) args)))))

(defstep #"ROOM holds none of the configured implementation paths"
  (fn [ctx]
    (let [ctx (with-cut-fixture ctx)
          _ (passed! ctx)
          impl (:implementation (config))
          files (g/room-files (:room ctx))
          held (filter (fn [f] (some #(under? % f) impl)) files)]
      (s/check! (seq (implementation-files-at-cut ctx)) "the cut holds no implementation")
      (s/check! (seq files) "ROOM is empty")
      (s/check! (empty? held) (str "ROOM holds implementation files " (pr-str held)))
      ctx)))

(defstep #"ROOM holds no file under (\S+) or (\S+)"
  (fn [ctx a b]
    (passed! ctx)
    (let [files (g/room-files (:room ctx))
          held (filter #(or (str/starts-with? % a) (str/starts-with? % b)) files)
          at-cut (keys (implementation-files-at-cut ctx))]
      (s/check! (some #(str/starts-with? % a) at-cut) (str "the cut holds nothing under " a))
      (s/check! (some #(str/starts-with? % b) at-cut) (str "the cut holds nothing under " b))
      (s/check! (empty? held) (str "ROOM holds " (pr-str held))))))

(defstep #"ROOM holds neither (\S+) nor (\S+)"
  (fn [ctx a b]
    (passed! ctx)
    (let [at-cut (implementation-files-at-cut ctx)]
      (doseq [f [a b]]
        (s/check! (contains? at-cut f) (str "the cut holds no " f))
        (s/check! (not (fs/exists? (fs/file (:room ctx) f))) (str "ROOM holds " f))))))

(defstep #"it holds every other implementation file at the cut, unchanged"
  (fn [ctx]
    (passed! ctx)
    (let [room (:room ctx)
          prefixes (removed-prefixes ctx)
          _ (s/check! (seq prefixes) "the run names no scope")
          at-cut (implementation-files-at-cut ctx)
          kept (into {} (remove (fn [[p _]] (some #(str/starts-with? p %) prefixes)) at-cut))
          impl (:implementation (config))
          in-room (filter (fn [f] (some #(under? % f) impl)) (g/room-files room))]
      (s/check! (< (count kept) (count at-cut)) "the scope removes nothing")
      (s/check! (= (sort (keys kept)) (sort in-room))
                (str "ROOM's implementation differs from the cut's, less the scope; extra: "
                     (pr-str (remove (set (keys kept)) in-room)) " missing: "
                     (pr-str (remove (set in-room) (keys kept)))))
      (doseq [[path obj] kept]
        (s/check! (= obj (g/hash-of room path)) (str path " is not as it was at the cut"))))))
